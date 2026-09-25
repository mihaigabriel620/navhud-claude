package com.mihai.navhud

import com.mihai.navhud.alerts.CameraAlert
import com.mihai.navhud.alerts.CameraPolicy
import com.mihai.navhud.alerts.CameraWatcher
import com.mihai.navhud.alerts.SpeedCamera
import com.mihai.navhud.nav.SpeedDefaults
import com.mihai.navhud.nav.Area
import com.mihai.navhud.nav.AreaRoads
import kotlin.math.roundToInt

/**
 * Navigation with nowhere to go.
 *
 * Open the app, drive: speed, the limit of the road you are actually on, the
 * road's name, and camera warnings — no destination, no route, no instructions.
 * This is how Waze and Google Maps behave before you type anything, and it is
 * how the app spends most of its life, because you already know the way to
 * work.
 *
 * The engine is different from RouteTracker's in one important respect. With a
 * route there is a single line to project onto and "along the route" is a real
 * number. Here there is a network, and the interesting question is which road
 * of several nearby you are on — which `AreaRoads.match` answers with distance,
 * direction and road importance together.
 */
class FreeTracker {

    companion object {
        /** Hold the last known limit this long after losing the match. */
        const val LIMIT_HOLD_MS = 20_000L

        /** Only warn about cameras roughly ahead of us. */
        const val CAMERA_CONE_DEG = 60.0

        /** Ignore a camera further away than this, whatever the speed band. */
        const val CAMERA_MAX_M = 2000.0

        const val OVER_LIMIT_TOLERANCE_KPH = 2

        /**
         * Close enough to a road that drawing the car on it is honest.
         *
         * The same distance the matcher uses to admit a road at all, and that
         * is the point: it used to be 22 m against the matcher's 35 m, so
         * between the two the app would put the road's name in the pill and
         * then draw the car in the middle of somebody's garden — naming a road
         * it would not stand on. If we are confident enough to say which road
         * you are on, we are confident enough to draw you on it.
         */
        const val SNAP_TRUST_M = AreaRoads.MATCH_LIMIT_M

        /**
         * How far off the centreline of *our* road a camera may be and still
         * be ours. Generous, because cameras are mounted on poles and gantries
         * set back from the carriageway, and a dual carriageway's two halves
         * are separate ways in OpenStreetMap — but nothing like generous
         * enough to pick up the camera on the road running parallel.
         */
        const val CAMERA_ON_ROAD_M = 18.0

        /**
         * How much nearer another road has to be before we conclude the camera
         * belongs to it and not to us. The same margin the route path uses.
         */
        const val OTHER_ROAD_MARGIN_M =
            com.mihai.navhud.alerts.SpeedCameras.OTHER_ROAD_MARGIN_M

        /**
         * Within this of our heading, a road is our own continuing or the
         * other carriageway -- not a rival claimant for the camera.
         */
        const val PARALLEL_TOLERANCE_DEG = 30.0

        /** Warn about the same camera again after this long — the way home. */
        const val REANNOUNCE_AFTER_MS = 5 * 60 * 1000L
    }

    var area: Area? = null

    /** The road we believe we are on, for the pill at the bottom. */
    var roadName: String? = null
        private set

    /** Perpendicular distance to that road, metres. */
    var crossM: Double = 0.0
        private set

    /**
     * The car pulled onto the road it is on, and the direction that road runs.
     *
     * Waze never draws you inside a building, and it is right not to: a marker
     * in somebody's front room is not a position, it is a GPS error rendered
     * literally. With a route the projection came free; without one the road
     * has to be found first, which is what AreaRoads.match does, and then the
     * same projection applies.
     */
    var snappedLat: Double = 0.0
        private set
    var snappedLon: Double = 0.0
        private set
    var roadBearing: Double? = null
        private set
    var snapTrusted: Boolean = false
        private set

    /**
     * The centreline of the road we believe we are on.
     *
     * Published for two jobs. The map re-projects the *drawn* marker onto it
     * after dead reckoning — a position snapped at the moment of the fix and
     * then advanced along the heading for the second since drifts straight off
     * the tarmac on a bend, which is exactly where you look at it.
     *
     * And warnings need it. A speed bump is *in* a road, and a
     * camera points down one; matching either by straight-line distance and a
     * heading cone announces the bump in the side street you are passing and
     * the camera on the parallel carriageway. Having the road means the test
     * can be "is this thing on my road", which is the actual question.
     */
    var roadPts: Array<DoubleArray>? = null
        private set

    private var heldLimit = 0

