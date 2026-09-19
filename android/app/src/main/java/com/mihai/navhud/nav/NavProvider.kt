package com.mihai.navhud.nav

/** A point on the earth. */
data class LatLon(val lat: Double, val lon: Double)

/** A maneuver pinned to a distance along the route polyline. */
data class ManeuverPoint(
    val alongM: Double,
    val code: Int,
    val exit: Int,
    val name: String,
    /** Which lanes to be in, when the road has them. Usually null. */
    val lanes: LaneGuidance? = null,
    /** Exit number and destinations, for the junction sign. */
    val sign: JunctionSign? = null,
    /**
     * Which way the roundabout exit actually points, in degrees from the road
     * you came in on: 0 straight ahead, positive to the right. Null when this
     * is not a roundabout or the router did not say.
     *
     * Both displays used to derive this from the exit NUMBER, through the same
     * seven-entry table. That is a guess, and a confident one: on a three-exit
     * roundabout "exit 2" is almost always dead ahead and the table said 30
     * degrees. Mapbox has carried the real answer all along -- bearing_before
     * and bearing_after on the step's maneuver -- and nobody was reading it.
     */
    val exitBearing: Int? = null
)

/**
 * A route, already flattened into the shape the tracker wants:
 * one polyline, one cumulative-distance array, one speed limit per segment.
 */
class Route(
    val pts: Array<DoubleArray>,      // [lat, lon] per vertex
    val cum: DoubleArray,             // metres from start, same length as pts
    val limitKph: IntArray,           // per segment (pts.size - 1); 0 unknown, -1 none
    val maneuvers: List<ManeuverPoint>,
    val totalDistanceM: Double,
    val totalDurationS: Double,
    val provider: String,
    /** "E40 · A12" — the roads this route mostly uses, for the picker. */
    val summary: String = "",
    /** Duration without traffic, when the provider distinguishes. */
    val durationTypicalS: Double = totalDurationS,
    /**
     * Live traffic per polyline segment, 0..4: unknown, free, moderate, heavy,
     * standstill. Drawn on the route line the way Waze colours it.
     */
    val congestion: IntArray = IntArray(0),
    /** Segments the provider reports as closed — roadworks, accidents, events. */
    val closed: BooleanArray = BooleanArray(0),
    /** Bitmask of [RouteTrait]: what this route makes you drive through. */
    val traits: Int = 0
) {
    val destination: LatLon
        get() = pts.last().let { LatLon(it[0], it[1]) }

    /** Seconds lost to traffic versus a clear run. Negative means clearer. */
    val trafficDelayS: Double get() = totalDurationS - durationTypicalS

    /**
     * The stretch of route polyline within `halfM` of `alongM`.
     *
     * Used to ask "does this OSM way carry my route here?" without scanning
     * a two-hundred-kilometre polyline for every camera.
     */
    fun window(alongM: Double, halfM: Double): Array<DoubleArray> {
        if (pts.size < 2) return pts
        val lo = alongM - halfM
        val hi = alongM + halfM
        var a = 0
        while (a < cum.size - 1 && cum[a + 1] < lo) a++
        var b = a
        while (b < cum.size - 1 && cum[b] <= hi) b++
        // Empty, not the whole route. Handing back a 200,000-point polyline as
        // "the route near this camera" would run onOurRoad's nearest-point
        // search over all of it for every road in the area, four times a
        // second. Callers already treat a window shorter than two points as
        // "no window".
        if (b - a < 1) return emptyArray()
        return Array(b - a + 1) { pts[a + it] }
    }

    /** Traffic level on the segment under `alongM`, 0 when not reported. */
    fun congestionAt(alongM: Double): Int {
        if (congestion.isEmpty()) return 0
        val i = segmentAt(alongM)
        return if (i in congestion.indices) congestion[i] else 0
    }

    fun has(trait: Int) = traits and trait != 0

    /** Metres of the route that are closed, for the picker's warning line. */
    val closedM: Double get() {
        if (closed.isEmpty()) return 0.0
        var m = 0.0
        for (i in closed.indices) if (closed[i]) m += cum[i + 1] - cum[i]
        return m
    }

    /** Distance to the next closed stretch ahead, or -1 when the way is clear. */
    fun metresToClosure(alongM: Double): Double {
        if (closed.isEmpty()) return -1.0
        var i = segmentAt(alongM)
        while (i < closed.size) {
            if (closed[i]) return maxOf(0.0, cum[i] - alongM)
            i++
        }
        return -1.0
    }

    private fun segmentAt(alongM: Double): Int {
        if (cum.size < 2) return 0
        var lo = 0
        var hi = cum.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cum[mid] <= alongM) lo = mid else hi = mid - 1
        }
        return lo
    }
}

/**
 * The things about a route you would want to know *before* setting off, not
 * halfway down it.
 *
 * Mapbox reports these per intersection, in `steps[].intersections[].classes`,
 * which is a slightly odd place to look for a whole-route property — but it is
 * where the data is, and a route that touches one tolled intersection is a
 * tolled route.
 *
 * [LEZ] is the exception: no router reports low-emission zones, so it is
 * filled in separately from OpenStreetMap's `boundary=low_emission_zone`
 * areas. It matters here more than most places — Brussels, Antwerp and Ghent
 * all run one, and driving into one in the wrong car is a fine in the post
 * rather than a sign at the roadside.
 */
object RouteTrait {
    const val TOLL       = 1 shl 0
    const val FERRY      = 1 shl 1
    const val RESTRICTED = 1 shl 2      // permit-only or private stretches
    const val TUNNEL     = 1 shl 3
    const val UNPAVED    = 1 shl 4
    const val CLOSURE    = 1 shl 5      // roadworks or an incident on the way
    const val LEZ        = 1 shl 6

    fun fromClass(s: String?): Int = when (s) {
        "toll" -> TOLL
        "ferry" -> FERRY
        "restricted" -> RESTRICTED
        "tunnel" -> TUNNEL
        "unpaved" -> UNPAVED
        else -> 0
    }
}

class NavException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Swap providers by implementing this. The tracker, the serial link and the
 * Arduino know nothing about who drew the map.
 */
interface NavProvider {
    val name: String

    /**
     * Blocking network call. Returns the recommended route first, then any
     * alternatives. Callers run it off the main thread.
     *
     * @param headingDeg which way the car is pointing. Passing it stops a
     *   reroute from opening with "make a U-turn" simply because the other
     *   carriageway happened to be a metre nearer the fix.
     */
    fun routes(
        from: LatLon,
        to: LatLon,
        alternatives: Boolean = true,
        headingDeg: Double? = null
    ): List<Route>

    /** Convenience for callers that only want the recommended route. */
    fun route(from: LatLon, to: LatLon): Route =
        routes(from, to, alternatives = false).firstOrNull()
            ?: throw NavException("No route found")

    /** Free-text place search. Returns null when nothing matched. */
    fun geocode(query: String, near: LatLon?): LatLon?

    /** ISO-3166 alpha-2 country at a point, for the camera-warning rules. */
    fun countryAt(point: LatLon): String? = null
}
