package com.mihai.navhud

import com.mihai.navhud.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Drives the real RouteTracker down the real DemoDrive route and asserts the
 * things that are miserable to debug from the driving seat.
 */
class NavLogicTest {

    private data class Sample(val along: Double, val frame: HudFrame, val cross: Double,
                             val trackerAlong: Double)

    private fun runDrive(
        route: Route,
        detour: Pair<Double, Double>? = null,
        tickSeconds: Double = 0.25
    ): List<Sample> {
        val t = RouteTracker(route)
        val out = ArrayList<Sample>()
        var along = 0.0
        var guard = 0
        // A simulated clock, because off-route is now debounced in time rather
        // than in fixes -- so the drive has to advance one.
        var nowMs = 100_000L
        while (along < route.totalDistanceM + 30 && guard++ < 20000) {
            val v = DemoDrive.speedMpsAt(route, along)
            val p = Geo.pointAlong(route.pts, route.cum, along)
            val ahead = Geo.pointAlong(route.pts, route.cum, along + 15.0)
            val brg = Geo.bearing(p[0], p[1], ahead[0], ahead[1]).toFloat()
            var lat = p[0]; var lon = p[1]
            if (detour != null && along >= detour.first && along <= detour.second) {
                val off = Geo.destination(lat, lon, ((brg + 90f) % 360f).toDouble(), 80.0)
                lat = off[0]; lon = off[1]
            }
            val f = t.update(lat, lon, v, brg, hasFix = true, nowMs = nowMs)
            out.add(Sample(along, f, t.lastCrossM, t.alongM))
            along += v * tickSeconds
            nowMs += (tickSeconds * 1000).toLong()
        }
        return out
    }

    @Test fun `the demo route is built the way the tracker expects`() {
        val r = DemoDrive.buildRoute()
        assertTrue(r.pts.size > 400)
        assertEquals(r.pts.size, r.cum.size)
        assertEquals(r.pts.size - 1, r.limitKph.size)
        assertEquals(9, r.maneuvers.size)
        assertTrue(r.totalDistanceM > 9000)
        // maneuvers must be sorted, or firstOrNull picks the wrong one
        for (i in 1 until r.maneuvers.size)
            assertTrue(r.maneuvers[i].alongM >= r.maneuvers[i - 1].alongM)
    }

    @Test fun `the tracker stays snapped to its own route`() {
        val samples = runDrive(DemoDrive.buildRoute())
        val worst = samples.maxOf { it.cross }
        assertTrue("max cross-track $worst m", worst < 8.0)
    }

    @Test fun `every maneuver is announced, in route order`() {
        val r = DemoDrive.buildRoute()
        val samples = runDrive(r)
        val announced = ArrayList<Pair<Int, String>>()
        for (s in samples) {
            val key = s.frame.maneuver to s.frame.street
            if (announced.isEmpty() || announced.last() != key) announced.add(key)
        }
        // every maneuver after DEPART (which is behind us at t=0) must show up
        for (m in r.maneuvers.drop(1)) {
            assertTrue("maneuver ${m.code} '${m.name}' never announced",
                announced.any { it.first == m.code && it.second == m.name })
        }
    }

    @Test fun `the countdown to the turn never ticks upwards`() {
        val samples = runDrive(DemoDrive.buildRoute())
        var bad = 0
        for (i in 1 until samples.size) {
            val a = samples[i - 1].frame; val b = samples[i].frame
            if (a.street == b.street && a.maneuver == b.maneuver &&
                b.distToManeuverM > a.distToManeuverM + 1) bad++
        }
        assertEquals("non-monotonic countdown ticks", 0, bad)
    }

    @Test fun `remaining distance only decreases`() {
        val samples = runDrive(DemoDrive.buildRoute())
        var bad = 0
        for (i in 1 until samples.size)
            if (samples[i].frame.remainingM > samples[i - 1].frame.remainingM + 1) bad++
        assertEquals(0, bad)
    }

    @Test fun `the roundabout carries its exit number on every frame`() {
        val samples = runDrive(DemoDrive.buildRoute())
        val rb = samples.filter { it.frame.maneuver == Man.ROUNDABOUT }
        assertTrue("roundabout was never shown", rb.isNotEmpty())
        assertTrue("exit number lost on some frames", rb.all { it.frame.roundaboutExit == 2 })
    }

    @Test fun `speed limits track the section being driven`() {
        val r = DemoDrive.buildRoute()
        val samples = runDrive(r)
        // The polyline has a vertex every 20 m, so the limit can only change on
        // a 20 m boundary. A frame is correct if the limit it shows belongs to
        // some segment within one vertex of where the tracker put us -- tighter
        // than that is asserting a precision the data does not have.
        val tolerance = 25.0
        var wrong = 0
        for (s in samples) {
            if (s.frame.flags and HudFrame.FLAG_LOW_CONF != 0) continue
            if (s.frame.limitKph == 0) continue
            // compare against where the *tracker* thinks it is, not where the
            // simulator put us -- the projection can lead or lag by a metre or
            // two through a bend, and that is the number the limit came from.
            val ok = r.limitKph.indices.any { seg ->
                abs(r.cum[seg] - s.trackerAlong) <= tolerance &&
                    r.limitKph[seg] == s.frame.limitKph
            }
            if (!ok) wrong++
        }
        assertEquals("frames showing a limit no nearby segment has", 0, wrong)
    }

