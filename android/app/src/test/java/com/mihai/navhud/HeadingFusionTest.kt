package com.mihai.navhud

import com.mihai.navhud.map.HeadingFusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 3.6 km/h: rolling, with a GPS bearing, but under the 5 km/h at which the
 * fusion counts as moving and stops listening to turn-rate sensors (1.27).
 * The gyro tests below used to run at 50 km/h; they run here now so they
 * still exercise the integration they were written for.
 */
private const val CRAWL_MPS = 1.0

/**
 * The gyroscope filter that makes the arrow turn when the car does.
 *
 * A GPS bearing arrives once a second and is computed from where you *were*, so
 * on its own the marker lags most of a second behind the steering wheel. These
 * tests drive the filter the way a car would.
 */
class HeadingFusionTest {

    /** Phone flat on the dash, screen up: Android reports gravity as +z. */
    private val UP = doubleArrayOf(0.0, 0.0, 1.0)

    private fun HeadingFusion.turn(rateDegPerSec: Double, seconds: Double, step: Double = 0.02) {
        var t = 0.0
        // Turning right (clockwise, heading increasing) is a negative rate
        // about the up axis under the right-hand rule.
        val rad = Math.toRadians(-rateDegPerSec)
        while (t < seconds) {
            onGyro(0.0, 0.0, rad, UP[0], UP[1], UP[2], step)
            t += step
        }
    }

    @Test fun `with no input at all there is no heading to report`() {
        assertNull(HeadingFusion().heading)
    }

    @Test fun `the first usable GPS bearing sets the heading`() {
        val f = HeadingFusion()
        f.onFix(90.0, 20.0, 1.0)
        assertEquals(90.0, f.heading!!, 1e-6)
    }

    @Test fun `a bearing while crawling is ignored, because it is noise`() {
        val f = HeadingFusion()
        f.onFix(90.0, 20.0, 1.0)
        f.onFix(200.0, 0.3, 1.0)          // stopped at a light, GPS wanders
        assertEquals(90.0, f.heading!!, 1e-6)
    }

    @Test fun `a right turn shows up immediately, without waiting for GPS`() {
        val f = HeadingFusion()
        f.onFix(0.0, CRAWL_MPS, 1.0)
        f.turn(rateDegPerSec = 30.0, seconds = 3.0)   // 90 degrees right
        assertEquals(90.0, f.heading!!, 2.0)
    }

    @Test fun `a left turn goes the other way`() {
        val f = HeadingFusion()
        f.onFix(180.0, CRAWL_MPS, 1.0)
        f.turn(rateDegPerSec = -30.0, seconds = 3.0)
        assertEquals(90.0, f.heading!!, 2.0)
    }

    @Test fun `the heading wraps through north instead of unwinding`() {
        val f = HeadingFusion()
        f.onFix(350.0, CRAWL_MPS, 1.0)
        f.turn(rateDegPerSec = 20.0, seconds = 1.5)   // +30 degrees, past north
        val h = f.heading!!
        assertTrue("heading left the 0..360 range: $h", h in 0.0..360.0)
        assertEquals(20.0, h, 2.0)
    }

    @Test fun `GPS pulls the gyro estimate back over a few seconds`() {
        val f = HeadingFusion()
        f.onFix(0.0, CRAWL_MPS, 1.0)
        // Pretend the gyro drifted 20 degrees at a crawl while GPS said north.
        f.turn(rateDegPerSec = 20.0, seconds = 1.0)
        assertEquals(20.0, f.heading!!, 2.0)
        repeat(5) { f.onFix(0.0, 20.0, 1.0) }
        assertTrue("GPS should have won by now: ${f.heading}", abs(f.heading!!) < 3.0)
    }

    // ---- moving: the GPS course only (1.27) ---------------------------------

    @Test fun `above 5 km-h the gyro does not move the heading`() {
        val f = HeadingFusion()
        f.onFix(0.0, 20.0, 1.0)                        // 72 km/h, due north
        assertTrue(f.moving)
        f.turn(rateDegPerSec = 30.0, seconds = 3.0)    // 90 degrees of "turn"
        assertEquals(0.0, f.heading!!, 1e-9)
    }

