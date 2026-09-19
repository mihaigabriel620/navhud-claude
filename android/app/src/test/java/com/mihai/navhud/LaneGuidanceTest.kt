package com.mihai.navhud

import com.mihai.navhud.nav.JunctionSign
import com.mihai.navhud.nav.Lane
import com.mihai.navhud.nav.LaneGuidance
import com.mihai.navhud.nav.RoadSigns
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lane guidance: the three-state model, the wide-carriageway window, and the
 * sign colours. All of it is arithmetic on the router's own fields, so all of
 * it can be tested without a phone.
 */
class LaneGuidanceTest {

    private fun banner(sub: String, modifier: String = "right"): JSONArray =
        JSONArray("""[{"primary":{"text":"x","modifier":"$modifier","components":[]},
                       "sub":{"text":"","components":[$sub]}}]""")

    // ---- active_direction ---------------------------------------------------

    @Test fun `active_direction picks one movement out of a shared lane`() {
        val g = LaneGuidance.fromBanners(banner(
            """{"type":"lane","directions":["straight"],"active":false},
               {"type":"lane","directions":["straight","right"],"active":true,
                "active_direction":"right"}"""))
        assertNotNull(g)
        assertEquals(Lane.STRAIGHT or Lane.RIGHT, g!!.lanes[1])
        assertEquals(Lane.RIGHT, g.chosenOf(1))
        assertEquals(0, g.chosenOf(0))
    }

    @Test fun `a lane with one movement needs no active_direction`() {
        val g = LaneGuidance.fromBanners(banner(
            """{"type":"lane","directions":["left"],"active":true}""", modifier = "left"))!!
        assertEquals(Lane.LEFT, g.chosenOf(0))
        assertEquals(-1, g.turnSide)
    }

    @Test fun `without active_direction the banner modifier breaks the tie`() {
        // driving-traffic omits active_direction; the instruction card is
        // about to say "turn left", so the left arrow is the one to light.
        val g = LaneGuidance.fromBanners(banner(
            """{"type":"lane","directions":["left","straight"],"active":true}""",
            modifier = "left"))!!
        assertEquals(Lane.LEFT, g.chosenOf(0))
    }

    @Test fun `an ambiguous lane chooses nothing rather than guessing`() {
        val g = LaneGuidance.fromBanners(banner(
            """{"type":"lane","directions":["left","right"],"active":true}""",
            modifier = "straight"))!!
        assertEquals(0, g.chosenOf(0))
        assertTrue(g.isActive(0))
    }

    @Test fun `active_direction is ignored when the lane does not allow it`() {
        val g = LaneGuidance.fromBanners(banner(
            """{"type":"lane","directions":["straight"],"active":true,
                "active_direction":"right"}""", modifier = "straight"))!!
        assertEquals(Lane.STRAIGHT, g.chosenOf(0))
    }

    @Test fun `an inactive lane never carries a chosen movement`() {
        val g = LaneGuidance.fromBanners(banner(
            """{"type":"lane","directions":["right"],"active":false,
                "active_direction":"right"}"""))!!
        assertFalse(g.isActive(0))
        assertEquals(0, g.chosenOf(0))
    }

    // ---- ordering and sides -------------------------------------------------

    @Test fun `lanes stay in the order the router sent them, left to right`() {
        val g = LaneGuidance.fromBanners(banner(
            """{"type":"lane","directions":["left"],"active":false},
               {"type":"lane","directions":["straight"],"active":false},
               {"type":"lane","directions":["right"],"active":true}"""))!!
        assertEquals(Lane.LEFT, g.lanes[0])
        assertEquals(Lane.STRAIGHT, g.lanes[1])
        assertEquals(Lane.RIGHT, g.lanes[2])
        assertEquals(2, g.firstActive)
        assertEquals(2, g.lastActive)
    }

    @Test fun `turn side falls back to where the usable lanes sit`() {
        // Nothing but "straight" arrows, but only the rightmost lane is ours:
        // that is a keep-right, whatever the arrows say.
        val g = LaneGuidance(
            intArrayOf(Lane.STRAIGHT, Lane.STRAIGHT, Lane.STRAIGHT),
            activeMask = 0b100)
        assertEquals(1, g.turnSide)
    }

    @Test fun `every exit position on a two lane road is representable`() {
        for (exitLane in 0 until 3) {
            val lanes = IntArray(3) { if (it == exitLane) Lane.RIGHT else Lane.STRAIGHT }
            val g = LaneGuidance(lanes, 1 shl exitLane, IntArray(3) {
                if (it == exitLane) Lane.RIGHT else 0
            })
            assertEquals(exitLane, g.firstActive)
            assertEquals(1, g.activeCount)
            assertEquals(Lane.RIGHT, g.chosenOf(exitLane))
        }
    }

