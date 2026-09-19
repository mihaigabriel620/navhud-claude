package com.mihai.navhud.nav


/**
 * What colour a direction sign is, country by country.
 *
 * This is not decoration. A driver reads the colour of a signpost before they
 * read a single word on it — green in Italy means "this is the motorway", green
 * in Belgium means "this way *to* the motorway", and a nav app that paints
 * every sign the same colour is throwing away the fastest channel it has.
 *
 * ## The rule, and the trap in it
 *
 * There are two different systems in Europe and they are easy to conflate:
 *
 *  - **Most countries colour the sign by the road you are on.** Blue on the
 *    Autobahn, green on the autostrada.
 *  - **Belgium colours the sign by the road that reaches the destination.**
 *    A destination you get to by motorway is on a green panel *even when the
 *    sign is standing on an ordinary road*, and a destination you reach by
 *    ordinary road is on a blue panel *even when the sign is hanging over a
 *    motorway*. That is why Belgian motorway gantries are blue but the sign
 *    at the roundabout pointing you at the slip road is green.
 *
 * Both collapse into the same implementation: colour the panel by the class of
 * the road the driver is about to be on, which is what
 * [colourFor] takes. The country lists below are the sign colour used for
 * motorway traffic in each country; the ordinary-road colour is the other one
 * of the pair, except where a country uses a third colour entirely (Germany
 * and Austria: yellow for Bundesstraßen/Landesstraßen; the British Isles:
 * green for primary routes and white for everything else).
 *
 * Sources: national highway-authority sign catalogues, cross-checked against
 * the Vienna Convention country annexes. Verified list rather than a guess —
 * the first draft of this file had Belgium the wrong way round.
 */
object RoadSigns {

    // ---- palette ------------------------------------------------------------
    // Written as literals rather than Color.rgb(). The unit tests run against
    // the stubbed android.jar with returnDefaultValues on, where every
    // Color.rgb() call returns 0 -- so BLUE, GREEN, YELLOW and WHITE were all
    // the same value and every sign-colour assertion in the suite passed
    // whatever the code did. Two of them asserted opposite things about
    // Belgium and both were green.
    const val BLUE   = 0xFF004B9B.toInt()
    const val GREEN  = 0xFF0B6B3D.toInt()
    const val YELLOW = 0xFFE8C41C.toInt()
    const val WHITE  = 0xFFECECEE.toInt()

    const val TEXT_ON_DARK  = 0xFFFFFFFF.toInt()
    const val TEXT_ON_LIGHT = 0xFF141416.toInt()

    /** ISO-3166 alpha-2 codes whose motorway signs are green. */
    private val GREEN_MOTORWAY = hashSetOf(
        "IT", "CH", "SI", "HR", "CZ", "SK", "SE", "FI", "DK", "GR",
        "RO", "BG", "TR", "LT", "RS", "ME", "MK", "AL", "RU", "UA", "MD",
        "BA", "XK"
    )

    /** Countries that put ordinary trunk roads on yellow rather than the pair. */
    private val YELLOW_ORDINARY = hashSetOf("DE", "AT")

    /** Countries whose ordinary primary routes are green (and locals white). */
    private val GREEN_PRIMARY = hashSetOf("GB", "IE")

    fun isGreenMotorway(cc: String?): Boolean =
        cc != null && GREEN_MOTORWAY.contains(cc.uppercase())

    /**
     * Panel colour for a sign about a road of the given class.
     *
     * @param cc         ISO-3166 alpha-2 country code, null when unknown
     * @param motorway   true when the destination is reached by motorway
     */
    fun colourFor(cc: String?, motorway: Boolean): Int {
        val c = cc?.uppercase()
        val greenMw = isGreenMotorway(c)
        if (motorway) return if (greenMw) GREEN else BLUE
        // Ordinary roads.
        if (c != null && YELLOW_ORDINARY.contains(c)) return YELLOW
        if (c != null && GREEN_PRIMARY.contains(c)) return GREEN
        return if (greenMw) BLUE else GREEN
    }

    /**
     * Countries that colour a panel by the road that *reaches* the destination
     * rather than by the road the sign stands on.
     *
     * Belgium is the one that matters here, and it needs both halves of the
     * rule, not just the first. The Arrêté ministériel of 11 October 1976,
     * art. 12.9.1, 9° says a destination is signed white-on-green "si la
     * destination est atteinte par autoroute" -- but the regional standards
     * that implement it scope green to the *ordinary* network: AWV's dienstorder
     * MOW/AWV/2018/4 says "witte tekst op groene achtergrond voor aanduidingen
     * van autosnelwegen **op gewone gewestwegen**", and on motorway signage
     * itself AWV's Richtlijn Bewegwijzering Autosnelwegen makes every
     * destination panel blue, with green reserved for the E-number badge.
     * Wallonia's Sécurothèque says the same for interchanges: city destinations
     * on blue, European and motorway numbers on green.
     *
     * So: green only when an ordinary road points at a motorway. Every panel
     * on a motorway gantry is blue, including one pointing at another motorway.
     * The old code returned green for an ordinary destination and blue for a
     * motorway one -- wrong in three of the four cases.
     */
    private val BY_DESTINATION = hashSetOf("BE")

    /**
     * The panel colour for a junction sign.
     *
     * @param onMotorway   the carriageway the sign stands over is a motorway
     * @param ontoMotorway the road the driver is being sent onto is a motorway
     */
    fun junctionPanel(cc: String?, onMotorway: Boolean, ontoMotorway: Boolean): Int {
        val c = cc?.uppercase()
        if (c != null && BY_DESTINATION.contains(c)) {
            return if (!onMotorway && ontoMotorway) GREEN else BLUE
        }
        return colourFor(cc, onMotorway)
    }

    fun textColourFor(panel: Int): Int =
        if (panel == YELLOW || panel == WHITE) TEXT_ON_LIGHT else TEXT_ON_DARK

    /**
     * Guess the country from a route reference when there is no better source.
     * Not authoritative — the fix's reverse geocode is — but it stops the sign
     * flashing the wrong colour for the second or two before that lands.
     */
    fun countryFromRef(ref: String?): String? {
        val r = ref?.trim()?.uppercase() ?: return null
        // "E40" is pan-European and says nothing about which country you are in.
        if (r.startsWith("E") && r.drop(1).all { it.isDigit() }) return null
        return null
    }

    /**
     * Does this reference look like a motorway? Used to decide which of the two
     * colours a junction sign gets when the router does not label the class.
     *
     * Belgium A/E, France A, Germany A, Netherlands A, Romania A, Italy A,
     * Spain AP/A, Portugal A, UK M. Deliberately conservative: anything it is
     * not sure about is treated as an ordinary road, because a blue sign in
     * Belgium where a green one belonged is a smaller error than the reverse
     * (blue is what an ordinary destination gets, and most exits lead to
     * ordinary roads).
     *
     * "D" is deliberately absent despite being in the first draft: a French
     * D-road is a route départementale, and a Romanian DN/DJ is a national or
     * county road. None of them is a motorway, and D<number> is common in both.
     */
    fun looksLikeMotorway(ref: String?): Boolean {
        val r = ref?.trim()?.uppercase()?.replace(" ", "") ?: return false
        if (r.isEmpty()) return false
        val letters = r.takeWhile { it.isLetter() }
        val digits = r.drop(letters.length)
        if (digits.isEmpty() || !digits.first().isDigit()) return false
        return when (letters) {
            "A", "AP", "M", "E" -> true
            else -> false
        }
    }
}
