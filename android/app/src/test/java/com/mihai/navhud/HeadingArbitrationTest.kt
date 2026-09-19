package com.mihai.navhud

import com.mihai.navhud.map.Compass
import com.mihai.navhud.map.HeadingFusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Heading arbitration, against the behaviour documented in docs/HEADING.md.
 *
 * Speed decides, and the crossover is 5 km/h. Below it the compass owns the
 * arrow, because that is the band where a GPS bearing does not exist yet --
 * standing still, shunting into a space, edging out of a junction. Above it
 * GPS is measuring the direction of travel from orbit, and no magnetometer
 * sitting in a car improves on that.
 *
 * The crossover used to be 70 km/h, which was the right answer for a phone
 * compass fighting a cradle and a steel shell. The compass is on the HUD
 * board now -- bolted down, hard iron calibrated out -- and being a good
 * magnetometer does not make it a better odometer.
 */
class HeadingArbitrationTest {

    private fun settle(f: HeadingFusion, deg: Double, n: Int = 120) {
        repeat(n) { f.onCompass(deg) }
    }

    @Test
    fun `parked, the compass has it`() {
        val f = HeadingFusion()
        f.setClock(100_000L)
        settle(f, 141.0)
        assertTrue(f.usingCompass)
        assertEquals(141.0, f.heading!!, 0.5)
        assertEquals("heading: compass", f.describe())
    }

    @Test
    fun `at a crawl the compass still turns the arrow`() {
        // The band the compass keeps is narrow now -- under 5 km/h -- but it is
        // the band where it is the only thing that knows anything: GPS derives
        // its bearing from displacement between fixes, and at 4 km/h there is
        // barely any.
        val f = HeadingFusion()
        f.setClock(100_000L)
        settle(f, 141.0)

        f.setClock(101_000L)
        f.onFix(141.0, 1.2, 1.0)                 // 4.3 km/h
        assertTrue("the compass still owns the arrow at a crawl", f.usingCompass)

        // Swing the car to face east while crawling. The arrow follows.
        repeat(30) { f.setClock(101_500L + it * 50L); f.onCompass(90.0, 0.05) }
        assertEquals(90.0, f.heading!!, 5.0)
    }

    @Test
    fun `above the crossover the arrow is GPS only`() {
        val f = HeadingFusion()
        f.setClock(100_000L)
        settle(f, 141.0)

        f.setClock(101_000L)
        f.onFix(20.0, 1.5, 1.0)                  // 5.4 km/h
        assertFalse("GPS takes over above 5 km/h", f.usingCompass)

        // The compass now shouts 300 degrees. Ignored.
        repeat(30) { f.setClock(101_500L); f.onCompass(300.0) }
        repeat(3) { f.setClock(102_000L); f.onFix(20.0, 1.5, 1.0) }
        assertEquals(20.0, f.heading!!, 3.0)
    }

    @Test
    fun `the changeover has hysteresis so it cannot flap at the crossover`() {
        // Driven through onFix on purpose. `compassDrove` -- which side of the
        // band we arrived from -- is only advanced there, so a test that set
        // the speed any other way would be asserting on a latch that never
        // moved and would pass whatever the two constants said.
        val f = HeadingFusion()
        f.setClock(100_000L)
        settle(f, 141.0)

        // 4.3 km/h: still the compass, because we came from below.
        f.setClock(101_000L); f.onFix(141.0, 1.2, 1.0)
        assertTrue(f.usingCompass)
        // Over the top: GPS.
        f.setClock(102_000L); f.onFix(141.0, 1.5, 1.0)
        assertFalse(f.usingCompass)
        // Back to 4.3: GPS keeps it, because the resume point is 3.5 km/h.
        f.setClock(103_000L); f.onFix(141.0, 1.2, 1.0)
        assertFalse("no flapping while the car holds a steady crawl", f.usingCompass)
        // Down to 2.9 km/h: the compass has it again.
        f.setClock(104_000L); f.onFix(141.0, 0.8, 1.0)
        assertTrue(f.usingCompass)
    }

