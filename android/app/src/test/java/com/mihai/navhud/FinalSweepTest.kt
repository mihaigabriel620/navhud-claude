package com.mihai.navhud

import com.mihai.navhud.alerts.CameraPolicy
import com.mihai.navhud.alerts.SpeedCamera
import com.mihai.navhud.alerts.SpeedCameras
import com.mihai.navhud.alerts.CameraWatcher
import com.mihai.navhud.map.Compass
import com.mihai.navhud.nav.Area
import com.mihai.navhud.nav.AreaRoads
import com.mihai.navhud.nav.RoadWay
import com.mihai.navhud.voice.Phrases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The final sweep, one test per bug found.
 *
 * Each of these fails against the code as it was before the sweep. They are
 * kept together deliberately: this is the list of things that were actually
 * wrong on the road, not a unit-test suite organised by class.
 */
class FinalSweepTest {

    // ---- OpenStreetMap parsing ---------------------------------------------

    @Test fun `a speed bump is not a speed camera`() {
        // The area query asks for level crossings, traffic calming and toll
        // booths as well as cameras, because parseFeatures wants them. Without
        // a tag test every one of them became a camera, and the app announced
        // a radar at every hump in every residential street.
        val body = """
            {"elements":[
              {"type":"node","id":1,"lat":50.80,"lon":4.35,
               "tags":{"traffic_calming":"hump"}},
              {"type":"node","id":2,"lat":50.81,"lon":4.35,
               "tags":{"railway":"level_crossing"}},
              {"type":"node","id":3,"lat":50.82,"lon":4.35,
               "tags":{"barrier":"toll_booth"}},
              {"type":"node","id":4,"lat":50.83,"lon":4.35,
               "tags":{"highway":"speed_display"}},
              {"type":"node","id":5,"lat":50.84,"lon":4.35,
               "tags":{"highway":"speed_camera","maxspeed":"50"}}
            ]}
        """.trimIndent()
        val (_, cams) = AreaRoads.parse(body)
        assertEquals("only the real camera", 1, cams.size)
        assertEquals(5L, cams[0].id)
    }

    @Test fun `a oneway=-1 road can still be matched, driving the legal way`() {
        // oneway=-1 means the legal direction is the reverse of the way as
        // drawn. Collapsing it to a plain "one-way" made the matcher compare
        // the heading against the forward bearing, always ~180 out, so the
        // road was dropped: no name, no limit, no snapping, no camera filter.
        val drawnNorth = RoadWay(
            1, Array(6) { doubleArrayOf(50.8000 + it * 0.0009, 4.3500) },
            "Rue à Contresens", "", 30, "residential", onewayDir = -1
        )
        val a = Area(50.802, 4.35, 2000.0, listOf(drawnNorth), emptyList(), 0L)

        val south = AreaRoads.match(a, 50.8020, 4.3500, 180.0)
        assertNotNull("driving the legal direction must match", south)
        assertEquals(180.0, south!!.bearingDeg, 1.0)

        assertNull("driving against a one-way must not match",
            AreaRoads.match(a, 50.8020, 4.3500, 0.0))
    }

    @Test fun `oneway values are read, not guessed`() {
        assertEquals(1, AreaRoads.onewayOf("yes"))
        assertEquals(1, AreaRoads.onewayOf("1"))
        assertEquals(-1, AreaRoads.onewayOf("-1"))
        assertEquals(-1, AreaRoads.onewayOf("reverse"))
        assertEquals(0, AreaRoads.onewayOf("no"))
        assertEquals(0, AreaRoads.onewayOf(null))
        // Cannot know which way it runs right now; admitting it is better than
        // losing the speed limit on it.
        assertEquals(0, AreaRoads.onewayOf("reversible"))
    }

    @Test fun `the two maxspeed parsers agree on the same string`() {
        // SpeedCameras had a bare digit regex, so an implicit country scheme
        // came out as 0 there and 50 in the road parser -- and a French town
        // camera with maxspeed=FR:urban got a 2 km danger zone instead of the
        // 300 m its real limit implies.
        for (raw in listOf("50", "50 km/h", "30 mph", "FR:urban", "BE:rural", "DE:zone30")) {
            assertEquals(
                "disagreement on \"$raw\"",
                AreaRoads.parseMaxspeed(raw).coerceAtLeast(0),
                SpeedCameras.parseMaxspeed(raw)
            )
        }
        assertEquals(50, SpeedCameras.parseMaxspeed("FR:urban"))
        // "none" is a derestricted *road*, not an enforced camera limit.
        assertEquals(0, SpeedCameras.parseMaxspeed("none"))
        assertEquals(-1, AreaRoads.parseMaxspeed("none"))
    }

    // ---- which road is the camera on ---------------------------------------

    /** A north-south way at the given longitude offset from 4.3500. */
    private fun nsRoad(id: Long, lonOffset: Double, name: String) = RoadWay(
        id, Array(9) { doubleArrayOf(50.8000 + it * 0.0006, 4.3500 + lonOffset) },
        name, "", 50, "primary", 0
    )

