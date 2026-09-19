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
 * Sources are listed in docs/CAMERAS.md. This is a best effort from public
 * sources, not legal advice, which is why UNVERIFIED countries default to the
 * conservative ZONE rather than to EXACT.
 */
enum class CameraPolicy { EXACT, ZONE, OFF }

object CountryRules {

    /** Countries whose rules were checked against a named source. */
    private val verified = mapOf(
        "BE" to CameraPolicy.EXACT,
        "AT" to CameraPolicy.EXACT,
        "HU" to CameraPolicy.EXACT,
        "FR" to CameraPolicy.ZONE,
        "DE" to CameraPolicy.OFF,
        "CH" to CameraPolicy.OFF
    )

    /**
     * Everywhere else. ZONE still warns you that enforcement is likely on this
     * stretch, without publishing a position -- the behaviour that is lawful in
     * the strictest country we know of that still permits anything.
     */
    val DEFAULT = CameraPolicy.ZONE

    fun policyFor(iso2: String?): CameraPolicy {
        val c = iso2?.uppercase() ?: return DEFAULT
        return verified[c] ?: DEFAULT
    }

    fun isVerified(iso2: String?) = verified.containsKey(iso2?.uppercase())

    /** Human-readable explanation for the settings screen and the map chip. */
    fun explain(iso2: String?): String {
        val c = iso2?.uppercase()
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
            c == "CH" -> "Switzerland: camera warnings are not permitted, so alerts are off."
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
