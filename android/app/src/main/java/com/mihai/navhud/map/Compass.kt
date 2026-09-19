package com.mihai.navhud.map

import com.mihai.navhud.Geo
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * An *absolute* heading from the phone's orientation sensor.
 *
 * The rest of the heading pipeline works in differences — how far the car has
 * turned since the last sample — and gets its absolute reference from GPS. That
 * is right while you are moving and useless before you have moved: park, open
 * the app, and there is no bearing to be had from a GPS receiver that has seen
 * you standing still. The map used to draw the arrow at zero in that state,
 * which is not "unknown", it is "pointing north" said with total confidence.
 *
 * ## The mounting problem, and why an offset solves it
 *
 * A compass heading is the direction some chosen axis of the *phone* points,
 * and the phone is not the car. Lay it flat in a landscape cradle and the axis
 * that points down the road is a different one from when it stands upright in a
 * windscreen mount; rotate it 90 degrees in the same cradle and the answer
 * changes by 90 degrees.
 *
 * Rather than guess the mounting from the geometry, pick any fixed convention
 * and learn the constant it is wrong by. That constant absorbs the mounting
 * angle, the cradle, *and* the car's own magnetic distortion — a steel shell
 * and a dashboard full of current can bend the reading by tens of degrees, and
 * a hard-iron offset like that is exactly the sort of thing a single learned
 * constant handles. It is learned automatically from the first GPS bearing at
 * road speed, and it can be nudged by hand on the calibration screen.
 *
 * Only two conventions are needed, because a phone in a car is either lying
 * down or standing up:
 *
 *  - [AXIS_TOP] — the top edge of the phone, its +Y axis. Right for a phone
 *    lying flat in a cradle or on the dash.
 *  - [AXIS_SCREEN] — the way the screen faces, its −Z axis. Right for a phone
 *    standing upright, where +Y points at the roof and has no heading at all.
 */
object Compass {

    const val AXIS_TOP = 0
    const val AXIS_SCREEN = 1

    /**
     * Below this the chosen axis is too close to vertical for its heading to
     * mean anything: the horizontal part of a near-vertical vector is mostly
     * sensor noise, and its direction swings wildly for a tiny change in tilt.
     *
     * This was 0.25 — about 75 degrees of tilt — and the last stretch before
     * that cut-off is exactly where the azimuth starts whirling, because the
     * horizontal projection it is the direction of has almost no length left.
     * 0.5 is 60 degrees, which still covers any sane cradle and stops feeding
     * the heading long before the reading becomes a spinning needle.
     */
    const val MIN_HORIZONTAL = 0.5

    /**
     * The heading, in degrees clockwise from north, of the given device axis.
     *
     * @param r row-major 3x3 device-to-world rotation matrix, Android's ENU
     *          world frame: x east, y north, z up
     * @return null when that axis is too near vertical to have a heading
     */
    fun azimuthOf(r: DoubleArray, axis: Int): Double? {
        if (r.size != 9) return null
        // The world-frame image of the device axis is the matching column of R
        // (or its negation for the screen normal, which points out of the back
        // of the screen towards the driver).
        val east: Double
        val north: Double
        if (axis == AXIS_SCREEN) { east = -r[2]; north = -r[5] }
        else { east = r[1]; north = r[4] }
        if (hypot(east, north) < MIN_HORIZONTAL) return null
        return Geo.normalizeDeg(Math.toDegrees(atan2(east, north)))
    }

