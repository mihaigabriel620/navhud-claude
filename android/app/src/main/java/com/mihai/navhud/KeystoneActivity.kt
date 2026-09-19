package com.mihai.navhud

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mihai.navhud.hud.Keystone
import com.mihai.navhud.ui.KeystoneView

/**
 * Aim the picture at the windscreen.
 *
 * Two things get set here and both live in the display's own flash, not the
 * phone's: which way round the image goes, and a keystone correction for a dash
 * that is not level with the panel sitting on it.
 *
 * The board is the source of truth. This screen asks it what it is using
 * (`$GEOM?`), previews changes live (`$GEOM`, which writes nothing), and only
 * commits when you press Save (`$GEOMSAVE`, one flash erase). That split is not
 * fussiness: the ESP8266 emulates its EEPROM by erasing a 4 KB sector, and
 * committing on every movement of a slider would spend the chip's endurance in
 * an afternoon.
 */
class KeystoneActivity : AppCompatActivity() {

    private lateinit var preview: KeystoneView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var vBar: SeekBar
    private lateinit var hBar: SeekBar
    private lateinit var vLabel: TextView
    private lateinit var hLabel: TextView
    private lateinit var mirrorX: CheckBox
    private lateinit var mirrorY: CheckBox
    private lateinit var cornerButtons: List<Button>
    private lateinit var saveBtn: Button

    private var current = Keystone()
    private var sliderH = 0
    private var sliderV = 0

    /** True once a corner has been nudged by hand, so the sliders are stale. */
    private var custom = false

    private val ui = Handler(Looper.getMainLooper())
    private var lastSentAtMs = 0L
    private var pendingSend: Runnable? = null

    /**
     * True between asking the board what it is using and getting an answer.
     *
     * The board's flash is the record -- it survives being used with a
     * different phone -- so the screen has to start from what the board says,
     * not from this handset's cache. Until the reply lands, nothing is pushed:
     * pushing first and adopting after is how the previous version silently
     * overwrote a board that had been set up from another phone.
     */
    private var awaitingBoard = false
    private var askedAtMs = 0L
    private var boardSeenAtMs = 0L

