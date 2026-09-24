package com.mihai.navhud.nav

import com.mihai.navhud.Geo
import com.mihai.navhud.alerts.SpeedCamera
import com.mihai.navhud.alerts.SpeedCameras
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One road, as a polyline with the tags that matter at the wheel. */
class RoadWay(
    val id: Long,
    val pts: Array<DoubleArray>,     // [lat, lon]
    val name: String,
    val ref: String,
    val limitKph: Int,               // 0 unknown, -1 derestricted
    val kind: String,                // motorway, primary, residential...
    /**
     * 0 two-way, +1 driveable only along the way as drawn, -1 only against it.
     *
     * OSM's `oneway=-1` means the legal direction is the *opposite* of the way
     * as it was digitised. Collapsing that to a plain "it is one-way" made the
     * matcher compare the car's heading against the forward bearing of a road
     * that can only be driven backwards, so the delta was always ~180 deg, the
     * road was always rejected, and the car sat on a street the app claimed
     * not to know: no name, no speed limit, no snapping -- and, because the
     * on-our-road camera filter needs a matched road, no filtering either.
     * https://wiki.openstreetmap.org/wiki/Key:oneway
     */
    val onewayDir: Int,

    /**
     * `source:maxspeed`, `maxspeed:type` or `zone:traffic`, verbatim.
     *
     * The only tag on a road that names the *region* it is in -- "BE-VLG:urban",
     * "BE-WAL:rural" -- which in Belgium is the difference between a rural
     * default of 70 and one of 90. Roughly 1.2 % of the untagged road length
     * carries one, so it will not fill the gap on its own; but a single road
     * anywhere in the fetched window reveals the region for all of them.
     */
    val schemeTag: String = "",

    /** `lit=yes` is the closest thing OSM has to "this is a built-up area". */
    val lit: Boolean? = null,

    /** `lanes`: in the way's direction when one-way, else both. 0 unknown. */
    val lanes: Int = 0,

    /** `int_ref`, "E 40": Romania's E-roads have a limit of their own. */
    val intRef: String = ""
) {
    /** What to print on the road-name pill: the number if it has one. */
    val label: String get() = when {
        ref.isNotBlank() && name.isNotBlank() -> "$ref · $name"
        ref.isNotBlank() -> ref
        else -> name
    }

    /** Rough priority: a motorway beats the slip road running beside it. */
    val importance: Int get() = when (kind) {
        "motorway" -> 6
        "trunk" -> 5
        "primary" -> 4
        "secondary" -> 3
        "tertiary" -> 2
        "unclassified", "residential" -> 1
        else -> 0
    }
}

/**
 * A thing on the road worth a word of warning, from OpenStreetMap.
 *
 * Waze warns about railway crossings, speed bumps and toll booths, and all
 * three are in OSM as plain nodes, so they cost one extra clause on a query
 * the app already makes. What they have in common is that they are invisible
 * until you are nearly on them — a level crossing behind a bend, a speed table
 * on an unlit street — which is exactly the case a nav display is for.
 *
 * Deliberately *not* here: anything that depends on other drivers reporting
 * it. That is Waze's core and it needs Waze's user base; a DIY build with one
 * user would show an empty map and a stale one.
 */
data class RoadFeature(val id: Long, val kind: Int, val lat: Double, val lon: Double) {
    companion object {
        const val LEVEL_CROSSING = 1
        const val SPEED_BUMP = 2
        const val TOLL_BOOTH = 3

        fun kindOf(tags: org.json.JSONObject): Int {
            if (tags.optString("railway") == "level_crossing" ||
                tags.optString("railway") == "crossing") return LEVEL_CROSSING
            if (tags.optString("barrier") == "toll_booth") return TOLL_BOOTH
            return when (tags.optString("traffic_calming")) {
                "bump", "hump", "table", "cushion" -> SPEED_BUMP
                else -> 0
            }
        }
    }
}

/** Everything we know about the roads around the car right now. */
/** The first of these that is not blank, or "". */
private fun firstNonBlank(vararg v: String): String =
    v.firstOrNull { it.isNotBlank() } ?: ""

