package com.mihai.navhud.nav

import com.mihai.navhud.Geo
import com.mihai.navhud.Man
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Mapbox Directions v5.
 *
 * The reason this one is the default: a single request returns the geometry,
 * the turn-by-turn steps *and* a per-segment `maxspeed` annotation. Every other
 * provider makes you do two calls and reconcile two different segmentations,
 * which is exactly the kind of thing that bites you at 3 am on a motorway.
 *
 * `annotations` requires `overview=full` -- Mapbox rejects the request otherwise.
 */
class MapboxProvider(
    private val token: String,
    /** Mapbox localises its step names and banner text. */
    private val language: String = "en",
    /**
     * The HTTP GET: the body of a 2xx, or a throw. Swappable so the failure
     * paths -- no signal, a timeout, an error page -- can be tested.
     */
    private val http: (url: String) -> String = ::mapboxGet
) : NavProvider {

    override val name = "Mapbox"

    private val profile = "driving-traffic"   // "driving" if you want it deterministic

    override fun routes(
        from: LatLon,
        to: LatLon,
        alternatives: Boolean,
        headingDeg: Double?
    ): List<Route> {
        val url = buildString {
            append("https://api.mapbox.com/directions/v5/mapbox/").append(profile).append('/')
            append("${from.lon},${from.lat};${to.lon},${to.lat}")
            append("?alternatives=").append(alternatives)
            append("&geometries=polyline6")
            append("&overview=full")           // required for annotations
            append("&steps=true")
            append("&banner_instructions=true")   // lane guidance + exit numbers
            // closure and congestion are what make the route aware of roadworks
            // and jams; both need the driving-traffic profile.
            append("&annotations=maxspeed,duration,congestion,closure")
            if (headingDeg != null) {
                // "this way, +-45 degrees" for the start point, nothing imposed
                // on the destination (the empty slot after the ';'). Without
                // it a reroute on a dual carriageway happily snaps you to the
                // opposite side. Mapbox recommends 45 or 90; 45 is the one
                // that keeps a reroute going the way the car already is.
                // https://docs.mapbox.com/api/navigation/directions/
                val h = (((headingDeg % 360.0) + 360.0) % 360.0).toInt()
                append("&bearings=").append(h).append(",45;")
            }
            append("&language=").append(language)
            append("&access_token=").append(token)
        }
        val root = getJson(url)
        val code = root.optString("code", "Ok")
        if (code != "Ok") {
            throw NavException("Mapbox: $code ${root.optString("message", "")}")
        }
        val arr = root.optJSONArray("routes")
            ?: throw NavException("Mapbox returned no routes")
        if (arr.length() == 0) throw NavException("No route found")
        val out = ArrayList<Route>(arr.length())
        for (i in 0 until arr.length()) {
            runCatching { parseRoute(arr.getJSONObject(i)) }.getOrNull()?.let { out.add(it) }
        }
        if (out.isEmpty()) throw NavException("Could not read any of the routes returned")
        return out
    }

    /**
     * Reverse geocode to a country, for the speed-camera rules. One call per
     * border crossing, not per fix.
     */
    override fun countryAt(point: LatLon): String? {
        val url = "https://api.mapbox.com/geocoding/v5/mapbox.places/" +
            "${point.lon},${point.lat}.json?types=country&limit=1&access_token=$token"
        val features = runCatching { getJson(url).optJSONArray("features") }.getOrNull() ?: return null
        if (features.length() == 0) return null
        val f = features.optJSONObject(0) ?: return null
        // The ISO code or nothing. This used to fall back to the feature's
        // `text` -- the country's *name*, "Belgique" -- which every caller
        // then compared against "BE" and treated as a country with no rules.
        return f.optJSONObject("properties")?.optString("short_code")
            ?.trim()?.uppercase()?.take(2)?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
    }


    /**
     * Percent-encoding for a URL *path* segment.
     *
     * URLEncoder produces application/x-www-form-urlencoded, where a space is
     * "+". That is right for a query string and wrong for a path: Mapbox's
     * geocoder takes the search text as a path segment, so
     * "Avenue de la Couronne" was being asked for as
     * "Avenue+de+la+Couronne" -- plus signs and all. Mapbox also rejects a
     * semicolon in the search text whether raw or encoded, so it is dropped.
     * https://docs.mapbox.com/api/search/geocoding-v5/
     */
    private fun encPath(s: String) =
        URLEncoder.encode(s.replace(';', ' '), "UTF-8").replace("+", "%20")

    override fun geocode(query: String, near: LatLon?): LatLon? {
        val q = encPath(query)
        val url = buildString {
            append("https://api.mapbox.com/geocoding/v5/mapbox.places/").append(q).append(".json")
            append("?limit=1")
            if (near != null) append("&proximity=${near.lon},${near.lat}")
            append("&access_token=").append(token)
        }
        val features = getJson(url).optJSONArray("features") ?: return null
        if (features.length() == 0) return null
        val c = features.getJSONObject(0).optJSONArray("center") ?: return null
        return LatLon(c.getDouble(1), c.getDouble(0))
    }

    // -----------------------------------------------------------------------

    internal fun parseRoute(r: JSONObject): Route {
        val geometry = r.optString("geometry")
        if (geometry.isEmpty()) throw NavException("Route has no geometry")
        val pts = Geo.decodePolyline(geometry, 6)
        if (pts.size < 2) throw NavException("Route geometry too short")
        val cum = Geo.cumulative(pts)

        val legs = r.optJSONArray("legs") ?: JSONArray()

        // ---- speed limits, one per polyline segment -------------------------
        val nSeg = pts.size - 1
        val limits = IntArray(nSeg)                 // 0 = unknown by default
        var seg = 0
        for (li in 0 until legs.length()) {
            val ms = legs.getJSONObject(li)
                .optJSONObject("annotation")?.optJSONArray("maxspeed") ?: continue
            for (k in 0 until ms.length()) {
                if (seg >= nSeg) break
                limits[seg++] = parseMaxspeed(ms.optJSONObject(k))
            }
        }
        // A short annotation array means we mis-guessed the segmentation. Rather
        // than shift every limit by one road, leave the tail unknown.
        if (seg != nSeg) {
            for (k in seg until nSeg) limits[k] = 0
        }

        // ---- live traffic, one value per polyline segment -------------------
        val congestion = IntArray(nSeg)
        var cseg = 0
        for (li in 0 until legs.length()) {
            val cg = legs.getJSONObject(li)
                .optJSONObject("annotation")?.optJSONArray("congestion") ?: continue
            for (k in 0 until cg.length()) {
                if (cseg >= nSeg) break
                congestion[cseg++] = congestionLevel(cg.optString(k))
            }
        }

        // ---- closures --------------------------------------------------------
        // These do NOT arrive as a per-segment annotation array, however much
        // asking for `annotations=closure` suggests they might. They come back
        // as `legs[].closures`, a list of vertex ranges into the geometry --
        // and reading them as an annotation fails silently, leaving every
        // segment open and the whole feature quietly dead.
        val closed = BooleanArray(nSeg)
        var vertexBase = 0
        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            val cl = leg.optJSONArray("closures")
            if (cl != null) {
                for (k in 0 until cl.length()) {
                    val c = cl.optJSONObject(k) ?: continue
                    val a = vertexBase + c.optInt("geometry_index_start", -1)
                    val b = vertexBase + c.optInt("geometry_index_end", -1)
                    if (a < 0 || b < a) continue
                    // A range a..b of vertices covers segments a..b-1.
                    for (s in a until minOf(b, nSeg)) closed[s] = true
                }
            }
            // Legs share their join vertex, so the next leg starts one earlier
            // than a naive running total would suggest.
            val n = leg.optJSONObject("annotation")?.optJSONArray("congestion")?.length()
                ?: leg.optJSONObject("annotation")?.optJSONArray("duration")?.length()
                ?: 0
            vertexBase += n
        }

        // ---- which country each segment is in -------------------------------
        // Each leg lists the countries it passes through in `admins`; each
        // intersection says which of them it is in (`admin_index`) and where
        // it sits in the leg's geometry (`geometry_index`, relative to the
        // leg). No extra request parameter: it comes with steps=true.
        // https://docs.mapbox.com/api/navigation/directions/
        // What the border crossing needs is on the route before the car gets
        // there, so the camera and speed rules switch on time with no signal.
        val countryCodes = ArrayList<String>()
        val countryIdx = ByteArray(nSeg) { -1 }
        var legBase = 0
        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            val legSegs = leg.optJSONObject("annotation")?.optJSONArray("congestion")?.length()
                ?: leg.optJSONObject("annotation")?.optJSONArray("duration")?.length()
                ?: (nSeg - legBase)
            val admins = leg.optJSONArray("admins")
            val legCode = IntArray(admins?.length() ?: 0) { k ->
                val cc = admins?.optJSONObject(k)?.optString("iso_3166_1")?.trim()?.uppercase()
                if (cc == null || cc.length != 2 || countryCodes.size >= Byte.MAX_VALUE) -1
                else countryCodes.indexOf(cc).takeIf { it >= 0 } ?: run {
                    countryCodes.add(cc); countryCodes.size - 1
                }
            }
            // (vertex in the whole route, country index), in route order.
            val marks = ArrayList<Pair<Int, Int>>()
            val steps = leg.optJSONArray("steps") ?: JSONArray()
            for (si in 0 until steps.length()) {
                val ints = steps.optJSONObject(si)?.optJSONArray("intersections") ?: continue
                for (ii in 0 until ints.length()) {
                    val x = ints.optJSONObject(ii) ?: continue
                    val gi = x.optInt("geometry_index", -1)
                    val ai = x.optInt("admin_index", -1)
                    val code = legCode.getOrNull(ai) ?: -1
                    if (gi >= 0 && code >= 0) marks.add(legBase + gi to code)
                }
            }
            marks.sortBy { it.first }
            val legEnd = minOf(nSeg, legBase + legSegs)
            if (marks.isEmpty()) {
                // No intersections to say where: a one-country leg is still known.
                val only = legCode.filter { it >= 0 }.distinct().singleOrNull()
                if (only != null) for (s in legBase until legEnd) countryIdx[s] = only.toByte()
            } else {
                for ((m, mark) in marks.withIndex()) {
                    val from = if (m == 0) legBase else mark.first
                    val to = if (m + 1 < marks.size) marks[m + 1].first else legEnd
                    for (s in maxOf(legBase, from) until minOf(legEnd, to)) {
                        countryIdx[s] = mark.second.toByte()
                    }
                }
            }
            legBase += legSegs
        }

        // ---- maneuvers, pinned to a distance along the polyline -------------
        val maneuvers = ArrayList<ManeuverPoint>()
        var walkedM = 0.0
        var lastVertex = 0
        for (li in 0 until legs.length()) {
            val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
            for (si in 0 until steps.length()) {
                val step = steps.getJSONObject(si)
                val man = step.optJSONObject("maneuver") ?: continue
                val loc = man.optJSONArray("location")
                val mLat: Double
                val mLon: Double
                if (loc != null && loc.length() >= 2) {
                    mLon = loc.getDouble(0); mLat = loc.getDouble(1)
                } else { mLon = Double.NaN; mLat = Double.NaN }

                val vertex =
                    if (mLat.isNaN()) nearestVertexByDistance(cum, walkedM, lastVertex)
                    else nearestVertex(pts, cum, mLat, mLon, walkedM, lastVertex)
                lastVertex = vertex

                val street = step.optString("name").ifEmpty { step.optString("ref") }

                // Banners belong to the step you are *driving*, and describe
                // the maneuver at its **end** -- Mapbox counts
                // `distanceAlongGeometry` down through the current step towards
                // the next turn. A step's own `maneuver`, by contrast, is the
                // turn that got you *into* it.
                //
                // So a maneuver's lane guidance and its exit sign live on the
                // step *before* it. Reading them off the same step paired every
                // junction with the lanes for the junction after it -- which on
                // the R0 meant being told to take the right-hand exit while the
                // arrows underneath pointed left, because the left turn was the
                // one waiting at the far end of the slip road. Getting this
                // backwards is worse than having no lane guidance at all.
                val prev = if (si > 0) steps.optJSONObject(si - 1) else null
                val banners = prev?.optJSONArray("bannerInstructions")
                val sign = JunctionSign.fromBanners(
                    banners, step.optString("ref"), step.optString("destinations")
                )
                // Where the exit really points, for roundabouts only.
                //
                // First the banner's `degrees` (bannerExitAngle). The bearings are
                // only the fallback: on a roundabout step Mapbox/OSRM put the
                // maneuver at the ENTRY, so their difference is the veer into the
                // circle, not the exit (1.31; to be confirmed on the road).
                //
                // bearing_before is the compass heading as you arrive, bearing_after
                // the heading as you leave; the difference is the turn. Normalised to
                // -180..180 so 0 is straight on and positive is to the right, which is
                // what both the HUD and ManeuverView draw in.
                //
                // Only when Mapbox gave BOTH -- it omits them on some steps, and a
                // missing field read as 0.0 would mean "straight ahead", stated with
                // total confidence. Null instead, and the old exit-number table remains
                // as the fallback.
                val exitBearing: Int? = bannerExitAngle(banners, step) ?: run {
                    if (!man.has("bearing_before") || !man.has("bearing_after")) return@run null
                    val before = man.optDouble("bearing_before", Double.NaN)
                    val after  = man.optDouble("bearing_after",  Double.NaN)
                    if (before.isNaN() || after.isNaN()) return@run null
                    var d = after - before
                    while (d > 180.0)  d -= 360.0
                    while (d < -180.0) d += 360.0
                    Math.round(d).toInt()
                }

                maneuvers.add(
                    ManeuverPoint(
                        alongM = cum[vertex],
                        code = Man.fromMapbox(man.optString("type"), man.optString("modifier")),
                        exit = man.optInt("exit", 0),
                        name = street,
                        lanes = LaneGuidance.fromBanners(banners),
                        sign = if (sign.isEmpty) null else sign,
                        exitBearing = exitBearing
                    )
                )
                walkedM += step.optDouble("distance", 0.0)
            }
        }
        maneuvers.sortBy { it.alongM }

        // ---- traits ----------------------------------------------------------
        // Tolls, ferries and the rest arrive per *intersection*, which is an
        // odd place to keep a property of the whole route, but it is where
        // Mapbox puts them.
        var traits = 0
        for (li in 0 until legs.length()) {
            val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
            for (si in 0 until steps.length()) {
                val ints = steps.getJSONObject(si).optJSONArray("intersections") ?: continue
                for (ii in 0 until ints.length()) {
                    val cs = ints.optJSONObject(ii)?.optJSONArray("classes") ?: continue
                    for (ci in 0 until cs.length()) traits = traits or RouteTrait.fromClass(cs.optString(ci))
                }
            }
        }
        if (closed.any { it }) traits = traits or RouteTrait.CLOSURE

        // "E40 · A12": the roads this route actually uses, for the picker.
        val refs = LinkedHashSet<String>()
        for (li in 0 until legs.length()) {
            val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
            for (si in 0 until steps.length()) {
                steps.getJSONObject(si).optString("ref").split(";", ",")
                    .map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach { refs.add(it) }
            }
        }

        return Route(
            pts = pts,
            cum = cum,
            limitKph = limits,
            maneuvers = maneuvers,
            totalDistanceM = if (r.has("distance")) r.getDouble("distance") else cum.last(),
            totalDurationS = r.optDouble("duration", 0.0),
            provider = name,
            summary = refs.take(3).joinToString(" \u00b7 "),
            durationTypicalS = r.optDouble("duration_typical", r.optDouble("duration", 0.0)),
            congestion = congestion,
            closed = closed,
            traits = traits,
            countryCodes = countryCodes,
            countryIdx = countryIdx
        )
    }

    /** `"unknown" | "low" | "moderate" | "heavy" | "severe"` -> 0..4 */
    internal fun congestionLevel(s: String?): Int = when (s) {
        "low" -> 1
        "moderate" -> 2
        "heavy" -> 3
        "severe" -> 4
        else -> 0
    }

    /** `{"speed":50,"unit":"km/h"}` | `{"unknown":true}` | `{"none":true}` */
    private fun parseMaxspeed(o: JSONObject?): Int {
        if (o == null) return 0
        if (o.optBoolean("none", false)) return -1          // derestricted
        if (o.optBoolean("unknown", false)) return 0
        if (!o.has("speed")) return 0
        val v = o.optInt("speed", 0)
        if (v <= 0) return 0
        return if (o.optString("unit") == "mph") Math.round(v * 1.609344).toInt() else v
    }

    /**
     * A roundabout exit's turn from the banner's `degrees`, -180..180, positive
     * right; null when no banner carries one.
     *
     * Mapbox documents `degrees` as "the degrees at which you will be exiting a
     * roundabout, assuming 180 indicates going straight through": how far round
     * the circle, in the direction of travel. So 180 - degrees is the turn where
     * roundabouts run anticlockwise, mirrored where `driving_side` is left.
     */
    private fun bannerExitAngle(banners: JSONArray?, step: JSONObject): Int? {
        if (banners == null) return null
        for (i in banners.length() - 1 downTo 0) {   // the nearest banner first
            val p = banners.optJSONObject(i)?.optJSONObject("primary") ?: continue
            val deg = p.optDouble("degrees", Double.NaN)
            if (deg.isNaN() || deg < 0.0 || deg > 360.0) continue
            val side = p.optString("driving_side").ifEmpty { step.optString("driving_side") }
            val turn = 180.0 - deg
            return Math.round(if (side == "left") -turn else turn).toInt()
        }
        return null
    }

    /**
     * Finds the polyline vertex a maneuver sits on. Searches near where the
     * accumulated step distances say we should be, so a route that crosses
     * itself does not pin a turn to the wrong pass.
     */
    private fun nearestVertex(
        pts: Array<DoubleArray>, cum: DoubleArray,
        lat: Double, lon: Double, expectedAlong: Double, notBefore: Int
    ): Int {
        var best = -1
        var bestD = Double.MAX_VALUE
        val slack = 400.0
        for (j in notBefore until pts.size) {
            if (cum[j] < expectedAlong - slack) continue
            if (cum[j] > expectedAlong + slack && best >= 0) break
            val d = Geo.haversine(pts[j][0], pts[j][1], lat, lon)
            if (d < bestD) { bestD = d; best = j }
        }
        if (best < 0) {                                  // fall back to a full scan
            for (j in notBefore until pts.size) {
                val d = Geo.haversine(pts[j][0], pts[j][1], lat, lon)
                if (d < bestD) { bestD = d; best = j }
            }
        }
        return if (best < 0) notBefore else best
    }

    private fun nearestVertexByDistance(cum: DoubleArray, target: Double, notBefore: Int): Int {
        var best = notBefore
        var bestD = Double.MAX_VALUE
        for (j in notBefore until cum.size) {
            val d = abs(cum[j] - target)
            if (d < bestD) { bestD = d; best = j } else if (cum[j] > target) break
        }
        return best
    }

    /**
     * Every failure comes out as a [NavException] -- no signal, a timeout, an
     * error page, a body that is not JSON -- so the callers' one catch is
     * enough and nothing escapes a network path.
     */
    private fun getJson(url: String): JSONObject {
        val body = try {
            http(url)
        } catch (e: NavException) {
            throw e
        } catch (e: Exception) {
            throw NavException("Network error: ${e.message}", e)
        }
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw NavException("Unreadable answer from Mapbox: ${body.take(80)}", e)
        }
    }
}

private fun mapboxGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 8000
    conn.readTimeout = 12000
    conn.requestMethod = "GET"
    conn.setRequestProperty("User-Agent", com.mihai.navhud.Net.USER_AGENT)
    try {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        if (code !in 200..299) {
            throw NavException("HTTP $code from ${URL(url).host}: ${body.take(200)}")
        }
        return body
    } finally {
        conn.disconnect()
    }
}
