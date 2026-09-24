package com.mihai.navhud.map

import com.mihai.navhud.nav.Route

/**
 * The route line, cut to what is still ahead of the car.
 *
 * Waze draws nothing behind you: the line starts under the arrow. MapActivity
 * builds it from two pieces -- a short full-resolution stretch from the arrow
 * onwards, rebuilt many times a second, and the thinned remainder, rebuilt
 * rarely -- and both come from [routeSlice]. Pure, so the geometry is tested
 * without a map.
 */
object RouteLine {

    /** One run of equal traffic level: a GeoJSON feature with a "level". */
    class Run(val level: Int, val pts: List<DoubleArray>)

    /**
     * Traffic level per segment -- 0 unknown, 1 free .. 4 standstill, 5 closed
     * -- with runs too short to see absorbed into their neighbour.
     *
     * Traffic alternates segment by segment on a busy motorway, and one GeoJSON
     * feature per run would undo the point-count cap entirely -- thousands of
     * two-point features instead of one line. So runs shorter than [minRunM],
     * or than the sampling [stride], take the previous run's level: they were
     * never visible as separate colours anyway. Closures are exempt; a closed
     * stretch is worth a feature of its own.
     *
     * Measured in *metres*, not only in stride: a typical 6 km urban route is
     * 400-1200 points, under any stride, and every congestion transition used
     * to become its own feature -- a visible lurch on the redraw after a
     * reroute.
     */
    fun trafficLevels(route: Route, stride: Int, minRunM: Double): IntArray {
        val nSeg = route.pts.size - 1
        if (nSeg < 1) return IntArray(0)
        val levels = IntArray(nSeg) { i ->
            if (route.closed.getOrElse(i) { false }) 5
            else route.congestion.getOrElse(i) { 0 }
        }
        var i = 0
        while (i < nSeg) {
            var j = i
            while (j + 1 < nSeg && levels[j + 1] == levels[i]) j++
            val runM = route.cum[j + 1] - route.cum[i]
            if ((runM < minRunM || j - i + 1 < stride) && levels[i] != 5 && i > 0) {
                for (k in i..j) levels[k] = levels[i - 1]
            }
            i = j + 1
        }
        return levels
    }

    /**
     * The route between [fromM] and [toM] metres, as runs of equal traffic
     * level.
     *
     * The first point is interpolated at [fromM] -- the line starts exactly
     * under the arrow, not at the last vertex passed -- and the last at [toM],
     * so two slices that meet at the same distance meet at the same point.
     * Neighbouring runs share their boundary vertex, so the line is continuous
     * where the colour changes. With [stride] > 1 only every stride-th vertex
     * is kept inside a run; its ends are always exact.
     *
     * @param levels [trafficLevels] of the same route
     */
    fun routeSlice(
        route: Route, fromM: Double, toM: Double, levels: IntArray, stride: Int = 1
    ): List<Run> {
        val pts = route.pts
        val cum = route.cum
        if (pts.size < 2) return emptyList()
        val total = cum.last()
        val a = fromM.coerceIn(0.0, total)
        val b = toM.coerceIn(0.0, total)
        if (b - a < 0.01) return emptyList()
        val i0 = segmentAt(cum, a)
        val i1 = segmentAt(cum, b)

        val runs = ArrayList<Run>()
        var level = levels.getOrElse(i0) { 0 }
        var cur = arrayListOf(pointAt(route, i0, a))
        for (s in i0 + 1..i1) {
            // Vertex s ends segment s-1 and starts segment s.
            val lv = levels.getOrElse(s) { 0 }
            if (lv != level) {
                cur.add(pts[s])
                if (cur.size >= 2) runs.add(Run(level, cur))
                cur = arrayListOf(pts[s])
                level = lv
            } else if ((s - i0) % stride == 0) {
                cur.add(pts[s])
            }
        }
        val end = pointAt(route, i1, b)
        val last = cur.last()
        if (end[0] != last[0] || end[1] != last[1]) cur.add(end)
        if (cur.size >= 2) runs.add(Run(level, cur))
        return runs
    }

    /** The segment holding [m]: the last i with cum[i] <= m, at most size - 2. */
    private fun segmentAt(cum: DoubleArray, m: Double): Int {
        var lo = 0
        var hi = cum.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cum[mid] <= m) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun pointAt(route: Route, seg: Int, m: Double): DoubleArray {
        val p = route.pts[seg]
        val q = route.pts[seg + 1]
        val len = route.cum[seg + 1] - route.cum[seg]
        val t = if (len > 1e-6) ((m - route.cum[seg]) / len).coerceIn(0.0, 1.0) else 0.0
        return doubleArrayOf(p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t)
    }
}
