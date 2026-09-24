package com.mihai.navhud.map

import com.mihai.navhud.Geo
import com.mihai.navhud.nav.AreaRoads
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where to draw the car along a line, frame by frame: a rubber band, not a
 * filter.
 *
 * The fix says where the car was up to a second ago; the reference speed says
 * how fast it is going. The marker runs at that speed plus a bounded
 * correction toward where the fix puts the car *now*, and its own speed
 * changes no faster than a car's can. A fix that lands ahead makes it speed up
 * a little for a moment instead of jumping; one that lands behind makes it ease
 * off -- never back up; and a tunnel is running at the car's speed, followed
 * by a smooth catch-up on the far side.
 *
 * This replaced exponential easing onto an extrapolated target, which stalled
 * when the extrapolation ran out and lurched when coasting took over.
 *
 * One axis, in metres: along the route, or along the direction of travel in
 * free drive (see [FreeDriveMotion]). Pure Kotlin, so it can be tested.
 */
class PuckMotion(private val coastLimitS: Double) {

    companion object {
        /** How fast the drawn speed may change, m/s². */
        const val MAX_ACCEL = 4.0

        /** Past this error, stop chasing and place the marker, metres. */
        const val JUMP_M = 300.0

        /** Time constant for closing a small error (GPS noise), seconds. */
        const val CORRECTION_TAU_S = 2.0

        /** The most the drawn speed may differ from the car's, m/s (54 km/h). */
        const val MAX_CORRECTION_MPS = 15.0

        /**
         * The speed offset that closes [errM]: linear for noise, capped for a
         * long gap, and tapered so that half of [MAX_ACCEL] is enough to
         * unwind it -- the marker lands on the target instead of overshooting.
         */
        fun correction(errM: Double): Double {
            val e = abs(errM)
            val c = minOf(e / CORRECTION_TAU_S, sqrt(MAX_ACCEL * e), MAX_CORRECTION_MPS)
            return if (errM < 0) -c else c
        }
    }

    /** Drawn position, metres; NaN until the first step. */
    var along = Double.NaN
        private set

    /** Drawn speed, m/s; never negative. */
    var speed = 0.0
        private set

    fun reset() {
        along = Double.NaN
        speed = 0.0
    }

    /**
     * Advance one frame.
     *
     * @param fixAlongM    where the last fix put the car
     * @param fixAgeS      how old that fix is
     * @param refSpeed     the car's speed: the bus when it is talking, else the
     *                     fix's -- which, with the fix gone, is its last value
     * @param fixAvailable false once the fix has gone stale: coast on
     *                     [refSpeed] until [coastLimitS], then hold
     * @return the drawn position
     */
    fun step(
        dt: Double,
        fixAlongM: Double,
        fixAgeS: Double,
        refSpeed: Double,
        fixAvailable: Boolean
    ): Double {
        val v = max(0.0, refSpeed)
        if (along.isNaN()) {
            along = fixAlongM + if (fixAvailable) v * fixAgeS else 0.0
            speed = v
            return along
        }
        if (dt <= 0.0) return along
        val want = if (fixAvailable) {
            val target = fixAlongM + v * fixAgeS
            // Against where this frame lands at the car's speed, so a marker
            // exactly on target stays on it rather than running a frame ahead.
            val err = target - (along + v * dt)
            if (abs(err) > JUMP_M) {
                along = target
                speed = v
                return along
            }
            max(0.0, v + correction(err))
        } else if (fixAgeS < coastLimitS) {
            v
        } else {
            // Coasted as far as is honest.
            speed = 0.0
            return along
        }
        val dv = MAX_ACCEL * dt
        speed = max(0.0, speed + (want - speed).coerceIn(-dv, dv))
        along += speed * dt
        return along
    }
}

/**
 * The same rubber band off the route, where there is no line to measure along.
 *
 * "Along" is the direction of travel: the road under the marker when one is
 * known -- so coasting through an underpass follows its bend -- else the
 * heading. The sideways part of the error is closed with the same bounded
 * correction, and the result is put back on the road last, so whatever the
 * motion did, the marker ends up on the tarmac while [AreaRoads.snapWithin]
 * agrees it still belongs there.
 */
class FreeDriveMotion(coastLimitS: Double, private val snapM: Double) {

    private val band = PuckMotion(coastLimitS)
    private var lastDir: Double? = null

    var lat = Double.NaN
        private set
    var lon = Double.NaN
        private set

    /** Forget the motion and start from here; NaN starts from the next fix. */
    fun reset(atLat: Double = Double.NaN, atLon: Double = Double.NaN) {
        lat = atLat
        lon = atLon
        lastDir = null
        band.reset()
    }

    /**
     * @param headingDeg direction of travel, or null when unknown
     * @param road       the matched road's polyline, or null
     * @return the drawn position, {lat, lon}
     */
    fun step(
        dt: Double,
        fixLat: Double,
        fixLon: Double,
        fixAgeS: Double,
        refSpeed: Double,
        fixAvailable: Boolean,
        headingDeg: Double?,
        road: Array<DoubleArray>?
    ): DoubleArray {
        if (lat.isNaN() ||
            (fixAvailable && Geo.haversine(lat, lon, fixLat, fixLon) > PuckMotion.JUMP_M)) {
            lat = fixLat
            lon = fixLon
            band.reset()
        }
        // With a live fix the heading is the truth; coasting, the way we were
        // already going is, because the heading froze with the fix.
        val ref = if (fixAvailable) headingDeg ?: lastDir else lastDir ?: headingDeg
        val travel = road?.let { roadDirection(it, ref) } ?: ref
        if (travel != null) lastDir = travel
        // No direction of travel at all: with a fix, head straight for it; with
        // none, there is nothing honest to coast along.
        val dir = travel ?: if (fixAvailable) Geo.bearing(lat, lon, fixLat, fixLon) else null
        if (dir != null) {
            // The fix, in metres ahead of and to the right of the marker.
            val kx = Geo.EARTH_R * cos(Math.toRadians(lat)) * Math.PI / 180.0
            val ky = Geo.EARTH_R * Math.PI / 180.0
            val dx = (fixLon - lon) * kx
            val dy = (fixLat - lat) * ky
            val r = Math.toRadians(dir)
            val ahead = dx * sin(r) + dy * cos(r)
            val across = dx * cos(r) - dy * sin(r)
            val a0 = if (band.along.isNaN()) 0.0 else band.along
            val moved = band.step(dt, a0 + ahead, fixAgeS, refSpeed, fixAvailable) - a0
            var p = Geo.destination(lat, lon, dir, moved)
            if (fixAvailable) {
                val side = PuckMotion.correction(across) * dt
                p = Geo.destination(p[0], p[1], dir + 90.0,
                    if (abs(side) > abs(across)) across else side)
            }
            lat = p[0]
            lon = p[1]
        }
        if (road != null) {
            AreaRoads.snapWithin(road, lat, lon, snapM)?.let { lat = it[0]; lon = it[1] }
        }
        return doubleArrayOf(lat, lon)
    }

    /** The road's direction under the marker, the way round that agrees with [ref]. */
    private fun roadDirection(road: Array<DoubleArray>, ref: Double?): Double? {
        if (ref == null) return null
        val brg = AreaRoads.nearestOn(road, lat, lon)?.second ?: return null
        return if (Geo.bearingDelta(brg, ref) > 90.0) (brg + 180.0) % 360.0 else brg
    }
}