    @Test fun `above 5 km-h neither the orientation sensor nor the board's yaw rate does`() {
        val f = HeadingFusion()
        f.onFix(0.0, 1.5, 1.0)                         // 5.4 km/h, just over
        assertTrue(f.moving)
        var r = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        f.onOrientation(r, 0.02)
        repeat(45) {
            val a = Math.toRadians(-1.0)
            val rz = doubleArrayOf(Math.cos(a), -Math.sin(a), 0.0, Math.sin(a), Math.cos(a), 0.0,
                0.0, 0.0, 1.0)
            val o = DoubleArray(9)
            for (i in 0..2) for (j in 0..2) for (k in 0..2) o[i * 3 + j] += rz[i * 3 + k] * r[k * 3 + j]
            r = o
            f.onOrientation(r, 0.02)
        }
        repeat(100) { f.onExternalYawRate(30.0, 0.02) }
        assertEquals(0.0, f.heading!!, 1e-9)
        // ...but the GPS course still does.
        f.onFix(80.0, 1.5, 1.0)
        assertEquals(80.0, f.heading!!, 1e-6)
    }

    @Test fun `moving has the compass's hysteresis, and the car's speed counts`() {
        val f = HeadingFusion()
        f.onFix(0.0, 1.5, 1.0)
        assertTrue(f.moving)
        f.noteVehicleSpeed(1.2)                        // 4.3 km/h: still moving
        assertTrue(f.moving)
        f.noteVehicleSpeed(0.9)                        // 3.2 km/h: stopped turning
        assertFalse(f.moving)
        f.noteVehicleSpeed(1.2)                        // back up to 4.3: not yet
        assertFalse(f.moving)
        // And below it the gyro turns the arrow again.
        f.turn(rateDegPerSec = 30.0, seconds = 1.0)
        assertEquals(30.0, f.heading!!, 2.0)
    }

    @Test fun `a wild disagreement snaps rather than sliding round slowly`() {
        val f = HeadingFusion()
        f.onFix(0.0, 20.0, 1.0)
        f.turn(rateDegPerSec = 20.0, seconds = 1.0)
        f.onFix(180.0, 20.0, 1.0)          // came out of a tunnel facing back
        assertEquals(180.0, f.heading!!, 0.5)
    }

    /**
     * This used to assert the opposite: that nothing at all moved the heading
     * while parked. That was the safe reading of "a gyro is noise at a
     * standstill", and it was also why the arrow sat frozen while you turned
     * the car in a car park.
     *
     * The rule now is a deadband rather than a blanket freeze — below the
     * sensor's own noise floor nothing happens, above it the car is really
     * turning and the map should say so.
     */
    @Test fun `a parked car turns the map only for a real turn`() {
        val slow = HeadingFusion()
        slow.onFix(90.0, 20.0, 1.0)
        slow.onFix(90.0, 0.0, 1.0)
        slow.turn(rateDegPerSec = 1.2, seconds = 10.0)   // drift, not a turn
        assertEquals("noise must not move it", 90.0, slow.heading!!, 0.001)

        val real = HeadingFusion()
        real.onFix(90.0, 20.0, 1.0)
        real.onFix(90.0, 0.0, 1.0)
        real.turn(rateDegPerSec = 40.0, seconds = 2.0)   // swinging into a space
        assertEquals(170.0, real.heading!!, 1.0)
    }

    @Test fun `gyro drift is learned while parked and cancelled while driving`() {
        val f = HeadingFusion()
        f.onFix(90.0, 20.0, 1.0)
        f.onFix(90.0, 0.0, 1.0)
        // A real chip's zero-rate offset: a slow, steady 1.7 deg/s.
        val bias = Math.toRadians(-1.7)
        var t = 0.0
        while (t < 60.0) { f.onGyro(0.0, 0.0, bias, UP[0], UP[1], UP[2], 0.02); t += 0.02 }
        assertTrue("bias was not learned: ${f.biasRadS}", abs(f.biasRadS - bias) < 0.005)

        // Now drive off. The same offset should now integrate to almost nothing.
        f.onFix(90.0, 20.0, 1.0)
        t = 0.0
        while (t < 30.0) { f.onGyro(0.0, 0.0, bias, UP[0], UP[1], UP[2], 0.02); t += 0.02 }
        assertEquals("drift leaked into the heading", 90.0, f.heading!!, 3.0)
    }

