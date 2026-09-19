package com.mihai.navhud

import com.mihai.navhud.location.Fix
import com.mihai.navhud.location.FixFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filter that stops the marker hopping onto a different road.
 *
 * The reported symptom was "sometimes it loses the GPS and the arrow goes on a
 * completely different road", which is the signature of a cell-tower fix being
 * taken while GPS is perfectly healthy. Each test below is one of the ways that
 * happens.
 */
class FixFilterTest {

    private fun gps(lat: Double, lon: Double, t: Long, acc: Float = 5f, v: Float = 15f) =
        Fix(lat, lon, acc, v, 90f, t, "gps")

    /** 150 m is a *good* cell-tower fix — well inside the absurdity gate, so
     *  these tests exercise the GPS-beats-network rule rather than that one. */
    private fun net(lat: Double, lon: Double, t: Long, acc: Float = 150f) =
        Fix(lat, lon, acc, 0f, null, t, "network")

    @Test fun `a network fix cannot displace a live GPS one`() {
        val f = FixFilter()
        assertTrue(f.accept(gps(50.8000, 4.3500, 1000)))
        // Cell towers put us 600 m away, a second later. This is the bug.
        assertFalse(f.accept(net(50.8055, 4.3500, 2000)))
        assertEquals(50.8000, f.last!!.lat, 1e-9)
        assertTrue(f.lastRejection!!.contains("network"))
    }

    @Test fun `a network fix is allowed once GPS has actually gone away`() {
        val f = FixFilter()
        assertTrue(f.accept(gps(50.8000, 4.3500, 1000)))
        // Eleven seconds of silence: out of the tunnel, GPS has not come back.
        // The fix is 20 m away, which 11 s of driving easily explains.
        assertTrue(f.accept(net(50.80018, 4.3500, 12_000, acc = 40f)))
    }

    @Test fun `an absurd accuracy is refused even with nothing to compare against`() {
        val f = FixFilter()
        assertFalse(f.accept(Fix(50.8, 4.35, 900f, 0f, null, 1000, "network")))
        assertTrue(f.lastRejection!!.contains("accuracy"))
    }

    @Test fun `a vague fix does not replace a sharp recent one`() {
        val f = FixFilter()
        assertTrue(f.accept(gps(50.8000, 4.3500, 1000, acc = 4f)))
        assertFalse(f.accept(gps(50.8003, 4.3500, 2000, acc = 120f)))
        assertEquals(4f, f.last!!.accuracyM, 1e-6f)
    }

    @Test fun `the wild outlier a city reflection produces is thrown away`() {
        val f = FixFilter()
        assertTrue(f.accept(gps(50.8000, 4.3500, 1000)))
        // 400 m in one second is 1440 km/h.
        assertFalse(f.accept(gps(50.8036, 4.3500, 2000)))
        assertTrue(f.lastRejection!!.contains("jumped"))
        // ...but the honest next fix is still accepted.
        assertTrue(f.accept(gps(50.80013, 4.3500, 2000)))
    }

    @Test fun `a plausible motorway step is not mistaken for a teleport`() {
        val f = FixFilter()
        var t = 1000L
        var lat = 50.8000
        assertTrue(f.accept(gps(lat, 4.3500, t, v = 36f)))
        // 36 m/s = 130 km/h, one fix a second, for ten seconds.
        repeat(10) {
            t += 1000
            lat += 36.0 / 111_320.0
            assertTrue("rejected a legitimate 130 km/h step: ${f.lastRejection}",
                f.accept(gps(lat, 4.3500, t, v = 36f)))
        }
    }

    @Test fun `out of order fixes are dropped rather than reversing time`() {
        val f = FixFilter()
        assertTrue(f.accept(gps(50.8000, 4.3500, 5000)))
        assertFalse(f.accept(gps(50.8001, 4.3500, 4000)))
    }

    @Test fun `staleness is reported off the monotonic clock`() {
        val f = FixFilter()
        assertTrue("nothing yet is stale", f.isStale(0))
        f.accept(gps(50.8, 4.35, 10_000))
        assertFalse(f.isStale(12_000))
        assertTrue(f.isStale(30_000))
    }

    @Test fun `one bad accepted fix cannot wedge the filter forever`() {
        val f = FixFilter()
        // A mock-location app writes wall time into the monotonic field. Every
        // real fix afterwards then looks like it arrived in the past, and the
        // out-of-order rule would refuse all of them for the rest of the drive.
        assertTrue(f.accept(gps(50.8000, 4.3500, 1_700_000_000_000L)))
        var t = 10_000L
        var accepted = 0
        repeat(FixFilter.MAX_CONSECUTIVE_REJECTS + 4) {
            if (f.accept(gps(50.8000, 4.3500, t))) accepted++
            t += 1000
        }
        assertTrue("filter never recovered", accepted > 0)
        // ...and once it has, it is tracking the real fixes again.
        assertTrue(f.accept(gps(50.80013, 4.3500, t)))
    }

    @Test fun `a normal drive never trips the escape hatch`() {
        val f = FixFilter()
        var t = 1000L
        var lat = 50.8000
        var last = lat
        repeat(300) {
            assertTrue("refused a clean 50 km/h fix: ${f.lastRejection}",
                f.accept(gps(lat, 4.3500, t, v = 14f)))
            last = lat
            lat += 14.0 / 111_320.0
            t += 1000
        }
        assertEquals(last, f.last!!.lat, 1e-9)
        assertNull("nothing should have been refused", f.lastRejection)
    }

    @Test fun `a demo fix counts as GPS so demo mode behaves identically`() {
        val f = FixFilter()
        assertTrue(f.accept(Fix(50.8, 4.35, 5f, 10f, 90f, 1000, "demo")))
        assertFalse("network still cannot displace it", f.accept(net(50.81, 4.35, 2000)))
    }
}
