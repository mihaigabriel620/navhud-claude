package com.mihai.navhud

import com.mihai.navhud.alerts.CameraPolicy
import com.mihai.navhud.nav.Area
import com.mihai.navhud.nav.AreaRoads
import com.mihai.navhud.nav.RoadWay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Driving with no destination.
 *
 * With a route, the speed limit arrives annotated onto the directions. Without
 * one there is nothing to annotate, so the road network has to be fetched and
 * matched directly — and matching is the whole difficulty, because a slip road
 * runs a few metres from the motorway and a service road runs nearer still.
 */
class FreeDriveTest {

    // A north-south road at longitude 4.3500, and a parallel one 20 m east.
    private fun northSouth(lonOffsetDeg: Double, name: String, limit: Int,
                           kind: String = "primary", oneway: Int = 0) =
        RoadWay(
            id = name.hashCode().toLong(),
            pts = Array(6) { doubleArrayOf(50.8000 + it * 0.0009, 4.3500 + lonOffsetDeg) },
            name = name, ref = "", limitKph = limit, kind = kind, onewayDir = oneway
        )

    private fun area(vararg roads: RoadWay) =
        Area(50.8020, 4.3500, 2000.0, roads.toList(), emptyList(), 0L)

    // ---- maxspeed parsing ---------------------------------------------------

    @Test fun `the maxspeed tags OpenStreetMap actually contains`() {
        assertEquals(50, AreaRoads.parseMaxspeed("50"))
        assertEquals(120, AreaRoads.parseMaxspeed("120"))
        assertEquals(0, AreaRoads.parseMaxspeed(null))
        assertEquals(0, AreaRoads.parseMaxspeed(""))
        assertEquals(-1, AreaRoads.parseMaxspeed("none"))          // German autobahn
        assertEquals(5, AreaRoads.parseMaxspeed("walk"))
        assertEquals(48, AreaRoads.parseMaxspeed("30 mph"))
        assertEquals(0, AreaRoads.parseMaxspeed("signals"))
    }

    @Test fun `implicit country limits are read, because most roads have no tag`() {
        assertEquals(50, AreaRoads.parseMaxspeed("BE:urban"))
        assertEquals(70, AreaRoads.parseMaxspeed("BE:rural"))      // 70 since 2017
        assertEquals(120, AreaRoads.parseMaxspeed("BE:motorway"))
        assertEquals(80, AreaRoads.parseMaxspeed("FR:rural"))
        assertEquals(130, AreaRoads.parseMaxspeed("RO:motorway"))
        assertEquals(-1, AreaRoads.parseMaxspeed("DE:motorway"))   // derestricted
        assertEquals(20, AreaRoads.parseMaxspeed("BE:living_street"))
    }

    // ---- picking the right road ---------------------------------------------

    @Test fun `the nearest road wins when there is only one`() {
        val a = area(northSouth(0.0, "Chaussée de Wavre", 50))
        val m = AreaRoads.match(a, 50.8020, 4.3500, 0.0)
        assertNotNull(m)
        assertEquals("Chaussée de Wavre", m!!.road.name)
        assertTrue(m.crossM < 1.0)
    }

    @Test fun `a road running across us is not the road we are on`() {
        // An east-west road passing right under the car.
        val crossing = RoadWay(
            1, Array(6) { doubleArrayOf(50.8020, 4.3480 + it * 0.0008) },
            "Rue Transversale", "", 30, "residential", 0
        )
        val a = area(crossing)
        // Driving north. The crossing road is 0 m away but 90 degrees off.
        assertNull("must not claim we are on the road we are crossing",
            AreaRoads.match(a, 50.8020, 4.3500, 0.0))
        // Driving east along it, the same road matches.
        assertNotNull(AreaRoads.match(a, 50.8020, 4.3500, 90.0))
    }

    @Test fun `a two-way road matches whichever way you drive it`() {
        val a = area(northSouth(0.0, "Rue du Labeur", 50))
        assertNotNull(AreaRoads.match(a, 50.8020, 4.3500, 0.0))
        assertNotNull(AreaRoads.match(a, 50.8020, 4.3500, 180.0))
    }

