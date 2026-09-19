package com.mihai.navhud.nav

import com.mihai.navhud.Geo
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One search hit. `precise` means it resolved to an actual house number. */
data class GeoResult(
    val label: String,
    val lat: Double,
    val lon: Double,
    val source: String,
    val precise: Boolean
)

/**
 * Address search that actually finds Belgian addresses.
 *
 * "Avenue de la Couronne 329A 1050 Ixelles" is a perfectly normal way to write
 * an address in Belgium, and a single fuzzy geocoder routinely fumbles it: the
 * lettered house number, the postcode-before-commune order, the commune not
 * being the city name. The fix is not a better single engine, it is structure
 * plus redundancy:
 *
 *  1. Parse the query. If it contains a house number, we know the street, the
 *     number, the postcode and the commune separately.
 *  2. Ask Nominatim (OpenStreetMap's own geocoder) with those fields as a
 *     STRUCTURED query -- exact-address lookup is what it is best at.
 *  3. Ask Photon (komoot's OSM search) -- typo-tolerant, excellent for POIs
 *     and half-remembered names. No key needed for either.
 *  4. Optionally ask Google's Geocoding API when the user has pasted a key in
 *     Setup. Google's terms allow coordinates (lat/lon/place_id) to be used
 *     with a non-Google map -- that carve-out is exactly what a geocoder-only
 *     integration needs -- and its Belgian address coverage is first rate.
 *  5. Mapbox last, since the token is already there for routing.
 *
 * Results are merged, de-duplicated by proximity, and exact-address hits are
 * ranked first. Every leg is fail-soft: a dead service just contributes
 * nothing.
 */
object Geocoders {

    private const val PHOTON = "https://photon.komoot.io/api/"
    private const val NOMINATIM = "https://nominatim.openstreetmap.org"
    private val USER_AGENT = com.mihai.navhud.Net.USER_AGENT

    /** Results closer than this are the same place found twice. */
    private const val DEDUPE_M = 40.0

    private const val ENOUGH = 5
    private const val MAX_RESULTS = 10

    // -----------------------------------------------------------------------
    //  the address parser
    // -----------------------------------------------------------------------

    data class ParsedAddress(
        val street: String,
        val houseNumber: String,
        val postcode: String?,
        val city: String?
    )

    private val NUM = Regex("^\\d{1,4}[a-zA-Z]?$")     // 329, 329A
    private val PC = Regex("^\\d{4,6}$")               // 1050, 10115, 010011

    /**
     * Splits "avenue de la couronne 329a 1050 ixelles" into street, number,
     * postcode and commune. Handles number-first (French habit), number-last
     * (Belgian habit), postcode before or after the commune, and commas.
     * Returns null when there is no house number -- then it is not an address
     * lookup but a name search, and the fuzzy engines handle it better.
     */
    fun parseAddress(raw: String): ParsedAddress? {
        val tokens = raw.replace(",", " ").trim().split(Regex("\\s+"))
        if (tokens.size < 2) return null

        // Postcode: the last 4-6 digit token that either has words after it
        // (the commune) or sits at the very end behind a real house number.
        var pcIdx = -1
        for (i in tokens.indices.reversed()) {
            if (!PC.matches(tokens[i])) continue
            val wordsAfter = (i + 1 until tokens.size).any { !NUM.matches(tokens[it]) }
            val numberBefore = (0 until i).any { NUM.matches(tokens[it]) }
            if (wordsAfter || (i == tokens.size - 1 && numberBefore)) { pcIdx = i; break }
        }

        // House number: the last short number before the postcode.
        var nrIdx = -1
        val limit = if (pcIdx >= 0) pcIdx else tokens.size
        for (i in (0 until limit).reversed()) {
            if (NUM.matches(tokens[i])) { nrIdx = i; break }
        }
        if (nrIdx < 0) return null

        val street: String
        var city: String? = if (pcIdx >= 0)
            tokens.drop(pcIdx + 1).joinToString(" ").ifBlank { null } else null

        if (nrIdx == 0) {
            // "329a avenue de la couronne ..." -- number first.
            street = tokens.subList(1, limit).joinToString(" ")
        } else {
            street = tokens.subList(0, nrIdx).joinToString(" ")
            // words between the number and the postcode, or after the number
            // when there is no postcode, are the commune
            val mid = if (pcIdx > nrIdx + 1) tokens.subList(nrIdx + 1, pcIdx).joinToString(" ")
                      else if (pcIdx < 0 && nrIdx < tokens.size - 1)
                          tokens.drop(nrIdx + 1).joinToString(" ")
                      else ""
            if (mid.isNotBlank()) {
                city = listOfNotNull(mid.ifBlank { null }, city).joinToString(" ")
            }
        }
        if (street.isBlank()) return null

        return ParsedAddress(
            street = street.trim(),
            houseNumber = tokens[nrIdx].uppercase(),
            postcode = if (pcIdx >= 0) tokens[pcIdx] else null,
            city = city
        )
    }

