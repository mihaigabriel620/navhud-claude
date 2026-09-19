package com.mihai.navhud

import com.mihai.navhud.nav.Route
import kotlin.math.max

/**
 * Which of the router's alternatives to actually drive.
 *
 * The router is asked for alternatives on every reroute, and it returns them
 * sorted by duration. Taken at face value that is the right answer, and for a
 * first route it is. On a *reroute* it frequently is not: having just missed a
 * turn, the shortest path back to the destination is usually to turn round,
 * so the fastest route the router hands back opens with a U-turn.
 *
 * On a residential street that advice is merely annoying. On a motorway it is
 * useless -- there is no U-turn to make, the next legal turnaround is at the
 * far end of the slip road several kilometres on, and the display sits there
 * showing an arrow the driver cannot obey until the tracker decides they are
 * off route again and asks for yet another route that says the same thing.
 *
 * So: if the fastest route starts by doubling back, prefer the quickest
 * alternative that carries on forwards, as long as it is not much slower. The
 * tolerance is deliberately generous, because a couple of minutes is a much
 * smaller cost than an instruction that cannot be followed. If every
 * alternative doubles back, or the forward ones are all far slower, the
 * fastest route stands -- turning round really is the answer sometimes.
 */
object RouteChoice {

    /**
     * A U-turn this soon after the start is the router turning you round.
     *
     * Further along than this it is a genuine part of the route -- a dead end
     * to come back out of, a turn-round on a dual carriageway to reach the
     * other side -- and has nothing to do with where the reroute began.
     */
    const val DOUBLE_BACK_M = 400.0

    /** How much slower a forward route may be, as a fraction of the fastest. */
    const val TOLERANCE_FRACTION = 0.15

    /**
     * ...but never less than this many seconds.
     *
     * The fraction alone collapses on short routes: 15 % of the four minutes
     * left of a journey is 36 seconds, which rules out every sensible way
     * round and leaves the U-turn winning by default. The floor is what makes
     * the rule work near the destination, where missed turns are commonest.
     */
    const val TOLERANCE_FLOOR_S = 90.0

    /** True when this route's first instruction of note is to turn round. */
    fun doublesBack(r: Route): Boolean =
        r.maneuvers.any { it.code == Man.UTURN && it.alongM <= DOUBLE_BACK_M }

    /**
     * @param routes the router's alternatives, fastest first
     * @return the route to drive, or null if there were none
     */
    fun pick(routes: List<Route>): Route? {
        if (routes.isEmpty()) return null

        val fastest = routes.first()
        // The common case, and the only one where the router needs no help.
        if (!doublesBack(fastest)) return fastest

        val budgetS = fastest.totalDurationS +
            max(TOLERANCE_FLOOR_S, fastest.totalDurationS * TOLERANCE_FRACTION)

        return routes.asSequence()
            .filter { !doublesBack(it) }
            .filter { it.totalDurationS <= budgetS }
            .minByOrNull { it.totalDurationS }
            ?: fastest
    }
}