    /**
     * Rotate a device-to-world matrix into the *display's* frame.
     *
     * The app is locked to landscape, so the phone's own axes are turned a
     * quarter-turn from what the driver sees. Android's own compass app does
     * this remap, which is why its reading matches what the phone is pointing
     * at rather than what its manufacturing axes are pointing at — and it is
     * why doing the same thing here means a phone lying flat in a cradle
     * usually needs no mounting offset at all: the top of the screen already
     * points down the road.
     *
     * Implemented here rather than through SensorManager.remapCoordinateSystem
     * so it can be tested without a device. The mapping is the standard one:
     * the display's x and y axes expressed in device axes.
     *
     * @param rotation Surface.ROTATION_0/90/180/270
     */
    fun remapForDisplay(r: DoubleArray, rotation: Int): DoubleArray {
        if (r.size != 9) return r
        // The new frame's axes, expressed in device axes.
        //
        // Read the parameter names of SensorManager.remapCoordinateSystem
        // carefully, because I got this backwards once and it cost a release:
        // "X defines the axis of the NEW coordinate system that coincides with
        // the X axis of the ORIGINAL". So remap(AXIS_Y, AXIS_MINUS_X), the call
        // the docs give for ROTATION_90, says device-X becomes new-Y and
        // device-Y becomes new-minus-X — from which new-X is *minus* device-Y.
        // Reading it the other way round produces the transpose, which for a
        // quarter turn is the inverse, so 90 and 270 swap and every heading
        // comes out 180 degrees wrong. Held one way up it looked right.
        val (xa, xs, ya, ys) = when (rotation) {
            1 -> Quad(1, -1.0, 0, 1.0)      // ROTATION_90:  X = -y, Y = +x
            2 -> Quad(0, -1.0, 1, -1.0)     // ROTATION_180: X = -x, Y = -y
            3 -> Quad(1, 1.0, 0, -1.0)      // ROTATION_270: X = +y, Y = -x
            else -> Quad(0, 1.0, 1, 1.0)    // ROTATION_0
        }
        // out = r * M, where M's columns are the display axes in device
        // coordinates. All four rotations are turns about the device's own z,
        // so M's third column is always the device z axis and the third column
        // of the product is simply copied through.
        val out = DoubleArray(9)
        for (row in 0..2) {
            out[row * 3] = r[row * 3 + xa] * xs
            out[row * 3 + 1] = r[row * 3 + ya] * ys
            out[row * 3 + 2] = r[row * 3 + 2]
        }
        return out
    }

    private data class Quad(val xa: Int, val xs: Double, val ya: Int, val ys: Double)

    /**
     * The heading the driver would say the phone is pointing, in degrees.
     *
     * Takes the display frame rather than the device frame, and picks the axis
     * that actually has a heading in it: the top of the screen when the phone
     * is lying down, the way the screen faces when it is stood upright in a
     * windscreen mount.
     *
     * @return null when neither axis is far enough from vertical to be read
     */
    /**
     * The heading of a *chosen* axis. Which axis is a decision the caller keeps.
     *
     * Deciding it per sample is a bug, and a bad one: the two axes agree only
     * when the phone's tilt is pure pitch, and any roll makes them differ by
     * the roll angle — up to 90 degrees in a cradle that leans, 180 in theory.
     * A mount sitting near the changeover would then flip the arrow through a
     * right angle and back on every bump. Decide once, hold it.
     */
    fun headingFor(r: DoubleArray, rotation: Int, axis: Int): Double? =
        if (axis == AXIS_SCREEN) azimuthOf(r, AXIS_SCREEN)
        else azimuthOf(remapForDisplay(r, rotation), AXIS_TOP)

    /**
     * Is this axis usable for this attitude right now?
     *
     * Two perpendicular axes can never both be vertical — for an orthonormal
     * pair the horizontal parts satisfy |n|² + |t|² ≥ 1, so at least one is
     * always above 0.707 — which is why there is no attitude with no heading
     * at all, and why re-picking is only ever needed when the phone has been
     * moved rather than merely jostled.
     */
    fun axisUsable(r: DoubleArray, rotation: Int, axis: Int): Boolean =
        headingFor(r, rotation, axis) != null

    fun headingFor(r: DoubleArray, rotation: Int): Double? {
        // The back of the phone first, and not only because it is usually right.
        //
        // A phone standing in a windscreen cradle has its +Y axis pointing at
        // the roof, and the azimuth of a vertical axis is not a small error —
        // it is undefined. Simulated against Android's own implementation, a
        // cradle-mounted phone returns a *constant* heading whichever way the
        // car faces. Both OsmAnd and Organic Maps have open bugs that are this.
        //
        // The phone's back faces along the car in exactly that case, and it
        // has a second property worth as much: rotating the phone in its own
        // plane does not change it. Turn the handset end for end in the cradle,
        // put the charging socket on the other side, and this axis is
        // unmoved — so the reading cannot depend on whether the app managed to
        // flip its layout.
        azimuthOf(r, AXIS_SCREEN)?.let { return it }
        // Lying flat, the screen faces the sky and has no heading; then it is
        // the top edge of the screen, and only then does the display rotation
        // come into it.
        return azimuthOf(remapForDisplay(r, rotation), AXIS_TOP)
    }

