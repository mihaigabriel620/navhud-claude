package com.mihai.navhud

import com.mihai.navhud.alerts.LowEmissionZones
import com.mihai.navhud.nav.MapboxProvider
import com.mihai.navhud.nav.Route
import com.mihai.navhud.nav.RouteTrait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a route makes you drive through, and whether the app notices before you
 * set off rather than after.
 */
class RouteTraitsTest {

    // A straight line east across Brussels, one point every ~70 m.
    private fun line(n: Int, lat0: Double = 50.85, lon0: Double = 4.30, step: Double = 0.001): Route {
        val pts = Array(n) { doubleArrayOf(lat0, lon0 + it * step) }
        val cum = Geo.cumulative(pts)
        return Route(
            pts = pts, cum = cum, limitKph = IntArray(n - 1),
            maneuvers = emptyList(),
            totalDistanceM = cum.last(), totalDurationS = 600.0, provider = "test"
        )
    }

    // ---- traits from the router --------------------------------------------

    @Test fun `tolls ferries and tunnels are read out of the intersections`() {
        val json = org.json.JSONObject("""
        {"geometry":"", "legs":[{"steps":[
           {"intersections":[{"classes":["motorway"]},{"classes":["toll","motorway"]}]},
           {"intersections":[{"classes":["ferry"]}]},
           {"intersections":[{"classes":["tunnel"]}]}
        ]}]}""")
        var traits = 0
        val steps = json.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
        for (si in 0 until steps.length()) {
            val ints = steps.getJSONObject(si).getJSONArray("intersections")
            for (ii in 0 until ints.length()) {
                val cs = ints.getJSONObject(ii).getJSONArray("classes")
                for (ci in 0 until cs.length()) traits = traits or RouteTrait.fromClass(cs.getString(ci))
            }
        }
        assertTrue(traits and RouteTrait.TOLL != 0)
        assertTrue(traits and RouteTrait.FERRY != 0)
        assertTrue(traits and RouteTrait.TUNNEL != 0)
        assertEquals(0, traits and RouteTrait.UNPAVED)
        // "motorway" is not a warning; it is the normal case.
        assertEquals(0, RouteTrait.fromClass("motorway"))
        assertEquals(0, RouteTrait.fromClass(null))
    }

    @Test fun `a route knows how much of it is closed`() {
        val n = 11
        val closed = BooleanArray(n - 1)
        closed[3] = true; closed[4] = true
        val base = line(n)
        val r = Route(base.pts, base.cum, base.limitKph, emptyList(),
            base.totalDistanceM, 600.0, "test", closed = closed, traits = RouteTrait.CLOSURE)
        assertTrue(r.has(RouteTrait.CLOSURE))
        assertFalse(r.has(RouteTrait.TOLL))
        val expected = (base.cum[5] - base.cum[3])
        assertEquals(expected, r.closedM, 1.0)
        // Distance to the closure from the start is the start of segment 3.
        assertEquals(base.cum[3], r.metresToClosure(0.0), 1.0)
        // Standing inside it, the answer is zero rather than negative.
        assertEquals(0.0, r.metresToClosure(base.cum[4]), 1.0)
        // Past it, the way is clear.
        assertEquals(-1.0, r.metresToClosure(base.cum[9]), 0.001)
    }

    // ---- low-emission zones -------------------------------------------------

    private val square = arrayOf(
        doubleArrayOf(50.80, 4.30),
        doubleArrayOf(50.90, 4.30),
        doubleArrayOf(50.90, 4.40),
        doubleArrayOf(50.80, 4.40)
    )

    @Test fun `point in polygon handles inside, outside and the edges`() {
        assertTrue(LowEmissionZones.contains(square, 50.85, 4.35))
        assertFalse(LowEmissionZones.contains(square, 50.85, 4.25))
        assertFalse(LowEmissionZones.contains(square, 50.75, 4.35))
        assertFalse(LowEmissionZones.contains(square, 50.95, 4.35))
        assertFalse(LowEmissionZones.contains(square, 50.85, 4.45))
    }

