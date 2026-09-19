package com.mihai.navhud

import com.mihai.navhud.map.ParkedHeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Remembering which way the car was left pointing.
 *
 * The scenario throughout is a real one: Mihai drives up a street heading east,
 * slows, and reverses into a space on his left. He ends up facing north. An
 * hour later he comes back and starts a route. The question every test here
 * asks is whether the app says "north" or something stupid.
 */
class ParkedHeadingTest {

    /** Somewhere in Drogenbos, the same patch the other tests use. */
    private val lat = 50.7726
    private val lon = 4.3106

    private fun near(a: Double, b: Double, tol: Double = 1.0) =
        assertTrue("expected ~$b, got $a", abs(Geo.signedDelta(a, b)) <= tol)

    // ---- the manoeuvre ------------------------------------------------------

    /**
     * The whole point of the file: the gyro has to carry the heading through
     * the part of the parking manoeuvre GPS cannot see.
     *
     * Without it the stored answer is 90 (the bearing while still rolling up
     * the road), which is 90 degrees wrong and stated with total confidence.
     */
    @Test
    fun `gyro carries the heading through the parking manoeuvre`() {
        val p = ParkedHeading()
        var t = 1_000L

        // Rolling east up the street at 20 km/h. GPS course is truth here.
        p.onFix(lat, lon, bearingDeg = 90.0, speedMps = 5.6)
        near(p.heading!!, 90.0)

        // Slow to a crawl. GPS still reports a bearing but it is below the
        // valid speed, so it must be ignored -- and it is deliberately garbage
        // here, because that is what a real receiver hands you at 1 km/h.
        p.onFix(lat, lon, bearingDeg = 213.0, speedMps = 0.3)
        near(p.heading!!, 90.0)

        // Reverse in, swinging 90 degrees left over 12 seconds. -7.5 deg/s.
        p.onYawRate(0.0, t)                       // first sample sets the clock
        repeat(240) {                              // 12 s at 20 Hz
            t += 50
            p.onYawRate(-7.5, t)
        }

        // Ends facing north.
        near(p.heading!!, 0.0, tol = 2.0)
    }

    @Test
    fun `naive last-GPS-bearing would have been ninety degrees wrong`() {
        // Documents the bug this class exists to avoid, so nobody "simplifies"
        // the gyro path away later.
        val p = ParkedHeading()
        p.onFix(lat, lon, bearingDeg = 90.0, speedMps = 5.6)
        val naive = p.heading!!
        var t = 1_000L
        p.onYawRate(0.0, t)
        repeat(240) { t += 50; p.onYawRate(-7.5, t) }
        assertTrue(abs(Geo.signedDelta(naive, p.heading!!)) > 85.0)
    }

    // ---- coming back --------------------------------------------------------

    @Test
    fun `restores when the car is where it was left`() {
        val p = ParkedHeading()
        val rec = ParkedHeading.Record(lat, lon, 0.0, savedAtMs = 1_000L)
        // Next morning, first fix 4 m away -- ordinary cold-fix scatter.
        val there = Geo.destination(lat, lon, 45.0, 4.0)
        assertTrue(p.restore(rec, there[0], there[1], wallMs = 9L * 3600 * 1000))
        near(p.heading!!, 0.0)
        assertTrue(p.restored)
    }

    @Test
    fun `refuses when the car has been moved`() {
        val p = ParkedHeading()
        val rec = ParkedHeading.Record(lat, lon, 0.0, savedAtMs = 1_000L)
        // 40 m away: a different space, or a different street.
        val moved = Geo.destination(lat, lon, 45.0, 40.0)
        assertFalse(p.restore(rec, moved[0], moved[1], wallMs = 9L * 3600 * 1000))
        assertNull(p.heading)
    }

    @Test
    fun `refuses a record older than the backstop`() {
        val p = ParkedHeading()
        val rec = ParkedHeading.Record(lat, lon, 0.0, savedAtMs = 0L)
        val now = ParkedHeading.MAX_AGE_MS + 1
        assertFalse(p.restore(rec, lat, lon, now))
    }

    @Test
    fun `refuses when there is nothing stored`() {
        assertFalse(ParkedHeading().restore(null, lat, lon, 1_000L))
    }

    @Test
    fun `does not overwrite a heading this drive already has`() {
        val p = ParkedHeading()
        p.onFix(lat, lon, bearingDeg = 270.0, speedMps = 10.0)
        val rec = ParkedHeading.Record(lat, lon, 0.0, savedAtMs = 1_000L)
        assertFalse(p.restore(rec, lat, lon, 2_000L))
        near(p.heading!!, 270.0)
    }

    /**
     * Head units routinely boot with a wrong clock and correct it from the
     * network a minute later. A record must not be discarded for looking like
     * it came from the future by a few minutes.
     */
    @Test
    fun `survives a clock that jumped backwards`() {
        val p = ParkedHeading()
        val rec = ParkedHeading.Record(lat, lon, 33.0, savedAtMs = 1_700_000_000_000L)
        // Boot clock is an hour behind the saved stamp.
        assertTrue(p.restore(rec, lat, lon, wallMs = 1_700_000_000_000L - 3_600_000L))
        near(p.heading!!, 33.0)
    }

