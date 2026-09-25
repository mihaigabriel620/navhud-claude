package com.mihai.navhud

import com.mihai.navhud.alerts.CameraPolicy
import com.mihai.navhud.alerts.CameraWatcher
import com.mihai.navhud.alerts.SpeedCamera
import com.mihai.navhud.nav.Area
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
