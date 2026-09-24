package com.mihai.navhud

import com.mihai.navhud.nav.ManeuverPoint
import com.mihai.navhud.nav.Route
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a stream of GPS fixes into HUD frames. This is the whole "navigation
 * engine" -- everything else is plumbing.
 */
class RouteTracker(val route: Route) {

    companion object {
        /**
         * Perpendicular distance that counts as having left the route.
         *
         * 30, not the 45 it was: a side street is often only 30-40 m from the
         * line when you realise you missed the turn, and every metre here is
         * a second of waiting at town speed. The tracker stays within 8 m of
         * its own route on the demo drive, so there is still plenty of margin.
         */
        const val OFF_ROUTE_M = 30.0

        /**
         * How long the car has to be off the line before we believe it, ms.
         *
         * Counted in *time*, not in fixes. It used to be a streak of two
         * fixes, which meant two seconds at 1 Hz -- and the service now asks
         * the chip for everything it will give, which on most hardware is 5 or
         * 10 Hz, where a streak of two would be two tenths of a second and one
         * burst of multipath under a bridge would reroute you.
         *
         * Six hundred milliseconds behaves the same whatever the fix rate --
         * which matters because this is driven from the 4 Hz tick rather than
         * from the GPS callback, so a fix-count streak was really a tick-count
         * streak and told you nothing about elapsed time either. The direction
         * test in RerouteRule is what actually makes a wrong turn fast; this
         * is only the debounce for the cases where direction cannot tell.
         */
        const val OFF_ROUTE_MS = 600L

        /** Hold the last known speed limit this far past an unmapped stretch. */
        const val LIMIT_HOLD_M = 400.0

        /** How far past a maneuver before we advance to the next one. */
        const val MANEUVER_PASSED_M = 8.0

        /** Tolerance before the display calls you a speeder. */
        const val OVER_LIMIT_TOLERANCE_KPH = 2

        const val ARRIVED_M = 25.0

        /**
         * Parked beside the pin counts as arrived too. A route's last metres
         * often run round the block or along the far side of a car park, so
         * the remaining distance can sit above [ARRIVED_M] with the car
         * already standing next to the destination -- and the route never
         * ended. Capped by [ARRIVED_NEAR_REMAINING_M] so a route that passes
         * the pin once before coming back to it is not cut short.
         */
        const val ARRIVED_NEAR_M = 30.0
        const val ARRIVED_NEAR_REMAINING_M = 60.0

        /**
         * Close enough to the line that drawing the car *on* the road is the
         * honest thing to do. Beyond this the fix is telling us something the
         * route does not know about -- a slip road, a car park, a wrong turn --
         * and pinning the marker to the route would be a lie.
         */
        const val SNAP_TRUST_M = 25.0

        /**
         * How long [coast] keeps the car running along the route with no fix.
         * Three minutes covers the long Belgian tunnels (Leopold II, Kennedy)
         * and the Alpine ones on the way east at motorway speed, while a car
         * that has genuinely left the route in the dark is not pinned to it
         * for ever. The map's COAST_ON_ROUTE_MS is the same figure.
         */
        const val COAST_MAX_MS = 180_000L

        /**
         * The same with no car bus, on the last GPS speed held constant: that
         * speed is only right while the car keeps it, so the estimate is given
         * a minute rather than three. The map uses the same figure.
         */
        const val COAST_NO_BUS_MAX_MS = 60_000L
    }

    private var lastSegIdx = 0
    /**
     * Off the line on *this* fix, with no debounce at all. The service uses
     * it as the cheap "is there anything to decide" test before RerouteRule.
     */
    var offLine = false
        private set

    /**
     * Separate flag rather than a 0L sentinel on the timestamp.
     *
     * 0 is a legal clock value -- and it is exactly the one SystemClock's
     * stubbed unit-test implementation returns, so with a sentinel the whole
     * debounce silently disabled itself in every test that did not pass a
     * clock of its own, and any future test that forgot one would assert
     * nothing at all.
     */
    private var offLineSeen = false
    private var offLineSinceMs = 0L

