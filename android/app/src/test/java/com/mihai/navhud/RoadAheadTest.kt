package com.mihai.navhud

import com.mihai.navhud.alerts.RoadAhead
import com.mihai.navhud.nav.Area
import com.mihai.navhud.nav.AreaRoads
import com.mihai.navhud.nav.RoadFeature
import org.junit.Assert.assertEquals as eq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level crossings, speed bumps and toll booths: the static warnings Waze
 * gives that need no user reports, only OpenStreetMap.
 */
class RoadAheadTest {

    private fun area(vararg f: RoadFeature) =
        Area(50.85, 4.35, 1000.0, emptyList(), emptyList(), 0L, f.toList())

    /** A point `m` metres due north of the test origin. */
    private fun north(m: Double) = Geo.destination(50.85, 4.35, 0.0, m)

    /** The road we are on: straight, due north, a kilometre of it. */
    private val road: Array<DoubleArray> = Array(11) { Geo.destination(50.85, 4.35, 0.0, it * 100.0) }

    /** A parallel street 40 m to the east — the one the app used to warn about. */
    private val parallel: Array<DoubleArray> = Array(11) {
        val p = Geo.destination(50.85, 4.35, 0.0, it * 100.0)
        Geo.destination(p[0], p[1], 90.0, 40.0)
    }

    @Test fun `the warning distance is a few seconds, not a few hundred metres`() {
        // Standing still: the floor, so the warning is not delivered after you
        // have already gone over the bump.
        assertEquals(RoadAhead.MIN_M, RoadAhead.warnDistance(0))
        assertEquals(RoadAhead.MIN_M, RoadAhead.warnDistance(30))
        // 90 km/h is 25 m/s; eight seconds is about 200 m.
        assertTrue(RoadAhead.warnDistance(90) in 180..220)
        // And it is capped, or a motorway would warn about a bump in the next
        // village.
        assertEquals(RoadAhead.MAX_M, RoadAhead.warnDistance(400))
    }

    @Test fun `a bump ahead is found and one behind is not`() {
        val p = north(80.0)
        val a = area(RoadFeature(1, RoadFeature.SPEED_BUMP, p[0], p[1]))
        val hit = RoadAhead.nearest(a, 50.85, 4.35, 0.0, 50, road)
        assertNotNull(hit)
        assertEquals(RoadFeature.SPEED_BUMP, hit!!.feature.kind)
        assertEquals(80.0, hit.distanceM.toDouble(), 3.0)
        // Same feature, now behind us.
        assertNull(RoadAhead.nearest(a, 50.85, 4.35, 180.0, 50, road))
    }

    @Test fun `something out of reach stays quiet`() {
        val p = north(250.0)
        val a = area(RoadFeature(1, RoadFeature.LEVEL_CROSSING, p[0], p[1]))
        // At 50 km/h eight seconds is 111 m, so this one is not our problem yet.
        assertNull(RoadAhead.nearest(a, 50.85, 4.35, 0.0, 50, road))
        // ...but at motorway speed the same feature is inside the reach.
        assertNotNull(RoadAhead.nearest(a, 50.85, 4.35, 0.0, 120, road))
    }

    @Test fun `a toll booth outranks a bump a little further on`() {
        val bump = north(70.0)
        val toll = north(95.0)
        val a = area(
            RoadFeature(1, RoadFeature.SPEED_BUMP, bump[0], bump[1]),
            RoadFeature(2, RoadFeature.TOLL_BOOTH, toll[0], toll[1])
        )
        val hit = RoadAhead.nearest(a, 50.85, 4.35, 0.0, 90, road)
        assertEquals(RoadFeature.TOLL_BOOTH, hit!!.feature.kind)
    }

    @Test fun `no heading means no cone, but the road still has to match`() {
        // 90 m *behind* us along the same road: with no heading there is no
        // cone to reject it, and it is genuinely on our road.
        val p = Geo.destination(50.85, 4.35, 180.0, 90.0)
        val a = area(RoadFeature(1, RoadFeature.SPEED_BUMP, p[0], p[1]))
        val backwards: Array<DoubleArray> =
            Array(11) { Geo.destination(50.85, 4.35, 180.0, it * 100.0) }
        assertNotNull(RoadAhead.nearest(a, 50.85, 4.35, null, 90, backwards))
    }

