package com.mihai.navhud.map

import com.mihai.navhud.Geo
import com.mihai.navhud.nav.Area
import com.mihai.navhud.nav.AreaRoads
import com.mihai.navhud.nav.RoadWay

/**
 * The road the arrow is on whenever it is not on the route line: chosen once,
 * then kept.
 *
 * Up to 1.28 the map drew the arrow on a road only while the service trusted a
 * match within 35 m, decided afresh at every fix. Parked indoors with a fix
 * wandering 10-30 m, or with the service's fix gone stale, that trust came and
 * went, and the arrow slid off the tarmac onto the raw fix and stayed there.
 * Waze does not do that: once you are on a road you stay on it, moving only
 * along it, until driving makes it plain you are on another.
 *
 *  - The first road is the best match within [PICK_M]: wide, because a parked
 *    car's fix can be well off its street, and any road beats a garden. With
 *    a known accuracy, within twice it ([PICK_MIN_M]..[PICK_M]).
 *  - Every later fix is projected onto *that* road, so the arrow moves only
 *    forwards or backwards along it.
 *  - Standing still ([STILL_MPS]) the position is frozen: GPS wander is not
 *    movement. Without the car's speed it stays frozen until the fix has
 *    moved more than its accuracy ([HOLD_M] at least) along the road.
 *  - It changes road only while moving ([SWITCH_MPS]) and on clear evidence:
 *    another road nearer by [SWITCH_MARGIN_M] (or ours running against our
 *    heading) for [SWITCH_FIXES] fixes in a row, or we have run off the end of
 *    our way at a junction and a road there runs our way. And at any speed
 *    when the fix is over [LOST_M] from our road: then the lock was wrong.
 *
 * No road data, or nothing within [PICK_M]: no lock, and the caller draws the
 * raw fix. Feed it one fix at a time, GPS only. Pure Kotlin, so it is tested.
 */
class RoadLock {

    companion object {
        /** How far from the fix to look for a road to lock on to. */
        const val PICK_M = 150.0

        /** ...but never under this, however accurate the fix claims to be. */
        const val PICK_MIN_M = 35.0

        /** A fix this far from the locked road means the lock was wrong. */
        const val LOST_M = 100.0

        /** Below this the car is standing: hold the arrow still (~2.5 km/h). */
        const val STILL_MPS = HeadingFusion.STATIONARY_MPS

        /** Road changes, other than [LOST_M], only above this: ~7 km/h. */
        const val SWITCH_MPS = 7.0 / 3.6

        /** How much nearer another road must be to count against ours. */
        const val SWITCH_MARGIN_M = 8.0

        /** ...on this many fixes in a row. */
        const val SWITCH_FIXES = 2

        /** Past the end of our way by this much, we have left it at a junction. */
        const val JUNCTION_M = 3.0

        /** A road further than this across our heading is not ours (as AreaRoads.match). */
        const val ACROSS_DEG = 70.0

        /** Without the car's speed, a hold ends only past this (or the fix's accuracy). */
        const val HOLD_M = 10.0
    }

    var road: RoadWay? = null
        private set

    /** Where the car is on [road]. */
    var lat = Double.NaN
        private set
    var lon = Double.NaN
        private set

    /** Direction of travel along [road] there; null when it has no direction. */
    var bearingDeg: Double? = null
        private set

    /** How far the last fix was from [road], metres. */
    var crossM = 0.0
        private set

    /** Standing still: [lat]/[lon] are being held where they were. */
    var holding = false
        private set

    private var rivalId: Long? = null
    private var rivalFixes = 0
    private var facing: Double? = null

    fun reset() {
        road = null
        lat = Double.NaN
        lon = Double.NaN
        bearingDeg = null
        crossM = 0.0
        holding = false
        rivalId = null
        rivalFixes = 0
    }

