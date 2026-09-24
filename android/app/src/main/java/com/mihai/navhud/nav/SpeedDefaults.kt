package com.mihai.navhud.nav

/**
 * The limit that applies when nobody has signed it and nobody has mapped it.
 *
 * ---------------------------------------------------------------------------
 *  Why this exists
 * ---------------------------------------------------------------------------
 *
 * Measured against OpenStreetMap's Belgian extract on 2026-09-07, by road
 * length rather than by way count:
 *
 *      motorway      99.5 %  have a maxspeed tag
 *      trunk         97.2 %
 *      primary       91.1 %
 *      secondary     74.2 %
 *      tertiary      55.9 %
 *      residential   48.6 %
 *      unclassified  28.1 %
 *      ------------------------------------------
 *      all drivable  50.8 %
 *
 * On a route that gap is already covered: the Mapbox Directions call asks for
 * `annotations=maxspeed` and gets a value per segment. Free driving has no
 * route, reads OSM directly, and therefore shows nothing on half the roads in
 * the country — which on residential streets is exactly where the limit is
 * least obvious and most enforced.
 *
 * A legal default is not a guess. Every country in Europe defines a limit that
 * applies in the absence of a sign, and a road with no sign *is* at that limit.
 * The uncertainty is not in the number, it is in deciding which of a handful of
 * numbers applies here — built-up or not, and which region.
 *
 * ---------------------------------------------------------------------------
 *  What is honest and what is not
 * ---------------------------------------------------------------------------
 *
 * Three cases where a default cannot be trusted, and this file says so rather
 * than pretending:
 *
 *  * **France, rural.** The national limit dropped 90 -> 80 on 1 July 2018
 *    (décret of 15 June 2018), and then the LOM of 24 December 2019 let
 *    departments put it back. By an Interior Ministry count of September 2021,
 *    around 33,400 km across 37 departments had reverted to 90. Which applies
 *    is a department-level fact not derivable from a country code, so this
 *    returns the lower of the two and flags it. Showing 80 where the truth is
 *    90 makes the display pessimistic; showing 90 where the truth is 80 would
 *    tell somebody they are legal when they are being fined.
 *
 *  * **Netherlands, motorway.** The statutory default is 130, but since
 *    16 March 2020 most of the network is signed 100 between 06:00 and 19:00.
 *    That is a posted limit, so it belongs to the map — but a library that just
 *    returns the statutory default is wrong for thirteen hours a day.
 *    Implemented here with the clock, because the clock is free.
 *
 *  * **Belgium, rural.** 70 in Flanders since 1 January 2017, still 90 in
 *    Wallonia. Same country, same-looking road, twenty km/h apart. Getting this
 *    from `country == "BE"` alone is impossible; the region has to be known.
 *
 * Values come from the OSM wiki's machine-readable default-speeds table, which
 * is the data behind `osm-legal-default-speeds`, cross-checked against primary
 * law. The library itself is not used: it is a Kotlin Multiplatform dependency
 * whose data is CC-BY-SA, and this is one small table that needs auditing
 * against Belgian law rather than importing on trust.
 *
 * https://wiki.openstreetmap.org/wiki/Default_speed_limits
 */
object SpeedDefaults {

    /** Nothing sensible could be derived. */
    const val UNKNOWN = 0

    /** No general limit — a German autobahn. Matches HudFrame's convention. */
    const val DERESTRICTED = -1

    /**
     * How much the caller should trust the number.
     *
     * `POSTED` never comes out of this file; it is here so the whole speed
     * limit pipeline can be described with one enum.
     */
    enum class Confidence {
        /** A sign, or a mapped `maxspeed`. */
        POSTED,

        /** A legal default, and the inputs that pick it were unambiguous. */
        DERIVED,

        /** A legal default resting on a guess about built-up or region. */
        WEAK
    }

    data class Result(val kph: Int, val confidence: Confidence) {
        val known: Boolean get() = kph != UNKNOWN
    }

    private val NOTHING = Result(UNKNOWN, Confidence.WEAK)