    /** Earth's field is ~30-60 uT; Brussels is about 49. */
    const val MIN_FIELD_UT = 10.0
    const val MAX_FIELD_UT = 100.0

    /**
     * Is the magnetic field we are sitting in plausibly the Earth's?
     *
     * Four lines, straight out of AOSP's own sensor fusion, and the single
     * most targeted thing that can be done about a heading that jumps twenty
     * or forty degrees in a car: a field outside this window is not the planet,
     * it is the door pillar, the speaker magnet, the mount or the charging
     * coil. Neither OsmAnd nor Organic Maps checks this, and both have open
     * bug reports describing exactly that jump.
     */
    fun fieldPlausible(bx: Double, by: Double, bz: Double): Boolean {
        val m = Math.sqrt(bx * bx + by * by + bz * bz)
        return m in MIN_FIELD_UT..MAX_FIELD_UT
    }

    /**
     * One step of a circular exponential average, done in sin/cos.
     *
     * Averaging angles directly is wrong across the 0/360 seam — 350 and 10
     * average to 180, which is the opposite direction. Decomposing to a vector,
     * averaging that, and recovering the angle is correct everywhere, and is
     * what OsmAnd has used since 2013.
     *
     * @param alpha weight of the new sample, 0..1
     */
    fun emaStep(current: Double, sample: Double, alpha: Double): Double {
        val a = alpha.coerceIn(0.0, 1.0)
        val cs = Math.cos(Math.toRadians(current)) * (1 - a) +
                 Math.cos(Math.toRadians(sample)) * a
        val sn = Math.sin(Math.toRadians(current)) * (1 - a) +
                 Math.sin(Math.toRadians(sample)) * a
        if (Math.abs(cs) < 1e-12 && Math.abs(sn) < 1e-12) return current
        return Geo.normalizeDeg(Math.toDegrees(Math.atan2(sn, cs)))
    }

    /**
     * Which of the two conventions has something to say about this attitude.
     *
     * Whichever axis lies flatter wins, and there is always one: two axes at
     * right angles cannot both be vertical. Decided once, when the driver
     * calibrates, and then stored — re-deciding it every frame would flip the
     * heading by 90 degrees whenever the phone passed through 45 degrees of
     * tilt, which is a bump in the road away.
     */
    fun bestAxis(r: DoubleArray, rotation: Int): Int {
        if (r.size != 9) return AXIS_TOP
        // Score the axis we will actually *read*, which for AXIS_TOP is the
        // top of the display, not the top of the device. Those are different
        // things: the activity is locked to landscape, so on a portrait-native
        // phone the display rotation is always 90 or 270 and the display's +Y
        // column is the device's +/-X. Scoring device Y here while headingFor()
        // read display Y meant bestAxis could keep re-electing an axis
        // headingFor() could not resolve -- so on an upright cradle-mounted
        // phone the compass returned null on every single sample, forever, and
        // the arrow had no heading at all while the car was stopped. Which is
        // the one thing the compass exists for.
        val d = remapForDisplay(r, rotation)
        val top = hypot(d[1], d[4])
        val screen = hypot(d[2], d[5])
        return if (screen > top) AXIS_SCREEN else AXIS_TOP
    }

    /**
     * The offset that makes this reading agree with a heading we trust.
     *
     * `trueHeading` comes from GPS at road speed, where it is the direction the
     * car actually moved and cannot be argued with.
     */
    fun offsetFor(azimuthDeg: Double, trueHeadingDeg: Double): Double =
        Geo.normalizeDeg(trueHeadingDeg - azimuthDeg)

    /** Blend a new offset into the learned one, the short way round the circle. */
    fun blendOffset(current: Double, fresh: Double, weight: Double): Double =
        Geo.normalizeDeg(current + Geo.signedDelta(fresh, current) * weight.coerceIn(0.0, 1.0))

    /** Is this reading close enough to the learned offset to be worth trusting? */
    fun agrees(a: Double, b: Double, toleranceDeg: Double = 25.0): Boolean =
        abs(Geo.signedDelta(a, b)) <= toleranceDeg
}
