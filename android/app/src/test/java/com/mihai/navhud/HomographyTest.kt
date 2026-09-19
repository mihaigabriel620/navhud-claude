package com.mihai.navhud

import com.mihai.navhud.hud.Homography
import com.mihai.navhud.hud.Keystone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The phone's preview and the board's rendering must agree, or the shape you
 * dial in is not the shape you get.
 *
 * The reference numbers below came out of the firmware itself -- compiling
 * arduino/NavHud/hud_geom.h on the host and printing map() for a set of points.
 * That is the point of this file: it is not testing that the maths is right (
 * arduino/test/test_geom.cpp does that), it is testing that two independent
 * implementations of it have not drifted apart.
 */
class HomographyTest {

    private val keystone = Keystone(
        mirrorX = true, mirrorY = false,
        offsets = intArrayOf(44, 16, -44, 16, -8, -4, 8, -4)
    )

    /** x, y, expected X, expected Y -- from `g++ hud_geom.h` on this quad. */
    private val reference = arrayOf(
        doubleArrayOf(0.0, 0.0, 44.0000, 16.0000),
        doubleArrayOf(480.0, 0.0, 436.0000, 16.0000),
        doubleArrayOf(480.0, 320.0, 472.0000, 316.0000),
        doubleArrayOf(0.0, 320.0, 8.0000, 316.0000),
        doubleArrayOf(240.0, 160.0, 240.0000, 153.3832),
        doubleArrayOf(120.0, 80.0, 138.0448, 81.9193),
        doubleArrayOf(360.0, 240.0, 350.9073, 231.1219),
        doubleArrayOf(240.0, 0.0, 240.0000, 16.0000),
        doubleArrayOf(0.0, 160.0, 27.5140, 153.3832),
        doubleArrayOf(479.0, 319.0, 470.9008, 314.8909)
    )

    @Test fun `the Kotlin map agrees with the firmware to a thousandth of a pixel`() {
        val hg = Homography.of(keystone)
        assertNotNull(hg)
        val out = DoubleArray(2)
        for (r in reference) {
            hg!!.map(r[0], r[1], out)
            assertEquals("x at (${r[0]}, ${r[1]})", r[2], out[0], 0.001)
            assertEquals("y at (${r[0]}, ${r[1]})", r[3], out[1], 0.001)
        }
    }

    @Test fun `identity is exactly identity`() {
        val hg = Homography.of(Keystone())
        assertNotNull(hg)
        assertTrue(hg!!.identity)
        val out = DoubleArray(2)
        hg.map(123.0, 45.0, out)
        assertEquals(123.0, out[0], 0.0)
        assertEquals(45.0, out[1], 0.0)
    }

    @Test fun `straight lines stay straight`() {
        // The property the whole approach rests on: a projective map takes
        // lines to lines, so drawing a warped rectangle as two triangles is
        // exact rather than approximate.
        val hg = Homography.of(keystone)!!
        val a = DoubleArray(2); val b = DoubleArray(2); val m = DoubleArray(2)
        var worst = 0.0
        for (yi in 0..8) {
            val y = yi * 40.0
            hg.map(0.0, y, a)
            hg.map(480.0, y, b)
            for (t in 1..19) {
                val u = t / 20.0
                hg.map(480.0 * u, y, m)
                // Distance from the mapped midpoint to the chord. Non-zero
                // because the parameter is not preserved, so measure the
                // perpendicular offset from the *line*, not from the point.
                val dx = b[0] - a[0]; val dy = b[1] - a[1]
                val len = Math.hypot(dx, dy)
                val off = abs(dx * (a[1] - m[1]) - (a[0] - m[0]) * dy) / len
                if (off > worst) worst = off
            }
        }
        assertTrue("worst bow was $worst px", worst < 0.01)
    }

    @Test fun `a crossed quad yields no transform rather than infinities`() {
        val bowtie = Keystone(offsets = intArrayOf(0, 0, 0, 320, 0, 0, 0, -320))
        val hg = Homography.of(bowtie)
        // Either it refuses, or it produces something finite. What it must not
        // do is hand the preview a coordinate that draws across the whole UI.
        if (hg != null) {
            val out = DoubleArray(2)
            for (x in 0..480 step 40) for (y in 0..320 step 40) {
                hg.map(x.toDouble(), y.toDouble(), out)
                assertTrue(out[0].isFinite() && out[1].isFinite())
                assertTrue(abs(out[0]) < 10_000 && abs(out[1]) < 10_000)
            }
        }
    }
}
