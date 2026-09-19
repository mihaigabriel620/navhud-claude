package com.mihai.navhud

import com.mihai.navhud.nav.Lane
import com.mihai.navhud.nav.MapboxProvider
import com.mihai.navhud.nav.Route
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lane guidance belongs to the step you are *driving*, not the step you are
 * about to start.
 *
 * Mapbox counts `distanceAlongGeometry` down through the current step towards
 * the next turn, so a step's banners describe the maneuver at its **end**. Read
 * them off the same step as that step's own `maneuver` and every junction gets
 * the lanes for the junction after it — which on the Brussels ring meant being
 * told to take the right-hand exit while the arrows underneath pointed left,
 * because the left turn was the one waiting at the far end of the slip road.
 */
class LaneBannerTest {

    /**
     * Two steps on a motorway: drive the R0, then take a right ramp, then turn
     * left at the end of it. The ramp step carries banners describing the LEFT
     * turn that follows it; the R0 step carries banners describing the RIGHT
     * ramp.
     */
    private fun parse(): Route =
        MapboxProvider(token = "test", language = "en").parseRoute(JSONObject(json()))

    private fun json(): String = """
    {"distance":3000.0,"duration":200.0,
      "geometry":"_wq{_B_mmeG?owH?_pRg^_pR",
      "legs":[{"steps":[
        {"name":"R0","distance":1000.0,
         "maneuver":{"type":"depart","modifier":"","location":[4.30,50.80]},
         "bannerInstructions":[{"distanceAlongGeometry":600.0,
           "primary":{"text":"St.-Pieters-Leeuw","type":"off ramp","modifier":"right"},
           "sub":{"components":[
             {"type":"lane","directions":["straight"],"active":false},
             {"type":"lane","directions":["straight","slight right"],"active":true,
              "active_direction":"slight right"},
             {"type":"lane","directions":["slight right"],"active":true,
              "active_direction":"slight right"}]}}]},
        {"name":"Slip road","distance":300.0,"ref":"N6",
         "maneuver":{"type":"off ramp","modifier":"right","location":[4.315,50.80]},
         "bannerInstructions":[{"distanceAlongGeometry":200.0,
           "primary":{"text":"Anderlecht","type":"turn","modifier":"left"},
           "sub":{"components":[
             {"type":"lane","directions":["left"],"active":true,"active_direction":"left"},
             {"type":"lane","directions":["left"],"active":true,"active_direction":"left"}]}}]},
        {"name":"N6","distance":1700.0,
         "maneuver":{"type":"turn","modifier":"left","location":[4.325,50.8005]},
         "bannerInstructions":[]}
      ]}]}
    """.trimIndent()

    @Test
    fun `the ramp gets the lanes for the ramp, not for the turn after it`() {
        val route = parse()
        val ramp = route.maneuvers.first { it.code == Man.RAMP_RIGHT }
        val lanes = ramp.lanes
        assertNotNull("the ramp must have lane guidance", lanes)

        // Three lanes on the R0 approach, the right two usable.
        assertEquals(3, lanes!!.count)
        assertEquals(0b110, lanes.activeMask)

        // ...and every usable one points RIGHT. This is the assertion that
        // would have caught the bug: before the fix these were LEFT, taken
        // from the turn at the end of the slip road.
        assertEquals(Lane.SLIGHT_RIGHT, lanes.chosen[1])
        assertEquals(Lane.SLIGHT_RIGHT, lanes.chosen[2])
        assertEquals(1, Lane.sideOf(lanes.chosen[1]))
        assertEquals(1, Lane.sideOf(lanes.chosen[2]))
    }

    @Test
    fun `the turn at the end of the slip road gets its own two left lanes`() {
        val route = parse()
        val turn = route.maneuvers.first { it.code == Man.LEFT }
        val lanes = turn.lanes
        assertNotNull(lanes)
        assertEquals(2, lanes!!.count)
        assertEquals(Lane.LEFT, lanes.chosen[0])
        assertEquals(-1, Lane.sideOf(lanes.chosen[0]))
    }

    @Test
    fun `the first maneuver of a leg has no previous step to read`() {
        val route = parse()
        assertNull(route.maneuvers.first().lanes)
    }
}
