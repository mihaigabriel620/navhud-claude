package com.mihai.navhud.alerts

import com.mihai.navhud.Geo
import com.mihai.navhud.nav.Route
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Low-emission zones, from OpenStreetMap.
 *
 * Waze puts a warning on the route card when a route goes through one, and it
 * is the single most useful thing on that card in this part of Europe: Brussels,
 * Antwerp and Ghent all run a LEZ, and so do most large French and German
 * cities. There is no barrier and no ticket machine — the enforcement is a
 * number-plate camera and a fine in the post weeks later — so a driver who is
 * not warned before setting off is not warned at all.
 *
 * No routing engine reports these. Mapbox has `exclude=toll` and friends but
 * nothing for emissions zones, so this comes straight from OSM's
 * `boundary=low_emission_zone` areas, which is the tag the wiki documents and
 * which is mapped across Belgium, France, Germany, the Netherlands, Italy and
 * the UK.
 *
 * ## What this deliberately does not do
 *
 * It does not tell you whether *your* car may enter. That depends on the Euro
 * class on your registration document, the fuel, the date, and a different set
 * of rules in every city — and a nav app that answered it confidently and
 * wrongly would be worse than one that stayed quiet. It tells you the zone is
 * there and names it. Whether your car is allowed in is a thing to look up
 * once, not a thing to guess at 90 km/h.
 */
object LowEmissionZones {

    private const val OVERPASS = "https://overpass-api.de/api/interpreter"

    /** How often to test the route against the polygons, metres. */
    const val SAMPLE_SPACING_M = 250.0

    /** Ignore a zone whose outline came back with fewer points than this. */
    const val MIN_RING = 4

    data class Zone(val name: String, val ring: Array<DoubleArray>) {
        /**
         * Name plus first vertex plus vertex count.
         *
         * Name and size alone collapsed two genuinely different polygons that
         * happened to share both -- and unnamed zones all fall back to the same
         * literal "Low-emission zone", so a route entering two of them warned
         * about one. A multipolygon's equal-sized outer rings did the same.
         */
        private val anchorLat = ring.firstOrNull()?.getOrNull(0) ?: 0.0
        private val anchorLon = ring.firstOrNull()?.getOrNull(1) ?: 0.0

        override fun equals(other: Any?) =
            other is Zone && other.name == name && other.ring.size == ring.size &&
                other.anchorLat == anchorLat && other.anchorLon == anchorLon

        override fun hashCode(): Int {
            var h = name.hashCode()
            h = h * 31 + ring.size
            h = h * 31 + anchorLat.hashCode()
            h = h * 31 + anchorLon.hashCode()
            return h
        }
    }

    /**
     * Ask for zones inside the route's bounding box, grown a little.
     *
     * `out geom` rather than `out ids` because a zone is only useful as an
     * outline: knowing that a relation exists somewhere in the box says
     * nothing about whether the route enters it.
     */
    fun buildQuery(route: Route, padDeg: Double = 0.05): String {
        var minLat = 90.0; var maxLat = -90.0
        var minLon = 180.0; var maxLon = -180.0
        for (p in route.pts) {
            if (p[0] < minLat) minLat = p[0]
            if (p[0] > maxLat) maxLat = p[0]
            if (p[1] < minLon) minLon = p[1]
            if (p[1] > maxLon) maxLon = p[1]
        }
        val bbox = "%.5f,%.5f,%.5f,%.5f".format(
            minLat - padDeg, minLon - padDeg, maxLat + padDeg, maxLon + padDeg)
        return """
            [out:json][timeout:25];
            (
              way["boundary"="low_emission_zone"]($bbox);
              relation["boundary"="low_emission_zone"]($bbox);
            );
            out geom;
        """.trimIndent()
    }

    fun parse(json: String): List<Zone> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Zone>()
        for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val name = tags.optString("name").ifBlank {
                tags.optString("ref").ifBlank { "Low-emission zone" }
            }
            when (e.optString("type")) {
                "way" -> ringOf(e)?.let { out.add(Zone(name, it)) }
                "relation" -> {
                    // A multipolygon's outers are separate ways; each becomes
                    // its own polygon. Inners (holes) are rare on a LEZ and
                    // ignoring one can only ever warn slightly too eagerly.
                    val members = e.optJSONArray("members") ?: continue
                    for (k in 0 until members.length()) {
                        val m = members.optJSONObject(k) ?: continue
                        if (m.optString("role") != "outer") continue
                        ringOf(m)?.let { out.add(Zone(name, it)) }
                    }
                }
            }
        }
        return out
    }

    private fun ringOf(e: JSONObject): Array<DoubleArray>? {
        val g = e.optJSONArray("geometry") ?: return null
        if (g.length() < MIN_RING) return null
        val pts = ArrayList<DoubleArray>(g.length())
        for (i in 0 until g.length()) {
            val p = g.optJSONObject(i) ?: continue
            pts.add(doubleArrayOf(p.optDouble("lat"), p.optDouble("lon")))
        }
        return if (pts.size < MIN_RING) null else pts.toTypedArray()
    }

    /**
     * Ray casting, on raw latitude and longitude.
     *
     * Treating degrees as a flat plane is wrong in general and completely fine
     * here: a low-emission zone is a few kilometres across, and the only thing
     * that matters is which side of an edge a point falls on.
     */
    fun contains(ring: Array<DoubleArray>, lat: Double, lon: Double): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val yi = ring[i][0]; val xi = ring[i][1]
            val yj = ring[j][0]; val xj = ring[j][1]
            if ((yi > lat) != (yj > lat)) {
                val x = (xj - xi) * (lat - yi) / (yj - yi) + xi
                if (lon < x) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** The zones this route drives into, in the order it meets them. */
    fun crossed(route: Route, zones: List<Zone>): List<Zone> {
        if (zones.isEmpty() || route.pts.size < 2) return emptyList()
        val hit = LinkedHashSet<Zone>()
        var d = 0.0
        while (d <= route.totalDistanceM) {
            val p = Geo.pointAlong(route.pts, route.cum, d)
            for (z in zones) if (z !in hit && contains(z.ring, p[0], p[1])) hit.add(z)
            d += SAMPLE_SPACING_M
        }
        // The destination itself matters most: parking inside a zone is the
        // case where the fine is certain rather than likely.
        val last = route.pts.last()
        for (z in zones) if (z !in hit && contains(z.ring, last[0], last[1])) hit.add(z)
        return hit.toList()
    }

    /** Blocking network call; run it off the main thread. */
    fun fetch(route: Route): List<Zone> {
        val body = post(OVERPASS,
            "data=" + java.net.URLEncoder.encode(buildQuery(route), "UTF-8"))
        return parse(body)
    }

    private fun post(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
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
