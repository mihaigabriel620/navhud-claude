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
    // Invalidates like the rest: two roundabouts in a row with the same
    // maneuver kept the first one's digit until something else changed.
    var roundaboutExit: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Traffic keeps left (HudFrame.FLAG_LEFT_HAND): the ring runs clockwise. */
    var leftHand: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

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

    /**
     * The HUD's roundabout (arduino/NavHud/hud_arrows.h, roundaboutArt), drawn
     * with the same numbers so the card and the glass show the same picture: a
     * thick ring, the part you drive in [color], the rest dimmed, the exit
     * number in the hole and the arrow at the exit's real angle. Everything is
     * in the firmware's -60..60 glyph box;
     * [r] is 60 of its units.
     */
    private fun drawRoundabout(c: Canvas, cx: Float, cy0: Float, r: Float) {
        val u = r / 60f
        val cy = cy0 - RAB_UP * u
        val dim = Color.rgb((Color.red(color) * DIM).toInt(),
                            (Color.green(color) * DIM).toInt(),
                            (Color.blue(color) * DIM).toInt())
        fun px(deg: Float, rr: Float) = cx + (sin(Math.toRadians(deg.toDouble())) * rr * u).toFloat()
        fun py(deg: Float, rr: Float) = cy - (cos(Math.toRadians(deg.toDouble())) * rr * u).toFloat()
        val oval = android.graphics.RectF(cx - RAB_RMID * u, cy - RAB_RMID * u,
                                          cx + RAB_RMID * u, cy + RAB_RMID * u)
        stroke.strokeWidth = (RAB_R - RAB_RI) * u
        stroke.strokeCap = Paint.Cap.BUTT
        fun road(deg: Float, r1: Float, w: Float, col: Int) {
            stroke.strokeWidth = w * u
            stroke.strokeCap = Paint.Cap.ROUND
            stroke.color = col
            c.drawLine(px(deg, RAB_RM), py(deg, RAB_RM), px(deg, r1), py(deg, r1), stroke)
            stroke.strokeCap = Paint.Cap.BUTT
        }

        // Which way the exit points: the route's angle, else the old guess
        // from the exit number (the firmware's table, mirrored where traffic
        // keeps left), else nothing. That table is a guess and worth naming as
        // one: an exit NUMBER is not an angle. Null also covers Mapbox's
        // "roundabout turn" and "exit rotary", which carry no exit at all --
        // drawing one there once claimed the first exit, a hard right, at a
        // mini-roundabout where the route went left.
        val guess = floatArrayOf(100f, 30f, -30f, -80f, -120f, -150f, -170f)
        val bearing: Float? = roundaboutBearing?.toFloat()
            ?: if (roundaboutExit in 1..7) guess[roundaboutExit - 1] * (if (leftHand) -1f else 1f)
               else null

        if (bearing == null) {
            stroke.color = color
            stroke.strokeWidth = (RAB_R - RAB_RI) * u
            c.drawCircle(cx, cy, RAB_RMID * u, stroke)
            road(180f, RAB_IN_R1, RAB_ROAD_W, color)
            return
        }

        // Kept 40 degrees off the road in, as on the HUD.
        val aim = bearing.coerceIn(-RAB_MAX_AIM, RAB_MAX_AIM)
        val over = Math.toDegrees(((RAB_ROAD_W * 0.5f + 1.5f) / RAB_RM).toDouble()).toFloat()
        var from = if (leftHand) 180f - over else aim - over     // the bold arc, clockwise
        val to = if (leftHand) aim + over else 180f + over
        var sweep = ((to - from) % 360f + 360f) % 360f
        if (360f - sweep < 2f * over) { from = 0f; sweep = 360f }  // a U-turn: all of it

        // Android measures arcs from three o'clock, clockwise: bearing - 90.
        stroke.strokeWidth = (RAB_R - RAB_RI) * u
        stroke.color = dim
        if (sweep < 360f) c.drawArc(oval, to - 90f, 360f - sweep, false, stroke)
        stroke.color = color
        c.drawArc(oval, from - 90f, sweep, false, stroke)
        road(180f, RAB_IN_R1, RAB_ROAD_W, color)
        road(aim, RAB_HEAD_R0 + 2f, RAB_ROAD_W, color)

        val a = Math.toRadians(aim.toDouble())
        val hx = (cos(a) * RAB_HEAD_W * u).toFloat()
        val hy = (sin(a) * RAB_HEAD_W * u).toFloat()
        path.reset()
        path.moveTo(px(aim, RAB_TIP_R), py(aim, RAB_TIP_R))
        path.lineTo(px(aim, RAB_HEAD_R0) + hx, py(aim, RAB_HEAD_R0) + hy)
        path.lineTo(px(aim, RAB_HEAD_R0) - hx, py(aim, RAB_HEAD_R0) - hy)
        path.close()
        paint.color = color
        c.drawPath(path, paint)

        // The number in the hole, no box -- only beside an arrow.
        if (roundaboutExit in 1..12) {
            text.textSize = 30f * u
            text.isFakeBoldText = true
            text.color = color
            c.drawText(roundaboutExit.toString(), cx, cy - (text.ascent() + text.descent()) / 2f, text)
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

    private companion object {
        // hud_arrows.h, in its -60..60 glyph box.
        const val RAB_R = 30f
        const val RAB_RI = 19f
        const val RAB_RM = 24.5f
        const val RAB_RMID = (RAB_R + RAB_RI) / 2f
        const val RAB_UP = 4f
        const val RAB_ROAD_W = 11f
        const val RAB_IN_R1 = 48f
        const val RAB_HEAD_R0 = 40f
        const val RAB_TIP_R = 57f
        const val RAB_HEAD_W = 13f
        const val RAB_MAX_AIM = 140f
        /** The undriven ring: DASH_DIM is 62 % of DASH_AMBER. */
        const val DIM = 0.62f
    }
}