    @Test fun `mounting angle does not matter, because gravity is projected out`() {
        // Phone stood upright in a cradle: gravity along -y, and the yaw axis
        // is now the device's y axis, not z.
        val f = HeadingFusion()
        f.onFix(0.0, CRAWL_MPS, 1.0)
        val up = doubleArrayOf(0.0, -1.0, 0.0)
        var t = 0.0
        val rad = Math.toRadians(-30.0)
        // Rotating about the vertical means rotating about -y in device axes.
        while (t < 3.0) {
            f.onGyro(0.0, -rad, 0.0, up[0], up[1], up[2], 0.02)
            t += 0.02
        }
        assertEquals("a tilted mount must still read a 90 degree turn", 90.0, f.heading!!, 2.0)
    }

    @Test fun `an implausible gap is not integrated`() {
        val f = HeadingFusion()
        f.onFix(0.0, CRAWL_MPS, 1.0)
        // The app was in the background for four seconds; one sample arrives.
        f.onGyro(0.0, 0.0, Math.toRadians(-30.0), UP[0], UP[1], UP[2], 4.0)
        assertEquals(0.0, f.heading!!, 0.001)
    }

    @Test fun `the HUD's own IMU drives the heading the same way`() {
        val f = HeadingFusion()
        f.onFix(0.0, CRAWL_MPS, 1.0)
        repeat(150) { f.onExternalYawRate(20.0, 0.02) }   // 60 degrees right
        assertEquals(60.0, f.heading!!, 1.0)
    }

    @Test fun `with no rate sensor it simply follows GPS`() {
        val f = HeadingFusion()
        f.onFix(10.0, 20.0, 1.0)
        f.onFix(80.0, 20.0, 1.0)
        assertNotNull(f.heading)
        assertEquals("no gyro means no reason to lag behind GPS", 80.0, f.heading!!, 1e-6)
    }

    // ---- "does it work in any orientation?" ---------------------------------

    /**
     * Drives a 90 degree right turn with the phone held at an arbitrary
     * attitude, and checks the heading came out right.
     *
     * The point: Android reports both the gyroscope and gravity in *device*
     * axes and never rotates them to follow the screen, so the activity being
     * portrait or landscape changes nothing. What matters is the physical
     * attitude of the phone, and the projection onto gravity removes it.
     */
    private fun turnMounted(upx: Double, upy: Double, upz: Double, degrees: Double): Double {
        val f = HeadingFusion()
        f.onFix(0.0, CRAWL_MPS, 1.0)
        // Rotation about the true vertical, expressed in device axes: the
        // rate vector is along the (unit) up axis, scaled by the rate.
        val n = Math.sqrt(upx * upx + upy * upy + upz * upz)
        val rad = Math.toRadians(-degrees / 3.0)      // over three seconds
        var t = 0.0
        while (t < 3.0) {
            f.onGyro(rad * upx / n, rad * upy / n, rad * upz / n, upx, upy, upz, 0.02)
            t += 0.02
        }
        return f.heading!!
    }

    @Test fun `every mounting attitude reads the same turn`() {
        val mounts = listOf(
            Triple(0.0, 0.0, 1.0) to "flat on the dash, screen up",
            Triple(0.0, 0.0, -1.0) to "flat, screen down",
            Triple(0.0, 1.0, 0.0) to "upright in a cradle, portrait",
            Triple(0.0, -1.0, 0.0) to "upright, upside down",
            Triple(1.0, 0.0, 0.0) to "on its side, landscape",
            Triple(-1.0, 0.0, 0.0) to "on its other side",
            Triple(0.0, 0.60, 0.80) to "a cradle leaning back about 37 degrees",
            Triple(0.35, 0.50, 0.79) to "a cradle skewed in two axes"
        )
        for ((up, how) in mounts) {
            val h = turnMounted(up.first, up.second, up.third, 90.0)
            assertEquals("90 degrees right, $how", 90.0, h, 2.0)
        }
    }

    @Test fun `a left turn is a left turn whatever the attitude`() {
        for (up in listOf(Triple(0.0, 0.0, 1.0), Triple(0.0, 1.0, 0.0),
                          Triple(0.35, 0.50, 0.79))) {
            val h = turnMounted(up.first, up.second, up.third, -90.0)
            assertEquals(270.0, h, 2.0)
        }
    }

