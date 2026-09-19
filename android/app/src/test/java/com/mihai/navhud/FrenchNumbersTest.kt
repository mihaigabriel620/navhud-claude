package com.mihai.navhud

import com.mihai.navhud.voice.FrenchBelgium
import com.mihai.navhud.voice.FrenchFrance
import com.mihai.navhud.voice.FrenchNumbers
import com.mihai.navhud.voice.Phrases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Belgian French numerals.
 *
 * The bug this fixes: the app showed a 90 limit and the voice said
 * *quatre-vingt-dix*. No Android TTS setting changes that — the engine applies
 * metropolitan French rules to the digits "90" whatever locale you ask for — so
 * the numbers are written out as words before they reach it.
 */
class FrenchNumbersTest {

    private fun be(n: Int) = FrenchNumbers.spell(n, belgian = true)
    private fun fr(n: Int) = FrenchNumbers.spell(n, belgian = false)

    @Test fun `the three numbers Belgium says differently`() {
        assertEquals("septante", be(70))
        assertEquals("quatre-vingts", be(80))      // not huitante -- that is Swiss
        assertEquals("nonante", be(90))
    }

    @Test fun `and what France says instead`() {
        assertEquals("soixante-dix", fr(70))
        assertEquals("quatre-vingts", fr(80))      // the one they agree on
        assertEquals("quatre-vingt-dix", fr(90))
    }

    @Test fun `the speed limits actually posted on Belgian roads`() {
        assertEquals("trente", be(30))
        assertEquals("cinquante", be(50))
        assertEquals("septante", be(70))
        assertEquals("nonante", be(90))
        assertEquals("cent", be(100))
        assertEquals("cent vingt", be(120))
        assertEquals("cent trente", be(130))
    }

    @Test fun `the counting in between is regular in Belgium`() {
        assertEquals("septante et un", be(71))
        assertEquals("septante-deux", be(72))
        assertEquals("septante-neuf", be(79))
        assertEquals("nonante et un", be(91))
        assertEquals("nonante-cinq", be(95))
        assertEquals("nonante-neuf", be(99))
    }

    @Test fun `and irregular in France`() {
        assertEquals("soixante et onze", fr(71))
        assertEquals("soixante-douze", fr(72))
        assertEquals("soixante-dix-neuf", fr(79))
        assertEquals("quatre-vingt-onze", fr(91))
        assertEquals("quatre-vingt-dix-neuf", fr(99))
    }

    @Test fun `quatre-vingts loses its s when something follows`() {
        assertEquals("quatre-vingts", be(80))
        assertEquals("quatre-vingt-un", be(81))    // never "quatre-vingt et un"
        assertEquals("quatre-vingt-cinq", be(85))
    }

    @Test fun `the small numbers and the teens`() {
        assertEquals("zéro", be(0))
        assertEquals("un", be(1))
        assertEquals("seize", be(16))
        assertEquals("dix-sept", be(17))
        assertEquals("dix-neuf", be(19))
        assertEquals("vingt", be(20))
        assertEquals("vingt et un", be(21))
        assertEquals("vingt-deux", be(22))
    }

    @Test fun `cent takes an s only when nothing follows it`() {
        assertEquals("cent", be(100))
        assertEquals("cent cinquante", be(150))
        assertEquals("deux cents", be(200))
        assertEquals("deux cent cinquante", be(250))
        assertEquals("sept cents", be(700))
        assertEquals("neuf cent nonante-neuf", be(999))
    }

    @Test fun `mille never takes one`() {
        assertEquals("mille", be(1000))
        assertEquals("mille cinq cents", be(1500))
        assertEquals("deux mille", be(2000))
        assertEquals("deux mille quatre cents", be(2400))
    }

    @Test fun `every distance the app can speak is a real French phrase`() {
        for (n in 0..3000) {
            val s = be(n)
            assertFalse("digits leaked through at $n: '$s'", s.any { it.isDigit() })
            assertTrue("empty or malformed at $n: '$s'",
                s.isNotBlank() && !s.contains("  ") && !s.startsWith("-") && !s.endsWith("-"))
        }
    }

    @Test fun `the edges that would have crashed or read wrong`() {
        // Negating Int.MIN_VALUE overflows back to itself: infinite recursion.
        assertEquals(Int.MIN_VALUE.toString(), be(Int.MIN_VALUE))
        // "cent" is invariable in front of "mille": trois cent mille.
        assertEquals("trois cent mille", be(300_000))
        assertEquals("deux cent cinquante mille", be(250_000))
        assertEquals("cent mille", be(100_000))
        assertEquals("moins vingt", be(-20))
        assertEquals("mille cent", be(1100))
        assertEquals("nonante mille", be(90_000))
        assertEquals("quatre-vingt-dix mille", fr(90_000))
    }

    // ---- how the phrases use them ------------------------------------------

    @Test fun `the spoken limit is Belgian`() {
        assertEquals("Vitesse limitée à nonante.", FrenchBelgium.overLimit(90))
        assertEquals("Vitesse limitée à septante.", FrenchBelgium.overLimit(70))
        assertEquals("Vitesse limitée à quatre-vingt-dix.", FrenchFrance.overLimit(90))
    }

    @Test fun `spoken distances are words, not digits`() {
        assertEquals("septante mètres", FrenchBelgium.distance(70))
        assertEquals("sept cents mètres", FrenchBelgium.distance(700))
        assertEquals("un kilomètre", FrenchBelgium.distance(1000))
        assertEquals("un kilomètre et demi", FrenchBelgium.distance(1500))
        assertEquals("deux kilomètres", FrenchBelgium.distance(2000))
    }

    @Test fun `the camera warning reads as a sentence`() {
        assertEquals("Radar dans sept cents mètres, limite nonante.",
            FrenchBelgium.cameraAhead(FrenchBelgium.distance(700), 90))
    }

    // ---- "maintenant" is gone ----------------------------------------------

    @Test fun `the final call is the instruction alone`() {
        val s = FrenchBelgium.immediate(
            FrenchBelgium.instruction(Man.RIGHT, 0, "Avenue de la Couronne"))
        assertEquals("Tournez à droite sur Avenue de la Couronne.", s)
        assertFalse("maintenant should be gone", s.contains("maintenant"))
    }

    @Test fun `English lost its now as well`() {
        val s = com.mihai.navhud.voice.English.immediate(
            com.mihai.navhud.voice.English.instruction(Man.LEFT, 0, "Rue Neuve"))
        assertEquals("Turn left onto Rue Neuve.", s)
        assertFalse(s.contains(" now"))
    }

    // ---- picking the right variant -----------------------------------------

    @Test fun `plain fr means Belgium here, and France can be asked for`() {
        assertSame(FrenchBelgium, Phrases.forCode("fr"))
        assertSame(FrenchBelgium, Phrases.forCode("fr-BE"))
        assertSame(FrenchBelgium, Phrases.forCode("fr_BE"))
        assertSame(FrenchFrance, Phrases.forCode("fr-FR"))
        assertSame(com.mihai.navhud.voice.English, Phrases.forCode("en-GB"))
        assertSame(com.mihai.navhud.voice.English, Phrases.forCode(null))
    }

    @Test fun `both French variants still ask Mapbox for French`() {
        assertEquals("fr", FrenchBelgium.code)
        assertEquals("fr", FrenchFrance.code)
        assertEquals("BE", FrenchBelgium.locale.country)
    }
}
