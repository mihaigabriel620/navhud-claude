package com.mihai.navhud

import com.mihai.navhud.nav.ManeuverPoint
import com.mihai.navhud.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arriving has to be recognised where the car actually stops.
 *
 * The service ends the route on the arrival flag (HudService.step), so a flag
 * that never comes is a route that never ends: the car parked beside the pin
 * was declared off route and rerouted back to where it was standing.
 */
class ArrivalEndsRouteTest {

    /** Legs of (bearing, metres), with a vertex every 10 m. */
    private fun route(vararg legs: Pair<Double, Double>): Route {
        val pts = ArrayList<DoubleArray>()
        var p = doubleArrayOf(50.85, 4.35)
        pts.add(p)
        for ((brg, len) in legs) {
            var done = 0.0
            while (done < len - 1e-6) {
                val step = minOf(10.0, len - done)
                p = Geo.destination(p[0], p[1], brg, step)
                pts.add(p)
                done += step
            }
        }
        val arr = pts.toTypedArray()
        val cum = Geo.cumulative(arr)
        return Route(arr, cum, IntArray(arr.size - 1), listOf(ManeuverPoint(0.0, Man.DEPART, 0, "")),
                     cum.last(), cum.last() / 10.0, "test")
    }

    private fun arrived(f: HudFrame) = f.flags and HudFrame.FLAG_ARRIVED != 0

    /** Settle the tracker on the route first, as a real drive would. */
    private fun driveTo(t: RouteTracker, r: Route, alongM: Double) {
        var a = 0.0
        while (a < alongM) {
            val p = Geo.pointAlong(r.pts, r.cum, a)
            t.update(p[0], p[1], 8f, null, hasFix = true)
            a += 20.0
        }
    }

    @Test fun `reaching the end of the line still arrives`() {
        val r = route(0.0 to 300.0)
        val t = RouteTracker(r)
        driveTo(t, r, 250.0)
        val p = Geo.pointAlong(r.pts, r.cum, r.totalDistanceM - 10.0)
        assertTrue(arrived(t.update(p[0], p[1], 2f, null, hasFix = true)))
    }

    @Test fun `parked beside the pin arrives even though the line goes round the block`() {
        // North 300 m, east 20 m, south 25 m: the pin is round the corner.
        val r = route(0.0 to 300.0, 90.0 to 20.0, 180.0 to 25.0)
        val t = RouteTracker(r)
        driveTo(t, r, 280.0)
        // Stopped 10 m into the east leg: 35 m of route left, 27 m from the pin.
        val p = Geo.pointAlong(r.pts, r.cum, 310.0)
        val f = t.update(p[0], p[1], 0f, null, hasFix = true)
        assertTrue("remaining ${f.remainingM} m", f.remainingM > RouteTracker.ARRIVED_M)
        assertTrue("27 m from the pin with 35 m of route left is arrived", arrived(f))
    }

    @Test fun `close to the pin with most of a loop still to drive is not arrived`() {
        // North 300, east 60, south 20, west 60: the pin sits 20 m south of
        // the corner the car passes long before the route ends there.
        val r = route(0.0 to 300.0, 90.0 to 60.0, 180.0 to 20.0, 270.0 to 60.0)
        val t = RouteTracker(r)
        driveTo(t, r, 280.0)
        val p = Geo.pointAlong(r.pts, r.cum, 290.0)
        val f = t.update(p[0], p[1], 8f, null, hasFix = true)
        assertTrue(f.remainingM > RouteTracker.ARRIVED_NEAR_REMAINING_M)
        assertFalse("the pin is 10 m away but 150 m of route remain", arrived(f))
    }

    @Test fun `near in route distance but not in a straight line is not arrived`() {
        val r = route(0.0 to 300.0)
        val t = RouteTracker(r)
        driveTo(t, r, 200.0)
        val p = Geo.pointAlong(r.pts, r.cum, r.totalDistanceM - 50.0)
        assertFalse(arrived(t.update(p[0], p[1], 8f, null, hasFix = true)))
    }

    @Test fun `the thresholds are the published ones`() {
        assertEquals(25.0, RouteTracker.ARRIVED_M, 0.0)
        assertEquals(30.0, RouteTracker.ARRIVED_NEAR_M, 0.0)
        assertEquals(60.0, RouteTracker.ARRIVED_NEAR_REMAINING_M, 0.0)
    }
}
