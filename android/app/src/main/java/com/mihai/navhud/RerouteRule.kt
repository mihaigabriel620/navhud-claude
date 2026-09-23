package com.mihai.navhud

/**
 * When to ask for a new route.
 *
 * Pulled out of the service so the timing can be tested against a simulated
 * wrong turn instead of a real one. The reported symptom was "when I go a
 * different route than the one it shows it takes a hell amount of time to
 * calculate a new route", and there were three separate causes:
 *
 *  1. a four-second confirmation delay *on top of* the three consecutive
 *     off-route fixes the tracker already requires;
 *  2. a fifteen-second cooldown that was armed every time a route was adopted,
 *     including the first one — so the first missed turn of a drive was the
 *     slowest possible case;
 *  3. no shortcut for the obvious case where you are plainly on another road.
 *
 * All three are gone. The tracker's own streak is the debounce; this adds a
 * short confirmation, skips even that when you are clearly elsewhere, and keeps
 * a cooldown only to stop a failing network request spinning.
 */
object RerouteRule {

    /** Confirmation on top of the tracker's off-route streak. */
    const val CONFIRM_MS = 1200L

    /** Past this far off the line, no confirmation is needed at all. */
    const val OBVIOUS_CROSS_M = 90.0

    /**
     * Heading this far from the route's direction means you have turned off it.
     *
     * This is the shortcut that makes a missed turn feel instant. Distance
     * alone is slow evidence at a junction: turn right where the route went
     * straight on and you are still only ten or fifteen metres from the line
     * for the first couple of seconds, so a purely distance-based test waits
     * for you to get far enough away to be sure — which is the "hell amount of
     * time" the driver notices. Direction is fast evidence: pointing
     * [TURNED_OFF_DEG] away from where the route runs, [TURNED_OFF_CROSS_M]
     * off the line, for [TURNED_OFF_MS], means the turn has already happened
     * -- well before the car is [RouteTracker.OFF_ROUTE_M] away.
     *
     * A route polyline cuts corners, so a bend can put twenty or thirty
     * degrees between the car and the segment it is snapped to for a moment.
     * The two seconds are what keep that, and a single multipath fix, from
     * counting.
     */
    const val TURNED_OFF_DEG = 45.0
    const val TURNED_OFF_CROSS_M = 15.0
    const val TURNED_OFF_MS = 2000L

    /** Minimum gap between two *requests*, so a failure cannot loop. */
    const val COOLDOWN_MS = 6000L

    /** The heading test on its own, for the caller that times it. */
    fun turnedOff(crossM: Double, headingOffDeg: Double?): Boolean =
        headingOffDeg != null && headingOffDeg > TURNED_OFF_DEG && crossM > TURNED_OFF_CROSS_M

    /**
     * @param offRoute        the tracker's debounced verdict
     * @param crossM          current perpendicular distance from the route
     * @param offRouteSinceMs when offRoute first became true, 0 if it is not
     * @param lastRequestMs   when a reroute was last *requested*, 0 if never
     * @param nowMs           monotonic clock
     * @param headingOffDeg   |car heading - route direction| here, null if the
     *                        car has no trustworthy heading
     * @param turnedOffSinceMs when [turnedOff] first became true in the current
     *                        unbroken run, 0 if it is not true now
     */
    fun shouldReroute(
        offRoute: Boolean,
        crossM: Double,
        offRouteSinceMs: Long,
        lastRequestMs: Long,
        nowMs: Long,
        headingOffDeg: Double? = null,
        turnedOffSinceMs: Long = 0L
    ): Boolean {
        // The cooldown is the one thing nothing may skip: it is what stops a
        // failing network request spinning.
        if (lastRequestMs != 0L && nowMs - lastRequestMs < COOLDOWN_MS) return false

        // The direction path. It deliberately does not wait for the debounced
        // `offRoute`, because that debounce exists for the case where direction
        // cannot tell us anything -- a parallel service road, a wide junction,
        // GPS drift -- and this is not that case.
        if (turnedOffSinceMs != 0L && turnedOff(crossM, headingOffDeg) &&
            nowMs - turnedOffSinceMs >= TURNED_OFF_MS) return true

        if (!offRoute || offRouteSinceMs == 0L) return false
        if (crossM > OBVIOUS_CROSS_M) return true
        return nowMs - offRouteSinceMs >= CONFIRM_MS
    }
}
