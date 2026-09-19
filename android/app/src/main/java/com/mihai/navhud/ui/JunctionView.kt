package com.mihai.navhud.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.mihai.navhud.nav.JunctionSign
import com.mihai.navhud.nav.Lane
import com.mihai.navhud.nav.LaneGuidance
import com.mihai.navhud.nav.RoadSigns
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The motorway junction view: the carriageway you are on, drawn in
 * perspective, with the slip road peeling off it and the lane you have to be
 * in lit up.
 *
 * ## Why this exists on top of the lane strip
 *
 * A row of arrows tells you *which* lane. It does not tell you that the lane
 * you are looking for is about to become a separate carriageway, or that there
 * are two exit lanes and either will do, or that the lane you are in continues
 * straight on *and* exits. On a motorway at 120 km/h with 400 m to go, that is
 * the difference between changing lanes once and changing lanes twice.
 *
 * Commercial apps solve this with photo-real junction imagery — Sygic and
 * TomTom license it. That imagery is not obtainable for a DIY build, so this
 * is the schematic version: the same information, drawn from the router's own
 * lane data rather than photographed. HERE splits its junction view into two
 * pictures, a signpost panel and a carriageway diagram, and that separation is
 * copied here because it is the right one — the sign answers *where*, the
 * carriageway answers *which lane*.
 *
 * ## The geometry
 *
 * A single vanishing band at the top. Depth runs 0 (the bumper) to 1 (as far
 * as the picture goes) and is raised to a power so the near field gets most of
 * the pixels, which is what a real windscreen view does. Lanes are drawn as
 * quads between depth slices.
 *
 * The slip road is the one thing that cannot be drawn with the same vanishing
 * point, because it does not share one: it diverges. So past the split depth
 * it is offset sideways by a number of *pixels* that grows with depth. Doing
 * that offset in lane-width units instead produces a slip road that bulges out
 * and then curves back in, because the lane width itself is shrinking — which
 * is exactly what the first draft of this view did, and it read as a bubble
 * rather than an exit.
 *
 * ## The three arrow states
 *
 * Full amber for the movement you must make, dim amber for the other
 * movements the same lane allows, grey for lanes that are not yours. See the
 * long comment in [com.mihai.navhud.nav.Lane].
 */
class JunctionView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    companion object {
        /** Depth at which the slip road starts to peel away, 0..1. */
        const val T_SPLIT = 0.34f

        /** How far the slip road swings sideways, as a fraction of the width. */
        const val SWING = 0.24f

        /** Depth at which the lane arrows sit. */
        const val T_ARROW = 0.26f

        /** Perspective exponent: <1 gives the near field more pixels. */
        const val PERSP = 0.62f

        const val NEAR_HALF_FRAC = 0.40f
        const val FAR_HALF_FRAC = 0.075f

        /** Slices used to approximate the curve of the slip road. */
        const val SLICES = 18
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private val colRoad = Color.parseColor("#26282C")
    private val colRoadHi = Color.parseColor("#5C420C")
    private val colEdge = Color.parseColor("#D8DADE")
    private val colDash = Color.parseColor("#96989E")
    private val colAmber = Color.parseColor("#FF9D00")
    private val colAmberDim = Color.parseColor("#B98A2E")
    private val colGrey = Color.parseColor("#7E8288")

    var guidance: LaneGuidance? = null
        set(v) { if (field !== v) { field = v; refresh() } }

    var sign: JunctionSign? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Panel colour for the signpost; set from [RoadSigns.colourFor]. */
    var signColour: Int = RoadSigns.BLUE
        set(v) { if (field != v) { field = v; invalidate() } }

    /**
     * How many of the rightmost (or leftmost) lanes actually leave the
     * carriageway. Distinct from "how many lanes are usable": a lane can be
     * usable and still carry straight on, which is the shared-lane case.
     */
    private var rampLanes = 0
    private var rampRight = true

    private fun refresh() {
        val g = guidance
        if (g == null) { rampLanes = 0; visibility = GONE; invalidate(); return }
        visibility = VISIBLE
        rampRight = g.turnSide >= 0
        rampLanes = g.rampLaneCount()
        invalidate()
    }

    // ---- perspective --------------------------------------------------------

