package com.mihai.navhud

import com.mihai.navhud.map.Compass
import com.mihai.navhud.map.HeadingFusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The absolute-heading path: knowing which way the car points before it moves.
 *
 * The numbers here are Mihai's own reading — phone laid in a landscape cradle,
 * stock compass app saying 141° SE at Drogenbos — used as the worked example.
 */
class CompassTest {

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

    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    @Test
    fun `a phone lying flat reads its top edge as the heading`() {
        // Identity: device axes are world axes, so +Y is north.
        assertEquals(0.0, Compass.azimuthOf(identity, Compass.AXIS_TOP)!!, 0.01)
        // Rotate the phone 90 degrees clockwise seen from above: +Y now east.
        val east = mul(rotZ(-90.0), identity)
        assertEquals(90.0, Compass.azimuthOf(east, Compass.AXIS_TOP)!!, 0.01)
        val se = mul(rotZ(-141.0), identity)
        assertEquals(141.0, Compass.azimuthOf(se, Compass.AXIS_TOP)!!, 0.01)
    }

    @Test
    fun `an upright phone has no heading in its top edge, and one in its screen`() {
        // Tipped up 90 degrees: +Y points at the sky.
        val upright = rotX(90.0)
        assertNull(Compass.azimuthOf(upright, Compass.AXIS_TOP))
        assertTrue(Compass.azimuthOf(upright, Compass.AXIS_SCREEN) != null)
        // rotation 0: the display's Y axis is the device's Y axis.
        assertEquals(Compass.AXIS_SCREEN, Compass.bestAxis(upright, 0))
        assertEquals(Compass.AXIS_TOP, Compass.bestAxis(identity, 0))
    }

    @Test
    fun `the mounting offset absorbs a phone turned sideways in its cradle`() {
        // The car faces 141 SE. The phone lies in a landscape cradle, turned
        // 90 degrees, so its top edge points 231 -- the reading his compass app
        // would give for the phone itself, not for the car.
        val phone = mul(rotZ(-231.0), identity)
        val az = Compass.azimuthOf(phone, Compass.AXIS_TOP)!!
        assertEquals(231.0, az, 0.01)

        // One drive above 25 km/h with a GPS bearing of 141 teaches it the
        // offset, and from then on the same reading gives the right answer.
        val offset = Compass.offsetFor(az, 141.0)
        assertEquals(270.0, offset, 0.01)
        assertEquals(141.0, Geo.normalizeDeg(az + offset), 0.01)

        // Turn the car 90 degrees to the right; the phone turns with it, and
        // the same offset still gives the car's heading, now 231.
        val right = mul(rotZ(-90.0), phone)
        assertEquals(231.0,
            Geo.normalizeDeg(Compass.azimuthOf(right, Compass.AXIS_TOP)!! + offset), 0.01)

        // ...and back round to due north.
        val north = mul(rotZ(141.0), phone)
        assertEquals(0.0,
            Geo.normalizeDeg(Compass.azimuthOf(north, Compass.AXIS_TOP)!! + offset), 0.01)
    }

    @Test
    fun `blending an offset takes the short way round zero`() {
        assertEquals(2.0, Compass.blendOffset(358.0, 6.0, 0.5), 0.01)
        assertEquals(358.0, Compass.blendOffset(2.0, 354.0, 0.5), 0.01)
    }

    @Test
    fun `the compass sets a stationary heading but never fights GPS`() {
        val f = HeadingFusion()
        f.setClock(10_000L)
        // Nothing known: the compass gets to say.
        repeat(120) { f.onCompass(141.0) }
        assertEquals(141.0, f.heading!!, 0.5)

        // Drive off; GPS establishes the truth and latches.
        f.setClock(11_000L)
        repeat(3) { f.onFix(90.0, 20.0, 1.0) }
        assertEquals(90.0, f.heading!!, 2.0)

        // Now a wildly wrong compass reading must not move it.
        repeat(50) { f.onCompass(300.0, 0.2) }
        assertEquals(90.0, f.heading!!, 2.0)
    }

    /**
     * The compass leads the arrow now rather than nudging it, so the easing is
     * much faster than it was — about a quarter of a second, not three. It is
     * still easing: a magnetometer in a car jitters by a degree or two, and a
     * marker that twitches reads worse than one that lags very slightly.
     */
    @Test
    fun `the heading eases towards the compass rather than jumping`() {
        val f = HeadingFusion()
        f.setClock(1_000L)
        f.onCompass(0.0)

        // One sample at the sensor's own rate moves it part of the way, not
        // all of it: a single bad reading costs a fraction of its error.
        f.onCompass(60.0, 0.067)
        val afterOne = f.heading!!
        assertTrue("one sample should move it part way: $afterOne", afterOne in 4.0..20.0)

        // ...within three degrees after a second...
        repeat(15) { f.onCompass(60.0, 0.067) }
        assertEquals(60.0, f.heading!!, 3.5)
        // ...and there after two.
        repeat(15) { f.onCompass(60.0, 0.067) }
        assertEquals(60.0, f.heading!!, 0.5)
    }
}