class Area(
    val centreLat: Double,
    val centreLon: Double,
    val radiusM: Double,
    val roads: List<RoadWay>,
    val cameras: List<SpeedCamera>,
    val fetchedAtMs: Long,
    val features: List<RoadFeature> = emptyList(),
    /**
     * This window came off the disk, not the network.
     *
     * Worth saying out loud on screen: the difference between "no speed limit
     * here" and "no signal here" is the difference between the app being
     * wrong and the app being honest, and only the caller that built the Area
     * is in a position to know which it is.
     */
    val fromCache: Boolean = false
) {
    /**
     * Which region this window is in -- "BE-VLG", "BE-WAL", "BE-BRU", "FR"...
     *
     * Worked out from the roads themselves rather than asked of a geocoder.
     * About 148,000 Belgian ways carry `source:maxspeed` or `zone:traffic`, and
     * every one of those values starts with the region code. A fetch window is
     * kilometres across and contains thousands of ways, so in any populated
     * area at least a few will say where they are -- and a region boundary is
     * hundreds of times bigger than the window, so a majority vote over them is
     * about as reliable as a reverse geocode, costs no network call, and cannot
     * fail while out of signal.
     *
     * Null when nothing in the window says. The caller then falls back to the
     * country code, which for Belgium means the lower of the two rural limits.
     */
    val regionCode: String? by lazy {
        val votes = HashMap<String, Int>()
        for (r in roads) {
            val (region, _) = SpeedDefaults.fromSchemeTag(r.schemeTag)
            if (region != null && region.contains('-')) {
                votes[region] = (votes[region] ?: 0) + 1
            }
        }
        // A plain "BE" tells us nothing we did not already know, so only
        // subdivided codes vote. Ties do not matter: any of them beats null.
        votes.maxByOrNull { it.value }?.key
    }

    /**
     * A grid, so nothing has to walk every road in the window.
     *
     * At motorway speed the fetch radius is six kilometres, which is a few
     * thousand ways of ten to fifty points each. Everything that asks "which
     * road is nearest to this point" used to scan all of them, and the free
     * drive tick asks that once for the car and then *once per camera in
     * range* -- so the cost was roads x points x cameras, four times a second.
     * Measured on a desktop JVM at five thousand roads: 4.4 ms with no cameras,
     * 19 ms with five. On a head unit's CPU that is a core pegged solid, which
     * is heat, a stuttering map, and a location callback that cannot get a look
     * in on the same looper.
     *
     * Cells are about 250 m. A way is filed in every cell its bounding box
     * touches, so a lookup returns a superset of the roads within a couple of
     * hundred metres and the caller does its exact test on those.
     *
     * Built once, on the thread that fetched the area, and never mutated.
     */
    private val index: Map<Long, IntArray> by lazy { buildIndex() }

    /** Force the grid to be built now, off the tick thread. */
    fun warmIndex() { index }

    private fun cellKey(latCell: Int, lonCell: Int): Long =
        (latCell.toLong() shl 32) or (lonCell.toLong() and 0xFFFFFFFFL)

    private fun latCellOf(lat: Double) = Math.floor(lat / CELL_DEG_LAT).toInt()
    private fun lonCellOf(lon: Double) = Math.floor(lon / cellDegLon).toInt()

    /** Longitude degrees per cell, widened by latitude so cells stay square. */
    private val cellDegLon: Double =
        CELL_DEG_LAT / Math.max(0.2, Math.cos(Math.toRadians(centreLat)))

    private fun buildIndex(): Map<Long, IntArray> {
        val acc = HashMap<Long, MutableList<Int>>(roads.size * 2)
        for (i in roads.indices) {
            val pts = roads[i].pts
            if (pts.isEmpty()) continue
            var minLat = pts[0][0]; var maxLat = pts[0][0]
            var minLon = pts[0][1]; var maxLon = pts[0][1]
            for (p in pts) {
                if (p[0] < minLat) minLat = p[0]
                if (p[0] > maxLat) maxLat = p[0]
                if (p[1] < minLon) minLon = p[1]
                if (p[1] > maxLon) maxLon = p[1]
            }
            val la0 = latCellOf(minLat); val la1 = latCellOf(maxLat)
            val lo0 = lonCellOf(minLon); val lo1 = lonCellOf(maxLon)
            // A way spanning a huge box is a motorway drawn as one element;
            // filing it in every cell it crosses is still far cheaper than
            // scanning it every tick.
            // A bounding box this wide is not a road, it is a way that wraps
            // the antimeridian or a parse accident. Filing it in a hundred
            // thousand cells would hang the fetch thread; leave it out of the
            // grid and let the exact test find it if it is ever queried
            // directly. (`WIDE_BOX_CELLS` is far beyond any real way: 64 cells
            // is 16 km.)
            if (la1 - la0 > WIDE_BOX_CELLS || lo1 - lo0 > WIDE_BOX_CELLS) continue
            for (la in la0..la1) for (lo in lo0..lo1) {
                acc.getOrPut(cellKey(la, lo)) { ArrayList(4) }.add(i)
            }
        }
        val out = HashMap<Long, IntArray>(acc.size * 2)
        for ((k, v) in acc) out[k] = v.toIntArray()
        return out
    }

    /**
     * Roads whose bounding box lies within roughly `radiusM` of this point.
     *
     * A superset: the caller still does the exact distance test. Verified
     * against the real Brussels network -- 16,985 ways, 100,382 vertices --
     * over three hundred random query points against a brute-force scan: no
     * road within the match limit was ever missed, and the candidate set was
     * 565 of 16,985.
     *
     * The guaranteed coverage is two cells, 500 m, against the 35 m the
     * matcher actually needs, so there is fourteen times more margin than the
     * job requires.
     */
    fun roadsNear(lat: Double, lon: Double, radiusM: Double): List<RoadWay> {
        if (roads.isEmpty()) return roads
        val idx = index
        // Only reachable when every way was degenerate, since parse() rejects
        // anything with fewer than two points -- but a caller that builds an
        // Area by hand can still get here, and answering "no roads" when we
        // hold some would silently disable the matcher.
        if (idx.isEmpty()) return roads
        val spanLat = Math.max(1, Math.ceil(radiusM / CELL_M).toInt())
        val la = latCellOf(lat); val lo = lonCellOf(lon)
        var seen: HashSet<Int>? = null
        var single: IntArray? = null
        for (dla in -spanLat..spanLat) for (dlo in -spanLat..spanLat) {
            val cell = idx[cellKey(la + dla, lo + dlo)] ?: continue
            if (seen == null && single == null) { single = cell; continue }
            if (seen == null) {
                val first = single ?: IntArray(0)
                seen = HashSet<Int>(first.size * 4)
                for (v in first) seen.add(v)
                single = null
            }
            for (v in cell) seen.add(v)
        }
        single?.let { one -> return object : AbstractList<RoadWay>() {
            override val size get() = one.size
            override fun get(index: Int) = roads[one[index]]
        } }
        val s = seen ?: return emptyList()
        val out = ArrayList<RoadWay>(s.size)
        for (i in s) out.add(roads[i])
        return out
    }

    companion object {
        /** ~250 m at Belgian latitudes. */
        const val CELL_DEG_LAT = 0.00225
        const val CELL_M = 250.0

        /** Widest bounding box a real way can have, in cells. 64 is 16 km. */
        const val WIDE_BOX_CELLS = 64
    }
}