    @Test fun `a one-way road only matches the way it runs`() {
        val a = area(northSouth(0.0, "Rue à Sens Unique", 30, "residential", oneway = 1))
        assertNotNull(AreaRoads.match(a, 50.8020, 4.3500, 0.0))
        assertNull("driving the wrong way up a one-way is not a match",
            AreaRoads.match(a, 50.8020, 4.3500, 180.0))
    }

    @Test fun `the motorway wins over the service road beside it`() {
        // 12 m apart -- inside GPS error of each other, which is the whole
        // problem: nearest-line alone would pick whichever the fix leaned to.
        val motorway = northSouth(0.0, "E411", 120, "motorway")
        val service = northSouth(0.00017, "parking", 30, "service")
        val a = area(service, motorway)
        val m = AreaRoads.match(a, 50.8020, 4.35008, 0.0)
        assertEquals("E411", m!!.road.name)
        assertEquals(120, m.road.limitKph)
    }

    @Test fun `being nowhere near a road is not a match`() {
        val a = area(northSouth(0.0, "Chaussée de Wavre", 50))
        // 200 m east, in a field.
        assertNull(AreaRoads.match(a, 50.8020, 4.3529, 0.0))
    }

    // ---- refetching ---------------------------------------------------------

    @Test fun `the fetch radius is about a minute of driving, within bounds`() {
        assertEquals(AreaRoads.MIN_RADIUS_M, AreaRoads.radiusFor(0.0), 1e-9)
        assertEquals(AreaRoads.MIN_RADIUS_M, AreaRoads.radiusFor(8.0), 1e-9)   // 30 km/h
        assertEquals(2100.0, AreaRoads.radiusFor(35.0), 1.0)                  // 126 km/h
        assertEquals(AreaRoads.MAX_RADIUS_M, AreaRoads.radiusFor(200.0), 1e-9)
        // 130 km/h -> about 36 m/s -> a bit over two kilometres.
        val fast = AreaRoads.radiusFor(36.0)
        assertTrue("$fast m at 130 km/h", fast > 2000.0 && fast <= AreaRoads.MAX_RADIUS_M)
    }

    @Test fun `we refetch once we have left the middle of the old window`() {
        val a = Area(50.8000, 4.3500, 2000.0, emptyList(), emptyList(), 0L)
        assertTrue("nothing loaded yet", AreaRoads.needsRefetch(null, 50.8, 4.35, 2000.0))
        assertTrue("no refetch while still central",
            !AreaRoads.needsRefetch(a, 50.8010, 4.3500, 2000.0))   // ~110 m
        // 1.2 km north is past 45% of a 2 km radius.
        assertTrue(AreaRoads.needsRefetch(a, 50.8108, 4.3500, 2000.0))
    }

    @Test fun `a big change of speed refetches, because the window is the wrong size`() {
        val a = Area(50.8000, 4.3500, 1200.0, emptyList(), emptyList(), 0L)
        assertTrue("joined a motorway", AreaRoads.needsRefetch(a, 50.8000, 4.3500, 4000.0))
        val big = Area(50.8000, 4.3500, 6000.0, emptyList(), emptyList(), 0L)
        assertTrue("came off it into town", AreaRoads.needsRefetch(big, 50.8, 4.35, 1200.0))
    }

    @Test fun `the query is centred ahead of the car, not on it`() {
        val c = AreaRoads.centreFor(50.8000, 4.3500, 0.0, 2000.0)
        assertTrue("should be north of us", c[0] > 50.8000)
        assertEquals(700.0, Geo.haversine(50.8, 4.35, c[0], c[1]), 50.0)
        // With no heading there is no "ahead".
        val still = AreaRoads.centreFor(50.8, 4.35, null, 2000.0)
        assertEquals(50.8, still[0], 1e-9)
    }

    // ---- parsing an Overpass reply -----------------------------------------

