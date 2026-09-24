package com.mihai.navhud.alerts

import com.mihai.navhud.Geo
import com.mihai.navhud.nav.AreaCache
import com.mihai.navhud.nav.Route
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** One enforcement point, already pinned to a distance along the active route. */
data class SpeedCamera(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val alongM: Double,
    val limitKph: Int,          // 0 = not tagged
    val directionDeg: Double?,  // way the camera faces, if tagged
    val kind: Kind,
    /** How far the camera sits from the route line, metres. */
    val crossM: Double = 0.0
) {
    /**
     * ANPR: a number-plate camera (`surveillance:type=ALPR`). No speed limit,
     * but part of section controls and of the police network, and Waze warns
     * of them in Belgium. Added last so existing ordinals do not move.
     */
    enum class Kind { FIXED, AVERAGE, TRAFFIC_LIGHT, UNKNOWN, ANPR }
}

/**
 * Speed cameras from OpenStreetMap.
 *
 * OSM is the only source a DIY build can actually use: Waze's and TomTom's
 * databases are theirs. Coverage is good in western Europe and patchy further
 * east, so this is an assist, not a guarantee — which is worth remembering
 * before trusting it on a road you do not know.
 */
object SpeedCameras {

    private const val OVERPASS = "https://overpass-api.de/api/interpreter"

    /** Corridor half-width around the route to search, metres. */
    private const val CORRIDOR_M = 250

    /** How far apart to place the `around` sample points along the route. */
    private const val SAMPLE_SPACING_M = 1500.0

    /**
     * A camera further than this from the line is not on our road.
     *
     * 45 m was too generous and it showed: a camera on the service road
     * running beside a motorway, or on the cross street at a junction the
     * route passes through, is comfortably inside 45 m and was being
     * announced. 28 m still covers a gantry over the far lane and the other
     * half of a dual carriageway, which are the two cases that legitimately
     * sit off the line.
     */
    /**
     * How far off the route a camera may be and still be treated as on it.
     *
     * A camera is mounted at the roadside, so a few metres off the centreline
     * is normal, and a routed polyline is itself a generalisation that sits
     * 5-12 m off the carriageway it follows. Fifteen, measured against that
     * line, threw away cameras genuinely on our road -- a gantry over the far
     * lanes of a motorway, the far side of a dual carriageway with a verge.
     *
     * Thirty-five does let the camera on a street running parallel through
     * the corridor, which is what the 28 m value was blamed for. That is no
     * longer this constant's job: [onOurRoad] asks which road in the loaded
     * network the camera is actually nearest to, and rejects it there.
     */
    internal const val MATCH_TOLERANCE_M = 35.0

    /**
     * ...and a second, better test on top of the distance.
     *
     * Distance alone cannot tell "beside my road" from "on the next road over",
     * because both are a few tens of metres away. What can is asking which road
     * the camera is *nearest to*: a camera belongs to the carriageway it
     * watches, and if some other road in the loaded network is clearly closer
     * to it than our route is, then it is watching that one. The margin stops a
     * near-tie — a service road running alongside a main road — from throwing
     * away a camera that is genuinely ours.
     */
    const val OTHER_ROAD_MARGIN_M = 6.0

    /**
     * Only cameras facing roughly the way we are travelling matter. OSM's
     * `direction` is the way the camera looks, i.e. towards oncoming traffic,
     * so a camera we will pass points back at us: ~180 degrees from our heading.
     *
     * This was 100 degrees, which accepted a camera pointing almost exactly
     * across our path — the one watching the road we are crossing, not the one
     * we are on. A camera whose direction is tagged at all is tagged
     * accurately; 70 degrees is enough slack for a bend and no more.
     */
    internal const val DIRECTION_TOLERANCE_DEG = 70.0

    // -----------------------------------------------------------------------

