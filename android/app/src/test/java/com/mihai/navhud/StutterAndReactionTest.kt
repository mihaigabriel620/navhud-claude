package com.mihai.navhud

import com.mihai.navhud.map.HeadingFusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three complaints from the drive: stutter, slow rerouting, and an arrow
 * that would not behave like a compass.
 */
class StutterAndReactionTest {

    // ---- rerouting ----------------------------------------------------------

    @Test fun `a wrong turn is caught as soon as the car is pointing elsewhere`() {
        // Distance is slow evidence at a junction: turn right where the route
        // went straight on and you are still only fifteen metres from the line
        // for the first couple of seconds. Direction is fast evidence.
        val t0 = 100_000L
        assertTrue(
            "turned ninety degrees off the route: no confirmation needed",
            RerouteRule.shouldReroute(
                offRoute = true, crossM = 50.0, offRouteSinceMs = t0,
                lastRequestMs = 0L, nowMs = t0, headingOffDeg = 90.0
            )
        )
    }

    @Test fun `a bend in the route is not a wrong turn`() {
        // A route polyline cuts corners, so the car can sit twenty or thirty
        // degrees off the segment it is snapped to without going anywhere.
        val t0 = 100_000L
        assertFalse(
            RerouteRule.shouldReroute(
                offRoute = true, crossM = 50.0, offRouteSinceMs = t0,
                lastRequestMs = 0L, nowMs = t0, headingOffDeg = 30.0
            )
        )
        // ...and it still reroutes on the ordinary confirmation timer.
        assertTrue(
            RerouteRule.shouldReroute(
                offRoute = true, crossM = 50.0, offRouteSinceMs = t0,
                lastRequestMs = 0L, nowMs = t0 + RerouteRule.CONFIRM_MS,
                headingOffDeg = 30.0
            )
        )
    }

    @Test fun `no heading means the old distance behaviour, unchanged`() {
        val t0 = 100_000L
        assertFalse(
            RerouteRule.shouldReroute(
                offRoute = true, crossM = 50.0, offRouteSinceMs = t0,
                lastRequestMs = 0L, nowMs = t0, headingOffDeg = null
            )
        )
        assertTrue(
            "plainly elsewhere is still its own shortcut",
            RerouteRule.shouldReroute(
                offRoute = true, crossM = RerouteRule.OBVIOUS_CROSS_M + 1, offRouteSinceMs = t0,
                lastRequestMs = 0L, nowMs = t0, headingOffDeg = null
            )
        )
    }

    @Test fun `the cooldown still stops a failing request from spinning`() {
        val t0 = 100_000L
        assertFalse(
            RerouteRule.shouldReroute(
                offRoute = true, crossM = 500.0, offRouteSinceMs = t0,
                lastRequestMs = t0, nowMs = t0 + 1000L, headingOffDeg = 180.0
            )
        )
    }

    @Test fun `off-route is debounced in time, not in fixes`() {
        // A streak of fixes means two seconds at 1 Hz and two tenths at 10 Hz,
        // and the service now asks the chip for everything it will give.
        assertEquals(600L, RouteTracker.OFF_ROUTE_MS)
    }

    @Test fun `turning off the route reroutes on the very first fix`() {
        // No debounce at all on this path: off the line *now* and pointing
        // sixty degrees away from where the route runs means the turn already
        // happened. This is the difference between "instant" and "it takes a
        // second to realise".
        val t0 = 100_000L
        assertTrue(RerouteRule.shouldReroute(
            offRoute = false,              // the debounce has not agreed yet
            offLine = true,                // ...but this fix is off the line
            crossM = 50.0,
            offRouteSinceMs = 0L,
            lastRequestMs = 0L,
            nowMs = t0,
            headingOffDeg = 85.0
        ))
    }

    @Test fun `but not while the previous request is still in its cooldown`() {
        val t0 = 100_000L
        assertFalse(RerouteRule.shouldReroute(
            offRoute = false, offLine = true, crossM = 50.0,
            offRouteSinceMs = 0L, lastRequestMs = t0, nowMs = t0 + 500L,
            headingOffDeg = 85.0
        ))
    }

    @Test fun `one stray fix off the line does not reroute on its own`() {
        // Off the line but still pointing along the route: a wide junction, a
        // service road, GPS drift. This is what the debounce is for.
        val t0 = 100_000L
        assertFalse(RerouteRule.shouldReroute(
            offRoute = false, offLine = true, crossM = 50.0,
            offRouteSinceMs = 0L, lastRequestMs = 0L, nowMs = t0,
            headingOffDeg = 10.0
        ))
    }

    // ---- the compass owning the arrow ---------------------------------------

    private fun settled(f: HeadingFusion, deg: Double, atMs: Long): HeadingFusion {
        f.setClock(atMs)
        repeat(40) { f.onCompass(deg, 0.05) }
        return f
    }

