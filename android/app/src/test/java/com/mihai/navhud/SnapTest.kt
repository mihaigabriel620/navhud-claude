package com.mihai.navhud

import com.mihai.navhud.map.NavCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The two things the driver could see were wrong: the arrow sitting beside the
 * road, and the arrow not turning when the car turned.
 *
 * Both are fixed by drawing the marker at the *projection of the fix onto the
 * route* and pointing it along the road there, which is what every nav app does
 * — Google sells the operation as the Roads API. These tests drive the tracker
 * down the demo route with realistic GPS error and check that the drawn point
 * is on the tarmac and pointing the right way.
 */
class SnapTest {

    private val route = DemoDrive.buildRoute()

    /** Deterministic, repeatable "GPS noise" — no Random, so failures reproduce. */
    private fun wobble(i: Int, amplitudeM: Double): Pair<Double, Double> {
        val a = Math.sin(i * 0.7) * amplitudeM
        val b = Math.cos(i * 1.3) * amplitudeM
        return a to b
    }

    @Test fun `the snapped point is on the route, however wide the fix is`() {
        val t = RouteTracker(route)
        var along = 0.0
        var i = 0
        var worst = 0.0
        while (along < route.totalDistanceM - 50) {
            val v = DemoDrive.speedMpsAt(route, along)
            val p = Geo.pointAlong(route.pts, route.cum, along)
            val ahead = Geo.pointAlong(route.pts, route.cum, along + 15.0)
            val brg = Geo.bearing(p[0], p[1], ahead[0], ahead[1])

            // Throw the fix around by up to 18 m, which is a bad urban fix.
            val (dx, dy) = wobble(i, 18.0)
            var lat = Geo.destination(p[0], p[1], brg + 90.0, dx)
            lat = Geo.destination(lat[0], lat[1], brg, dy)

            t.update(lat[0], lat[1], v, brg.toFloat(), hasFix = true)

            // The drawn point must lie on the polyline, not where the fix said.
            val snapOff = Geo.project(route.pts, route.cum, t.snappedLat, t.snappedLon,
                                      fromIdx = 0, searchAll = true).cross
            worst = maxOf(worst, snapOff)
            along += v * 0.25
            i++
        }
        assertTrue("the drawn point drifted $worst m off the road", worst < 0.5)
    }

    @Test fun `an 18 metre fix error moves the drawn point by almost nothing`() {
        val t = RouteTracker(route)
        // Settle onto the route first.
        var along = 0.0
        repeat(20) {
            val p = Geo.pointAlong(route.pts, route.cum, along)
            t.update(p[0], p[1], 14f, headingAt(along).toFloat(), hasFix = true)
            along += 10.0
        }
        val p = Geo.pointAlong(route.pts, route.cum, along)
        val brg = headingAt(along)
        t.update(p[0], p[1], 14f, brg.toFloat(), hasFix = true)
        val cleanAlong = t.alongM

        // Now shove the fix 18 m sideways -- the classic "arrow in the garden".
        val off = Geo.destination(p[0], p[1], brg + 90.0, 18.0)
        t.update(off[0], off[1], 14f, brg.toFloat(), hasFix = true)

        assertTrue("a sideways error should barely move us along the route",
            abs(t.alongM - cleanAlong) < 6.0)
        assertTrue("and the marker must stay on the road", t.snapTrusted)
        val drawnToRoad = Geo.project(route.pts, route.cum, t.snappedLat, t.snappedLon,
                                      fromIdx = 0, searchAll = true).cross
        assertTrue("drawn $drawnToRoad m off the road", drawnToRoad < 0.5)
    }