    /**
     * True when the limit on screen came from the law rather than a map.
     *
     * Published so the diagnostics can say which, and used to set
     * FLAG_LOW_CONF -- the same treatment a held limit gets.
     */
    var limitDerived = false; private set
    private var heldAtMs = 0L

    /** The last heading we trusted, so a stop at a red light keeps its bearings. */
    private var lastHeading: Double? = null

    /** stage key -> when it was announced, so a return trip warns again. */
    private val announced = HashMap<Long, Long>()

    var alert: CameraAlert? = null
        private set

    fun update(
        lat: Double,
        lon: Double,
        speedMps: Float,
        bearingDeg: Float?,
        hasFix: Boolean,
        nowMs: Long,
        policy: CameraPolicy,
        night: Boolean = false,
        /** ISO 3166-1 alpha-2, for the legal default when nothing is posted. */
        country: String? = null,
        /** Local hour, 0-23. Only the Dutch daytime motorway limit uses it. */
        localHour: Int = 12
    ): HudFrame {

        if (!hasFix) {
            alert = null
            snapTrusted = false
            roadPts = null
            // The hold has to expire here too. Lose the fix in a tunnel or a
            // car park and this branch runs for ever, and it used to keep the
            // last known limit on the glass and the last road name in the pill
            // for as long as the app was open -- which is the one thing a
            // display like this must never do.
            if (heldLimit != 0 && nowMs - heldAtMs > LIMIT_HOLD_MS) {
                heldLimit = 0
                roadName = null
            }
            return HudFrame(
                speedKph = -1,
                limitKph = heldLimit,
                maneuver = Man.NONE,
                flags = (if (night) HudFrame.FLAG_NIGHT else 0) or HudFrame.FLAG_LOW_CONF
            )
        }

        // Below walking pace a GPS bearing is noise. Keep the last good one
        // rather than dropping to "no heading": at a red light that would turn
        // off the direction filters and start warning about the camera you
        // passed a hundred metres ago.
        val heading = if (speedMps > 2.0f) {
            bearingDeg?.toDouble()?.also { lastHeading = it }
        } else lastHeading
        val a = area
        val m = if (a == null) null else AreaRoads.match(a, lat, lon, heading)

        var limit = 0
        var lowConf = false
        snapTrusted = false
        // Published inside the trust gate below, not here. Set from the match
        // before the gate, an untrusted match would still hand the camera
        // filter a road we are not confident we are on -- and that filter
        // *suppresses* warnings, so the failure would be a camera you never
        // hear about. It happened to be safe only because SNAP_TRUST_M and the
        // matcher's own limit are currently the same number.
        roadPts = null
        if (m != null) {
            crossM = m.crossM
            roadName = m.road.label.takeIf { it.isNotBlank() }
            // Pull the marker onto the road, exactly as the route tracker does.
            val n = AreaRoads.projectOnto(m.road.pts, lat, lon)
            if (n != null && m.crossM <= SNAP_TRUST_M) {
                snappedLat = n[0]
                snappedLon = n[1]
                roadBearing = m.bearingDeg
                roadPts = m.road.pts
                snapTrusted = true
            }
            // The road's own maxspeed, else what the law says applies when
            // there is no sign -- which is not a guess: a road with no sign IS
            // at its legal default. Half the drivable road length in Belgium
            // has no `maxspeed` in OpenStreetMap, concentrated in exactly the
            // residential and unclassified streets where the limit matters
            // most. SpeedDefaults owns this, rather than a copy living here,
            // because a route needs exactly the same answer where the router
            // has none.
            //
            // Null is the only "no answer". A derestricted autobahn comes back
            // as DERESTRICTED (-1) and is a real answer, so this tests the
            // nullability and never the sign.
            val rl = SpeedDefaults.limitOf(m.road, a, country, localHour)
            if (rl != null) {
                limit = rl.kph
                limitDerived = rl.derived
                if (rl.derived) {
                    // Never held: a derived limit is only as good as the road it
                    // was derived from, so it must not survive onto the next one.
                    heldLimit = 0
                } else {
                    heldLimit = limit
                    heldAtMs = nowMs
                }
            }
        }

        if (limit == 0) {
            // No match, or a road the law has no default for. Hold the last
            // posted one briefly rather than flicking the sign off and on again
            // over every untagged junction.
            if (heldLimit != 0 && nowMs - heldAtMs < LIMIT_HOLD_MS) {
                limit = heldLimit
                lowConf = true
            } else {
                heldLimit = 0
                // No match and nothing left to hold: stop claiming a road.
                if (m == null) roadName = null
            }
        }

        val speedKph = (speedMps * 3.6f).roundToInt()
        val prev = alert
        alert = nearestCameraAhead(a, lat, lon, heading, speedKph, policy)
            // A danger zone does not end on the camera, or its end would mark
            // it: hold the passed one for the zone's tail (zoneTailM).
            ?: prev?.takeIf {
                policy == CameraPolicy.ZONE && it.zoneMode &&
                    Geo.haversine(lat, lon, it.camera.lat, it.camera.lon) <
                    CameraWatcher.zoneTailM(it.camera.limitKph)
            }?.copy(passed = true)
        lastAlertAtMs = nowMs

        var flags = HudFrame.FLAG_GPS_OK
        if (limit > 0 && speedKph > limit + OVER_LIMIT_TOLERANCE_KPH) {
            flags = flags or HudFrame.FLAG_OVER_LIMIT
        }
        // A derived limit is marked low-confidence for the same reason a held
        // one is: the display should say "probably 50" differently from "50 on
        // a sign you just passed", and the driver is the one carrying the fine.
        if (lowConf || limitDerived || m == null) flags = flags or HudFrame.FLAG_LOW_CONF
        if (night) flags = flags or HudFrame.FLAG_NIGHT

        return HudFrame(
            speedKph = speedKph,
            limitKph = limit,
            // No route means no instruction. The display shows a plain
            // "carry on" rather than inventing a turn.
            maneuver = Man.NONE,
            roundaboutExit = 0,
            distToManeuverM = 0,
            etaSeconds = 0,
            remainingM = 0,
            flags = flags,
            street = ""
        )
    }

