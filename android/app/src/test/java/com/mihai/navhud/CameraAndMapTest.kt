package com.mihai.navhud

import com.mihai.navhud.alerts.CameraPolicy
import com.mihai.navhud.alerts.CameraWatcher
import com.mihai.navhud.alerts.CountryRules
import com.mihai.navhud.alerts.SpeedCamera
import com.mihai.navhud.alerts.SpeedCameras
import com.mihai.navhud.map.NavCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The camera-warning rules and the map camera. The first of these is a legal
 * constraint, not a feature, so it gets tested like one: the interesting cases
 * are all about what the app must refuse to say.
 */
class CameraAndMapTest {

    // ---- country rules -----------------------------------------------------

    @Test fun `the countries whose rules were actually checked`() {
        assertEquals(CameraPolicy.EXACT, CountryRules.policyFor("BE"))
        assertEquals(CameraPolicy.ZONE, CountryRules.policyFor("FR"))
        assertEquals(CameraPolicy.OFF, CountryRules.policyFor("DE"))
        assertEquals(CameraPolicy.OFF, CountryRules.policyFor("CH"))
    }

    @Test fun `unknown countries fall back to the conservative default`() {
        // Slovakia: detectors banned, app warnings unclear -- so unverified.
        // (Romania was the example here until its rule was checked: ADAC.)
        assertEquals(CameraPolicy.ZONE, CountryRules.policyFor("SK"))
        assertEquals(CameraPolicy.ZONE, CountryRules.policyFor(null))
        assertEquals(CameraPolicy.ZONE, CountryRules.policyFor("ZZ"))
        assertFalse(CountryRules.isVerified("SK"))
    }

    @Test fun `the user can tighten the rules but never loosen them`() {
        // Germany: nothing the user asks for turns alerts back on.
        assertEquals(CameraPolicy.OFF, CountryRules.effective("DE", CameraPolicy.EXACT))
        assertEquals(CameraPolicy.OFF, CountryRules.effective("DE", CameraPolicy.ZONE))
        // France: exact positions stay unavailable however they are requested.
        assertEquals(CameraPolicy.ZONE, CountryRules.effective("FR", CameraPolicy.EXACT))
        assertEquals(CameraPolicy.OFF, CountryRules.effective("FR", CameraPolicy.OFF))
        // Belgium: the user's choice is honoured in both directions.
        assertEquals(CameraPolicy.EXACT, CountryRules.effective("BE", CameraPolicy.EXACT))
        assertEquals(CameraPolicy.ZONE, CountryRules.effective("BE", CameraPolicy.ZONE))
        assertEquals(CameraPolicy.OFF, CountryRules.effective("BE", CameraPolicy.OFF))
        // No preference means the local rule applies.
        assertEquals(CameraPolicy.EXACT, CountryRules.effective("BE", null))
        assertEquals(CameraPolicy.ZONE, CountryRules.effective("FR", null))
    }

    // ---- OSM parsing -------------------------------------------------------

    @Test fun `maxspeed tags in every shape OSM uses`() {
        assertEquals(50, SpeedCameras.parseMaxspeed("50"))
        assertEquals(50, SpeedCameras.parseMaxspeed("50 km/h"))
        assertEquals(48, SpeedCameras.parseMaxspeed("30 mph"))
        assertEquals(0, SpeedCameras.parseMaxspeed(null))
        assertEquals(0, SpeedCameras.parseMaxspeed(""))
        assertEquals(0, SpeedCameras.parseMaxspeed("signals"))
    }

    @Test fun `direction tags may be degrees or a compass point`() {
        assertEquals(90.0, SpeedCameras.parseDirection("90")!!, 1e-9)
        assertEquals(270.0, SpeedCameras.parseDirection("W")!!, 1e-9)
        assertEquals(22.5, SpeedCameras.parseDirection("NNE")!!, 1e-9)
        assertEquals(10.0, SpeedCameras.parseDirection("370")!!, 1e-9)
        assertNull(SpeedCameras.parseDirection("forward"))
        assertNull(SpeedCameras.parseDirection(null))
    }