    private var vpX = 0f
    private var yNear = 0f
    private var yFar = 0f
    private var nearHalf = 0f
    private var farHalf = 0f

    private fun rowY(t: Float) = yNear + (yFar - yNear) * t.toDouble().pow(PERSP.toDouble()).toFloat()
    private fun halfW(t: Float) = nearHalf + (farHalf - nearHalf) * t.toDouble().pow(PERSP.toDouble()).toFloat()
    private fun xAt(u: Float, t: Float) = vpX + u * halfW(t)

    /** Sideways offset, in pixels, of the slip road at depth t. */
    private fun off(t: Float): Float {
        if (t <= T_SPLIT || rampLanes == 0) return 0f
        val q = (t - T_SPLIT) / (1f - T_SPLIT)
        val s = if (rampRight) 1f else -1f
        return s * width * SWING * q.toDouble().pow(1.5).toFloat()
    }

    private fun quad(c: Canvas, u0: Float, u1: Float, t0: Float, t1: Float, colour: Int, ramp: Boolean) {
        val o0 = if (ramp) off(t0) else 0f
        val o1 = if (ramp) off(t1) else 0f
        p.style = Paint.Style.FILL
        p.color = colour
        path.reset()
        path.moveTo(xAt(u0, t0) + o0, rowY(t0))
        path.lineTo(xAt(u1, t0) + o0, rowY(t0))
        path.lineTo(xAt(u1, t1) + o1, rowY(t1))
        path.lineTo(xAt(u0, t1) + o1, rowY(t1))
        path.close()
        c.drawPath(path, p)
    }

    override fun onDraw(canvas: Canvas) {
        val g = guidance ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || g.count == 0) return

        // No background fill: this view lives inside the instruction card and
        // painting a rectangle here would square off the card's rounded
        // corners. The card's own colour is the backdrop.
        vpX = w / 2f
        yNear = h - h * 0.02f
        yFar = h * 0.32f
        nearHalf = w * NEAR_HALF_FRAC
        farHalf = w * FAR_HALF_FRAC

        val total = g.count
        val lw = 2f / total
        val firstRamp = if (rampRight) total - rampLanes else 0
        val lastRamp = if (rampRight) total - 1 else rampLanes - 1
        fun isRamp(i: Int) = rampLanes > 0 && i in firstRamp..lastRamp

        // ---- carriageway ----------------------------------------------------
        for (i in 0 until total) {
            if (isRamp(i)) continue
            val u0 = -1f + i * lw
            val hi = g.isActive(i)
            quad(canvas, u0, u0 + lw, 0f, 1f, if (hi) colRoadHi else colRoad, false)
        }
        if (rampLanes > 0) {
            val u0 = -1f + firstRamp * lw
            val u1 = u0 + lw * rampLanes
            for (k in 0 until SLICES) {
                val ta = T_SPLIT * k / SLICES
                val tb = T_SPLIT * (k + 1) / SLICES
                quad(canvas, u0, u1, ta, tb, colRoadHi, false)
            }
            for (k in 0 until SLICES) {
                val ta = T_SPLIT + (1f - T_SPLIT) * k / SLICES
                val tb = T_SPLIT + (1f - T_SPLIT) * (k + 1) / SLICES
                quad(canvas, u0, u1, ta, tb, colRoadHi, true)
            }
        }

        // ---- lane markings ---------------------------------------------------
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        for (i in 1 until total) {
            val bothRamp = isRamp(i) && isRamp(i - 1)
            val straddles = isRamp(i) != isRamp(i - 1)
            if (straddles) continue                 // that one is the gore line
            val u = -1f + i * lw
            p.color = colDash
            for (k in 0 until 12) {
                val ta = k / 12f
                val tb = (k + 0.55f) / 12f
                p.strokeWidth = max(1f, 3.5f * (1f - ta) * (w / 780f))
                val oa = if (bothRamp) off(ta) else 0f
                val ob = if (bothRamp) off(tb) else 0f
                canvas.drawLine(xAt(u, ta) + oa, rowY(ta), xAt(u, tb) + ob, rowY(tb), p)
            }
        }

