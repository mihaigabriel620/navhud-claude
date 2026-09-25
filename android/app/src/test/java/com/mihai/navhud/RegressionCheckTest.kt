package com.mihai.navhud

import com.mihai.navhud.alerts.CameraPolicy
import com.mihai.navhud.alerts.CameraWatcher
import com.mihai.navhud.alerts.SpeedCamera
import com.mihai.navhud.map.RoadLock
import com.mihai.navhud.nav.Area
import com.mihai.navhud.nav.RoadWay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The regression check against the owner's bug lists, one test per finding.
 * Each fails against the code as it was before its fix.
 */
class RegressionCheckTest {

    // ---- R1: a danger zone must not reveal where the camera stands ---------

    @Test fun `a route danger zone is announced once and does not end on the camera`() {
        // Motorway camera: a 4 km zone, so a 2 km tail after it.
        val cam = SpeedCamera(7, 0.0, 0.0, 10000.0, 130, null, SpeedCamera.Kind.FIXED)
        val w = CameraWatcher(listOf(cam), CameraPolicy.ZONE)
        var said = 0
        var d = 5000.0
        while (d < 10000.0) {
            w.update(d, 120, null)?.let { if (w.shouldAnnounce(it)) said++ }
            d += 20.0
        }
        assertEquals("one 'zone de danger', not one per stage", 1, said)
        for (past in listOf(10.0, 100.0, 1500.0)) {
            val a = w.update(10000.0 + past, 120, null)
            assertNotNull("zone still up ${past.toInt()} m after the camera", a)
            assertEquals("and silent", false, w.shouldAnnounce(a!!))
        }
        assertNull("over once the tail is behind", w.update(12100.0, 120, null))
    }

    @Test fun `a free-drive danger zone is announced once and does not end on the camera`() {
        // 90 km/h road: a 2 km zone, so a 1 km tail. No roads loaded, so the
        // camera is judged by the line we drive (due north along 4.35).
        val cam = SpeedCamera(9, 50.8050, 4.3500, 0.0, 90, null, SpeedCamera.Kind.FIXED)
        val t = FreeTracker()
        t.area = Area(50.805, 4.35, 3000.0, emptyList(), listOf(cam), 0L)
        var said = 0
        var now = 0L
        fun at(lat: Double) {
            now += 1000L
            t.update(lat, 4.3500, 25f, 0f, true, now, CameraPolicy.ZONE)
            t.alert?.let { if (t.shouldAnnounce(it)) said++ }
        }
        var lat = 50.7870                              // ~2 km before the camera
        while (lat < 50.8050) { at(lat); lat += 0.0002 }
        assertEquals("one 'zone de danger', not one per stage", 1, said)
        at(50.8055)                                    // ~55 m past it
        assertNotNull("zone still up just after the camera", t.alert)
        at(50.8100)                                    // ~550 m past it
        assertNotNull("and half a kilometre on", t.alert)
        at(50.8150)                                    // ~1.1 km past it
        assertNull("over once the tail is behind", t.alert)
        assertEquals("the tail is never announced", 1, said)
    }

    // ---- R2: no GPS fix is not no speedometer ------------------------------

    @Test fun `with no fix the gauge shows the car's own speed, else dashes`() {
        val route = DemoDrive.buildRoute()
        val before = RouteTracker(route)
        assertEquals("route before the first fix",
            87, before.update(0.0, 0.0, 0f, null, hasFix = false, noFixKph = 87).speedKph)
        assertEquals(-1, before.update(0.0, 0.0, 0f, null, hasFix = false).speedKph)

        // A tunnel longer than the coast limit, the bus still talking.
        val t = RouteTracker(route)
        val p = Geo.pointAlong(route.pts, route.cum, 3000.0)
        t.update(p[0], p[1], 30f, null, hasFix = true)
        assertEquals(108, t.coast(7.5, 108, RouteTracker.COAST_MAX_MS + 1).speedKph)
        assertEquals("GPS was all it had", -1,
            t.coast(7.5, -1, RouteTracker.COAST_MAX_MS + 1).speedKph)

        val free = FreeTracker()
        assertEquals("free drive, no fix",
            87, free.update(0.0, 0.0, 0f, null, false, 1000L, CameraPolicy.EXACT, noFixKph = 87).speedKph)
        assertEquals(-1, free.update(0.0, 0.0, 0f, null, false, 2000L, CameraPolicy.EXACT).speedKph)
    }

    // ---- R4: a limit flicker is not a new limit ----------------------------

    /** 4 Hz from [fromMs] for [ms]; how many times it warned. */
    private fun SpeedingRule.warns(fromMs: Long, ms: Long, kph: Int, limit: Int): Int {
        var n = 0
        var t = fromMs
        while (t < fromMs + ms) { if (update(t, kph, limit)) n++; t += 250 }
        return n
    }