    @Test fun `an Overpass response is parsed and pinned to the route`() {
        val route = DemoDrive.buildRoute()
        // Two cameras on the line, one 300 m off it on a parallel road.
        val on1 = Geo.pointAlong(route.pts, route.cum, 1200.0)
        val on2 = Geo.pointAlong(route.pts, route.cum, 5000.0)
        val offRoute = Geo.destination(on1[0], on1[1], 90.0, 300.0)
        val json = """
        {"elements":[
          {"type":"node","id":1,"lat":${on1[0]},"lon":${on1[1]},
           "tags":{"highway":"speed_camera","maxspeed":"70","direction":"250"}},
          {"type":"node","id":2,"lat":${on2[0]},"lon":${on2[1]},
           "tags":{"highway":"speed_camera","camera:type":"section","maxspeed":"120 km/h"}},
          {"type":"node","id":3,"lat":${offRoute[0]},"lon":${offRoute[1]},
           "tags":{"highway":"speed_camera"}},
          {"type":"way","id":4,"tags":{"highway":"speed_camera"}}
        ]}
        """.trimIndent()

        val cams = SpeedCameras.parse(json, route)
        assertEquals("the off-route camera and the way must both be dropped", 2, cams.size)
        assertEquals(1L, cams[0].id)
        assertEquals(70, cams[0].limitKph)
        assertEquals(250.0, cams[0].directionDeg!!, 1e-9)
        assertEquals(SpeedCamera.Kind.FIXED, cams[0].kind)
        assertEquals(SpeedCamera.Kind.AVERAGE, cams[1].kind)
        assertEquals(120, cams[1].limitKph)
        assertTrue("cameras must come out in route order", cams[0].alongM < cams[1].alongM)
        assertEquals(1200.0, cams[0].alongM, 30.0)
    }

    @Test fun `a camera facing the other carriageway is ignored`() {
        fun cam(dir: Double?) = SpeedCamera(1, 0.0, 0.0, 0.0, 50, dir, SpeedCamera.Kind.FIXED)
        // Driving north (0). A camera watching northbound traffic looks south (180).
        assertTrue(SpeedCameras.facesUs(cam(180.0), 0.0))
        assertFalse("a camera looking north watches southbound traffic",
            SpeedCameras.facesUs(cam(0.0), 0.0))
        // Untagged cameras are always kept: a missed warning is worse than a spurious one.
        assertTrue(SpeedCameras.facesUs(cam(null), 0.0))
        assertTrue(SpeedCameras.facesUs(cam(180.0), null))
    }

    // ---- alert timing ------------------------------------------------------

    private fun watcherFor(policy: CameraPolicy): Pair<CameraWatcher, SpeedCamera> {
        val cam = SpeedCamera(7, 0.0, 0.0, 5000.0, 70, null, SpeedCamera.Kind.FIXED)
        return CameraWatcher(listOf(cam), policy) to cam
    }

    @Test fun `exact mode counts down, zone mode does not give a position`() {
        val (w, _) = watcherFor(CameraPolicy.EXACT)
        assertNull("too far away to warn yet", w.update(3000.0, 120, null))
        val a = w.update(4300.0, 120, null)
        assertNotNull(a)
        assertFalse(a!!.zoneMode)
        assertEquals(700, a.distanceM)

        val (wz, _) = watcherFor(CameraPolicy.ZONE)
        val z = wz.update(3500.0, 120, null)
        assertNotNull("zone mode warns further out", z)
        assertTrue(z!!.zoneMode)
    }

    @Test fun `policy OFF never produces an alert at any distance`() {
        val (w, _) = watcherFor(CameraPolicy.OFF)
        for (d in 0..5000 step 100) assertNull(w.update(d.toDouble(), 120, null))
    }

    @Test fun `a camera is announced once per stage, the way Waze repeats`() {
        val (w, _) = watcherFor(CameraPolicy.EXACT)
        val stages = ArrayList<Int>()
        var d = 4000.0
        while (d < 5200.0) {
            w.update(d, 120, null)?.let { if (w.shouldAnnounce(it)) stages.add(it.stage) }
            d += 20.0
        }
        // warn, remind, chirp: three announcements, in order, none repeated
        assertEquals("expected three staged announcements, got $stages", 3, stages.size)
        assertEquals(listOf(0, 1, 2), stages)
        assertNull("still alerting after passing it", w.update(5200.0, 120, null))
    }