    /**
     * @param speedMps   the car's speed: the bus when fresh, else the fix's
     * @param headingDeg the fix's course, or null
     * @param facingDeg  which way the car points (compass, gyro), or null:
     *                   only to say which way along the road it faces before
     *                   it has driven anywhere
     * @param speedFromCar [speedMps] is the bus's, not the fix's
     * @param accuracyM  the fix's accuracy, metres (0 = unknown)
     * @return true when locked: [road], [lat], [lon] say where to draw
     */
    fun update(
        area: Area?, fixLat: Double, fixLon: Double, speedMps: Double, headingDeg: Double?,
        facingDeg: Double? = null, speedFromCar: Boolean = true, accuracyM: Double = 0.0
    ): Boolean {
        facing = facingDeg
        val moving = speedMps >= SWITCH_MPS
        // Below that a GPS course is noise, not a direction.
        val heading = if (moving) headingDeg else null
        val cur = road
        val p = cur?.let { AreaRoads.projectOnto(it.pts, fixLat, fixLon) }
        if (cur != null && p != null) {
            val d = Geo.haversine(fixLat, fixLon, p[0], p[1])
            if (d <= LOST_M) {
                crossM = d
                // A parked receiver reports 1-2 m/s of multipath wander, so on
                // GPS speed alone a hold ends on distance, not speed: the fix
                // must leave the held point by more than its own accuracy.
                holding = if (holding && !speedFromCar)
                    Geo.haversine(p[0], p[1], lat, lon) <= maxOf(accuracyM, HOLD_M)
                else speedMps < STILL_MPS
                if (holding) return true
                if (moving && area != null) {
                    // Only a road nearer than ours can take over, so only
                    // those need looking at.
                    val other = AreaRoads.match(area, fixLat, fixLon, heading, d)
                    if (other != null && other.road.id != cur.id &&
                        outvoted(cur, p, d, other, fixLat, fixLon, heading)) {
                        take(other.road, fixLat, fixLon, heading, speedMps)
                        return true
                    }
                } else {
                    rivalId = null
                    rivalFixes = 0
                }
                place(cur, p, heading)
                return true
            }
        }
        // No road yet, or the fix has left ours: pick afresh. Within twice the
        // fix's accuracy (35..150 m) when it has one: an accurate fix in a car
        // park 80 m from the street is in the car park, not on the street.
        val pickM = if (accuracyM > 0.0) minOf(PICK_M, maxOf(PICK_MIN_M, 2.0 * accuracyM)) else PICK_M
        val m = area?.let { AreaRoads.match(it, fixLat, fixLon, heading, pickM) }
        if (m == null) {
            reset()
            return false
        }
        take(m.road, fixLat, fixLon, heading, speedMps)
        return true
    }

    private fun outvoted(
        cur: RoadWay, p: DoubleArray, d: Double, other: AreaRoads.Match,
        fixLat: Double, fixLon: Double, heading: Double?
    ): Boolean {
        // Run off the end of our way (OSM splits ways at every junction), and
        // the road here runs our way -- match() admits none that does not.
        // Waiting for two fixes would leave the arrow parked on the junction.
        if (heading != null && d > JUNCTION_M && atEnd(cur.pts, p)) return true
        val wrongWay = heading != null && !runsOurWay(cur, fixLat, fixLon, heading)
        if (other.crossM + SWITCH_MARGIN_M < d || wrongWay) {
            if (other.road.id == rivalId) rivalFixes++ else { rivalId = other.road.id; rivalFixes = 1 }
            return rivalFixes >= SWITCH_FIXES
        }
        rivalId = null
        rivalFixes = 0
        return false
    }

    private fun take(r: RoadWay, fixLat: Double, fixLon: Double, heading: Double?, speedMps: Double) {
        road = r
        rivalId = null
        rivalFixes = 0
        val p = AreaRoads.projectOnto(r.pts, fixLat, fixLon) ?: return reset()
        crossM = Geo.haversine(fixLat, fixLon, p[0], p[1])
        holding = speedMps < STILL_MPS
        place(r, p, heading)
    }

    /** Put the car at [p] on [r], facing along it the way we are going. */
    private fun place(r: RoadWay, p: DoubleArray, heading: Double?) {
        lat = p[0]
        lon = p[1]
        val brg = AreaRoads.nearestOn(r.pts, lat, lon)?.second ?: return
        // The way we are going, else the way we were going, else the way the
        // car points, else the legal way.
        val ref = heading ?: bearingDeg ?: facing
        bearingDeg = when {
            ref != null -> if (Geo.bearingDelta(brg, ref) > 90.0) (brg + 180.0) % 360.0 else brg
            r.onewayDir == -1 -> (brg + 180.0) % 360.0
            else -> brg
        }
    }

    /** [p] clamped to an end vertex of the way. A ring (a roundabout) has no end. */
    private fun atEnd(pts: Array<DoubleArray>, p: DoubleArray): Boolean {
        val a = pts.first()
        val b = pts.last()
        if (Geo.haversine(a[0], a[1], b[0], b[1]) < 0.5) return false
        return Geo.haversine(p[0], p[1], a[0], a[1]) < 0.5 ||
            Geo.haversine(p[0], p[1], b[0], b[1]) < 0.5
    }

    /** Can [r] be driven in [heading] here? The same test AreaRoads.match applies. */
    private fun runsOurWay(r: RoadWay, fixLat: Double, fixLon: Double, heading: Double): Boolean {
        val brg = AreaRoads.nearestOn(r.pts, fixLat, fixLon)?.second ?: return true
        val fwd = Geo.bearingDelta(brg, heading)
        val delta = when (r.onewayDir) {
            1 -> fwd
            -1 -> 180.0 - fwd
            else -> minOf(fwd, 180.0 - fwd)
        }
        return delta <= ACROSS_DEG
    }
}