    private val poll = object : Runnable {
        override fun run() {
            adoptFromBoard()
            refreshStatus()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContentView(R.layout.activity_keystone)

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        detail = findViewById(R.id.detail)
        vBar = findViewById(R.id.vKeystone)
        hBar = findViewById(R.id.hKeystone)
        vLabel = findViewById(R.id.vValue)
        hLabel = findViewById(R.id.hValue)
        mirrorX = findViewById(R.id.mirrorX)
        mirrorY = findViewById(R.id.mirrorY)
        saveBtn = findViewById(R.id.save)
        cornerButtons = listOf(
            findViewById(R.id.cornerTL), findViewById(R.id.cornerTR),
            findViewById(R.id.cornerBR), findViewById(R.id.cornerBL)
        )

        // Start from whatever was last seen on the board, then from what the
        // phone remembers, then from the sensible default for the mount.
        current = HudService.boardGeometry
            ?: Prefs.keystone(this)
            ?: Keystone(mirrorX = true, mirrorY = false)
        sliderH = Prefs.keystoneSliderH(this)
        sliderV = Prefs.keystoneSliderV(this)
        custom = Prefs.keystoneCustom(this)

        vBar.max = Keystone.SLIDER_MAX_X * 2
        hBar.max = Keystone.SLIDER_MAX_Y * 2
        vBar.progress = sliderV + Keystone.SLIDER_MAX_X
        hBar.progress = sliderH + Keystone.SLIDER_MAX_Y

        vBar.setOnSeekBarChangeListener(sliderListener { p ->
            sliderV = p - Keystone.SLIDER_MAX_X
            rebuildFromSliders()
        })
        hBar.setOnSeekBarChangeListener(sliderListener { p ->
            sliderH = p - Keystone.SLIDER_MAX_Y
            rebuildFromSliders()
        })

        mirrorX.setOnCheckedChangeListener { _, on ->
            if (current.mirrorX != on) { current = current.withMirror(x = on); push() }
        }
        mirrorY.setOnCheckedChangeListener { _, on ->
            if (current.mirrorY != on) { current = current.withMirror(y = on); push() }
        }

        cornerButtons.forEachIndexed { i, b ->
            b.setOnClickListener { preview.selected = i; markCorners() }
        }
        findViewById<View>(R.id.nudgeLeft).setOnClickListener { nudge(-STEP, 0) }
        findViewById<View>(R.id.nudgeRight).setOnClickListener { nudge(STEP, 0) }
        findViewById<View>(R.id.nudgeUp).setOnClickListener { nudge(0, -STEP) }
        findViewById<View>(R.id.nudgeDown).setOnClickListener { nudge(0, STEP) }

        findViewById<View>(R.id.reset).setOnClickListener {
            sliderH = 0; sliderV = 0; custom = false
            vBar.progress = Keystone.SLIDER_MAX_X
            hBar.progress = Keystone.SLIDER_MAX_Y
            current = current.reset()
            push()
        }
        saveBtn.setOnClickListener { save() }
        findViewById<View>(R.id.done).setOnClickListener { finish() }

        preview.onCornerMoved = { k ->
            current = k
            custom = true
            push()
        }
        preview.onCornerSelected = { markCorners() }

        preview.keystone = current
        markCorners()
        syncControls()
    }

    override fun onResume() {
        super.onResume()
        HudService.boardSaveResult = null
        // Remember what we had already seen, so only a *new* reply counts as
        // the answer to this question.
        boardSeenAtMs = HudService.boardGeometryAtMs
        askedAtMs = SystemClock.elapsedRealtime()
        awaitingBoard = HudService.sendToBoard(Keystone.testCommand(true)) &&
            HudService.sendToBoard(Keystone.queryCommand())
        // With no cable there is nothing to wait for, so show what we have.
        if (!awaitingBoard) push(force = true)
        ui.post(poll)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(poll)
        pendingSend?.let { ui.removeCallbacks(it) }
        pendingSend = null
        // Back to the drive display. The board also times out on its own after
        // two minutes, in case this never runs.
        HudService.sendToBoard(Keystone.testCommand(false))
        Prefs.setKeystone(this, current, sliderH, sliderV, custom)
    }

    // ---- editing -----------------------------------------------------------

    private fun sliderListener(onValue: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) onValue(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }

    /**
     * A slider movement rebuilds all four corners, discarding hand nudges.
     *
     * The alternative -- treating the sliders as an offset on top of the
     * corners -- means two sets of state for one shape, and no way to show the
     * driver which one is winning. One shape, one source, and the label says so.
     */
    private fun rebuildFromSliders() {
        current = Keystone.fromSliders(current.mirrorX, current.mirrorY, sliderH, sliderV)
        custom = false
        push()
    }

    private fun nudge(dx: Int, dy: Int) {
        val moved = current.nudge(preview.selected, dx, dy)
        if (moved == current) return
        current = moved
        custom = true
        push()
    }

    /**
     * Send the current shape to the board, at most every 60 ms.
     *
     * A SeekBar fires on every pixel of travel, which at 115200 baud with a
     * 50-character sentence is comfortably more than the cable can carry; the
     * board would then be rendering a shape the finger left a second ago.
     */
    private fun push(force: Boolean = false, fromBoard: Boolean = false) {
        // Any edit by the driver ends the wait: their hand beats a late reply.
        if (!fromBoard) awaitingBoard = false
        preview.keystone = current
        syncControls()
        pendingSend?.let { ui.removeCallbacks(it) }
        val now = SystemClock.elapsedRealtime()
        val wait = if (force) 0L else (SEND_GAP_MS - (now - lastSentAtMs)).coerceAtLeast(0L)
        val send = Runnable {
            lastSentAtMs = SystemClock.elapsedRealtime()
            pendingSend = null
            if (!current.isUsable()) {
                // Do not send a shape the firmware will refuse: it would drop
                // back to no correction, which looks like the screen ignoring
                // the control rather than rejecting the shape.
                refreshStatus()
                return@Runnable
            }
            HudService.sendToBoard(current.encode())
            refreshStatus()
        }
        pendingSend = send
        ui.postDelayed(send, wait)
    }

    private fun save() {
        if (!current.isUsable()) return
        HudService.boardSaveResult = null
        // Make sure the board has the shape before being told to keep it: the
        // preview send may still be sitting in the throttle window.
        pendingSend?.let { ui.removeCallbacks(it) }
        pendingSend = null
        lastSentAtMs = SystemClock.elapsedRealtime()
        val sent = HudService.sendToBoard(current.encode()) &&
            HudService.sendToBoard(Keystone.saveCommand())
        Prefs.setKeystone(this, current, sliderH, sliderV, custom)
        status.text = if (sent) getString(R.string.keystone_saving)
                      else getString(R.string.keystone_no_link)
    }

    // ---- display -----------------------------------------------------------

    /**
     * Take up what the board reports, once, at the start.
     *
     * After that the phone leads: this screen is where the edits happen, and a
     * reply arriving mid-drag must never move a corner out from under a finger.
     * `awaitingBoard` is cleared by the first user edit as well as by the
     * reply, so starting to adjust before the board answers is safe.
     */
    private fun adoptFromBoard() {
        if (!awaitingBoard) return
        val g = HudService.boardGeometry
        val at = HudService.boardGeometryAtMs
        if (g != null && at != boardSeenAtMs) {
            awaitingBoard = false
            boardSeenAtMs = at
            if (g != current) {
                current = g
                preview.keystone = current
            }
            // The board stores four corners, not two slider positions, so the
            // sliders are only meaningful if they would reproduce what came
            // back. When they would not, say so rather than showing positions
            // that do not describe the shape on the glass.
            custom = g != Keystone.fromSliders(g.mirrorX, g.mirrorY, sliderH, sliderV)
            push(force = true, fromBoard = true)
            return
        }
        if (SystemClock.elapsedRealtime() - askedAtMs > BOARD_REPLY_MS) {
            // Older firmware, or a board that did not answer. Fall back to what
            // this phone remembers, rather than leaving the screen showing one
            // thing and the glass another.
            awaitingBoard = false
            push(force = true)
        }
    }

    private fun markCorners() {
        cornerButtons.forEachIndexed { i, b -> b.isSelected = i == preview.selected }
    }

    private fun syncControls() {
        mirrorX.isChecked = current.mirrorX
        mirrorY.isChecked = current.mirrorY
        vLabel.text = describe(sliderV, "top", "bottom")
        hLabel.text = describe(sliderH, "left", "right")
        saveBtn.isEnabled = current.isUsable()
        refreshStatus()
    }

    private fun describe(v: Int, pos: String, neg: String) = when {
        v == 0 -> getString(R.string.keystone_none)
        v > 0 -> getString(R.string.keystone_narrow, pos, v)
        else -> getString(R.string.keystone_narrow, neg, -v)
    }

    private fun refreshStatus() {
        val linked = HudService.linkUp
        val saved = HudService.boardSaveResult      // read once: another thread writes it
        status.text = when {
            !linked -> getString(R.string.keystone_no_link)
            saved == "1" -> getString(R.string.keystone_saved)
            saved == "0" -> getString(R.string.keystone_saved_same)
            saved != null -> getString(R.string.keystone_save_failed, saved)
            !current.isUsable() -> getString(R.string.keystone_crossed)
            awaitingBoard -> getString(R.string.keystone_reading)
            else -> getString(R.string.keystone_live)
        }
        val bits = ArrayList<String>(3)
        bits += getString(R.string.keystone_area, current.areaPercent())
        bits += Keystone.CORNER_NAMES[preview.selected] +
            "  ${current.dx(preview.selected)}, ${current.dy(preview.selected)} px"
        if (custom) bits += getString(R.string.keystone_custom)
        detail.text = bits.joinToString("   ·   ")
    }

    companion object {
        private const val STEP = 4
        private const val SEND_GAP_MS = 60L
        /** How long to wait for a board to answer $GEOM? before giving up. */
        private const val BOARD_REPLY_MS = 1500L
    }
}