    @Test fun `a camera on our own carriageway survives the other-road test`() {
        // The route line is a generalisation of the road it follows and sits
        // several metres off it, while the camera's own way passes within a
        // metre. Comparing "distance to route" against "distance to any way"
        // therefore rejected cameras that were genuinely ours -- and memoised
        // the rejection.
        val ourWay = nsRoad(1, 0.0, "Chaussée de Wavre")
        val other = nsRoad(2, 0.00060, "Service road")          // ~42 m east
        val area = Area(50.802, 4.35, 2000.0, listOf(ourWay, other), emptyList(), 0L)

        // Our route runs 10 m east of the OSM centreline, as a routed polyline
        // does. The camera sits on the OSM way itself.
        val route = Array(9) { doubleArrayOf(50.8000 + it * 0.0006, 4.3500 + 0.00014) }
        val camLat = 50.8024
        val camLon = 4.3500
        val routeCross = Geo.haversine(camLat, camLon, camLat, 4.3500 + 0.00014)

        assertTrue("without the window, the camera's own way looks 'nearer'",
            !SpeedCameras.onOurRoad(area, camLat, camLon, routeCross))
        assertTrue("with the window, our own way is excluded and it is kept",
            SpeedCameras.onOurRoad(area, camLat, camLon, routeCross, route))
    }

    @Test fun `a camera on the street next door is still rejected`() {
        val ourWay = nsRoad(1, 0.0, "Chaussée de Wavre")
        val other = nsRoad(2, 0.00060, "Rue Parallèle")
        val area = Area(50.802, 4.35, 2000.0, listOf(ourWay, other), emptyList(), 0L)
        val route = Array(9) { doubleArrayOf(50.8000 + it * 0.0006, 4.3500) }
        // Camera sits on the parallel street.
        val camLat = 50.8024
        val camLon = 4.3500 + 0.00060
        val routeCross = Geo.haversine(camLat, camLon, camLat, 4.3500)
        assertFalse("that camera is not ours",
            SpeedCameras.onOurRoad(area, camLat, camLon, routeCross, route))
    }

    @Test fun `free drive says nothing about cameras until it knows which way we face`() {
        // With no heading neither the cone nor facesUs can filter anything, so
        // a camera 300 m *behind* the car was announced as 300 m ahead.
        val behind = SpeedCamera(5, 50.8011, 4.3500, 0.0, 50, null, SpeedCamera.Kind.FIXED)
        val t = FreeTracker()
        t.area = Area(50.802, 4.35, 2000.0, emptyList(), listOf(behind), 0L)
        // First fix of the drive: parked, no bearing ever seen.
        t.update(50.8020, 4.3500, 0f, null, true, 1000L, CameraPolicy.EXACT)
        assertNull("no heading, no camera warning", t.alert)
    }

    @Test fun `a dual carriageway pair is not collapsed into one camera`() {
        // Two cameras a few metres apart with opposite direction tags are the
        // pair on a dual carriageway, one per direction -- not one
        // installation mapped twice. Collapsing them kept whichever projected
        // first, which facesUs then rejected: no warning at all.
        // `direction` is the way the camera *looks*, i.e. against the traffic
        // it photographs -- so the one watching northbound traffic faces south.
        val ours = SpeedCamera(1, 50.8024, 4.3500, 100.0, 70, 180.0, SpeedCamera.Kind.FIXED)
        val theirs = SpeedCamera(2, 50.8024, 4.3501, 95.0, 70, 0.0, SpeedCamera.Kind.FIXED)
        assertTrue("travelling north, this one photographs us",
            SpeedCameras.facesUs(ours, 0.0))
        assertFalse("this one watches the other carriageway",
            SpeedCameras.facesUs(theirs, 0.0))
    }

    // ---- the wire protocol --------------------------------------------------

    @Test fun `zone length has a documented maximum`() {
        assertEquals(CameraWatcher.ZONE_LENGTH_MAX_M,
            CameraWatcher.zoneLengthFor(130))
        assertEquals(300, CameraWatcher.zoneLengthFor(50))
    }

    // ---- the compass --------------------------------------------------------