    @Test fun `the stages sit where a driver would want them`() {
        // Motorway: 1000 / 500 / 200 m, like Waze -- the owner's choice in 1.28.
        assertEquals("no warning beyond the first stage", -1, stageOrNone(1100, 120))
        assertEquals("first warning at 1000 m", 0, CameraWatcher.stageFor(1000, 120))
        assertEquals("still stage 0 at 600 m", 0, CameraWatcher.stageFor(600, 120))
        assertEquals("reminder at 500 m", 1, CameraWatcher.stageFor(500, 120))
        assertEquals("last reminder at 200 m", 2, CameraWatcher.stageFor(200, 120))
        assertEquals("and right on top of it", 2, CameraWatcher.stageFor(0, 120))
    }

    /** -1 when the distance is beyond the first stage for that speed. */
    private fun stageOrNone(d: Int, kph: Int): Int =
        if (d > CameraWatcher.warnDistance(kph)) -1 else CameraWatcher.stageFor(d, kph)

    @Test fun `the last motorway reminder is early enough to be useful`() {
        // 200 m at 130 km/h is about six seconds -- time to glance down, not
        // time to stamp on the brakes. Anything under 100 m would be startling.
        assertEquals("not yet the final stage at 210 m", 1, CameraWatcher.stageFor(210, 130))
        assertEquals("final stage lands at 200 m", 2, CameraWatcher.stageFor(200, 130))
    }

    @Test fun `warnings come earlier the faster you are going`() {
        assertTrue(CameraWatcher.warnDistance(120) > CameraWatcher.warnDistance(70))
        assertTrue(CameraWatcher.warnDistance(70) > CameraWatcher.warnDistance(30))
        // ...and every band still gives a genuine head start in seconds.
        for ((kph, minSeconds) in listOf(130 to 25.0, 70 to 25.0, 50 to 20.0)) {
            val seconds = CameraWatcher.warnDistance(kph) / (kph / 3.6)
            assertTrue("only ${"%.0f".format(seconds)} s of warning at $kph km/h",
                seconds >= minSeconds)
        }
    }

    @Test fun `staging never goes backwards as you approach`() {
        for (kph in listOf(30, 50, 70, 90, 120, 130)) {
            var prev = -1
            for (d in CameraWatcher.warnDistance(kph) downTo 0 step 5) {
                val st = CameraWatcher.stageFor(d, kph)
                assertTrue("stage went backwards at $d m, $kph km/h: $prev -> $st", st >= prev)
                prev = st
            }
            assertEquals("every band ends on the final stage", 2, prev)
        }
    }

    @Test fun `the voice quotes the stage distance, never the live one`() {
        // The drive: the last call said "in 13 m". A stage quotes its own round
        // number; a stage reached late is rounded; under 50 m, no number.
        assertEquals(1000, CameraWatcher.spokenDistance(987, 120))
        assertEquals(500, CameraWatcher.spokenDistance(463, 120))
        assertEquals(200, CameraWatcher.spokenDistance(181, 120))
        assertEquals("reached late: rounded, not the stage's 200", 100,
            CameraWatcher.spokenDistance(88, 120))
        assertEquals(50, CameraWatcher.spokenDistance(55, 120))
        assertEquals("too close for a number", 0, CameraWatcher.spokenDistance(13, 120))
        assertEquals(0, CameraWatcher.spokenDistance(49, 30))
        val cam = SpeedCamera(7, 0.0, 0.0, 5000.0, 70, null, SpeedCamera.Kind.FIXED)
        val a = CameraWatcher(listOf(cam), CameraPolicy.EXACT).update(4987.0, 120, null)
        assertEquals(0, a!!.spokenM)
        assertEquals("the HUD still shows the live distance", 13, a.distanceM)
    }

    // ---- duplicates and the camera after the one just passed ----------------

    private fun cam(id: Long, along: Double, dir: Double? = null,
                    kind: SpeedCamera.Kind = SpeedCamera.Kind.FIXED, limit: Int = 70,
                    lat: Double = 0.0, lon: Double = 0.0) =
        SpeedCamera(id, lat, lon, along, limit, dir, kind)

