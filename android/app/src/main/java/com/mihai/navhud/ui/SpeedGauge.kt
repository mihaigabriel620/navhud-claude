package com.mihai.navhud.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Speed and speed limit as one instrument, the way Waze does it: a round dial
 * with the number in the middle and the limit roundel tucked into its shoulder.
 *
 * ## Why it replaced two separate boxes
 *
 * They were a pill and a roundel side by side, and they had two problems. The
 * limit vanished entirely when the map did not have one, which made the whole
 * cluster jump sideways; and the two numbers sat at the same size in the same
 * weight, so at a glance you could not tell which was which. Putting the limit
 * *on* the dial fixes both: it is smaller, it is a road sign rather than a
 * number, and its slot is always there whether or not it is filled.
 *
 * ## The ring
 *
 * The arc around the dial fills with speed, and its full scale is the limit
 * rather than some fixed 180 km/h — so "the ring is nearly closed" means the
 * same thing in a 30 zone and on the motorway. Past the limit it turns red and
 * keeps going round a short overrun band, which is the part you feel: at
 * 5 km/h over, a needle against a fixed scale has barely moved, while this has
 * changed colour and pushed visibly into the red.
 *
 * Nothing here blinks. A flashing speed reading is the sort of thing that
 * demos well and then, at night on a motorway, pulls your eyes off the road
 * every time you drift 2 km/h over.
 */
class SpeedGauge @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    companion object {
        /** Sweep of the dial's arc, degrees, starting bottom-left. */
        const val ARC_START = 130f
        const val ARC_SWEEP = 280f

        /** How far past the limit the red band runs before it saturates. */
        const val OVERRUN_FRACTION = 0.22f

        /** Matches FreeTracker/RouteTracker: 2 km/h of GPS slop is not speeding. */
        const val TOLERANCE_KPH = 2

        /** Wider than tall, by exactly the shoulder the roundel needs. */
        const val RATIO = 1.22f
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()

    private val colFace = Color.parseColor("#15171C")
    private val colRim = Color.parseColor("#2C3038")
    private val colTrack = Color.parseColor("#23262C")
    private val colAmber = Color.parseColor("#FF9D00")
    private val colRed = Color.parseColor("#FF4A32")
    private val colText = Color.WHITE
    private val colUnit = Color.parseColor("#7E858E")
    private val colSignFace = Color.parseColor("#F4F5F7")
    private val colSignRing = Color.parseColor("#D0021B")
    private val colSignText = Color.parseColor("#101114")

    /** -1 when there is no fix, so the dial reads "--" instead of 0. */
    var speedKph: Int = -1
        set(v) { if (field != v) { field = v; invalidate() } }

