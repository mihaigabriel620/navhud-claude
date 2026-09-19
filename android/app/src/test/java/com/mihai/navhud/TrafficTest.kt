package com.mihai.navhud

import com.mihai.navhud.nav.MapboxProvider
import com.mihai.navhud.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live traffic and closures, off a canned Mapbox response.
 *
 * The trap here, and the bug this was written after: `annotations=closure` is
 * how you *ask* for closures, but they do not come back as a per-segment array
 * inside `annotation`. They arrive as `legs[].closures`, a list of vertex
 * ranges. Reading them the obvious way fails silently — every segment simply
 * reads as open — so nothing on screen ever looks wrong, it just never lights
 * up. Only a test against a real response shape catches that.
 */
class TrafficTest {

    private val provider = MapboxProvider(token = "test", language = "en")

    @Test fun `congestion strings map to the levels the map draws`() {
        assertEquals(0, provider.congestionLevel("unknown"))
        assertEquals(0, provider.congestionLevel(null))
        assertEquals(0, provider.congestionLevel(""))
        assertEquals(1, provider.congestionLevel("low"))
        assertEquals(2, provider.congestionLevel("moderate"))
        assertEquals(3, provider.congestionLevel("heavy"))
        assertEquals(4, provider.congestionLevel("severe"))
    }

    // ---- Route helpers ------------------------------------------------------

    /** A straight 5-vertex, 4-segment route with 100 m spacing. */
    private fun route(congestion: IntArray, closed: BooleanArray): Route {
        val pts = Array(5) { doubleArrayOf(50.8000 + it * 0.0008993, 4.3500) }
        val cum = Geo.cumulative(pts)
        return Route(
            pts = pts, cum = cum, limitKph = IntArray(4),
            maneuvers = emptyList(),
            totalDistanceM = cum.last(), totalDurationS = 60.0,
            provider = "test", congestion = congestion, closed = closed
        )
    }

    @Test fun `the traffic level under the car is the segment it is on`() {
        val r = route(intArrayOf(1, 3, 3, 1), BooleanArray(4))
        assertEquals(1, r.congestionAt(0.0))
        assertEquals(1, r.congestionAt(50.0))
        assertEquals(3, r.congestionAt(150.0))
        assertEquals(3, r.congestionAt(250.0))
        assertEquals(1, r.congestionAt(350.0))
        // Past the end it clamps rather than throwing.
        assertEquals(1, r.congestionAt(99_999.0))
        assertEquals(1, r.congestionAt(-5.0))
    }

    @Test fun `no traffic data reads as unknown, not as free-flowing`() {
        val r = route(IntArray(0), BooleanArray(0))
        assertEquals(0, r.congestionAt(150.0))
        assertEquals(-1.0, r.metresToClosure(0.0), 1e-9)
    }

    @Test fun `the distance to a closure is measured ahead, not behind`() {
        val r = route(IntArray(4), booleanArrayOf(false, false, true, false))
        // Segment 2 starts at 200 m.
        assertEquals(200.0, r.metresToClosure(0.0), 1.0)
        assertEquals(100.0, r.metresToClosure(100.0), 1.0)
        assertEquals(0.0, r.metresToClosure(250.0), 1.0)
        // Once past it, the road ahead is clear again.
        assertEquals(-1.0, r.metresToClosure(320.0), 1e-9)
    }

    // ---- parsing the real response shape ------------------------------------

    private fun body(closuresJson: String, congestionJson: String) = """
    {"code":"Ok","routes":[{
      "distance":400.0,"duration":60.0,"duration_typical":40.0,
      "geometry":"${'$'}{GEOM}",
      "legs":[{
        "annotation":{"congestion":$congestionJson,
                      "maxspeed":[{"speed":50,"unit":"km/h"},{"speed":50,"unit":"km/h"},
                                  {"speed":50,"unit":"km/h"},{"speed":50,"unit":"km/h"}]},
        "closures":$closuresJson,
        "steps":[]
      }]
    }]}
    """.trimIndent()

    /** Five vertices 100 m apart, encoded as polyline6. */
    private fun geometry(): String {
        val pts = Array(5) { doubleArrayOf(50.8000 + it * 0.0008993, 4.3500) }
        return encodePolyline6(pts)
    }

