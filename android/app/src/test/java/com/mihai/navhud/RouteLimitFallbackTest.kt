package com.mihai.navhud

import com.mihai.navhud.nav.ManeuverPoint
import com.mihai.navhud.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The limit on a route where the router has none.
 *
 * Mapbox's `maxspeed` annotation is missing on a good share of minor roads.
 * The tracker used to hold the last known number for 400 m -- including
 * through the turn onto the next road -- and then show nothing.
 */
class RouteLimitFallbackTest {

    /** 1 km due north; 50 on the first 300 m, nothing known after; a turn at 500 m. */
    private fun route(): Route {
        val pts = Array(101) { Geo.destination(50.85, 4.35, 0.0, it * 10.0) }
        val cum = Geo.cumulative(pts)
        val limits = IntArray(100) { if (it < 30) 50 else 0 }
        val mans = listOf(ManeuverPoint(0.0, Man.DEPART, 0, "A"),
                          ManeuverPoint(500.0, Man.RIGHT, 0, "B"))
        return Route(pts, cum, limits, mans, cum.last(), 100.0, "test")
    }

    private fun lowConf(f: HudFrame) = f.flags and HudFrame.FLAG_LOW_CONF != 0

    private fun at(t: RouteTracker, r: Route, along: Double): HudFrame {
        val p = Geo.pointAlong(r.pts, r.cum, along)
        return t.update(p[0], p[1], 10f, 0f, hasFix = true)
    }

    @Test fun `the fallback fills a gap in the router's limits`() {
        val r = route()
        val t = RouteTracker(r)
        val asked = ArrayList<Double?>()
        t.limitFallback = { _, _, h -> asked.add(h); 30 }
        var a = 0.0
        while (a < 250.0) { at(t, r, a); a += 20.0 }
        assertTrue("the router's own limit needs no fallback", asked.isEmpty())
        assertEquals(50, at(t, r, 250.0).limitKph)
        val f = at(t, r, 350.0)
        assertEquals(30, f.limitKph)
        assertTrue("a second-hand limit is marked", lowConf(f))
        assertEquals("with the car's heading", 0.0, asked.last()!!, 1e-6)
    }

    @Test fun `a derestricted answer is an answer`() {
        val r = route()
        val t = RouteTracker(r)
        t.limitFallback = { _, _, _ -> -1 }
        at(t, r, 250.0)
        assertEquals(-1, at(t, r, 350.0).limitKph)
    }

    @Test fun `without a fallback the hold still bridges a short gap`() {
        val r = route()
        val t = RouteTracker(r)
        t.limitFallback = { _, _, _ -> 0 }
        at(t, r, 280.0)
        val f = at(t, r, 400.0)
        assertEquals(50, f.limitKph)
        assertTrue(lowConf(f))
    }

    @Test fun `the hold ends at a maneuver`() {
        // 400 m of hold would carry the 50 to 690 m, well past the right turn
        // at 500 m onto a road it has nothing to do with.
        val r = route()
        val t = RouteTracker(r)
        var a = 200.0
        while (a < 480.0) { at(t, r, a); a += 20.0 }
        assertEquals(50, at(t, r, 490.0).limitKph)
        assertEquals("past the turn, nothing is known", 0, at(t, r, 520.0).limitKph)
    }
}
