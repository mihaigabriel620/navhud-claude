package com.mihai.navhud.map

import com.mihai.navhud.Geo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The camera behaviour that makes a nav map feel like a nav map rather than a
 * map with a dot on it. Three things do most of the work:
 *
 *  1. **The car sits low on the screen, not in the middle.** You need to see
 *     where you are going, not where you have been. Roughly 72% down.
 *  2. **Zoom follows speed.** At 30 km/h you want the next junction; at 130 you
 *     want the next kilometre. The curve below is in seconds-of-road-ahead, not
 *     arbitrary zoom numbers, which is why it feels consistent at both ends.
 *  3. **Bearing is smoothed, and frozen when stopped.** GPS heading is noise
 *     below walking pace; letting it through spins the map while you sit at a
 *     red light, which is the single most nauseating thing a nav app can do.
 *
 * Pure maths, no Android types, so it can be unit-tested.
 */
class NavCamera {

    companion object {
        /**
         * Fraction down the screen where the car sits. 0.60 keeps it clearly
         * above the bottom chrome and near the middle, the way Waze places it
         * on a landscape screen; the tilt is what shows the road ahead.
         */
        const val PUCK_SCREEN_FRACTION = 0.60

        /**
         * One tilt, always.
         *
         * This used to ramp from flat when stopped to 55 degrees at speed,
         * which meant the map snapped from a plan view to a steep perspective
         * every time you pulled away from a light, and sat as a flat 2D map
         * whenever you stopped. Waze holds a constant, moderate tilt from the
         * moment it opens and never changes it, and that is what makes it read
         * as a road ahead of you rather than a diagram of one.
         *
         * 40 degrees, not 55: steeper than this and the near foreground eats
         * the screen while the useful distance compresses into a thin band.
         */
        const val TILT = 40.0

        /**
         * How far a two-finger drag may move the tilt away from [TILT].
         *
         * The map used to accept any pitch the gesture recogniser produced,
         * including zero — one careless two-finger drag and you were looking
         * at a plan view with a chevron on it, which is a map, not a
         * navigation display, and there was no way back except restarting.
         * Clamping the gesture keeps the perspective in the range the whole
         * screen is designed around: the puck lies on the ground plane, so a
         * flat map squashes nothing and a very steep one hides the road ahead
         * behind the car.
         */
        const val TILT_MIN = 28.0
        const val TILT_MAX = 58.0

        /** Below this a *GPS* heading is not trustworthy, m/s (~7 km/h). */
        const val HEADING_MIN_SPEED = 2.0

        /**
         * ...but a gyroscope, or the direction of the road you are snapped to,
         * is trustworthy at any speed — including none.
         *
         * This was 0.4 m/s, a walking-pace floor kept as a safety net, and it
         * was the last thing holding the map still on a stationary car. There
         * is nothing to be safe from: the threshold exists to keep *GPS* noise
         * from spinning the map at a standstill, and a heading that came from
         * an orientation sensor has no GPS noise in it. Zero means the map
         * comes round with the car in a car park, on a driveway, at a barrier
         * — every place you are moving too slowly for GPS to have an opinion.
         */
        const val HEADING_MIN_SPEED_TRUSTED = 0.0

        /**
         * Smoothing time constants, seconds. Time-based (alpha derived from
         * the actual frame dt) so the feel is identical whether the caller
         * updates at 4 Hz or 30 Hz -- per-update constants are what made the
         * motion depend on the tick rate.
         */
        private const val TAU_BEARING = 0.35
        private const val TAU_ZOOM = 1.5
        private const val TAU_TILT = 0.5

        /**
         * Speed (km/h) to zoom level. Picked so the visible road ahead is
         * roughly 12-20 seconds of travel at each speed.
         */
        private val SPEED_KPH = doubleArrayOf(0.0, 30.0, 50.0, 70.0, 90.0, 110.0, 130.0)
        private val ZOOM     = doubleArrayOf(17.6, 17.2, 16.8, 16.4, 16.0, 15.6, 15.2)

        /**
         * How much closer to pull in on the approach to a junction, in zoom
         * levels. Waze does this, and it is the difference between "a turn is
         * coming" and "*that* turn, into *that* street".
         */
        const val MAX_MANEUVER_BOOST = 1.2

        /** Start closing in this many seconds out... */
        const val APPROACH_S = 14.0

        /** ...and be fully zoomed by this many. */
        const val APPROACH_FULL_S = 4.0

        /**
         * The boost is scaled by *time* to the junction rather than distance:
         * 300 m is a long way at 30 km/h and almost nothing at 120, and zooming
         * to a street-level view on a motorway would leave you looking at
         * tarmac while the exit you needed slid past the top of the screen.
         *
         * @param distM distance to the maneuver, negative when there isn't one
         */
        fun maneuverZoomBoost(distM: Double, speedMps: Double): Double {
            if (distM < 0.0) return 0.0
            val t = distM / max(5.0, speedMps)
            if (t >= APPROACH_S) return 0.0
            val f = ((APPROACH_S - t) / (APPROACH_S - APPROACH_FULL_S)).coerceIn(0.0, 1.0)
            return MAX_MANEUVER_BOOST * f
        }

        fun zoomForSpeed(kph: Double): Double {
            if (kph <= SPEED_KPH.first()) return ZOOM.first()
            if (kph >= SPEED_KPH.last()) return ZOOM.last()
            for (i in 1 until SPEED_KPH.size) {
                if (kph <= SPEED_KPH[i]) {
                    val t = (kph - SPEED_KPH[i - 1]) / (SPEED_KPH[i] - SPEED_KPH[i - 1])
                    return ZOOM[i - 1] + t * (ZOOM[i] - ZOOM[i - 1])
                }
            }
            return ZOOM.last()
        }

        /** Shortest signed turn from a to b, in degrees, -180..180. */
        fun shortestTurn(from: Double, to: Double): Double {
            var d = (to - from) % 360.0
            if (d > 180.0) d -= 360.0
            if (d < -180.0) d += 360.0
            return d
        }
    }

