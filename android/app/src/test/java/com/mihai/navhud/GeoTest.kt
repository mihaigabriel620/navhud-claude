package com.mihai.navhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

/**
 * The real Kotlin, not the Python reference. Same cases as
 * tools/geo_reference.py so the two cannot drift apart unnoticed.
 */
class GeoTest {

    private val DEG = Math.PI / 180.0

    @Test fun `decodes the canonical Google polyline vector`() {
        val pts = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0][0], 1e-9);   assertEquals(-120.2, pts[0][1], 1e-9)
        assertEquals(40.7, pts[1][0], 1e-9);   assertEquals(-120.95, pts[1][1], 1e-9)
        assertEquals(43.252, pts[2][0], 1e-9); assertEquals(-126.453, pts[2][1], 1e-9)
    }

    @Test fun `decodes polyline6 at the precision Mapbox sends`() {
        // Encoded with the standard algorithm at 1e6 for three Brussels points.
        val src = arrayOf(
            doubleArrayOf(50.8503, 4.3517),
            doubleArrayOf(50.8600, 4.3600),
            doubleArrayOf(50.8700, 4.3800)
        )
        val enc = encodePolyline6(src)
        val back = Geo.decodePolyline(enc, 6)
        assertEquals(src.size, back.size)
        for (i in src.indices) {
            assertEquals(src[i][0], back[i][0], 1e-6)
            assertEquals(src[i][1], back[i][1], 1e-6)
        }
    }

    @Test fun `haversine matches known distances`() {
        val d = Geo.haversine(50.8467, 4.3525, 50.8949, 4.3415)   // Grand-Place -> Atomium
        assertTrue("got $d", d in 5200.0..5500.0)
        assertEquals(111194.9, Geo.haversine(0.0, 0.0, 1.0, 0.0), 1.0)
    }

    @Test fun `projects onto the segment, not the nearest vertex`() {
        val pts = arrayOf(
            doubleArrayOf(50.0000, 4.0),
            doubleArrayOf(50.0100, 4.0),
            doubleArrayOf(50.0200, 4.0)
        )
        val cum = Geo.cumulative(pts)
        val eastDeg = 30.0 / (Geo.EARTH_R * cos(50.005 * DEG) * DEG)
        val snap = Geo.project(pts, cum, 50.0050, 4.0 + eastDeg)
        assertEquals(0, snap.segIndex)
        assertEquals(0.5, snap.t, 0.02)
        assertEquals(30.0, snap.cross, 0.5)
        assertEquals(cum[1] * 0.5, snap.along, 5.0)
    }

    @Test fun `heading picks the correct carriageway of a divided road`() {
        val off = 25.0 / (Geo.EARTH_R * cos(50.005 * DEG) * DEG)
        val dual = arrayOf(
            doubleArrayOf(50.0000, 4.0),          // northbound 0->1
            doubleArrayOf(50.0100, 4.0),
            doubleArrayOf(50.0100, 4.0 + off),    // link
            doubleArrayOf(50.0000, 4.0 + off)     // southbound 3
        )
        val cum = Geo.cumulative(dual)
        val midLon = 4.0 + off / 2
        val north = Geo.project(dual, cum, 50.0050, midLon, headingDeg = 0.0, searchAll = true)
        val south = Geo.project(dual, cum, 50.0050, midLon, headingDeg = 180.0, searchAll = true)
        assertEquals("heading north should match the northbound leg", 0, north.segIndex)
        assertEquals("heading south should match the southbound leg", 2, south.segIndex)
    }

    @Test fun `a poor windowed match triggers one full re-search`() {
        // Dense out-and-back, the shape a real overview=full geometry has.
        val retOff = 12.0 / (Geo.EARTH_R * cos(50.005 * DEG) * DEG)
        val pts = ArrayList<DoubleArray>()
        for (k in 0..100) pts.add(doubleArrayOf(50.0 + 0.0001 * k, 4.0))
        for (k in 0..100) pts.add(doubleArrayOf(50.01 - 0.0001 * k, 4.0 + retOff))
        val arr = pts.toTypedArray()
        val cum = Geo.cumulative(arr)

        // 555 m along, but the caller thinks we are at index 0 with a 400 m window.
        val snap = Geo.project(arr, cum, 50.0050, 4.0, fromIdx = 0, windowMeters = 400.0)
        assertTrue("should find the true match, cross=${snap.cross}", snap.cross < 2.0)
        assertEquals(555.0, snap.along, 20.0)

        // Well into the return leg: must stay on the return leg.
        val late = Geo.project(arr, cum, 50.0050, 4.0 + retOff, fromIdx = 150, windowMeters = 400.0)
        assertTrue("return pass, got ${late.segIndex}", late.segIndex >= 101)
        assertTrue(late.along > snap.along)
    }

    @Test fun `pointAlong interpolates and clamps at both ends`() {
        val pts = arrayOf(doubleArrayOf(50.0, 4.0), doubleArrayOf(50.01, 4.0))
        val cum = Geo.cumulative(pts)
        assertEquals(50.0, Geo.pointAlong(pts, cum, -10.0)[0], 1e-9)
        assertEquals(50.01, Geo.pointAlong(pts, cum, cum.last() + 10)[0], 1e-9)
        assertEquals(50.005, Geo.pointAlong(pts, cum, cum.last() / 2)[0], 1e-5)
    }

    @Test fun `bearing and bearingDelta behave at the wraparound`() {
        assertEquals(0.0, Geo.bearing(50.0, 4.0, 50.01, 4.0), 0.5)
        assertEquals(90.0, Geo.bearing(50.0, 4.0, 50.0, 4.01), 0.5)
        assertEquals(10.0, Geo.bearingDelta(355.0, 5.0), 1e-9)
        assertEquals(180.0, Geo.bearingDelta(0.0, 180.0), 1e-9)
    }

    // -----------------------------------------------------------------------

    private fun encodePolyline6(pts: Array<DoubleArray>): String {
        val sb = StringBuilder()
        var pLat = 0L; var pLon = 0L
        fun enc(v0: Long) {
            var v = if (v0 < 0) (v0 shl 1).inv() else (v0 shl 1)
            while (v >= 0x20) { sb.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar()); v = v shr 5 }
            sb.append((v.toInt() + 63).toChar())
        }
        for (p in pts) {
            val la = Math.round(p[0] * 1e6); val lo = Math.round(p[1] * 1e6)
            enc(la - pLat); enc(lo - pLon)
            pLat = la; pLon = lo
        }
        return sb.toString()
    }
}
