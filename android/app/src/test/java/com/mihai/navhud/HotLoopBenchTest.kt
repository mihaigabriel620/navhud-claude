package com.mihai.navhud

import com.mihai.navhud.alerts.CameraPolicy
import com.mihai.navhud.alerts.SpeedCamera
import com.mihai.navhud.nav.Area
import com.mihai.navhud.nav.RoadWay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the free-drive tick actually costs, with a road network the size of the
 * one the app really loads.
 *
 * Not an assertion of correctness -- a measurement, printed, so a number
 * replaces a guess about where the stutter comes from.
 */
class HotLoopBenchTest {

    private fun bigArea(roads: Int, ptsPerRoad: Int, cameras: Int): Area {
        val ways = ArrayList<RoadWay>(roads)
        var seed = 12345L
        fun rnd(): Double { seed = seed * 6364136223846793005L + 1442695040888963407L
                            return ((seed ushr 11).toDouble() / (1L shl 53).toDouble()) }
        for (r in 0 until roads) {
            val lat0 = 50.78 + rnd() * 0.05
            val lon0 = 4.32 + rnd() * 0.07
            val dLat = (rnd() - 0.5) * 0.004
            val dLon = (rnd() - 0.5) * 0.004
            ways.add(RoadWay(
                id = r.toLong(),
                pts = Array(ptsPerRoad) {
                    doubleArrayOf(lat0 + dLat * it / ptsPerRoad, lon0 + dLon * it / ptsPerRoad)
                },
                name = "Rue $r", ref = "", limitKph = 50, kind = "residential", onewayDir = 0
            ))
        }
        // The car's own road: due north through the middle.
        ways.add(RoadWay(
            id = 999_999L,
            pts = Array(40) { doubleArrayOf(50.8000 + it * 0.0002, 4.3500) },
            name = "Grote Baan", ref = "N6", limitKph = 50, kind = "primary", onewayDir = 0
        ))
        val cams = (0 until cameras).map {
            SpeedCamera(it.toLong(), 50.8020 + it * 0.0004, 4.3500, 0.0, 50, null,
                SpeedCamera.Kind.FIXED)
        }
        return Area(50.802, 4.35, 6000.0, ways, cams, 0L)
    }

    @Test fun `how long does one free-drive tick take`() {
        for ((roads, cams) in listOf(5000 to 0, 5000 to 5, 5000 to 20, 2000 to 10)) {
            val area = bigArea(roads, 20, cams)
            val t = FreeTracker()
            t.area = area
            // warm up
            repeat(20) { t.update(50.8020, 4.3500, 6.4f, 0f, true, 1000L + it, CameraPolicy.EXACT) }
            val n = 100
            val t0 = System.nanoTime()
            repeat(n) {
                t.update(50.8020 + it * 1e-6, 4.3500, 6.4f, 0f, true,
                         100_000L + it * 250L, CameraPolicy.EXACT)
            }
            val ms = (System.nanoTime() - t0) / 1e6 / n
            println("roads=%5d cameras=%3d  ->  %.2f ms per tick  (%.0f%% of a 250 ms budget)"
                .format(roads, cams, ms, ms / 250.0 * 100))
        }
    }

    @Test fun `the grid returns the road the car is actually on`() {
        // A superset is fine; missing our own road is not.
        val area = bigArea(3000, 20, 5)
        val near = area.roadsNear(50.8020, 4.3500, 60.0)
        assertTrue("the grid must not lose the road under the car",
            near.any { it.id == 999_999L })
        assertTrue("and it must be a small fraction of the network",
            near.size < area.roads.size / 4)
    }

    @Test fun `an area built by hand still works without an index`() {
        // Tests construct Area directly; roadsNear must degrade to "everything"
        // rather than to "nothing".
        val one = RoadWay(1, Array(4) { doubleArrayOf(50.80 + it * 0.001, 4.35) },
            "Rue", "", 50, "residential", 0)
        val a = Area(50.801, 4.35, 500.0, listOf(one), emptyList(), 0L)
        assertEquals(1, a.roadsNear(50.8015, 4.3500, 50.0).size)
        // Far away: the grid legitimately returns nothing.
        assertEquals(0, a.roadsNear(51.5, 5.0, 50.0).size)
    }
}
