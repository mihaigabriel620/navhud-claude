package com.mihai.navhud.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.mihai.navhud.Man
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Draws the same turn arrow the Arduino draws, so the phone screen and the HUD
 * never disagree about which way you are going. Vector, not bitmaps, so it
 * scales to whatever the head unit's display happens to be.
 */
class ManeuverView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()

    var maneuver: Int = Man.STRAIGHT
        set(v) { if (field != v) { field = v; invalidate() } }
    var roundaboutExit: Int = 0

    /**
     * The real exit direction in degrees from the approach, or null to guess.
     *
     * This view and the HUD firmware carry the same seven-entry fallback table
     * and have to agree: they were once independent and disagreed on five of the
     * seven exits, including a sign flip where this view pointed down-left and
     * the panel pointed down-right. Two displays showing opposite turns for one
     * instruction is worse than either being a few degrees off. When a real
     * bearing arrives, both use it; when it does not, both guess the same way.
     */
    var roundaboutBearing: Int? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    var color: Int = Color.parseColor("#FF9D00")
        set(v) { if (field != v) { field = v; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 4f
        paint.color = color
        stroke.color = color
        text.color = color

        when (maneuver) {
            Man.ROUNDABOUT -> drawRoundabout(canvas, cx, cy, r)
            Man.ARRIVE -> {
                stroke.strokeWidth = r * 0.16f
                canvas.drawCircle(cx, cy, r * 0.62f, stroke)
                canvas.drawCircle(cx, cy, r * 0.24f, paint)
            }
            Man.UTURN -> drawUturn(canvas, cx, cy, r)
            else -> drawArrow(canvas, cx, cy, r, angleFor(maneuver))
        }
    }

    // -----------------------------------------------------------------------

    private fun angleFor(man: Int): Float = when (man) {
        Man.LEFT -> -90f
        Man.RIGHT -> 90f
        Man.SLIGHT_LEFT, Man.FORK_LEFT, Man.KEEP_LEFT,
        Man.RAMP_LEFT, Man.MERGE_LEFT -> -42f
        Man.SLIGHT_RIGHT, Man.FORK_RIGHT, Man.KEEP_RIGHT,
        Man.RAMP_RIGHT, Man.MERGE_RIGHT -> 42f
        Man.SHARP_LEFT -> -135f
        Man.SHARP_RIGHT -> 135f
        else -> 0f
    }

    private fun thick(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, w: Float) {
        val len = hypot(x1 - x0, y1 - y0)
        if (len < 0.01f) return
        paint.strokeWidth = w
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT
        c.drawLine(x0, y0, x1, y1, paint)
        paint.style = Paint.Style.FILL
    }

    private fun head(c: Canvas, cx: Float, cy: Float, angle: Float, r: Float, hw: Float) {
        val a = Math.toRadians(angle.toDouble())
        val ux = sin(a).toFloat(); val uy = -cos(a).toFloat()
        val tx = cx + ux * r; val ty = cy + uy * r
        val bx = cx + ux * (r - hw); val by = cy + uy * (r - hw)
        val px = -uy * hw * 0.62f; val py = ux * hw * 0.62f
        path.reset()
        path.moveTo(tx, ty); path.lineTo(bx + px, by + py); path.lineTo(bx - px, by - py)
        path.close()
        c.drawPath(path, paint)
    }

    private fun drawArrow(c: Canvas, cx: Float, cy: Float, r: Float, angle: Float) {
        val shaft = r * 0.28f
        val hw = r * 0.5f
        if (kotlin.math.abs(angle) < 1f) {
            thick(c, cx, cy + r, cx, cy - r + hw, shaft)
            head(c, cx, cy, 0f, r, hw)
            return
        }
        thick(c, cx, cy + r, cx, cy, shaft)
        val a = Math.toRadians(angle.toDouble())
        thick(c, cx, cy, cx + (sin(a) * (r - hw * 0.8)).toFloat(),
              cy - (cos(a) * (r - hw * 0.8)).toFloat(), shaft)
        c.drawCircle(cx, cy, shaft / 2, paint)
        head(c, cx, cy, angle, r, hw)
    }

    private fun drawRoundabout(c: Canvas, cx: Float, cy: Float, r: Float) {
        val ring = r * 0.46f
        val stub = r * 0.2f
        stroke.strokeWidth = r * 0.11f
        c.drawCircle(cx, cy - r * 0.08f, ring, stroke)
        thick(c, cx, cy + r, cx, cy - r * 0.08f + ring, stub)
        val ry = cy - r * 0.08f

        // Which way the exit points.
        //
        // The real bearing if the router gave one, otherwise the old guess from
        // the exit number. That table is a guess and worth naming as one: an
        // exit NUMBER is not an angle, and on a three-exit roundabout "exit 2"
        // is almost always dead ahead while the table says 30 degrees. It is
        // kept only for routers and steps that do not report a bearing, and the
        // firmware carries the identical table for the identical reason.
        //
        // Null means we have no idea which exit, either: Mapbox omits `exit` on
        // "roundabout turn" and "exit rotary" steps. Drawing index 0 there once
        // claimed the FIRST exit -- a hard right, with no digit to contradict it
        // -- so at a mini-roundabout where the route went left, the card pointed
        // right. A plain ring with no stub is the honest answer.
        val guess = floatArrayOf(100f, 30f, -30f, -80f, -120f, -150f, -170f)
        val bearing: Float? = roundaboutBearing?.toFloat()
            ?: if (roundaboutExit in 1..7) guess[roundaboutExit - 1] else null

        if (bearing != null) {
            // Keep the exit arrow off the road you came in on. With a real
            // bearing a roundabout that doubles you back can ask for exactly
            // 180, which lays the arrow on top of the entry stub and reads as
            // one line through a circle. The old table stopped at -170 for the
            // same reason.
            val aim = bearing.coerceIn(-170f, 170f)
            val a = Math.toRadians(aim.toDouble())
            thick(c, cx + (sin(a) * ring).toFloat(), ry - (cos(a) * ring).toFloat(),
                  cx + (sin(a) * ring * 1.6).toFloat(), ry - (cos(a) * ring * 1.6).toFloat(), stub)
            head(c, cx, ry, aim, ring * 2.2f, ring * 0.75f)

            // The number only when there is an arrow to put it beside. A digit
            // on a bare ring says which exit without saying where it is, and 8
            // and 9 used to print on top of the 7th exit's arrow.
            if (roundaboutExit in 1..12) {
                text.textSize = ring * 0.9f
                text.isFakeBoldText = true
                c.drawText(roundaboutExit.toString(), cx, ry + ring * 0.32f, text)
            }
        }
    }

    private fun drawUturn(c: Canvas, cx: Float, cy: Float, r: Float) {
        val rr = r * 0.34f
        val w = r * 0.24f
        stroke.strokeWidth = w
        stroke.strokeCap = Paint.Cap.BUTT
        val top = cy - r * 0.18f
        path.reset()
        path.addArc(cx - rr, top - rr, cx + rr, top + rr, 180f, 180f)
        c.drawPath(path, stroke)
        thick(c, cx + rr, top, cx + rr, top + r * 0.7f, w)
        // The returning leg has to reach the arrowhead. head() puts the
        // triangle's base at centre + (r0 - hw) = top + 0.78r, and this line
        // used to stop at top + 0.18r -- so the U-turn was drawn as a hook
        // with a triangle floating unattached six-tenths of a radius below it.
        head(c, cx - rr, top + r * 0.7f, 180f, r * 0.5f, r * 0.42f)
        thick(c, cx - rr, top, cx - rr, top + r * (0.7f + 0.5f - 0.42f), w)
    }
}