    // -----------------------------------------------------------------------
    //  the engines
    // -----------------------------------------------------------------------

    /**
     * @param languageCode "fr"/"en"/...; labels come back localised
     * @param mapboxToken null skips the Mapbox leg
     * @param googleKey null skips the Google leg
     * @param regionHint ISO-2 country, biases Google ("be")
     */
    fun search(
        query: String,
        near: LatLon?,
        languageCode: String,
        mapboxToken: String?,
        googleKey: String?,
        regionHint: String?
    ): List<GeoResult> {
        val parsed = parseAddress(query)
        val out = ArrayList<GeoResult>()

        if (parsed != null) {
            out += runSafe { nominatimStructured(parsed, languageCode) }
        }
        if (!googleKey.isNullOrBlank()) {
            out += runSafe { googleGeocode(query, googleKey, languageCode, regionHint) }
        }
        val photonQuery = if (parsed != null)
            listOfNotNull(parsed.street, parsed.houseNumber, parsed.city).joinToString(" ")
        else query
        if (out.size < ENOUGH) out += runSafe { photonSearch(photonQuery, near, languageCode) }
        if (out.size < ENOUGH) out += runSafe { nominatimFreeform(query, languageCode) }
        if (out.size < ENOUGH && !mapboxToken.isNullOrBlank()) {
            out += runSafe { mapboxGeocode(query, near, mapboxToken, languageCode) }
        }

        return dedupe(out).sortedByDescending { it.precise }.take(MAX_RESULTS)
    }

    /** ISO-2 country at a point via Nominatim reverse. Keyless. */
    fun countryCodeAt(p: LatLon): String? {
        val url = "$NOMINATIM/reverse?lat=${p.lat}&lon=${p.lon}&zoom=3&format=jsonv2"
        val body = get(url) ?: return null
        return runCatching {
            JSONObject(body).optJSONObject("address")
                ?.optString("country_code")?.uppercase()?.takeIf { it.length == 2 }
        }.getOrNull()
    }

    private inline fun runSafe(block: () -> List<GeoResult>): List<GeoResult> =
        runCatching(block).getOrDefault(emptyList())

    // ---- Nominatim ---------------------------------------------------------

    private fun nominatimStructured(pa: ParsedAddress, lang: String): List<GeoResult> {
        val url = buildString {
            append(NOMINATIM).append("/search?format=jsonv2&limit=5")
            append("&street=").append(enc("${pa.houseNumber} ${pa.street}"))
            pa.postcode?.let { append("&postalcode=").append(enc(it)) }
            pa.city?.let { append("&city=").append(enc(it)) }
            append("&accept-language=").append(enc(lang.ifBlank { "en" }))
        }
        return parseNominatim(get(url) ?: return emptyList())
    }

    private fun nominatimFreeform(q: String, lang: String): List<GeoResult> {
        val url = "$NOMINATIM/search?format=jsonv2&limit=5&q=${enc(q)}" +
            "&accept-language=${enc(lang.ifBlank { "en" })}"
        return parseNominatim(get(url) ?: return emptyList())
    }

