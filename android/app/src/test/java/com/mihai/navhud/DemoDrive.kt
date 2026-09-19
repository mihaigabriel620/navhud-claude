package com.mihai.navhud

import com.mihai.navhud.alerts.SpeedCamera
import com.mihai.navhud.nav.JunctionSign
import com.mihai.navhud.nav.Lane
import com.mihai.navhud.nav.LaneGuidance
import com.mihai.navhud.nav.ManeuverPoint
import com.mihai.navhud.nav.Route
import kotlin.math.min

/**
 * A canned drive, for tests only.
 *
 * This used to be a "demo mode" button in the app. It earned its keep while
 * there was nothing else to point the tracker at, and stopped earning it the
 * moment the app worked on a real road -- a synthetic drive that always
 * behaves is exactly the thing that stops telling you anything. It lives in
 * the test sources now, where a repeatable 10 km route with known maneuvers,
 * limits, cameras and lanes is genuinely useful.
 *
 * Roughly: out of a residential street in Brussels, right onto a 50 road, a
 * roundabout, a slip road onto a 120 motorway, then off it and arrive.
 */
object DemoDrive {

    private class Section(
        val bearing: Double,
        val lengthM: Double,
        val limitKph: Int,
        val entryManeuver: Int,
        val entryName: String,
        val exit: Int = 0
    )

    private val sections = listOf(
        Section(  20.0,  260.0,  30, Man.DEPART,       "Rue du Depart"),
        Section(  95.0,  740.0,  50, Man.RIGHT,        "Rue de la Loi"),
        Section(  95.0,   60.0,  50, Man.ROUNDABOUT,   "Avenue de Tervueren", exit = 2),
        Section(  40.0, 1150.0,  70, Man.SLIGHT_LEFT,  "Avenue de Tervueren"),
        Section(  75.0,  420.0,  90, Man.RAMP_RIGHT,   "E40 slip road"),
        Section(  75.0, 6200.0, 120, Man.MERGE_LEFT,   "E40"),
        Section( 110.0,  600.0,  70, Man.RAMP_RIGHT,   "Exit 22"),
        Section( 150.0,  480.0,  50, Man.LEFT,         "Chaussee de Louvain"),
        Section( 150.0,   90.0,  30, Man.ARRIVE,       "Destination")
    )

    /** Exit board and lane strip for the motorway exit, so the demo drive
     *  exercises the same UI a real E40 exit produces. */
    private val demoSigns = mapOf(
        "E40" to JunctionSign(null, "Bruxelles / Liège"),
        "Exit 22" to JunctionSign("22", "Liège / Namur")
    )
    private val demoLanes = mapOf(
        "Exit 22" to LaneGuidance(
            intArrayOf(Lane.STRAIGHT, Lane.STRAIGHT,
                       Lane.STRAIGHT or Lane.RIGHT, Lane.RIGHT),
            activeMask = 0b1100
        )
    )

    private const val VERTEX_SPACING_M = 20.0
    private const val START_LAT = 50.8467
    private const val START_LON = 4.3525

    fun buildRoute(): Route {
        val pts = ArrayList<DoubleArray>()
        val segLimits = ArrayList<Int>()
        val maneuvers = ArrayList<ManeuverPoint>()

        var lat = START_LAT
        var lon = START_LON
        var along = 0.0
        pts.add(doubleArrayOf(lat, lon))

        for (s in sections) {
            maneuvers.add(
                ManeuverPoint(
                    along, s.entryManeuver, s.exit, s.entryName,
                    lanes = demoLanes[s.entryName],
                    sign = demoSigns[s.entryName]
                )
            )
            var done = 0.0
            while (done < s.lengthM - 0.001) {
                val step = min(VERTEX_SPACING_M, s.lengthM - done)
                val p = Geo.destination(lat, lon, s.bearing, step)
                lat = p[0]; lon = p[1]
                pts.add(doubleArrayOf(lat, lon))
                segLimits.add(s.limitKph)
                done += step
                along += step
            }
        }

        val arr = pts.toTypedArray()
        val cum = Geo.cumulative(arr)

        // Punch a 300 m hole in the speed-limit data on the motorway, so the
        // hold-over logic and the low-confidence ring get exercised too.
        val limits = segLimits.toIntArray()
        val holeStart = (limits.size * 0.62).toInt()
        for (i in holeStart until min(limits.size, holeStart + 15)) limits[i] = 0

        val total = cum.last()
        return Route(
            pts = arr,
            cum = cum,
            limitKph = limits,
            maneuvers = maneuvers,
            totalDistanceM = total,
            totalDurationS = total / 16.0,     // ~58 km/h average
            provider = "Demo"
        )
    }

    /**
     * Two cameras on the demo route, so the alert path can be seen working
     * without driving past a real one: a fixed camera in town and an average
     * speed check on the motorway.
     */
    fun demoCameras(route: Route): List<SpeedCamera> {
        fun at(along: Double, limit: Int, kind: SpeedCamera.Kind, id: Long): SpeedCamera {
            val p = Geo.pointAlong(route.pts, route.cum, along)
            return SpeedCamera(id, p[0], p[1], along, limit, null, kind)
        }
        return listOf(
            at(900.0, 50, SpeedCamera.Kind.FIXED, 1L),
            at(4200.0, 120, SpeedCamera.Kind.AVERAGE, 2L),
            at(8900.0, 50, SpeedCamera.Kind.FIXED, 3L)
        )
    }

    /**
     * A plausible speed for a given point on the demo route: obeys the limit,
     * eases off for the turns, and does 8 km/h over on the motorway because
     * that is what makes the red "over limit" state show up.
     */
    fun speedMpsAt(route: Route, alongM: Double): Float {
        val idx = route.pts.indices.firstOrNull { route.cum[it] >= alongM } ?: 0
        val seg = (idx - 1).coerceIn(0, route.limitKph.size - 1)
        val limit = route.limitKph[seg].takeIf { it > 0 } ?: 70

        // Ease off approaching a turn. Only maneuvers *ahead* count -- you brake
        // before a corner, not after it, and the depart maneuver sits at 0 m.
        val nextMan = route.maneuvers.firstOrNull { it.alongM > alongM + 8.0 }
        val d = if (nextMan != null) nextMan.alongM - alongM else 1e9
        val factor = when {
            d < 25.0 -> 0.35
            d < 80.0 -> 0.35 + 0.65 * ((d - 25.0) / 55.0)
            else -> 1.0
        }
        val target = limit * factor + (if (limit >= 100) 8.0 else 0.0)
        // gentle ramp away from a standstill at the very start
        val launch = min(1.0, 0.25 + alongM / 60.0)
        return ((target * launch) / 3.6).toFloat()
    }
}
