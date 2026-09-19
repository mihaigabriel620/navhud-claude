package com.mihai.navhud

import com.mihai.navhud.nav.AreaCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Before fun setUp() {
        AreaCache.dir = tmp.newFolder("area")
    }

    /** The cache is global state; leaving it set would poison every later test. */
    @After fun tearDown() {
        AreaCache.dir = null
    }

    private fun now() = System.currentTimeMillis()

    /** Push a file's modification time back, and fail loudly if the FS refuses. */
    private fun age(f: File, byMs: Long) {
        assertTrue("could not set mtime on ${f.name}",
            f.setLastModified(System.currentTimeMillis() - byMs))
    }

    private fun onlyFile(): File = AreaCache.dir!!.listFiles()!!.single()

    // ---- the basic round trip ------------------------------------------------

    @Test fun `what went in comes back out`() {
        AreaCache.put(LAT, LON, RADIUS_M, BODY)
        assertEquals(1, AreaCache.fileCount())
        assertEquals(BODY, AreaCache.get(LAT, LON, RADIUS_M, AreaCache.FRESH_MS, now()))
        assertTrue("the body is on disk, not in memory", AreaCache.sizeBytes() > 0L)
    }

    @Test fun `a window never fetched is simply absent`() {
        assertNull(AreaCache.get(LAT, LON, RADIUS_M, AreaCache.FRESH_MS, now()))
    }

    @Test fun `with no directory set the cache is a no-op, not a crash`() {
        AreaCache.dir = null
        AreaCache.put(LAT, LON, RADIUS_M, BODY)          // must not throw
        assertNull(AreaCache.get(LAT, LON, RADIUS_M, AreaCache.FRESH_MS, now()))
        assertEquals(0, AreaCache.fileCount())
        assertEquals(0L, AreaCache.sizeBytes())
    }

    // ---- freshness -----------------------------------------------------------

    @Test fun `a week-old window is still fresh, a fortnight-old one is not`() {
        AreaCache.put(LAT, LON, RADIUS_M, BODY)

        age(onlyFile(), AreaCache.FRESH_MS - 60_000L)
        assertNotNull("just inside the window",
            AreaCache.get(LAT, LON, RADIUS_M, AreaCache.FRESH_MS, now()))

        age(onlyFile(), AreaCache.FRESH_MS + 60_000L)
        assertNull("just outside it",
            AreaCache.get(LAT, LON, RADIUS_M, AreaCache.FRESH_MS, now()))
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

        assertNull(AreaCache.get(LAT, LON, RADIUS_M, AreaCache.FRESH_MS, now()))
        assertEquals(BODY, AreaCache.get(LAT, LON, RADIUS_M, Long.MAX_VALUE, now()))
    }

    // ---- the nearest-cell fallback -------------------------------------------

    /**
     * Keying on the exact query centre would almost never hit: the centre is
     * derived from the car's position and heading and is different on every
     * fetch. The grid is what makes a hit possible at all, and this is what
     * saves the drive that happens to run along a cell boundary.
     */
    @Test fun `a neighbouring cell answers for a window just off the grid`() {
        AreaCache.put(LAT, LON, RADIUS_M, BODY)

        // 4.306 rounds to cell 431, not 430 -- so the exact key misses. The
        // stored cell centre is 422 m away, well inside NEAR_M.
        assertEquals(BODY, AreaCache.get(LAT, 4.306, RADIUS_M, AreaCache.FRESH_MS, now()))
    }

    @Test fun `a cell too far away is not borrowed from`() {
        AreaCache.put(LAT, LON, RADIUS_M, BODY)

        // 4.33 is 2.1 km from the stored cell centre. A 2 km window centred
        // there does not contain the road under the car, so answering with it
        // would be worse than answering with nothing.
        assertNull(AreaCache.get(LAT, 4.33, RADIUS_M, AreaCache.FRESH_MS, now()))
    }

    @Test fun `a stale neighbour is skipped as surely as a stale exact hit`() {
        AreaCache.put(LAT, LON, RADIUS_M, BODY)
        age(onlyFile(), AreaCache.FRESH_MS + 60_000L)

        assertNull(AreaCache.get(LAT, 4.306, RADIUS_M, AreaCache.FRESH_MS, now()))
        assertEquals("...but the no-limit fallback still finds it", BODY,
            AreaCache.get(LAT, 4.306, RADIUS_M, Long.MAX_VALUE, now()))
    }

    @Test fun `a window fetched at another radius is not reused`() {
        AreaCache.put(LAT, LON, RADIUS_M, BODY)

        // A 2 km circle is not an answer to a question about a 6 km one: at
        // 100 km/h the lookahead runs off the edge of what was cached.
        assertNull(AreaCache.get(LAT, LON, 6000.0, AreaCache.FRESH_MS, now()))
        assertNull(AreaCache.get(LAT, 4.306, 6000.0, AreaCache.FRESH_MS, now()))
    }

    @Test fun `the nearest of several neighbours is the one read`() {
        AreaCache.put(LAT, LON, RADIUS_M, "near")        // cell centre, 0 m away
        AreaCache.put(50.859, LON, RADIUS_M, "far")      // next cell north, ~1 km

        assertEquals("near", AreaCache.get(LAT, 4.306, RADIUS_M, AreaCache.FRESH_MS, now()))
    }

    // ---- the cap -------------------------------------------------------------

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
            AreaCache.get(LAT, LON, RADIUS_M, AreaCache.FRESH_MS, now()))
    }

    @Test fun `under the cap nothing is evicted`() {
        val d = AreaCache.dir!!
        for (i in 1 until AreaCache.MAX_FILES) File(d, "a_${i}_0_2.json").writeText("body $i")
        AreaCache.put(LAT, LON, RADIUS_M, BODY)

        assertEquals(AreaCache.MAX_FILES, AreaCache.fileCount())
        assertTrue(File(d, "a_1_0_2.json").exists())
    }

    @Test fun `clear empties the lot`() {
        AreaCache.put(LAT, LON, RADIUS_M, BODY)
        AreaCache.put(50.9, 4.4, RADIUS_M, BODY)
        assertEquals(2, AreaCache.fileCount())

        AreaCache.clear()

        assertEquals(0, AreaCache.fileCount())
        assertEquals(0L, AreaCache.sizeBytes())
        assertNull(AreaCache.get(LAT, LON, RADIUS_M, AreaCache.FRESH_MS, now()))
    }

    // ---- the published numbers ------------------------------------------------

    @Test fun `the grid and the window are the shipped ones`() {
        assertEquals(0.01, AreaCache.CELL_DEG, 0.0)
        assertEquals(7L * 24 * 3600 * 1000, AreaCache.FRESH_MS)
        assertEquals(1500.0, AreaCache.NEAR_M, 0.0)
        assertEquals(400, AreaCache.MAX_FILES)
    }
}