    @Test fun `an unnormalised gravity vector is fine`() {
        // Android reports gravity in m/s2, magnitude about 9.81, not 1.
        val f = HeadingFusion()
        f.onFix(0.0, CRAWL_MPS, 1.0)
        var t = 0.0
        val rad = Math.toRadians(-30.0)
        while (t < 3.0) { f.onGyro(0.0, 0.0, rad, 0.0, 0.0, 9.81, 0.02); t += 0.02 }
        assertEquals(90.0, f.heading!!, 2.0)
    }

    @Test fun `free fall does not produce a heading out of nothing`() {
        // No gravity vector means no vertical, so nothing to integrate about.
        val f = HeadingFusion()
        f.onFix(0.0, CRAWL_MPS, 1.0)
        repeat(150) { f.onGyro(0.0, 0.0, Math.toRadians(-30.0), 0.0, 0.0, 0.0, 0.02) }
        assertEquals("heading must not drift on a null vertical", 0.0, f.heading!!, 0.001)
    }

    // ---- the head unit: no sensors, no fix, one compass on a wire -----------

    /**
     * Every test above this line hands the filter either a GPS fix or a rate
     * sensor to lean on. The unit this is fitted to has neither: no gyroscope,
     * no magnetometer, no receiver of its own. The only thing that ever reports
     * is the board's magnetometer, at 5 Hz over serial, and it carries its own
     * timestamp because nothing else in the system has a clock to offer.
     *
     * That was the gap. With no fix and no sensor callback, nothing advanced
     * the filter's idea of "now", the compass's readings were being stamped
     * against zero, and the freshness test threw away the only absolute source
     * there was.
     */
    @Test fun `a board compass alone is enough to point the arrow`() {
        val f = HeadingFusion()
        f.onExternalCompass(141.0, 30_000L)
        assertNotNull("no fix and no sensors is a supported configuration", f.heading)
        assertEquals(141.0, f.heading!!, 1e-9)
        assertTrue(f.usingCompass)
    }

    @Test fun `and it is still pointing after a quarter of an hour of it`() {
        val f = HeadingFusion()
        var t = 30_000L
        // Fifteen minutes of $MAG, then a right turn at the end of it.
        repeat(4500) { f.onExternalCompass(10.0, t); t += 200L }
        assertEquals(10.0, f.heading!!, 1e-9)
        repeat(50) { f.onExternalCompass(100.0, t); t += 200L }
        assertEquals("a long drive must not stale it out", 100.0, f.heading!!, 1e-9)
        assertTrue(f.usingCompass)
    }

    @Test fun `and it hands over as soon as GPS has a bearing worth having`() {
        // 5 km/h, not 70. The compass is bolted to the car and calibrated,
        // which earns it the band where GPS has no bearing at all -- and not a
        // metre per second more than that.
        val f = HeadingFusion()
        f.onExternalCompass(141.0, 30_000L)
        assertTrue(f.usingCompass)
        f.onFix(200.0, 1.5, 1.0)                      // 5.4 km/h
        assertFalse(f.usingCompass)
        assertEquals(200.0, f.heading!!, 1e-6)
    }

    // ---- on a hill, or with the box turned: learned against GPS -------------

    /**
     * Drive [s] seconds at [mps] on GPS bearing [gps] while the board reads
     * [board], one fix and five $MAG rows a second. Returns the clock.
     */
    private fun drive(f: HeadingFusion, t0: Long, s: Int, gps: Double, board: Double,
                      mps: Double = 14.0): Long {
        var t = t0
        repeat(s) {
            repeat(5) { f.onExternalCompass(board, t); t += 200L }
            f.onFix(gps, mps, 1.0)
        }
        return t
    }

    /** Stand still for [s] seconds, the board reading [board]. */
    private fun stand(f: HeadingFusion, t0: Long, s: Int, board: Double): Long {
        var t = t0
        repeat(s) {
            f.onFix(null, 0.0, 1.0)
            repeat(5) { f.onExternalCompass(board, t); t += 200L }
        }
        return t
    }