    private var heldLimit = 0
    private var heldLimitAlong = -1e9

    /**
     * Where to ask when the router has no limit for the segment: the OSM road
     * under the car, or its legal default (SpeedDefaults.limitAt). Returns 0
     * for "nothing", -1 for derestricted. Injected so the tracker stays pure.
     *
     * Mapbox's `maxspeed` is missing on a good share of minor roads, and the
     * route used to show a blank sign there -- or, worse, the previous road's
     * limit held on through the turn.
     */
    var limitFallback: ((lat: Double, lon: Double, headingDeg: Double?) -> Int)? = null

    /** The limit from the last [limitAt] was held or came from the fallback. */
    private var limitLowConf = false

    /**
     * The router's limit for this segment; else the fallback; else the last
     * known one held over a short gap in the data, but never past a maneuver
     * -- a turn is exactly where the road, and so the limit, changes.
     */
    private fun limitAt(seg: Int, along: Double, lat: Double, lon: Double, heading: Double?): Int {
        val raw = if (seg < route.limitKph.size) route.limitKph[seg] else 0
        limitLowConf = false
        if (raw != 0) {
            heldLimit = raw
            heldLimitAlong = along
            return raw
        }
        // A limit from a second source, matched by position: flagged, and not
        // held, because it is only as good as the match it came from.
        val fb = limitFallback?.invoke(lat, lon, heading) ?: 0
        if (fb != 0) {
            limitLowConf = true
            return fb
        }
        val turned = route.maneuvers.any { it.alongM > heldLimitAlong && it.alongM <= along }
        if (heldLimit != 0 && !turned && along - heldLimitAlong < LIMIT_HOLD_M) {
            limitLowConf = true         // brief gap in the data, keep showing it
            return heldLimit
        }
        heldLimit = 0
        return 0
    }

    /** Metres along the route at the last fix, or where [coast] has taken it. */
    var alongM = 0.0
        private set

    /** True once OFF_ROUTE_STREAK fixes in a row landed off the line. */
    var offRoute = false
        private set

    var lastCrossM = 0.0
        private set

    /**
     * The fix pulled onto the route line. Every nav app on the market draws the
     * car here rather than at the raw fix -- Google even sells the operation as
     * the Roads API -- because a 10 m urban error puts the raw point in the
     * building next door, and a marker that is not on a road looks broken even
     * when the navigation underneath it is perfect.
     */
    var snappedLat = 0.0
        private set
    var snappedLon = 0.0
        private set

    /** Direction the road runs where we are, or null on a degenerate segment. */
    var roadBearing: Double? = null
        private set

    /** True while the snapped position is close enough to draw instead of the fix. */
    var snapTrusted = false
        private set

    /**
     * The maneuver currently being counted down to, or null on the final leg.
     * Voice guidance keys its announcements on this so a GPS wobble cannot make
     * it repeat itself.
     */
    var nextManeuver: ManeuverPoint? = null
        private set

    /**
     * The maneuver after [nextManeuver], or null when that is the last one.
     * Its [ManeuverPoint.alongM] minus the next one's is the gap between the
     * two -- what "then turn left" needs to decide whether to be said at all.
     */
    var thenManeuver: ManeuverPoint? = null
        private set

    /**
     * The road you are on right now, as opposed to the one you are turning
     * onto. It is the name attached to the last maneuver you passed, because
     * that maneuver is what put you on this road.
     */
    var currentRoadName: String? = null
        private set