    /** 0 unknown, -1 derestricted, otherwise the limit in km/h. */
    var limitKph: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Set by the tracker rather than recomputed here, so both agree. */
    var over: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // Square dial plus the roundel hanging off its shoulder.
        val h = resolveSize((96 * resources.displayMetrics.density).toInt(), heightSpec)
        val w = resolveSize((h * RATIO).toInt(), widthSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // The dial sits bottom-right; the roundel occupies the top-left corner
        // it leaves free, which is why the view is wider than it is tall.
        val d = min(h, w / RATIO)
        val r = d * 0.46f
        val cx = w - r - d * 0.03f
        val cy = h - r - d * 0.03f

        // ---- face -----------------------------------------------------------
        p.style = Paint.Style.FILL
        p.color = colFace
        canvas.drawCircle(cx, cy, r, p)

        val ringW = r * 0.13f
        arc.set(cx - r + ringW * 0.6f, cy - r + ringW * 0.6f,
                cx + r - ringW * 0.6f, cy + r - ringW * 0.6f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = ringW
        p.strokeCap = Paint.Cap.ROUND
        p.color = colTrack
        canvas.drawArc(arc, ARC_START, ARC_SWEEP, false, p)

        val frac = fillFraction()
        if (frac > 0f) {
            p.color = if (over) colRed else colAmber
            canvas.drawArc(arc, ARC_START, ARC_SWEEP * frac, false, p)
        }

        p.strokeWidth = max(1f, r * 0.02f)
        p.color = colRim
        canvas.drawCircle(cx, cy, r - ringW * 1.25f, p)

        // ---- the number ------------------------------------------------------
        t.textAlign = Paint.Align.CENTER
        t.typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        t.textSize = r * 0.95f
        t.color = if (over) colRed else colText
        val label = if (speedKph < 0) "--" else speedKph.toString()
        // Shrink to fit rather than letting three digits run off the face --
        // "133" at the two-digit size overflowed the dial and collided with
        // the roundel, which is the one number on the screen you must be able
        // to read without thinking.
        val maxW = r * 1.34f
        val tw = t.measureText(label)
        if (tw > maxW) t.textSize = t.textSize * maxW / tw
        canvas.drawText(label, cx, cy + t.textSize * 0.32f, t)

        t.typeface = Typeface.DEFAULT
        t.textSize = r * 0.26f
        t.color = if (over) colRed else colUnit
        canvas.drawText("km/h", cx, cy + r * 0.66f, t)

        // ---- the limit roundel ----------------------------------------------
        drawRoundel(canvas, d * 0.25f, d * 0.25f, d * 0.24f)
    }

    /**
     * How much of the ring is filled. Full scale is the limit; the last fifth
     * of the sweep is the overrun band, so the ring is exactly at the limit
     * mark when you are exactly at the limit and there is still somewhere for
     * it to go when you are not.
     */
    private fun fillFraction(): Float {
        val v = speedKph
        if (v <= 0) return 0f
        val lim = limitKph
        val scale = when {
            lim > 0 -> lim.toFloat()
            else -> 130f              // unknown or derestricted: a sane full scale
        }
        val atLimit = 1f - OVERRUN_FRACTION
        val f = if (v <= scale) (v / scale) * atLimit
                else atLimit + ((v - scale) / (scale * 0.30f)) * OVERRUN_FRACTION
        return f.coerceIn(0f, 1f)
    }

    /**
     * A real sign: white face, red ring, black number. Hidden when the map has
     * no limit for this road — an empty roundel reads as "no limit here",
     * which is a different and much more dangerous claim.
     */
    private fun drawRoundel(c: Canvas, cx: Float, cy: Float, r: Float) {
        if (limitKph == 0) return

        // A halo in the background colour so the sign reads as sitting in
        // front of the dial rather than welded to it.
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.18f
        p.color = Color.parseColor("#07070A")
        c.drawCircle(cx, cy, r + r * 0.09f, p)

        p.style = Paint.Style.FILL
        p.color = colSignFace
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.26f
        // The end-of-restriction sign has no red on it at all: white disc,
        // grey slashes. A red ring around it would read as a limit.
        p.color = if (limitKph < 0) Color.parseColor("#5A5E66") else colSignRing
        c.drawCircle(cx, cy, r - r * 0.13f, p)

        t.textAlign = Paint.Align.CENTER
        t.color = colSignText
        if (limitKph < 0) {
            // Derestricted. Germany's sign for it is a white disc with grey
            // slashes and no number at all; the slashes are the sign.
            p.style = Paint.Style.STROKE
            p.strokeWidth = r * 0.10f
            p.color = Color.parseColor("#3A3D42")
            for (k in -1..1) {
                val o = k * r * 0.30f
                c.drawLine(cx - r * 0.42f + o, cy + r * 0.42f,
                           cx + r * 0.10f + o, cy - r * 0.42f, p)
            }
            return
        }
        // The limit roundel is a number, so it takes the numeric family
        // like every other number in the app.
        t.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        // Three digits have to fit in the same disc as two.
        val s = limitKph.toString()
        t.textSize = r * (if (s.length >= 3) 0.82f else 1.0f)
        c.drawText(s, cx, cy + t.textSize * 0.36f, t)
    }
}
