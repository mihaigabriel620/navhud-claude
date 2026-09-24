package com.mihai.navhud

import com.mihai.navhud.map.FreeDriveMotion
import com.mihai.navhud.map.PuckMotion
import com.mihai.navhud.nav.AreaRoads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The marker's motion between fixes: a rubber band at the car's own speed,
 * never backwards, never faster to change than a car, coasting through a
 * tunnel and catching up smoothly after it.
 */
class PuckMotionTest {

    private val dt = 1.0 / 60

    /** Drive a band against a car moving at [v], with a fix every second. */
    private fun drive(m: PuckMotion, seconds: Double, v: Double, startTrue: Double,
                      fixError: (Int) -> Double = { 0.0 }): Double {
        var t = 0.0
        var truth = startTrue
        var fixAlong = truth
        var fixAt = 0.0
        var n = 0
        while (t < seconds) {
            if (t - fixAt >= 1.0) { fixAt = t; fixAlong = truth + fixError(n++) }
            m.step(dt, fixAlong, t - fixAt, v, fixAvailable = true)
            t += dt
            truth += v * dt
        }
        return truth
    }

    @Test fun `the first step puts the marker where the fix says the car is now`() {
        val m = PuckMotion(180.0)
        assertEquals(110.0, m.step(dt, 100.0, 0.5, 20.0, true), 1e-9)
    }

    @Test fun `steady driving tracks the car at the car's speed`() {
        val m = PuckMotion(180.0)
        val truth = drive(m, 30.0, 20.0, 0.0)
        assertEquals(truth, m.along, 0.5)
        assertEquals(20.0, m.speed, 0.2)
    }

    @Test fun `GPS noise moves the speed a little, never the position in steps`() {
        val m = PuckMotion(180.0)
        val rnd = java.util.Random(3)
        var prev = Double.NaN
        var t = 0.0
        var truth = 0.0
        var fixAlong = 0.0
        var fixAt = 0.0
        while (t < 60.0) {
            if (t - fixAt >= 1.0) { fixAt = t; fixAlong = truth + (rnd.nextDouble() - 0.5) * 8.0 }
            val a = m.step(dt, fixAlong, t - fixAt, 15.0, true)
            if (!prev.isNaN()) {
                val step = a - prev
                assertTrue("went backwards at $t", step >= 0.0)
                assertTrue("jumped ${step} m at $t",
                    step <= (15.0 + PuckMotion.MAX_CORRECTION_MPS) * dt + 1e-9)
            }
            prev = a
            t += dt; truth += 15.0 * dt
        }
        assertEquals(truth, m.along, 6.0)
    }

    @Test fun `a fix behind the marker slows it down, it never reverses`() {
        val m = PuckMotion(180.0)
        drive(m, 5.0, 10.0, 0.0)
        val before = m.along
        var prev = before
        repeat(300) {
            val a = m.step(dt, before - 40.0, 0.0, 0.0, true)     // car stopped, 40 m behind
            assertTrue(a >= prev)
            prev = a
        }
        assertEquals("waits rather than backing up", 0.0, m.speed, 1e-9)
    }

    @Test fun `the drawn speed changes no faster than a car can`() {
        val m = PuckMotion(180.0)
        m.step(dt, 0.0, 0.0, 10.0, true)
        var v = m.speed
        repeat(600) {
            m.step(dt, 250.0, 0.0, 10.0, true)                  // a gap to close
            assertTrue("accel ${(m.speed - v) / dt}",
                abs(m.speed - v) <= PuckMotion.MAX_ACCEL * dt + 1e-9)
            assertTrue(m.speed - 10.0 <= PuckMotion.MAX_CORRECTION_MPS + 1e-9)
            v = m.speed
        }
    }

    @Test fun `a gap under three hundred metres is closed, not jumped`() {
        val m = PuckMotion(180.0)
        m.step(dt, 0.0, 0.0, 20.0, true)
        val first = m.step(dt, 250.0, 0.0, 20.0, true)
        assertTrue("jumped to $first", first < 5.0)
    }

    @Test fun `past three hundred metres it jumps`() {
        val m = PuckMotion(180.0)
        m.step(dt, 0.0, 0.0, 20.0, true)
        assertEquals(400.0, m.step(dt, 400.0, 0.0, 20.0, true), 1e-9)
    }

    @Test fun `in a tunnel it coasts at the car's speed, then holds at the limit`() {
        val m = PuckMotion(180.0)
        drive(m, 10.0, 25.0, 0.0)
        val start = m.along
        var age = 3.1
        var t = 0.0
        while (t < 60.0) { m.step(dt, 0.0, age, 25.0, false); t += dt; age += dt }
        assertEquals("a minute at 90 km/h", start + 1500.0, m.along, 5.0)
        while (age < 180.0) { m.step(dt, 0.0, age, 25.0, false); age += dt }
        val atLimit = m.along
        repeat(600) { m.step(dt, 0.0, age, 25.0, false); age += dt }
        assertEquals("holds once the coast limit is reached", atLimit, m.along, 1e-9)
    }

    @Test fun `on a held GPS speed it coasts at that constant speed for a minute only`() {
        val m = PuckMotion(180.0)
        drive(m, 10.0, 25.0, 0.0)
        val start = m.along
        var age = 3.1
        while (age < 60.0) { m.step(dt, 0.0, age, 25.0, false, limitS = 60.0); age += dt }
        assertEquals("constant 25 m/s until the minute is up", start + 25.0 * (60.0 - 3.1), m.along, 5.0)
        val atLimit = m.along
        repeat(100) { m.step(dt, 0.0, age, 25.0, false, limitS = 60.0); age += dt }
        assertEquals(atLimit, m.along, 1e-9)
    }

