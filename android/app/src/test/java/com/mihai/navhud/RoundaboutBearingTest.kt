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

    /**
     * A roundabout step as Mapbox documents it: the maneuver at the entry,
     * then the intersections round the ring. Approach heading 2 degrees
     * (north). Right-hand traffic, so the ring runs anticlockwise: the east
     * exit first, then an inbound one-way road (entry=false: not an exit),
     * then the north exit, then the west one.
     *
     *   entry  in 182 (back south)  out 62 (veer right onto the ring)  302 ring in
     *   east   side road 95                          -> exit 1, +93
     *   NE     inbound road 40, entry false          -> not counted
     *   north  side road 3                           -> exit 2, +1
     *   west   side road 268                         -> exit 3, -94
     */
    private fun ring(exit: Int, side: String = "right", banner: String = "",
                     before: String = ""","bearing_before":2.0,"bearing_after":62.0""",
                     withIntersections: Boolean = true, lastNode: String? = null): String {
        fun out(n: Int) = if (exit == n) 1 else 2
        val west = lastNode
            ?: """{"location":[4.3094,50.8004],"bearings":[20,268,170],"entry":[false,true,true],"in":0,"out":${out(3)}}"""
        val ints = if (!withIntersections) "" else """,
         "intersections":[
           {"location":[4.3100,50.8000],"bearings":[182,62,302],"entry":[false,true,false],"in":0,"out":1},
           {"location":[4.3104,50.8003],"bearings":[200,95,350],"entry":[false,true,true],"in":0,"out":${out(1)}},
           {"location":[4.3101,50.8007],"bearings":[160,40,300],"entry":[false,false,true],"in":0,"out":2},
           {"location":[4.3098,50.8008],"bearings":[110,3,250],"entry":[false,true,true],"in":0,"out":${out(2)}},
           $west
         ]"""
        return """
    {"distance":900.0,"duration":90.0,
      "geometry":"_wq{_B_mmeG?owH?_pRg^_pR",
      "legs":[{"steps":[
        {"name":"Grote Baan","distance":400.0,"driving_side":"$side"$banner,
         "maneuver":{"type":"depart","modifier":"","location":[4.30,50.80]}},
        {"name":"Ring","distance":200.0,"driving_side":"$side",
         "maneuver":{"type":"roundabout","modifier":"slight right","exit":$exit,
                     "location":[4.3100,50.8000]$before}$ints},
        {"name":"Steenweg","distance":300.0,
         "maneuver":{"type":"turn","modifier":"left","location":[4.32,50.80]}}
      ]}]}
    """.trimIndent()
    }

    /** Exit 3's node, leaving by the road straight back south: 182 from a heading of 2. */
    private val uturnNode =
        """{"location":[4.3094,50.8004],"bearings":[20,170,182],"entry":[false,true,true],"in":0,"out":2}"""

    private fun exitOf(json: String, exit: Int) =
        parse(json).maneuvers.first { it.code == Man.ROUNDABOUT && it.exit == exit }

    // ---- reading the real angle off the route ------------------------------

    @Test fun `the first exit, east, is to the right`() {
        assertEquals(93, exitOf(ring(1), 1).exitBearing)
    }

    @Test fun `the second exit, north, is straight on -- not the table's thirty`() {
        assertEquals(1, exitOf(ring(2), 2).exitBearing)
    }

    @Test fun `the third exit, west, is to the left, and the inbound road is not counted`() {
        // Counting the one-way road into the ring would make "exit 3" the north
        // one and point the arrow straight ahead.
        assertEquals(-94, exitOf(ring(3), 3).exitBearing)
    }

    /**
     * bearing_after is "the direction of travel immediately after the
     * maneuver", and a roundabout's maneuver is the ENTRY: it is the veer onto
     * the ring (62 here, the "slight right"), whatever the exit. Until 1.32 it
     * was the fallback and pointed every such roundabout a little right.
     */
    @Test fun `bearing_after is the veer into the ring, never the exit`() {
        assertNull(exitOf(ring(3, withIntersections = false), 3).exitBearing)
    }

    @Test fun `no bearings and no intersections means null, never zero`() {
        assertNull(exitOf(ring(2, before = "", withIntersections = false), 2).exitBearing)
    }

    @Test fun `the banner's own figure wins over the intersections`() {
        val banner = ""","bannerInstructions":[{"distanceAlongGeometry":400.0,
            "primary":{"text":"Ring","type":"roundabout","degrees":270,"driving_side":"right"}}]"""
        assertEquals(-90, exitOf(ring(3, banner = banner), 3).exitBearing)
    }

    @Test fun `with no bearing_before the heading comes from the entry node`() {
        // in = 182 points back along the approach, so the heading is 2.
        assertEquals(-94, exitOf(ring(3, before = ""), 3).exitBearing)
    }

    @Test fun `a U-turn is all the way round, anticlockwise where traffic keeps right`() {
        assertEquals(-180, exitOf(ring(3, lastNode = uturnNode), 3).exitBearing)
    }

    @Test fun `left-hand traffic is marked, and its U-turn goes the other way round`() {
        val m = exitOf(ring(3, side = "left", lastNode = uturnNode), 3)
        assertTrue(m.leftHand)
        assertEquals(180, m.exitBearing)
        assertTrue(!exitOf(ring(3), 3).leftHand)
    }

    @Test fun `too few exits on the ring means null, not the last one found`() {
        assertNull(exitOf(ring(4), 4).exitBearing)
    }

    @Test fun `left-hand traffic reaches the HUD as flags bit 7, and nothing else moves`() {
        assertEquals(128, HudFrame.FLAG_LEFT_HAND)
        val f = HudFrame(maneuver = Man.ROUNDABOUT, roundaboutExit = 2,
                         flags = HudFrame.FLAG_ROUTE or HudFrame.FLAG_LEFT_HAND, street = "A1")
        val body = f.encode().substringAfter('$').substringBefore('*')
        assertEquals("HUD,-1,0,13,2,0,0,0,192,A1", body)
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