    @Test fun `out geom gives the coordinates inline, cameras and all`() {
        val body = """
        {"elements":[
          {"type":"way","id":1,"tags":{"highway":"primary","name":"Chaussée de Wavre",
             "maxspeed":"50","ref":"N2","oneway":"yes"},
           "geometry":[{"lat":50.80,"lon":4.35},{"lat":50.81,"lon":4.35}]},
          {"type":"way","id":2,"tags":{"highway":"residential"},
           "geometry":[{"lat":50.80,"lon":4.36}]},
          {"type":"node","id":9,"lat":50.805,"lon":4.351,
           "tags":{"highway":"speed_camera","maxspeed":"70","direction":"180"}}
        ]}
        """.trimIndent()
        val (roads, cams) = AreaRoads.parse(body)
        assertEquals("a one-point way is not a road", 1, roads.size)
        assertEquals("Chaussée de Wavre", roads[0].name)
        assertEquals("N2 · Chaussée de Wavre", roads[0].label)
        assertEquals(50, roads[0].limitKph)
        assertEquals(1, roads[0].onewayDir)
        assertEquals(1, cams.size)
        assertEquals(70, cams[0].limitKph)
        assertEquals(180.0, cams[0].directionDeg!!, 1e-9)
    }

    @Test fun `a road with no name falls back to its number`() {
        val (roads, _) = AreaRoads.parse("""
          {"elements":[{"type":"way","id":1,"tags":{"highway":"motorway","ref":"E411"},
            "geometry":[{"lat":50.8,"lon":4.35},{"lat":50.81,"lon":4.35}]}]}
        """.trimIndent())
        assertEquals("E411", roads[0].label)
    }

    @Test fun `rubbish in gives nothing out rather than an exception`() {
        assertEquals(0, AreaRoads.parse("""{"elements":[]}""").first.size)
        assertEquals(0, AreaRoads.parse("""{}""").first.size)
    }

    // ---- the tracker --------------------------------------------------------

    @Test fun `the limit of the road under the car is shown with no route at all`() {
        val t = FreeTracker()
        t.area = area(northSouth(0.0, "Chaussée de Wavre", 50))
        val f = t.update(50.8020, 4.3500, 16f, 0f, true, 1000L, CameraPolicy.EXACT)
        assertEquals(50, f.limitKph)
        assertEquals(58, f.speedKph)
        assertEquals("no route means no instruction", Man.NONE, f.maneuver)
        assertEquals(0, f.distToManeuverM)
        assertEquals("Chaussée de Wavre", t.roadName)
        assertTrue("over 50 at 58", f.flags and HudFrame.FLAG_OVER_LIMIT != 0)
    }

    @Test fun `the limit is held briefly across an untagged junction`() {
        val t = FreeTracker()
        t.area = area(northSouth(0.0, "Chaussée de Wavre", 50))
        t.update(50.8020, 4.3500, 12f, 0f, true, 1000L, CameraPolicy.EXACT)

        // Now the match is lost entirely -- a roundabout with no maxspeed.
        t.area = area()
        val soon = t.update(50.8021, 4.3500, 12f, 0f, true, 6000L, CameraPolicy.EXACT)
        assertEquals("hold it rather than blinking the sign off", 50, soon.limitKph)
        assertTrue(soon.flags and HudFrame.FLAG_LOW_CONF != 0)

        val later = t.update(50.8022, 4.3500, 12f, 0f, true, 40_000L, CameraPolicy.EXACT)
        assertEquals("but not for ever", 0, later.limitKph)
    }

    @Test fun `losing the fix blanks the speed but keeps the last limit`() {
        val t = FreeTracker()
        t.area = area(northSouth(0.0, "Chaussée de Wavre", 50))
        t.update(50.8020, 4.3500, 12f, 0f, true, 1000L, CameraPolicy.EXACT)
        val f = t.update(0.0, 0.0, 0f, null, false, 2000L, CameraPolicy.EXACT)
        assertEquals(-1, f.speedKph)
        assertEquals(50, f.limitKph)
        assertNull(t.alert)
    }