    @Test fun `coming out of the tunnel it converges without a jump`() {
        val m = PuckMotion(180.0)
        drive(m, 10.0, 20.0, 0.0)
        var age = 3.1
        repeat(60 * 30) { m.step(dt, 0.0, age, 20.0, false); age += dt }
        // The car actually went a little slower underground: 80 m behind.
        val truth0 = m.along - 80.0
        var prev = m.along
        var t = 0.0
        var truth = truth0
        var fixAlong = truth
        var fixAt = 0.0
        while (t < 30.0) {
            if (t - fixAt >= 1.0) { fixAt = t; fixAlong = truth }
            val a = m.step(dt, fixAlong, t - fixAt, 20.0, true)
            assertTrue("reversed at $t", a >= prev)
            assertTrue("jumped at $t", a - prev <= 35.0 * dt + 1e-9)
            prev = a
            t += dt; truth += 20.0 * dt
        }
        assertEquals("caught up", truth, m.along, 1.0)
    }

    @Test fun `parked, the marker settles on the fix and stays`() {
        val m = PuckMotion(180.0)
        m.step(dt, 0.0, 0.0, 0.0, true)
        repeat(60 * 20) { m.step(dt, 12.0, 0.0, 0.0, true) }
        assertEquals(12.0, m.along, 0.2)
        val settled = m.along
        repeat(60 * 20) { m.step(dt, 12.0, 0.0, 0.0, true) }
        assertEquals(settled, m.along, 0.2)
    }

    @Test fun `the correction is linear for noise and capped for a gap`() {
        assertEquals(0.5, PuckMotion.correction(1.0), 1e-9)
        assertEquals(-0.5, PuckMotion.correction(-1.0), 1e-9)
        assertEquals(PuckMotion.MAX_CORRECTION_MPS, PuckMotion.correction(1000.0), 1e-9)
    }

    // ---- free drive ---------------------------------------------------------

    @Test fun `free drive extrapolates the fix continuously until coasting takes over`() {
        // The old code capped the extrapolation at 1.5 s but only started
        // coasting at 3 s: the marker stopped dead for a second and a half and
        // then lurched off again.
        val m = FreeDriveMotion(30.0, 35.0)
        val lat0 = 50.0; val lon0 = 4.0
        val v = 20.0
        m.step(dt, lat0, lon0, 0.0, v, true, 0.0, null)
        var prev = doubleArrayOf(m.lat, m.lon)
        var age = 0.0
        while (age < 6.0) {
            age += dt
            val p = m.step(dt, lat0, lon0, age, v, age <= 3.0, 0.0, null)
            val moved = Geo.haversine(prev[0], prev[1], p[0], p[1])
            assertEquals("at ${"%.2f".format(age)} s", v * dt, moved, 0.05 * v * dt)
            prev = p
        }
    }

    @Test fun `free drive coasts along the road's bend, then holds`() {
        // A quarter circle of 400 m radius, heading from north round to east.
        val cLat = 50.0; val cLon = 4.0
        val road = Array(200) { i ->
            val a = Math.toRadians(-90.0 + 90.0 * i / 199)      // west of centre, round to north
            Geo.destination(cLat, cLon, Math.toDegrees(a).let { (it + 360) % 360 }, 400.0)
        }
        val m = FreeDriveMotion(30.0, 35.0)
        val v = 15.0
        m.step(dt, road[0][0], road[0][1], 0.0, v, true, 0.0, road)
        var age = 3.1
        while (age < 25.0) { m.step(dt, road[0][0], road[0][1], age, v, false, 0.0, road); age += dt }
        val cross = AreaRoads.nearestOn(road, m.lat, m.lon)!!.first
        assertTrue("left the road by $cross m", cross < 1.0)
        val fromCentre = Geo.haversine(cLat, cLon, m.lat, m.lon)
        assertEquals("still on the arc", 400.0, fromCentre, 1.0)
        // ~22 s at 15 m/s is ~330 m of a 628 m arc: about 47 degrees round,
        // which a straight line along the first heading could never be.
        val brg = Geo.bearing(cLat, cLon, m.lat, m.lon)
        assertEquals(270.0 + Math.toDegrees(21.9 * v / 400.0), brg, 3.0)

        while (age < 30.0) { m.step(dt, road[0][0], road[0][1], age, v, false, 0.0, road); age += dt }
        val held = doubleArrayOf(m.lat, m.lon)
        repeat(300) { m.step(dt, road[0][0], road[0][1], age, v, false, 0.0, road); age += dt }
        assertEquals(0.0, Geo.haversine(held[0], held[1], m.lat, m.lon), 1e-6)
    }

    @Test fun `free drive with no heading and no fix does not wander`() {
        val m = FreeDriveMotion(30.0, 35.0)
        m.step(dt, 50.0, 4.0, 0.0, 10.0, true, null, null)
        val a = doubleArrayOf(m.lat, m.lon)
        repeat(300) { m.step(dt, 50.0, 4.0, 5.0 + it * dt, 10.0, false, null, null) }
        assertEquals(0.0, Geo.haversine(a[0], a[1], m.lat, m.lon), 1e-9)
    }
}