    // ---- the window ---------------------------------------------------------

    @Test fun `a wide approach keeps the lane you need`() {
        // Eleven lanes, the exit is the tenth. Truncating from the left would
        // throw away exactly the lane the driver has to be in.
        val n = 11
        val lanes = IntArray(n) { if (it >= 9) Lane.RIGHT else Lane.STRAIGHT }
        val chosen = IntArray(n) { if (it >= 9) Lane.RIGHT else 0 }
        var mask = 0
        for (i in 9 until n) mask = mask or (1 shl i)
        val g = LaneGuidance.window(lanes, mask, chosen)
        assertEquals(LaneGuidance.MAX_LANES, g.count)
        assertTrue("the exit lanes must survive the window", g.activeCount == 2)
        assertEquals(Lane.RIGHT, g.chosenOf(g.firstActive))
        assertTrue(g.clippedLeft)
        assertFalse(g.clippedRight)
    }

    @Test fun `a wide approach with a left exit clips the other side`() {
        val n = 12
        val lanes = IntArray(n) { if (it <= 1) Lane.LEFT else Lane.STRAIGHT }
        val chosen = IntArray(n) { if (it <= 1) Lane.LEFT else 0 }
        val g = LaneGuidance.window(lanes, 0b11, chosen)
        assertEquals(LaneGuidance.MAX_LANES, g.count)
        assertEquals(2, g.activeCount)
        assertFalse(g.clippedLeft)
        assertTrue(g.clippedRight)
    }

    @Test fun `a normal carriageway is not windowed at all`() {
        val g = LaneGuidance.window(
            IntArray(4) { Lane.STRAIGHT }, 0b1000, IntArray(4))
        assertEquals(4, g.count)
        assertFalse(g.clippedLeft)
        assertFalse(g.clippedRight)
    }

    // ---- wire format --------------------------------------------------------

    @Test fun `the wire format keeps the old fields in the old places`() {
        val g = LaneGuidance(
            intArrayOf(Lane.STRAIGHT, Lane.RIGHT), 0b10, intArrayOf(0, Lane.RIGHT))
        val body = g.encodeBody()
        assertTrue(body.startsWith("LANE,2,2,16,64"))
        // A protocol v2 firmware reads exactly 3 + count fields and stops.
        val f = body.split(",")
        assertEquals("2", f[1]); assertEquals("2", f[2])
        assertEquals("16", f[3]); assertEquals("64", f[4])
        assertEquals("0", f[5]); assertEquals("64", f[6])
    }

    // ---- sign colours -------------------------------------------------------

    @Test fun `motorway sign colours follow the country, not a guess`() {
        // Verified against national sign catalogues: blue on the motorway in
        // France, Germany, the Netherlands and Belgium; green in Italy,
        // Switzerland, Romania, Czechia, Sweden.
        for (cc in listOf("FR", "DE", "NL", "BE", "ES", "PT", "GB", "AT", "PL")) {
            assertEquals("$cc motorway", RoadSigns.BLUE, RoadSigns.colourFor(cc, true))
        }
        for (cc in listOf("IT", "CH", "RO", "CZ", "SE", "HR", "SI", "DK", "GR")) {
            assertEquals("$cc motorway", RoadSigns.GREEN, RoadSigns.colourFor(cc, true))
        }
    }

    @Test fun `Belgium signs a motorway destination green only from an ordinary road`() {
        // AM 11 Oct 1976 art 12.9.1 9 puts a destination on green "si la
        // destination est atteinte par autoroute", and the regional standards
        // scope that to the ordinary network: AWV dienstorder MOW/AWV/2018/4
        // says green is for motorway destinations "op gewone gewestwegen",
        // while on motorway signage itself every destination panel is blue and
        // green is only the E-number badge (AWV Richtlijn Bewegwijzering
        // Autosnelwegen; Securotheque fiche 135 for Wallonia).
        assertEquals("ordinary road -> motorway", RoadSigns.GREEN,
            RoadSigns.junctionPanel("BE", onMotorway = false, ontoMotorway = true))
        assertEquals("ordinary road -> ordinary road", RoadSigns.BLUE,
            RoadSigns.junctionPanel("BE", onMotorway = false, ontoMotorway = false))
        assertEquals("on a motorway, everything is blue", RoadSigns.BLUE,
            RoadSigns.junctionPanel("BE", onMotorway = true, ontoMotorway = true))
        assertEquals("on a motorway, everything is blue", RoadSigns.BLUE,
            RoadSigns.junctionPanel("BE", onMotorway = true, ontoMotorway = false))
        assertEquals(RoadSigns.BLUE,
            RoadSigns.junctionPanel("FR", onMotorway = true, ontoMotorway = false))
    }