    @Test fun `a camera behind us is not a warning`() {
        val cam = com.mihai.navhud.alerts.SpeedCamera(
            5, 50.7990, 4.3500, 0.0, 50, null,
            com.mihai.navhud.alerts.SpeedCamera.Kind.FIXED)
        val t = FreeTracker()
        t.area = Area(50.802, 4.35, 2000.0,
            listOf(northSouth(0.0, "Chaussée de Wavre", 50)), listOf(cam), 0L)
        // Driving north, camera is south of us.
        t.update(50.8020, 4.3500, 12f, 0f, true, 1000L, CameraPolicy.EXACT)
        assertNull(t.alert)
    }

    @Test fun `a camera ahead of us is`() {
        val cam = com.mihai.navhud.alerts.SpeedCamera(
            5, 50.8045, 4.3500, 0.0, 50, null,
            com.mihai.navhud.alerts.SpeedCamera.Kind.FIXED)
        val t = FreeTracker()
        t.area = Area(50.802, 4.35, 2000.0,
            listOf(northSouth(0.0, "Chaussée de Wavre", 50)), listOf(cam), 0L)
        t.update(50.8020, 4.3500, 12f, 0f, true, 1000L, CameraPolicy.EXACT)
        val a = t.alert
        assertNotNull("278 m ahead, in town: that is a warning", a)
        assertEquals(278.0, a!!.distanceM.toDouble(), 15.0)
        assertTrue("first time only", t.shouldAnnounce(a))
        assertTrue(!t.shouldAnnounce(a))
    }

    @Test fun `camera policy OFF is honoured with no route just the same`() {
        val cam = com.mihai.navhud.alerts.SpeedCamera(
            5, 50.8045, 4.3500, 0.0, 50, null,
            com.mihai.navhud.alerts.SpeedCamera.Kind.FIXED)
        val t = FreeTracker()
        t.area = Area(50.802, 4.35, 2000.0, emptyList(), listOf(cam), 0L)
        t.update(50.8020, 4.3500, 12f, 0f, true, 1000L, CameraPolicy.OFF)
        assertNull("Germany and Switzerland do not get camera alerts", t.alert)
    }

    @Test fun `with no road data at all it still reports your speed`() {
        val t = FreeTracker()
        val f = t.update(50.8020, 4.3500, 25f, 0f, true, 1000L, CameraPolicy.EXACT)
        assertEquals(90, f.speedKph)
        assertEquals(0, f.limitKph)
        assertTrue("say so rather than implying a known limit",
            f.flags and HudFrame.FLAG_LOW_CONF != 0)
    }

    // ---- the review's findings, as regressions ------------------------------

    @Test fun `a nearer legal road is not lost to a further important one`() {
        // The bug: candidates were admitted out to 70 m, ranked by a cost that
        // subtracts road importance, and only then re-checked against 35 m. A
        // motorway at 40 m out-scored a residential road at 34 m, won, failed
        // the final check -- and took the legitimate match down with it.
        val motorway = northSouth(0.00057, "E411", 120, "motorway")     // ~40 m east
        val street = northSouth(-0.00048, "Rue Basse", 50, "residential") // ~34 m west
        val m = AreaRoads.match(area(motorway, street), 50.8020, 4.3500, 0.0)
        assertNotNull("a road 34 m away must still be found", m)
        assertEquals("Rue Basse", m!!.road.name)
        assertTrue(m.crossM <= AreaRoads.MATCH_LIMIT_M)
    }

    @Test fun `every match returned is inside the match limit`() {
        val far = northSouth(0.0009, "E40", 120, "motorway")            // ~63 m
        assertNull(AreaRoads.match(area(far), 50.8020, 4.3500, 0.0))
    }

