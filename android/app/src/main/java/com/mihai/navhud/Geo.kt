package com.mihai.navhud

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry helpers. Everything here is pure and side-effect free so it can be
 * checked against the Python reference in tools/geo_reference.py.
 */
object Geo {

    const val EARTH_R = 6371008.8            // IUGG mean radius, metres
    private const val DEG = Math.PI / 180.0

    /** How far behind the last match we still consider, to absorb GPS jitter. */
    const val BACKTRACK_M = 50.0

    /** Windowed match worse than this triggers one full-polyline re-search. */
    const val WIDEN_IF_CROSS_OVER_M = 60.0

    // ---- encoded polyline ---------------------------------------------------

    /**
     * Decodes a Google/Mapbox encoded polyline.
     * @param precision 5 for `polyline`, 6 for `polyline6`.
     * @return array of [lat, lon] pairs.
     */
    fun decodePolyline(encoded: String, precision: Int = 6): Array<DoubleArray> {
        val factor = 10.0.pow(precision)
        val out = ArrayList<DoubleArray>(encoded.length / 4 + 8)
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            var shift = 0
            var result = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)

            out.add(doubleArrayOf(lat / factor, lng / factor))
        }
        return out.toTypedArray()
    }

    // ---- distances ----------------------------------------------------------

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * DEG
        val dLon = (lon2 - lon1) * DEG
        val a = sin(dLat / 2).pow(2) +
                cos(lat1 * DEG) * cos(lat2 * DEG) * sin(dLon / 2).pow(2)
        return 2 * EARTH_R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Cumulative distance from the first vertex to each vertex, in metres. */
    fun cumulative(pts: Array<DoubleArray>): DoubleArray {
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            cum[i] = cum[i - 1] +
                    haversine(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1])
        }
        return cum
    }

    /** Initial bearing from 1 to 2, degrees clockwise from north, 0..360. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * DEG
        val p2 = lat2 * DEG
        val dl = (lon2 - lon1) * DEG
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        val deg = atan2(y, x) / DEG
        return (deg + 360.0) % 360.0
    }

    /** Smallest absolute difference between two bearings, 0..180. */
    fun bearingDelta(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }

    /** Point reached by travelling `dist` metres from (lat, lon) on `brgDeg`. */
    fun destination(lat: Double, lon: Double, brgDeg: Double, dist: Double): DoubleArray {
        val d = dist / EARTH_R
        val b = brgDeg * DEG
        val p1 = lat * DEG
        val l1 = lon * DEG
        val p2 = kotlin.math.asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return doubleArrayOf(p2 / DEG, ((l2 / DEG) + 540.0) % 360.0 - 180.0)
    }

    /** Interpolates a position `alongM` metres into the polyline. */
    fun pointAlong(pts: Array<DoubleArray>, cum: DoubleArray, alongM: Double): DoubleArray {
        if (pts.isEmpty()) return doubleArrayOf(0.0, 0.0)
        if (alongM <= 0.0) return pts[0].copyOf()
        if (alongM >= cum.last()) return pts.last().copyOf()
        // First segment whose far end reaches alongM. A binary search, not a
        // scan from vertex 0: the map calls this several times a frame on a
        // route that can be tens of thousands of points long.
        var i = 0
        var hi = cum.size - 2
        while (i < hi) {
            val mid = (i + hi) ushr 1
            if (cum[mid + 1] < alongM) i = mid + 1 else hi = mid
        }
        val segLen = cum[i + 1] - cum[i]
        val t = if (segLen > 1e-6) (alongM - cum[i]) / segLen else 0.0
        return doubleArrayOf(
            pts[i][0] + (pts[i + 1][0] - pts[i][0]) * t,
            pts[i][1] + (pts[i + 1][1] - pts[i][1]) * t
        )
    }

    /**
     * Direction the road is running at `alongM`, in degrees.
     *
     * Taken across a short span rather than from the single segment underneath
     * the car, because a polyline vertex is a discontinuity: read one segment at
     * a time and the arrow snaps through corners in one frame. Sampling a few
     * metres either side rotates it through the corner the way a car actually
     * turns, which is what Waze's marker does.
     */
    fun bearingAlong(
        pts: Array<DoubleArray>,
        cum: DoubleArray,
        alongM: Double,
        spanM: Double = 8.0
    ): Double? {
        if (pts.size < 2) return null
        val total = cum.last()
        if (total < 1e-6) return null
        // Keep the window inside the route, and never let it collapse to zero.
        var a = alongM - spanM
        var b = alongM + spanM
        if (a < 0.0) { a = 0.0; b = min(total, 2 * spanM) }
        if (b > total) { b = total; a = maxOf(0.0, total - 2 * spanM) }
        if (b - a < 0.5) return null
        val p = pointAlong(pts, cum, a)
        val q = pointAlong(pts, cum, b)
        if (haversine(p[0], p[1], q[0], q[1]) < 0.2) return null
        return bearing(p[0], p[1], q[0], q[1])
    }

    /**
     * Signed difference `to - from`, wrapped to -180..180. Positive means `to`
     * is clockwise of `from`. The building block for every heading filter here:
     * averaging bearings naively takes you the long way round 0/360.
     */
    fun signedDelta(to: Double, from: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    fun normalizeDeg(d: Double): Double = ((d % 360.0) + 360.0) % 360.0

    // ---- projection onto the route -----------------------------------------

    /**
     * @param segIndex index of the polyline segment we matched
     * @param t        0..1 along that segment
     * @param along    metres travelled from the route start
     * @param cross    perpendicular distance from the route, metres
     */
    class Snap(val segIndex: Int, val t: Double, val along: Double, val cross: Double)

    /**
     * Projects a fix onto the route.
     *
     * Two things make this more accurate than "nearest vertex":
     *  - it searches a *window* forward of where we were last, so a route that
     *    doubles back on itself does not teleport you to the wrong pass;
     *  - if the fix has a usable heading it penalises segments running the
     *    other way, which is what keeps you off the parallel carriageway.
     *
     * @param fromIdx      segment index of the previous match (0 to start)
     * @param windowMeters how far ahead of fromIdx to look
     * @param headingDeg   GPS bearing, or null when standing still / no heading
     */
    fun project(
        pts: Array<DoubleArray>,
        cum: DoubleArray,
        lat: Double,
        lon: Double,
        fromIdx: Int = 0,
        windowMeters: Double = 400.0,
        headingDeg: Double? = null,
        searchAll: Boolean = false
    ): Snap {
        if (pts.size < 2) return Snap(0, 0.0, 0.0, 0.0)

        val kx = EARTH_R * cos(lat * DEG) * DEG   // metres per degree of longitude
        val ky = EARTH_R * DEG                    // metres per degree of latitude

        // Back off by *distance*, not by a segment count: a fixed number of
        // segments means metres in a town centre and kilometres on a motorway.
        val here = cum[minOf(fromIdx, cum.size - 1)]
        var start = minOf(fromIdx, pts.size - 2)
        if (!searchAll) {
            val backLimit = here - BACKTRACK_M
            while (start > 0 && cum[start] > backLimit) start--
        } else {
            start = 0
        }
        val limit = if (searchAll) Double.MAX_VALUE else here + windowMeters

        var bestCost = Double.MAX_VALUE
        var bestIdx = start
        var bestT = 0.0
        var bestCross = Double.MAX_VALUE

        var i = start
        while (i < pts.size - 1) {
            if (cum[i] > limit && bestCost < Double.MAX_VALUE) break

            val ax = (pts[i][1] - lon) * kx
            val ay = (pts[i][0] - lat) * ky
            val bx = (pts[i + 1][1] - lon) * kx
            val by = (pts[i + 1][0] - lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy

            val t = if (len2 > 1e-9) {
                min(1.0, maxOf(0.0, -(ax * dx + ay * dy) / len2))
            } else 0.0
            val px = ax + t * dx
            val py = ay + t * dy
            val cross = sqrt(px * px + py * py)

            // Heading penalty: up to +60 m of "cost" for a segment pointing the
            // wrong way. Cheap, and it fixes the divided-highway problem.
            var cost = cross
            if (headingDeg != null && len2 > 1e-9) {
                val segBrg = bearing(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1])
                cost += bearingDelta(segBrg, headingDeg) / 180.0 * 60.0
            }

            if (cost < bestCost) {
                bestCost = cost
                bestIdx = i
                bestT = t
                bestCross = cross
            }
            i++
        }

        // If nothing inside the window is a plausible match -- we lost signal in
        // a tunnel, or the route starts a few streets away -- widen to the whole
        // polyline once rather than confidently reporting the wrong segment.
        if (!searchAll && bestCross > WIDEN_IF_CROSS_OVER_M) {
            return project(pts, cum, lat, lon, fromIdx, windowMeters, headingDeg,
                           searchAll = true)
        }

        val segLen = cum[bestIdx + 1] - cum[bestIdx]
        return Snap(bestIdx, bestT, cum[bestIdx] + bestT * segLen, bestCross)
    }
}
