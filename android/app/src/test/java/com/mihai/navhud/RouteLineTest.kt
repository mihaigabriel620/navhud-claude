package com.mihai.navhud

import com.mihai.navhud.map.RouteLine
import com.mihai.navhud.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Waze-style route line: drawn from under the arrow onwards, in two
 * pieces that must meet exactly, keeping the traffic colours.
 */
class RouteLineTest {

    /** A straight line north, a vertex every 10 m, with a traffic pattern. */
    private fun route(n: Int = 1001, congestion: (Int) -> Int = { 1 }): Route {
        val pts = Array(n) { i -> Geo.destination(50.0, 4.0, 0.0, i * 10.0) }
        val cum = Geo.cumulative(pts)
        return Route(pts, cum, IntArray(n - 1), emptyList(), cum.last(), cum.last() / 15.0,
            "test", congestion = IntArray(n - 1) { congestion(it) })
    }

    private fun same(a: DoubleArray, b: DoubleArray) =
        assertEquals(0.0, Geo.haversine(a[0], a[1], b[0], b[1]), 1e-6)

    @Test fun `the line starts exactly at the arrow, between two vertices`() {
        val r = route()
        val lv = RouteLine.trafficLevels(r, 1, 40.0)
        val runs = RouteLine.routeSlice(r, 1234.5, 3000.0, lv)
        same(Geo.pointAlong(r.pts, r.cum, 1234.5), runs.first().pts.first())
        same(Geo.pointAlong(r.pts, r.cum, 3000.0), runs.last().pts.last())
    }

    @Test fun `near and far meet at the same point`() {
        val r = route()
        val lv = RouteLine.trafficLevels(r, 1, 40.0)
        val near = RouteLine.routeSlice(r, 777.7, 2500.0, lv)
        val far = RouteLine.routeSlice(r, 2500.0, r.cum.last(), lv, stride = 7)
        same(near.last().pts.last(), far.first().pts.first())
        same(r.pts.last(), far.last().pts.last())
    }

    @Test fun `traffic runs keep their level and share their boundary vertex`() {
        // 200 m free, 200 m heavy, alternating.
        val r = route { if ((it / 20) % 2 == 0) 1 else 3 }
        val lv = RouteLine.trafficLevels(r, 1, 40.0)
        val runs = RouteLine.routeSlice(r, 50.0, 1150.0, lv)
        assertEquals(listOf(1, 3, 1, 3, 1, 3), runs.map { it.level })
        for (k in 1 until runs.size) same(runs[k - 1].pts.last(), runs[k].pts.first())
        // The first colour change is at 200 m, exactly on its vertex.
        same(r.pts[20], runs[0].pts.last())
        // Every point of each run lies inside its stretch of equal level.
        for (run in runs) for (p in run.pts) {
            val along = Geo.haversine(r.pts[0][0], r.pts[0][1], p[0], p[1])
            val seg = ((along + 0.5) / 10.0).toInt().coerceAtMost(lv.size - 1)
            val ok = lv[seg] == run.level || lv[(seg - 1).coerceAtLeast(0)] == run.level
            assertTrue("point at $along m is not in a level-${run.level} stretch", ok)
        }
    }

    @Test fun `short runs are absorbed, closures never are`() {
        // One 10 m blip of heavy traffic, and one 10 m closure.
        val r = route(101) { when (it) { 30 -> 3; 60 -> 0; else -> 1 } }
        val closed = BooleanArray(100).also { it[60] = true }
        val rc = Route(r.pts, r.cum, r.limitKph, emptyList(), r.totalDistanceM, r.totalDurationS,
            "test", congestion = r.congestion, closed = closed)
        val lv = RouteLine.trafficLevels(rc, 1, 40.0)
        assertEquals(1, lv[30])
        assertEquals(5, lv[60])
    }

    @Test fun `a thinned slice keeps its ends exact and drops vertices between`() {
        val r = route()
        val lv = RouteLine.trafficLevels(r, 1, 40.0)
        val full = RouteLine.routeSlice(r, 2000.0, r.cum.last(), lv)
        val thin = RouteLine.routeSlice(r, 2000.0, r.cum.last(), lv, stride = 10)
        assertTrue(thin.single().pts.size < full.single().pts.size / 5)
        same(full.single().pts.first(), thin.single().pts.first())
        same(full.single().pts.last(), thin.single().pts.last())
    }

    @Test fun `nothing is drawn past the end or for an empty window`() {
        val r = route()
        val lv = RouteLine.trafficLevels(r, 1, 40.0)
        assertTrue(RouteLine.routeSlice(r, r.cum.last() + 5.0, r.cum.last() + 2000.0, lv).isEmpty())
        assertTrue(RouteLine.routeSlice(r, 500.0, 500.0, lv).isEmpty())
    }
}