    /** jsonv2 quirk worth knowing: lat/lon arrive as strings. */
    fun parseNominatim(body: String): List<GeoResult> {
        val arr = JSONArray(body)
        val out = ArrayList<GeoResult>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optString("lat").toDoubleOrNull() ?: continue
            val lon = o.optString("lon").toDoubleOrNull() ?: continue
            val display = o.optString("display_name")
            if (display.isBlank()) continue
            val label = display.split(",").map { it.trim() }
                .filter { it.isNotBlank() }.take(4).joinToString(", ")
            val precise = o.optString("addresstype") in setOf("house", "building") ||
                label.substringBefore(",").any { it.isDigit() }
            out.add(GeoResult(label, lat, lon, "osm", precise))
        }
        return out
    }

    // ---- Photon ------------------------------------------------------------

    private fun photonSearch(q: String, near: LatLon?, lang: String): List<GeoResult> {
        val url = buildString {
            append(PHOTON).append("?q=").append(enc(q)).append("&limit=6")
            if (near != null) append("&lat=${near.lat}&lon=${near.lon}")
            if (lang.lowercase().take(2) in setOf("en", "de", "fr")) {
                append("&lang=").append(lang.lowercase().take(2))
            }
        }
        return parsePhoton(get(url) ?: return emptyList())
    }

    fun parsePhoton(body: String): List<GeoResult> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<GeoResult>(features.length())
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val p = f.optJSONObject("properties") ?: JSONObject()
            val hn = p.optString("housenumber")
            val street = p.optString("street")
            val name = p.optString("name")
            val line1 = when {
                street.isNotBlank() && hn.isNotBlank() -> "$street $hn"
                name.isNotBlank() -> name
                street.isNotBlank() -> street
                else -> continue
            }
            val line2 = listOf(p.optString("postcode"), p.optString("city"))
                .filter { it.isNotBlank() }.joinToString(" ")
                .ifBlank { p.optString("country") }
            val label = listOf(line1, line2).filter { it.isNotBlank() }.joinToString(", ")
            out.add(GeoResult(label, coords.getDouble(1), coords.getDouble(0),
                              "photon", hn.isNotBlank()))
        }
        return out
    }

    // ---- Google (optional) -------------------------------------------------

    private fun googleGeocode(q: String, key: String, lang: String, region: String?): List<GeoResult> {
        val url = buildString {
            append("https://maps.googleapis.com/maps/api/geocode/json?address=").append(enc(q))
            append("&key=").append(key)
            if (lang.isNotBlank()) append("&language=").append(enc(lang))
            region?.takeIf { it.length == 2 }?.let { append("&region=").append(it.lowercase()) }
        }
        return parseGoogle(get(url) ?: return emptyList())
    }

    fun parseGoogle(body: String): List<GeoResult> {
        val root = JSONObject(body)
        if (root.optString("status") != "OK") return emptyList()
        val results = root.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<GeoResult>(results.length())
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val loc = r.optJSONObject("geometry")?.optJSONObject("location") ?: continue
            val label = r.optString("formatted_address")
            if (label.isBlank()) continue
            var precise = false
            r.optJSONArray("types")?.let { t ->
                for (k in 0 until t.length()) {
                    if (t.optString(k) in setOf("street_address", "premise", "subpremise")) {
                        precise = true
                    }
                }
            }
            out.add(GeoResult(label, loc.optDouble("lat"), loc.optDouble("lng"),
                              "google", precise))
        }
        return out
    }

    // ---- Mapbox (last, the token is there anyway) --------------------------

    private fun mapboxGeocode(q: String, near: LatLon?, token: String, lang: String): List<GeoResult> {
        val url = buildString {
            append("https://api.mapbox.com/geocoding/v5/mapbox.places/")
            append(encPath(q)).append(".json?limit=6&types=address,poi,place,locality")
            if (near != null) append("&proximity=${near.lon},${near.lat}")
            if (lang.isNotBlank()) append("&language=").append(enc(lang))
            append("&access_token=").append(token)
        }
        val body = get(url) ?: return emptyList()
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<GeoResult>(features.length())
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val c = f.optJSONArray("center") ?: continue
            val label = f.optString("place_name", f.optString("text"))
            if (label.isBlank()) continue
            var precise = false
            f.optJSONArray("place_type")?.let { t ->
                for (k in 0 until t.length()) if (t.optString(k) == "address") precise = true
            }
            out.add(GeoResult(label, c.getDouble(1), c.getDouble(0), "mapbox", precise))
        }
        return out
    }

    // -----------------------------------------------------------------------

    fun dedupe(results: List<GeoResult>): List<GeoResult> {
        val kept = ArrayList<GeoResult>(results.size)
        for (r in results) {
            val dup = kept.any {
                it.label.equals(r.label, ignoreCase = true) ||
                    Geo.haversine(it.lat, it.lon, r.lat, r.lon) < DEDUPE_M
            }
            if (!dup) kept.add(r)
        }
        return kept
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

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


    private fun get(url: String): String? {
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 7000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            try {
                if (conn.responseCode !in 200..299) null
                else conn.inputStream.bufferedReader().use(BufferedReader::readText)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