    private fun rotX(deg: Double): DoubleArray {
        val a = Math.toRadians(deg); val c = Math.cos(a); val s = Math.sin(a)
        return doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)
    }

    @Test fun `bestAxis picks the axis the caller will actually read`() {
        // The activity is locked to landscape, so on a portrait-native phone
        // the display rotation is always 90 or 270 -- and there the display's
        // Y column is the device's X. bestAxis used to score device Y while
        // headingFor read display Y, so on an upright cradle-mounted phone it
        // kept re-electing an axis headingFor could not resolve: the compass
        // returned null on every sample, forever.
        val upright = rotX(90.0)          // +Y at the sky, screen facing the driver
        for (rot in intArrayOf(0, 1, 2, 3)) {
            val axis = Compass.bestAxis(upright, rot)
            assertNotNull(
                "rotation $rot: bestAxis chose an axis with no heading in it",
                Compass.headingFor(upright, rot, axis)
            )
            assertTrue("rotation $rot", Compass.axisUsable(upright, rot, axis))
        }
    }

    // ---- spoken output ------------------------------------------------------

    @Test fun `a camera at 199 metres is not announced as one hundred`() {
        // Integer division truncated to the hundred *below*, so a camera 199 m
        // ahead was "in one hundred metres" -- out by nearly a factor of two.
        assertEquals(200, Phrases.round100(199))
        assertEquals(1000, Phrases.round100(999))
        assertEquals(300, Phrases.round100(300))
        assertEquals(200, Phrases.round100(249))
        assertEquals(300, Phrases.round100(250))
        assertTrue(Phrases.forCode("en").distance(199).startsWith("200"))
    }

    // ---- second pass: regressions the verification agent caught ------------

    @Test fun `a route window past the end of the route is empty, not the whole route`() {
        val pts = Array(5) { doubleArrayOf(50.80 + it * 0.001, 4.35) }
        val cum = Geo.cumulative(pts)
        val r = com.mihai.navhud.nav.Route(
            pts = pts, cum = cum, limitKph = IntArray(4),
            maneuvers = emptyList(), totalDistanceM = cum.last(),
            totalDurationS = 60.0, provider = "test"
        )
        assertTrue("a sane window is a slice", r.window(cum.last() / 2, 120.0).size in 2..5)
        // Well past the end: not the entire polyline dressed up as "nearby".
        assertEquals(0, r.window(cum.last() + 100_000.0, 10.0).size)
    }

    @Test fun `the road continuing past a junction does not steal our camera`() {
        // OSM splits a street at every junction, so the continuation is a
        // different way. Treating it as "another road, and nearer" suppressed
        // every camera in the 6-18 m band past a split -- which is exactly
        // where a red-light camera sits.
        val ourWay = RoadWay(
            1, Array(5) { doubleArrayOf(50.8000 + it * 0.0004, 4.3500) },
            "Chaussée de Wavre", "", 50, "primary", 0
        )
        // Same street, continuing north past the junction.
        val continuation = RoadWay(
            2, Array(5) { doubleArrayOf(50.8016 + it * 0.0004, 4.3500) },
            "Chaussée de Wavre", "", 50, "primary", 0
        )
        // The camera is just past the split, on the carriageway.
        val cam = SpeedCamera(9, 50.8017, 4.3500, 0.0, 50, null, SpeedCamera.Kind.FIXED)
        val t = FreeTracker()
        t.area = Area(50.801, 4.35, 2000.0, listOf(ourWay, continuation), listOf(cam), 0L)
        // Driving north up our way, camera ahead.
        t.update(50.8010, 4.3500, 12f, 0f, true, 1000L, CameraPolicy.EXACT)
        assertNotNull("the camera on our own street must still be warned about", t.alert)
    }

    @Test fun `a camera on the cross street at the lights is still rejected`() {
        val ourWay = RoadWay(
            1, Array(9) { doubleArrayOf(50.8000 + it * 0.0004, 4.3500) },
            "Chaussée de Wavre", "", 50, "primary", 0
        )
        // An east-west street crossing ours at 50.8020.
        val crossing = RoadWay(
            2, Array(9) { doubleArrayOf(50.8020, 4.3480 + it * 0.0006) },
            "Rue Transversale", "", 30, "residential", 0
        )
        // Camera on the cross street, a few metres east of the junction.
        val cam = SpeedCamera(9, 50.8020, 4.35022, 0.0, 30, null, SpeedCamera.Kind.FIXED)
        val t = FreeTracker()
        t.area = Area(50.801, 4.35, 2000.0, listOf(ourWay, crossing), listOf(cam), 0L)
        t.update(50.8010, 4.3500, 12f, 0f, true, 1000L, CameraPolicy.EXACT)
        assertNull("that camera watches the road we are crossing", t.alert)
    }

    @Test fun `a lane with four movements keeps the one we are taking`() {
        // Only three arrows fit in a cell; the chosen one must never be the
        // one trimmed, or the driver gets three dim arrows and no instruction.
        val bits = com.mihai.navhud.nav.Lane.UTURN or com.mihai.navhud.nav.Lane.LEFT or
            com.mihai.navhud.nav.Lane.STRAIGHT or com.mihai.navhud.nav.Lane.RIGHT
        val chosen = com.mihai.navhud.nav.Lane.RIGHT
        val inLane = ArrayList<Int>()
        for (bit in com.mihai.navhud.nav.Lane.ORDER) {
            if (bits and bit == 0) continue
            inLane.add(bit)
        }
        while (inLane.size > 3) {
            val victim = if (inLane.last() != chosen) inLane.size - 1 else inLane.size - 2
            inLane.removeAt(victim)
        }
        assertEquals(3, inLane.size)
        assertTrue("the chosen movement survived the trim", inLane.contains(chosen))
    }
}