    /**
     * The nearest camera we are driving towards.
     *
     * Straight-line distance rather than distance along a route, because there
     * is no route. That understates the distance around a bend, which errs
     * towards warning slightly early — the right direction to be wrong in.
     */
    private fun nearestCameraAhead(
        a: Area?,
        lat: Double,
        lon: Double,
        heading: Double?,
        speedKph: Int,
        policy: CameraPolicy
    ): CameraAlert? {
        if (a == null || policy == CameraPolicy.OFF) return null
        val warn = CameraWatcher.warnDistance(speedKph).toDouble()
        val zone = policy == CameraPolicy.ZONE
        // Not the flat 2 km ZONE_LENGTH_M: the zone a camera actually covers
        // depends on the limit it enforces (300 m in town, 4 km on a motorway),
        // which is why zoneLengthFor exists. A flat 2 km meant every 50 km/h
        // town camera in France opened its danger zone 1.7 km too early, and
        // with urban camera density the box never closed again.
        // The outer radius is only a pre-filter; in zone mode each camera is
        // then held to the zone its own enforced limit implies. The 2 km hard
        // cap does not apply to a zone, or a motorway's 4 km one could never
        // open.
        val reach = if (zone) CameraWatcher.ZONE_LENGTH_MAX_M.toDouble()
                    else minOf(warn, CAMERA_MAX_M)

        // With no heading we cannot tell ahead from behind, and neither the
        // cone nor facesUs can filter anything. Announcing "camera in 300 m"
        // for one you are driving away from is worse than saying nothing, so
        // free drive stays quiet until it knows which way the car points.
        if (heading == null) return null

        var best: SpeedCamera? = null
        var bestD = Double.MAX_VALUE
        val road = roadPts
        for (c in a.cameras) {
            val d = Geo.haversine(lat, lon, c.lat, c.lon)
            if (d > reach) continue
            if (zone && d > maxOf(warn, CameraWatcher.zoneLengthFor(c.limitKph).toDouble())) {
                continue
            }
            // In front of us, not behind: bearing to the camera must be
            // within a cone of where we are pointing.
            val toCam = Geo.bearing(lat, lon, c.lat, c.lon)
            if (Geo.bearingDelta(toCam, heading) > CAMERA_CONE_DEG) continue
            if (!com.mihai.navhud.alerts.SpeedCameras.facesUs(c, heading)) continue
            // On our road, not merely near us. Without this the app announced
            // the camera on the cross street at the lights and the one on the
            // other carriageway of a dual, both of which are real cameras and
            // neither of which was going to photograph us.
            //
            // Two tests, because one is not enough. The first asks how far the
            // camera is from the road we are on; the second asks whether some
            // *other* road in the network is nearer to it than ours is, which
            // is the only way to tell a camera 15 m off our centreline from
            // one sitting on the street 15 m to the left of it. Free drive
            // used to do only the first, with a 30 m tolerance, and skip even
            // that whenever the road match was untrusted -- so it failed open.
            if (road != null) {
                // How far off *our* road the camera sits. Two measurements,
                // whichever is kinder, because neither works alone:
                //
                //  - distance to the matched OSM way. Exact where the camera is
                //    beside us, useless further ahead: OSM splits a street at
                //    every junction, every bridge and every speed-limit change,
                //    so a camera 300 m up the same road is on a *different* way
                //    and this clamps to our way's end vertex.
                //  - distance off the line we are driving. Works at any range
                //    on a straight road; too strict through a bend.
                val n = AreaRoads.nearestOn(road, c.lat, c.lon)
                val toCamB = Geo.bearing(lat, lon, c.lat, c.lon)
                val offTrack = d * kotlin.math.abs(
                    kotlin.math.sin(Math.toRadians(Geo.bearingDelta(toCamB, heading)))
                )
                val ourD = minOf(n?.first ?: Double.MAX_VALUE, offTrack)
                if (ourD > CAMERA_ON_ROAD_M) continue
                if (cameraBelongsToAnotherRoad(a, c, ourD, heading)) continue
            } else {
                // No matched road -- the area fetch failed, or OpenStreetMap
                // does not have this street. Falling straight through here is
                // what made the filter fail *open*, so instead fall back to the
                // straight line we are driving: how far the camera sits off our
                // current track. It cannot follow a bend, so it is stricter
                // than the road test rather than looser, which is the right way
                // round for a warning.
                val toCam = Geo.bearing(lat, lon, c.lat, c.lon)
                val offTrack = d * kotlin.math.abs(
                    kotlin.math.sin(Math.toRadians(Geo.bearingDelta(toCam, heading)))
                )
                if (offTrack > CAMERA_ON_ROAD_M) continue
            }
            if (d < bestD) { bestD = d; best = c }
        }
        val c = best ?: return null
        val raw = bestD.roundToInt()
        // Same rule as on a route: inside a French zone the number shown is
        // deliberately coarse, so it cannot be read as a position.
        val shown = if (zone)
            CameraWatcher.blurZoneDistance(raw, CameraWatcher.zoneLengthFor(c.limitKph)) else raw
        return CameraAlert(
            camera = c,
            distanceM = shown,
            zoneMode = zone,
            // One announcement per zone, on entry (see CameraWatcher.update).
            stage = if (zone) 0 else CameraWatcher.stageFor(raw, speedKph),
            spokenM = CameraWatcher.spokenDistance(raw, speedKph)
        )
    }