    private fun parse(closuresJson: String, congestionJson: String): Route {
        val json = body(closuresJson, congestionJson).replace("\${GEOM}", geometry())
        val root = org.json.JSONObject(json)
        // parseRoute is internal now -- the lane-banner tests needed it too, and
        // a reflective call by name silently rots the moment the method is
        // renamed, which is exactly what happened.
        return provider.parseRoute(root.getJSONArray("routes").getJSONObject(0))
    }

    @Test fun `congestion is read one value per segment`() {
        val r = parse("[]", """["low","heavy","heavy","moderate"]""")
        assertEquals(4, r.congestion.size)
        assertEquals(1, r.congestion[0])
        assertEquals(3, r.congestion[1])
        assertEquals(3, r.congestion[2])
        assertEquals(2, r.congestion[3])
        assertFalse("nothing was closed", r.closed.any { it })
    }

    @Test fun `a closure arrives as a vertex range on the leg, not an annotation`() {
        val r = parse(
            """[{"geometry_index_start":1,"geometry_index_end":3}]""",
            """["low","low","low","low"]"""
        )
        // Vertices 1..3 means segments 1 and 2.
        assertFalse(r.closed[0])
        assertTrue(r.closed[1])
        assertTrue(r.closed[2])
        assertFalse(r.closed[3])
        assertEquals(100.0, r.metresToClosure(0.0), 2.0)
    }

    @Test fun `a nonsensical closure range is ignored rather than crashing`() {
        val r = parse(
            """[{"geometry_index_start":9,"geometry_index_end":99},
                {"geometry_index_start":3,"geometry_index_end":1},
                {"geometry_index_start":-1,"geometry_index_end":2}]""",
            """["low","low","low","low"]"""
        )
        assertEquals(4, r.closed.size)
        assertFalse("out-of-range closures must not spill", r.closed.any { it })
    }

    @Test fun `a response with no closures key at all is fine`() {
        val json = """
        {"code":"Ok","routes":[{"distance":400.0,"duration":60.0,
          "geometry":"${geometry()}",
          "legs":[{"annotation":{"congestion":["low","low","low","low"]},"steps":[]}]}]}
        """.trimIndent()
        val root = org.json.JSONObject(json)
        val r = provider.parseRoute(root.getJSONArray("routes").getJSONObject(0))
        assertEquals(4, r.closed.size)
        assertFalse(r.closed.any { it })
    }

    @Test fun `traffic delay comes from the typical duration`() {
        val r = parse("[]", """["low","low","low","low"]""")
        assertEquals(20.0, r.trafficDelayS, 1e-6)   // 60 actual vs 40 typical
    }

    // ---- a tiny polyline6 encoder, so the fixtures are real ------------------

    private fun encodePolyline6(pts: Array<DoubleArray>): String {
        val sb = StringBuilder()
        var lastLat = 0L
        var lastLon = 0L
        for (p in pts) {
            val lat = Math.round(p[0] * 1e6)
            val lon = Math.round(p[1] * 1e6)
            encodeValue(sb, lat - lastLat)
            encodeValue(sb, lon - lastLon)
            lastLat = lat; lastLon = lon
        }
        return sb.toString()
    }

    private fun encodeValue(sb: StringBuilder, v: Long) {
        var value = if (v < 0) (v shl 1).inv() else (v shl 1)
        while (value >= 0x20) {
            sb.append((((value and 0x1f) or 0x20) + 63).toInt().toChar())
            value = value shr 5
        }
        sb.append((value + 63).toInt().toChar())
    }

    @Test fun `the test's own encoder round-trips through the app's decoder`() {
        val pts = Array(5) { doubleArrayOf(50.8000 + it * 0.0008993, 4.3500) }
        val back = Geo.decodePolyline(encodePolyline6(pts), 6)
        assertEquals(5, back.size)
        for (i in pts.indices) {
            assertEquals(pts[i][0], back[i][0], 1e-6)
            assertEquals(pts[i][1], back[i][1], 1e-6)
        }
    }
}