    /**
     * The owner's question: the board lies flat on the dashboard, so on a hill
     * it is tilted with the car, and at a 65-degree dip that reads 10-15
     * degrees out. The arrow used to jump by that the moment the car stopped
     * and the compass took over from GPS.
     */
    @Test fun `on a hill the arrow stays where GPS left it when the car stops`() {
        val f = HeadingFusion()
        // Up a slope facing east; tilted, the board reads 12 degrees out.
        var t = drive(f, 100_000L, 20, gps = 90.0, board = 102.0)
        assertEquals(-12.0, f.hudCorrection!!, 0.5)
        t = stand(f, t, 10, board = 102.0)
        assertTrue(f.usingCompass)
        assertEquals("not the tilted 102", 90.0, f.heading!!, 0.5)
    }

    /** Which way round the box sits on the dash does not matter either. */
    @Test fun `the box turned any way on the dash is learned away`() {
        val f = HeadingFusion()
        var t = drive(f, 100_000L, 20, gps = 90.0, board = 227.0)   // turned 137 degrees
        t = stand(f, t, 5, board = 227.0)
        assertEquals("held on GPS's heading while the raw compass is distrusted",
            90.0, f.heading!!, 0.5)
        // And once the distrust has been forgiven, the corrected compass agrees.
        t = stand(f, t, (HeadingFusion.FORGIVE_AFTER_MS / 1000).toInt() + 5, board = 227.0)
        assertTrue(f.usingCompass)
        assertEquals(90.0, f.heading!!, 0.5)
    }

    @Test fun `turning at a crawl turns the arrow by what the compass saw change`() {
        val f = HeadingFusion()
        var t = drive(f, 100_000L, 20, gps = 90.0, board = 102.0)
        t = stand(f, t, 3, board = 102.0)
        // A quarter turn right at walking pace: the board goes 102 -> 192.
        for (i in 1..18) {
            f.onFix(null, 0.5, 0.2)
            f.onExternalCompass(102.0 + i * 5.0, t); t += 200L
        }
        assertEquals(180.0, f.heading!!, 0.5)
    }

    /** Through a bend GPS lags the car; that disagreement is not the compass's. */
    @Test fun `a bend teaches it nothing`() {
        val f = HeadingFusion()
        var t = 100_000L
        for (i in 0 until 12) {
            val gps = i * 15.0
            repeat(5) { f.onExternalCompass(gps + 20.0, t); t += 200L }
            f.onFix(gps, 14.0, 1.0)
        }
        assertNull(f.hudCorrection)
    }

    @Test fun `too slow for GPS to know, too slow to learn from`() {
        val f = HeadingFusion()
        drive(f, 100_000L, 20, gps = 90.0, board = 102.0, mps = 2.0)   // 7 km/h
        assertNull(f.hudCorrection)
    }

    @Test fun `one bad bearing moves it by a fraction`() {
        val f = HeadingFusion()
        var t = drive(f, 100_000L, 20, gps = 90.0, board = 102.0)
        // One bearing 6 degrees off, within the straight-road gate.
        repeat(5) { f.onExternalCompass(102.0, t); t += 200L }
        f.onFix(96.0, 14.0, 1.0)
        assertEquals(-12.0, f.hudCorrection!!, 2.0)
    }

    /** The phone's compass has its own mounting offset; this is only the board's. */
    @Test fun `the phone's compass is not corrected with the board's numbers`() {
        val f = HeadingFusion()
        val t = drive(f, 100_000L, 20, gps = 90.0, board = 102.0)
        f.setClock(t + HeadingFusion.HUD_COMPASS_STALE_MS)     // cable out
        f.onFix(null, 0.0, 1.0)
        repeat(50) { f.onCompass(180.0) }
        assertEquals(180.0, f.heading!!, 1.0)
    }

    @Test fun `a car that has not moved starts pointing the way it was left`() {
        val f = HeadingFusion()
        f.restoreHudCorrection(-12.0)
        f.onExternalCompass(102.0, 30_000L)
        assertEquals(90.0, f.heading!!, 1e-6)
        // What is learned this drive replaces it; a restore never overwrites that.
        val g = HeadingFusion()
        drive(g, 100_000L, 20, gps = 90.0, board = 95.0)
        g.restoreHudCorrection(-12.0)
        assertEquals(-5.0, g.hudCorrection!!, 0.5)
    }

    @Test fun `nothing learned and nothing restored, the board as it reads`() {
        val f = HeadingFusion()
        f.onExternalCompass(141.0, 30_000L)
        assertNull(f.hudCorrection)
        assertEquals(141.0, f.heading!!, 1e-9)
    }
}