    fun update(
        lat: Double,
        lon: Double,
        speedMps: Float,
        bearingDeg: Float?,
        hasFix: Boolean,
        night: Boolean = false,
        /** Monotonic clock. Supplied so the debounce is testable. */
        nowMs: Long = android.os.SystemClock.elapsedRealtime(),
        /**
         * The speed to *show*, when it differs from the one used to place the
         * car: the HUD draws the raw bus speed, positioning wants the scaled one.
         */
        displayMps: Float = speedMps
    ): HudFrame {

        if (!hasFix) {
            snapTrusted = false
            // Losing the fix ends the run. Kept across an outage, one stray
            // off-line fix before a tunnel and one on the way out would satisfy
            // "off the line for 600 ms" with a minute of nothing in between --
            // two samples of evidence, at exactly the multipath moment the
            // debounce exists for.
            offLineSeen = false
            offLine = false
            return HudFrame(
                speedKph = -1,
                limitKph = heldLimit,
                // FLAG_ROUTE even with no fix: the route exists, we simply
                // cannot say where on it we are. Dropping it here would make
                // the HUD blank the arrow at every traffic light under a
                // bridge and paint it back on the far side.
                flags = HudFrame.FLAG_ROUTE or
                        (if (night) HudFrame.FLAG_NIGHT else 0) or
                        (if (offRoute) HudFrame.FLAG_OFF_ROUTE else 0) or
                        HudFrame.FLAG_LOW_CONF
            )
        }

        // Only trust the heading when we are actually moving; a parked phone
        // reports whatever it last saw, which would poison the segment match.
        val heading = if (speedMps > 2.0f) bearingDeg?.toDouble() else null

        val snap = Geo.project(
            route.pts, route.cum, lat, lon,
            fromIdx = lastSegIdx,
            windowMeters = max(400.0, speedMps * 20.0),   // ~20 s of lookahead
            headingDeg = heading
        )
        lastSegIdx = snap.segIndex
        alongM = snap.along
        lastCrossM = snap.cross

        // Off the line right now -- one fix, no debounce. What RerouteRule
        // pairs with the direction test to catch a wrong turn immediately.
        offLine = snap.cross > OFF_ROUTE_M
        if (!offLine) {
            offLineSeen = false
        } else if (!offLineSeen) {
            offLineSeen = true
            offLineSinceMs = nowMs
        }
        offRoute = offLine && offLineSeen && nowMs - offLineSinceMs >= OFF_ROUTE_MS

        // ---- where to draw the car -----------------------------------------
        placeAt(snap.along)
        snapTrusted = snap.cross <= SNAP_TRUST_M && !offRoute

        // ---- speed limit ----------------------------------------------------
        val limit = limitAt(snap.segIndex, snap.along, lat, lon, heading)
        val lowConf = limitLowConf || offRoute

        val nearPin = route.destination.let {
            Geo.haversine(lat, lon, it.lat, it.lon) < ARRIVED_NEAR_M
        }
        return frameAt(snap.along, (displayMps * 3.6f).roundToInt(), limit, lowConf,
                       gpsOk = true, night = night, nearPin = nearPin)
    }

    /**
     * No fix: carry on along the route instead of giving up on it.
     *
     * A tunnel, a covered junction, an underground stretch of ring road: the
     * route is still the best information there is about where the car went,
     * and the car's own speed (or the last GPS one) says how far. So the
     * position runs on along the line, the countdown to the next maneuver
     * keeps falling, and the snap stays trusted -- for [COAST_MAX_MS]. After
     * that, or when the car was not on the route when the fix went, this is
     * exactly the no-fix frame [update] gives.
     *
     * @param advanceM   metres travelled since the last call
     * @param displayKph speed to show, or -1 when only GPS knew it
     * @param sinceFixMs how long since the last real fix
     * @param maxMs      [COAST_MAX_MS] on the car's own speed, and
     *                   [COAST_NO_BUS_MAX_MS] on a held GPS one
     *
     * Never arrives: a coasted position is an estimate, and ending the route
     * on one threw it away in a tunnel or an underground car park.
     */
    fun coast(
        advanceM: Double, displayKph: Int, sinceFixMs: Long, night: Boolean = false,
        maxMs: Long = COAST_MAX_MS
    ): HudFrame {
        if (sinceFixMs > maxMs || !snapTrusted || offRoute) {
            return update(0.0, 0.0, 0f, null, hasFix = false, night = night)
        }
        // As on losing the fix: an off-line run does not survive the outage.
        offLineSeen = false
        offLine = false
        lastCrossM = 0.0
        val along = minOf(route.totalDistanceM, alongM + max(0.0, advanceM))
        while (lastSegIdx < route.cum.size - 2 && route.cum[lastSegIdx + 1] <= along) lastSegIdx++
        alongM = along
        placeAt(along)
        val limit = limitAt(lastSegIdx, along, snappedLat, snappedLon, roadBearing)
        // Not GPS_OK: the HUD says NO GPS, and the distances are an estimate.
        return frameAt(along, displayKph, limit, lowConf = true, gpsOk = false,
                       night = night, nearPin = false, canArrive = false)
    }