    @Test fun `two cameras 30 m apart facing the same way are one camera, warned once`() {
        // OSM often has the same device twice. It gave two sets of warnings,
        // and the second one's last call came under the camera: "in 13 m".
        val w = CameraWatcher(listOf(cam(1, 5000.0), cam(2, 5030.0, limit = 0)), CameraPolicy.EXACT)
        assertEquals("merged into one", listOf(1L), w.cameras.map { it.id })
        assertEquals("the limit survives the merge", 70, w.cameras[0].limitKph)

        val said = ArrayList<Pair<Int, Int>>()          // stage, spoken metres
        var d = 3800.0
        while (d < 5300.0) {
            w.update(d, 120, null)?.let { if (w.shouldAnnounce(it)) said.add(it.stage to it.spokenM) }
            d += 5.0
        }
        assertEquals("warn, remind, last call -- once each", listOf(0, 1, 2), said.map { it.first })
        assertEquals(listOf(1000, 500, 200), said.map { it.second })
    }

    @Test fun `cameras 30 m apart that are not the same device are both kept`() {
        // One for each direction of a dual carriageway.
        assertEquals(2, CameraWatcher(listOf(cam(1, 5000.0, dir = 0.0), cam(2, 5030.0, dir = 180.0)),
            CameraPolicy.EXACT).cameras.size)
        // Tagged and untagged: the untagged one may watch the other direction.
        assertEquals(2, CameraWatcher(listOf(cam(1, 5000.0, dir = 0.0), cam(2, 5030.0)),
            CameraPolicy.EXACT).cameras.size)
        // A number-plate camera is a different warning from a speed camera.
        assertEquals(2, CameraWatcher(listOf(cam(1, 5000.0),
            cam(2, 5030.0, kind = SpeedCamera.Kind.ANPR)), CameraPolicy.EXACT).cameras.size)
        // Same direction, but 60 m apart: two cameras.
        assertEquals(2, CameraWatcher(listOf(cam(1, 5000.0, dir = 10.0), cam(2, 5060.0, dir = 30.0)),
            CameraPolicy.EXACT).cameras.size)
        assertEquals(1, CameraWatcher(listOf(cam(1, 5000.0, dir = 10.0), cam(2, 5030.0, dir = 30.0)),
            CameraPolicy.EXACT).cameras.size)
    }

    @Test fun `the next camera takes over the moment the last one is passed`() {
        val w = CameraWatcher(listOf(cam(1, 5000.0), cam(2, 5150.0)), CameraPolicy.EXACT)
        val a = w.update(5001.0, 120, null)!!
        assertEquals("not held on the camera just passed", 2L, a.camera.id)
        assertFalse(a.passed)
        assertTrue(w.shouldAnnounce(a))
        assertEquals(200, a.spokenM)
    }

    @Test fun `a camera just passed is still shown but never spoken`() {
        val w = CameraWatcher(listOf(cam(1, 5000.0)), CameraPolicy.EXACT)
        val a = w.update(5010.0, 120, null)!!
        assertTrue(a.passed)
        assertFalse("never announced behind the car", w.shouldAnnounce(a))
        assertNull(w.update(5041.0, 120, null))
    }

    @Test fun `free drive merges duplicates by position`() {
        // 30 m north of each other: 30 / 111320 degrees of latitude.
        val north30 = 30.0 / 111_320.0
        val merged = SpeedCameras.dedupeByPosition(listOf(
            cam(1, 0.0, lat = 50.8, lon = 4.35), cam(2, 0.0, lat = 50.8 + north30, lon = 4.35)))
        assertEquals(listOf(1L), merged.map { it.id })
        val apart = SpeedCameras.dedupeByPosition(listOf(
            cam(1, 0.0, lat = 50.8, lon = 4.35), cam(2, 0.0, lat = 50.8 + 2 * north30, lon = 4.35)))
        assertEquals(2, apart.size)
    }

    // ---- map camera --------------------------------------------------------

    @Test fun `zoom pulls back as speed rises`() {
        val slow = NavCamera.zoomForSpeed(0.0)
        val town = NavCamera.zoomForSpeed(50.0)
        val fast = NavCamera.zoomForSpeed(130.0)
        assertTrue("$slow > $town > $fast", slow > town && town > fast)
        // and it is monotonic all the way, not just at the ends
        var prev = Double.MAX_VALUE
        for (kph in 0..160 step 5) {
            val z = NavCamera.zoomForSpeed(kph.toDouble())
            assertTrue("zoom went up at $kph", z <= prev + 1e-9)
            prev = z
        }
    }

    @Test fun `the camera turns the short way round, never the long way`() {
        assertEquals(20.0, NavCamera.shortestTurn(350.0, 10.0), 1e-9)
        assertEquals(-20.0, NavCamera.shortestTurn(10.0, 350.0), 1e-9)
        assertEquals(0.0, NavCamera.shortestTurn(90.0, 90.0), 1e-9)
    }

