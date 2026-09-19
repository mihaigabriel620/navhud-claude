package com.mihai.navhud.voice

import com.mihai.navhud.Man
import java.util.Locale

/**
 * Spoken phrasing, per language.
 *
 * These are written as real sentences in each language, not English strings run
 * through a translator. French puts the ordinal before "sortie", uses a comma
 * for decimals, and says "serrez à gauche" where English says "keep left" --
 * word-for-word translation gets all three wrong.
 */
interface Phrases {
    val locale: Locale
    val code: String

    /** "300 metres" / "300 mètres" */
    fun distance(metres: Int): String

    /** The bare instruction: "turn right onto X" / "tournez à droite sur X" */
    fun instruction(maneuver: Int, roundaboutExit: Int, street: String): String

    /** "In <distance>, <instruction>." */
    fun advance(distance: String, instruction: String): String

    /**
     * The final call, at the junction itself. Deliberately just the
     * instruction: the tail Google and TomTom both leave off, because by the
     * time a voice has finished saying "now" you are past the turn.
     */
    fun immediate(instruction: String): String

    fun arrived(): String
    fun willArrive(): String

    /** Camera warning, exact position known. */
    fun cameraAhead(distance: String, limitKph: Int): String

    /** Camera warning, France-style zone with no precise position. */
    fun dangerZone(): String

    fun reroute(): String
    fun overLimit(limitKph: Int): String

    /**
     * A stretch of road the provider reports as closed — roadworks, an
     * accident, an event. Said once per closure, far enough out to still be
     * able to do something about it.
     */
    fun roadworks(distance: String): String

    /** Static road features: a level crossing, a speed bump, a toll booth. */
    fun levelCrossing(): String
    fun speedBump(): String
    fun tollBooth(): String

    companion object {
        /**
         * Speakable metres: to the nearest hundred, not the hundred below.
         *
         * Maneuver announcements always pass an exact threshold so this never
         * showed there, but camera and closure warnings pass the real measured
         * distance -- and integer division turned a camera 199 m ahead into
         * "in one hundred metres", an error of nearly a factor of two, and 999
         * m into "nine hundred".
         */
        fun round100(metres: Int): Int = ((metres + 50) / 100) * 100

        /**
         * Accepts "en", "fr", "fr-BE", "fr_FR" and the like. Plain "fr" gets
         * the Belgian numerals: this app is driven in Brussels, and a driver
         * who wants Parisian counting can pick it explicitly.
         */
        fun forCode(code: String?): Phrases {
            val c = (code ?: "").lowercase().replace('_', '-')
            return when {
                c.startsWith("fr") -> if (c.endsWith("-fr")) FrenchFrance else FrenchBelgium
                else -> English
            }
        }

        /** Follows the device language when the user has not chosen one. */
        fun forDevice(): Phrases {
            val l = Locale.getDefault()
            return forCode("${l.language}-${l.country}")
        }

        /** "tournez à droite" -> "Tournez à droite" */
        internal fun capitalise(s: String): String =
            if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)
    }
}

// ---------------------------------------------------------------------------

object English : Phrases {
    override val locale: Locale = Locale.UK
    override val code = "en"

    private val ordinals = arrayOf(
        "", "first", "second", "third", "fourth", "fifth",
        "sixth", "seventh", "eighth", "ninth", "tenth"
    )

    override fun distance(metres: Int): String = when {
        metres >= 1000 && metres % 1000 == 0 ->
            "${metres / 1000} kilometre${if (metres == 1000) "" else "s"}"
        metres >= 1000 -> "${"%.1f".format(locale, metres / 1000.0)} kilometres"
        metres >= 100 -> "${Phrases.round100(metres)} metres"
        else -> "$metres metres"
    }

    override fun instruction(maneuver: Int, roundaboutExit: Int, street: String): String {
        val onto = if (street.isNotBlank()) " onto $street" else ""
        return when (maneuver) {
            Man.LEFT -> "turn left$onto"
            Man.RIGHT -> "turn right$onto"
            Man.SLIGHT_LEFT -> "bear left$onto"
            Man.SLIGHT_RIGHT -> "bear right$onto"
            Man.SHARP_LEFT -> "turn sharply left$onto"
            Man.SHARP_RIGHT -> "turn sharply right$onto"
            Man.UTURN -> "make a U-turn"
            Man.MERGE_LEFT -> "merge left$onto"
            Man.MERGE_RIGHT -> "merge right$onto"
            Man.FORK_LEFT -> "keep left at the fork$onto"
            Man.FORK_RIGHT -> "keep right at the fork$onto"
            Man.KEEP_LEFT -> "keep left$onto"
            Man.KEEP_RIGHT -> "keep right$onto"
            Man.RAMP_LEFT -> "take the ramp on the left$onto"
            Man.RAMP_RIGHT -> "take the ramp on the right$onto"
            Man.ROUNDABOUT -> {
                val n = ordinals.getOrNull(roundaboutExit)?.takeIf { it.isNotEmpty() }
                if (n != null) "at the roundabout, take the $n exit$onto"
                else "at the roundabout, follow the route$onto"
            }
            Man.ARRIVE -> "you have arrived"
            Man.DEPART -> "start off$onto"
            else -> "continue straight$onto"
        }
    }

