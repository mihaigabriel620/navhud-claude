package com.mihai.navhud

import com.mihai.navhud.map.HeadingFusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The orientation-sensor path: "make the arrow point where the car points even
 * when it is standing still."
 *
 * The maths under test is the vertical-axis component of a relative rotation
 * matrix. These build real rotation matrices for a phone at an arbitrary
 * mounting attitude, turn the whole thing about the world vertical, and check
 * that the extracted yaw is the angle it was turned through -- whatever the
 * mounting attitude was, which is the entire point of doing it this way.
 */
class OrientationTest {

    /** Rotation about the world vertical (ENU z) by `deg`, anticlockwise. */
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

    @Test
    fun `a turn about the vertical is read back, whatever the mounting angle`() {
        // Eight mounting attitudes: flat, upright in a cradle, and everything
        // between, including the one that breaks getOrientation().
        for (pitch in listOf(0.0, 15.0, 30.0, 45.0, 60.0, 75.0, 89.0, 90.0)) {
            val mount = rotX(pitch)
            for (turn in listOf(-3.0, -0.5, 0.2, 1.0, 2.5)) {
                val now = mul(rotZ(turn), mount)
                // The fusion's own timesTranspose is private; reproduce it.
                val rel = DoubleArray(9)
                for (r in 0..2) for (c in 0..2) {
                    var v = 0.0
                    for (k in 0..2) v += now[r * 3 + k] * mount[c * 3 + k]
                    rel[r * 3 + c] = v
                }
                val got = Math.toDegrees(HeadingFusion.yawOf(rel))
                // Turning anticlockwise seen from above lowers the compass
                // heading, hence the sign.
                assertEquals("pitch=$pitch turn=$turn", -turn, got, 0.02)
            }
        }
    }

    @Test
    fun `the map turns with a parked car`() {
        val f = HeadingFusion()
        f.onFix(90.0, 20.0, 1.0)              // driving east, then stop
        f.onFix(90.0, 0.0, 1.0)
        assertEquals(90.0, f.heading!!, 0.01)

        var r = rotX(35.0)                     // phone in a cradle
        f.onOrientation(r, 0.02)
        // Swing the car 60 degrees to the right, a degree at a time.
        repeat(60) {
            r = mul(rotZ(-1.0), r)
            f.onOrientation(r, 0.02)
        }
        assertEquals(150.0, f.heading!!, 0.5)
        assertEquals(HeadingFusion.Source.PHONE_ROTATION, f.source)
    }

    @Test
    fun `a gap in the samples is not integrated`() {
        val f = HeadingFusion()
        f.onFix(0.0, 20.0, 1.0)
        var r = rotX(20.0)
        f.onOrientation(r, 0.02)
        r = mul(rotZ(-40.0), r)
        // Screen was off for four seconds: this "delta" is not a real turn.
        f.onOrientation(r, 4.0)
        assertEquals(0.0, f.heading!!, 0.01)
    }

    @Test
    fun `a parked phone on a desk does not drift`() {
        val f = HeadingFusion()
        f.onFix(180.0, 20.0, 1.0)
        f.onFix(180.0, 0.0, 1.0)
        val r = rotX(0.0)
        repeat(2000) { f.onOrientation(r, 0.02) }   // 40 seconds of stillness
        assertEquals(180.0, f.heading!!, 0.001)
    }

    @Test
    fun `the raw gyro still turns a parked car, above its noise floor`() {
        val f = HeadingFusion()
        f.onFix(0.0, 20.0, 1.0)
        f.onFix(0.0, 0.0, 1.0)
        // 20 deg/s to the right for one second, gravity straight down.
        repeat(50) {
            f.onGyro(0.0, 0.0, -Math.toRadians(20.0), 0.0, 0.0, 1.0, 0.02)
        }
        assertTrue("expected a real turn, got ${f.heading}", f.heading!! > 15.0)
    }

    @Test
    fun `gyro noise on a parked car is ignored`() {
        val f = HeadingFusion()
        f.onFix(0.0, 20.0, 1.0)
        f.onFix(0.0, 0.0, 1.0)
        // 1 deg/s of bias-like noise, well under the deadband, for 30 seconds.
        repeat(1500) {
            f.onGyro(0.0, 0.0, Math.toRadians(1.0), 0.0, 0.0, 1.0, 0.02)
        }
        assertEquals(0.0, f.heading!!, 0.01)
    }
}

/**
 * The regression the first version of this feature shipped with: a phone that
 * has both a gyroscope and a rotation vector integrating the same turn twice.
 */