    @Test
    fun `stopping hands it straight back to the compass`() {
        val f = HeadingFusion()
        f.setClock(200_000L)
        f.onCompass(90.0)              // there is a compass in this phone
        f.onFix(90.0, 2.0, 1.0)        // 7.2 km/h: over the crossover
        assertFalse(f.usingCompass)

        f.setClock(201_000L)
        f.onFix(null, 0.0, 1.0)        // stopped
        assertTrue("no waiting out a lockout at the kerb", f.usingCompass)
    }

    @Test
    fun `a crawl does not hand the heading to GPS`() {
        val f = HeadingFusion()
        f.setClock(300_000L)
        settle(f, 180.0)
        // 0.3 m/s: a GPS bearing at that speed is noise, not a direction.
        f.setClock(301_000L)
        f.onFix(45.0, 0.3, 1.0)
        assertTrue(f.usingCompass)
        assertEquals(180.0, f.heading!!, 1.0)
    }

    @Test
    fun `a compass that argues with GPS for two seconds is demoted`() {
        val f = HeadingFusion()
        f.setClock(400_000L)
        settle(f, 200.0)                       // compass says 200
        assertFalse(f.compassDistrusted)

        // GPS repeatedly says 20 -- 180 degrees out, well past the 45 gate.
        // At 3.6 km/h the compass is the thing driving the arrow, which is the
        // case worth testing: demoting it has to actually take the arrow away.
        f.setClock(401_000L); f.onFix(20.0, 1.0, 1.0)
        assertFalse("one disagreement is not enough", f.compassDistrusted)
        f.setClock(402_000L); f.onFix(20.0, 1.0, 1.0)
        assertFalse(f.compassDistrusted)
        f.setClock(403_500L); f.onFix(20.0, 1.0, 1.0)
        assertTrue("sustained disagreement should demote it", f.compassDistrusted)
        assertEquals("the same fix that demotes it must also take the arrow",
            20.0, f.heading!!, 1e-6)

        // ...and even once stopped long enough, it stays demoted.
        f.setClock(420_000L)
        assertFalse(f.usingCompass)
    }

    @Test
    fun `a brief disagreement is forgiven`() {
        val f = HeadingFusion()
        f.setClock(500_000L)
        settle(f, 200.0)
        f.setClock(501_000L); f.onFix(20.0, 1.0, 1.0)      // one spike
        f.setClock(501_500L); f.onFix(200.0, 1.0, 1.0)     // agrees again
        assertFalse(f.compassDistrusted)
    }

    // ---- the pieces --------------------------------------------------------

    @Test
    fun `the circular average crosses the seam correctly`() {
        // 350 and 10 average to 0, not to 180.
        assertEquals(0.0, Compass.emaStep(350.0, 10.0, 0.5), 0.01)
        assertEquals(350.0, Compass.emaStep(340.0, 0.0, 0.5), 0.01)
        // A single outlier moves it by alpha, not all the way.
        val moved = Compass.emaStep(0.0, 40.0, 0.08)
        assertTrue("$moved", moved in 2.0..4.0)
    }

    @Test
    fun `a field that is not the earth's is rejected`() {
        // Brussels is about 49 uT.
        assertTrue(Compass.fieldPlausible(20.0, 40.0, -20.0))
        // A speaker magnet or a door pillar.
        assertFalse(Compass.fieldPlausible(300.0, 40.0, -20.0))
        // Shielded, or the car cancelling the field.
        assertFalse(Compass.fieldPlausible(1.0, 2.0, 1.0))
    }
}

/**
 * The remap table, against the values Android documents and AOSP implements.
 *
 * These are the assertions that would have caught the 180-degree swap: the
 * previous implementation read the parameters as the transpose, and for a
 * quarter turn the transpose is the inverse.
 */
class RemapTest {

    private fun rotZ(deg: Double): DoubleArray {
        val a = Math.toRadians(deg)
        return doubleArrayOf(
            cos(a), -sin(a), 0.0,
            sin(a), cos(a), 0.0,
            0.0, 0.0, 1.0
        )
    }

