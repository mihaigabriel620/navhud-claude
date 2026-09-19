package com.mihai.navhud

import com.mihai.navhud.nav.MapboxProvider
import com.mihai.navhud.nav.Route
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A roundabout exit has a direction, and an exit NUMBER is not one.
 *
 * Both displays used to derive the arrow from the exit number through the same
 * seven-entry table -- 100, 30, -30, -80, -120, -150, -170 degrees. On a
 * three-exit roundabout "exit 2" is almost always dead ahead and the table said
 * 30. On a five-exit one it might be 45. The arrow was always plausible and
 * rarely right, which is the worst way for a display to be wrong.
 *
 * Mapbox has carried the real answer all along: `bearing_before` and
 * `bearing_after` on the step's maneuver. Nobody was reading them.
 */
class RoundaboutBearingTest {

    private fun parse(json: String): Route =
        MapboxProvider(token = "test", language = "en").parseRoute(JSONObject(json))

    /** Approach heading, exit heading, and whether Mapbox reports them at all. */
    private fun route(before: String, after: String, exit: Int = 2): String = """
    {"distance":900.0,"duration":90.0,
      "geometry":"_wq{_B_mmeG?owH?_pRg^_pR",
      "legs":[{"steps":[
        {"name":"Grote Baan","distance":400.0,
         "maneuver":{"type":"depart","modifier":"","location":[4.30,50.80]}},
        {"name":"Ring","distance":200.0,
         "maneuver":{"type":"roundabout","modifier":"straight","exit":$exit,
                     "location":[4.31,50.80]$before$after}},
        {"name":"Steenweg","distance":300.0,
         "maneuver":{"type":"turn","modifier":"left","location":[4.32,50.80]}}
      ]}]}
    """.trimIndent()

    private fun bearings(before: Double, after: Double) =
        route(""","bearing_before":$before""", ""","bearing_after":$after""")

    // ---- reading the real angle off the route ------------------------------

    @Test fun `a straight-ahead exit is zero, not the table's thirty`() {
        val r = parse(bearings(90.0, 90.0))
        val m = r.maneuvers.first { it.exit == 2 }
        assertEquals(0, m.exitBearing)
    }

    @Test fun `a right-hand exit is positive`() {
        val m = parse(bearings(0.0, 90.0)).maneuvers.first { it.exit == 2 }
        assertEquals(90, m.exitBearing)
    }

    @Test fun `a left-hand exit is negative`() {
        val m = parse(bearings(0.0, 270.0)).maneuvers.first { it.exit == 2 }
        assertEquals(-90, m.exitBearing)
    }

    /** 350 -> 10 is a twenty-degree turn to the right, not 340 to the left. */
    @Test fun `the wrap at north is a small turn, not a huge one`() {
        val m = parse(bearings(350.0, 10.0)).maneuvers.first { it.exit == 2 }
        assertEquals(20, m.exitBearing)
    }

    @Test fun `the wrap the other way is a small turn too`() {
        val m = parse(bearings(10.0, 350.0)).maneuvers.first { it.exit == 2 }
        assertEquals(-20, m.exitBearing)
    }

    /**
     * Mapbox omits these on some steps, and a missing field read as 0.0 would
     * mean "straight ahead" -- stated with total confidence, on a roundabout
     * where the route might go hard left.
     */
    @Test fun `no bearings at all means null, never zero`() {
        val m = parse(route("", "")).maneuvers.first { it.exit == 2 }
        assertNull(m.exitBearing)
    }

    @Test fun `half the pair is not enough`() {
        val m = parse(route(""","bearing_before":90.0""", "")).maneuvers.first { it.exit == 2 }
        assertNull(m.exitBearing)
    }

    // ---- what goes on the wire ---------------------------------------------

    @Test fun `the RAB line carries the exit and the angle, with a checksum`() {
        val f = HudFrame(maneuver = Man.ROUNDABOUT, roundaboutExit = 3, roundaboutBearing = -45)
        val line = f.rabLine()
        assertNotNull(line)
        assertTrue(line!!, line.startsWith("\$RAB,3,-45*"))
        assertTrue(line.endsWith("\r\n"))
        // the checksum must actually be the XOR of the body
        val body = line.substring(1, line.indexOf('*'))
        val sent = line.substring(line.indexOf('*') + 1).trim().toInt(16)
        assertEquals(HudFrame.checksum(body), sent)
    }

    @Test fun `zero degrees is a real bearing and must reach the board`() {
        val f = HudFrame(maneuver = Man.ROUNDABOUT, roundaboutExit = 2, roundaboutBearing = 0)
        assertTrue(f.rabLine()!!.startsWith("\$RAB,2,0*"))
    }

    @Test fun `no bearing means no line, and the board falls back on its own`() {
        assertNull(HudFrame(maneuver = Man.ROUNDABOUT, roundaboutExit = 2).rabLine())
    }

    @Test fun `an angle with no exit to pin it to is not sent`() {
        // The board ties the angle to an exit number so an angle from the LAST
        // roundabout can never be aimed at the next one. Without an exit there
        // is nothing to tie it to.
        assertNull(HudFrame(roundaboutExit = 0, roundaboutBearing = 40).rabLine())
    }

    @Test fun `an impossible angle is dropped rather than clamped`() {
        // A clamped corrupt value is still an arrow pointing somewhere with
        // total confidence.
        assertNull(HudFrame(roundaboutExit = 2, roundaboutBearing = 999).rabLine())
        assertNull(HudFrame(roundaboutExit = 2, roundaboutBearing = -999).rabLine())
    }

    @Test fun `the plain HUD frame is untouched, so older firmware still parses`() {
        val f = HudFrame(speedKph = 50, limitKph = 70, maneuver = Man.ROUNDABOUT,
                         roundaboutExit = 2, roundaboutBearing = -35, street = "Grote Baan")
        val body = f.encode().substringAfter('$').substringBefore('*')
        assertEquals(10, body.split(',').size)
        assertTrue(body, body.startsWith("HUD,50,70,"))
        assertTrue(body, body.endsWith(",Grote Baan"))
    }
}