/**
 * The road network around the car, straight from OpenStreetMap.
 *
 * This is what makes the app useful with no destination set. With a route, the
 * speed limit comes free with the directions -- Mapbox annotates every segment.
 * Without one there is nothing to annotate, so the roads have to be fetched and
 * matched directly, which is what a dedicated speed-limit warner does.
 *
 * The radius is scaled by speed rather than fixed. At 120 km/h you need a few
 * kilometres of motorway ahead and the network is sparse; in town you need a
 * few hundred metres and the network is dense. Roughly a minute of driving
 * either way keeps the response a sensible size at both ends, and means the
 * refetch interval stays about the same whether you are crawling or cruising.
 */
object AreaRoads {

    private const val OVERPASS = "https://overpass-api.de/api/interpreter"

    /** Bounds on the fetch radius, metres. */
    const val MIN_RADIUS_M = 1200.0
    const val MAX_RADIUS_M = 6000.0

    /** Roughly this many seconds of driving ahead. */
    private const val LOOKAHEAD_S = 60.0

    /** Refetch once the car has left this fraction of the old circle. */
    const val REFETCH_FRACTION = 0.45

    /** Beyond this, we are not on any road we fetched. */
    const val MATCH_LIMIT_M = 35.0

    /**
     * How far a projection may be dragged to reach a way's end vertex before
     * we stop believing it. Beyond this the car has genuinely left the way and
     * the matcher should be finding it a new one; within it, we are simply
     * standing at the junction.
     */
    const val CLAMP_TOLERANCE_M = 10.0

    /** Roads that a car can actually be driven on. */
    private const val DRIVABLE =
        "motorway|trunk|primary|secondary|tertiary|unclassified|residential|" +
        "living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"