    @Test fun `the limit shown is exactly the matched segment's, away from boundaries`() {
        val r = DemoDrive.buildRoute()
        // Sample mid-section positions and check the tracker agrees exactly.
        val t = RouteTracker(r)
        var checked = 0
        var along = 60.0
        while (along < r.totalDistanceM - 60.0) {
            val p = Geo.pointAlong(r.pts, r.cum, along)
            val f = t.update(p[0], p[1], 15f, null, hasFix = true)
            val seg = Geo.project(r.pts, r.cum, p[0], p[1], searchAll = true).segIndex
            val expect = r.limitKph[seg.coerceIn(0, r.limitKph.size - 1)]
            if (expect != 0 && f.flags and HudFrame.FLAG_LOW_CONF == 0) {
                assertEquals("at ${along.toInt()} m", expect, f.limitKph)
                checked++
            }
            along += 137.0        // a prime-ish step, to land off the boundaries
        }
        assertTrue("nothing was actually checked", checked > 30)
    }

    @Test fun `the data gap holds the last limit over and flags it`() {
        val samples = runDrive(DemoDrive.buildRoute())
        val held = samples.filter { it.frame.flags and HudFrame.FLAG_LOW_CONF != 0 }
        assertTrue("hold-over never fired", held.isNotEmpty())
        // it must show a real number while holding, not fall back to unknown
        assertTrue("held frames must still show a limit", held.all { it.frame.limitKph != 0 })
        assertFalse("hold-over never cleared",
            samples.last().frame.flags and HudFrame.FLAG_LOW_CONF != 0)
    }

    @Test fun `over-limit is flagged on the motorway and not before`() {
        val samples = runDrive(DemoDrive.buildRoute())
        val over = samples.filter { it.frame.flags and HudFrame.FLAG_OVER_LIMIT != 0 }
        assertTrue(over.isNotEmpty())
        assertTrue("flagged when not actually over",
            over.all { it.frame.speedKph > it.frame.limitKph + RouteTracker.OVER_LIMIT_TOLERANCE_KPH })
    }

    @Test fun `arrival is flagged only at the very end`() {
        val samples = runDrive(DemoDrive.buildRoute())
        val first = samples.indexOfFirst { it.frame.flags and HudFrame.FLAG_ARRIVED != 0 }
        assertTrue("arrival never flagged", first >= 0)
        assertTrue("arrival flagged too early ($first of ${samples.size})",
            first > samples.size * 0.9)
    }

    @Test fun `an 80 metre detour is caught, with no false positives before it`() {
        val samples = runDrive(DemoDrive.buildRoute(), detour = 2000.0 to 2600.0)
        val off = samples.filter { it.frame.flags and HudFrame.FLAG_OFF_ROUTE != 0 }
        assertTrue("detour not detected", off.isNotEmpty())
        assertTrue("false positive before the detour", off.none { it.along < 1900.0 })
    }

    @Test fun `losing the fix reports no speed rather than a stale one`() {
        val r = DemoDrive.buildRoute()
        val t = RouteTracker(r)
        t.update(r.pts[10][0], r.pts[10][1], 20f, 0f, hasFix = true)
        val lost = t.update(0.0, 0.0, 0f, null, hasFix = false)
        assertEquals(-1, lost.speedKph)
        assertEquals(0, lost.flags and HudFrame.FLAG_GPS_OK)
    }

    @Test fun `the maneuver after the next one is exposed for "then"`() {
        val r = DemoDrive.buildRoute()
        val t = RouteTracker(r)
        // Just after departing: next is the right turn onto Rue de la Loi,
        // then the roundabout.
        val p = Geo.pointAlong(r.pts, r.cum, 100.0)
        t.update(p[0], p[1], 10f, null, hasFix = true)
        assertEquals(r.maneuvers[1], t.nextManeuver)
        assertEquals(r.maneuvers[2], t.thenManeuver)
        assertEquals(Man.ROUNDABOUT, t.thenManeuver!!.code)
        // On the final leg there is no "then".
        val q = Geo.pointAlong(r.pts, r.cum, r.maneuvers.last().alongM - 100.0)
        t.update(q[0], q[1], 10f, null, hasFix = true)
        assertEquals(r.maneuvers.last(), t.nextManeuver)
        assertEquals(null, t.thenManeuver)
    }

    @Test fun `the frame shows the display speed, not the positioning one`() {
        // Scaled bus speed places the car; the raw bus speed is what the HUD
        // draws, so it is what the frame and the over-limit flag must carry.
        val r = DemoDrive.buildRoute()
        val t = RouteTracker(r)
        val p = Geo.pointAlong(r.pts, r.cum, 100.0)
        val f = t.update(p[0], p[1], 9.0f, null, hasFix = true, displayMps = 10.0f)
        assertEquals(36, f.speedKph)
    }

    @Test fun `the final approach shows arrive, never carry straight on`() {
        val r = DemoDrive.buildRoute()
        val t = RouteTracker(r)
        val p = Geo.pointAlong(r.pts, r.cum, r.totalDistanceM - 5.0)
        val f = t.update(p[0], p[1], 5f, null, hasFix = true)
        assertEquals(Man.ARRIVE, f.maneuver)
    }
}
