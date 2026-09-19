package com.mihai.navhud.voice

/**
 * French numerals, written out.
 *
 * The reason this file exists: a text-to-speech engine pronounces "90" using
 * the rules of whatever voice is loaded, and every French voice Android ships
 * is metropolitan French, so "limite 90" comes out *quatre-vingt-dix* even on a
 * phone set to Belgium. There is no voice setting that changes that. The only
 * reliable fix is to stop handing the engine digits: "nonante" is just a word,
 * and any French voice reads it correctly.
 *
 * Belgium says **septante** (70) and **nonante** (90) but keeps **quatre-vingts**
 * for 80 — *huitante* is Swiss, and saying it in Brussels marks you out as
 * having learned French from a textbook. France is included for completeness so
 * the setting can offer both.
 */
object FrenchNumbers {

    private val UNITS = arrayOf(
        "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit",
        "neuf", "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize"
    )

    /**
     * @param belgian septante/nonante when true, soixante-dix/quatre-vingt-dix
     *                when false.
     */
    fun spell(n: Int, belgian: Boolean = true): String {
        // Int.MIN_VALUE has no positive counterpart, so negating it overflows
        // straight back to itself and the recursion never ends.
        if (n == Int.MIN_VALUE || n >= 1_000_000) return n.toString()
        if (n < 0) return "moins " + spell(-n, belgian)
        return when {
            n < 100 -> underHundred(n, belgian)
            n < 1000 -> hundreds(n, belgian, plural = true)
            else -> thousands(n, belgian)
        }
    }

    /** 0..19, the range where French stops being compositional. */
    private fun underTwenty(n: Int): String =
        if (n <= 16) UNITS[n] else "dix-" + UNITS[n - 10]

    private fun underHundred(n: Int, belgian: Boolean): String {
        if (n < 20) return underTwenty(n)

        val tens = n / 10
        val unit = n % 10

        // The two irregular decades. In Belgium they are regular, which is the
        // whole point; in France 70 counts on from soixante and 90 from
        // quatre-vingts, so 71 is "soixante et onze" and 92 "quatre-vingt-douze".
        if (!belgian && (tens == 7 || tens == 9)) {
            val base = if (tens == 7) "soixante" else "quatre-vingt"
            val rest = n - if (tens == 7) 60 else 80
            if (rest == 11 && tens == 7) return "soixante et onze"
            return "$base-" + underTwenty(rest)
        }

        val decade = when (tens) {
            2 -> "vingt"
            3 -> "trente"
            4 -> "quarante"
            5 -> "cinquante"
            6 -> "soixante"
            7 -> "septante"                       // Belgian
            8 -> if (unit == 0) "quatre-vingts" else "quatre-vingt"
            else -> "nonante"                     // Belgian
        }
        return when {
            unit == 0 -> decade
            // "vingt et un", "septante et un", but never "quatre-vingt et un".
            unit == 1 && tens != 8 -> "$decade et un"
            else -> "$decade-" + UNITS[unit]
        }
    }

    /**
     * @param plural false when something follows the whole group, so "cent"
     *               stays invariable: *trois cent mille*, not "trois cents mille".
     */
    private fun hundreds(n: Int, belgian: Boolean, plural: Boolean): String {
        val h = n / 100
        val rest = n % 100
        // "cent" takes an s when multiplied and nothing follows it:
        // deux cents, but deux cent cinquante.
        val head = when {
            h == 1 -> "cent"
            rest == 0 && plural -> UNITS[h] + " cents"
            else -> UNITS[h] + " cent"
        }
        return if (rest == 0) head else "$head " + underHundred(rest, belgian)
    }

    private fun thousands(n: Int, belgian: Boolean): String {
        val k = n / 1000
        val rest = n % 1000
        // "mille" is invariable: deux mille, jamais "deux milles". And the
        // multiplier in front of it never pluralises "cent" either.
        val head = when {
            k == 1 -> "mille"
            k < 100 -> underHundred(k, belgian) + " mille"
            else -> hundreds(k, belgian, plural = false) + " mille"
        }
        return if (rest == 0) head else "$head " + spell(rest, belgian)
    }
}
