package com.mihai.navhud

import com.mihai.navhud.location.CarLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car's own speed, arriving from the HUD board.
 *
 * The line is exactly what NavHud.ino's sendCar() emits:
 *   $CAR,<kmh>,<rpm>,<ps>,<peakPs>,<volts>,<ignition>*CS
 */
class CarLinkTest {

    private fun line(kph: Int, rpm: Int = 1500, ps: Int = 90,
                     peak: Int = 120, v: String = "14.2", ign: Int = 1) =
        "\$CAR,$kph,$rpm,$ps,$peak,$v,$ign"

    @Test
    fun `parses what the board sends`() {
        val c = CarLink()
        assertTrue(c.feed(line(97, rpm = 2400, ps = 103), 1_000L))
        assertEquals(97, c.speedKph)
        assertEquals(2400, c.rpm)
        assertEquals(103, c.ps)
        assertEquals(14.2, c.volts, 1e-9)
        assertTrue(c.ignitionOn)
        assertEquals(97 / 3.6, c.speedMps(1_000L)!!, 1e-9)
    }

    @Test
    fun `ignores lines that are not ours`() {
        val c = CarLink()
        assertFalse(c.feed("\$IMU,0,0,0,3.5", 1_000L))
        assertFalse(c.feed("\$HELLO,1", 1_000L))
        assertFalse(c.feed("", 1_000L))
        assertNull(c.speedMps(1_000L))
    }

    @Test
    fun `rejects a malformed or impossible speed rather than clamping it`() {
        val c = CarLink()
        c.feed(line(50), 1_000L)
        // A clamped 400 would still read as "moving fast", and everything
        // downstream is about to trust this more than it trusts GPS.
        assertFalse(c.feed(line(CarLink.MAX_PLAUSIBLE_KPH), 2_000L))
        assertFalse(c.feed(line(900), 2_000L))
        assertFalse(c.feed(line(-5), 2_000L))
        assertFalse(c.feed("\$CAR,80,1500", 2_000L))          // truncated
        assertEquals(50, c.speedKph)                           // unchanged
    }

    /**
     * Firmware 2.8 sends -1 once the bus's speed frame (0x1A6) is stale; 2.7
     * repeated the last speed for as long as the bus stayed up without it.
     * The -1 line is refused, so the last good one ages out and GPS takes over.
     */
    @Test
    fun `the board's -1 for a stale speed is refused and ages out to GPS`() {
        val c = CarLink()
        assertTrue(c.feed(line(87), 1_000L))
        assertFalse(c.feed(line(-1), 1_500L))
        assertEquals(87, c.speedKph)
        assertTrue(c.fresh(2_000L))
        assertFalse(c.fresh(1_000L + CarLink.STALE_MS))
    }

    @Test
    fun `reads the optional raw battery count when the board sends it`() {
        val c = CarLink()
        // Seventh field, added so the volts-per-count scale can be checked
        // against a meter. The documented 0xF3DB sample is 987 counts.
        assertTrue(c.feed(line(50) + ",987", 1_000L))
        assertEquals(987, c.voltsRaw)
        assertEquals(50, c.speedKph)
    }

    @Test
    fun `an older board that sends only six fields still parses`() {
        val c = CarLink()
        assertTrue(c.feed(line(50), 1_000L))
        assertEquals(0, c.voltsRaw)
        assertEquals(50, c.speedKph)
    }

    @Test
    fun `tolerates a checksum suffix`() {
        val c = CarLink()
        assertTrue(c.feed(line(60) + "*3F", 1_000L))
        assertEquals(60, c.speedKph)
    }

    // ---- staleness ----------------------------------------------------------

    @Test
    fun `goes stale when the board stops talking`() {
        val c = CarLink()
        c.feed(line(70), 1_000L)
        assertTrue(c.fresh(1_500L))
        assertNotNull(c.speedMps(1_500L))
        assertFalse(c.fresh(1_000L + CarLink.STALE_MS))
        // Null, not zero. A caller that cannot tell "no data" from "stopped"
        // freezes the map because a cable fell out.
        assertNull(c.speedMps(1_000L + CarLink.STALE_MS))
    }

    @Test
    fun `is not stopped when it is merely silent`() {
        val c = CarLink()
        c.feed(line(0), 1_000L)
        assertTrue(c.stopped(1_000L + CarLink.STOPPED_SETTLE_MS))
        // Same state, but now the data is old.
        assertFalse(c.stopped(1_000L + CarLink.STALE_MS))
    }

    // ---- standstill ---------------------------------------------------------

    @Test
    fun `needs to have been still for a while before it says stopped`() {
        val c = CarLink()
        c.feed(line(0), 1_000L)
        assertFalse(c.stopped(1_100L))                       // just arrived
        assertTrue(c.stopped(1_000L + CarLink.STOPPED_SETTLE_MS))
    }

    @Test
    fun `one km per hour of decode jitter does not un-stop the car`() {
        val c = CarLink()
        c.feed(line(0), 1_000L)
        c.feed(line(1), 1_500L)                              // 0x1A6 counter jitter
        assertTrue(c.stopped(1_000L + CarLink.STOPPED_SETTLE_MS))
    }

    @Test
    fun `pulling away clears the stop immediately`() {
        val c = CarLink()
        c.feed(line(0), 1_000L)
        assertTrue(c.stopped(2_800L))          // still fresh, settled long ago
        c.feed(line(8), 2_900L)
        assertFalse(c.stopped(3_000L))
        assertFalse(c.stopped(4_000L))                       // and stays cleared
    }