    // ---- integration hygiene -------------------------------------------------

    @Test
    fun `ignores a yaw rate arriving after the board went quiet`() {
        val p = ParkedHeading()
        p.onFix(lat, lon, 90.0, 10.0)
        p.onYawRate(0.0, 1_000L)
        // Cable pulled; next sample is 5 s later. Integrating 30 deg/s across
        // that gap would swing the heading 150 degrees on one sample.
        p.onYawRate(30.0, 6_000L)
        near(p.heading!!, 90.0)
    }

    @Test
    fun `does not integrate before a heading exists`() {
        val p = ParkedHeading()
        p.onYawRate(0.0, 1_000L)
        p.onYawRate(30.0, 1_050L)
        assertNull(p.heading)
    }

    @Test
    fun `wraps across the north seam`() {
        val p = ParkedHeading()
        p.onFix(lat, lon, bearingDeg = 350.0, speedMps = 10.0)
        var t = 1_000L
        p.onYawRate(0.0, t)
        repeat(40) { t += 50; p.onYawRate(10.0, t) }   // +20 deg over 2 s
        near(p.heading!!, 10.0, tol = 2.0)
        assertTrue(p.heading!! >= 0.0 && p.heading!! < 360.0)
    }

    // ---- writing ------------------------------------------------------------

    @Test
    fun `throttles writes but lets a real turn through`() {
        val p = ParkedHeading()
        p.onFix(lat, lon, bearingDeg = 90.0, speedMps = 10.0)
        assertNotNull(p.recordToSave(1_000L))          // first one always writes
        assertNull(p.recordToSave(1_100L))             // too soon, nothing turned

        // Turn 30 degrees: that has to be captured even inside the interval,
        // because the ignition may be about to take the power away.
        var t = 1_100L
        p.onYawRate(0.0, t)
        repeat(60) { t += 50; p.onYawRate(10.0, t) }   // +30 deg over 3 s
        assertNotNull(p.recordToSave(t))

        // And the plain interval still works when nothing is turning.
        assertNull(p.recordToSave(t + 100))
        assertNotNull(p.recordToSave(t + ParkedHeading.SAVE_INTERVAL_MS))
    }

    @Test
    fun `writes nothing before there is a position and a heading`() {
        val p = ParkedHeading()
        assertNull(p.recordToSave(1_000L))
        p.onFix(lat, lon, bearingDeg = null, speedMps = 0.0)
        assertNull(p.recordToSave(2_000L))             // position but no heading
    }

    @Test
    fun `saveNow defeats the throttle`() {
        val p = ParkedHeading()
        p.onFix(lat, lon, bearingDeg = 90.0, speedMps = 10.0)
        assertNotNull(p.recordToSave(1_000L))
        assertNull(p.recordToSave(1_100L))
        p.saveNow()
        assertNotNull(p.recordToSave(1_100L))
    }

    @Test
    fun `the saved record carries the position of the last fix`() {
        val p = ParkedHeading()
        p.onFix(lat, lon, bearingDeg = 90.0, speedMps = 10.0)
        val moved = Geo.destination(lat, lon, 90.0, 25.0)
        p.onFix(moved[0], moved[1], bearingDeg = 90.0, speedMps = 10.0)
        val rec = p.recordToSave(1_000L)!!
        assertEquals(moved[0], rec.lat, 1e-9)
        assertEquals(moved[1], rec.lon, 1e-9)
    }

    // ---- the round trip ------------------------------------------------------

    /**
     * End to end: drive in, park, shut down, come back, and check the app knows
     * it is facing north rather than east.
     */
    @Test
    fun `full park and return`() {
        val evening = ParkedHeading()
        var t = 1_000L
        evening.onFix(lat, lon, bearingDeg = 90.0, speedMps = 5.6)
        evening.onFix(lat, lon, bearingDeg = 213.0, speedMps = 0.3)
        evening.onYawRate(0.0, t)
        repeat(240) { t += 50; evening.onYawRate(-7.5, t) }
        evening.saveNow()
        val stored = evening.recordToSave(t)!!
        near(stored.headingDeg, 0.0, tol = 2.0)

        val morning = ParkedHeading()
        val firstFix = Geo.destination(lat, lon, 200.0, 6.0)   // 6 m of fix scatter
        assertTrue(morning.restore(stored, firstFix[0], firstFix[1],
                                   wallMs = stored.savedAtMs + 11L * 3600 * 1000))
        near(morning.heading!!, 0.0, tol = 2.0)
        assertTrue(morning.restored)

        // And the moment the car actually moves, live GPS takes over again.
        morning.onFix(firstFix[0], firstFix[1], bearingDeg = 182.0, speedMps = 6.0)
        near(morning.heading!!, 182.0)
        assertFalse(morning.restored)
    }
}