    fun radiusFor(speedMps: Double): Double =
        (speedMps * LOOKAHEAD_S).coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)

    /**
     * Where to centre the query. Ahead of the car rather than on it: half a
     * circle behind you is road you have already driven.
     */
    fun centreFor(lat: Double, lon: Double, headingDeg: Double?, radiusM: Double): DoubleArray {
        if (headingDeg == null) return doubleArrayOf(lat, lon)
        return Geo.destination(lat, lon, headingDeg, radiusM * 0.35)
    }

    /**
     * True when the cached area no longer covers where we are.
     *
     * "Covers" is the car plus [1 - REFETCH_FRACTION] of the radius it wants,
     * inside the window. For a window fetched at the radius wanted that is the
     * old rule -- refetch past 45 % of the radius from the centre -- and it
     * stays right when the window came from the cache at a different size.
     */
    fun needsRefetch(area: Area?, lat: Double, lon: Double, wantRadiusM: Double): Boolean {
        if (area == null) return true
        // A big change in speed changes what we need to see.
        if (wantRadiusM > area.radiusM * 1.6 || wantRadiusM < area.radiusM * 0.5) return true
        val d = Geo.haversine(area.centreLat, area.centreLon, lat, lon)
        return d + wantRadiusM * (1.0 - REFETCH_FRACTION) > area.radiusM
    }

    // ---- windows along a route -----------------------------------------------
    //
    // On a route the window follows the route instead of the car's heading:
    // circles centred on the line every ROUTE_WINDOW_STEP_M. The service asks
    // for the one the car is in, and prefetches the ones ahead -- with the
    // same centre and radius, so the live lookup is an exact cache hit and a
    // dead zone ahead is already on disk when the car reaches it.

    const val ROUTE_WINDOW_STEP_M = 2000.0
    const val ROUTE_WINDOW_RADIUS_M = 2000.0

    /** Keep the current window this far past its centre before moving on. */
    const val ROUTE_WINDOW_KEEP_M = ROUTE_WINDOW_STEP_M / 2 + 200.0

    class RouteWindow(val index: Int, val lat: Double, val lon: Double, val radiusM: Double)

    fun routeWindowCount(r: Route): Int =
        (r.totalDistanceM / ROUTE_WINDOW_STEP_M).toInt() + 1

    fun routeWindowIndex(alongM: Double): Int =
        Math.round(alongM / ROUTE_WINDOW_STEP_M).toInt().coerceAtLeast(0)

    fun routeWindow(r: Route, index: Int): RouteWindow {
        val p = Geo.pointAlong(r.pts, r.cum, index * ROUTE_WINDOW_STEP_M)
        return RouteWindow(index, p[0], p[1], ROUTE_WINDOW_RADIUS_M)
    }

    /**
     * The route windows to make sure of next, or null while enough is already
     * ahead. [next] is the first window not yet made sure of; the car's own
     * window if it has overtaken that. Tops up to [aheadM] past the car once
     * less than [refillM] remains, so data arrives in chunks, not all at once.
     */
    fun prefetchRange(r: Route, alongM: Double, next: Int, aheadM: Double, refillM: Double): IntRange? {
        val here = routeWindowIndex(alongM)
        val from = maxOf(next, here)
        val last = routeWindowCount(r) - 1
        if (from > last || (from - here) * ROUTE_WINDOW_STEP_M >= refillM) return null
        return from..minOf(last, routeWindowIndex(alongM + aheadM))
    }

    /** True while [area] still serves a car on a route: centred near enough. */
    fun routeWindowStillGood(area: Area?, lat: Double, lon: Double): Boolean =
        area != null && area.radiusM >= ROUTE_WINDOW_RADIUS_M - 1.0 &&
            Geo.haversine(area.centreLat, area.centreLon, lat, lon) <= ROUTE_WINDOW_KEEP_M

    fun buildQuery(lat: Double, lon: Double, radiusM: Int): String = """
        [out:json][timeout:25];
        (
          way(around:$radiusM,${fmt(lat)},${fmt(lon)})["highway"~"^($DRIVABLE)$"];
          node(around:$radiusM,${fmt(lat)},${fmt(lon)})["highway"="speed_camera"];
          node(around:$radiusM,${fmt(lat)},${fmt(lon)})["enforcement"="maxspeed"];
          node(around:$radiusM,${fmt(lat)},${fmt(lon)})["railway"="level_crossing"];
          node(around:$radiusM,${fmt(lat)},${fmt(lon)})["traffic_calming"];
          node(around:$radiusM,${fmt(lat)},${fmt(lon)})["barrier"="toll_booth"];
        );
        out geom;
    """.trimIndent()

    private fun fmt(v: Double) = "%.5f".format(java.util.Locale.US, v)

    /**
     * Blocking network call; run it off the main thread.
     *
     * Disk, then network, then disk again at any age. The last step is the
     * point of the whole arrangement: the roads that have no mobile coverage
     * are the ones worth having cached, and a week-old speed limit on a road
     * whose limit has not changed since it was signed beats a blank display.
     * Only when there is nothing on disk at all does the failure reach the
     * caller, which is then a genuine "we have never seen this place".
     */
    fun fetch(
        lat: Double, lon: Double, radiusM: Double, nowMs: Long,
        /**
         * Wall clock for the cache's file ages. Not [nowMs]: the service's
         * clock is elapsedRealtime, and against file modification times that
         * made every cached file look fresh for ever.
         */
        wallMs: Long = System.currentTimeMillis()
    ): Area {
        AreaCache.get(lat, lon, radiusM, AreaCache.FRESH_MS, wallMs)?.let {
            // Unreadable -- cut short by a crash or a power cut: drop it and
            // ask again, or it would fail here until it went stale.
            runCatching { return build(it.lat, it.lon, it.radiusM, it.body, nowMs, fromCache = true) }
            AreaCache.forget(it)
        }
        // Only the network call is guarded. A body that comes back and then
        // fails to parse is a bug worth seeing, not a reason to quietly serve
        // last week's roads instead.
        val fresh = try {
            download(lat, lon, radiusM)
        } catch (e: Exception) {
            // Anything on disk, at any age: first a window that covers what
            // was asked, then any window the point is inside at all. Each is
            // labelled with the circle it really covers.
            val h = AreaCache.get(lat, lon, radiusM, Long.MAX_VALUE, wallMs)
                ?: AreaCache.nearest(lat, lon, Long.MAX_VALUE, wallMs)
                ?: throw e
            return build(h.lat, h.lon, h.radiusM, h.body, nowMs, fromCache = true)
        }
        return build(lat, lon, radiusM, fresh, nowMs, fromCache = false)
    }

    /**
     * Download a window into the cache without parsing it: the route prefetch.
     * Throws on any failure, [OverpassBusy] included.
     */
    fun prefetch(lat: Double, lon: Double, radiusM: Double) {
        download(lat, lon, radiusM)
    }

    private fun download(lat: Double, lon: Double, radiusM: Double): String {
        val gate = fairUse
        val wait = gate.waitMs(monoMs())
        if (wait < 0) throw OverpassBusy("Overpass asked us to back off")
        // Only the aux thread gets here, so this sleep delays nothing urgent.
        if (wait > 0) Thread.sleep(wait)
        gate.sent(monoMs())
        val body = try {
            transport(OVERPASS,
                "data=" + java.net.URLEncoder.encode(buildQuery(lat, lon, radiusM.toInt()), "UTF-8"))
        } catch (e: HttpStatus) {
            gate.answered(e.code, monoMs())
            throw e
        }
        gate.answered(200, monoMs())
        // Overpass answers some failures with 200 and a remark instead of
        // elements; caching that would serve an empty map for a week.
        if (!body.trimStart().startsWith("{") || !body.contains("\"elements\"")) {
            throw java.io.IOException("Overpass returned no data: ${body.take(120)}")
        }
        AreaCache.put(lat, lon, radiusM, body)
        return body
    }

    // ---- fair use -------------------------------------------------------------

    /** Overpass is refusing us for now; the caller should fall back to disk. */
    class OverpassBusy(msg: String) : java.io.IOException(msg)

    /**
     * The public Overpass instance's etiquette: at most ~10,000 requests and
     * ~1 GB a day, and 429 / 504 mean "too many" / "too busy" -- back off.
     * https://dev.overpass-api.de/overpass-doc/en/preface/commons.html
     *
     * Every request here goes through one of these: a gap of [minGapMs]
     * between requests, and after a 429 or 504 nothing at all for a back-off
     * that doubles up to [maxBackoffMs] and resets on the next success.
     */
    class FairUse(
        val minGapMs: Long = 3_000L,
        val baseBackoffMs: Long = 30_000L,
        val maxBackoffMs: Long = 600_000L
    ) {
        private var lastSentMs = Long.MIN_VALUE / 2
        private var backoffMs = 0L
        var busyUntilMs = Long.MIN_VALUE / 2
            private set

        /** How long to wait before sending, or -1 while backing off. */
        @Synchronized fun waitMs(nowMs: Long): Long =
            if (nowMs < busyUntilMs) -1L else maxOf(0L, lastSentMs + minGapMs - nowMs)

        @Synchronized fun sent(nowMs: Long) { lastSentMs = nowMs }

        /** @param code the HTTP status, or null when there was no answer at all */
        @Synchronized fun answered(code: Int?, nowMs: Long) {
            when (code) {
                429, 504 -> {
                    backoffMs = (backoffMs * 2).coerceIn(baseBackoffMs, maxBackoffMs)
                    busyUntilMs = nowMs + backoffMs
                }
                in 200..299 -> backoffMs = 0L
            }
        }
    }

    /** Swappable for tests, which cannot wait three seconds a request. */
    @Volatile internal var fairUse = FairUse()

    /** A non-2xx answer, with its status, so 429 and 504 can be told apart. */
    class HttpStatus(val code: Int, msg: String) : java.io.IOException(msg)

    private fun monoMs() = System.nanoTime() / 1_000_000L

    /**
     * The HTTP round trip, swappable so the failure paths can be tested
     * without a network. Returns the body of a 2xx; throws on anything else.
     */
    @Volatile internal var transport: (url: String, body: String) -> String = { u, b -> post(u, b) }

    /** Cached and fresh bodies go through exactly the same parser. */
    private fun build(
        lat: Double, lon: Double, radiusM: Double,
        body: String, nowMs: Long, fromCache: Boolean
    ): Area {
        val (roads, cams) = parse(body)
        return Area(lat, lon, radiusM, roads, cams, nowMs, parseFeatures(body), fromCache).also {
            // Build the grid here, on the thread that did the fetch, rather
            // than lazily on the first lookup -- which would be the 4 Hz tick.
            it.warmIndex()
        }
    }

    /**
     * Crossings, bumps and toll booths, out of the same response.
     *
     * A second pass over the same JSON rather than a third element in the
     * return type: the parse of roads and cameras is the one that matters, it
     * is already wrapped in per-element error handling, and threading a third
     * list through it would have meant touching every caller and every test to
     * add a feature none of them care about.
     */
    fun parseFeatures(json: String): List<RoadFeature> {
        val els = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<RoadFeature>()
        for (i in 0 until els.length()) {
            runCatching {
                val e = els.optJSONObject(i) ?: return@runCatching
                if (e.optString("type") != "node") return@runCatching
                val tags = e.optJSONObject("tags") ?: return@runCatching
                val kind = RoadFeature.kindOf(tags)
                if (kind == 0) return@runCatching
                val lat = e.optDouble("lat", Double.NaN)
                val lon = e.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return@runCatching
                out.add(RoadFeature(e.optLong("id"), kind, lat, lon))
            }
        }
        return out
    }

    /** `out geom` puts the coordinates inline on each way — no second pass. */
    fun parse(json: String): Pair<List<RoadWay>, List<SpeedCamera>> {
        val root = JSONObject(json)
        val els = root.optJSONArray("elements") ?: return emptyList<RoadWay>() to emptyList()
        val roads = ArrayList<RoadWay>(els.length())
        val cams = ArrayList<SpeedCamera>()

        for (i in 0 until els.length()) {
            // One malformed element must not take the other three thousand
            // roads with it: the query is deterministic, so the same bad
            // element would come back on every retry and free drive would be
            // left with no speed limit for as long as the car was in the area.
            runCatching {
            val e = els.optJSONObject(i) ?: return@runCatching
            val tags = e.optJSONObject("tags") ?: JSONObject()
            when (e.optString("type")) {
                "way" -> {
                    val geom = e.optJSONArray("geometry") ?: return@runCatching
                    if (geom.length() < 2) return@runCatching
                    val pts = Array(geom.length()) { k ->
                        val g = geom.optJSONObject(k)
                        doubleArrayOf(g?.optDouble("lat") ?: Double.NaN,
                                      g?.optDouble("lon") ?: Double.NaN)
                    }
                    if (pts.any { it[0].isNaN() || it[1].isNaN() }) return@runCatching
                    val ow = tags.optString("oneway")
                    val maxspeed = tags.optString("maxspeed")
                    // "BE:rural" names no region, and the region is the answer:
                    // no number here, and the scheme tag lets the window's
                    // vote settle it (SpeedDefaults.forRoad).
                    val deferred = SpeedDefaults.deferredZone(maxspeed)
                    roads.add(
                        RoadWay(
                            id = e.optLong("id"),
                            pts = pts,
                            name = tags.optString("name"),
                            ref = tags.optString("ref").replace(";", " · "),
                            limitKph = if (deferred) 0 else parseMaxspeed(maxspeed),
                            kind = tags.optString("highway"),
                            onewayDir = onewayOf(ow),
                            schemeTag = firstNonBlank(
                                tags.optString("source:maxspeed"),
                                tags.optString("maxspeed:type"),
                                tags.optString("zone:maxspeed"),
                                tags.optString("zone:traffic"),
                                // An implicit maxspeed names the region too.
                                if (maxspeed.contains(':')) maxspeed else ""),
                            lit = when (tags.optString("lit")) {
                                "" -> null
                                "no" -> false
                                else -> true          // yes, sunset-sunrise, 24/7...
                            },
                            lanes = tags.optString("lanes").trim().toIntOrNull() ?: 0,
                            intRef = tags.optString("int_ref")
                        )
                    )
                }
                "node" -> {
                    val lat = e.optDouble("lat", Double.NaN)
                    val lon = e.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) return@runCatching
                    // The same query also brings back level crossings, speed
                    // humps and toll booths, because parseFeatures() wants
                    // them. Without this gate every one of them became a
                    // "speed camera": the app announced a radar at every hump
                    // in every residential street, which is exactly the
                    // complaint that "it says for a speed bump that is not
                    // even on my road". Only enforcement devices are cameras.
                    if (!SpeedCameras.isEnforcement(tags)) return@runCatching
                    cams.add(
                        SpeedCamera(
                            id = e.optLong("id"),
                            lat = lat, lon = lon,
                            alongM = 0.0,                       // no route to be along
                            limitKph = parseMaxspeed(tags.optString("maxspeed")),
                            directionDeg = parseDirection(tags),
                            kind = kindOf(tags)
                        )
                    )
                }
            }
            }
        }
        return roads to cams
    }

    /**
     * OSM `oneway`: 0 two-way, +1 as drawn, -1 against the drawn direction.
     *
     * `reversible` and `alternating` are deliberately treated as two-way --
     * we cannot know which way it is running right now, and admitting the
     * road is better than losing the speed limit on it.
     */
    fun onewayOf(raw: String?): Int = when (raw?.trim()?.lowercase()) {
        "yes", "true", "1" -> 1
        "-1", "reverse" -> -1
        else -> 0
    }

    private fun kindOf(tags: JSONObject): SpeedCamera.Kind = when {
        tags.optString("enforcement") == "average_speed" -> SpeedCamera.Kind.AVERAGE
        tags.optString("highway") == "speed_camera" &&
            tags.optString("speed_camera") == "section" -> SpeedCamera.Kind.AVERAGE
        tags.has("traffic_signals") || tags.optString("enforcement") == "traffic_signals" ->
            SpeedCamera.Kind.TRAFFIC_LIGHT
        else -> SpeedCamera.Kind.FIXED
    }

    private fun parseDirection(tags: JSONObject): Double? {
        val d = tags.optString("direction").ifBlank { tags.optString("camera:direction") }
        if (d.isBlank()) return null
        d.toDoubleOrNull()?.let { return ((it % 360.0) + 360.0) % 360.0 }
        return when (d.lowercase()) {
            "n", "north" -> 0.0; "ne" -> 45.0; "e", "east" -> 90.0; "se" -> 135.0
            "s", "south" -> 180.0; "sw" -> 225.0; "w", "west" -> 270.0; "nw" -> 315.0
            else -> null
        }
    }

    /** `"50"`, `"50 mph"`, `"none"`, `"BE:urban"` and the other things OSM has. */
    fun parseMaxspeed(raw: String?): Int {
        val s = raw?.trim()?.lowercase() ?: return 0
        if (s.isEmpty()) return 0
        if (s == "none") return -1
        if (s == "walk") return 5
        // Implicit country defaults. Belgium, France and Romania cover the
        // driving this app was built for; the rest are close enough to be
        // useful and are only ever a fallback for an untagged road.
        implicit(s)?.let { return it }
        val num = Regex("^(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: return 0
        return if (s.contains("mph")) Math.round(num * 1.609344).toInt() else num
    }

    /**
     * One table for implicit codes and legal defaults, in SpeedDefaults. This
     * was a second copy that had drifted: "BE-VLG:rural" fell through to 90,
     * "BE-BRU:urban" read 50, "AT:motorway" 120, and "BE:zone30" nothing.
     */
    private fun implicit(s: String): Int? = SpeedDefaults.zoneLimit(s)

    // ---- matching ----------------------------------------------------------

    class Match(val road: RoadWay, val crossM: Double, val bearingDeg: Double)

    /** The point on `pts` nearest (lat, lon), as [lat, lon]. */
    fun projectOnto(pts: Array<DoubleArray>, lat: Double, lon: Double): DoubleArray? {
        if (pts.size < 2) return null
        val kx = Geo.EARTH_R * Math.cos(Math.toRadians(lat)) * Math.PI / 180.0
        val ky = Geo.EARTH_R * Math.PI / 180.0
        var bestD = Double.MAX_VALUE
        var out: DoubleArray? = null
        for (i in 0 until pts.size - 1) {
            val ax = (pts[i][1] - lon) * kx
            val ay = (pts[i][0] - lat) * ky
            val bx = (pts[i + 1][1] - lon) * kx
            val by = (pts[i + 1][0] - lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 1e-9) min(1.0, max(0.0, -(ax * dx + ay * dy) / len2)) else 0.0
            val px = ax + t * dx
            val py = ay + t * dy
            val d = px * px + py * py
            if (d < bestD) {
                bestD = d
                out = doubleArrayOf(pts[i][0] + (pts[i + 1][0] - pts[i][0]) * t,
                                    pts[i][1] + (pts[i + 1][1] - pts[i][1]) * t)
            }
        }
        return out
    }

    /**
     * Project onto a way, but only when the answer is worth having.
     *
     * [projectOnto] always returns something: the segment parameter is clamped
     * to 0..1, so once you are past the end of a way it keeps handing back that
     * way's last vertex, however far away you now are. Used to place a marker
     * that is fine while you are on the road and wrong the moment you leave it
     * — OSM splits ways at junctions, so at every turn the marker would clamp
     * to the junction and sit there while the car drove on, then jump.
     *
     * This returns null in both of the cases where the projection is a lie:
     * too far from the way to belong to it, and pinned to an end vertex, which
     * is the "we have run off this way" signal.
     *
     * @return {lat, lon} on the way, or null
     */
    fun snapWithin(
        pts: Array<DoubleArray>, lat: Double, lon: Double, maxM: Double
    ): DoubleArray? {
        val p = projectOnto(pts, lat, lon) ?: return null
        val moved = Geo.haversine(lat, lon, p[0], p[1])
        if (moved > maxM) return null

        // Landing on an end vertex means the projection clamped — we have run
        // off the end of this way, usually at a junction where OSM splits it.
        //
        // That used to be rejected outright, and rejecting it was too strong.
        // Approaching any junction, the projection clamps for a moment, the
        // snap switches off, and the marker springs sideways off the tarmac
        // into the gardens — which is the "arrow is not in the middle of the
        // road" you see in town, where junctions come every few seconds.
        //
        // What the rule was actually protecting against is the marker *sticking*
        // at a junction while the car drives on. That only happens when the
        // clamp has to drag the point a long way to reach the vertex. A clamp
        // that barely moves it is not a lie: you are at the end of the road,
        // and the end of the road is still the road.
        val eps = 0.5
        val atEnd = Geo.haversine(p[0], p[1], pts.first()[0], pts.first()[1]) < eps ||
                    Geo.haversine(p[0], p[1], pts.last()[0], pts.last()[1]) < eps
        if (atEnd && moved > CLAMP_TOLERANCE_M) return null
        return p
    }

    /**
     * Which road the car is on.
     *
     * Nearest-line is not enough on its own: a slip road runs a few metres from
     * the motorway, and a parallel cycleway or service road is nearer still.
     * So the cost combines distance, how far the road's direction is from the
     * car's, and a nudge towards the more important road when two are equally
     * close -- which is what stops a motorway reading as its own hard shoulder.
     */
    fun match(area: Area, lat: Double, lon: Double, headingDeg: Double?): Match? {
        var best: Match? = null
        var bestCost = Double.MAX_VALUE

        // Only the roads whose bounding box comes near us, from the grid.
        for (r in area.roadsNear(lat, lon, MATCH_LIMIT_M + Area.CELL_M)) {
            val n = nearestOn(r.pts, lat, lon) ?: continue
            val cross = n.first
            val brg = n.second
            // The admission test is the *final* one. It used to admit twice
            // this far and then re-check at the end, so a motorway 40 m away
            // could out-score a residential road 34 m away, win, fail the
            // final check, and take the legitimate match down with it.
            if (cross > MATCH_LIMIT_M) continue

            var cost = cross
            // The direction we are *travelling* along this road, which is not
            // the same as the direction the way was drawn in.
            var travel = brg
            // A degenerate segment has no direction; skip the penalty rather
            // than comparing the car's heading against a fictitious due north.
            if (headingDeg != null && brg != null) {
                // A one-way road can only be driven one way; a two-way road
                // matches whichever direction is closer.
                val fwd = Geo.bearingDelta(brg, headingDeg)
                val back = Geo.bearingDelta((brg + 180.0) % 360.0, headingDeg)
                val delta = when (r.onewayDir) {
                    1 -> fwd            // drawn direction is the legal one
                    -1 -> back          // oneway=-1: legal direction is reversed
                    else -> min(fwd, back)
                }
                if (delta > 70.0) continue          // running across us, not along
                // ...and when it is the reverse direction that matched, that is
                // the bearing to report. This used to store the raw OSM way
                // direction whatever the car was doing, so on a two-way street
                // digitised against your direction of travel -- about half of
                // them -- the marker and the whole map faced backwards.
                if (r.onewayDir == -1 || (r.onewayDir == 0 && back < fwd)) {
                    travel = (brg + 180.0) % 360.0
                }
                cost += delta / 180.0 * 60.0
            }
            cost -= r.importance * 1.5

            if (cost < bestCost) {
                bestCost = cost
                best = Match(r, cross, travel ?: headingDeg ?: 0.0)
            }
        }
        return best
    }

    /**
     * @return perpendicular distance, and the road's bearing there — null when
     *         the nearest segment has zero length and so has no direction.
     */
    fun nearestOn(pts: Array<DoubleArray>, lat: Double, lon: Double): Pair<Double, Double?>? {
        if (pts.size < 2) return null
        val kx = Geo.EARTH_R * Math.cos(Math.toRadians(lat)) * Math.PI / 180.0
        val ky = Geo.EARTH_R * Math.PI / 180.0
        var bestD = Double.MAX_VALUE
        var bestI = -1
        var bestDegenerate = false
        for (i in 0 until pts.size - 1) {
            val ax = (pts[i][1] - lon) * kx
            val ay = (pts[i][0] - lat) * ky
            val bx = (pts[i + 1][1] - lon) * kx
            val by = (pts[i + 1][0] - lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val degenerate = len2 <= 1e-9
            val t = if (degenerate) 0.0 else min(1.0, max(0.0, -(ax * dx + ay * dy) / len2))
            val px = ax + t * dx
            val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < bestD) { bestD = d; bestI = i; bestDegenerate = degenerate }
        }
        if (bestI < 0 || bestD.isNaN()) return null
        val brg = if (bestDegenerate) null
                  else Geo.bearing(pts[bestI][0], pts[bestI][1],
                                   pts[bestI + 1][0], pts[bestI + 1][1])
        return bestD to brg
    }

    // ---- transport ---------------------------------------------------------

    private fun post(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("User-Agent", com.mihai.navhud.Net.USER_AGENT)
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            if (code !in 200..299) throw HttpStatus(code, "Overpass HTTP $code: ${text.take(160)}")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