    @Test fun `heading is frozen when stopped, so the map cannot spin at a light`() {
        val c = NavCamera()
        c.update(20.0, 90.0)                 // driving east
        val settled = c.bearing
        repeat(20) { c.update(0.0, 275.0) }  // parked, GPS heading now nonsense
        assertEquals("bearing drifted while stationary", settled, c.bearing, 1e-9)
    }

    @Test fun `heading is smoothed through a bend and swings round a U-turn`() {
        val c = NavCamera()
        c.update(20.0, 0.0)
        c.update(20.0, 30.0)
        assertTrue("should not jump the whole way in one update",
            c.bearing > 0.0 && c.bearing < 30.0)

        // A reversal used to be taken in one frame, flipping the whole map.
        // It now swings at MAX_TURN_DPS: a quarter of the way per 0.25 s...
        val c2 = NavCamera()
        c2.update(20.0, 0.0)
        c2.update(20.0, 179.0, dtSeconds = 0.25)
        assertEquals(NavCamera.MAX_TURN_DPS * 0.25, c2.bearing, 1e-6)
        // ...and is all the way round in about a second, not crawling.
        repeat(8) { c2.update(20.0, 179.0, dtSeconds = 0.25) }
        assertEquals(179.0, c2.bearing, 2.0)
    }

    @Test fun `no turn is ever faster than the cap, whatever the frame rate`() {
        for (dt in listOf(1.0 / 60, 1.0 / 30, 0.25)) {
            var b = 10.0
            repeat(100) {
                val next = NavCamera.turnToward(b, 200.0, dt, 0.2)
                assertTrue(kotlin.math.abs(NavCamera.shortestTurn(b, next)) <=
                    NavCamera.MAX_TURN_DPS * dt + 1e-9)
                b = next
            }
            assertEquals(200.0, b, 0.5)
        }
    }

    @Test fun `tilt is constant, moving or stopped`() {
        // It used to ramp from flat when parked to 55 degrees at speed, which
        // meant the map heaved from plan view to perspective at every set of
        // lights. Waze holds one tilt and never touches it; so do we.
        val c = NavCamera()
        repeat(60) { c.update(30.0, 0.0) }
        assertEquals(NavCamera.TILT, c.tilt, 0.001)
        repeat(120) { c.update(0.0, 0.0) }
        assertEquals(NavCamera.TILT, c.tilt, 0.001)
        repeat(120) { c.update(36.0, 0.0) }
        assertEquals(NavCamera.TILT, c.tilt, 0.001)
    }

    @Test fun `overview pulls back to the shallowest allowed tilt, not to flat`() {
        val c = NavCamera()
        repeat(60) { c.update(20.0, 0.0) }
        repeat(120) { c.update(20.0, 0.0, overview = true) }
        assertEquals(NavCamera.TILT_MIN, c.tilt, 0.5)
        repeat(120) { c.update(20.0, 0.0) }
        assertEquals(NavCamera.TILT, c.tilt, 0.5)
    }

    @Test fun `the driving tilt sits inside the range a gesture may reach`() {
        assertTrue(NavCamera.TILT_MIN < NavCamera.TILT)
        assertTrue(NavCamera.TILT < NavCamera.TILT_MAX)
        // Flat is not reachable: that is the whole point of the clamp.
        assertTrue(NavCamera.TILT_MIN > 15.0)
    }

    @Test fun `a pinch while following is kept as an offset on the speed zoom`() {
        val c = NavCamera()
        repeat(60) { c.update(10.0, 0.0) }
        val auto = c.zoom
        c.setUserZoom(auto + 1.0)                 // pinched in one level
        repeat(200) { c.update(10.0, 0.0) }
        assertEquals("the pinch must survive the next frames", auto + 1.0, c.zoom, 0.01)
        // ...and still applies after speeding up.
        repeat(400) { c.update(30.0, 0.0) }
        assertEquals(NavCamera.zoomForSpeed(108.0) + 1.0, c.zoom, 0.01)
        c.reset()
        assertEquals(0.0, c.userZoomOffset, 1e-9)
    }