    /**
     * Builds an Overpass query hugging the route rather than a big bounding box.
     * A Brussels-to-Bucharest bbox would be most of Europe; the corridor is a
     * few hundred metres wide.
     */
    fun buildQuery(route: Route): String {
        val pts = sampleRoute(route)
        val coords = pts.joinToString(",") { "${fmt(it[0])},${fmt(it[1])}" }
        // Three ways a camera is mapped in OSM, and we need all three.
        //
        // A plain `highway=speed_camera` node is the common case. A node
        // tagged `enforcement=maxspeed` without the highway tag is the second.
        // The third is the one this query used to miss entirely: a
        // `type=enforcement` relation, which is how section control is
        // *supposed* to be mapped -- a relation tying the device nodes to the
        // stretch of road they cover. There are 24,200 such relations in OSM
        // against 4,368 bare nodes, and 876 of them are in Belgium, so
        // querying only nodes was leaving a large fraction of the section
        // controls on the table. `node(r.e)` pulls the relation's members
        // back out as ordinary nodes, which is all the rest of this file
        // wants to see.
        //
        // Both `enforcement=maxspeed` and `enforcement=average_speed` -- the
        // second is the usual tagging for section control, and filtering on
        // the first alone would have missed exactly the thing the relation
        // query was added for.
        //
        // And number-plate cameras, which the OSM wiki maps as
        // `man_made=surveillance` + `surveillance:type=ALPR` (the documented
        // value; "ANPR" is the British/Belgian name and turns up too, so the
        // match is a case-insensitive regex).
        return """
            [out:json][timeout:60];
            (
              node(around:$CORRIDOR_M,$coords)["highway"="speed_camera"];
              node(around:$CORRIDOR_M,$coords)["enforcement"="maxspeed"];
              node(around:$CORRIDOR_M,$coords)["man_made"="surveillance"]["surveillance:type"~"^(alpr|anpr)$",i];
              rel(around:$CORRIDOR_M,$coords)["type"="enforcement"]["enforcement"~"^(maxspeed|average_speed)$"];
            )->.e;
            .e out body;
            node(r.e)->.n;
            .n out body;
        """.trimIndent()
    }

    private fun fmt(v: Double) = "%.5f".format(java.util.Locale.US, v)

