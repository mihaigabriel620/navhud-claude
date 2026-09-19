package com.mihai.navhud

import com.mihai.navhud.nav.AreaRoads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The marker is only pulled onto a road while that is still the truth.
 *
 * `projectOnto` clamps its segment parameter to 0..1, so past the end of a way
 * it keeps returning that way's last vertex however far away you have got.
 * OSM splits ways at junctions, so used unguarded to place a marker it pins to
 * the junction and sits there while the car drives into the side street.
 */
class SnapGateTest {

    /** A 200 m way running due east at Brussels' latitude. */
    private val way = Array(3) { i ->
        doubleArrayOf(50.8000, 4.3000 + i * 0.00142)     // ~100 m steps
    }

    @Test
    fun `a point beside the way snaps onto it`() {
        // 8 m north of the middle of the way.
        val p = Geo.destination(50.8000, 4.30142, 0.0, 8.0)
        val snapped = AreaRoads.snapWithin(way, p[0], p[1], 35.0)
        assertNotNull(snapped)
        assertEquals(50.8000, snapped!![0], 1e-5)
        // ...and it really did move onto the line.
        assertEquals(0.0, Geo.haversine(snapped[0], snapped[1], 50.8000, p[1]), 1.5)
    }

    @Test
    fun `a point too far from the way is left alone`() {
        val p = Geo.destination(50.8000, 4.30142, 0.0, 60.0)
        assertNull(AreaRoads.snapWithin(way, p[0], p[1], 35.0))
    }

    @Test
    fun `running off the end of the way stops the snap instead of clamping`() {
        // 40 m past the last vertex, still dead in line with the road: the
        // unguarded projection would clamp to that vertex and hold the marker
        // there. This is the junction case, and it is the one that matters.
        val last = way.last()
        val p = Geo.destination(last[0], last[1], 90.0, 40.0)
        assertNull("must not clamp to the end vertex",
            AreaRoads.snapWithin(way, p[0], p[1], 35.0))

        // For contrast: the raw projection does exactly that.
        val raw = AreaRoads.projectOnto(way, p[0], p[1])!!
        assertEquals(last[0], raw[0], 1e-9)
        assertEquals(last[1], raw[1], 1e-9)
    }

    @Test
    fun `turning off into a side street releases the marker`() {
        // 30 m past the junction and 25 m to the side -- driving away up the
        // side road. Close enough that a distance test alone would still snap.
        val last = way.last()
        val p = Geo.destination(last[0], last[1], 20.0, 30.0)
        assertNull(AreaRoads.snapWithin(way, p[0], p[1], 35.0))
    }

    /**
     * The other half of the junction problem, and the one that shipped broken.
     *
     * Rejecting every clamped projection stopped the marker sticking at a
     * junction — and also switched the snap off for the second or two you
     * spend approaching one, which in a town is most of the time. The marker
     * then sprang sideways off the tarmac. A clamp that barely moves the point
     * is not a lie: you are standing at the end of the road.
     */
    @Test
    fun `standing at the end of a way still snaps you to it`() {
        val last = way.last()
        // 6 m past the final vertex and 5 m to the side: at the junction, and
        // still unambiguously on this road.
        val p = Geo.destination(Geo.destination(last[0], last[1], 90.0, 6.0)[0],
                                Geo.destination(last[0], last[1], 90.0, 6.0)[1], 0.0, 5.0)
        val snapped = AreaRoads.snapWithin(way, p[0], p[1], 35.0)
        assertNotNull("a small clamp is still the road", snapped)
        assertEquals(last[0], snapped!![0], 1e-6)
    }

    @Test
    fun `a degenerate way is refused rather than crashing`() {
        assertNull(AreaRoads.snapWithin(arrayOf(doubleArrayOf(50.8, 4.3)), 50.8, 4.3, 35.0))
        assertNull(AreaRoads.snapWithin(emptyArray(), 50.8, 4.3, 35.0))
    }
}
