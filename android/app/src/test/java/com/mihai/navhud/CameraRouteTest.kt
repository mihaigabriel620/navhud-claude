package com.mihai.navhud

import com.mihai.navhud.alerts.CameraAlert
import com.mihai.navhud.alerts.CameraPolicy
import com.mihai.navhud.alerts.CameraWatcher
import com.mihai.navhud.alerts.SpeedCamera
import com.mihai.navhud.alerts.SpeedCameras
import com.mihai.navhud.nav.AreaCache
import com.mihai.navhud.nav.Route
import com.mihai.navhud.voice.FrenchBelgium
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Number-plate cameras, the wider corridor, reroutes and losing the network.
 */
class CameraRouteTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() { AreaCache.dir = tmp.newFolder("area") }
    @After fun tearDown() { AreaCache.dir = null }

    /** Metres east, as longitude, at these latitudes. */
    private fun east(m: Double) = m / (111_320.0 * Math.cos(Math.toRadians(50.82)))

    private fun route(vararg pts: DoubleArray): Route {
        val arr = arrayOf(*pts)
        val cum = Geo.cumulative(arr)
        return Route(arr, cum, IntArray(arr.size - 1), emptyList(), cum.last(), 600.0, "test")
    }

    /** Straight north from 50.800 to 50.840, about 4.4 km. */
    private val north = route(*Array(41) { doubleArrayOf(50.800 + it * 0.001, 4.35) })

    private fun node(id: Long, lat: Double, lon: Double, tags: String) =
        """{"type":"node","id":$id,"lat":$lat,"lon":$lon,"tags":{$tags}}"""

    private fun overpass(vararg elements: String) = """{"elements":[${elements.joinToString(",")}]}"""

    // ---- ANPR ------------------------------------------------------------

    @Test fun `the query asks for number-plate cameras as the OSM wiki tags them`() {
        val q = SpeedCameras.buildQuery(north)
        assertTrue(q, q.contains("""["man_made"="surveillance"]["surveillance:type"~"^(alpr|anpr)$",i]"""))
    }

    @Test fun `ANPR cameras are parsed as their own kind`() {
        val json = overpass(
            node(1, 50.810, 4.35, """"man_made":"surveillance","surveillance:type":"ALPR","direction":"180""""),
            node(2, 50.815, 4.35, """"man_made":"surveillance","surveillance:type":"anpr""""),
            // Enforces a speed as well: that makes it a speed camera.
            node(3, 50.820, 4.35, """"man_made":"surveillance","surveillance:type":"ALPR","highway":"speed_camera","maxspeed":"70""""),
            // An ordinary CCTV camera is not wanted.
            node(4, 50.825, 4.35, """"man_made":"surveillance","surveillance:type":"guard"""")
        )
        val cams = SpeedCameras.parse(json, north)
        assertEquals(listOf(1L, 2L, 3L), cams.map { it.id })
        assertEquals(SpeedCamera.Kind.ANPR, cams[0].kind)
        assertEquals(180.0, cams[0].directionDeg!!, 1e-9)
        assertEquals(SpeedCamera.Kind.ANPR, cams[1].kind)
        assertEquals(SpeedCamera.Kind.FIXED, cams[2].kind)
        assertEquals(70, cams[2].limitKph)
    }

    @Test fun `an ANPR device of a section control is a section control`() {
        val json = overpass(
            """{"type":"relation","id":9,"members":[{"type":"node","ref":5,"role":"device"}],
                "tags":{"type":"enforcement","enforcement":"average_speed","maxspeed":"120"}}""",
            node(5, 50.812, 4.35, """"man_made":"surveillance","surveillance:type":"ALPR"""")
        )
        val cams = SpeedCameras.parse(json, north)
        assertEquals(SpeedCamera.Kind.AVERAGE, cams.single().kind)
        assertEquals(120, cams.single().limitKph)
    }

    @Test fun `an ANPR camera is announced as one, in French too`() {
        val speaker = FakeSpeaker()
        val g = VoiceGuide(null, FrenchBelgium, speaker) { 1_000_000L }
        val cam = SpeedCamera(1, 50.81, 4.35, 1000.0, 0, null, SpeedCamera.Kind.ANPR)
        g.announceCamera(CameraAlert(cam, 300, zoneMode = false))
        assertEquals(listOf("Caméra ANPR dans trois cents mètres."), speaker.texts)
    }

    // ---- corridor ----------------------------------------------------------

    @Test fun `a camera 25 m off the route line is kept, one 50 m off is not`() {
        val json = overpass(
            node(1, 50.810, 4.35 + east(25.0), """"highway":"speed_camera""""),
            node(2, 50.820, 4.35 + east(50.0), """"highway":"speed_camera"""")
        )
        val cams = SpeedCameras.parse(json, north)
        assertEquals("15 m used to drop the first one", listOf(1L), cams.map { it.id })
        assertEquals(25.0, cams[0].crossM, 1.0)
    }

    // ---- rebase ------------------------------------------------------------

    private val c1 = SpeedCamera(1, 50.809, 4.35, 0.0, 70, null, SpeedCamera.Kind.FIXED)
    private val c2 = SpeedCamera(2, 50.827, 4.35, 0.0, 70, null, SpeedCamera.Kind.FIXED)

    /** Starts just north of [north]'s start, follows it, then turns east before c2. */
    private val detour = route(
        doubleArrayOf(50.8005, 4.35), doubleArrayOf(50.805, 4.35), doubleArrayOf(50.815, 4.35),
        doubleArrayOf(50.815, 4.35 + east(1000.0)), doubleArrayOf(50.815, 4.35 + east(3000.0))
    )

    @Test fun `a reroute keeps the cameras it can and remembers what it said`() {
        val w = CameraWatcher(SpeedCameras.reproject(listOf(c1, c2), north), CameraPolicy.EXACT)
        val first = w.update(150.0, 70, null)!!                // c1 about 850 m ahead
        assertEquals(1L, first.camera.id)
        assertEquals(0, first.stage)
        assertTrue(w.shouldAnnounce(first))

        val w2 = w.rebase(detour)
        assertEquals("c2 is not on the new route", listOf(1L), w2.cameras.map { it.id })
        assertEquals(945.0, w2.cameras[0].alongM, 15.0)
        val again = w2.update(60.0, 70, null)!!
        assertEquals(0, again.stage)
        assertFalse("the warning just given is not given again", w2.shouldAnnounce(again))
        // A fresh watcher, which is what a reroute used to build, repeats it.
        assertTrue(CameraWatcher(w2.cameras, CameraPolicy.EXACT).shouldAnnounce(again))
        // The next stage still comes.
        val closer = w2.update(600.0, 70, null)!!
        assertEquals(1, closer.stage)
        assertTrue(w2.shouldAnnounce(closer))
    }

    @Test fun `a refreshed list keeps the memory for cameras still in it`() {
        val w = CameraWatcher(SpeedCameras.reproject(listOf(c1, c2), north), CameraPolicy.EXACT)
        val a = w.update(150.0, 70, null)!!
        w.shouldAnnounce(a)
        val w2 = w.successor(SpeedCameras.reproject(listOf(c1, c2), north))
        assertFalse(w2.shouldAnnounce(w2.update(150.0, 70, null)!!))
        val w3 = w.successor(SpeedCameras.reproject(listOf(c2), north))
        assertNull("c1 left the list", w3.update(150.0, 70, null))
    }

    // ---- losing the network ------------------------------------------------

    private val oneCamera = overpass(node(1, 50.810, 4.35, """"highway":"speed_camera""""))

    @Test fun `with no network the last response for this destination is used`() {
        val fresh = SpeedCameras.fetchWith(north, AreaCache) { oneCamera }
        assertEquals(listOf(1L), fresh.map { it.id })
        val offline = SpeedCameras.fetchWith(north, AreaCache) { throw IOException("no signal") }
        assertEquals(listOf(1L), offline.map { it.id })
    }

    @Test fun `a timed-out Overpass answer is a failure, not an empty road`() {
        SpeedCameras.fetchWith(north, AreaCache) { oneCamera }
        val timedOut = """{"remark":"runtime error: Query timed out in \"query\" at line 3 after 61 seconds.","elements":[]}"""
        val cams = SpeedCameras.fetchWith(north, AreaCache) { timedOut }
        assertEquals("the cached list, not zero cameras", listOf(1L), cams.map { it.id })
    }

    @Test fun `with neither network nor cache the failure reaches the caller`() {
        var threw = false
        try {
            SpeedCameras.fetchWith(north, null) { throw IOException("no signal") }
        } catch (e: IOException) { threw = true }
        assertTrue(threw)
    }

    @Test fun `a cached camera body is never served as a road network`() {
        SpeedCameras.fetchWith(north, AreaCache) { oneCamera }
        val end = north.pts.last()
        for (radius in listOf(1200.0, 2000.0, 6000.0)) {
            assertNull(AreaCache.get(end[0], end[1], radius, Long.MAX_VALUE, System.currentTimeMillis()))
        }
    }
}