        p.color = colEdge
        p.strokeWidth = max(2f, 4f * (w / 780f))
        p.strokeCap = Paint.Cap.ROUND
        // Outer edges, one of which may be the slip road's outside. A side the
        // window cut off gets no edge line at all -- a tidy white line there
        // would claim the carriageway ends where the picture does.
        if (!g.clippedLeft) drawEdge(canvas, -1f, rampLanes > 0 && !rampRight)
        if (!g.clippedRight) drawEdge(canvas, 1f, rampLanes > 0 && rampRight)
        if (rampLanes > 0) {
            val ug = -1f + (if (rampRight) firstRamp else lastRamp + 1) * lw
            drawEdge(canvas, ug, true)             // inside of the slip road
            drawEdge(canvas, ug, false)            // edge of the through road
        }

        // ---- clipped-lane markers -------------------------------------------
        if (g.clippedLeft) drawTorn(canvas, -1f)
        if (g.clippedRight) drawTorn(canvas, 1f)

        // ---- arrows ----------------------------------------------------------
        val ay = rowY(T_ARROW)
        val base = min(h * 0.30f, halfW(T_ARROW) * 2f / total * 0.95f)
        for (i in 0 until total) {
            val u = -1f + (i + 0.5f) * lw
            val cx = xAt(u, T_ARROW) + (if (isRamp(i)) off(T_ARROW) else 0f)
            drawLaneArrows(canvas, g, i, cx, ay, base)
        }