    @Test fun `a 50-70-50 limit flicker does not warn twice`() {
        val r = SpeedingRule()
        assertEquals(1, r.warns(0, 5_000, 75, 50))
        assertEquals(0, r.warns(5_000, 2_000, 75, 70))      // an unheld fallback 70
        assertEquals("back to the same 50: still the cooldown",
            0, r.warns(7_000, 20_000, 75, 50))
        assertEquals("a limit below the one warned about is news",
            1, r.warns(27_000, 5_000, 75, 30))
    }

    // ---- R5: a parked GPS's 1-2 m/s of wander is not driving ----------------

    @Test fun `without the car's speed, GPS speed jitter does not creep the held arrow`() {
        val lat0 = 50.8000
        val road = RoadWay(1, arrayOf(doubleArrayOf(lat0, 4.3000), doubleArrayOf(lat0, 4.3142)),
            "Rue A", "", 50, "residential", 0)
        val area = Area(lat0, 4.307, 2000.0, listOf(road), emptyList(), 0L)
        val lock = RoadLock()
        val start = Geo.destination(lat0, 4.3071, 0.0, 5.0)
        lock.update(area, start[0], start[1], 0.0, null, speedFromCar = false, accuracyM = 15.0)
        val heldLat = lock.lat
        val heldLon = lock.lon
        for ((i, east) in listOf(8.0, -6.0, 12.0, -10.0, 5.0).withIndex()) {
            val w = Geo.destination(start[0], start[1], 90.0, east)
            lock.update(area, w[0], w[1], 1.5, null, speedFromCar = false, accuracyM = 15.0)
            assertEquals("wander $i held", heldLon, lock.lon, 0.0)
            assertEquals(heldLat, lock.lat, 0.0)
        }
        val moved = Geo.destination(start[0], start[1], 90.0, 40.0)
        lock.update(area, moved[0], moved[1], 1.5, null, speedFromCar = false, accuracyM = 15.0)
        assertEquals("40 m along the road is movement", moved[1], lock.lon, 1e-6)
    }

    // ---- R6: off the route, the limit is the road's, not the route's -------

    @Test fun `off the route the limit comes from the road under the car`() {
        val route = DemoDrive.buildRoute()
        val t = RouteTracker(route)
        t.limitFallback = { _, _, _ -> 20 }
        val p = Geo.pointAlong(route.pts, route.cum, 3000.0)
        val on = t.update(p[0], p[1], 0f, null, hasFix = true, nowMs = 0L)
        assertTrue("the route has its own limit here", on.limitKph > 0 && on.limitKph != 20)
        val off = Geo.destination(p[0], p[1],
            Geo.bearingAlong(route.pts, route.cum, 3000.0)!! + 90.0, 80.0)
        t.update(off[0], off[1], 0f, null, hasFix = true, nowMs = 1_000L)
        val f = t.update(off[0], off[1], 0f, null, hasFix = true, nowMs = 2_000L)
        assertTrue(t.offRoute)
        assertEquals(20, f.limitKph)
    }

    // ---- R7: a fork in town is not a motorway exit -------------------------

    @Test fun `forks and ramps in town get two calls, motorway exits three`() {
        for (m in listOf(Man.FORK_LEFT, Man.RAMP_RIGHT, Man.KEEP_LEFT)) {
            assertEquals("$m at 40 km/h", 2, VoiceGuide.thresholdsFor(40, m).size)
            assertEquals(listOf(1200, 500, 200), VoiceGuide.thresholdsFor(120, m).toList())
        }
    }

    // ---- W1: parked off the road is not a wrong turn -----------------------

    /** [m] metres left of the route at [along]. */
    private fun beside(route: com.mihai.navhud.nav.Route, along: Double, m: Double): DoubleArray {
        val p = Geo.pointAlong(route.pts, route.cum, along)
        return Geo.destination(p[0], p[1], Geo.bearingAlong(route.pts, route.cum, along)!! - 90.0, m)
    }

    @Test fun `a route started from a car park is not off route while parked there`() {
        val route = DemoDrive.buildRoute()
        val t = RouteTracker(route)
        var now = 0L
        for (m in listOf(80.0, 90.0, 70.0, 95.0, 85.0, 75.0, 88.0, 80.0)) {
            val f = beside(route, 50.0, m)
            t.update(f[0], f[1], 1.5f, null, hasFix = true, nowMs = now)
            now += 1_000L
            assertFalse("parked ${m.toInt()} m off, before joining", t.offRoute || t.offLine)
        }
        // Driving further away from the start is still caught.
        val away = beside(route, 50.0, 130.0)
        t.update(away[0], away[1], 8f, null, hasFix = true, nowMs = now)
        t.update(away[0], away[1], 8f, null, hasFix = true, nowMs = now + 1_000L)
        assertTrue(t.offRoute)
    }

