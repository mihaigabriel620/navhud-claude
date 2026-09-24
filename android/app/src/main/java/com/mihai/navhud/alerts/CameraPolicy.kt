package com.mihai.navhud.alerts

/**
 * What the app is allowed to tell you about speed cameras, which is not the
 * same question in every country you drive through.
 *
 *  - EXACT — announce the camera and count down the distance. Belgium and most
 *    of Europe: Waze and Coyote do exactly this. Belgian law bans *detectors*
 *    (art. 62bis of the traffic code) but not databases of known positions.
 *  - ZONE  — France. Since the 2011 agreement between the state and the
 *    navigation industry, apps may only show a "zone de danger" covering a
 *    stretch of road, never a precise camera position.
 *  - OFF   — Germany (§23 StVO bans using a camera-warning device while
 *    driving) and Switzerland (banned outright). No alerts at all.
 *
 * Sources are listed in docs/CAMERAS.md and next to each entry below. This is
 * a best effort from public sources, not legal advice, which is why
 * UNVERIFIED countries default to the conservative ZONE rather than to EXACT.
 * The commercial apps draw the same lines: TomTom switches warnings off while
 * driving in Germany, shows danger zones in France and offers nothing in
 * Switzerland, Cyprus and North Macedonia; Google Maps hides them in Germany.
 */
enum class CameraPolicy { EXACT, ZONE, OFF }

object CountryRules {

    // Sources (checked 2026-09):
    //  ADAC  = adac.de/verkehr/recht/verkehrsvorschriften-deutschland/radarwarner/
    //          (per-country table: detector ban vs "POI-Funktion erlaubt")
    //  MA    = Moniteur Automobile, "Aides à la conduite et avertisseurs radar :
    //          les règles pays par pays", 14 Apr 2026
    //  TT    = TomTom support, "TomTom Speed Camera service and the law"
    //  VIAS  = VIAS institute report 2023-R-16 "Systèmes d'avertissement de radars"
    /** Countries whose rules were checked against a named source. */
    private val verified = mapOf(
        // Position databases are lawful; what is banned is detecting or jamming.
        "BE" to CameraPolicy.EXACT,   // art. 62bis bans detectors only (VIAS, MA, ADAC)
        "NL" to CameraPolicy.EXACT,   // ADAC: POI function and apps allowed; MA
        "LU" to CameraPolicy.EXACT,   // ADAC: POI function allowed; MA
        "AT" to CameraPolicy.EXACT,   // ADAC: POI "als Ankündigung" allowed
        "HU" to CameraPolicy.EXACT,   // KRESZ 3.§(3); ADAC: only jammers banned
        "RO" to CameraPolicy.EXACT,   // ADAC: only jammers banned; app warnings allowed
        "HR" to CameraPolicy.EXACT,   // ADAC: detectors confiscated, apps not banned
        "IT" to CameraPolicy.EXACT,   // MA: legal for apps (detectors banned)
        "ES" to CameraPolicy.EXACT,   // ADAC: POI allowed; detectors banned (art. 13.6)
        "PL" to CameraPolicy.EXACT,   // MA: legal for apps
        // Danger zones only.
        "FR" to CameraPolicy.ZONE,    // art. R413-15, decree of 2012 (MA, TT)
        // No camera warnings at all.
        "DE" to CameraPolicy.OFF,     // §23 Abs. 1c StVO (ADAC, TT; Google hides them)
        "CH" to CameraPolicy.OFF,     // art. 98a SVG, even GPS POI (ADAC, TT)
        "LI" to CameraPolicy.OFF,     // follows the Swiss rule (MA)
        "CY" to CameraPolicy.OFF,     // TT: not permitted by local law
        "MK" to CameraPolicy.OFF,     // TT: not permitted by local law
        "TR" to CameraPolicy.OFF      // MA: forbidden, even carried
    )
    // Deliberately not listed, so they get the cautious ZONE: Czechia (ADAC says
    // POI allowed, other sources a total ban), Slovakia and Slovenia (detectors
    // banned, app status unclear), and everything not checked at all.

    /**
     * Everywhere else. ZONE still warns you that enforcement is likely on this
     * stretch, without publishing a position -- the behaviour that is lawful in
     * the strictest country we know of that still permits anything.
     */
    val DEFAULT = CameraPolicy.ZONE

    fun policyFor(iso2: String?): CameraPolicy {
        val c = normalise(iso2) ?: return DEFAULT
        return verified[c] ?: DEFAULT
    }