        drawSign(canvas, w)
    }

    private fun drawEdge(c: Canvas, u: Float, ramp: Boolean) {
        path.reset()
        path.moveTo(xAt(u, 0f), rowY(0f))
        for (k in 1..24) {
            val t = k / 24f
            path.lineTo(xAt(u, t) + (if (ramp) off(t) else 0f), rowY(t))
        }
        p.style = Paint.Style.STROKE
        c.drawPath(path, p)
    }

    /**
     * The carriageway is wider than the picture: mark the edge as cut rather
     * than drawing a tidy white line there, which would claim the road ends.
     */
    private fun drawTorn(c: Canvas, u: Float) {
        p.style = Paint.Style.STROKE
        p.color = colGrey
        p.strokeWidth = max(2f, 3f * (width / 780f))
        var k = 0
        while (k < 24) {
            val ta = k / 24f
            val tb = (k + 0.5f) / 24f
            c.drawLine(xAt(u, ta), rowY(ta), xAt(u, tb), rowY(tb), p)
            k += 2
        }
        p.color = colEdge
    }

    /**
     * Where the picture already shows a slip road, the movement it serves is
     * drawn as a gentle bend even when the router calls it "right". The
     * geometry is on the screen -- a carriageway peeling away -- and a square
     * 90-degree arrow inside it reads as a turn into a side street, which is
     * the opposite of what the driver is about to do at 110 km/h. Movements
     * the other way keep their true angle: a left turn out of a right-hand
     * diverge is a real left turn.
     */
    private fun angleFor(bit: Int): Float {
        val a = Lane.angleOf(bit)
        val servesTheRamp = rampLanes > 0 && bit != Lane.UTURN &&
            Lane.sideOf(bit) == (if (rampRight) 1 else -1)
        return if (servesTheRamp) a.coerceIn(-45f, 45f) else a
    }

    private fun drawLaneArrows(
        c: Canvas, g: LaneGuidance, i: Int, cx: Float, cy: Float, full: Float
    ) {
        val bits = g.lanes[i]
        val on = g.isActive(i)
        val chosen = g.chosenOf(i)

        if (bits == 0) {
            arrow(c, cx, cy, full, 0f, if (on) colAmber else colGrey, on)
            return
        }
        // A lane carrying two or three arrows has to fit them in the same
        // width as a lane carrying one.
        val size = if (Lane.countBits(bits) > 1) full * 0.76f else full
        // Draw the chosen movement last and biggest so it wins any overlap.
        // Lane.ORDER is left to right on the tarmac, and the chosen movement
        // keeps its place in it. Appending it after the others put it in the
        // rightmost slot regardless, reversing the arrows the driver is
        // matching against the road markings.
        val inLane = ArrayList<Int>(3)
        for (bit in Lane.ORDER) {
            if (bits and bit == 0) continue
            inLane.add(bit)
        }
        // Three arrows is all a lane cell has room for. Trim from the far end
        // but never drop the chosen one: a lane allowing u-turn, left, straight
        // and right with "right" chosen used to lose its bright arrow entirely,
        // leaving a lit cell with three dim arrows and no movement named.
        while (inLane.size > 3) {
            val victim = if (inLane.last() != chosen) inLane.size - 1 else inLane.size - 2
            inLane.removeAt(victim)
        }
        val slots = inLane.size
        val spread = if (slots <= 1) 0f else size * 0.62f
        if (chosen != 0) {
            var slot = 0
            for (bit in inLane) {
                if (bit != chosen) {
                    arrow(c, cx + laneOffset(slot, slots, spread), cy, size * 0.88f,
                        angleFor(bit), if (on) colAmberDim else colGrey, false)
                }
                slot++
            }
            val ci = inLane.indexOf(chosen)
            if (ci >= 0) {
                arrow(c, cx + laneOffset(ci, slots, spread), cy, size,
                    angleFor(chosen), colAmber, true)
            }
        } else if (on) {
            // Usable lane, but the router never said which movement. Everything
            // it allows is lit at full strength -- honest about the ambiguity
            // rather than picking one at random.
            var s = 0
            for (bit in Lane.ORDER) {
                if (bits and bit == 0) continue
                arrow(c, cx + laneOffset(s, slots, spread), cy, size, angleFor(bit), colAmber, true)
                if (++s >= slots) break
            }
        }
    }

    private fun laneOffset(slot: Int, slots: Int, spread: Float): Float =
        if (slots <= 1) 0f else (slot - (slots - 1) / 2f) * spread

    /**
     * One arrow, drawn upright and facing the driver rather than laid flat on
     * the road surface. Painting it into the perspective plane is the obvious
     * thing to try and it looks wrong: a right-turn arrow near the left edge
     * ends up sheared into something that reads as a bent stick.
     */
    /**
     * @param thick the movement the route takes: filled head, drawn last
     *
     * The alternatives get a hollow head. See LaneView.arrow for why the
     * distinction is filled-versus-hollow rather than two shades of amber.
     */
    private fun arrow(c: Canvas, cx: Float, cy: Float, s: Float, angle: Float, colour: Int, thick: Boolean) {
        val sw = max(2f, s * (if (thick) 0.20f else 0.16f))
        val headStyle = if (thick) Paint.Style.FILL else Paint.Style.STROKE
        val headStroke = max(1.5f, s * 0.09f)
        val hw = s * 0.30f
        val hl = s * 0.34f
        p.color = colour
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.strokeCap = Paint.Cap.BUTT

        if (angle == 0f) {
            c.drawLine(cx, cy + s * 0.5f, cx, cy - s * 0.5f + hl * 0.5f, p)
            p.style = headStyle
            p.strokeWidth = headStroke
            p.strokeJoin = Paint.Join.MITER
            path.reset()
            path.moveTo(cx, cy - s * 0.5f)
            path.lineTo(cx - hw, cy - s * 0.5f + hl)
            path.lineTo(cx + hw, cy - s * 0.5f + hl)
            path.close()
            c.drawPath(path, p)
            return
        }

        val ky = cy - s * 0.06f
        c.drawLine(cx, cy + s * 0.5f, cx, ky, p)

        if (abs(angle) >= 170f) {                    // u-turn: a little hook
            val ex = cx - s * 0.34f
            val ey = ky + s * 0.24f
            path.reset()
            path.moveTo(cx, ky)
            path.quadTo(cx + s * 0.22f, ky - s * 0.24f, cx - s * 0.06f, ky - s * 0.14f)
            path.quadTo(ex - s * 0.10f, ky - s * 0.06f, ex, ey - s * 0.10f)
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            c.drawPath(path, p)
            p.style = headStyle
            p.strokeWidth = headStroke
            p.strokeJoin = Paint.Join.MITER
            path.reset()
            path.moveTo(ex, ey + s * 0.16f)
            path.lineTo(ex - hw * 0.9f, ey - s * 0.06f)
            path.lineTo(ex + hw * 0.9f, ey - s * 0.06f)
            path.close()
            c.drawPath(path, p)
            return
        }

        val a = Math.toRadians(angle.toDouble())
        val ux = sin(a).toFloat()
        val uy = -cos(a).toFloat()
        val l = s * 0.44f
        val ex = cx + ux * l
        val ey = ky + uy * l
        c.drawLine(cx, ky, ex, ey, p)
        // The corner. Two butt-capped strokes meeting at a point leave a
        // wedge-shaped notch on the outside of the bend and a hole on the
        // inside, which is what made these look like a straight piece and a
        // bent piece glued together. A round dot the width of the stroke fills
        // the join exactly, and unlike a round *cap* it does not lengthen
        // either arm.
        val old = p.strokeCap
        p.strokeCap = Paint.Cap.ROUND
        c.drawPoint(cx, ky, p)
        p.strokeCap = old

        p.style = headStyle
            p.strokeWidth = headStroke
            p.strokeJoin = Paint.Join.MITER
        val px = -uy * hw
        val py = ux * hw
        path.reset()
        path.moveTo(ex + ux * hl * 0.95f, ey + uy * hl * 0.95f)
        path.lineTo(ex + px, ey + py)
        path.lineTo(ex - px, ey - py)
        path.close()
        c.drawPath(path, p)
    }

    // ---- signpost -----------------------------------------------------------

    private val signText = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawSign(c: Canvas, w: Float) {
        val s = sign ?: return
        if (s.isEmpty) return
        val pad = w * 0.020f
        val big = s.exitNumber
        val dest = s.destinations

        signText.textAlign = Paint.Align.LEFT
        val bigSize = max(14f, w * 0.048f)
        val smallSize = max(10f, w * 0.032f)

        signText.typeface =
            android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
        signText.textSize = bigSize
        val bigW = if (big != null) signText.measureText(big) else 0f
        signText.typeface = android.graphics.Typeface.DEFAULT
        signText.textSize = smallSize
        val lines = splitDest(dest, w * 0.42f)
        var destW = 0f
        for (l in lines) destW = max(destW, signText.measureText(l))

        val boxW = max(bigW, destW) + pad * 3f
        val boxH = (if (big != null) bigSize * 1.25f else 0f) +
            lines.size * smallSize * 1.35f + pad * 1.6f
        // The panel goes on the side the slip road is *not* on. A gantry in
        // real life hangs over the exit, but here that puts white text on top
        // of the one part of the picture the driver is looking at.
        val top = pad * 1.2f
        val left = if (rampLanes > 0 && rampRight) pad * 1.5f else w - pad * 1.5f - boxW
        rect.set(left, top, left + boxW, top + boxH)

        p.style = Paint.Style.FILL
        p.color = signColour
        c.drawRoundRect(rect, pad * 0.5f, pad * 0.5f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(2f, w * 0.004f)
        p.color = Color.WHITE
        c.drawRoundRect(rect, pad * 0.5f, pad * 0.5f, p)

        val tc = RoadSigns.textColourFor(signColour)
        var y = top + pad * 0.8f
        if (big != null) {
            signText.typeface = android.graphics.Typeface.create(
                "sans-serif-condensed", android.graphics.Typeface.BOLD)
            signText.textSize = bigSize
            signText.color = tc
            y += bigSize
            c.drawText(big, rect.left + pad * 1.5f, y, signText)
            y += bigSize * 0.25f
        }
        signText.typeface = android.graphics.Typeface.DEFAULT
        signText.textSize = smallSize
        signText.color = tc
        for (l in lines) {
            y += smallSize
            c.drawText(l, rect.left + pad * 1.5f, y, signText)
            y += smallSize * 0.35f
        }
    }

    /** At most two lines; the second is ellipsised rather than allowed to run. */
    private fun splitDest(dest: String?, maxW: Float): List<String> {
        if (dest.isNullOrBlank()) return emptyList()
        val parts = dest.split(" / ", "/", ",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val out = ArrayList<String>(2)
        for (part in parts) {
            if (out.size >= 2) break
            out.add(fit(part, maxW))
        }
        return out
    }

    private fun fit(s: String, maxW: Float): String {
        if (signText.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 2 && signText.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }
}