    /**
     * Where along the route a fix projects, without moving the tracker. The
     * service publishes this for the *real* fix while the tracker itself is
     * fed a position led forward in time.
     */
    fun alongOf(lat: Double, lon: Double, speedMps: Float, bearingDeg: Float?): Double =
        Geo.project(
            route.pts, route.cum, lat, lon,
            fromIdx = lastSegIdx,
            windowMeters = max(400.0, speedMps * 20.0),
            headingDeg = if (speedMps > 2.0f) bearingDeg?.toDouble() else null
        ).along

    private fun placeAt(along: Double) {
        val sp = Geo.pointAlong(route.pts, route.cum, along)
        snappedLat = sp[0]
        snappedLon = sp[1]
        roadBearing = Geo.bearingAlong(route.pts, route.cum, along)
    }

    private fun frameAt(
        along: Double, speedKph: Int, limit: Int, lowConf: Boolean,
        gpsOk: Boolean, night: Boolean, nearPin: Boolean, canArrive: Boolean = true
    ): HudFrame {
        // ---- next maneuver --------------------------------------------------
        val nextIdx = route.maneuvers.indexOfFirst { it.alongM > along + MANEUVER_PASSED_M }
        val next: ManeuverPoint? = route.maneuvers.getOrNull(nextIdx)
        nextManeuver = next
        thenManeuver = if (nextIdx >= 0) route.maneuvers.getOrNull(nextIdx + 1) else null
        currentRoadName = route.maneuvers
            .lastOrNull { it.alongM <= along + MANEUVER_PASSED_M }
            ?.name?.takeIf { it.isNotBlank() }

        val remaining = max(0.0, route.totalDistanceM - along)
        val arrived = canArrive &&
            (remaining < ARRIVED_M || (remaining < ARRIVED_NEAR_REMAINING_M && nearPin))

        val etaS = if (route.totalDistanceM > 1.0) {
            (route.totalDurationS * (remaining / route.totalDistanceM)).roundToInt()
        } else 0

        val over = limit > 0 && speedKph > limit + OVER_LIMIT_TOLERANCE_KPH

        // This tracker exists only because there is a route, so the bit is
        // unconditional here. It is what tells the HUD it may draw a maneuver.
        var flags = HudFrame.FLAG_ROUTE
        if (gpsOk) flags = flags or HudFrame.FLAG_GPS_OK
        if (over) flags = flags or HudFrame.FLAG_OVER_LIMIT
        if (offRoute) flags = flags or HudFrame.FLAG_OFF_ROUTE
        if (arrived) flags = flags or HudFrame.FLAG_ARRIVED
        if (lowConf) flags = flags or HudFrame.FLAG_LOW_CONF
        if (night) flags = flags or HudFrame.FLAG_NIGHT

        return HudFrame(
            speedKph = speedKph,
            limitKph = limit,
            // No maneuver left means we are on the final approach, so show the
            // destination marker rather than a misleading "carry straight on".
            maneuver = if (arrived || next == null) Man.ARRIVE else next.code,
            roundaboutExit = if (next?.code == Man.ROUNDABOUT) next.exit else 0,
            roundaboutBearing =
                if (next?.code == Man.ROUNDABOUT) next.exitBearing else null,
            distToManeuverM = if (next != null) (next.alongM - along).roundToInt() else 0,
            etaSeconds = etaS,
            remainingM = remaining.roundToInt(),
            flags = flags,
            street = next?.name ?: ""
        )
    }
}