class DoubleIntegrationTest {

    private fun rotZ(deg: Double): DoubleArray {
        val a = Math.toRadians(deg)
        return doubleArrayOf(
            Math.cos(a), -Math.sin(a), 0.0,
            Math.sin(a), Math.cos(a), 0.0,
            0.0, 0.0, 1.0
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

    @Test
    fun `both sensors reporting a 90 degree turn move the heading 90 degrees`() {
        val f = HeadingFusion()
        f.onFix(0.0, 20.0, 1.0)
        var r = DoubleArray(9).also { it[0] = 1.0; it[4] = 1.0; it[8] = 1.0 }
        f.onOrientation(r, 0.02)

        // 90 degrees to the right over 3 seconds, both sensors describing the
        // same physical motion at 50 Hz, exactly as a real phone would.
        val rate = 30.0                                  // deg/s
        repeat(150) {
            r = mul(rotZ(-rate * 0.02), r)
            f.onOrientation(r, 0.02)
            f.onGyro(0.0, 0.0, -Math.toRadians(rate), 0.0, 0.0, 1.0, 0.02)
        }
        assertEquals("must not double-count the turn", 90.0, f.heading!!, 1.0)
        assertEquals(HeadingFusion.Source.PHONE_ROTATION, f.source)
    }

    @Test
    fun `the HUD IMU keeps its reference orientation current while it drives`() {
        val f = HeadingFusion()
        f.onFix(0.0, 20.0, 1.0)
        var r = DoubleArray(9).also { it[0] = 1.0; it[4] = 1.0; it[8] = 1.0 }
        f.onOrientation(r, 0.02)

        // The HUD's own gyro takes over and drives a 90 degree turn, while the
        // phone's rotation vector keeps reporting the same motion underneath.
        repeat(150) {
            f.onExternalYawRate(30.0, 0.02)
            r = mul(rotZ(-30.0 * 0.02), r)
            f.onOrientation(r, 0.02)
        }
        assertEquals(90.0, f.heading!!, 1.0)

        // Cable out. The next phone sample must not dump the accumulated
        // difference into the heading in one step.
        f.onExternalLost(phoneGyroPresent = true)
        val before = f.heading!!
        r = mul(rotZ(-0.6), r)
        f.onOrientation(r, 0.02)
        assertEquals("no jump when the HUD drops out", before + 0.6, f.heading!!, 0.05)
    }
}

/**
 * The "oiia cat" bug: the arrow spinning on the spot when the phone is tilted.
 *
 * Two orientation sensors were feeding one difference. Android's game rotation
 * vector and its full rotation vector describe the same physical attitude in
 * different yaw frames — the game one has no magnetometer, so its zero is
 * arbitrary — and differencing across them yields the angle between two
 * reference frames rather than any motion of the car, fifteen times a second.
 */
class MixedFrameTest {

    private fun rotZ(deg: Double): DoubleArray {
        val a = Math.toRadians(deg)
        return doubleArrayOf(
            Math.cos(a), -Math.sin(a), 0.0,
            Math.sin(a), Math.cos(a), 0.0,
            0.0, 0.0, 1.0
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
    fun `samples from a second sensor frame cannot become a phantom turn`() {
        val f = HeadingFusion()
        f.onFix(0.0, 20.0, 1.0)
        assertEquals(0.0, f.heading!!, 0.01)

        // Sensor A sits at the true heading; sensor B's frame is 25 degrees
        // out — small enough to slip under the jump guard, which is what made
        // this so hard to see. The car is perfectly still throughout.
        val a = identity
        val b = mul(rotZ(-25.0), identity)

        repeat(200) {
            f.onOrientation(a, 0.02, frameId = 1)
            f.onOrientation(b, 0.02, frameId = 2)
        }
        assertEquals("a parked car must not have turned", 0.0, f.heading!!, 0.01)
    }

    @Test
    fun `a real turn is still tracked after a frame change`() {
        val f = HeadingFusion()
        f.onFix(0.0, 20.0, 1.0)
        var r = identity
        f.onOrientation(r, 0.02, frameId = 1)

        // The sensor changes -- one sample is lost re-establishing the
        // reference, and then everything works as before.
        f.onOrientation(mul(rotZ(-25.0), r), 0.02, frameId = 2)
        var g = mul(rotZ(-25.0), r)
        repeat(45) {
            g = mul(rotZ(-1.0), g)
            f.onOrientation(g, 0.02, frameId = 2)
        }
        assertEquals(45.0, f.heading!!, 0.5)
    }
}
