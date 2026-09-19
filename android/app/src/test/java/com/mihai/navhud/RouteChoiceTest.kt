package com.mihai.navhud

import com.mihai.navhud.nav.ManeuverPoint
import com.mihai.navhud.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reroute that told you to turn round on a motorway.
 *
 * The router sorts its alternatives by duration and it is not wrong to: the
 * way back past the junction you just missed really is the quickest line to
 * the destination. It is also, on a dual carriageway, an instruction nobody
 * can obey -- so the app spent the next few kilometres showing a U-turn arrow,
 * decided the car was off route, asked again, and got the same answer back.
 *
 * These tests pin the escape: prefer a forward alternative when the router
 * offers one and it is not much slower, and otherwise leave the router's
 * choice alone, because sometimes turning round genuinely is the answer.
 */
class RouteChoiceTest {

    /** Geometry is irrelevant here; only duration and the maneuver list matter. */
    private fun route(durationS: Double, uTurnAtM: Double? = null): Route {
        val pts = arrayOf(doubleArrayOf(50.85, 4.30), doubleArrayOf(50.86, 4.31))
        val mans = ArrayList<ManeuverPoint>()
        mans.add(ManeuverPoint(0.0, Man.DEPART, 0, "start"))
        if (uTurnAtM != null) mans.add(ManeuverPoint(uTurnAtM, Man.UTURN, 0, "turn around"))
        mans.add(ManeuverPoint(1000.0, Man.ARRIVE, 0, "there"))
        return Route(
            pts = pts, cum = doubleArrayOf(0.0, 1000.0), limitKph = IntArray(1),
            maneuvers = mans, totalDistanceM = 1000.0, totalDurationS = durationS,
            provider = "test"
        )
    }

    @Test fun `no routes, no choice`() {
        assertNull(RouteChoice.pick(emptyList()))
    }

    // ---- what counts as doubling back ---------------------------------------

    @Test fun `a U-turn at the start is the router turning you round`() {
        assertTrue(RouteChoice.doublesBack(route(600.0, uTurnAtM = 50.0)))
        assertTrue("on the boundary still counts",
            RouteChoice.doublesBack(route(600.0, uTurnAtM = RouteChoice.DOUBLE_BACK_M)))
    }

    @Test fun `a U-turn further along is part of the journey, not the reroute`() {
        // Coming back out of a dead end ten kilometres away has nothing to do
        // with where the reroute began, and must not disqualify the route.
        assertFalse(RouteChoice.doublesBack(route(600.0, uTurnAtM = 10_000.0)))
        assertFalse(RouteChoice.doublesBack(route(600.0)))
    }

    // ---- the choice ---------------------------------------------------------

    @Test fun `the fastest route is left alone when it goes forwards`() {
        val fastest = route(600.0)
        val slower = route(700.0)
        assertSame(fastest, RouteChoice.pick(listOf(fastest, slower)))
    }

    @Test fun `a forward alternative beats a U-turn it is only a little slower than`() {
        val uTurn = route(600.0, uTurnAtM = 40.0)
        val forward = route(660.0)                      // +60 s, inside the 90 s floor
        assertSame(forward, RouteChoice.pick(listOf(uTurn, forward)))
    }

    @Test fun `the quickest forward alternative wins, not the first one offered`() {
        val uTurn = route(600.0, uTurnAtM = 40.0)
        val slowForward = route(680.0)
        val quickForward = route(640.0)
        // The router's own order puts the slower one first; we must not take it.
        assertSame(quickForward, RouteChoice.pick(listOf(uTurn, slowForward, quickForward)))
    }

    @Test fun `a forward route that is far slower does not justify the detour`() {
        val uTurn = route(600.0, uTurnAtM = 40.0)
        // 600 s + max(90 s, 15 % of 600 s) = 690 s of budget.
        assertSame(uTurn, RouteChoice.pick(listOf(uTurn, route(691.0))))
        val justInside = route(690.0)
        assertSame("the boundary itself is acceptable",
            justInside, RouteChoice.pick(listOf(uTurn, justInside)))
    }

    @Test fun `when every alternative turns you round, the fastest stands`() {
        val fastest = route(600.0, uTurnAtM = 40.0)
        val other = route(650.0, uTurnAtM = 120.0)
        // Sometimes there really is no way on -- a cul-de-sac, a closed road.
        assertSame(fastest, RouteChoice.pick(listOf(fastest, other)))
    }

    /**
     * The regression the floor exists for.
     *
     * A fraction on its own vanishes near the destination: 15 % of the four
     * minutes left of a journey is 36 seconds, which rules out every sensible
     * way round and hands the U-turn the win by default -- in the part of the
     * drive where turns are closest together and missing one is likeliest.
     */
    @Test fun `on a short route the floor, not the fraction, decides`() {
        val uTurn = route(240.0, uTurnAtM = 30.0)
        val forward = route(310.0)                      // +70 s: 29 %, but under 90 s
        assertTrue("the fraction alone would reject this",
            70.0 > 240.0 * RouteChoice.TOLERANCE_FRACTION)
        assertSame(forward, RouteChoice.pick(listOf(uTurn, forward)))
    }

    @Test fun `a single route is returned whatever it says`() {
        val only = route(600.0, uTurnAtM = 10.0)
        assertSame(only, RouteChoice.pick(listOf(only)))
    }

    @Test fun `the tolerance is the published one`() {
        assertEquals(400.0, RouteChoice.DOUBLE_BACK_M, 0.0)
        assertEquals(0.15, RouteChoice.TOLERANCE_FRACTION, 0.0)
        assertEquals(90.0, RouteChoice.TOLERANCE_FLOOR_S, 0.0)
    }
}