    /** Route points spaced far enough apart to keep the query small. */
    fun sampleRoute(route: Route): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var d = 0.0
        while (d < route.totalDistanceM) {
            out.add(Geo.pointAlong(route.pts, route.cum, d))
            d += SAMPLE_SPACING_M
        }
        out.add(route.pts.last())
        return out
    }

    /**
     * Cache name for a route's camera list: its destination, to ~100 m. A
     * reroute to the same place reuses it; the cached body is parsed against
     * the route actually driven, so cameras off it are dropped anyway.
     */
    internal fun cacheKey(route: Route): String {
        val dest = route.pts.last()
        return "cams_${Math.round(dest[0] * 1e3)}_${Math.round(dest[1] * 1e3)}"
    }

    /**
     * Blocking network call; run it off the main thread.
     *
     * With [cache], each good response is kept on disk keyed on the route's
     * *destination*, and read back -- at any age -- when the network fails.
     * A reroute or a restart on the way to the same place, in a tunnel or out
     * of coverage, then still has its cameras: the cached body is parsed
     * against the route actually being driven, so only cameras on it survive.
     * Throws only when there is neither a network answer nor a cached one, and
     * the caller keeps whatever list it had.
     */
    fun fetch(route: Route, cache: AreaCache? = null): List<SpeedCamera> =
        fetchWith(route, cache) { post(OVERPASS, it) }

    /** [fetch] with the transport swappable, for tests. */
    internal fun fetchWith(
        route: Route, cache: AreaCache?, transport: (String) -> String
    ): List<SpeedCamera> {
        val key = cacheKey(route)
        val body = try {
            transport("data=" + java.net.URLEncoder.encode(buildQuery(route), "UTF-8")).also {
                checkComplete(it)
                cache?.putNamed(key, it)
            }
        } catch (e: Exception) {
            cache?.getNamed(key, Long.MAX_VALUE, System.currentTimeMillis()) ?: throw e
        }
        return parse(body, route)
    }

    /**
     * Overpass answers a query that ran out of time or memory with HTTP 200,
     * a `remark` and whatever it had found so far -- often nothing. Taken at
     * face value that is "no cameras on this route", and it replaced a good
     * list; it is a failure, and is treated as one.
     */
    private fun checkComplete(body: String) {
        val remark = JSONObject(body).optString("remark")
        if (remark.contains("error", ignoreCase = true) ||
            remark.contains("timed out", ignoreCase = true)) {
            throw java.io.IOException("Overpass incomplete: ${remark.take(160)}")
        }
    }

    /**
     * Pins cameras already known onto another route: the reroute case, where
     * waiting a minute for Overpass would leave the first cameras unguarded.
     * Same corridor as [parse]; cameras the new route does not pass are
     * dropped.
     */
    fun reproject(cameras: List<SpeedCamera>, route: Route): List<SpeedCamera> {
        val out = ArrayList<SpeedCamera>(cameras.size)
        for (c in cameras) {
            val snap = Geo.project(route.pts, route.cum, c.lat, c.lon, searchAll = true)
            if (snap.cross > MATCH_TOLERANCE_M) continue
            out.add(c.copy(alongM = snap.along, crossM = snap.cross))
        }
        out.sortBy { it.alongM }
        return out
    }

    /**
     * Parses an Overpass response and pins each camera to the route.
     * Split out from `fetch` so it can be tested without a network.
     */
    fun parse(json: String, route: Route): List<SpeedCamera> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()

        // First pass: the enforcement relations. `out body` on a relation
        // includes its member list, so a device node can be looked up against
        // the relation it belongs to -- which is where the tags that matter
        // live. The node itself is usually just `highway=speed_camera`, so
        // without this a section control was announced as a fixed camera:
        // precisely the cameras the relation query was added to find, labelled
        // as the wrong thing.
        val byMember = HashMap<Long, JSONObject>()
        for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue
            if (e.optString("type") != "relation") continue
            val tags = e.optJSONObject("tags") ?: continue
            val members = e.optJSONArray("members") ?: continue
            for (j in 0 until members.length()) {
                val m = members.optJSONObject(j) ?: continue
                if (m.optString("type") != "node") continue
                // `device` is the camera; `from`/`to`/`force` are the road.
                val role = m.optString("role")
                if (role.isNotBlank() && role != "device") continue
                byMember[m.optLong("ref")] = tags
            }
        }

        val out = ArrayList<SpeedCamera>()
        for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue
            if (e.optString("type") != "node") continue
            if (!e.has("lat") || !e.has("lon")) continue
            val lat = e.getDouble("lat")
            val lon = e.getDouble("lon")
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val parent = byMember[e.optLong("id")]
            if (parent == null && !isEnforcement(tags)) continue

            val snap = Geo.project(route.pts, route.cum, lat, lon, searchAll = true)
            if (snap.cross > MATCH_TOLERANCE_M) continue      // a camera on a parallel road

            out.add(
                SpeedCamera(
                    id = e.optLong("id"),
                    lat = lat, lon = lon,
                    alongM = snap.along,
                    // The relation's limit wins: it is the limit being
                    // enforced over the section, which is the number the
                    // driver is being measured against.
                    limitKph = parseMaxspeed(parent?.optString("maxspeed"))
                        .takeIf { it != 0 } ?: parseMaxspeed(tags.optString("maxspeed")),
                    directionDeg = parseDirection(
                        tags.optString("direction").ifBlank { tags.optString("camera:direction") }
                    ),
                    kind = if (parent != null) kindOf(parent) else kindOf(tags),
                    crossM = snap.cross
                )
            )
        }
        // Nearest first, and drop duplicates mapped as two nodes a few metres apart.
        out.sortBy { it.alongM }
        val deduped = ArrayList<SpeedCamera>(out.size)
        for (c in out) {
            val prev = deduped.lastOrNull()
            // Same kind, a few metres apart along the route: one physical
            // installation mapped twice -- unless they carry *different*
            // directions, in which case they are the pair on a dual
            // carriageway, one per direction. Collapsing those kept whichever
            // happened to project first, which was the one watching the other
            // side half the time; facesUs then correctly rejected it and the
            // camera on our own carriageway was never warned about at all.
            val samePlace = prev != null && abs(prev.alongM - c.alongM) < 30.0 &&
                prev.kind == c.kind
            val opposedPair = prev != null && prev.directionDeg != null &&
                c.directionDeg != null &&
                Geo.bearingDelta(prev.directionDeg, c.directionDeg) > 90.0
            if (samePlace && !opposedPair) continue
            deduped.add(c)
        }
        return deduped
    }

    /**
     * Is this node actually an enforcement camera?
     *
     * Two things get swept up by the query and must not be announced. A
     * `highway=speed_display` is one of those "YOUR SPEED 48" feedback signs --
     * 5,364 of them in OSM, no fine attached, and warning about one trains you
     * to ignore the warnings that matter. And the relation members come back
     * with whatever tags they carry, which for the `from`/`to` roles of an
     * enforcement relation is a plain bit of road.
     */
    internal fun isEnforcement(tags: JSONObject): Boolean {
        val hw = tags.optString("highway").lowercase()
        if (hw == "speed_display") return false
        if (hw == "speed_camera") return true
        val enf = tags.optString("enforcement").lowercase()
        if (enf.isNotBlank()) return true
        // A relation's device member is sometimes tagged only this way.
        return tags.optString("camera:type").isNotBlank() ||
               tags.optString("man_made").lowercase() == "surveillance" &&
               tags.optString("surveillance:type").lowercase() == "camera" ||
               isAnpr(tags)
    }

    /** A number-plate camera: `man_made=surveillance` + `surveillance:type=ALPR`. */
    internal fun isAnpr(tags: JSONObject): Boolean =
        tags.optString("man_made").lowercase() == "surveillance" &&
            tags.optString("surveillance:type").lowercase().let { it == "alpr" || it == "anpr" }

    private fun kindOf(tags: JSONObject): SpeedCamera.Kind {
        val t = tags.optString("camera:type").lowercase()
        val enf = tags.optString("enforcement").lowercase()
        return when {
            t.contains("section") || enf.contains("average") ||
                tags.optString("type") == "enforcement" &&
                tags.optString("enforcement") == "average_speed" -> SpeedCamera.Kind.AVERAGE
            tags.optString("highway") == "traffic_signals" ||
                enf.contains("traffic_signals") -> SpeedCamera.Kind.TRAFFIC_LIGHT
            tags.optString("highway") == "speed_camera" -> SpeedCamera.Kind.FIXED
            // Only a *plain* ANPR: one that also enforces a speed is a speed
            // camera, and a section-control device gets its kind from the
            // relation before this is ever asked.
            enf.isBlank() && isAnpr(tags) -> SpeedCamera.Kind.ANPR
            else -> SpeedCamera.Kind.UNKNOWN
        }
    }

    /**
     * OSM maxspeed for an enforced limit.
     *
     * Delegates to the road parser so the two cannot disagree about the same
     * string. They used to: this one was a bare `\d+` over the whole value, so
     * `FR:urban` -- an implicit 50 -- came out as 0, and a French town camera
     * was given a 2 km danger zone instead of the 300 m one its real limit
     * implies. `DE:zone30` came out as 30 here and 0 there.
     *
     * `none` (derestricted) means there is no enforced limit to show, so it
     * folds to "unknown" rather than the -1 a road uses.
     */
    fun parseMaxspeed(raw: String?): Int =
        com.mihai.navhud.nav.AreaRoads.parseMaxspeed(raw).coerceAtLeast(0)

    /**
     * How close a road has to run to the route before we call it *our* road
     * rather than the one next door. A routed polyline is a generalisation of
     * the carriageway it follows, so it sits a few metres off the OSM way even
     * where they are the same piece of tarmac.
     */
    const val OUR_CORRIDOR_M = 14.0

    /**
     * Is this camera watching the road we are on, or the one next to it?
     *
     * The comparison has to be like for like. Measuring the camera against our
     * *route line* and against every OSM *way* and taking the smaller was not:
     * a route polyline is generalised and typically sits 5-12 m off the way it
     * follows, while the camera's own way passes within a metre of it. So the
     * camera's own carriageway always looked "clearly nearer than the route",
     * and a camera genuinely on our road was thrown away -- and then memoised,
     * so it stayed thrown away.
     *
     * With `routeWindow` supplied we can tell which ways carry the route near
     * the camera and exclude them, then compare OSM way against OSM way.
     *
     * @param routeCrossM  how far the camera is from our own route line
     * @param routeWindow  route vertices near the camera, or null when unknown
     * @return false when another road in the area is clearly nearer to it
     */
    fun onOurRoad(
        area: com.mihai.navhud.nav.Area?,
        camLat: Double,
        camLon: Double,
        routeCrossM: Double,
        routeWindow: Array<DoubleArray>? = null
    ): Boolean {
        if (area == null || area.roads.isEmpty()) return true   // nothing to compare with
        var nearestOther = Double.MAX_VALUE
        var nearestOurs = Double.MAX_VALUE
        val near = area.roadsNear(camLat, camLon, MATCH_TOLERANCE_M + 250.0)
        for (r in near) {
            val p = com.mihai.navhud.nav.AreaRoads.projectOnto(r.pts, camLat, camLon) ?: continue
            val d = Geo.haversine(camLat, camLon, p[0], p[1])
            val carriesRoute = routeWindow != null && routeWindow.size >= 2 &&
                (com.mihai.navhud.nav.AreaRoads.nearestOn(routeWindow, p[0], p[1])?.first
                    ?: Double.MAX_VALUE) <= OUR_CORRIDOR_M
            if (carriesRoute) {
                if (d < nearestOurs) nearestOurs = d
            } else if (d < nearestOther) {
                nearestOther = d
            }
        }
        if (nearestOther == Double.MAX_VALUE) return true
        // Prefer the way that carries our route; fall back to the route line
        // itself when no way could be identified as ours.
        val ours = if (nearestOurs != Double.MAX_VALUE) nearestOurs else routeCrossM
        return nearestOther >= ours - OTHER_ROAD_MARGIN_M
    }

    /** `direction` may be degrees or a compass point. */
    fun parseDirection(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().uppercase()
        s.toDoubleOrNull()?.let { return ((it % 360) + 360) % 360 }
        val compass = mapOf(
            "N" to 0.0, "NNE" to 22.5, "NE" to 45.0, "ENE" to 67.5,
            "E" to 90.0, "ESE" to 112.5, "SE" to 135.0, "SSE" to 157.5,
            "S" to 180.0, "SSW" to 202.5, "SW" to 225.0, "WSW" to 247.5,
            "W" to 270.0, "WNW" to 292.5, "NW" to 315.0, "NNW" to 337.5,
            "NORTH" to 0.0, "EAST" to 90.0, "SOUTH" to 180.0, "WEST" to 270.0
        )
        return compass[s]
    }

    /**
     * True if a camera tagged with a facing direction is pointing at traffic
     * going our way. Untagged cameras are always kept -- a missed warning is
     * worse than a spurious one.
     */
    fun facesUs(cam: SpeedCamera, travelBearingDeg: Double?): Boolean {
        val dir = cam.directionDeg ?: return true
        if (travelBearingDeg == null) return true
        val oncoming = (travelBearingDeg + 180.0) % 360.0
        return Geo.bearingDelta(dir, oncoming) <= DIRECTION_TOLERANCE_DEG
    }

    private fun post(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 60000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("User-Agent", com.mihai.navhud.Net.USER_AGENT)
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            if (code !in 200..299) throw RuntimeException("Overpass HTTP $code: ${text.take(160)}")
            return text
        } finally {
            conn.disconnect()
        }
    }
}

