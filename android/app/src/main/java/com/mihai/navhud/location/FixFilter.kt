package com.mihai.navhud.location

import com.mihai.navhud.Geo
import kotlin.math.abs

/** A location fix, stripped of Android types so the filter can be unit-tested. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float,
    val bearingDeg: Float?,
    /** Monotonic clock, milliseconds. Never wall time — that can jump. */
    val elapsedMs: Long,
    val provider: String
) {
    val isGps: Boolean get() = provider == "gps" || provider == "fused" || provider == "demo"
}

/**
 * Throws away the fixes that make the car jump to another road.
 *
 * The app was subscribing to both GPS and the network provider and simply
 * using whichever arrived last. A network fix is derived from cell towers and
 * wifi and can be a kilometre wide; taking one of those while a perfectly good
 * GPS fix exists is precisely how the marker ends up on a different street.
 *
 * Three rules, in order of how much damage they prevent:
 *
 *  1. **GPS wins.** A network fix is only used when GPS has gone quiet for
 *     NETWORK_GRACE_MS — leaving a tunnel, or a cold start indoors.
 *  2. **Accuracy gate.** Anything worse than MAX_ACCURACY_M is refused while a
 *     recent good fix exists. A 300 m fix is not an improvement on a 5 m one
 *     that is two seconds old.
 *  3. **No teleports.** A fix implying a speed no car achieves is refused,
 *     which catches the single wild outlier that reflections off buildings
 *     produce in a city.
 */
class FixFilter {

    companion object {
        /** Worse than this is refused while something better is recent. */
        const val MAX_ACCURACY_M = 50f

        /** ...and this is refused outright, whatever else we have. */
        const val ABSURD_ACCURACY_M = 200f

        /** How long GPS may be silent before a network fix is allowed through. */
        const val NETWORK_GRACE_MS = 10_000L

        /** A fix implying more than this is a glitch, not a car. 70 m/s ~ 250 km/h. */
        const val MAX_PLAUSIBLE_MPS = 70.0

        /** Below this the existing fix is simply kept; nothing has changed. */
        const val STALE_AFTER_MS = 15_000L

        /**
         * Refuse this many in a row and the *filter* is what is wrong, not the
         * fixes. Every rule here compares against the last accepted fix, so one
         * bad accepted fix — a mock-location app writing wall time into the
         * monotonic field, an old HAL — could otherwise reject everything that
         * followed, for ever, and the only cure would be killing the app.
         */
        const val MAX_CONSECUTIVE_REJECTS = 12

        /**
         * A network fix arriving while the route is still snapped on GPS: a
         * tunnel, nearly always. Past [NETWORK_GRACE_MS] the filter lets cell
         * fixes through, and the service used to take them -- which ended its
         * coasting and put the car up to 200 m off the line, so it rerouted
         * from a point on the surface. Ignored instead, the route coasts; once
         * coasting gives up the snap is no longer trusted and they are used.
         */
        fun ignoreOnRoute(isGps: Boolean, lastWasGps: Boolean, routeSnapTrusted: Boolean): Boolean =
            !isGps && lastWasGps && routeSnapTrusted
    }

    var last: Fix? = null
        private set

    private var lastGpsMs = 0L
    private var rejects = 0

    /** Why the most recent fix was refused, for the diagnostics screen. */
    var lastRejection: String? = null
        private set

    /** @return true when the fix was accepted and `last` now holds it. */
    fun accept(f: Fix): Boolean {
        lastRejection = null
        // The absurd-accuracy gate is checked *before* the deadlock breaker on
        // purpose. It compares only against the incoming fix, so it can never
        // be part of the self-latching deadlock the counter exists to break --
        // and letting it through every thirteenth fix teleported a parked car
        // in an underground car park across the neighbourhood once every
        // thirteen seconds.
        if (f.accuracyM > ABSURD_ACCURACY_M) {
            return refuse("accuracy ${f.accuracyM.toInt()} m")
        }

        if (rejects >= MAX_CONSECUTIVE_REJECTS) {
            // Break the deadlock: trust this one and start again from it.
            reset()
            return take(f)
        }

        val prev = last
        val haveRecent = prev != null && (f.elapsedMs - prev.elapsedMs) < STALE_AFTER_MS

        // 1. GPS beats the network provider unless GPS has actually gone away.
        if (!f.isGps && haveRecent && (f.elapsedMs - lastGpsMs) < NETWORK_GRACE_MS) {
            return refuse("network fix while GPS is live")
        }

        // 2. Don't downgrade a good fix for a vague one.
        if (f.accuracyM > MAX_ACCURACY_M && haveRecent && prev!!.accuracyM <= MAX_ACCURACY_M) {
            return refuse("accuracy ${f.accuracyM.toInt()} m, keeping ${prev.accuracyM.toInt()} m")
        }

        // 3. Reject the single wild outlier: a jump no car could have made.
        if (prev != null) {
            val dtS = (f.elapsedMs - prev.elapsedMs) / 1000.0
            if (dtS <= 0.0) return refuse("out of order")
            if (dtS < STALE_AFTER_MS / 1000.0) {
                val jump = Geo.haversine(prev.lat, prev.lon, f.lat, f.lon)
                // Allow for the accuracy circles overlapping before calling it a jump.
                val slack = (prev.accuracyM + f.accuracyM).toDouble()
                if (jump - slack > MAX_PLAUSIBLE_MPS * dtS) {
                    return refuse("jumped ${jump.toInt()} m in ${"%.1f".format(dtS)} s")
                }
            }
        }

        return take(f)
    }

    private fun take(f: Fix): Boolean {
        last = f
        rejects = 0
        if (f.isGps) lastGpsMs = f.elapsedMs
        return true
    }

    private fun refuse(why: String): Boolean {
        lastRejection = why
        rejects++
        return false
    }

    /** True when nothing usable has arrived for a while. */
    fun isStale(nowElapsedMs: Long, limitMs: Long = 8000L): Boolean {
        val l = last ?: return true
        return nowElapsedMs - l.elapsedMs > limitMs
    }

    fun reset() {
        last = null
        lastGpsMs = 0L
        lastRejection = null
        rejects = 0
    }
}