    private fun rotX(deg: Double): DoubleArray {
        val a = Math.toRadians(deg)
        return doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, cos(a), -sin(a),
            0.0, sin(a), cos(a)
        )
    }

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) {
            var v = 0.0
            for (k in 0..2) v += a[r * 3 + k] * b[k * 3 + c]
            o[r * 3 + c] = v
        }
        return o
    }

    /** Phone flat, its own top edge pointing at `deg`. */
    private fun flatFacing(deg: Double) = rotZ(-deg)

    @Test
    fun `rotation 90 and 270 are a quarter turn either side, not swapped`() {
        val north = flatFacing(0.0)
        // Device +Y points north. In ROTATION_90 the display's +Y is device +X,
        // which points east.
        assertEquals(90.0, Compass.azimuthOf(Compass.remapForDisplay(north, 1), Compass.AXIS_TOP)!!, 0.01)
        // ...and in ROTATION_270 it is device -X, which points west.
        assertEquals(270.0, Compass.azimuthOf(Compass.remapForDisplay(north, 3), Compass.AXIS_TOP)!!, 0.01)
        // The two must differ by half a circle. Reading the doc backwards made
        // them identical to swapping these two rows.
        assertEquals(180.0, Math.abs(Geo.signedDelta(
            Compass.azimuthOf(Compass.remapForDisplay(north, 1), Compass.AXIS_TOP)!!,
            Compass.azimuthOf(Compass.remapForDisplay(north, 3), Compass.AXIS_TOP)!!)), 0.01)
    }

    @Test
    fun `rotation 180 turns it right round`() {
        val north = flatFacing(0.0)
        assertEquals(180.0, Compass.azimuthOf(Compass.remapForDisplay(north, 2), Compass.AXIS_TOP)!!, 0.01)
        assertEquals(0.0, Compass.azimuthOf(Compass.remapForDisplay(north, 0), Compass.AXIS_TOP)!!, 0.01)
    }

    @Test
    fun `an upright phone reads its screen, and turning it end for end changes nothing`() {
        // Stood up in a cradle, screen facing back at the driver, so the car
        // is heading the way the screen faces: north.
        val upright = mul(rotZ(0.0), rotX(90.0))
        val h = Compass.headingFor(upright, 1)!!

        // The same phone rotated 180 degrees in its own plane -- charging socket
        // on the other side, which is what a landscape-locked app cannot see.
        val flipped = mul(upright, rotZ(180.0))
        assertEquals("turning the handset must not turn the car", h, Compass.headingFor(flipped, 1)!!, 0.01)

        // ...and the display rotation must not matter either.
        assertEquals(h, Compass.headingFor(upright, 3)!!, 0.01)
    }

    @Test
    fun `an upright phone is not stuck on one heading`() {
        // The gimbal-lock failure: the classic recipe returns a constant.
        val headings = listOf(0.0, 90.0, 180.0, 270.0).map { car ->
            Compass.headingFor(mul(rotZ(-car), rotX(90.0)), 1)!!
        }
        assertEquals(4, headings.toSet().size)
        assertEquals(0.0, headings[0], 0.01)
        assertEquals(90.0, headings[1], 0.01)
        assertEquals(180.0, headings[2], 0.01)
        assertEquals(270.0, headings[3], 0.01)
    }
}

/**
 * The regressions the verification pass caught before this shipped.
 */
class HeadingRegressionTest {

    private fun rotZ(deg: Double): DoubleArray {
        val a = Math.toRadians(deg)
        return doubleArrayOf(cos(a), -sin(a), 0.0, sin(a), cos(a), 0.0, 0.0, 0.0, 1.0)
    }