    /** Current smoothed values, degrees / zoom level. */
    var bearing = 0.0
        private set
    var zoom = ZOOM.first()
        private set
    var tilt = TILT
        private set

    private var started = false

    /**
     * @param speedMps  current speed
     * @param rawBearing GPS heading, or null when there isn't a usable one
     * @return true if anything moved enough to be worth animating
     */
    fun update(
        speedMps: Double,
        rawBearing: Double?,
        overview: Boolean = false,
        dtSeconds: Double = 0.25,
        maneuverDistM: Double = -1.0,
        /** True when the heading comes from a gyro or the road, not raw GPS. */
        headingTrusted: Boolean = false
    ): Boolean {
        val kph = speedMps * 3.6

        val targetZoom = if (overview) 13.0
                         else zoomForSpeed(kph) + maneuverZoomBoost(maneuverDistM, speedMps)
        // Not zero: the map's own pitch is clamped to [TILT_MIN, TILT_MAX] so
        // a gesture cannot flatten it, and asking for a pitch the map will
        // refuse would leave this filter's idea of the tilt and the map's
        // permanently disagreeing.
        val targetTilt = if (overview) TILT_MIN else TILT

        // Freeze the heading when we are barely moving; keep the last good one.
        val minSpeed = if (headingTrusted) HEADING_MIN_SPEED_TRUSTED else HEADING_MIN_SPEED
        val targetBearing = if (!overview && rawBearing != null && speedMps >= minSpeed) {
            rawBearing
        } else if (overview) 0.0 else bearing

        if (!started) {
            started = true
            bearing = targetBearing
            zoom = targetZoom
            tilt = targetTilt
            return true
        }

        val dt = dtSeconds.coerceIn(0.005, 0.5)
        val aBearing = 1.0 - kotlin.math.exp(-dt / TAU_BEARING)
        val aZoom = 1.0 - kotlin.math.exp(-dt / TAU_ZOOM)
        val aTilt = 1.0 - kotlin.math.exp(-dt / TAU_TILT)

        val turn = shortestTurn(bearing, targetBearing)
        // Snap through a big change (a U-turn, or the first fix after a tunnel)
        // instead of spinning the long way round slowly.
        bearing = if (abs(turn) > 90.0) targetBearing
                  else (bearing + turn * aBearing + 360.0) % 360.0

        zoom += (targetZoom - zoom) * aZoom
        tilt += (targetTilt - tilt) * aTilt

        return true
    }

    fun reset() {
        started = false
        bearing = 0.0
        zoom = ZOOM.first()
        tilt = TILT
    }

    /**
     * Point the map somewhere immediately, without easing.
     *
     * Waze swings the map round to face the first leg of the route the moment
     * you accept it, standing still, before you have moved a metre. It is the
     * first thing that tells you which way you are about to set off, and a
     * heading filter fed by a stationary GPS can never produce it: the car has
     * no heading yet, but the *route* does.
     */
    fun faceBearing(deg: Double) {
        bearing = Geo.normalizeDeg(deg)
        started = true
    }

    /**
     * Top padding, in pixels, that puts the puck at PUCK_SCREEN_FRACTION down a
     * viewport of the given height.
     *
     * MapLibre centres the target inside the rectangle left after padding, so
     * the centre lands at `top + (H - top - bottom) / 2`. Setting that equal to
     * `f * H` gives `top = (2f - 1)H + bottom`. The bottom term is what stops
     * the car marker sliding under the bottom strip -- pass the height of
     * whatever chrome is overlapping the map down there.
     */
    @JvmOverloads
    fun topPaddingPx(viewportHeightPx: Int, bottomInsetPx: Int = 0): Int =
        max(0.0, viewportHeightPx * (2.0 * PUCK_SCREEN_FRACTION - 1.0) + bottomInsetPx).toInt()

    /**
     * Where to aim the camera. Looking slightly *ahead* of the car rather than
     * at it keeps the road you are about to drive in frame through a bend.
     */
    fun lookAhead(lat: Double, lon: Double, speedMps: Double, bearingDeg: Double?): DoubleArray {
        if (bearingDeg == null || speedMps < HEADING_MIN_SPEED) return doubleArrayOf(lat, lon)
        // Kept small on purpose: the low anchor and the tilt already show the
        // road ahead, and a large geographic lead pushes the car down the
        // screen towards the chrome -- the exact bug this replaces.
        val lead = min(60.0, speedMps * 2.0)
        return Geo.destination(lat, lon, bearingDeg, lead)
    }
}