// ---------------------------------------------------------------------------

/** What the display and the voice should be saying about cameras right now. */
data class CameraAlert(
    val camera: SpeedCamera,
    val distanceM: Int,
    val zoneMode: Boolean,
    /** 0 = first warning, 1 = closing, 2 = right on top of it. */
    val stage: Int = 0,
    /**
     * The distance the voice quotes: the stage's own round number, never the
     * live one (see [CameraWatcher.spokenDistance]). 0 = too close for a
     * number, just "Radar".
     */
    val spokenM: Int = distanceM
)

/**
 * Decides when to speak. Kept separate from the fetching so the timing rules
 * can be tested against a simulated drive.
 */
class CameraWatcher(
    /** Pinned to the route this watcher was made for, nearest first. */
    val cameras: List<SpeedCamera>,
    private val policy: CameraPolicy,
    /** Stage keys already spoken, carried over from a predecessor. */
    announcedBefore: Collection<Long> = emptyList()
) {
    companion object {
        /**
         * When to speak, in metres before the camera, per speed band.
         *
         * These used to be fractions of a single warning distance, which put
         * the first motorway warning at 900 m — eleven seconds at 130 km/h, by
         * which point you are already braking — and the last one at 160 m,
         * close enough to be startling rather than useful.
         *
         * Now they are written out, because the right answer is a *time*
         * budget and the bands make that explicit:
         *
         *   motorway  1000 / 500 / 200 m  ~= 28 / 14 / 6 s at 130 km/h
         *   main road  600 / 300 / 150 m  ~= 31 / 15 / 8 s at 70 km/h
         *   town       400 / 200 / 100 m  ~= 29 / 14 / 7 s at 50 km/h
         *
         * The first call is early enough to lift off rather than brake, the
         * second confirms it, and the last lands about 200 m out on a fast
         * road — enough to check the speedometer once more before the gantry.
         * (1.28: the owner's choice, like Waze; 1500 m was too early to matter.)
         */
        private val STAGES_FAST = intArrayOf(1000, 500, 200)   // >= 90 km/h
        private val STAGES_MID = intArrayOf(600, 300, 150)     // >= 50 km/h
        private val STAGES_SLOW = intArrayOf(400, 200, 100)    // town

        fun stages(speedKph: Int): IntArray = when {
            speedKph >= 90 -> STAGES_FAST
            speedKph >= 50 -> STAGES_MID
            else -> STAGES_SLOW
        }

        /** How far ahead the first warning comes, i.e. the deepest stage. */
        fun warnDistance(speedKph: Int): Int = stages(speedKph)[0]

        /** In zone mode the alert covers a stretch instead of a point. */
        const val ZONE_LENGTH_M = 2000

        /**
         * How long a French *zone de danger* is, by the kind of road it is on.
         *
         * This is not a design choice. The 2011 agreement between the French
         * state and the navigation industry — the one that turned "radars" into
         * "zones de danger" — fixes the lengths, and TomTom, Coyote and
         * everyone else use the same three numbers: **300 m in a built-up area,
         * 2 km on an ordinary road, 4 km on a motorway.** The point is that the
         * zone is long enough that knowing you are in one tells you nothing
         * about where the camera is standing.
         *
         * A flat 2 km, which is what this used to be, is simultaneously too
         * long in town — a 2 km zone in Lille covers most of the drive — and
         * too short on the A1, where it is 55 seconds of warning.
         *
         * @param limitKph the limit on this stretch, 0 if we do not know
         */
        /** The longest zone any limit can produce — a motorway's 4 km. */
        const val ZONE_LENGTH_MAX_M = 4000

        fun zoneLengthFor(limitKph: Int): Int = when {
            limitKph >= 100 -> 4000
            limitKph in 1..50 -> 300
            else -> 2000
        }

        /**
         * Round a distance so the display cannot be used to locate the camera.
         *
         * Counting down "800… 700… 600…" to a point inside a zone defeats the
         * entire purpose of the zone: watch the number hit zero and you know
         * exactly where the camera is. Rounding to the zone's own granularity
         * keeps the warning useful and the position vague, which is the deal.
         */
        fun blurZoneDistance(distanceM: Int, zoneLengthM: Int): Int {
            val step = when {
                zoneLengthM >= 4000 -> 1000
                zoneLengthM >= 2000 -> 500
                else -> 100
            }
            return ((distanceM + step / 2) / step) * step
        }

        /** Stop alerting once it is this far behind. */
        private const val PASSED_M = 40.0

        /** 0 = first warning, 1 = closing, 2 = right on top of it. */
        fun stageFor(distanceM: Int, speedKph: Int): Int {
            val s = stages(speedKph)
            var stage = 0
            for (i in s.indices) if (distanceM <= s[i]) stage = i
            return stage
        }

        /** Below this no number is spoken, only "Radar". */
        const val SPOKEN_MIN_M = 50

        /**
         * What the voice says for a camera [distanceM] ahead: the stage's own
         * distance ("radar dans deux cents mètres"), not the live one. The live
         * one is what said "in 13 m": a stage first reached right under the
         * camera (the alert was on another camera until then, or the camera
         * only just passed the on-our-road test) quoted the metres left.
         *
         * A stage reached late, less than half its distance out, would make the
         * round number a lie, so that one is rounded to 50 m instead. Under
         * [SPOKEN_MIN_M] it is 0: no number at all.
         */
        fun spokenDistance(distanceM: Int, speedKph: Int): Int {
            if (distanceM < SPOKEN_MIN_M) return 0
            val at = stages(speedKph)[stageFor(distanceM, speedKph)]
            if (distanceM * 2 >= at) return at
            return maxOf(SPOKEN_MIN_M, (distanceM + 25) / 50 * 50)
        }
    }

    /**
     * (cameraId, stage) pairs already spoken. Locked: the tick adds to it
     * while [successor] copies it from the fetch, country or settings thread,
     * and a HashSet iterated during an add throws -- on the fetch executor,
     * which takes the whole app down.
     */
    private val announced = HashSet<Long>(announcedBefore)

    /**
     * A watcher for a new route, from the cameras already known, re-pinned to
     * it -- so a reroute has camera cover at once instead of after the next
     * Overpass round trip -- and remembering what was already said about each
     * camera, so the one just announced is not announced again.
     */
    fun rebase(route: Route): CameraWatcher = successor(SpeedCameras.reproject(cameras, route))

    /**
     * A watcher for a fresh camera list on the same trip (the fetch that
     * follows a reroute, the periodic refresh, a policy change) that keeps the
     * "already announced" memory for the cameras still in it. A brand-new
     * watcher repeated the stage the car was in the middle of.
     */
    fun successor(cameras: List<SpeedCamera>, policy: CameraPolicy = this.policy): CameraWatcher {
        val ids = cameras.mapTo(HashSet()) { it.id }
        val said = synchronized(announced) { announced.filter { it / 8L in ids } }
        return CameraWatcher(cameras, policy, said)
    }

    /** The alert to show, or null. */
    /**
     * @param accept an extra test the chosen camera must pass. Used to drop a
     *        camera that belongs to the road next to ours: a distance test on
     *        its own cannot separate the two, so the caller supplies one that
     *        can, and it is applied here rather than after the fact because
     *        `firstOrNull` would otherwise keep handing back the same rejected
     *        camera on every tick and hide the real one behind it.
     */
    fun update(
        alongM: Double, speedKph: Int, bearingDeg: Double?,
        accept: (SpeedCamera) -> Boolean = { true }
    ): CameraAlert? {
        if (policy == CameraPolicy.OFF) return null
        val warn = warnDistance(speedKph)
        val zone = policy == CameraPolicy.ZONE

        val next = cameras.firstOrNull {
            val reach = if (zone) max(warn, zoneLengthFor(it.limitKph)) else warn
            it.alongM > alongM - PASSED_M &&
                it.alongM - alongM <= reach &&
                SpeedCameras.facesUs(it, bearingDeg) &&
                accept(it)
        } ?: return null

        val raw = (next.alongM - alongM).roundToInt().coerceAtLeast(0)
        val dist = if (zone) blurZoneDistance(raw, zoneLengthFor(next.limitKph)) else raw
        return CameraAlert(
            camera = next,
            distanceM = dist,
            zoneMode = zone,
            stage = stageFor(raw, speedKph),
            spokenM = spokenDistance(raw, speedKph)
        )
    }

    /**
     * True the first time each *stage* of each camera comes up, so the driver
     * gets a warning, a reminder and a final chirp rather than one message.
     */
    fun shouldAnnounce(alert: CameraAlert): Boolean =
        synchronized(announced) { announced.add(alert.camera.id * 8L + alert.stage) }

    fun reset() = synchronized(announced) { announced.clear() }
}
