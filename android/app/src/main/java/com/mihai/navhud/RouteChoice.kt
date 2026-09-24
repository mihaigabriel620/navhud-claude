package com.mihai.navhud

import com.mihai.navhud.nav.AreaRoads
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

    // ---- going back without saying U-turn ------------------------------------
    //
    // `bearings` stops the router opening with a U-turn, so it finds the next
    // best way back instead: round the block, or all the way round a roundabout,
    // and back onto the line you just left. No UTURN maneuver anywhere, so
    // doublesBack() cannot see it. Waze keeps you going forward; these catch
    // the loop by geometry and make it pay.

    /** Seconds added to a route that loops back, so a forward one wins. */
    const val LOOP_PENALTY_S = 180.0

    /** Only the start of a route is about where the reroute began. */
    const val LOOP_WINDOW_M = 3000.0

    /** Passing this close to the departure point is going back to it... */
    const val DEPARTURE_NEAR_M = 30.0

    /** ...once the route has first got this much further away than it started. */
    const val DEPARTURE_LEFT_M = 60.0

    /** Running back along the recent track: this close to it... */
    const val TRACK_NEAR_M = 20.0

    /** ...within this of its opposite direction... */
    const val TRACK_REVERSE_DEG = 30.0

    /** ...for more than this. */
    const val TRACK_REVERSE_M = 100.0

    private const val SAMPLE_M = 10.0

    /**
     * What the car just did.
     *
     * @param departure where it left the old route, {lat, lon}, if known
     * @param track     its recent fixes, oldest first -- see [Breadcrumb]
     */
    class Backtrack(val departure: DoubleArray?, val track: List<DoubleArray>)

    /** True when the route turns back to where the car came from. */
    fun loopsBack(r: Route, b: Backtrack): Boolean =
        (b.departure != null && returnsTo(r, b.departure)) || reversesTrack(r, b.track)

    /**
     * Gets well away from the departure point and then comes back past it.
     *
     * "Well away" is measured from where the route starts, so a driver who has
     * already turned round and is heading back towards the junction is not
     * penalised for being routed the way they are already going.
     */
    private fun returnsTo(r: Route, dep: DoubleArray): Boolean {
        if (r.pts.size < 2) return false
        val start = Geo.haversine(r.pts[0][0], r.pts[0][1], dep[0], dep[1])
        var left = false
        var i = 0
        while (i < r.pts.size - 1 && r.cum[i] <= LOOP_WINDOW_M) {
            val b = r.pts[i + 1]
            if (!left) {
                left = Geo.haversine(b[0], b[1], dep[0], dep[1]) > start + DEPARTURE_LEFT_M
            } else {
                val d = AreaRoads.nearestOn(arrayOf(r.pts[i], b), dep[0], dep[1])?.first
                if (d != null && d < DEPARTURE_NEAR_M) return true
            }
            i++
        }
        return false
    }

    /**
     * Runs back down the road just driven for more than [TRACK_REVERSE_M].
     *
     * Only the track since the car last pointed the other way counts: a driver
     * who turned round on their own is heading back already, and being sent
     * onwards is not being sent back.
     */
    private fun reversesTrack(r: Route, track: List<DoubleArray>): Boolean {
        val t = sinceLastTurnRound(track)
        if (t.size < 2 || r.pts.size < 2) return false
        val end = minOf(LOOP_WINDOW_M, r.cum.last())
        var reversed = 0.0
        var a = SAMPLE_M / 2
        while (a < end) {
            val p = Geo.pointAlong(r.pts, r.cum, a)
            val brg = Geo.bearingAlong(r.pts, r.cum, a)
            if (brg != null) {
                for (j in 0 until t.size - 1) {
                    val n = AreaRoads.nearestOn(arrayOf(t[j], t[j + 1]), p[0], p[1])
                    val tb = n?.second ?: continue
                    if (n.first <= TRACK_NEAR_M &&
                        Geo.bearingDelta(brg, tb) >= 180.0 - TRACK_REVERSE_DEG) {
                        reversed += SAMPLE_M
                        break
                    }
                }
                if (reversed > TRACK_REVERSE_M) return true
            }
            a += SAMPLE_M
        }
        return false
    }

    /** The tail of the track after its last stretch opposite to the final heading. */
    private fun sinceLastTurnRound(track: List<DoubleArray>): List<DoubleArray> {
        if (track.size < 3) return track
        val n = track.size
        val last = Geo.bearing(track[n - 2][0], track[n - 2][1], track[n - 1][0], track[n - 1][1])
        for (j in n - 2 downTo 0) {
            val b = Geo.bearing(track[j][0], track[j][1], track[j + 1][0], track[j + 1][1])
            if (Geo.bearingDelta(b, last) >= 180.0 - TRACK_REVERSE_DEG) return track.subList(j + 1, n)
        }
        return track
    }

    /**
     * @param routes the router's alternatives, fastest first
     * @param back   what the car just did, on a reroute; null on a first route
     * @return the route to drive, or null if there were none
     */
    fun pick(routes: List<Route>, back: Backtrack? = null): Route? {
        if (routes.isEmpty()) return null

        // Durations as the choice sees them: a route that loops back pays
        // [LOOP_PENALTY_S]. With nothing to go on, the router's order stands.
        val cost = HashMap<Route, Double>()
        for (r in routes) {
            cost[r] = r.totalDurationS +
                if (back != null && loopsBack(r, back)) LOOP_PENALTY_S else 0.0
        }
        val ranked = if (back == null) routes else routes.sortedBy { cost.getValue(it) }

        val fastest = ranked.first()
        // The common case, and the only one where the router needs no help.
        if (!doublesBack(fastest)) return fastest

        val fastestS = cost.getValue(fastest)
        val budgetS = fastestS + max(TOLERANCE_FLOOR_S, fastestS * TOLERANCE_FRACTION)

        return ranked.asSequence()
            .filter { !doublesBack(it) }
            .filter { cost.getValue(it) <= budgetS }
            .minByOrNull { cost.getValue(it) }
            ?: fastest
    }
}

/**
 * The last few hundred metres the car actually drove, for [RouteChoice].
 *
 * Fed from the GPS callback and read on a reroute, which happen on different
 * threads, hence the locking.
 */
class Breadcrumb(private val keepM: Double = KEEP_M) {

    companion object {
        const val KEEP_M = 300.0

        /** Closer than this to the last point adds nothing but noise. */
        const val MIN_STEP_M = 5.0

        /** A jump this long is a lost fix, not a road; start again. */
        const val MAX_STEP_M = 200.0
    }

    private val pts = ArrayDeque<DoubleArray>()
    private var lengthM = 0.0

    @Synchronized fun add(lat: Double, lon: Double) {
        val last = pts.lastOrNull()
        if (last != null) {
            val d = Geo.haversine(last[0], last[1], lat, lon)
            if (d < MIN_STEP_M) return
            if (d > MAX_STEP_M) { pts.clear(); lengthM = 0.0 } else lengthM += d
        }
        pts.addLast(doubleArrayOf(lat, lon))
        // Drop from the front while what is left is still long enough.
        while (pts.size > 2) {
            val d = Geo.haversine(pts[0][0], pts[0][1], pts[1][0], pts[1][1])
            if (lengthM - d < keepM) break
            pts.removeFirst()
            lengthM -= d
        }
    }

    @Synchronized fun snapshot(): List<DoubleArray> = pts.toList()

    @Synchronized fun clear() { pts.clear(); lengthM = 0.0 }

    val length: Double @Synchronized get() = lengthM
}
