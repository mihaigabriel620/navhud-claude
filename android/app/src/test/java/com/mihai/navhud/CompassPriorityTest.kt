package com.mihai.navhud

import com.mihai.navhud.map.HeadingFusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Which compass wins when there are two, and who is allowed to say what time it
 * is.
 *
 * The board's magnetometer and the phone's both arrived at HeadingFusion
 * through the same door, with nothing to tell them apart, so whichever
 * reported more often won — and the phone reports far more often.
 *
 * The symptom was exact and small: with the HUD plugged in and driving the
 * heading, picking up the phone and turning it moved the arrow *slightly*.
 * Slightly, because the compass path is a 5 %-per-sample trim rather than an
 * integration. That smallness is what made it look like a mystery instead of
 * an obvious second source, so these tests assert on movement of a fraction of
 * a degree rather than on anything dramatic.
 *
 * The board is bolted to the car: constant hard iron, calibrated out, and it
 * does not care which way the driver is holding anything. On a head unit,
 * which has no sensors at all, the phone's compass is at best a vendor stub.
 *
 * The clock is the other half. A `$MAG` row carries its own timestamp and now
 * sets the fusion's clock from it, so the tests that feed the board never call
 * setClock — that omission *is* the assertion, and see the first one for why.
 */
class CompassPriorityTest {

    /**
     * A fusion whose clock the caller has set.
     *
     * Only the phone's path needs this. `onCompass` stamps its reading with the
     * clock field, and a yaw rate carries no time of its own, so a test where
     * the phone or a bare IMU reports first has to say when "now" is.
     */
    private fun fusion(t0: Long = 1_000L) = HeadingFusion().apply { setClock(t0) }

    @Test
    fun `a board row sets the clock, so a unit with no fix still has an arrow`() {
        // Nothing here calls setClock, and that is the whole test.
        //
        // setClock used to be reachable only from the GPS-fix handler and the
        // phone's own sensor callback. On a head unit with no sensors and no
        // fix neither ever fires, so the clock stayed at 0 for the life of the
        // process: onCompass stamped `lastCompassMs = 0`, usingCompass reads a
        // zero there as "the compass has never reported", and the one absolute
        // source in the system failed its own freshness test on arrival. The
        // arrow froze, while the phone was correctly refusing to use sensors it
        // does not have.
        val f = HeadingFusion()
        f.onExternalCompass(90.0, 1_000L)
        assertNotNull("the board's own row must be enough", f.heading)
        assertEquals(90.0, f.heading!!, 1e-9)
        assertTrue(f.usingCompass)
        assertTrue(f.hudCompassLive)
    }

    @Test
    fun `and it keeps following, with nothing else in the car ever reporting`() {
        val f = HeadingFusion()
        var t = 10_000L
        // Five seconds of $MAG at the board's 5 Hz, pointing north.
        repeat(25) { f.onExternalCompass(0.0, t); t += 200L }
        assertEquals(0.0, f.heading!!, 1e-9)
        // Turn the car right through ninety degrees, five degrees a row.
        for (i in 1..18) { f.onExternalCompass(i * 5.0, t); t += 200L }
        assertEquals("the arrow must not freeze without a fix", 90.0, f.heading!!, 1e-9)
        assertTrue(f.usingCompass)
    }

    @Test
    fun `the board's compass still hands over to GPS once the car is moving`() {
        // Bolted down and calibrated makes it trustworthy, not exempt. Above
        // the crossover GPS measures the direction of travel from orbit, and
        // there is nothing a magnetometer inside the car can add to that.
        val f = HeadingFusion()
        f.onExternalCompass(90.0, 1_000L)
        assertTrue(f.usingCompass)
        f.onFix(90.0, 2.0, 1.0)                  // 7.2 km/h
        assertFalse("the board does not get its own crossover", f.usingCompass)
    }

    @Test
    fun `the phone cannot move the arrow while the board's compass is reporting`() {
        val f = HeadingFusion()
        f.onExternalCompass(90.0, 1_000L)
        assertEquals(90.0, f.heading!!, 1e-9)

        // Now turn the phone through a quarter circle, a hundred samples of it.
        repeat(100) { f.onCompass(180.0) }
        assertEquals("the phone moved the arrow", 90.0, f.heading!!, 1e-9)
        assertTrue(f.hudCompassLive)
    }

    @Test
    fun `not even slightly`() {
        // The original bug was worth about a degree per second of phone
        // waving, which is small enough to argue about. Pin it at a hundredth.
        val f = HeadingFusion()
        f.onExternalCompass(0.0, 1_000L)
        f.onCompass(45.0)
        assertTrue("moved by ${abs(f.heading!!)} deg", abs(f.heading!!) < 0.01)
    }

    @Test
    fun `the board still steers it`() {
        val f = HeadingFusion()
        f.onExternalCompass(90.0, 1_000L)
        f.onExternalCompass(100.0, 1_200L)
        assertEquals(100.0, f.heading!!, 1e-9)
    }

    @Test
    fun `the phone takes over when the cable comes out`() {
        val f = HeadingFusion()
        f.onExternalCompass(90.0, 1_000L)
        // Ten missed sends and the board is gone. With no rows arriving there
        // is nothing to advance the clock any more, so the phone's callback has
        // to say what time it is -- which on a phone it can, having sensors.
        f.setClock(1_000L + HeadingFusion.HUD_COMPASS_STALE_MS)
        assertFalse(f.hudCompassLive)
        repeat(50) { f.onCompass(180.0) }
        assertTrue("the phone never got a turn", abs(f.heading!! - 180.0) < 5.0)
    }

    @Test
    fun `and hands it straight back when it is plugged in again`() {
        val f = fusion(9_000L)
        repeat(50) { f.onCompass(180.0) }        // phone alone, no board yet
        assertFalse(f.hudCompassLive)
        f.onExternalCompass(270.0, 9_000L)
        assertEquals(270.0, f.heading!!, 1e-9)
        repeat(50) { f.onCompass(180.0) }
        assertEquals("the phone got back in", 270.0, f.heading!!, 1e-9)
    }

    @Test
    fun `a board with a gyro but no magnetometer does not lock the phone out`() {
        // HUD_MAG is optional: an MPU-6050 board reports a yaw rate and never a
        // heading. Blocking the phone's compass on account of the *rate* is the
        // exact mistake that was made once already — it left the only absolute
        // source in the system unable to set anything, and the arrow never
        // appeared at all.
        //
        // A rate carries no timestamp either, which is why this one still
        // primes the clock: there is no $MAG row here to do it.
        val f = fusion()
        f.onExternalYawRate(10.0, 0.1)           // rate only: no $MAG ever
        assertFalse("a yaw rate is not a compass", f.hudCompassLive)
        repeat(50) { f.onCompass(120.0) }
        assertTrue("the phone's compass was wrongly locked out", f.heading != null)
    }

    @Test
    fun `reset forgets the board`() {
        val f = HeadingFusion()
        f.onExternalCompass(90.0, 1_000L)
        assertTrue(f.hudCompassLive)
        f.reset()
        assertFalse(f.hudCompassLive)
    }
}