    @Test fun `a route that clips the corner of a zone is still in it`() {
        // 60 points at 0.001 degrees is about 4 km of road, entering the box.
        val r = line(60, lat0 = 50.85, lon0 = 4.26)
        val z = LowEmissionZones.Zone("Bruxelles", square)
        assertEquals(listOf(z), LowEmissionZones.crossed(r, listOf(z)))
    }

    @Test fun `a route that misses the zone is not flagged`() {
        val r = line(40, lat0 = 50.60, lon0 = 4.26)
        val z = LowEmissionZones.Zone("Bruxelles", square)
        assertTrue(LowEmissionZones.crossed(r, listOf(z)).isEmpty())
    }

    @Test fun `a destination inside a zone counts even on a short hop`() {
        // Two points, both inside: too short for the sampler to take a second
        // step, so the destination check is the one that has to catch it.
        val pts = arrayOf(doubleArrayOf(50.849, 4.349), doubleArrayOf(50.85, 4.35))
        val cum = Geo.cumulative(pts)
        val r = Route(pts, cum, IntArray(1), emptyList(), cum.last(), 60.0, "test")
        val z = LowEmissionZones.Zone("Bruxelles", square)
        assertEquals(1, LowEmissionZones.crossed(r, listOf(z)).size)
    }

    @Test fun `overpass zones parse from ways and multipolygon relations`() {
        val json = """
        {"elements":[
          {"type":"way","id":1,"tags":{"boundary":"low_emission_zone","name":"Gent"},
           "geometry":[{"lat":51.0,"lon":3.7},{"lat":51.1,"lon":3.7},
                       {"lat":51.1,"lon":3.8},{"lat":51.0,"lon":3.8}]},
          {"type":"relation","id":2,"tags":{"boundary":"low_emission_zone","name":"Antwerpen"},
           "members":[
             {"type":"way","role":"outer","geometry":[{"lat":51.2,"lon":4.4},{"lat":51.3,"lon":4.4},
                                                      {"lat":51.3,"lon":4.5},{"lat":51.2,"lon":4.5}]},
             {"type":"way","role":"inner","geometry":[{"lat":51.25,"lon":4.45}]}
           ]},
          {"type":"way","id":3,"tags":{"boundary":"low_emission_zone"},
           "geometry":[{"lat":50.0,"lon":3.0},{"lat":50.1,"lon":3.0}]}
        ]}"""
        val zones = LowEmissionZones.parse(json)
        // The third is a two-point "ring", which is a line, not an area.
        assertEquals(2, zones.size)
        assertEquals("Gent", zones[0].name)
        assertEquals("Antwerpen", zones[1].name)
        assertTrue(LowEmissionZones.contains(zones[1].ring, 51.25, 4.45))
    }

    @Test fun `a nameless zone still warns`() {
        val json = """
        {"elements":[{"type":"way","id":9,"tags":{"boundary":"low_emission_zone"},
          "geometry":[{"lat":48.8,"lon":2.3},{"lat":48.9,"lon":2.3},
                      {"lat":48.9,"lon":2.4},{"lat":48.8,"lon":2.4}]}]}"""
        val zones = LowEmissionZones.parse(json)
        assertEquals(1, zones.size)
        assertTrue(zones[0].name.isNotBlank())
    }

    @Test fun `the query asks for geometry, not just ids`() {
        val q = LowEmissionZones.buildQuery(line(10))
        assertTrue(q.contains("boundary\"=\"low_emission_zone"))
        assertTrue("an id-only query cannot answer the question", q.contains("out geom"))
        assertTrue(q.contains("relation["))
        assertTrue(q.contains("way["))
    }

    @Test fun `parsing junk does not take the navigation down`() {
        assertTrue(LowEmissionZones.parse("""{"elements":[]}""").isEmpty())
        assertTrue(LowEmissionZones.parse("""{"elements":[{"type":"way"}]}""").isEmpty())
    }

    // ---- the provider still parses a route with none of this ---------------

    @Test fun `a route with no classes has no traits`() {
        assertEquals(0, RouteTrait.fromClass(""))
        val p = MapboxProvider("pk.test")
        assertEquals("Mapbox", p.name)
    }
}