    @Test fun `a duplicated node does not turn a road due north`() {
        // Two identical consecutive points: the segment has no direction, and
        // reading it as bearing 0 made an east-west road look like it ran north.
        val pts = arrayOf(
            doubleArrayOf(50.8020, 4.3490),
            doubleArrayOf(50.8020, 4.3500),
            doubleArrayOf(50.8020, 4.3500),     // duplicate
            doubleArrayOf(50.8020, 4.3510)
        )
        val road = RoadWay(1, pts, "Rue Est-Ouest", "", 50, "residential", 0)
        val m = AreaRoads.match(area(road), 50.8020, 4.3500, 90.0)
        assertNotNull("driving east along an east-west road must match", m)
    }

    @Test fun `one broken element does not discard the whole road network`() {
        val body = """
        {"elements":[
          {"type":"way","id":1,"tags":{"highway":"primary","maxspeed":"50","name":"Bonne"},
           "geometry":[{"lat":50.80,"lon":4.35},{"lat":50.81,"lon":4.35}]},
          {"type":"way","id":2,"tags":{"highway":"primary"},"geometry":[null,null]},
          {"type":"way","id":3,"tags":{"highway":"primary","name":"Aussi"},
           "geometry":[{"lat":50.82,"lon":4.35},{"lat":50.83,"lon":4.35}]}
        ]}
        """.trimIndent()
        val (roads, _) = AreaRoads.parse(body)
        assertEquals("the good roads must survive the bad one", 2, roads.size)
    }

    @Test fun `losing the fix for good does not leave a limit on the glass`() {
        val t = FreeTracker()
        t.area = area(northSouth(0.0, "Chaussée de Wavre", 50))
        t.update(50.8020, 4.3500, 12f, 0f, true, 1000L, CameraPolicy.EXACT)

        // Into a tunnel, and the fix never comes back.
        assertEquals(50, t.update(0.0, 0.0, 0f, null, false, 5000L, CameraPolicy.EXACT).limitKph)
        val later = t.update(0.0, 0.0, 0f, null, false, 90_000L, CameraPolicy.EXACT)
        assertEquals("a stale limit is worse than no limit", 0, later.limitKph)
        assertNull(t.roadName)
    }

    @Test fun `stopping at a light does not warn about the camera behind you`() {
        val behind = com.mihai.navhud.alerts.SpeedCamera(
            5, 50.8011, 4.3500, 0.0, 50, null,
            com.mihai.navhud.alerts.SpeedCamera.Kind.FIXED)
        val t = FreeTracker()
        t.area = Area(50.802, 4.35, 2000.0,
            listOf(northSouth(0.0, "Chaussée de Wavre", 50)), listOf(behind), 0L)
        // Driving north past it...
        t.update(50.8020, 4.3500, 12f, 0f, true, 1000L, CameraPolicy.EXACT)
        assertNull(t.alert)
        // ...then stopping at a red light. The GPS bearing goes to noise, but
        // the camera is still behind us.
        t.update(50.8021, 4.3500, 0f, null, true, 6000L, CameraPolicy.EXACT)
        assertNull("the last known heading still applies", t.alert)
    }

    @Test fun `the same camera warns again on the way home`() {
        val cam = com.mihai.navhud.alerts.SpeedCamera(
            5, 50.8045, 4.3500, 0.0, 50, null,
            com.mihai.navhud.alerts.SpeedCamera.Kind.FIXED)
        val t = FreeTracker()
        t.area = Area(50.802, 4.35, 2000.0, emptyList(), listOf(cam), 0L)

        t.update(50.8020, 4.3500, 12f, 0f, true, 1000L, CameraPolicy.EXACT)
        assertTrue(t.shouldAnnounce(t.alert!!))
        t.update(50.8021, 4.3500, 12f, 0f, true, 3000L, CameraPolicy.EXACT)
        assertTrue("not twice on the same approach", !t.shouldAnnounce(t.alert!!))

        // Ten minutes later, coming back the other way past the same camera.
        t.update(50.8020, 4.3500, 12f, 0f, true, 600_000L, CameraPolicy.EXACT)
        assertTrue("a return trip deserves the warning again", t.shouldAnnounce(t.alert!!))
    }
}
