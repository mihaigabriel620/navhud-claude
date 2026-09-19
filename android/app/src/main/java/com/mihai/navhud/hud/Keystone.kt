package com.mihai.navhud.hud

import com.mihai.navhud.HudFrame
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where the picture lands on the glass.
 *
 * The panel lies flat on the dash and is read as a reflection in the
 * windscreen, so two things have to be settable and then remembered: which way
 * round the image goes, and a keystone correction for a dash that is not level
 * with the screen it is holding.
 *
 * This class is the phone's half. It is deliberately pure Kotlin -- no Android
 * types -- because the rule that keeps a keystone screen usable is that the app
 * must never send the board a shape the board will refuse, and that rule is
 * worth a unit test rather than a drive.
 *
 * Corner order is top-left, top-right, bottom-right, bottom-left, in the
 * *driver's* view. The panel mirror and the windscreen reflection are flips of
 * the same axis and cancel, so a corner in the driver's view is a corner in the
 * layout, and neither side has to un-mirror anything. See hud_geom.h.
 */
data class Keystone(
    val mirrorX: Boolean = true,
    val mirrorY: Boolean = false,
    /** dx0, dy0, dx1, dy1, dx2, dy2, dx3, dy3 -- pixels, positive right/down. */
    val offsets: IntArray = IntArray(8)
) {

    val isIdentity: Boolean get() = offsets.all { it == 0 }

    fun dx(corner: Int) = offsets[corner * 2]
    fun dy(corner: Int) = offsets[corner * 2 + 1]

    /** The quad this describes, in panel pixels. */
    fun quadX(corner: Int): Int = BASE_X[corner] + dx(corner)
    fun quadY(corner: Int): Int = BASE_Y[corner] + dy(corner)

    fun withMirror(x: Boolean = mirrorX, y: Boolean = mirrorY) =
        copy(mirrorX = x, mirrorY = y)

    /**
     * Move one corner. Inward only, and never past the firmware's limit.
     *
     * Outward is legal on the wire and the board would accept it, but there is
     * nothing out there: a corner pushed past the edge of the panel asks for
     * drawing on pixels that do not exist, so whatever lands there is simply
     * not shown. Every useful correction shrinks the picture into the shape the
     * windscreen actually reflects. Constraining it here also means the preview
     * never has to draw outside itself, and the only remaining way to make a
     * shape the board refuses is to cross two corners over each other.
     */
    fun nudge(corner: Int, byX: Int, byY: Int): Keystone {
        val o = offsets.copyOf()
        o[corner * 2] = clampInX(corner, o[corner * 2] + byX)
        o[corner * 2 + 1] = clampInY(corner, o[corner * 2 + 1] + byY)
        return copy(offsets = o)
    }

    fun reset() = copy(offsets = IntArray(8))

    /**
     * `$GEOM` -- the live-preview message. Sent on every slider movement; the
     * board applies it immediately and writes nothing to flash.
     */
    fun encode(): String = HudFrame.wrap(
        "GEOM,${if (mirrorX) 1 else 0},${if (mirrorY) 1 else 0}," +
            (0 until 4).joinToString(",") { "${dx(it)},${dy(it)}" }
    )

    /**
     * Whether the board will accept this shape.
     *
     * The firmware rejects a quad that is concave or self-crossing and falls
     * back to no correction at all, which from the driver's seat looks like the
     * screen ignoring them. Checking here means the app can grey the control
     * out instead, and it is the same test: all four cross products the same
     * sign.
     */
    fun isUsable(): Boolean {
        if (isIdentity) return true
        val x = IntArray(4) { quadX(it) }
        val y = IntArray(4) { quadY(it) }
        var sign = 0
        for (i in 0 until 4) {
            val j = (i + 1) and 3
            val k = (i + 2) and 3
            val z = (x[j] - x[i]).toLong() * (y[k] - y[j]) -
                (y[j] - y[i]).toLong() * (x[k] - x[j])
            when {
                z > 0 -> { if (sign < 0) return false; sign = 1 }
                z < 0 -> { if (sign > 0) return false; sign = -1 }
                else -> return false          // three corners in a line
            }
        }
        return sign != 0
    }

    /** True when some of the drawing would fall off the edge of the panel. */
    fun clipsOffPanel(): Boolean =
        (0 until 4).any {
            quadX(it) < 0 || quadX(it) > SCR_W || quadY(it) < 0 || quadY(it) > SCR_H
        }

    /**
     * Roughly how much of the panel is still being used, as a percentage.
     *
     * Worth showing, because keystone is not free: every pixel of correction is
     * a pixel of screen you no longer have, and on a 4" panel read through a
     * windscreen that is the number that decides whether the speed is still
     * legible. Shoelace area of the quad over the area of the panel.
     */
    fun areaPercent(): Int {
        var a = 0L
        for (i in 0 until 4) {
            val j = (i + 1) and 3
            a += quadX(i).toLong() * quadY(j) - quadX(j).toLong() * quadY(i)
        }
        val area = abs(a) / 2.0
        return (area / (SCR_W.toDouble() * SCR_H) * 100.0).roundToInt().coerceIn(0, 100)
    }

    companion object {
        /** Must match HUD_SCR_W / HUD_SCR_H in hud_protocol.h. */
        const val SCR_W = 480
        const val SCR_H = 320

        /** Must match GEOM_MAX_PULL in hud_geom.h. */
        const val MAX_PULL = 0.35f
        val MAX_X = (SCR_W * MAX_PULL).toInt()      // 168
        val MAX_Y = (SCR_H * MAX_PULL).toInt()      // 112

        /**
         * ...and a tighter one for the sliders, which is not the same number.
         *
         * The two sliders both pull the same two corners when their signs line
         * up. At the full 35 % each, "narrow the bottom" plus "narrow the right"
         * drags the bottom-right corner across the diagonal and the quad turns
         * concave -- which the firmware refuses, falling back to no correction
         * at all, so the sliders would appear to work right up until they
         * silently stopped. Convexity holds everywhere up to 33 % and breaks at
         * 34 %; 25 % is the same shape with margin, and a quarter of the screen
         * is already a very tilted dashboard. The corner nudges still reach 35 %
         * for anything stranger.
         */
        const val SLIDER_PULL = 0.25f
        val SLIDER_MAX_X = (SCR_W * SLIDER_PULL).toInt()    // 120
        val SLIDER_MAX_Y = (SCR_H * SLIDER_PULL).toInt()    // 80

        val BASE_X = intArrayOf(0, SCR_W, SCR_W, 0)
        val BASE_Y = intArrayOf(0, 0, SCR_H, SCR_H)

        const val TOP_LEFT = 0
        const val TOP_RIGHT = 1
        const val BOTTOM_RIGHT = 2
        const val BOTTOM_LEFT = 3

        val CORNER_NAMES = arrayOf("Top left", "Top right", "Bottom right", "Bottom left")

        fun clampX(v: Int) = v.coerceIn(-MAX_X, MAX_X)
        fun clampY(v: Int) = v.coerceIn(-MAX_Y, MAX_Y)

        /**
         * Which way "in" is for each corner: +1 when the corner sits on the
         * low edge of that axis, -1 when it sits on the high one.
         */
        private val IN_X = intArrayOf(1, -1, -1, 1)
        private val IN_Y = intArrayOf(1, 1, -1, -1)

        fun clampInX(corner: Int, v: Int): Int =
            if (IN_X[corner] > 0) v.coerceIn(0, MAX_X) else v.coerceIn(-MAX_X, 0)

        fun clampInY(corner: Int, v: Int): Int =
            if (IN_Y[corner] > 0) v.coerceIn(0, MAX_Y) else v.coerceIn(-MAX_Y, 0)

        /**
         * The two-slider form, which is what most people actually want.
         *
         * `v` corrects a dash that tilts away from or towards you: the
         * reflection comes out as a trapezoid, so one horizontal edge is
         * shortened to match the other. `h` does the same for a screen that
         * sits off to one side.
         *
         * Both only ever pull corners *inward*. Pushing one outward is legal --
         * the corner nudges allow it -- but there are no pixels out there, so
         * whatever lands past the edge is simply not drawn. Making the sliders
         * incapable of it means the common path cannot produce an invisible
         * speed reading.
         *
         * @param v  >0 narrows the top edge, <0 narrows the bottom
         * @param h  >0 narrows the left edge, <0 narrows the right
         */
        fun fromSliders(mirrorX: Boolean, mirrorY: Boolean, h: Int, v: Int): Keystone {
            val o = IntArray(8)
            val vv = v.coerceIn(-SLIDER_MAX_X, SLIDER_MAX_X)
            if (vv > 0) {                       // top edge in
                o[0] = vv; o[2] = -vv
            } else if (vv < 0) {                // bottom edge in
                o[6] = -vv; o[4] = vv
            }
            val hh = h.coerceIn(-SLIDER_MAX_Y, SLIDER_MAX_Y)
            if (hh > 0) {                       // left edge in
                o[1] += hh; o[7] -= hh
            } else if (hh < 0) {                // right edge in
                o[3] += -hh; o[5] -= -hh
            }
            return Keystone(mirrorX, mirrorY, o)
        }

        /** `$GEOMSAVE` -- write the current settings to the board's flash. */
        fun saveCommand(): String = HudFrame.wrap("GEOMSAVE")

        /** `$GEOM?` -- ask the board what it is using. */
        fun queryCommand(): String = HudFrame.wrap("GEOM?")

        /** `$GEOMTEST,1|0` -- show or hide the alignment pattern. */
        fun testCommand(on: Boolean): String =
            HudFrame.wrap("GEOMTEST," + if (on) "1" else "0")

        /**
         * Parse the board's reply to `$GEOM?`.
         *
         * Takes the line with the `$` and the `*CS` already stripped, the way
         * HudService hands it over. Returns null for anything else, so an
         * unrelated sentence cannot half-apply.
         */
        fun parse(body: String): Keystone? {
            if (!body.startsWith("GEOM,")) return null
            val f = body.split(',')
            if (f.size != 11) return null
            val n = IntArray(10)
            for (i in 0 until 10) n[i] = f[i + 1].trim().toIntOrNull() ?: return null
            val o = IntArray(8)
            for (i in 0 until 8) {
                o[i] = if (i % 2 == 0) clampX(n[i + 2]) else clampY(n[i + 2])
            }
            return Keystone(n[0] != 0, n[1] != 0, o)
        }

        /** Store as a single string, so Prefs needs one key rather than ten. */
        fun serialize(k: Keystone): String =
            "${if (k.mirrorX) 1 else 0},${if (k.mirrorY) 1 else 0}," +
                k.offsets.joinToString(",")

        fun deserialize(s: String?): Keystone? {
            if (s.isNullOrBlank()) return null
            val f = s.split(',')
            if (f.size != 10) return null
            val n = IntArray(10)
            for (i in 0 until 10) n[i] = f[i].trim().toIntOrNull() ?: return null
            val o = IntArray(8)
            for (i in 0 until 8) o[i] = if (i % 2 == 0) clampX(n[i + 2]) else clampY(n[i + 2])
            return Keystone(n[0] != 0, n[1] != 0, o)
        }
    }

    // data class + IntArray: the generated equals() would compare references,
    // which would make two identical settings look different and send a $GEOM
    // for every frame.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Keystone) return false
        return mirrorX == other.mirrorX && mirrorY == other.mirrorY &&
            offsets.contentEquals(other.offsets)
    }

    override fun hashCode(): Int {
        var h = if (mirrorX) 1 else 0
        h = h * 31 + (if (mirrorY) 1 else 0)
        return h * 31 + offsets.contentHashCode()
    }
}
