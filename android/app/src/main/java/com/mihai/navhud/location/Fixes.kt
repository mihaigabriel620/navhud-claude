package com.mihai.navhud.location

import android.location.Location
import android.os.SystemClock
import com.mihai.navhud.Geo

/**
 * Turns Android `Location` objects into filtered fixes.
 *
 * Kept apart from `FixFilter` so the filter itself stays free of Android types
 * and can be unit-tested, and shared between the service and the map screen so
 * they cannot end up disagreeing about which fixes are real.
 */
object Fixes {

    /** Below this a fix has not really moved, so its direction means nothing. */
    private const val MIN_STEP_FOR_BEARING_M = 3.0
    /** A synthesised bearing is only meaningful between two nearby-in-time fixes. */
    private const val MIN_BEARING_DT_S = 0.05
    private const val MAX_BEARING_DT_S = 10.0

    /** Below this the car is not going anywhere worth taking a bearing from. */
    const val MIN_SPEED_FOR_BEARING_MPS = 3.0

    /** Ceiling on the accuracy slack, expressed as a speed. */
    private const val MAX_SLACK_MPS = 2.5

    fun of(loc: Location, nowElapsedMs: Long = SystemClock.elapsedRealtime()) = Fix(
        lat = loc.latitude,
        lon = loc.longitude,
        accuracyM = if (loc.hasAccuracy()) loc.accuracy else 999f,
        speedMps = if (loc.hasSpeed()) loc.speed else -1f,
        bearingDeg = if (loc.hasBearing()) loc.bearing else null,
        // The monotonic clock, never Location.getTime(): wall time can jump
        // backwards when the phone syncs, and a negative dt makes the teleport
        // test nonsense.
        elapsedMs = (loc.elapsedRealtimeNanos / 1_000_000L).takeIf { it > 0L } ?: nowElapsedMs,
        provider = loc.provider ?: "?"
    )

    /**
     * Runs a fix through the filter and, if it survives, returns a copy of the
     * Location carrying whatever the filter had to work out for itself.
     *
     * Speed and bearing are optional in Android's API and network-provider
     * fixes routinely omit both. Everything downstream keys off them — the
     * heading filter refuses to start without a speed, the camera stops tilting,
     * the map stops rotating — so when they are missing they are derived from
     * the previous accepted fix rather than defaulted to zero.
     *
     * @return the Location to use, or null when the fix was rejected.
     */
    fun accept(filter: FixFilter, loc: Location): Location? {
        val prev = filter.last
        if (!filter.accept(of(loc))) return null
        val f = filter.last ?: return null

        val out = Location(loc)
        if (f.speedMps < 0f) {
            val derived = derivedSpeed(prev, f)
            out.speed = derived
        }
        if (!loc.hasBearing() && prev != null) {
            // Bounded in time as well as in distance, the way derivedSpeed is.
            // Without the time bound, the first fix after a three-kilometre
            // tunnel took the straight line from the entrance to the exit as
            // the car's heading. That is not merely displayed: above 7 m/s it
            // also feeds the mounting-offset learner, which *persists* what it
            // learns -- so one tunnel could write a seventy-degree error to
            // disk and poison every later drive.
            val dtS = (f.elapsedMs - prev.elapsedMs) / 1000.0
            val step = Geo.haversine(prev.lat, prev.lon, f.lat, f.lon)
            // A *speed*, not a distance. The threshold used to be a flat three
            // metres between fixes, which meant "above 11 km/h" while the GPS
            // reported once a second -- and "above 54 km/h" once the service
            // started asking the chip for everything it will give. A constant
            // in metres per fix is a constant that changes meaning when the
            // fix rate does.
            if (dtS in MIN_BEARING_DT_S..MAX_BEARING_DT_S &&
                step >= MIN_SPEED_FOR_BEARING_MPS * dtS && step >= MIN_STEP_FOR_BEARING_M) {
                out.bearing = Geo.bearing(prev.lat, prev.lon, f.lat, f.lon).toFloat()
            }
        }
        return out
    }

    private fun derivedSpeed(prev: Fix?, f: Fix): Float {
        if (prev == null) return 0f
        val dtS = (f.elapsedMs - prev.elapsedMs) / 1000.0
        if (dtS <= 0.05 || dtS > 10.0) return 0f
        val d = Geo.haversine(prev.lat, prev.lon, f.lat, f.lon)
        // Accuracy noise alone can look like a few km/h while parked; subtract
        // enough of it that a stationary car reads as stationary.
        //
        // Capped, because the slack is a distance and the interval is not. At
        // one fix a second, subtracting a quarter of the combined accuracy is
        // about 9 km/h off a +/-5 m pair; at five fixes a second the same
        // metres are 45 km/h, so a car genuinely doing 50 read as 5 and
        // anything slower read as parked.
        val slackM = ((prev.accuracyM + f.accuracyM) * 0.25)
            .coerceAtMost(MAX_SLACK_MPS * dtS)
        val moved = d - slackM
        if (moved <= 0.0) return 0f
        return (moved / dtS).toFloat().coerceIn(0f, 70f)
    }
}