    private fun rotX(deg: Double): DoubleArray {
        val a = Math.toRadians(deg)
        return doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(a), -sin(a), 0.0, sin(a), cos(a))
    }

    private fun rotY(deg: Double): DoubleArray {
        val a = Math.toRadians(deg)
        return doubleArrayOf(cos(a), 0.0, sin(a), 0.0, 1.0, 0.0, -sin(a), 0.0, cos(a))
    }

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) {
            var v = 0.0
            for (k in 0..2) v += a[r * 3 + k] * b[k * 3 + c]
            o[r * 3 + c] = v
        }
        return o
    }

    /**
     * Two axes read the same attitude differently by the roll angle, so a
     * mount that leans and sits near the changeover would flip the arrow
     * through a right angle on every bump. The axis is a held decision now,
     * and this is the band nothing used to test.
     */
    @Test
    fun `the two axes disagree in the middle of the tilt range`() {
        // A cradle tilted 45 degrees back and rolled 40 degrees to one side.
        val r = mul(rotX(45.0), rotY(40.0))
        val viaScreen = Compass.headingFor(r, 1, Compass.AXIS_SCREEN)
        val viaTop = Compass.headingFor(r, 1, Compass.AXIS_TOP)
        // Both readable at this attitude...
        assertTrue(viaScreen != null && viaTop != null)
        // ...and they do not agree, which is exactly why the choice must be
        // made once rather than per sample.
        assertTrue("they should differ: $viaScreen vs $viaTop",
            Math.abs(Geo.signedDelta(viaScreen!!, viaTop!!)) > 20.0)
    }

    @Test
    fun `there is no attitude where neither axis has a heading`() {
        var worst = 999
        for (p in 0..90 step 5) for (rl in 0..90 step 5) for (yaw in 0..350 step 30) {
            val r = mul(rotZ(-yaw.toDouble()), mul(rotX(p.toDouble()), rotY(rl.toDouble())))
            val a = Compass.headingFor(r, 1, Compass.AXIS_SCREEN)
            val b = Compass.headingFor(r, 1, Compass.AXIS_TOP)
            if (a == null && b == null) worst = 0
        }
        assertTrue("some attitude had no heading at all", worst != 0)
    }

    /**
     * An IMU reports a turn *rate*. It can say how far the car turned and
     * never which way it is pointing, so it must not be allowed to block the
     * one source that can.
     */
    @Test
    fun `the HUD gyro does not stop the compass setting a heading`() {
        val f = HeadingFusion()
        f.setClock(50_000L)
        f.onExternalYawRate(0.0, 0.02)          // HUD is connected and reporting
        repeat(100) { f.onCompass(141.0) }
        assertEquals(141.0, f.heading!!, 1.0)
        assertTrue(f.usingCompass)
    }

    @Test
    fun `a stale compass reading is not evidence against the compass`() {
        val f = HeadingFusion()
        f.setClock(600_000L)
        f.onCompass(0.0)                        // last reading: north

        // Ten minutes underground, then out again heading south. The old
        // reading is now 180 degrees out through no fault of the sensor.
        f.setClock(1_200_000L)
        repeat(5) { f.setClock(1_200_000L + it * 1000L); f.onFix(180.0, 15.0, 1.0) }
        assertFalse("must not demote on a ten minute old sample", f.compassDistrusted)
    }

    @Test
    fun `a distrusted compass is forgiven eventually`() {
        val f = HeadingFusion()
        f.setClock(700_000L)
        repeat(60) { f.onCompass(200.0) }
        f.setClock(701_000L); f.onFix(20.0, 10.0, 1.0)
        f.setClock(704_000L); f.onFix(20.0, 10.0, 1.0)
        assertTrue(f.compassDistrusted)
        // Parked for a couple of minutes: the standing-still compass is the
        // one thing that matters now, so it gets another chance.
        f.setClock(704_000L + 90_000L)
        assertFalse(f.compassDistrusted)
    }

    @Test
    fun `the compass keeps up with a car turning into a parking space`() {
        val f = HeadingFusion()
        f.setClock(800_000L)
        repeat(60) { f.onCompass(0.0, 0.067) }
        assertEquals(0.0, f.heading!!, 0.5)

        // Swing 90 degrees over a second and a half, at the sensor's 15 Hz.
        for (i in 1..22) f.onCompass(i * 4.0, 0.067)
        // Then hold. Within a second it must have caught up.
        repeat(15) { f.onCompass(88.0, 0.067) }
        assertEquals("should not trail the car", 88.0, f.heading!!, 6.0)
    }
}
