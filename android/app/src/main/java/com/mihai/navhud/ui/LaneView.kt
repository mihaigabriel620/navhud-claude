package com.mihai.navhud.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.mihai.navhud.nav.Lane
import com.mihai.navhud.nav.LaneGuidance
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The lane strip: one cell per lane, left to right as you see them through the
 * windscreen, sitting under the instruction card.
 *
 * Three states, not two. The old version lit a lane amber if you could use it
 * and dimmed it otherwise, which is fine until a lane allows both "straight
 * on" and "exit" — then it drew two equally bright arrows and left the driver
 * to guess which one the route meant. The router knows: Mapbox sends
 * `active_direction` for exactly this. So:
 *
 *  - the movement to make: full amber, thick, on a lit cell;
 *  - the other movements that lane also allows: dim amber;
 *  - lanes that are not yours: grey, on the plain background.
 *
 * Lanes are never hidden. Knowing there are three lanes you must *not* be in
 * is what tells you how far across to move.
 */
class LaneView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private val active = Color.parseColor("#FF9D00")
    private val activeDim = Color.parseColor("#B98A2E")
    private val inactive = Color.parseColor("#5A5E64")
    private val cellLit = Color.parseColor("#2A1D06")
    private val divider = Color.parseColor("#241906")

    var guidance: LaneGuidance? = null
        set(v) {
            // Identity, not equality: LaneGuidance is rebuilt from the same
            // frame every tick, so equals() would still be a full comparison,
            // and the service hands out the *same* instance until the lanes
            // actually change.
            if (field === v) return
            field = v
            visibility = if (v == null) GONE else VISIBLE
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val g = guidance ?: return
        if (g.count == 0) return

        val w = width.toFloat() / g.count
        val h = height.toFloat()
        val cy = h * 0.52f
        val r = min(w, h) * 0.32f

        // Lit backing behind the usable lanes: at a glance you see the block of
        // lanes to be in before you read a single arrow.
        paint.style = Paint.Style.FILL
        paint.color = cellLit
        for (i in 0 until g.count) {
            if (!g.isActive(i)) continue
            canvas.drawRect(w * i, h * 0.06f, w * (i + 1), h * 0.94f, paint)
        }

        for (i in 0 until g.count) {
            val cx = w * (i + 0.5f)
            val on = g.isActive(i)
            val bits = g.lanes[i]
            val chosen = g.chosenOf(i)

            if (bits == 0) {
                arrow(canvas, cx, cy, r, 0f, if (on) active else inactive, on)
            } else {
                // Two or three arrows have to share one lane's width.
                val r2 = if (Lane.countBits(bits) > 1) r * 0.78f else r
                // Every arrow this lane carries, in Lane.ORDER -- left to
                // right, as the road is painted. The chosen one keeps its
                // place in that order; it used to be pulled out and appended
                // last, which put it in the *rightmost* slot whatever it was,
                // so a lane allowing straight-or-right with "straight" chosen
                // drew the dim right-turn arrow on the left and the bright
                // straight arrow on the right -- the reverse of the tarmac.
                val inLane = ArrayList<Int>(3)
                for (bit in Lane.ORDER) {
                    if (bits and bit == 0) continue
                    inLane.add(bit)
                }
                // Three arrows is all a lane cell has room for. Trim from the
                // far end but never drop the chosen one: a lane allowing
                // u-turn, left, straight and right with "right" chosen used to
                // lose its bright arrow entirely, leaving a lit cell with three
                // dim arrows and no movement named.
                while (inLane.size > 3) {
                    val victim =
                        if (inLane.last() != chosen) inLane.size - 1 else inLane.size - 2
                    inLane.removeAt(victim)
                }
                val slots = inLane.size
                val spread = if (slots <= 1) 0f else min(w * 0.30f, r2 * 0.70f)
                if (chosen != 0) {
                    // The chosen arrow is drawn last so it wins any overlap.
                    var slot = 0
                    for (bit in inLane) {
                        if (bit != chosen) {
                            arrow(canvas, cx + offset(slot, slots, spread), cy, r2 * 0.88f,
                                Lane.angleOf(bit), if (on) activeDim else inactive, false)
                        }
                        slot++
                    }
                    val ci = inLane.indexOf(chosen)
                    if (ci >= 0) {
                        arrow(canvas, cx + offset(ci, slots, spread), cy, r2,
                            Lane.angleOf(chosen), active, true)
                    }
                } else if (on) {
                    // Usable, but the router did not say which movement. Light
                    // them all rather than picking one.
                    var s = 0
                    for (bit in Lane.ORDER) {
                        if (bits and bit == 0) continue
                        arrow(canvas, cx + offset(s, slots, spread), cy, r2,
                            Lane.angleOf(bit), active, true)
                        if (++s >= slots) break
                    }
                }
            }

            if (i < g.count - 1) {
                paint.style = Paint.Style.STROKE
                paint.color = divider
                paint.strokeWidth = 1f
                canvas.drawLine(w * (i + 1), h * 0.18f, w * (i + 1), h * 0.82f, paint)
            }
        }

        // The carriageway is wider than the strip: say so.
        if (g.clippedLeft) ellipsis(canvas, w * 0.10f, cy)
        if (g.clippedRight) ellipsis(canvas, width - w * 0.10f, cy)
    }

    private fun offset(slot: Int, slots: Int, spread: Float): Float =
        if (slots <= 1) 0f else (slot - (slots - 1) / 2f) * spread

    private fun ellipsis(c: Canvas, x: Float, cy: Float) {
        paint.style = Paint.Style.FILL
        paint.color = inactive
        val d = max(1.5f, height * 0.045f)
        for (k in -1..1) c.drawCircle(x, cy + k * d * 3f, d, paint)
    }

    /**
     * @param thick the movement the route takes: filled, and drawn last
     *
     * Filled versus hollow, not bright amber versus dim amber.
     *
     * The dim arrow has to satisfy two things that pull in opposite
     * directions: it must be legible against the lane cell behind it, and it
     * must be clearly *not* the chosen arrow beside it. There is no amber that
     * does both. Measured: at 5.28:1 against the cell it is 1.50:1 against the
     * bright arrow; pull it down to 2.5:1 against the bright arrow and it is
     * 3.13:1 against the cell, under the floor. Colour is simply the wrong
     * channel for this distinction.
     *
     * So the alternatives are drawn hollow and the chosen one solid, which is
     * what road markings do and what every mature nav app does. It reads
     * instantly, it survives a dirty screen and low sun, and it does not
     * depend on telling two similar oranges apart at a glance.
     */
    private fun arrow(c: Canvas, cx: Float, cy: Float, r: Float, angle: Float, colour: Int, thick: Boolean) {
        paint.color = colour
        // The head is filled for the chosen movement and outlined otherwise.
        val headStyle = if (thick) Paint.Style.FILL else Paint.Style.STROKE
        val headStroke = r * 0.13f
        val shaft = r * (if (thick) 0.34f else 0.24f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = shaft
        paint.strokeCap = Paint.Cap.BUTT

        if (abs(angle) >= 170f) {
            // u-turn: stem, hook, head pointing back down
            val ky = cy - r * 0.25f
            c.drawLine(cx, cy + r, cx, ky, paint)
            path.reset()
            path.moveTo(cx, ky)
            path.quadTo(cx + r * 0.55f, ky - r * 0.55f, cx - r * 0.45f, ky - r * 0.10f)
            paint.strokeCap = Paint.Cap.ROUND
            c.drawPath(path, paint)
            paint.style = headStyle
            paint.strokeWidth = headStroke
            paint.strokeJoin = Paint.Join.MITER
            val hw = r * 0.42f
            path.reset()
            path.moveTo(cx - r * 0.45f, ky + r * 0.55f)
            path.lineTo(cx - r * 0.45f - hw, ky + r * 0.02f)
            path.lineTo(cx - r * 0.45f + hw, ky + r * 0.02f)
            path.close()
            c.drawPath(path, paint)
            return
        }

        val a = Math.toRadians(angle.toDouble())
        val ux = sin(a).toFloat()
        val uy = -cos(a).toFloat()
        if (angle == 0f) {
            // Straight ahead: the head's base sits at cy - 0.5r (tipR = r,
            // hw = 0.5r), and the shaft used to stop at cy. With Cap.BUTT
            // nothing bridges the half-radius between them, so the commonest
            // arrow of all was drawn as a bar with a triangle floating above
            // it. Turn arrows were fine, which is why it went unnoticed.
            c.drawLine(cx, cy + r, cx, cy - r * 0.45f, paint)
        } else {
            c.drawLine(cx, cy + r, cx, cy, paint)
        }
        if (angle != 0f) {
            c.drawLine(cx, cy, cx + ux * r * 0.55f, cy + uy * r * 0.55f, paint)
            // Fill the mitre. See the same fix in JunctionView.
            val old = paint.strokeCap
            paint.strokeCap = Paint.Cap.ROUND
            c.drawPoint(cx, cy, paint)
            paint.strokeCap = old
        }

        paint.style = headStyle
        paint.strokeWidth = headStroke
        paint.strokeJoin = Paint.Join.MITER
        val hw = r * 0.5f
        val tipR = if (angle == 0f) r else r * 0.95f
        val tx = cx + ux * tipR
        val ty = cy + uy * tipR
        val bx = cx + ux * (tipR - hw)
        val by = cy + uy * (tipR - hw)
        val px = -uy * hw * 0.6f
        val py = ux * hw * 0.6f
        path.reset()
        path.moveTo(tx, ty); path.lineTo(bx + px, by + py); path.lineTo(bx - px, by - py)
        path.close()
        c.drawPath(path, paint)
    }
}
