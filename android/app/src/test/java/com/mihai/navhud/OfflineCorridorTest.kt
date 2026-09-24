package com.mihai.navhud

import com.mihai.navhud.map.OfflineCorridor
import com.mihai.navhud.map.OfflineCorridor.CHUNK_M
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rolling offline chunk: only the next stretch of a long route is kept on
 * the device, refilled as the car uses it up.
 */
class OfflineCorridorTest {

    @Test fun `a new route gets a chunk straight away, from just behind the car`() {
        assertEquals(0.0, OfflineCorridor.nextChunkStart(null, 0.0, 2_000_000.0)!!, 1e-9)
        assertEquals(4_000.0, OfflineCorridor.nextChunkStart(null, 5_000.0, 2_000_000.0)!!, 1e-9)
    }

    @Test fun `the next chunk comes once half of this one is used up`() {
        val total = 2_000_000.0
        assertNull(OfflineCorridor.nextChunkStart(0.0, CHUNK_M / 2 - 1.0, total))
        val next = OfflineCorridor.nextChunkStart(0.0, CHUNK_M / 2, total)!!
        assertEquals(CHUNK_M / 2 - OfflineCorridor.BEHIND_M, next, 1e-9)
    }

    @Test fun `a Belgium to Romania drive needs about fifty chunks, never all at once`() {
        val total = 2_000_000.0
        var start: Double? = null
        var chunks = 0
        var along = 0.0
        while (along < total) {
            OfflineCorridor.nextChunkStart(start, along, total)?.let { start = it; chunks++ }
            // The car is always inside the current chunk, with at least half
            // a chunk -- or the rest of the route -- still ahead of it.
            val s = start!!
            assertTrue(along >= s)
            assertTrue(s + CHUNK_M - along >= minOf(CHUNK_M / 2, total - along) - 1e-6)
            along += 100.0
        }
        assertTrue("$chunks chunks", chunks in 45..55)
    }

    @Test fun `no more chunks once the current one reaches the destination`() {
        assertNull(OfflineCorridor.nextChunkStart(50_000.0, 100_000.0, 120_000.0))
    }

    @Test fun `the corridor is the route plus a line either side, within budget`() {
        // 150 km of route, a vertex every 20 m: far more points than allowed.
        val n = 7501
        val pts = Array(n) { i -> Geo.destination(50.0, 4.0, 90.0, i * 20.0) }
        val cum = Geo.cumulative(pts)
        val lines = OfflineCorridor.corridor(pts, cum, 10_000.0, 10_000.0 + CHUNK_M)
        assertEquals(3, lines.size)
        assertTrue(lines.sumOf { it.size } <= OfflineCorridor.MAX_POINTS)
        val centre = lines[0]
        val from = Geo.pointAlong(pts, cum, 10_000.0)
        val to = Geo.pointAlong(pts, cum, 10_000.0 + CHUNK_M)
        assertEquals(0.0, Geo.haversine(from[0], from[1], centre.first()[0], centre.first()[1]), 1e-6)
        assertEquals(0.0, Geo.haversine(to[0], to[1], centre.last()[0], centre.last()[1]), 1e-6)
        for (side in listOf(lines[1], lines[2])) {
            for (i in side.indices step 50) {
                val d = Geo.haversine(centre[i][0], centre[i][1], side[i][0], side[i][1])
                assertEquals(OfflineCorridor.HALF_WIDTH_M, d, 1.0)
            }
        }
        // One line north of the road, one south.
        assertTrue(lines[1][100][0] > centre[100][0])
        assertTrue(lines[2][100][0] < centre[100][0])
    }

    @Test fun `past the end there is nothing to fetch`() {
        val pts = Array(11) { i -> Geo.destination(50.0, 4.0, 0.0, i * 100.0) }
        val cum = Geo.cumulative(pts)
        assertTrue(OfflineCorridor.corridor(pts, cum, 2_000.0, 3_000.0).isEmpty())
    }
}