    /**
     * The legal default for a road the map gave no `maxspeed`, or null.
     *
     * The one entry point both drive modes use. This logic used to live inside
     * FreeTracker, so it existed only while there was no destination: the
     * moment a route was set the limit went blank again on the same half of
     * the country. Nothing here is free-drive-specific, and the two paths
     * showing different numbers for the same road is a bug the driver cannot
     * even see — so there is one copy.
     *
     * Three outcomes, and the third is the reason this returns `Int?`:
     *
     *  * `null` — nothing could be derived. Show no sign.
     *  * [DERESTRICTED] (-1) — a German autobahn. Show the no-limit sign.
     *  * a positive number — kph.
     *
     * "No answer" and "the answer is no limit" are different facts, and only
     * one of them is uncertain. Folding them together — returning [UNKNOWN]
     * for both, or letting the caller test `> 0` — throws away the single road
     * class whose default is never in doubt: every German motorway would read
     * as missing data.
     *
     * Where the region comes from, first answer winning: the road's own scheme
     * tag (authoritative, and the only thing that names a region), then the
     * window's majority vote, then the country code — each a step less certain
     * than the last, and [implied] marks the weakest of them WEAK.
     *
     * @param localHour 0-23 local time; only the Dutch daytime motorway limit
     *                  reads it.
     */
    fun forRoad(road: RoadWay, area: Area?, country: String?, localHour: Int): Int? {
        val (tagRegion, tagUrban) = fromSchemeTag(road.schemeTag)
        // The window's vote only counts inside the country we are in: near a
        // border it can be won by the neighbour's roads, and Flanders' 70
        // does not apply to a Dutch lane.
        val cc = (tagRegion ?: country)?.uppercase()?.substringBefore('-')
        val vote = area?.regionCode?.takeIf { cc == null || it.startsWith("$cc-") }
        // A bare country tag ("BE:rural") names less than the window does.
        val region = tagRegion?.takeIf { it.contains('-') || vote == null }
            ?: vote ?: tagRegion ?: country
            // Nothing names a country: there is no law to fall back on, and
            // the only honest answer is silence.
            ?: return null

        // `lit` last, because it is the weakest of the three: street lighting
        // is a strong hint at a built-up area and not a legal definition of one.
        val urban = tagUrban ?: impliedUrban(road.kind) ?: road.lit

        // Romania: a national road carrying a European number is 100, like an
        // expressway (OUG 195/2002 art. 49), and OSM maps many as `primary`.
        val kind = if (region.uppercase().startsWith("RO") && road.intRef.contains(E_ROAD)) "trunk"
                   else road.kind
        // Physically separated, two lanes each way: the class several
        // countries give its own rural limit (BE 120, FR 110, DE none, PL 100).
        // Needs `lanes` because in Belgium one lane each way stays at 70/90.
        val dual = road.onewayDir != 0 && road.lanes >= 2 &&
            (kind == "trunk" || kind == "primary")

        val d = implied(region, kind, urban, dual, localHour)
        return if (d.known) d.kph else null
    }

    private val E_ROAD = Regex("\\bE ?\\d")

    /** A matched road's limit, and whether it came from the law rather than a sign. */
    class RoadLimit(val kph: Int, val derived: Boolean)

    /**
     * The limit on a road the matcher found: its own `maxspeed`, else the
     * legal default. Null when neither says anything.
     *
     * The one derivation both trackers use. Free drive calls it on the road
     * it matched; a route calls it through [limitAt] wherever the router has
     * no limit for the segment -- which used to go blank on exactly the
     * untagged half of the network this file exists for.
     */
    fun limitOf(road: RoadWay, area: Area?, country: String?, localHour: Int): RoadLimit? {
        if (road.limitKph != 0) return RoadLimit(road.limitKph, derived = false)
        return forRoad(road, area, country, localHour)?.let { RoadLimit(it, derived = true) }
    }