    fun isVerified(iso2: String?) = verified.containsKey(normalise(iso2))

    /**
     * Whatever a lookup handed back, as an ISO 3166-1 alpha-2 code, or null.
     *
     * Not every source answers in ISO-2. Mapbox falls back to the country's
     * *name* in the request language when a feature has no short code, and a
     * "Allemagne" that misses the table silently became the ZONE default --
     * danger-zone warnings inside Germany, where any warning is an offence.
     * An OSM region code ("BE-VLG", from [com.mihai.navhud.nav.Area.regionCode],
     * which works offline) is cut to its country.
     */
    fun normalise(raw: String?): String? {
        val s = raw?.trim()?.uppercase() ?: return null
        if (s.length >= 2 && s[0].isLetter() && s[1].isLetter() &&
            (s.length == 2 || s[2] == '-' || s[2] == ':')) return s.substring(0, 2)
        return names[s]
    }

    /**
     * Alpha-3 codes and country names as geocoders return them (EN, FR,
     * native), for the listed countries: the ones where a miss matters.
     */
    private val names: Map<String, String> = buildMap {
        fun add(iso: String, vararg n: String) = n.forEach { put(it.uppercase(), iso) }
        add("BE", "BEL", "Belgium", "Belgique", "België", "Belgien")
        add("NL", "NLD", "Netherlands", "The Netherlands", "Pays-Bas", "Nederland")
        add("LU", "LUX", "Luxembourg", "Luxemburg", "Lëtzebuerg")
        add("AT", "AUT", "Austria", "Autriche", "Österreich")
        add("HU", "HUN", "Hungary", "Hongrie", "Magyarország")
        add("RO", "ROU", "Romania", "Roumanie", "România")
        add("HR", "HRV", "Croatia", "Croatie", "Hrvatska")
        add("IT", "ITA", "Italy", "Italie", "Italia")
        add("ES", "ESP", "Spain", "Espagne", "España")
        add("PL", "POL", "Poland", "Pologne", "Polska")
        add("FR", "FRA", "France")
        add("DE", "DEU", "Germany", "Allemagne", "Deutschland")
        add("CH", "CHE", "Switzerland", "Suisse", "Schweiz", "Svizzera")
        add("LI", "LIE", "Liechtenstein")
        add("CY", "CYP", "Cyprus", "Chypre", "Κύπρος")
        add("MK", "MKD", "North Macedonia", "Macédoine du Nord", "Северна Македонија")
        add("TR", "TUR", "Turkey", "Türkiye", "Turquie")
    }

    /** Human-readable explanation for the settings screen and the map chip. */
    fun explain(iso2: String?): String {
        val c = normalise(iso2)
        return when {
            c == null -> "Country unknown, so danger zones: the safe default."
            c == "BE" -> "Belgium: warning apps are legal, showing exact positions. " +
                "Art. 62bis bans detectors and jammers, not a list of known positions."
            c == "AT" -> "Austria: camera warning apps are legal. Jammers are not."
            c == "HU" -> "Hungary: KRESZ 3.§(3) expressly excludes advance-warning " +
                "devices from the ban on obstructing enforcement."
            c == "FR" -> "France: danger zones only, exact positions not permitted " +
                "(art. R413-15 and the 2011 agreement)."
            c == "DE" -> "Germany: §23 Abs. 1c StVO forbids the driver using a " +
                "camera-warning function, so alerts are off. It also counts if a " +
                "passenger runs it for you (OLG Karlsruhe, 2 ORbs 35 Ss 9/23)."
            c == "CH" || c == "LI" ->
                "$c: camera warnings are not permitted, even as GPS points, so alerts are off."
            policyFor(c) == CameraPolicy.OFF ->
                "$c: camera warnings are not permitted there, so alerts are off."
            policyFor(c) == CameraPolicy.EXACT ->
                "$c: warning of known camera positions is legal; detectors and jammers are not."
            else -> "$c: rules not verified, so danger zones: the safe default."
        }
    }

    /**
     * A country can never be talked into being *more* permissive than its rule.
     * The user's preference can only tighten it.
     */
    fun effective(detected: String?, userChoice: CameraPolicy?): CameraPolicy {
        val legal = policyFor(detected)
        val want = userChoice ?: legal
        return when (legal) {
            CameraPolicy.OFF -> CameraPolicy.OFF
            CameraPolicy.ZONE -> if (want == CameraPolicy.OFF) CameraPolicy.OFF else CameraPolicy.ZONE
            CameraPolicy.EXACT -> want
        }
    }
}
