package com.mihai.navhud

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

    @Test fun `the cooldown is short enough to retry a genuine second wrong turn`() {
        // Two wrong turns in a row happens in a one-way system. The second must
        // not have to wait out a quarter-minute penalty.
        assertTrue(RerouteRule.COOLDOWN_MS <= 8000L)
        assertTrue(RerouteRule.CONFIRM_MS <= 2000L)
    }
}
