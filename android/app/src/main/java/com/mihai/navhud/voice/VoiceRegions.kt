package com.mihai.navhud.voice

import java.util.Locale

/**
 * Which regional voice is acceptable for a language, and in what order.
 *
 * This exists because of one specific, very audible failure: asking Android
 * for French and being answered in **Canadian** French. Android ships fr-FR
 * and fr-CA on most devices and fr-BE on almost none, and the old picker
 * ranked candidates by the engine's own quality score with a bonus for
 * network voices — so on a phone where the Québécois voice happened to be the
 * high-quality network one, that is what a driver in Brussels got.
 *
 * The fix is not a better score. It is a whitelist: a region we did not ask
 * for is rejected outright, however good the engine says it is. Quality only
 * breaks ties *within* the acceptable set.
 *
 * Pure, so it can be tested without a phone or a text-to-speech engine.
 */
object VoiceRegions {

    /** Acceptable regions per language, best first. */
    fun regionsFor(language: String?): List<String> = when (language?.lowercase()) {
        "fr" -> listOf("BE", "FR", "CH", "LU")
        "en" -> listOf("GB", "IE", "US", "AU")
        "nl" -> listOf("BE", "NL")
        "de" -> listOf("DE", "AT", "CH")
        else -> emptyList()
    }

    /** True when this language has an opinion about regions at all. */
    fun isRestricted(language: String?) = regionsFor(language).isNotEmpty()

    /**
     * Lower is better. [Int.MAX_VALUE] means "not acceptable, do not use".
     *
     * A voice with no region at all — plain "fr" — ranks last but is still
     * acceptable: it is the engine's generic voice and it is never the wrong
     * dialect in the way fr-CA is.
     */
    fun rank(want: Locale, candidate: Locale?): Int {
        if (candidate == null) return Int.MAX_VALUE
        if (!candidate.language.equals(want.language, ignoreCase = true)) return Int.MAX_VALUE
        val regions = regionsFor(want.language)
        if (regions.isEmpty()) return 0
        val i = regions.indexOfFirst { it.equals(candidate.country, ignoreCase = true) }
        return when {
            i >= 0 -> i
            candidate.country.isNullOrBlank() -> regions.size
            else -> Int.MAX_VALUE
        }
    }

    fun acceptable(want: Locale, candidate: Locale?) = rank(want, candidate) != Int.MAX_VALUE

    /**
     * One sort key combining region and quality, so the choice is a single
     * comparison. Quality runs 100 (very low) to 500 (very high); it is
     * inverted here so that, like the region rank, lower is better.
     */
    fun sortKey(want: Locale, candidate: Locale?, quality: Int): Int {
        val r = rank(want, candidate)
        if (r == Int.MAX_VALUE) return Int.MAX_VALUE
        return r * 1000 + (500 - quality).coerceIn(0, 999)
    }
}