    @Test fun `a tilt gesture while following is kept, inside the allowed range`() {
        val c = NavCamera()
        c.setUserTilt(50.0)
        repeat(100) { c.update(20.0, 0.0) }
        assertEquals(50.0, c.tilt, 0.01)
        c.setUserTilt(5.0)
        repeat(100) { c.update(20.0, 0.0) }
        assertEquals(NavCamera.TILT_MIN, c.tilt, 0.01)
        c.reset()
        repeat(100) { c.update(20.0, 0.0) }
        assertEquals("recentring goes back to the standard tilt", NavCamera.TILT, c.tilt, 0.01)
    }

    @Test fun `facing a bearing takes effect at once, without easing`() {
        val c = NavCamera()
        repeat(20) { c.update(0.0, 10.0) }
        c.faceBearing(200.0)
        assertEquals(200.0, c.bearing, 0.001)
    }

    @Test fun `the puck sits low on the screen, not in the middle`() {
        val c = NavCamera()
        val h = 1000
        val pad = c.topPaddingPx(h)
        assertTrue("padding should push the target down, got $pad", pad > 0)
        // centre of the padded viewport, as a fraction down the screen
        val fraction = (pad + (h - pad) / 2.0) / h
        assertEquals(NavCamera.PUCK_SCREEN_FRACTION, fraction, 0.02)
    }

    @Test fun `the camera aims ahead of the car when moving`() {
        val c = NavCamera()
        val here = doubleArrayOf(50.85, 4.35)
        val still = c.lookAhead(here[0], here[1], 0.0, 90.0)
        assertEquals(here[0], still[0], 1e-12)

        val moving = c.lookAhead(here[0], here[1], 30.0, 90.0)
        val lead = Geo.haversine(here[0], here[1], moving[0], moving[1])
        assertTrue("should look ahead, got $lead m", lead in 50.0..130.0)
        assertTrue("should look east", moving[1] > here[1])
    }

    // ---- lane guidance -----------------------------------------------------

    @Test fun `lanes are read out of a Mapbox banner`() {
        val banners = org.json.JSONArray(
            """
            [{"distanceAlongGeometry":120,
              "primary":{"text":"Exit 22","type":"off ramp","modifier":"right",
                         "components":[{"type":"exit-number","text":"22"}]},
              "sub":{"text":"","components":[
                {"type":"lane","directions":["straight"],"active":false},
                {"type":"lane","directions":["straight"],"active":false},
                {"type":"lane","directions":["straight","right"],"active":true},
                {"type":"lane","directions":["right"],"active":true}
              ]}}]
            """.trimIndent()
        )
        val g = com.mihai.navhud.nav.LaneGuidance.fromBanners(banners)
        assertNotNull(g)
        assertEquals(4, g!!.count)
        assertFalse(g.isActive(0))
        assertTrue(g.isActive(2))
        assertTrue(g.isActive(3))
        assertEquals(com.mihai.navhud.nav.Lane.STRAIGHT, g.lanes[0])
        assertEquals(
            com.mihai.navhud.nav.Lane.STRAIGHT or com.mihai.navhud.nav.Lane.RIGHT,
            g.lanes[2]
        )
        // The chosen movement per lane is appended after the permitted ones:
        // lanes 0 and 1 are not ours, lane 2 is a shared through/exit lane
        // whose chosen movement is the exit, lane 3 exits and nothing else.
        assertEquals(com.mihai.navhud.nav.Lane.RIGHT, g.chosenOf(2))
        assertEquals(com.mihai.navhud.nav.Lane.RIGHT, g.chosenOf(3))
        assertEquals(0, g.chosenOf(0))
        assertEquals("LANE,4,12,16,16,80,64,0,0,64,64", g.encodeBody())
        assertEquals(1, g.turnSide)

        val sign = com.mihai.navhud.nav.JunctionSign.fromBanners(banners, "E40", "Liège,Namur")
        assertEquals("22", sign.exitNumber)
        assertEquals("Liège / Namur", sign.destinations)
    }

    @Test fun `a step with no lane data yields nothing rather than an empty strip`() {
        assertNull(com.mihai.navhud.nav.LaneGuidance.fromBanners(null))
        val noLanes = org.json.JSONArray(
            """[{"primary":{"text":"Rue de la Loi"},"sub":{"components":[{"type":"text","text":"x"}]}}]"""
        )
        assertNull(com.mihai.navhud.nav.LaneGuidance.fromBanners(noLanes))
    }
}