    /**
     * [limitOf] for a position rather than a road: 0 when there is no road
     * data here or no answer on the road that is. -1 is derestricted.
     */
    fun limitAt(
        area: Area?, lat: Double, lon: Double, headingDeg: Double?,
        country: String?, localHour: Int
    ): Int {
        val a = area ?: return UNKNOWN
        val m = AreaRoads.match(a, lat, lon, headingDeg) ?: return UNKNOWN
        return limitOf(m.road, a, country, localHour)?.kph ?: UNKNOWN
    }

    /**
     * The km/h an implicit OSM `maxspeed` value stands for: "BE-VLG:rural",
     * "RO:urban", "DE:motorway", "BE:zone30"... Null when the code is not one
     * we know, or when it cannot be answered without knowing more -- a bare
     * "BE:rural" is 70 in Flanders and 90 in Wallonia, so the road keeps no
     * number and [forRoad] settles it with the window's region.
     * https://wiki.openstreetmap.org/wiki/Key:maxspeed#Implicit_maxspeed_values
     */
    fun zoneLimit(code: String): Int? {
        val s = code.trim().uppercase()
        if (!s.contains(':')) return null
        val region = s.substringBefore(':')
        val zone = s.substringAfter(':').lowercase()
        val country = region.substringBefore('-')
        Regex("^zone:?(\\d+)$").find(zone)?.let { return it.groupValues[1].toInt() }
        val law = LAWS[region] ?: LAWS[country] ?: return null
        return when (zone) {
            "living_street" -> law.living.takeIf { it != UNKNOWN }
            "bicycle_road", "cyclestreet", "bicycle_street" -> 30
            "motorway" -> if (country == "NL") null else law.motorway   // clock-dependent
            "urban_motorway", "motorway_urban" -> law.urbanMotorway
            "urban_trunk" -> if (country == "CZ") 80 else law.urban
            "urban" -> if (region == "BE") null else law.urban
            "rural" -> if (region == "BE") null else law.rural
            "trunk" -> law.trunk
            else -> null
        }
    }

    /** True for an implicit code [zoneLimit] leaves for the window to settle. */
    fun deferredZone(code: String): Boolean {
        val s = code.trim().uppercase()
        return s.startsWith("BE:") && s.substringAfter(':').lowercase() in setOf("urban", "rural")
    }

    /**
     * @param region     ISO 3166-1 alpha-2, or alpha-2 plus subdivision for
     *                   the countries where it matters: "BE-VLG", "BE-WAL",
     *                   "BE-BRU". A bare "BE" is treated as unknown-region and
     *                   returns the *lower* of the regional values, weakly.
     * @param highway    the OSM `highway` value.
     * @param urban      inside a built-up area, or null when not known.
     * @param dualCarriageway  a physically separated dual carriageway.
     * @param localHour  0-23 local time, for the Dutch daytime motorway limit.
     */
    fun implied(
        region: String?,
        highway: String,
        urban: Boolean?,
        dualCarriageway: Boolean = false,
        localHour: Int = 12
    ): Result {
        val r = (region ?: return NOTHING).uppercase()
        val country = r.substringBefore('-')

        // Road classes whose limit is a property of the class, not the country
        // — these are signed by their own existence.
        when (highway) {
            "living_street" -> return Result(livingStreet(country), Confidence.DERIVED)
            "service", "track" -> return NOTHING   // yards and farm tracks: no useful default
        }

        val isMotorway = highway == "motorway" || highway == "motorway_link"
        if (isMotorway) return motorway(country, localHour)

        // Everything else needs the built-up answer, and that is the part that
        // is genuinely uncertain. A road class can imply it: `residential` is
        // built-up by definition, and a trunk road never is.
        val builtUp = urban ?: impliedUrban(highway) ?: return NOTHING
        val weak = urban == null

        val kph = if (builtUp) urbanLimit(r) else ruralLimit(r, dualCarriageway)
        if (kph == UNKNOWN) return NOTHING

        // Two independent reasons to distrust the answer: we guessed built-up,
        // or the country has a regional split and we do not know the region.
        val ambiguousRegion = country == "BE" && !r.contains('-')
        val ambiguousLaw = country == "FR" && !builtUp      // 80 vs 90, see above
        val conf = if (weak || ambiguousRegion || ambiguousLaw)
            Confidence.WEAK else Confidence.DERIVED
        return Result(kph, conf)
    }