    @Test fun `the marker stops being pinned once you really are on another road`() {
        val t = RouteTracker(route)
        var along = 0.0
        repeat(20) {
            val p = Geo.pointAlong(route.pts, route.cum, along)
            t.update(p[0], p[1], 14f, headingAt(along).toFloat(), hasFix = true)
            along += 10.0
        }
        assertTrue(t.snapTrusted)

        // 60 m sideways is a different street, not a bad fix. Pinning the
        // marker to the route here would be a lie.
        val p = Geo.pointAlong(route.pts, route.cum, along)
        val off = Geo.destination(p[0], p[1], headingAt(along) + 90.0, 60.0)
        t.update(off[0], off[1], 14f, headingAt(along).toFloat(), hasFix = true)
        assertFalse("must not claim to be on the route at 60 m out", t.snapTrusted)
    }

    @Test fun `losing the fix drops the snap rather than freezing it on the road`() {
        val t = RouteTracker(route)
        val p = Geo.pointAlong(route.pts, route.cum, 100.0)
        t.update(p[0], p[1], 14f, headingAt(100.0).toFloat(), hasFix = true)
        assertTrue(t.snapTrusted)
        t.update(0.0, 0.0, 0f, null, hasFix = false)
        assertFalse(t.snapTrusted)
    }

    // ---- road direction -----------------------------------------------------

    private fun headingAt(along: Double): Double {
        val a = Geo.pointAlong(route.pts, route.cum, along)
        val b = Geo.pointAlong(route.pts, route.cum, along + 15.0)
        return Geo.bearing(a[0], a[1], b[0], b[1])
    }

    @Test fun `the road direction matches the way the route actually runs`() {
        var along = 20.0
        while (along < route.totalDistanceM - 40) {
            val road = Geo.bearingAlong(route.pts, route.cum, along)
            assertNotNull("no direction at $along m", road)
            val truth = headingAt(along)
            // Sampling over a span deliberately cuts corners, so allow for a
            // genuine bend inside the window.
            assertTrue("road direction off by ${Geo.bearingDelta(road!!, truth)} at $along m",
                Geo.bearingDelta(road, truth) < 45.0)
            along += 37.0
        }
    }

    @Test fun `the direction turns through a corner instead of snapping at a vertex`() {
        // A right-angle corner, 100 m each side.
        val pts = arrayOf(
            doubleArrayOf(50.8000, 4.3500),
            doubleArrayOf(50.8009, 4.3500),          // ~100 m north
            doubleArrayOf(50.8009, 4.3514)           // ~100 m east
        )
        val cum = Geo.cumulative(pts)
        val corner = cum[1]

        val before = Geo.bearingAlong(pts, cum, corner - 40.0)!!
        val at = Geo.bearingAlong(pts, cum, corner)!!
        val after = Geo.bearingAlong(pts, cum, corner + 40.0)!!

        assertEquals("running north before the corner", 0.0, Geo.bearingDelta(before, 0.0), 2.0)
        assertEquals("running east after it", 0.0, Geo.bearingDelta(after, 90.0), 2.0)
        // Mid-corner it should be somewhere between the two, not at either end.
        assertTrue("the corner should be partly turned, was $at", at > 20.0 && at < 70.0)
    }

    @Test fun `a degenerate route reports no direction rather than a wrong one`() {
        val pts = arrayOf(doubleArrayOf(50.8, 4.35), doubleArrayOf(50.8, 4.35))
        assertNull(Geo.bearingAlong(pts, Geo.cumulative(pts), 0.0))
        assertNull(Geo.bearingAlong(arrayOf(doubleArrayOf(50.8, 4.35)), doubleArrayOf(0.0), 0.0))
    }

    @Test fun `signed heading differences take the short way round north`() {
        assertEquals(20.0, Geo.signedDelta(10.0, 350.0), 1e-9)
        assertEquals(-20.0, Geo.signedDelta(350.0, 10.0), 1e-9)
        assertEquals(180.0, abs(Geo.signedDelta(180.0, 0.0)), 1e-9)
        assertEquals(0.0, Geo.signedDelta(90.0, 90.0), 1e-9)
        assertEquals(355.0, Geo.normalizeDeg(-5.0), 1e-9)
        assertEquals(5.0, Geo.normalizeDeg(365.0), 1e-9)
    }

