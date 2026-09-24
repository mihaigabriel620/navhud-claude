package com.mihai.navhud.map

import com.mihai.navhud.Geo

/**
 * Which stretch of the route to keep downloaded, and its shape.
 *
 * Not the whole route: Belgium to Romania is 2000 km, and caching all of it
 * the moment the route is accepted is gigabytes the car may never need -- a
 * reroute throws it away. So a rolling chunk: the next [CHUNK_M] of route,
 * replaced by a fresh one once the car is halfway through it, so there is
 * always at least half a chunk (about half an hour at motorway speed) of map
 * already on the device ahead of the car. OfflineRoutes does the MapLibre
 * side; this is the geometry, pure so it can be tested.
 */
object OfflineCorridor {

    /** Length of one chunk, metres. */
    const val CHUNK_M = 80_000.0

    /** A new chunk starts this far behind the car, so the join is covered. */
    const val BEHIND_M = 1_000.0

    /**
     * Width either side of the route, metres.
     *
     * A line alone would fetch only the tiles it crosses, and a route running
     * along a tile edge would have nothing on the other side. Two offset lines
     * a kilometre out, under a z14 tile's width even in Scandinavia, fill the
     * corridor with no gaps.
     */
    const val HALF_WIDTH_M = 1_000.0

    /** Point budget for the three lines together. */
    const val MAX_POINTS = 2000

    /**
     * Where a new chunk should start, or null if the current one still covers
     * the car well enough.
     *
     * @param currentStartM start of the chunk already made for this route,
     *                      or null when there is none yet
     */
    fun nextChunkStart(currentStartM: Double?, alongM: Double, totalM: Double): Double? {
        if (currentStartM != null) {
            // Already reaching the destination: nothing more to fetch.
            if (currentStartM + CHUNK_M >= totalM && alongM >= currentStartM) return null
            if (alongM >= currentStartM && alongM < currentStartM + CHUNK_M / 2) return null
        }
        return (alongM - BEHIND_M).coerceIn(0.0, maxOf(0.0, totalM))
    }

    /**
     * The route from [fromM] to [toM], thinned to fit [maxPoints], plus the
     * same line offset [halfWidthM] to either side: three lines, [lat, lon].
     */
    fun corridor(
        pts: Array<DoubleArray>,
        cum: DoubleArray,
        fromM: Double,
        toM: Double,
        maxPoints: Int = MAX_POINTS,
        halfWidthM: Double = HALF_WIDTH_M
    ): List<List<DoubleArray>> {
        if (pts.size < 2) return emptyList()
        val a = fromM.coerceIn(0.0, cum.last())
        val b = toM.coerceIn(0.0, cum.last())
        if (b - a < 1.0) return emptyList()

        val all = ArrayList<DoubleArray>()
        all.add(Geo.pointAlong(pts, cum, a))
        for (i in pts.indices) if (cum[i] > a && cum[i] < b) all.add(pts[i])
        all.add(Geo.pointAlong(pts, cum, b))

        val perLine = maxOf(2, maxPoints / 3)
        val stride = maxOf(1, (all.size + perLine - 3) / (perLine - 1))
        val centre = ArrayList<DoubleArray>()
        var k = 0
        while (k < all.size - 1) { centre.add(all[k]); k += stride }
        centre.add(all.last())

        val left = ArrayList<DoubleArray>(centre.size)
        val right = ArrayList<DoubleArray>(centre.size)
        for (i in centre.indices) {
            val p = centre[maxOf(0, i - 1)]
            val q = centre[minOf(centre.size - 1, i + 1)]
            val brg = Geo.bearing(p[0], p[1], q[0], q[1])
            val c = centre[i]
            left.add(Geo.destination(c[0], c[1], brg - 90.0, halfWidthM))
            right.add(Geo.destination(c[0], c[1], brg + 90.0, halfWidthM))
        }
        return listOf(centre, left, right)
    }
}