    // ---- the road test ------------------------------------------------------

    @Test fun `a bump on the street beside us is not our bump`() {
        // Directly ahead within the cone and well inside the reach -- the old
        // code announced this one, and it was the thing that made the warnings
        // untrustworthy.
        val p0 = north(120.0)
        val p = Geo.destination(p0[0], p0[1], 90.0, 40.0)
        val a = area(RoadFeature(1, RoadFeature.SPEED_BUMP, p[0], p[1]))
        assertNull(RoadAhead.nearest(a, 50.85, 4.35, 0.0, 90, road))
        // ...and it *is* found by someone actually driving that street.
        assertNotNull(RoadAhead.nearest(a, p[0], p[1], 0.0, 90, parallel))
    }

    @Test fun `a bump a few metres off the centreline is still ours`() {
        val p0 = north(120.0)
        val p = Geo.destination(p0[0], p0[1], 90.0, 6.0)      // the other lane
        val a = area(RoadFeature(1, RoadFeature.SPEED_BUMP, p[0], p[1]))
        assertNotNull(RoadAhead.nearest(a, 50.85, 4.35, 0.0, 90, road))
    }

    @Test fun `with no idea which road we are on, we say nothing`() {
        val p = north(120.0)
        val a = area(RoadFeature(1, RoadFeature.LEVEL_CROSSING, p[0], p[1]))
        assertNull(RoadAhead.nearest(a, 50.85, 4.35, 0.0, 90, null))
        assertNull(RoadAhead.nearest(a, 50.85, 4.35, 0.0, 90, arrayOf(doubleArrayOf(50.85, 4.35))))
    }

    @Test fun `an empty or missing area is not an error`() {
        assertNull(RoadAhead.nearest(null, 50.85, 4.35, 0.0, 50, road))
        assertNull(RoadAhead.nearest(area(), 50.85, 4.35, 0.0, 50, road))
    }

    // ---- parsing ------------------------------------------------------------

    @Test fun `road features parse out of the same Overpass response`() {
        val json = """
        {"elements":[
          {"type":"node","id":1,"lat":50.85,"lon":4.35,"tags":{"railway":"level_crossing"}},
          {"type":"node","id":2,"lat":50.86,"lon":4.35,"tags":{"traffic_calming":"hump"}},
          {"type":"node","id":3,"lat":50.87,"lon":4.35,"tags":{"barrier":"toll_booth"}},
          {"type":"node","id":4,"lat":50.88,"lon":4.35,"tags":{"highway":"speed_camera"}},
          {"type":"node","id":5,"lat":50.89,"lon":4.35,"tags":{"traffic_calming":"rumble_strip"}},
          {"type":"way","id":6,"tags":{"highway":"primary"}}
        ]}"""
        val f = AreaRoads.parseFeatures(json)
        assertEquals(3, f.size)
        assertEquals(RoadFeature.LEVEL_CROSSING, f[0].kind)
        assertEquals(RoadFeature.SPEED_BUMP, f[1].kind)
        assertEquals(RoadFeature.TOLL_BOOTH, f[2].kind)
    }

    @Test fun `a node with no position is skipped, not crashed on`() {
        val json = """{"elements":[
          {"type":"node","id":1,"tags":{"railway":"level_crossing"}},
          {"type":"node","id":2,"lat":50.85,"lon":4.35,"tags":{"barrier":"toll_booth"}}
        ]}"""
        val f = AreaRoads.parseFeatures(json)
        assertEquals(1, f.size)
        assertEquals(2L, f[0].id)
    }

    @Test fun `the area query asks for all three kinds`() {
        val q = AreaRoads.buildQuery(50.85, 4.35, 1200)
        assertTrue(q.contains("level_crossing"))
        assertTrue(q.contains("traffic_calming"))
        assertTrue(q.contains("toll_booth"))
        // ...without losing what it already fetched.
        assertTrue(q.contains("speed_camera"))
        assertTrue(q.contains("highway"))
    }
}
