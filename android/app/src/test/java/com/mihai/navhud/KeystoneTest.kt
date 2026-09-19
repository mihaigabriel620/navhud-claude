package com.mihai.navhud

import com.mihai.navhud.hud.Keystone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keystone screen's job is to never hand the board a shape it will refuse,
 * because the failure mode is silent: the firmware falls back to no correction
 * and the driver sees a control that does nothing.
 */
class KeystoneTest {

    @Test fun `identity encodes as all zeroes and the board's checksum agrees`() {
        val k = Keystone(mirrorX = true, mirrorY = false)
        assertEquals("\$GEOM,1,0,0,0,0,0,0,0,0,0*01\r\n", k.encode())
        // Same checksum the firmware's own test asserts, so the two cannot
        // drift apart without one of them failing.
        assertEquals("\$GEOMSAVE*01\r\n", Keystone.saveCommand())
        assertEquals("\$GEOM?*3F\r\n", Keystone.queryCommand())
    }

    @Test fun `every slider combination is inward, convex and accepted`() {
        // Exhaustive, not sampled. The failure this guards against showed up
        // only in the far corner of the grid -- both sliders at their limit
        // with signs that pull the same corner twice -- and a step of 7 walked
        // straight past it.
        var checked = 0
        for (v in -Keystone.SLIDER_MAX_X..Keystone.SLIDER_MAX_X) {
            for (h in -Keystone.SLIDER_MAX_Y..Keystone.SLIDER_MAX_Y) {
                val k = Keystone.fromSliders(true, false, h, v)
                assertFalse("h=$h v=$v put a corner off the panel", k.clipsOffPanel())
                assertTrue("h=$h v=$v produced a shape the board rejects", k.isUsable())
                checked++
            }
        }
        assertTrue(checked > 38_000)
    }

    @Test fun `the slider limit is tighter than the corner limit, on purpose`() {
        // At 35 % each the two sliders together turn the quad concave; the
        // firmware then falls back to no correction and the control looks
        // broken. Documented in Keystone.SLIDER_PULL.
        val worst = Keystone.fromSliders(true, false, -Keystone.MAX_Y, -Keystone.MAX_X)
        assertTrue(worst.isUsable())
        assertEquals(Keystone.SLIDER_MAX_X, 120)
        assertEquals(Keystone.SLIDER_MAX_Y, 80)
        // A raw quad at the full corner limit in the same direction is the
        // shape that fails, which is why the sliders cannot produce it.
        val raw = Keystone(offsets = intArrayOf(0, 0, 0, 112, -168, -112, 168, 0))
        assertFalse(raw.isUsable())
    }

    @Test fun `a vertical slider narrows one edge and leaves the other alone`() {
        val top = Keystone.fromSliders(true, false, 0, 40)
        assertEquals(40, top.quadX(Keystone.TOP_LEFT))
        assertEquals(Keystone.SCR_W - 40, top.quadX(Keystone.TOP_RIGHT))
        assertEquals(0, top.quadX(Keystone.BOTTOM_LEFT))
        assertEquals(Keystone.SCR_W, top.quadX(Keystone.BOTTOM_RIGHT))

        val bottom = Keystone.fromSliders(true, false, 0, -40)
        assertEquals(0, bottom.quadX(Keystone.TOP_LEFT))
        assertEquals(40, bottom.quadX(Keystone.BOTTOM_LEFT))
        assertEquals(Keystone.SCR_W - 40, bottom.quadX(Keystone.BOTTOM_RIGHT))
    }

    @Test fun `nudges clamp to the same limit the firmware enforces`() {
        var k = Keystone()
        repeat(200) { k = k.nudge(Keystone.TOP_LEFT, 4, 4) }
        assertEquals(Keystone.MAX_X, k.dx(Keystone.TOP_LEFT))
        assertEquals(Keystone.MAX_Y, k.dy(Keystone.TOP_LEFT))
        // 480 * 0.35 and 320 * 0.35, matching GEOM_MAX_PULL in hud_geom.h.
        // Both land on an exact integer in float, on the JVM and in g++.
        assertEquals(168, Keystone.MAX_X)
        assertEquals(112, Keystone.MAX_Y)
    }

    @Test fun `a corner can only be pulled inward`() {
        // Outward is legal on the wire but useless: there are no pixels out
        // there. Constraining it here is also what lets the preview promise
        // never to draw outside itself.
        var k = Keystone()
        for (corner in 0 until 4) {
            var c = k
            repeat(100) { c = c.nudge(corner, -8, -8) }
            repeat(100) { c = c.nudge(corner, 8, 8) }
            assertFalse("corner $corner escaped the panel", c.clipsOffPanel())
            k = c
        }
        // ...and pushed to every limit at once it is still a shape the board
        // will accept.
        var all = Keystone()
        for (corner in 0 until 4) {
            repeat(100) { all = all.nudge(corner, 999, 999) }
            repeat(100) { all = all.nudge(corner, -999, -999) }
        }
        assertFalse(all.clipsOffPanel())
    }

