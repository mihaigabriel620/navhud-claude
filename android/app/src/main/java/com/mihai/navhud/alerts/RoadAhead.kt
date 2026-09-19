package com.mihai.navhud.alerts

import com.mihai.navhud.Geo
import com.mihai.navhud.nav.Area
import com.mihai.navhud.nav.RoadFeature

/**
 * The next thing on the road you would want a second's notice about: a railway
 * crossing, a speed bump, a toll booth.
 *
 * Waze warns about all three, and none of them needs a single user report —
 * they are static features of the road, mapped in OpenStreetMap, and the app
 * is already downloading the roads around the car to know their speed limits.
 * So this is one more clause on a query that was being made anyway.
 *
 * ## The distance
 *
 * Scaled by speed, like everything else here, but with a shorter reach than a
 * speed camera: a bump a kilometre ahead is not information, it is clutter.
 * Eight seconds is the number, which is long enough to lift off and short
 * enough that the warning is about the thing in front of you. The floor of
 * 100 m exists because at 30 km/h eight seconds is 67 m and a level crossing
 * behind a bend deserves better than that.
 */
object RoadAhead {

    const val SECONDS_AHEAD = 8.0
    const val MIN_M = 100
    const val MAX_M = 500

    /** Only things roughly in front of us. Tighter than the camera cone: a
     *  bump on the side road you are passing is not your bump. */
    const val CONE_DEG = 45.0

    /**
     * How far off the centreline of *our* road a feature may be and still be
     * ours. A speed bump is physically in the carriageway, so this is tight:
     * the whole complaint was the app warning about a bump that was not even
     * on the road being driven, and a cone alone cannot tell the difference
     * between a bump 80 m ahead on this road and one 80 m ahead on the street
     * running beside it.
     */
    const val ON_ROAD_M = 14.0

    data class Hit(val feature: RoadFeature, val distanceM: Int)

    fun warnDistance(speedKph: Int): Int =
        ((speedKph / 3.6) * SECONDS_AHEAD).toInt().coerceIn(MIN_M, MAX_M)

    /**
     * @param roadPts the centreline of the road being driven — the matched
     *        road in free drive, the route line when there is a route. When it
     *        is null the app does not know which road it is on, and the honest
     *        answer is to say nothing rather than guess from a bearing.
     */
    fun nearest(
        area: Area?,
        lat: Double,
        lon: Double,
        headingDeg: Double?,
        speedKph: Int,
        roadPts: Array<DoubleArray>?
    ): Hit? {
        if (area == null || area.features.isEmpty()) return null
        if (roadPts == null || roadPts.size < 2) return null
        val reach = warnDistance(speedKph).toDouble()
        var best: RoadFeature? = null
        var bestD = Double.MAX_VALUE
        var nearestD = Double.MAX_VALUE
        for (f in area.features) {
            val d = Geo.haversine(lat, lon, f.lat, f.lon)
            if (d > reach) continue
            if (headingDeg != null) {
                val to = Geo.bearing(lat, lon, f.lat, f.lon)
                if (Geo.bearingDelta(to, headingDeg) > CONE_DEG) continue
            }
            val n = com.mihai.navhud.nav.AreaRoads.nearestOn(roadPts, f.lat, f.lon)
            if (n == null || n.first > ON_ROAD_M) continue
            // A toll booth outranks a bump at the same distance: you have to
            // stop for one and slow for the other.
            //
            // Keep the *nearest* distance separately from the winner's. Setting
            // bestD to the toll booth's larger distance moved the bar outwards,
            // so a bump further away than the booth could then beat it on
            // "d < bestD" -- and the app announced neither the nearest feature
            // nor the one the priority rule exists for.
            val preferred = best != null && f.kind == RoadFeature.TOLL_BOOTH &&
                best.kind != RoadFeature.TOLL_BOOTH && d < nearestD * 1.5
            if (d < nearestD) nearestD = d
            if (d < bestD || preferred) { bestD = d; best = f }
        }
        val f = best ?: return null
        return Hit(f, bestD.toInt())
    }
}
