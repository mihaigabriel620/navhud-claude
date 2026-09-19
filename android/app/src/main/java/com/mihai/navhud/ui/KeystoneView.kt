package com.mihai.navhud.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.mihai.navhud.R
import com.mihai.navhud.hud.Homography
import com.mihai.navhud.hud.Keystone
import kotlin.math.hypot

/**
 * What the driver will see, drawn to scale.
 *
 * The panel is mirrored in hardware and the windscreen mirrors it back, so a
 * layout coordinate and a driver's-eye coordinate are the same thing -- which
 * means this preview is not mirrored either, and dragging the corner that looks
 * top-left moves the one that looks top-left on the glass. That is the whole
 * reason the mirror is a rotation in the panel rather than a transform in the
 * drawing code; see hud_geom.h.
 *
 * The pattern inside the quad is the same one the board puts up while this
 * screen is open, so the two can be compared directly.
 */
class KeystoneView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var keystone: Keystone = Keystone()
        set(v) { field = v; invalidate() }

    /** Which corner the nudge pad is pointed at, 0..3. */
    var selected: Int = Keystone.TOP_LEFT
        set(v) { field = v; invalidate() }

    /** Called when the user drags or taps a corner on the preview. */
    var onCornerMoved: ((Keystone) -> Unit)? = null
    var onCornerSelected: ((Int) -> Unit)? = null

    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = ctx.getColor(R.color.line_strong)
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = ctx.getColor(R.color.amber)
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = ctx.getColor(R.color.amber_dim)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ctx.getColor(R.color.amber_wash)
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.amber_dim)
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
    }
    private val warn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.danger)
        textSize = dp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val clipWarn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.amber_bright)
        textSize = dp(12f)
        textAlign = Paint.Align.CENTER
    }

    // Preallocated: onDraw runs on every frame of a drag, and a Path plus four
    // DoubleArrays per frame is garbage the compositor does not need.
    private val path = Path()
    private val a = DoubleArray(2)
    private val b = DoubleArray(2)
    private val qx = FloatArray(4)
    private val qy = FloatArray(4)

    private val amber = ctx.getColor(R.color.amber)
    private val amberBright = ctx.getColor(R.color.amber_bright)
    private val danger = ctx.getColor(R.color.danger)

    // Panel pixels -> view pixels.
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    private var dragging = -1
    private var grabDx = 0
    private var grabDy = 0

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        // The panel is 3:2. Reserve a margin so a corner dragged outward still
        // has somewhere to be drawn instead of being clipped by the view.
        val h = (w * Keystone.SCR_H / Keystone.SCR_W.toFloat()).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val pad = dp(22f)
        scale = minOf((w - pad * 2) / Keystone.SCR_W, (h - pad * 2) / Keystone.SCR_H)
        offX = (w - Keystone.SCR_W * scale) / 2f
        offY = (h - Keystone.SCR_H * scale) / 2f
    }

    private fun vx(px: Float) = offX + px * scale
    private fun vy(py: Float) = offY + py * scale

    override fun onDraw(c: Canvas) {
        // The panel itself: where there are pixels.
        c.drawRect(vx(0f), vy(0f), vx(Keystone.SCR_W.toFloat()),
            vy(Keystone.SCR_H.toFloat()), panel)

        val hg = Homography.of(keystone)
        val usable = keystone.isUsable() && hg != null
        edge.color = if (usable) amber else danger

        for (i in 0 until 4) {
            qx[i] = vx(keystone.quadX(i).toFloat())
            qy[i] = vy(keystone.quadY(i).toFloat())
        }

        path.rewind()
        path.moveTo(qx[0], qy[0])
        for (i in 1 until 4) path.lineTo(qx[i], qy[i])
        path.close()
        if (usable) c.drawPath(path, fill)
        c.drawPath(path, edge)

        if (hg != null) {
            // Thirds, the same grid the board draws.
            for (i in 1..2) {
                val x = Keystone.SCR_W * i / 3.0
                hg.map(x, 0.0, a); hg.map(x, Keystone.SCR_H.toDouble(), b)
                c.drawLine(vx(a[0].toFloat()), vy(a[1].toFloat()),
                    vx(b[0].toFloat()), vy(b[1].toFloat()), grid)
                val y = Keystone.SCR_H * i / 3.0
                hg.map(0.0, y, a); hg.map(Keystone.SCR_W.toDouble(), y, b)
                c.drawLine(vx(a[0].toFloat()), vy(a[1].toFloat()),
                    vx(b[0].toFloat()), vy(b[1].toFloat()), grid)
            }
            // Centre cross.
            hg.map(Keystone.SCR_W / 2.0 - 26, Keystone.SCR_H / 2.0, a)
            hg.map(Keystone.SCR_W / 2.0 + 26, Keystone.SCR_H / 2.0, b)
            c.drawLine(vx(a[0].toFloat()), vy(a[1].toFloat()),
                vx(b[0].toFloat()), vy(b[1].toFloat()), edge)
            hg.map(Keystone.SCR_W / 2.0, Keystone.SCR_H / 2.0 - 26, a)
            hg.map(Keystone.SCR_W / 2.0, Keystone.SCR_H / 2.0 + 26, b)
            c.drawLine(vx(a[0].toFloat()), vy(a[1].toFloat()),
                vx(b[0].toFloat()), vy(b[1].toFloat()), edge)
        }

        // Corner labels, inside the quad, matching what is on the glass.
        val inset = floatArrayOf(1f, -1f, -1f, 1f)
        val vinset = floatArrayOf(1f, 1f, -1f, -1f)
        for (i in 0 until 4) {
            val tx = qx[i] + inset[i] * dp(20f)
            val ty = qy[i] + vinset[i] * dp(20f) + dp(4f)
            c.drawText(CORNER_SHORT[i], tx, ty, label)
        }

        // Handles. The selected one is filled and bigger, because it is the one
        // the buttons below will move.
        for (i in 0 until 4) {
            val sel = i == selected
            handle.color = if (sel) amberBright else amber
            c.drawCircle(qx[i], qy[i], dp(if (sel) 9f else 6f), handle)
            if (sel) {
                handle.color = Color.BLACK
                c.drawCircle(qx[i], qy[i], dp(3.5f), handle)
            }
        }

        if (!usable) {
            c.drawText(context.getString(R.string.keystone_view_crossed),
                width / 2f, height - dp(4f), warn)
        } else if (keystone.clipsOffPanel()) {
            c.drawText(context.getString(R.string.keystone_view_clipped),
                width / 2f, height - dp(4f), clipWarn)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                var best = -1
                var bestD = dp(44f)
                for (i in 0 until 4) {
                    val d = hypot(e.x - vx(keystone.quadX(i).toFloat()),
                        e.y - vy(keystone.quadY(i).toFloat()))
                    if (d < bestD) { bestD = d; best = i }
                }
                if (best < 0) return false
                dragging = best
                selected = best
                onCornerSelected?.invoke(best)
                // Remember the offset between finger and corner so the corner
                // does not jump to the fingertip on the first move.
                grabDx = keystone.dx(best) - ((e.x - vx(Keystone.BASE_X[best].toFloat())) / scale).toInt()
                grabDy = keystone.dy(best) - ((e.y - vy(Keystone.BASE_Y[best].toFloat())) / scale).toInt()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging < 0) return false
                val wantX = ((e.x - vx(Keystone.BASE_X[dragging].toFloat())) / scale).toInt() + grabDx
                val wantY = ((e.y - vy(Keystone.BASE_Y[dragging].toFloat())) / scale).toInt() + grabDy
                val moved = keystone.nudge(dragging,
                    wantX - keystone.dx(dragging),
                    wantY - keystone.dy(dragging))
                if (moved != keystone) {
                    keystone = moved
                    onCornerMoved?.invoke(moved)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                dragging = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                // A tap on a corner selects it, which is a click as far as
                // accessibility services are concerned.
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private val CORNER_SHORT = arrayOf("TL", "TR", "BR", "BL")
    }
}
