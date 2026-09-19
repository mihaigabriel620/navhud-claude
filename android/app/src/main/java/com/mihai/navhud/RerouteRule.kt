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
     * time" the driver notices. Direction is fast evidence: the moment the car
     * is pointing sixty degrees away from where the route runs *and* it is off
     * the line at all, the turn has already happened.
     *
     * Sixty degrees, not less, because a route polyline cuts corners and a
     * bend can put twenty or thirty degrees between the car and the segment it
     * is snapped to without anyone having gone anywhere.
     */
    const val TURNED_OFF_DEG = 60.0

    /** Minimum gap between two *requests*, so a failure cannot loop. */
    const val COOLDOWN_MS = 6000L

    /**
     * @param offRoute        the tracker's debounced verdict
     * @param crossM          current perpendicular distance from the route
     * @param offRouteSinceMs when offRoute first became true, 0 if it is not
     * @param lastRequestMs   when a reroute was last *requested*, 0 if never
     * @param nowMs           monotonic clock
     * @param headingOffDeg   |car heading - route direction| here, null if the
     *                        car has no trustworthy heading
     */
    fun shouldReroute(
        offRoute: Boolean,
        crossM: Double,
        offRouteSinceMs: Long,
        lastRequestMs: Long,
        nowMs: Long,
        headingOffDeg: Double? = null,
        offLine: Boolean = offRoute
    ): Boolean {
        // The cooldown is the one thing nothing may skip: it is what stops a
        // failing network request spinning.
        if (lastRequestMs != 0L && nowMs - lastRequestMs < COOLDOWN_MS) return false

        // The instant path. Off the line *on this fix* and pointing well away
        // from where the route runs: the turn has already happened, there is
        // nothing left to confirm, and waiting is the difference between this
        // and the apps it is being compared to.
        //
        // It deliberately does not wait for the debounced `offRoute`, because
        // that debounce exists for the case where direction cannot tell us
        // anything -- a parallel service road, a wide junction, GPS drift --
        // and this is not that case.
        if (offLine && headingOffDeg != null && headingOffDeg > TURNED_OFF_DEG) return true

        if (!offRoute || offRouteSinceMs == 0L) return false
        if (crossM > OBVIOUS_CROSS_M) return true
        return nowMs - offRouteSinceMs >= CONFIRM_MS
    }
}