    @Test fun `parked just off the road near the end is not a reroute loop`() {
        val route = DemoDrive.buildRoute()
        val end = route.totalDistanceM - 300.0
        // The route made from there (a reroute): parked 35 m off, never joined.
        val t = RouteTracker(route)
        var now = 0L
        for (m in listOf(35.0, 45.0, 30.5, 50.0, 40.0)) {
            val f = beside(route, end, m)
            t.update(f[0], f[1], 1.5f, null, hasFix = true, nowMs = now)
            now += 1_000L
            assertFalse(t.offRoute)
        }
        // A route the car has driven on still goes off route at 40 m.
        val j = RouteTracker(route)
        val on = beside(route, end, 0.0)
        j.update(on[0], on[1], 10f, null, hasFix = true, nowMs = 0L)
        val off = beside(route, end, 40.0)
        j.update(off[0], off[1], 10f, null, hasFix = true, nowMs = 1_000L)
        j.update(off[0], off[1], 10f, null, hasFix = true, nowMs = 2_000L)
        assertTrue(j.offRoute)
    }

    // ---- W2: a roundabout arrow shows the exit, not the entry veer ---------

    /**
     * A roundabout reached from a depart step, shaped as the Mapbox Directions
     * docs show it: the banner on the step *before* the roundabout, `degrees`
     * and `driving_side` on its primary. The bearings sit at the entry: 0 -> 60,
     * the veer into the circle.
     */
    private fun roundabout(banner: String, side: String = "right"): Int? {
        val json = """
        {"distance":900.0,"duration":90.0,
          "geometry":"_wq{_B_mmeG?owH?_pRg^_pR",
          "legs":[{"steps":[
            {"name":"Grote Baan","distance":400.0,"driving_side":"$side",
             "maneuver":{"type":"depart","modifier":"","location":[4.30,50.80]},
             "bannerInstructions":[
               {"distanceAlongGeometry":400.0,
                "primary":{"text":"Steenweg","components":[{"text":"Steenweg","type":"text"}],
                           "type":"roundabout","modifier":"right"$banner},
                "secondary":null,"sub":null}]},
            {"name":"Ring","distance":200.0,"driving_side":"$side",
             "maneuver":{"type":"roundabout","modifier":"right","exit":2,
                         "location":[4.31,50.80],"bearing_before":0.0,"bearing_after":60.0}},
            {"name":"Steenweg","distance":300.0,"driving_side":"$side",
             "maneuver":{"type":"arrive","modifier":"","location":[4.32,50.80]}}
          ]}]}
        """.trimIndent()
        return com.mihai.navhud.nav.MapboxProvider(token = "test", language = "en")
            .parseRoute(org.json.JSONObject(json)).maneuvers.first { it.exit == 2 }.exitBearing
    }

    @Test fun `the roundabout angle comes from the banner's degrees`() {
        assertEquals("first exit, right-hand traffic", 90,
            roundabout(""","degrees":90,"driving_side":"right""""))
        assertEquals("straight through", 0,
            roundabout(""","degrees":180,"driving_side":"right""""))
        assertEquals("third exit of four", -90,
            roundabout(""","degrees":270,"driving_side":"right""""))
        assertEquals("first exit where they drive on the left", -90,
            roundabout(""","degrees":90,"driving_side":"left"""", side = "left"))
        assertEquals("side from the step when the banner has none", -90,
            roundabout(""","degrees":90""", side = "left"))
        assertEquals("no degrees: the bearings, as before", 60, roundabout(""))
    }

    // ---- W5: an accurate fix in a car park is not on the street ------------

    @Test fun `the first road pick is bounded by the fix's accuracy`() {
        val lat0 = 50.8000
        val road = RoadWay(1, arrayOf(doubleArrayOf(lat0, 4.3000), doubleArrayOf(lat0, 4.3142)),
            "Rue A", "", 50, "residential", 0)
        val area = Area(lat0, 4.307, 2000.0, listOf(road), emptyList(), 0L)
        val carPark = Geo.destination(lat0, 4.3071, 0.0, 80.0)      // 80 m off the street
        val accurate = RoadLock()
        assertFalse("a 5 m fix in a car park stays raw",
            accurate.update(area, carPark[0], carPark[1], 0.0, null, accuracyM = 5.0))
        assertNull(accurate.road)
        val indoor = RoadLock()
        assertTrue("a +-60 m indoor fix still locks",
            indoor.update(area, carPark[0], carPark[1], 0.0, null, accuracyM = 60.0))
        assertEquals(1L, indoor.road!!.id)
    }
}
