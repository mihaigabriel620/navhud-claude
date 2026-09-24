package com.mihai.navhud

import com.mihai.navhud.map.RoadLock
import com.mihai.navhud.nav.Area
import com.mihai.navhud.nav.RoadWay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arrow stays on the road it is on (1.29).
 *
 * 1.28 parked indoors: the arrow snapped to the street, then slid off it onto
 * the raw fix and stayed there. The lock picks a road once and keeps it: GPS
 * wander while standing moves nothing, and driving moves the arrow only along
 * the road until another road clearly takes over.
 */
class RoadLockTest {

    private val lat0 = 50.8000
    private val lon0 = 4.3000

    /** ~1 km due east. */
    private val a = RoadWay(1, arrayOf(doubleArrayOf(lat0, lon0), doubleArrayOf(lat0, 4.3142)),
        "Rue A", "", 50, "residential", 0)

    /** Parallel to [a], 60 m north. */
    private val bLat = Geo.destination(lat0, lon0, 0.0, 60.0)[0]
    private val b = RoadWay(2, arrayOf(doubleArrayOf(bLat, lon0), doubleArrayOf(bLat, 4.3142)),
        "Rue B", "", 50, "residential", 0)

    /** North from [a]'s east end. */
    private val cTop = Geo.destination(lat0, 4.3142, 0.0, 30.0)
    private val c = RoadWay(3, arrayOf(doubleArrayOf(lat0, 4.3142), cTop),
        "Rue C", "", 50, "residential", 0)

    private val area = Area(lat0, 4.307, 2000.0, listOf(a, b, c), emptyList(), 0L)

    private fun north(lon: Double, m: Double) = Geo.destination(lat0, lon, 0.0, m)

    @Test
    fun `parked, GPS wander does not move the arrow`() {
        val lock = RoadLock()
        val f = north(4.3071, 15.0)
        assertTrue(lock.update(area, f[0], f[1], 0.0, null))
        assertEquals(1L, lock.road!!.id)
        val lat = lock.lat
        val lon = lock.lon
        assertEquals(lat0, lat, 1e-6)                    // on the road
        // Wander: beyond the old 35 m snap limit, nearer road B, a little east.
        for (m in listOf(30.0, 50.0, 40.0)) {
            val w = Geo.destination(lat0, 4.3073, 0.0, m)
            assertTrue(lock.update(area, w[0], w[1], 0.4, null))
            assertEquals(1L, lock.road!!.id)
            assertTrue(lock.holding)
            assertEquals(lat, lock.lat, 0.0)
            assertEquals(lon, lock.lon, 0.0)
        }
    }

    @Test
    fun `parked, the arrow faces along the road the way the car points`() {
        val lock = RoadLock()
        val f = north(4.3071, 10.0)
        lock.update(area, f[0], f[1], 0.0, null, facingDeg = 250.0)
        assertEquals(270.0, lock.bearingDeg!!, 1.0)
    }

    @Test
    fun `a road up to 150 m away is taken, nothing nearer means raw`() {
        val lock = RoadLock()
        val f = Geo.destination(lat0, 4.3071, 180.0, 120.0)      // 120 m south of A
        assertTrue(lock.update(area, f[0], f[1], 0.0, null))
        assertEquals(1L, lock.road!!.id)

        val far = Geo.destination(lat0, 4.3071, 180.0, 200.0)
        assertFalse(RoadLock().update(area, far[0], far[1], 0.0, null))
        // No road data yet: raw too.
        assertFalse(RoadLock().update(null, f[0], f[1], 0.0, null))
    }

    @Test
    fun `driving, the arrow moves along the road it is locked to`() {
        val lock = RoadLock()
        var lastLon = 0.0
        for (i in 0 until 5) {
            val f = north(4.3010 + i * 0.0002, 12.0)
            assertTrue(lock.update(area, f[0], f[1], 10.0, 90.0))
            assertEquals(1L, lock.road!!.id)
            assertEquals(lat0, lock.lat, 1e-6)
            assertTrue(lock.lon > lastLon)
            lastLon = lock.lon
            assertEquals(90.0, lock.bearingDeg!!, 1.0)
        }
    }

    @Test
    fun `another road takes over only when clearly nearer on two fixes`() {
        val lock = RoadLock()
        var f = north(4.3010, 3.0)
        lock.update(area, f[0], f[1], 10.0, 90.0)
        assertEquals(1L, lock.road!!.id)
        // One fix near B: still A.
        f = north(4.3012, 52.0)
        lock.update(area, f[0], f[1], 10.0, 90.0)
        assertEquals(1L, lock.road!!.id)
        // Second in a row: B.
        f = north(4.3014, 53.0)
        lock.update(area, f[0], f[1], 10.0, 90.0)
        assertEquals(2L, lock.road!!.id)
        assertEquals(bLat, lock.lat, 1e-6)
    }

    @Test
    fun `slow or standing, no road change however near the other road looks`() {
        val lock = RoadLock()
        var f = north(4.3010, 3.0)
        lock.update(area, f[0], f[1], 10.0, 90.0)
        for (i in 0 until 4) {
            f = north(4.3012 + i * 0.00001, 55.0)
            lock.update(area, f[0], f[1], 1.0, 90.0)    // 3.6 km/h: creeping
            assertEquals(1L, lock.road!!.id)
            assertEquals(lat0, lock.lat, 1e-6)
        }
    }

    @Test
    fun `turning at the end of the way takes the road there at once`() {
        val lock = RoadLock()
        var f = north(4.3138, 2.0)
        lock.update(area, f[0], f[1], 8.0, 90.0)
        assertEquals(1L, lock.road!!.id)
        // Round the corner, 15 m up road C, heading north.
        f = Geo.destination(lat0, 4.3142, 0.0, 15.0)
        lock.update(area, f[0], f[1], 8.0, 0.0)
        assertEquals(3L, lock.road!!.id)
        assertEquals(0.0, Geo.haversine(lock.lat, lock.lon, f[0], f[1]), 1.0)
    }

    @Test
    fun `a fix far from the locked road re-picks even standing`() {
        val lock = RoadLock()
        var f = north(4.3071, 5.0)
        lock.update(area, f[0], f[1], 0.0, null)
        assertEquals(1L, lock.road!!.id)
        f = north(4.3071, 110.0)                          // 50 m past B
        assertTrue(lock.update(area, f[0], f[1], 0.0, null))
        assertEquals(2L, lock.road!!.id)
    }
}