    @Test fun `crawling in traffic, turning the car turns the arrow`() {
        val f = HeadingFusion()
        settled(f, 0.0, 100_000L)
        f.setClock(101_000L)
        f.onFix(0.0, 1.0, 1.0)                       // 3.6 km/h
        assertTrue(f.usingCompass)
        // Swing the car through ninety degrees while still crawling.
        f.setClock(102_000L)
        repeat(60) { f.onCompass(90.0, 0.05) }
        assertEquals(90.0, f.heading!!, 5.0)
    }

    /**
     * This used to say "on the motorway", because the handover was at 70 km/h.
     * The band it guards is the whole of "moving" now, so the case worth
     * pinning is the one just over the line rather than the one at 108 km/h --
     * a brisk walk already belongs to GPS.
     */
    @Test fun `once the car is properly moving the compass cannot touch the arrow`() {
        val f = HeadingFusion()
        settled(f, 0.0, 100_000L)
        f.setClock(101_000L)
        f.onFix(30.0, 1.5, 1.0)                      // 5.4 km/h, just over
        assertFalse(f.usingCompass)
        val before = f.heading!!
        repeat(60) { f.setClock(101_500L); f.onCompass(200.0, 0.05) }
        assertEquals("a magnet in the dash cannot move it out here", before, f.heading!!, 0.01)
    }

    @Test fun `the crossover sits at five, with a km and a half of hysteresis`() {
        // 70 was the right number for a magnetometer in a cradle, being lied to
        // by the car it is sitting in. This one is on the HUD board, bolted
        // down and calibrated -- which makes it trustworthy, not better than a
        // bearing measured from orbit. So it keeps only the band where GPS has
        // no bearing at all: the standstill, the car park, the three-point turn.
        assertEquals(5.0, HeadingFusion.COMPASS_MAX_MPS * 3.6, 0.2)
        assertEquals(3.5, HeadingFusion.COMPASS_RESUME_MPS * 3.6, 0.2)
        assertTrue("resuming must be the lower of the two",
            HeadingFusion.COMPASS_RESUME_MPS < HeadingFusion.COMPASS_MAX_MPS)
    }

    // ---- the redraw thresholds ----------------------------------------------

    @Test fun `the no-change thresholds are all sub-pixel`() {
        // These are what stop a parked car costing thirty map frames a second.
        // At zoom 17 one screen pixel is about 0.9 m at this latitude, so 20 cm
        // is comfortably under it; the angles likewise.
        // Worst case is a 3x phone at the junction-approach zoom of 18.8,
        // where a metre is about twelve physical pixels -- so the bar is
        // 1/12 m, not the 20 cm the first draft used.
        assertTrue("${MapActivity.CAM_EPSILON_M}", MapActivity.CAM_EPSILON_M <= 0.08)
        assertTrue("${MapActivity.PUCK_EPSILON_M}", MapActivity.PUCK_EPSILON_M <= 0.08)
        assertTrue(MapActivity.CAM_EPSILON_DEG <= 0.05)
        assertTrue(MapActivity.PUCK_EPSILON_DEG <= 0.05)
        assertTrue(MapActivity.CAM_EPSILON_ZOOM <= 0.001)
    }

    @Test fun `a silenced compass hands the arrow back instead of freezing it`() {
        // The magnetic-field gate rejects everything on a neodymium mount, and
        // a multi-storey or a tunnel does the same. hasCompass latches on the
        // first sample and never clears, so without a freshness test the
        // fusion went on believing the compass was in charge, onFix returned
        // without writing the heading, and nothing could correct the arrow
        // below the crossover for the rest of the drive -- while the distrust
        // path that exists for this sat unreachable, because it needs a fresh
        // compass reading to compare against.
        val f = HeadingFusion()
        f.setClock(100_000L)
        repeat(20) { f.onCompass(0.0, 0.05) }
        f.setClock(101_000L)
        f.onFix(0.0, 1.0, 1.0)                       // 3.6 km/h: the compass has it
        assertTrue(f.usingCompass)

        // Compass goes quiet. Six seconds later it is no longer in charge...
        f.setClock(107_000L)
        assertFalse("a stale compass is not a compass", f.usingCompass)
        // ...and a GPS bearing can move the arrow again.
        f.onFix(90.0, 1.0, 1.0)
        repeat(6) { f.setClock(108_000L + it * 1000L); f.onFix(90.0, 1.0, 1.0) }
        assertEquals(90.0, f.heading!!, 5.0)
    }

    // ---- which way the arrow points ----------------------------------------