    // ---- the table ---------------------------------------------------------

    private fun livingStreet(country: String) = when (country) {
        "NL" -> 15          // erf
        "LU" -> 20
        else -> 20          // BE, FR, DE
    }

    private fun motorway(country: String, localHour: Int): Result = when (country) {
        "BE" -> Result(120, Confidence.DERIVED)
        "FR" -> Result(130, Confidence.DERIVED)
        "LU" -> Result(130, Confidence.DERIVED)
        "DE" -> Result(DERESTRICTED, Confidence.DERIVED)
        "NL" ->
            // Signed 100 between 06:00 and 19:00 on most of the network since
            // 16 March 2020; the statutory 130 applies outside those hours and
            // is deliberately *not* signed, so the map cannot supply it either.
            // A few stretches went back to 130 by day in 2025, so the daytime
            // answer is the common case rather than a certainty.
            if (localHour in 6..18) Result(100, Confidence.WEAK)
            else Result(130, Confidence.WEAK)
        else -> NOTHING
    }

    private fun urbanLimit(region: String): Int = when (region) {
        "BE-BRU" -> 30      // "Ville 30", 1 January 2021
        "BE-VLG", "BE-WAL" -> 50
        "BE" -> 30          // region unknown: the lower of the three
        else -> when (region.substringBefore('-')) {
            "FR", "NL", "DE", "LU" -> 50
            else -> UNKNOWN
        }
    }

    private fun ruralLimit(region: String, dual: Boolean): Int = when (region) {
        "BE-VLG", "BE-BRU" -> if (dual) 120 else 70
        "BE-WAL" -> if (dual) 120 else 90
        // Region unknown. 70 is Flanders' value and the lower of the two, so
        // it is the one that cannot tell a Walloon driver they are legal when
        // they are not.
        "BE" -> if (dual) 120 else 70
        else -> when (region.substringBefore('-')) {
            "FR" -> if (dual) 110 else 80
            "NL" -> 80
            "DE" -> if (dual) DERESTRICTED else 100
            "LU" -> 90
            else -> UNKNOWN
        }
    }

    /**
     * What the road class alone says about being built-up.
     *
     * Null where it says nothing, which is most of the interesting cases.
     * `unclassified` is the worst of them: 21,467 km of it in Belgium has no
     * maxspeed, and it is used for both a village high street and a farm lane
     * between two fields.
     */
    fun impliedUrban(highway: String): Boolean? = when (highway) {
        "residential", "living_street", "pedestrian" -> true
        "motorway", "motorway_link", "trunk", "trunk_link" -> false
        else -> null        // primary, secondary, tertiary, unclassified
    }

    /**
     * Read the built-up answer straight out of OSM's own scheme tags.
     *
     * `source:maxspeed=BE-VLG:urban`, `zone:traffic=BE-VLG:rural`,
     * `maxspeed:type=BE:zone30` and friends. Only about 1.2 % of the untagged
     * Belgian road length carries any of these, so this is not a solution to
     * the coverage gap — but where it is present it is authoritative, and it
     * carries the region too, which nothing else on the way does.
     *
     * @return region ("BE-VLG") to urban flag, either of which may be null.
     */
    fun fromSchemeTag(value: String?): Pair<String?, Boolean?> {
        val v = value?.trim()?.lowercase() ?: return null to null
        if (v.isEmpty()) return null to null

        val urban = when {
            v.contains("urban") -> true
            v.contains("rural") -> false
            v.contains("zone") -> true      // BE:zone30, DE:zone30 -- always built-up
            v.contains("motorway") -> false
            else -> null
        }

        // "BE-VLG:urban" -> "BE-VLG";  "BE:zone30" -> "BE";  "DE:urban" -> "DE"
        val head = v.substringBefore(':').uppercase()
        val region = if (head.matches(Regex("[A-Z]{2}(-[A-Z]{2,3})?"))) head else null
        return region to urban
    }
}