    // ---- the wheel-speed scale factor ---------------------------------------

    @Test
    fun `learns that the cluster reads optimistic`() {
        val c = CarLink()
        // Bus says 100, GPS says 96: the classic BMW over-read.
        repeat(400) { i ->
            c.feed(line(100), 1_000L + i * 250L)
            c.learnScale(96 / 3.6, 1_000L + i * 250L)
        }
        assertEquals(0.96, c.scale, 0.005)
        assertEquals(96 / 3.6, c.speedMps(1_000L + 399 * 250L)!!, 0.05)
    }

    @Test
    fun `will not learn from a crawl`() {
        val c = CarLink()
        c.feed(line(10), 1_000L)
        c.learnScale(1.0, 1_000L)          // GPS speed is noise down here
        assertEquals(1.0, c.scale, 1e-9)
    }

    @Test
    fun `refuses a correction that means one of the two is broken`() {
        val c = CarLink()
        repeat(50) { c.feed(line(100), 1_000L); c.learnScale(50 / 3.6, 1_000L) }
        assertEquals(1.0, c.scale, 1e-9)   // half speed is not a tyre size
    }

    @Test
    fun `will not learn while the board is silent`() {
        val c = CarLink()
        c.feed(line(100), 1_000L)
        c.learnScale(96 / 3.6, 1_000L + CarLink.STALE_MS)
        assertEquals(1.0, c.scale, 1e-9)
    }

    @Test
    fun `the scale survives a reset because the tyres do`() {
        val c = CarLink()
        repeat(400) { i ->
            c.feed(line(100), 1_000L + i * 250L)
            c.learnScale(96 / 3.6, 1_000L + i * 250L)
        }
        val learned = c.scale
        c.reset()
        assertEquals(learned, c.scale, 1e-9)
        assertEquals(0, c.speedKph)
        assertNull(c.speedMps(1_000L))
    }

    // ---- the speed that is shown -------------------------------------------

    @Test
    fun `the displayed speed is the raw bus number, not the corrected one`() {
        val c = CarLink()
        repeat(400) { i ->
            c.feed(line(100), 1_000L + i * 250L)
            c.learnScale(96 / 3.6, 1_000L + i * 250L)
        }
        val now = 1_000L + 399 * 250L
        // The HUD draws 100; the app must too, whatever the tyres have taught us.
        assertEquals(100 / 3.6, c.rawSpeedMps(now)!!, 1e-9)
        assertEquals(96 / 3.6, c.speedMps(now)!!, 0.05)
        assertNull(c.rawSpeedMps(now + CarLink.STALE_MS))
    }

    @Test
    fun `a zero on the bus while GPS says 50 is a stale frame, not a stop`() {
        val c = CarLink()
        var t = 1_000L
        c.feed(line(0), t)
        // Below the 3 s bar the zero still stands.
        while (t < 1_000L + CarLink.ZERO_SUSPECT_MS - 250) {
            c.feed(line(0), t); c.checkPlausible(50 / 3.6, t); t += 250
        }
        assertEquals(0.0, c.rawSpeedMps(t)!!, 1e-9)
        c.feed(line(0), t); c.checkPlausible(50 / 3.6, t)
        t += 250
        c.feed(line(0), t); c.checkPlausible(50 / 3.6, t)
        assertTrue(c.zeroSuspect)
        assertNull("fall back to GPS", c.rawSpeedMps(t))
        assertNull(c.speedMps(t))
        assertFalse("and it is not a stopped car", c.stopped(t))

        // Latched while it keeps reading zero, even once GPS slows...
        t += 250
        c.feed(line(0), t); c.checkPlausible(1.0, t)
        assertNull(c.rawSpeedMps(t))
        // ...until the bus reports motion again.
        t += 250
        c.feed(line(40), t); c.checkPlausible(11.0, t)
        assertFalse(c.zeroSuspect)
        assertEquals(40 / 3.6, c.rawSpeedMps(t)!!, 1e-9)
    }

    @Test
    fun `a real stop is not suspected`() {
        val c = CarLink()
        var t = 1_000L
        repeat(40) {
            c.feed(line(0), t); c.checkPlausible(0.8, t); t += 250   // GPS wander
        }
        assertFalse(c.zeroSuspect)
        assertEquals(0.0, c.rawSpeedMps(t - 250)!!, 1e-9)
        // No fix at all: nothing to judge by, so nothing changes.
        c.feed(line(0), t); c.checkPlausible(null, t)
        assertFalse(c.zeroSuspect)
    }

    @Test
    fun `a GPS dip below 15 restarts the three seconds`() {
        val c = CarLink()
        var t = 1_000L
        repeat(10) { c.feed(line(0), t); c.checkPlausible(20.0, t); t += 250 }   // 2.5 s
        c.feed(line(0), t); c.checkPlausible(3.0, t); t += 250
        repeat(10) { c.feed(line(0), t); c.checkPlausible(20.0, t); t += 250 }
        assertFalse(c.zeroSuspect)
    }

    // ---- ignition -----------------------------------------------------------

    @Test
    fun `reports the ignition line`() {
        val c = CarLink()
        c.feed(line(0, ign = 1), 1_000L)
        assertTrue(c.ignitionOn)
        c.feed(line(0, ign = 0), 1_500L)
        assertFalse(c.ignitionOn)
    }
}