    @Test fun `snapped to a road at a crawl, the arrow follows the compass`() {
        // The reported symptom: "when driving and I try to turn my phone the
        // arrow does not turn at all". The map used the road bearing and the
        // arrow used the same value, so below the speed changeover the compass
        // was in charge of a number nothing displayed.
        val roadBearing = 0.0        // street runs due north, map points north
        val compass = 90.0           // phone turned to face east in the hand
        assertEquals(compass, com.mihai.navhud.map.Marker.headingFor(
            compassHeading = compass, mapHeading = roadBearing,
            compassDriving = true, mountKnown = true)!!, 1e-9)
    }

    @Test fun `above the changeover the arrow goes back to the road`() {
        assertEquals(0.0, com.mihai.navhud.map.Marker.headingFor(
            compassHeading = 90.0, mapHeading = 0.0,
            compassDriving = false, mountKnown = true)!!, 1e-9)
    }

    @Test fun `moving, the arrow points where the map points`() {
        // 1.27: over 5 km/h the arrow and the map share one heading -- the
        // road's when snapped -- whatever the compass says.
        assertEquals(0.0, com.mihai.navhud.map.Marker.headingFor(
            compassHeading = 90.0, mapHeading = 0.0,
            compassDriving = true, mountKnown = true, moving = true)!!, 1e-9)
    }

    @Test fun `an unlearned mount does not get to point the arrow`() {
        // Unlearned, the compass reads the phone's heading, not the car's --
        // so the map would point along the street while the arrow sat at an
        // angle to it, which reads as broken rather than as uncalibrated.
        assertEquals(0.0, com.mihai.navhud.map.Marker.headingFor(
            compassHeading = 90.0, mapHeading = 0.0,
            compassDriving = true, mountKnown = false)!!, 1e-9)
    }

    @Test fun `with no compass reading at all the arrow still has the map`() {
        assertEquals(12.0, com.mihai.navhud.map.Marker.headingFor(
            compassHeading = null, mapHeading = 12.0,
            compassDriving = true, mountKnown = true)!!, 1e-9)
        assertNull(com.mihai.navhud.map.Marker.headingFor(
            compassHeading = null, mapHeading = null,
            compassDriving = true, mountKnown = true))
    }

    // ---- the debounce must not disable itself ------------------------------

    @Test fun `off-route needs six hundred milliseconds, whatever the clock says`() {
        // The first version used 0L as "not off the line yet". 0 is also what
        // the stubbed SystemClock returns in a unit test, so the whole debounce
        // silently switched itself off in every test that did not pass a clock
        // -- and would have gone on doing that for any test written later.
        val r = DemoDrive.buildRoute()
        val t = RouteTracker(r)
        val p = Geo.pointAlong(r.pts, r.cum, 400.0)
        val brg = Geo.bearingAlong(r.pts, r.cum, 400.0) ?: 0.0
        val off = Geo.destination(p[0], p[1], (brg + 90.0) % 360.0, 80.0)

        t.update(off[0], off[1], 14f, brg.toFloat(), hasFix = true, nowMs = 0L)
        assertTrue("off the line on the very first fix", t.offLine)
        assertFalse("but not yet confirmed", t.offRoute)

        t.update(off[0], off[1], 14f, brg.toFloat(), hasFix = true,
                 nowMs = RouteTracker.OFF_ROUTE_MS)
        assertTrue("confirmed once the time has passed", t.offRoute)
    }

    @Test fun `losing the fix ends the off-route run`() {
        // One stray off-line fix before a tunnel and one on the way out would
        // otherwise satisfy "off the line for 600 ms" with a minute of nothing
        // in between: two samples of evidence, at exactly the moment the
        // debounce exists for.
        val r = DemoDrive.buildRoute()
        val t = RouteTracker(r)
        val p = Geo.pointAlong(r.pts, r.cum, 400.0)
        val brg = Geo.bearingAlong(r.pts, r.cum, 400.0) ?: 0.0
        val off = Geo.destination(p[0], p[1], (brg + 90.0) % 360.0, 80.0)

        t.update(off[0], off[1], 14f, brg.toFloat(), hasFix = true, nowMs = 1_000L)
        t.update(0.0, 0.0, 0f, null, hasFix = false, nowMs = 2_000L)
        t.update(off[0], off[1], 14f, brg.toFloat(), hasFix = true, nowMs = 61_000L)
        assertFalse("the run restarts after the outage", t.offRoute)
    }

    // ---- constants that mean a speed must be written as a speed ------------

    @Test fun `a synthesised bearing needs a speed, not a distance per fix`() {
        // Three metres between fixes is "above 11 km/h" at 1 Hz and "above
        // 54 km/h" at 5 Hz. The same constant, two different meanings, decided
        // by a setting somewhere else entirely.
        val slow = com.mihai.navhud.location.Fixes.MIN_SPEED_FOR_BEARING_MPS
        assertTrue("the gate is a speed", slow in 1.0..5.0)
    }
}
