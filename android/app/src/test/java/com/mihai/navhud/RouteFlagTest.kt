package com.mihai.navhud

import com.mihai.navhud.alerts.CameraPolicy
import com.mihai.navhud.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Is there an itinerary?" is a fact the phone knows and the HUD does not.
 *
 * The HUD used to infer it: a maneuver that was not NONE meant a route, and no
 * maneuver meant none. That is a guess dressed as a fact, and it guessed wrong
 * in the case that mattered — open the app with no destination and a
 * straight-ahead arrow appeared on the glass of a parked car, because
 * "straight on" is what an unrecognised maneuver code draws.
 *
 * So it became a bit on the wire. This file is the contract:
 *
 *   - RouteTracker sets it. It only exists because there is a route.
 *   - FreeTracker cannot set it. There is nothing for it to set.
 *   - The firmware strips the whole route half out of any frame without it,
 *     which is asserted on the other side in test_sketch.cpp group 18.
 */
class RouteFlagTest {

    @Test
    fun `the bit is where both sides agree it is`() {
        // hud_protocol.h has FLAG_ROUTE = 1 << 6. If these two ever disagree
        // the HUD reads someone else's flag as "there is a route", and the
        // failure is a phantom arrow rather than anything that looks like a
        // protocol error.
        assertEquals(64, HudFrame.FLAG_ROUTE)
        // ...and it does not collide with anything already defined.
        val others = listOf(
            HudFrame.FLAG_OVER_LIMIT, HudFrame.FLAG_OFF_ROUTE, HudFrame.FLAG_GPS_OK,
            HudFrame.FLAG_ARRIVED, HudFrame.FLAG_LOW_CONF, HudFrame.FLAG_NIGHT
        )
        others.forEach { assertEquals(0, it and HudFrame.FLAG_ROUTE) }
    }

    @Test
    fun `free drive never claims a route`() {
        val t = FreeTracker()
        val f = t.update(50.8020, 4.3500, 16f, 0f, true, 1000L, CameraPolicy.EXACT)
        assertEquals(0, f.flags and HudFrame.FLAG_ROUTE)
        assertEquals(Man.NONE, f.maneuver)
    }

    @Test
    fun `free drive still claims a route after being asked many times`() {
        // The flag is computed per frame, not latched, so this is really a
        // check that nothing accumulates into `flags` over a drive.
        val t = FreeTracker()
        repeat(200) { i ->
            val f = t.update(50.8020 + i * 1e-5, 4.3500, 16f, 0f, true,
                             1000L + i * 250L, CameraPolicy.EXACT)
            assertEquals("frame $i claimed a route", 0, f.flags and HudFrame.FLAG_ROUTE)
        }
    }

    @Test
    fun `a tracker with a route says so on every frame of the drive`() {
        val route: Route = DemoDrive.buildRoute()
        val t = RouteTracker(route)
        var along = 0.0
        var nowMs = 100_000L
        var frames = 0
        while (along < route.totalDistanceM && frames < 4000) {
            val v = DemoDrive.speedMpsAt(route, along)
            val p = Geo.pointAlong(route.pts, route.cum, along)
            val ahead = Geo.pointAlong(route.pts, route.cum, along + 15.0)
            val brg = Geo.bearing(p[0], p[1], ahead[0], ahead[1]).toFloat()
            val f = t.update(p[0], p[1], v, brg, hasFix = true, nowMs = nowMs)
            assertTrue("frame $frames dropped the route flag",
                       f.flags and HudFrame.FLAG_ROUTE != 0)
            along += v * 0.25
            nowMs += 250
            frames++
        }
        assertTrue("the drive produced no frames", frames > 100)
    }

    @Test
    fun `losing the fix does not drop the route`() {
        // The route still exists in a tunnel; we simply cannot say where on it
        // we are. Dropping the bit here would blank the arrow at every traffic
        // light under a bridge and paint it back on the far side.
        val route: Route = DemoDrive.buildRoute()
        val t = RouteTracker(route)
        val p = Geo.pointAlong(route.pts, route.cum, 100.0)
        t.update(p[0], p[1], 15f, 0f, hasFix = true, nowMs = 100_000L)
        val lost = t.update(p[0], p[1], 15f, 0f, hasFix = false, nowMs = 100_500L)
        assertTrue(lost.flags and HudFrame.FLAG_ROUTE != 0)
        assertEquals(-1, lost.speedKph)
    }

    @Test
    fun `the flag survives the wire`() {
        val f = HudFrame(speedKph = 50, limitKph = 50, maneuver = Man.RIGHT,
                         distToManeuverM = 120,
                         flags = HudFrame.FLAG_GPS_OK or HudFrame.FLAG_ROUTE)
        val body = f.encode()
        // Ninth field of $HUD is the flags. 4 | 64 = 68.
        assertTrue("flags did not survive: $body", body.contains(",68,"))
        assertFalse(body.contains(",4,"))
    }
}