    @Test fun `the sign palette is four distinct colours`() {
        // Guards the reason two contradictory Belgium tests used to both pass:
        // with returnDefaultValues, Color.rgb() returned 0 for all of them.
        val all = listOf(RoadSigns.BLUE, RoadSigns.GREEN, RoadSigns.YELLOW, RoadSigns.WHITE)
        assertEquals(4, all.toSet().size)
    }

    @Test fun `a French D-road is not a motorway`() {
        // route departementale, not autoroute -- and Romanian DN/DJ likewise.
        assertEquals(false, RoadSigns.looksLikeMotorway("D920"))
        assertEquals(false, RoadSigns.looksLikeMotorway("DN1"))
        assertEquals(true, RoadSigns.looksLikeMotorway("A12"))
        assertEquals(true, RoadSigns.looksLikeMotorway("E40"))
    }

    @Test fun `Germany puts ordinary trunk roads on yellow`() {
        assertEquals(RoadSigns.YELLOW, RoadSigns.colourFor("DE", false))
        assertEquals(RoadSigns.BLUE, RoadSigns.colourFor("DE", true))
    }

    @Test fun `an unknown country still gets a sensible pair`() {
        assertEquals(RoadSigns.BLUE, RoadSigns.colourFor(null, true))
        assertEquals(RoadSigns.GREEN, RoadSigns.colourFor(null, false))
    }

    @Test fun `motorway references are recognised without inventing any`() {
        assertTrue(RoadSigns.looksLikeMotorway("A12"))
        assertTrue(RoadSigns.looksLikeMotorway("E40"))
        assertTrue(RoadSigns.looksLikeMotorway("M25"))
        assertFalse(RoadSigns.looksLikeMotorway("N4"))
        assertFalse(RoadSigns.looksLikeMotorway("Chaussée de Louvain"))
        assertFalse(RoadSigns.looksLikeMotorway(null))
        assertFalse(RoadSigns.looksLikeMotorway(""))
    }

    // ---- the slip road ------------------------------------------------------

    @Test fun `a dedicated exit lane is drawn as a slip road`() {
        val g = LaneGuidance.fromBanners(banner(
            """{"type":"lane","directions":["straight"],"active":false},
               {"type":"lane","directions":["straight"],"active":false},
               {"type":"lane","directions":["right"],"active":true,
                "active_direction":"right"}"""))!!
        assertEquals(1, g.rampLaneCount())
        assertEquals(1, g.turnSide)
    }

    @Test fun `a shared through and exit lane stays on the carriageway`() {
        // You can sit in it and still change your mind; drawing it peeling
        // away would be a lie.
        val g = LaneGuidance.fromBanners(banner(
            """{"type":"lane","directions":["straight"],"active":false},
               {"type":"lane","directions":["straight","right"],"active":true,
                "active_direction":"right"}"""))!!
        assertEquals(0, g.rampLaneCount())
    }

    @Test fun `two dedicated exit lanes both peel away`() {
        val g = LaneGuidance.fromBanners(banner(
            """{"type":"lane","directions":["straight"],"active":false},
               {"type":"lane","directions":["straight"],"active":false},
               {"type":"lane","directions":["right"],"active":true,"active_direction":"right"},
               {"type":"lane","directions":["right"],"active":true,"active_direction":"right"}"""))!!
        assertEquals(2, g.rampLaneCount())
    }

    @Test fun `a left hand exit peels away to the left`() {
        val g = LaneGuidance.fromBanners(banner(
            """{"type":"lane","directions":["left"],"active":true,"active_direction":"left"},
               {"type":"lane","directions":["straight"],"active":false},
               {"type":"lane","directions":["straight"],"active":false}""",
            modifier = "left"))!!
        assertEquals(1, g.rampLaneCount())
        assertEquals(-1, g.turnSide)
    }

    @Test fun `a bend is not a diverge`() {
        // Every lane turns right: that is the road bending, not an exit.
        val g = LaneGuidance(
            intArrayOf(Lane.RIGHT, Lane.RIGHT), 0b11, intArrayOf(Lane.RIGHT, Lane.RIGHT))
        assertEquals(0, g.rampLaneCount())
    }

    @Test fun `a plain junction with no turn has no slip road`() {
        val g = LaneGuidance(
            intArrayOf(Lane.STRAIGHT, Lane.STRAIGHT), 0b11, intArrayOf(Lane.STRAIGHT, Lane.STRAIGHT))
        assertEquals(0, g.rampLaneCount())
    }

    // ---- the sign text ------------------------------------------------------

    @Test fun `an exit board survives a banner with no exit number`() {
        val sign = JunctionSign.fromBanners(banner(
            """{"type":"lane","directions":["right"],"active":true}"""), "A12", "Antwerpen")
        assertEquals("A12", sign.exitNumber)
        assertEquals("Antwerpen", sign.destinations)
        assertFalse(sign.isEmpty)
    }
}