    override fun advance(distance: String, instruction: String) = "In $distance, $instruction."
    override fun immediate(instruction: String) = Phrases.capitalise(instruction) + "."
    override fun arrived() = "You have arrived."
    override fun willArrive() = "you will arrive at your destination"
    override fun cameraAhead(distance: String, limitKph: Int) =
        if (limitKph > 0) "Speed camera in $distance, limit $limitKph."
        else "Speed camera in $distance."
    override fun dangerZone() = "Entering a danger zone."
    override fun reroute() = "Recalculating."
    override fun overLimit(limitKph: Int) = "Speed limit $limitKph."
    override fun roadworks(distance: String) = "Roadworks ahead in $distance."
    override fun levelCrossing() = "Level crossing ahead."
    override fun speedBump() = "Speed bump ahead."
    override fun tollBooth() = "Toll booth ahead."
}

// ---------------------------------------------------------------------------

/**
 * French guidance.
 *
 * Numbers are spelled out rather than left as digits, which is the only way to
 * make a French TTS voice say *nonante* instead of *quatre-vingt-dix*: the
 * engine applies its own numeral rules to "90" whatever locale it is set to,
 * and every French voice Android ships is metropolitan.
 */
open class FrenchBase(
    /** septante/nonante, or the metropolitan forms. */
    private val belgian: Boolean,
    override val locale: Locale
) : Phrases {

    override val code = "fr"       // what Mapbox wants; the region is ours alone

    private fun num(n: Int) = FrenchNumbers.spell(n, belgian)

    private val ordinals = arrayOf(
        "", "première", "deuxième", "troisième", "quatrième", "cinquième",
        "sixième", "septième", "huitième", "neuvième", "dixième"
    )

    override fun distance(metres: Int): String = when {
        metres >= 1000 && metres % 1000 == 0 -> {
            val km = metres / 1000
            "${num(km)} kilomètre${if (km == 1) "" else "s"}"
        }
        // "un kilomètre et demi" is what a Belgian voice actually says.
        metres >= 1000 && metres % 1000 == 500 -> {
            val km = metres / 1000
            "${num(km)} kilomètre${if (km == 1) "" else "s"} et demi"
        }
        metres >= 1000 -> "${num(Phrases.round100(metres))} mètres"
        metres >= 100 -> "${num(Phrases.round100(metres))} mètres"
        else -> "${num(metres)} mètres"
    }

    override fun instruction(maneuver: Int, roundaboutExit: Int, street: String): String {
        // "sur" for a named road; French nav says "sur la Rue de la Loi", and the
        // article is already part of the OSM name, so no article is added here.
        val sur = if (street.isNotBlank()) " sur $street" else ""
        return when (maneuver) {
            Man.LEFT -> "tournez à gauche$sur"
            Man.RIGHT -> "tournez à droite$sur"
            Man.SLIGHT_LEFT -> "serrez à gauche$sur"
            Man.SLIGHT_RIGHT -> "serrez à droite$sur"
            Man.SHARP_LEFT -> "tournez fortement à gauche$sur"
            Man.SHARP_RIGHT -> "tournez fortement à droite$sur"
            Man.UTURN -> "faites demi-tour"
            Man.MERGE_LEFT -> "insérez-vous à gauche$sur"
            Man.MERGE_RIGHT -> "insérez-vous à droite$sur"
            Man.FORK_LEFT -> "à l'embranchement, restez à gauche$sur"
            Man.FORK_RIGHT -> "à l'embranchement, restez à droite$sur"
            Man.KEEP_LEFT -> "restez à gauche$sur"
            Man.KEEP_RIGHT -> "restez à droite$sur"
            Man.RAMP_LEFT -> "prenez la bretelle à gauche$sur"
            Man.RAMP_RIGHT -> "prenez la sortie à droite$sur"
            Man.ROUNDABOUT -> {
                val n = ordinals.getOrNull(roundaboutExit)?.takeIf { it.isNotEmpty() }
                if (n != null) "au rond-point, prenez la $n sortie$sur"
                else "au rond-point, suivez l'itinéraire$sur"
            }
            Man.ARRIVE -> "vous êtes arrivé"
            Man.DEPART -> "démarrez$sur"
            else -> "continuez tout droit$sur"
        }
    }

    override fun advance(distance: String, instruction: String) = "Dans $distance, $instruction."

    // No "maintenant": by the time the voice has said it you are in the
    // junction. The instruction on its own is the whole message.
    override fun immediate(instruction: String) = Phrases.capitalise(instruction) + "."

    override fun arrived() = "Vous êtes arrivé à destination."
    override fun willArrive() = "vous arriverez à destination"
    override fun cameraAhead(distance: String, limitKph: Int) =
        if (limitKph > 0) "Radar dans $distance, limite ${num(limitKph)}."
        else "Radar dans $distance."
    override fun dangerZone() = "Vous entrez dans une zone de danger."
    override fun reroute() = "Recalcul de l'itinéraire."
    override fun overLimit(limitKph: Int) = "Vitesse limitée à ${num(limitKph)}."
    override fun roadworks(distance: String) = "Travaux dans $distance."
    override fun levelCrossing() = "Passage à niveau."
    override fun speedBump() = "Ralentisseur."
    override fun tollBooth() = "Péage."
}

/** Belgium: septante, quatre-vingts, nonante. */
object FrenchBelgium : FrenchBase(belgian = true, locale = Locale("fr", "BE"))

/** France: soixante-dix, quatre-vingts, quatre-vingt-dix. */
object FrenchFrance : FrenchBase(belgian = false, locale = Locale.FRANCE)
