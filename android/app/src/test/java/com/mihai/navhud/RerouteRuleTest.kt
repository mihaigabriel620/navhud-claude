package com.mihai.navhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long a wrong turn takes to be noticed.
 *
 * The old numbers: the tracker needed three consecutive off-route fixes (~3 s),
 * then four more seconds of confirmation, and a fifteen-second cooldown armed
 * on every route adoption — including the first — could swallow the request
 * entirely and push it out to the far side of twenty seconds.
 */
class RerouteRuleTest {

    /** The tracker has already needed ~3 s of consecutive bad fixes to say so. */
    private val TRACKER_DEBOUNCE_MS = 3000L

    @Test fun `a missed turn is picked up in about four seconds, not twenty`() {
        val offSince = 10_000L
        // Nothing on the very first tick: one wobble is not a wrong turn.
        assertFalse(RerouteRule.shouldReroute(true, 50.0, offSince, 0L, offSince))
        assertFalse(RerouteRule.shouldReroute(true, 50.0, offSince, 0L, offSince + 500))
        assertTrue(RerouteRule.shouldReroute(true, 50.0, offSince, 0L, offSince + 1300))

        val total = TRACKER_DEBOUNCE_MS + RerouteRule.CONFIRM_MS
        assertTrue("end-to-end $total ms is still too slow", total <= 5000L)
    }

    @Test fun `plainly being on another road needs no confirmation at all`() {
        val offSince = 10_000L
        assertTrue("120 m off the line is not a GPS wobble",
            RerouteRule.shouldReroute(true, 120.0, offSince, 0L, offSince))
    }

    @Test fun `the first route no longer mutes the first missed turn`() {
        // This is the regression. lastRequestMs stays 0 until a *reroute* is
        // asked for; adopting the initial route must not arm the cooldown.
        val offSince = 30_000L
        assertTrue(RerouteRule.shouldReroute(true, 60.0, offSince, 0L, offSince + 1300))
    }

    @Test fun `a failing reroute cannot spin`() {
        val requested = 20_000L
        assertFalse(RerouteRule.shouldReroute(true, 200.0, 20_000L, requested, requested + 1000))
        assertFalse(RerouteRule.shouldReroute(true, 200.0, 20_000L, requested, requested + 5000))
        assertTrue(RerouteRule.shouldReroute(true, 200.0, 20_000L, requested, requested + 6500))
    }

    @Test fun `being on the route is not a reason to reroute`() {
        assertFalse(RerouteRule.shouldReroute(false, 200.0, 0L, 0L, 50_000L))
        assertFalse("off-route but not yet timed",
            RerouteRule.shouldReroute(true, 200.0, 0L, 0L, 50_000L))
    }

    // ---- the direction shortcut ---------------------------------------------

    @Test fun `the heading test needs both the angle and the distance`() {
        assertTrue(RerouteRule.turnedOff(16.0, 46.0))
        assertFalse("45 degrees is a bend the polyline cut", RerouteRule.turnedOff(16.0, 45.0))
        assertFalse("15 m out is still the road", RerouteRule.turnedOff(15.0, 90.0))
        assertFalse("no heading, no shortcut", RerouteRule.turnedOff(50.0, null))
    }

    @Test fun `pointing away for two seconds reroutes at twenty metres`() {
        val t0 = 40_000L
        assertFalse(RerouteRule.shouldReroute(false, 20.0, 0L, 0L, t0 + 1999, 60.0, t0))
        assertTrue(RerouteRule.shouldReroute(false, 20.0, 0L, 0L, t0 + 2000, 60.0, t0))
    }

    @Test fun `a broken run does not count`() {
        // The caller zeroes the start when the test fails; a stale start with
        // a failing test now must not fire either.
        val t0 = 40_000L
        assertFalse(RerouteRule.shouldReroute(false, 20.0, 0L, 0L, t0 + 5000, 30.0, t0))
        assertFalse(RerouteRule.shouldReroute(false, 20.0, 0L, 0L, t0 + 5000, 60.0, 0L))
    }

    @Test fun `the direction shortcut still respects the cooldown`() {
        val t0 = 40_000L
        assertFalse(RerouteRule.shouldReroute(false, 20.0, 0L, t0, t0 + 3000, 90.0, t0 - 3000))
        assertTrue(RerouteRule.shouldReroute(false, 20.0, 0L, t0, t0 + 6000, 90.0, t0 - 3000))
    }

    @Test fun `thirty metres off the line is off route`() {
        assertEquals(30.0, RouteTracker.OFF_ROUTE_M, 0.0)
        val r = DemoDrive.buildRoute()
        val t = RouteTracker(r)
        val p = Geo.pointAlong(r.pts, r.cum, 400.0)
        val brg = Geo.bearingAlong(r.pts, r.cum, 400.0) ?: 0.0
        val near = Geo.destination(p[0], p[1], (brg + 90.0) % 360.0, 25.0)
        t.update(near[0], near[1], 14f, brg.toFloat(), hasFix = true, nowMs = 1_000L)
        assertFalse("25 m is still on the line", t.offLine)
        val out = Geo.destination(p[0], p[1], (brg + 90.0) % 360.0, 35.0)
        t.update(out[0], out[1], 14f, brg.toFloat(), hasFix = true, nowMs = 1_250L)
        assertTrue("35 m is off it", t.offLine)
    }

    @Test fun `the cooldown is short enough to retry a genuine second wrong turn`() {
        // Two wrong turns in a row happens in a one-way system. The second must
        // not have to wait out a quarter-minute penalty.
        assertTrue(RerouteRule.COOLDOWN_MS <= 8000L)
        assertTrue(RerouteRule.CONFIRM_MS <= 2000L)
    }
}