    // ---- auto-zoom ----------------------------------------------------------

    @Test fun `the camera closes in on a junction by time, not by distance`() {
        // The same 300 m means completely different things: half a minute away
        // in town, eight seconds away on a motorway. Zooming on distance would
        // pull in far too early in town and far too late on the motorway.
        val town = NavCamera.maneuverZoomBoost(300.0, 8.0)       // ~30 km/h
        val motorway = NavCamera.maneuverZoomBoost(300.0, 36.0)  // ~130 km/h
        assertEquals("300 m in town is not close", 0.0, town, 1e-9)
        assertTrue("300 m at 130 km/h is close", motorway > 0.5)

        // Judged in seconds instead, the two behave the same.
        assertEquals(
            NavCamera.maneuverZoomBoost(8.0 * 8, 8.0),
            NavCamera.maneuverZoomBoost(36.0 * 8, 36.0),
            1e-9
        )
    }

    @Test fun `no junction, no boost`() {
        assertEquals(0.0, NavCamera.maneuverZoomBoost(-1.0, 20.0), 1e-9)
        assertEquals(0.0, NavCamera.maneuverZoomBoost(5000.0, 20.0), 1e-9)
    }

    @Test fun `the boost never lets a motorway view become a street view`() {
        for (kph in listOf(90, 110, 130)) {
            val v = kph / 3.6
            val base = NavCamera.zoomForSpeed(kph.toDouble())
            var worst = base
            var d = 0.0
            while (d < 2000.0) {
                worst = maxOf(worst, base + NavCamera.maneuverZoomBoost(d, v))
                d += 10.0
            }
            assertTrue("zoomed to $worst at $kph km/h, too tight to see the exit",
                worst <= base + NavCamera.MAX_MANEUVER_BOOST + 1e-9)
            // A hard ceiling as well: past about 17.5 you are looking at
            // individual house numbers, which is useless at motorway speed.
            assertTrue("zoom $worst is street-level at $kph km/h", worst <= 17.5)
        }
    }

    @Test fun `the boost grows monotonically as you approach`() {
        val v = 14.0
        var prev = -1.0
        var d = 400.0
        while (d >= 0.0) {
            val b = NavCamera.maneuverZoomBoost(d, v)
            assertTrue("zoom boost went backwards at $d m", b >= prev - 1e-9)
            prev = b
            d -= 5.0
        }
        assertEquals(NavCamera.MAX_MANEUVER_BOOST, prev, 1e-9)
    }

    @Test fun `a slow turn still rotates the map when the heading is trustworthy`() {
        // Turning into a driveway at 5 km/h. A raw GPS bearing at that speed is
        // noise and is rightly ignored; the road direction under a snapped
        // marker, or a gyro heading, is not.
        val slow = 1.4                            // m/s, about 5 km/h
        val noisy = NavCamera()
        noisy.update(slow, 90.0, dtSeconds = 1.0, headingTrusted = false)
        noisy.update(slow, 90.0, dtSeconds = 1.0, headingTrusted = false)
        assertEquals("a GPS heading this slow must not steer the map",
            0.0, noisy.bearing, 1e-9)

        val trusted = NavCamera()
        trusted.update(slow, 90.0, dtSeconds = 1.0, headingTrusted = true)
        repeat(6) { trusted.update(slow, 90.0, dtSeconds = 1.0, headingTrusted = true) }
        assertEquals("a road-aligned heading should", 90.0, trusted.bearing, 2.0)
    }

    @Test fun `a standstill never spins the map, trusted or not`() {
        val c = NavCamera()
        c.update(8.0, 45.0, dtSeconds = 1.0, headingTrusted = true)
        val settled = c.bearing
        // Parked: the fused heading is frozen upstream, so the same value
        // arrives every frame and nothing moves.
        repeat(20) { c.update(0.0, 45.0, dtSeconds = 0.25, headingTrusted = true) }
        assertEquals(settled, c.bearing, 1e-9)
    }
}