    /**
     * Does some road that is not ours have a better claim on this camera?
     *
     * `ourD` is how far the camera is from the road we are driving. A road that
     * passes closer than that by a clear margin owns the camera -- but only if
     * it is a road at an *angle* to us. A way running parallel to our heading
     * is our own street continuing past a junction (OSM splits it there) or the
     * other carriageway of a dual, and neither should steal a camera: the first
     * is us, and the second is what the `direction` tag is for. Without that
     * test, every red-light camera just past a way split was suppressed, which
     * is precisely where junction-mounted cameras live.
     */
    private fun cameraBelongsToAnotherRoad(
        a: Area, c: SpeedCamera, ourD: Double, headingDeg: Double
    ): Boolean {
        val mine = roadPts
        for (r in a.roadsNear(c.lat, c.lon, CAMERA_ON_ROAD_M + Area.CELL_M)) {
            if (r.pts === mine) continue                 // that is us
            val n = AreaRoads.nearestOn(r.pts, c.lat, c.lon) ?: continue
            val brg = n.second
            if (brg != null) {
                val along = Geo.bearingDelta(brg, headingDeg)
                if (minOf(along, 180.0 - along) < PARALLEL_TOLERANCE_DEG) continue
            }
            if (n.first < ourD - OTHER_ROAD_MARGIN_M) return true
        }
        return false
    }

    private var lastAlertAtMs = 0L

    /**
     * True the first time each stage of each camera comes up — and again if you
     * come back past it later.
     *
     * The service is a foreground service that stays up all day, so a plain
     * "announced once" set means the camera you were warned about on the way
     * out is silent on the way home. Expiring the record after a few minutes
     * gives you the warning on the return trip without repeating it while you
     * are still approaching the same one.
     */
    fun shouldAnnounce(a: CameraAlert): Boolean {
        if (a.passed) return false                       // a zone's tail
        val key = a.camera.id * 8L + a.stage
        val was = announced[key]
        if (was != null && lastAlertAtMs - was < REANNOUNCE_AFTER_MS) return false
        announced[key] = lastAlertAtMs
        if (announced.size > 512) announced.clear()      // a very long drive
        return true
    }

    fun resetAnnouncements() {
        announced.clear()
        lastHeading = null
    }
}