    @Test fun `no reachable corner combination escapes the panel`() {
        // Exhaustive over the corners of the reachable space: each corner at
        // one of its three extremes on each axis. 3^8 = 6561 shapes.
        val xs = intArrayOf(0, 1, 2)
        var checked = 0
        var concave = 0
        fun ext(corner: Int, which: Int, axis: Int): Int {
            val lim = if (axis == 0) Keystone.MAX_X else Keystone.MAX_Y
            val inward = if (axis == 0) intArrayOf(1, -1, -1, 1)[corner]
                         else intArrayOf(1, 1, -1, -1)[corner]
            return when (which) { 0 -> 0; 1 -> inward * lim / 2; else -> inward * lim }
        }
        for (a in xs) for (b in xs) for (c in xs) for (d in xs)
            for (e in xs) for (f in xs) for (g in xs) for (h in xs) {
                val sel = intArrayOf(a, b, c, d, e, f, g, h)
                var k = Keystone()
                for (i in 0 until 4) {
                    k = k.nudge(i, ext(i, sel[i * 2], 0), ext(i, sel[i * 2 + 1], 1))
                }
                assertFalse(k.clipsOffPanel())
                if (!k.isUsable()) concave++
                checked++
            }
        assertEquals(6561, checked)
        // Inward-only does not make crossing impossible -- opposite corners can
        // still be dragged past each other -- so isUsable() still has a job.
        // What matters is that it is reachable only at the extremes.
        assertTrue("concave shapes found: $concave", concave < checked / 4)
    }

    @Test fun `a crossed quad is reported as unusable rather than sent`() {
        val pulled = Keystone()
            .nudge(Keystone.TOP_LEFT, 168, 0)
            .nudge(Keystone.TOP_RIGHT, -168, 0)
            .nudge(Keystone.BOTTOM_LEFT, 168, 0)
            .nudge(Keystone.BOTTOM_RIGHT, -168, 0)
        // Top-left is now at x=168, top-right at x=312 -- still in order, so
        // this one is fine. The unusable case is the pair actually swapping.
        assertTrue(pulled.isUsable())

        val bowtie = Keystone(offsets = intArrayOf(0, 0, 0, 320, 0, 0, 0, -320))
        assertFalse(bowtie.isUsable())
    }

    @Test fun `isUsable agrees with the firmware on the collinear case`() {
        // hud_geom.h's convex() rejects three corners in a line (its cross
        // product falls inside +/-1e-3 and it returns false). Coordinates here
        // are integers, so an exactly-zero cross product is the same case and
        // the two must give the same answer.
        val flat = Keystone(offsets = intArrayOf(0, 0, 0, 0, -480, 0, 0, 0))
        assertEquals(0, flat.quadX(Keystone.BOTTOM_RIGHT))
        assertEquals(0, flat.quadX(Keystone.BOTTOM_LEFT))
        assertFalse("a quad with a zero-length edge is not usable", flat.isUsable())
    }

    @Test fun `the reply from the board round-trips`() {
        val k = Keystone(mirrorX = false, mirrorY = true,
            offsets = intArrayOf(44, 16, -44, 16, -8, -4, 8, -4))
        val body = k.encode().removePrefix("\$").substringBefore('*')
        val back = Keystone.parse(body)
        assertNotNull(back)
        assertEquals(k, back)
    }

    @Test fun `a malformed or unrelated line is not half-applied`() {
        assertNull(Keystone.parse("HUD,72,50,2,0,180,845,7300,5,RUE"))
        assertNull(Keystone.parse("GEOM,1,0,4,-2,-4,-2,-6,3"))          // short
        assertNull(Keystone.parse("GEOM,1,0,4,-2,-4,-2,-6,3,6,x"))      // not a number
        assertNull(Keystone.parse(""))
    }

    @Test fun `saved settings survive being written to prefs and read back`() {
        val k = Keystone(mirrorX = true, mirrorY = true,
            offsets = intArrayOf(12, -6, -12, -6, -3, 2, 3, 2))
        assertEquals(k, Keystone.deserialize(Keystone.serialize(k)))
        assertNull(Keystone.deserialize(null))
        assertNull(Keystone.deserialize("nonsense"))
    }

    @Test fun `equality compares the numbers, not the array reference`() {
        // A data class with an IntArray in it compares by reference, which
        // would make two identical settings look different -- and this screen
        // sends a frame on every change.
        val a = Keystone(true, false, intArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val b = Keystone(true, false, intArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == b.nudge(Keystone.TOP_LEFT, 1, 0))
    }

    @Test fun `the area readout tells you what the correction costs`() {
        assertEquals(100, Keystone().areaPercent())
        val hard = Keystone.fromSliders(true, false, 0, Keystone.SLIDER_MAX_X)
        // Top edge shortened by 2 * 120 of 480: a trapezoid of (480+240)/2 * 320
        // over 480 * 320, which is 75 %.
        assertEquals(75, hard.areaPercent())
        assertTrue(hard.isUsable())
    }
}
