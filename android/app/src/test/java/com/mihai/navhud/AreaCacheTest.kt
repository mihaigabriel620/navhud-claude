package com.mihai.navhud

import com.mihai.navhud.nav.AreaCache
import com.mihai.navhud.nav.AreaRoads
import com.mihai.navhud.nav.ManeuverPoint
import com.mihai.navhud.nav.Route
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The roads survive losing signal.
 *
 * Free drive reads OpenStreetMap directly, so with no Overpass answer there is
 * no speed limit, no road name, no camera and no level crossing -- and the
 * roads with no coverage are exactly the unfamiliar rural ones where all four
 * matter most. This is the disk that keeps them.
 *
 * The filesystem is real here, and so is the clock: an earlier version of this
 * test passed a fake `now` of 1e9 ms while the files it had just written
 * carried real wall-clock modification times around 1.7e12, so every freshness
 * comparison came out hugely negative and every file looked fresh no matter
 * what the test claimed to be proving. Ages are made by pushing a file's
 * modification time backwards from the real now, which is the only way the
 * comparison under test is the one that actually runs on the phone.
 */
class AreaCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Brussels, sitting exactly on a cell centre so the arithmetic is legible. */
    private val LAT = 50.85
    private val LON = 4.30
    private val RADIUS_M = 2000.0

    private val BODY = """{"elements":[{"type":"way","id":1}]}"""

    private val realTransport = AreaRoads.transport
    private val realFairUse = AreaRoads.fairUse

    @Before fun setUp() {
        AreaCache.dir = tmp.newFolder("area")
        AreaRoads.fairUse = gates()
    }

    /** A gate per server that lets every request straight through. */
    private fun gates() = AreaRoads.SERVERS.map { AreaRoads.FairUse(minGapMs = 0L) }

    /** The cache is global state; leaving it set would poison every later test. */
    @After fun tearDown() {
        AreaCache.dir = null
        AreaCache.maxBytes = AreaCache.MAX_BYTES
        AreaRoads.transport = realTransport
        AreaRoads.fairUse = realFairUse
    }

    private fun now() = System.currentTimeMillis()

    /** Push a file's modification time back, and fail loudly if the FS refuses. */
    private fun age(f: File, byMs: Long) {
        assertTrue("could not set mtime on ${f.name}",
            f.setLastModified(System.currentTimeMillis() - byMs))
    }

    private fun onlyFile(): File = AreaCache.dir!!.listFiles()!!.single()

    private fun body(lat: Double, lon: Double, r: Double, maxAge: Long = AreaCache.FRESH_MS) =
        AreaCache.get(lat, lon, r, maxAge, now())?.body

    // ---- the basic round trip ------------------------------------------------

    @Test fun `what went in comes back out`() {
        AreaCache.put(LAT, LON, RADIUS_M, BODY)
        assertEquals(1, AreaCache.fileCount())
        assertEquals(BODY, body(LAT, LON, RADIUS_M))
        assertTrue("the body is on disk, not in memory", AreaCache.sizeBytes() > 0L)
    }

    @Test fun `a window never fetched is simply absent`() {
        assertNull(body(LAT, LON, RADIUS_M))
    }

    @Test fun `with no directory set the cache is a no-op, not a crash`() {
        AreaCache.dir = null
        AreaCache.put(LAT, LON, RADIUS_M, BODY)          // must not throw
        assertNull(body(LAT, LON, RADIUS_M))
        assertNull(AreaCache.nearest(LAT, LON, Long.MAX_VALUE, now()))
        assertEquals(0, AreaCache.fileCount())
        assertEquals(0L, AreaCache.sizeBytes())
    }

    @Test fun `fetching the same window again replaces it`() {
        AreaCache.put(LAT, LON, RADIUS_M, "old")
        AreaCache.put(LAT + 0.001, LON, RADIUS_M, BODY)   // same cell, same bucket
        assertEquals(1, AreaCache.fileCount())
        assertEquals(BODY, body(LAT + 0.001, LON, RADIUS_M))
    }

    // ---- freshness -----------------------------------------------------------

    @Test fun `a week-old window is still fresh, a fortnight-old one is not`() {
        AreaCache.put(LAT, LON, RADIUS_M, BODY)

        age(onlyFile(), AreaCache.FRESH_MS - 60_000L)
        assertNotNull("just inside the window", body(LAT, LON, RADIUS_M))

        age(onlyFile(), AreaCache.FRESH_MS + 60_000L)
        assertNull("just outside it", body(LAT, LON, RADIUS_M))
    }

    /**
     * The offline fallback. AreaRoads asks a second time with no age limit at
     * all after the network has failed, because a month-old speed limit on a
     * road whose limit has not changed since it was signed beats a blank
     * screen -- and the file is still there, it was only judged too old.
     */
    @Test fun `stale beats nothing when the network is gone`() {
        AreaCache.put(LAT, LON, RADIUS_M, BODY)
        age(onlyFile(), 30L * 24 * 3600 * 1000)          // a month

        assertNull(body(LAT, LON, RADIUS_M))
        assertEquals(BODY, body(LAT, LON, RADIUS_M, Long.MAX_VALUE))
    }

    // ---- what counts as covered ------------------------------------------------

    /**
     * The labelling bug. A neighbouring cell's window used to be handed back
     * for any request within 1.5 km, and the caller labelled it with the
     * centre it had asked for -- so a window centred up to 1.5 km away was
     * believed to be centred on the car. Now only a window that contains the
     * whole circle asked for is a hit, and it says where it really is.
     */
    @Test fun `a neighbour that does not cover the request is not a hit`() {
        AreaCache.put(LAT, LON, RADIUS_M, BODY)
        // 422 m east: a 2 km circle there sticks out of the cached one.
        assertNull(body(LAT, 4.306, RADIUS_M))
        // ...but a small request well inside it is covered.
        val h = AreaCache.get(LAT, 4.306, 1200.0, AreaCache.FRESH_MS, now())!!
        assertEquals(BODY, h.body)
        assertEquals("labelled with its own centre", LON, h.lon, 1e-5)
        assertEquals(LAT, h.lat, 1e-5)
        assertEquals(RADIUS_M, h.radiusM, 1.0)
    }

    @Test fun `any radius bucket that covers the request will do`() {
        AreaCache.put(LAT, LON, 3000.0, BODY)
        assertEquals(BODY, body(LAT, 4.31, 1500.0))       // 700 m off, 1.5 km circle
        // A 2 km circle is not an answer to a question about a 6 km one.
        AreaCache.put(50.95, 4.40, RADIUS_M, BODY)
        assertNull(body(50.95, 4.40, 6000.0))
        // And one more than twice the size is not used for a small request:
        // AreaRoads.needsRefetch would ask again straight away.
        AreaCache.put(51.2, 4.4, 6000.0, BODY)
        assertNull(body(51.2, 4.4, 1200.0))
        assertTrue(AreaCache.covers(LAT, 4.31, 1500.0, AreaCache.FRESH_MS, now()))
    }

    @Test fun `offline, any window the point is inside answers, labelled truthfully`() {
        AreaCache.put(LAT, LON, RADIUS_M, "near")
        AreaCache.put(50.859, LON, RADIUS_M, "far")      // ~1 km north
        val h = AreaCache.nearest(LAT, 4.306, Long.MAX_VALUE, now())!!
        assertEquals("the one with the most room around the point", "near", h.body)
        assertEquals(LON, h.lon, 1e-5)
        // 2.1 km away is outside both: nothing, rather than the wrong roads.
        assertNull(AreaCache.nearest(LAT, 4.33, Long.MAX_VALUE, now()))
    }

    @Test fun `an old-style file is still read, and trusted less`() {
        val d = AreaCache.dir!!
        File(d, "a_5085_430_2.json").writeText(BODY)     // cell (50.85, 4.30), 2 km
        // Its true centre could be ~700 m out, so it only answers close in.
        assertNull(body(LAT, LON, 1200.0))
        val h = AreaCache.nearest(LAT, LON, Long.MAX_VALUE, now())!!
        assertEquals(BODY, h.body)
        assertTrue(h.radiusM < RADIUS_M)
    }

    // ---- the caps ------------------------------------------------------------

    /**
     * Left uncapped this grows without bound: every new town adds cells and
     * nothing ever removes them. Oldest first, so what survives is the commute
     * rather than last summer's holiday.
     */
    @Test fun `the oldest files go once the cap is passed`() {
        val d = AreaCache.dir!!
        val overflow = 2
        // Distinct, ordered modification times: sorting by mtime is the whole
        // policy, and files written in the same millisecond would not test it.
        for (i in 1..(AreaCache.MAX_FILES + overflow)) {
            val f = File(d, "a_${i}_0_2.json")
            f.writeText("body $i")
            assertTrue(f.setLastModified(System.currentTimeMillis() - (10_000L - i) * 1000L))
        }
        assertEquals(AreaCache.MAX_FILES + overflow, AreaCache.fileCount())

        // One more write, which is what runs the prune. That leaves
        // MAX_FILES + overflow + 1 files, so overflow + 1 must go.
        AreaCache.put(LAT, LON, RADIUS_M, BODY)

        assertEquals(AreaCache.MAX_FILES, AreaCache.fileCount())
        for (i in 1..(overflow + 1)) {
            assertFalse("file $i was among the oldest and should be gone",
                File(d, "a_${i}_0_2.json").exists())
        }
        assertTrue("the next-oldest survives",
            File(d, "a_${overflow + 2}_0_2.json").exists())
        assertTrue("and so does the newest of the batch",
            File(d, "a_${AreaCache.MAX_FILES + overflow}_0_2.json").exists())
        assertEquals("the write that triggered the prune is still readable", BODY,
            body(LAT, LON, RADIUS_M))
    }

    @Test fun `under the cap nothing is evicted`() {
        val d = AreaCache.dir!!
        for (i in 1 until AreaCache.MAX_FILES) File(d, "a_${i}_0_2.json").writeText("body $i")
        AreaCache.put(LAT, LON, RADIUS_M, BODY)

        assertEquals(AreaCache.MAX_FILES, AreaCache.fileCount())
        assertTrue(File(d, "a_1_0_2.json").exists())
    }

    @Test fun `a long trip cannot fill the disk`() {
        // Three windows bigger than a third of the byte cap each: the oldest goes.
        AreaCache.maxBytes = 250L
        val big = "x".repeat(100)
        AreaCache.put(50.0, 4.0, RADIUS_M, big)
        age(AreaCache.dir!!.listFiles()!!.single(), 3_000L)
        AreaCache.put(51.0, 4.0, RADIUS_M, big)
        AreaCache.put(52.0, 4.0, RADIUS_M, big)
        assertEquals(2, AreaCache.fileCount())
        assertTrue(AreaCache.sizeBytes() <= 250L)
        assertNull("the oldest went", body(50.0, 4.0, RADIUS_M))
    }

    @Test fun `clear empties the lot`() {
        AreaCache.put(LAT, LON, RADIUS_M, BODY)
        AreaCache.put(50.9, 4.4, RADIUS_M, BODY)
        assertEquals(2, AreaCache.fileCount())

        AreaCache.clear()

        assertEquals(0, AreaCache.fileCount())
        assertEquals(0L, AreaCache.sizeBytes())
        assertNull(body(LAT, LON, RADIUS_M))
    }

    // ---- the published numbers ------------------------------------------------

    @Test fun `the grid and the window are the shipped ones`() {
        assertEquals(0.01, AreaCache.CELL_DEG, 0.0)
        assertEquals(7L * 24 * 3600 * 1000, AreaCache.FRESH_MS)
        assertEquals(400, AreaCache.MAX_FILES)
        assertEquals(120L * 1024 * 1024, AreaCache.MAX_BYTES)
    }

    // ---- fetching with no network ----------------------------------------------

    @Test fun `with no network the live lookup falls back to the disk, labelled truthfully`() {
        AreaCache.put(LAT, LON, RADIUS_M, BODY)
        age(onlyFile(), 30L * 24 * 3600 * 1000)          // stale: not a normal hit
        var calls = 0
        AreaRoads.transport = { _, _ -> calls++; throw java.net.UnknownHostException("offline") }
        val a = AreaRoads.fetch(LAT, 4.306, RADIUS_M, 0L)
        assertEquals("both servers asked", AreaRoads.SERVERS.size, calls)
        assertTrue(a.fromCache)
        assertEquals("the window's own centre, not the one asked for", LON, a.centreLon, 1e-5)
    }

    @Test fun `with no network and nothing on disk the failure reaches the caller`() {
        AreaRoads.transport = { _, _ -> throw java.net.SocketTimeoutException("no signal") }
        try {
            AreaRoads.fetch(LAT, LON, RADIUS_M, 0L)
            fail("nothing to fall back on")
        } catch (e: java.io.IOException) {
            // The service catches it and keeps what it has.
        }
    }

    @Test fun `a cached window cut short is dropped and fetched again`() {
        AreaCache.put(LAT, LON, RADIUS_M, """{"elements":[{"type":"way","""")
        assertEquals("written whole, nothing left aside", 1, AreaCache.dir!!.listFiles()!!.size)
        var calls = 0
        AreaRoads.transport = { _, _ -> calls++; BODY }
        val a = AreaRoads.fetch(LAT, LON, RADIUS_M, 0L)
        assertEquals(1, calls)
        assertFalse(a.fromCache)
        assertEquals(BODY, body(LAT, LON, RADIUS_M))
    }

    @Test fun `a fresh answer is cached and a remark instead of data is not`() {
        AreaRoads.transport = { _, _ -> BODY }
        AreaRoads.prefetch(LAT, LON, RADIUS_M)
        assertEquals(BODY, body(LAT, LON, RADIUS_M))

        AreaRoads.transport = { _, _ -> """{"remark":"runtime error: timeout"}""" }
        try { AreaRoads.prefetch(50.0, 4.0, RADIUS_M); fail() } catch (e: java.io.IOException) { }
        assertNull(body(50.0, 4.0, RADIUS_M))
    }

    @Test fun `a 429 makes every request back off, doubling, until one succeeds`() {
        val g = AreaRoads.FairUse(minGapMs = 3_000L, baseBackoffMs = 30_000L, maxBackoffMs = 120_000L)
        assertEquals(0L, g.waitMs(0L))
        g.sent(0L)
        assertEquals("the fair-use gap", 3_000L, g.waitMs(0L))
        g.answered(429, 1_000L)
        assertEquals(-1L, g.waitMs(20_000L))
        assertEquals(0L, g.waitMs(31_000L))
        g.answered(504, 31_000L)
        assertEquals("doubled", -1L, g.waitMs(31_000L + 59_000L))
        g.answered(429, 100_000L); g.answered(429, 100_000L)
        assertEquals("capped", 0L, g.waitMs(100_000L + 120_000L))
        g.answered(200, 300_000L)
        g.answered(429, 300_000L)
        assertEquals("reset by a success", 0L, g.waitMs(330_000L))
    }

    @Test fun `while backing off nothing is sent and the disk answers`() {
        AreaCache.put(LAT, LON, RADIUS_M, BODY)
        age(onlyFile(), 30L * 24 * 3600 * 1000)
        var calls = 0
        AreaRoads.transport = { _, _ -> calls++; throw AreaRoads.HttpStatus(429, "slow down") }
        AreaRoads.fetch(LAT, LON, RADIUS_M, 0L)
        assertEquals("each server asked once", AreaRoads.SERVERS.size, calls)
        AreaRoads.fetch(LAT, LON, RADIUS_M, 0L)
        assertEquals("the second never went out", AreaRoads.SERVERS.size, calls)
        try { AreaRoads.prefetch(50.0, 4.0, RADIUS_M); fail() }
        catch (e: AreaRoads.OverpassBusy) { }
        assertEquals(AreaRoads.SERVERS.size, calls)
    }

    // ---- straight away, while the network is asked ---------------------------

    /**
     * Opening the app on a road driven every day showed no speed limit until
     * Overpass answered -- half a minute or more with a busy server -- because
     * an old window was only used after the network had failed. This is what
     * the service shows in the meantime.
     */
    @Test fun `the disk answers for where the car is, at any age, with no network`() {
        AreaCache.put(LAT, LON, RADIUS_M, BODY)
        age(onlyFile(), 30L * 24 * 3600 * 1000)          // a month: not "fresh"
        AreaRoads.transport = { _, _ -> fail("must not go to the network"); "" }
        val a = AreaRoads.cachedAt(LAT, 4.306, 0L)!!
        assertTrue(a.fromCache)
        assertEquals("labelled with its own circle", LON, a.centreLon, 1e-5)
        assertTrue(AreaRoads.contains(a, LAT, 4.306))
        // 2.1 km away is outside it: nothing, rather than the wrong roads.
        assertNull(AreaRoads.cachedAt(LAT, 4.33, 0L))
    }

    @Test fun `a window cut short is dropped, not shown`() {
        AreaCache.put(LAT, LON, RADIUS_M, """{"elements":[{"type":"way",""")
        assertNull(AreaRoads.cachedAt(LAT, LON, 0L))
        assertEquals("forgotten", 0, AreaCache.fileCount())
    }

    @Test fun `an area holds the car only with room to spare`() {
        AreaCache.put(LAT, LON, RADIUS_M, BODY)
        val a = AreaRoads.cachedAt(LAT, LON, 0L)!!
        assertFalse(AreaRoads.contains(null, LAT, LON))
        assertTrue(AreaRoads.contains(a, LAT, LON))
        // 1.9 km east of the centre of a 2 km window: inside, but not by 200 m.
        val edge = Geo.destination(LAT, LON, 90.0, 1_900.0)
        assertFalse(AreaRoads.contains(a, edge[0], edge[1]))
        val inside = Geo.destination(LAT, LON, 90.0, 1_700.0)
        assertTrue(AreaRoads.contains(a, inside[0], inside[1]))
    }

    // ---- a second server -------------------------------------------------------

    /** Counts requests per server; [answer] decides what each one gets. */
    private class Servers(val answer: (server: Int) -> String) {
        val calls = IntArray(AreaRoads.SERVERS.size)
        val transport: (String, String) -> String = { url, _ ->
            val i = AreaRoads.SERVERS.indexOf(url)
            assertTrue("an unknown server: $url", i >= 0)
            calls[i]++
            answer(i)
        }
    }

    /**
     * The reason there is a second server. A 429 from the main one used to
     * mean no road data at all -- no speed limit in free drive -- for the
     * whole back-off, 30 s doubling up to 10 minutes.
     */
    @Test fun `a busy main server hands the request to the second`() {
        val s = Servers { if (it == 0) throw AreaRoads.HttpStatus(429, "slow down") else BODY }
        AreaRoads.transport = s.transport
        val a = AreaRoads.fetch(LAT, LON, RADIUS_M, 0L)
        assertFalse("fresh data, not the disk", a.fromCache)
        assertEquals(listOf(1, 1), s.calls.toList())
        assertEquals("and it is cached like any answer", BODY, body(LAT, LON, RADIUS_M))
        // While the main one backs off, the next request goes straight on.
        AreaRoads.fetch(51.20, 4.40, RADIUS_M, 0L)
        assertEquals(listOf(1, 2), s.calls.toList())
    }

    @Test fun `a remark instead of data from one server is not the end`() {
        val s = Servers { if (it == 0) """{"remark":"runtime error: timeout"}""" else BODY }
        AreaRoads.transport = s.transport
        val a = AreaRoads.fetch(LAT, LON, RADIUS_M, 0L)
        assertFalse(a.fromCache)
        assertEquals(listOf(1, 1), s.calls.toList())
        assertEquals(BODY, body(LAT, LON, RADIUS_M))
    }

    /**
     * A server that times out costs up to 40 s a request, so once the other
     * one has shown the signal is fine it is left alone for a while.
     */
    @Test fun `a server that does not answer is skipped while the other one does`() {
        val s = Servers { if (it == 0) throw java.net.SocketTimeoutException("no answer") else BODY }
        AreaRoads.transport = s.transport
        AreaRoads.fetch(LAT, LON, RADIUS_M, 0L)
        AreaRoads.fetch(51.20, 4.40, RADIUS_M, 0L)
        assertEquals("the silent one was not asked again", listOf(1, 2), s.calls.toList())
    }

    /** With no signal every server fails the same way: none of them is to blame. */
    @Test fun `with no signal no server is benched`() {
        val s = Servers { throw java.net.UnknownHostException("offline") }
        AreaRoads.transport = s.transport
        repeat(2) {
            try { AreaRoads.fetch(LAT, LON, RADIUS_M, 0L); fail("nothing on disk") }
            catch (e: java.io.IOException) { }
        }
        assertEquals("both asked both times", listOf(2, 2), s.calls.toList())
    }

    @Test fun `a server benched for not answering is asked again after the pause`() {
        val g = AreaRoads.FairUse(minGapMs = 0L, unreachableMs = 300_000L)
        g.unreachable(1_000L)
        assertEquals(-1L, g.waitMs(300_999L))
        assertEquals(0L, g.waitMs(301_000L))
        // A flat pause: a second one does not double it.
        g.unreachable(301_000L)
        assertEquals(0L, g.waitMs(601_000L))
        // And it never shortens a longer back-off already running: five 429s
        // make 30 + 60 + 120 + 240 + 480 s, the last one 8 minutes.
        repeat(5) { g.answered(429, 700_000L) }
        g.unreachable(700_000L)
        assertEquals(-1L, g.waitMs(700_000L + 400_000L))
        assertEquals(0L, g.waitMs(700_000L + 480_000L))
    }

    // ---- rolling along a route ------------------------------------------------

    private fun straightRoute(km: Int): Route {
        val pts = Array(km + 1) { Geo.destination(47.0, 10.0, 90.0, it * 1000.0) }
        val cum = Geo.cumulative(pts)
        return Route(pts, cum, IntArray(km), listOf(ManeuverPoint(0.0, Man.DEPART, 0, "")),
                     cum.last(), km * 36.0, "test")
    }

    @Test fun `the prefetch works in chunks, about 100 km ahead, topped up below 50`() {
        val r = straightRoute(2000)                     // Brussels to Bucharest, roughly
        val first = AreaRoads.prefetchRange(r, 0.0, 0, 100_000.0, 50_000.0)!!
        assertEquals(0, first.first)
        assertEquals(50, first.last)                    // 100 km of 2 km windows
        // 30 km on, 70 km are still ahead: nothing to do.
        assertNull(AreaRoads.prefetchRange(r, 30_000.0, first.last + 1, 100_000.0, 50_000.0))
        // 54 km on, under 50 km ahead: top up to 154 km.
        val next = AreaRoads.prefetchRange(r, 54_000.0, first.last + 1, 100_000.0, 50_000.0)!!
        assertEquals(51, next.first)
        assertEquals(77, next.last)
        // Near the end it stops at the last window.
        val end = AreaRoads.prefetchRange(r, 1_990_000.0, 900, 100_000.0, 50_000.0)!!
        assertEquals(AreaRoads.routeWindowCount(r) - 1, end.last)
    }

    @Test fun `the live window on a route is the prefetched one, so it is a disk hit`() {
        val r = straightRoute(20)
        val w = AreaRoads.routeWindow(r, AreaRoads.routeWindowIndex(5_100.0))
        AreaCache.put(w.lat, w.lon, w.radiusM, BODY)
        AreaRoads.transport = { _, _ -> fail("must not go to the network"); "" }
        val a = AreaRoads.fetch(w.lat, w.lon, w.radiusM, 0L)
        assertTrue(a.fromCache)
        // Kept until the car is well past its centre, then the next one.
        val p = Geo.pointAlong(r.pts, r.cum, 6_000.0)
        assertTrue(AreaRoads.routeWindowStillGood(a, p[0], p[1]))
        val q = Geo.pointAlong(r.pts, r.cum, 7_300.0)
        assertFalse(AreaRoads.routeWindowStillGood(a, q[0], q[1]))
    }
}
