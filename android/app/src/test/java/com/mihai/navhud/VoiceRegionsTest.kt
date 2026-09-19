package com.mihai.navhud

import com.mihai.navhud.voice.Phrases
import com.mihai.navhud.voice.VoiceRegions
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which voice the engine is allowed to answer in.
 *
 * The bug this pins down: asking for French and being answered in Canadian
 * French, because the old picker ranked by the engine's quality score and a
 * network-voice bonus rather than by region.
 */
class VoiceRegionsTest {

    private val frBE = Locale("fr", "BE")
    private val frFR = Locale.FRANCE
    private val frCA = Locale("fr", "CA")
    private val frGeneric = Locale("fr")

    @Test fun `Canadian French is never an answer to a request for French`() {
        assertFalse(VoiceRegions.acceptable(frBE, frCA))
        assertFalse(VoiceRegions.acceptable(frFR, frCA))
        assertEquals(Int.MAX_VALUE, VoiceRegions.rank(frBE, frCA))
        // ...even when the engine says it is the best voice on the device.
        assertEquals(Int.MAX_VALUE, VoiceRegions.sortKey(frBE, frCA, 500))
    }

    @Test fun `Belgian French prefers Belgium, then France`() {
        assertEquals(0, VoiceRegions.rank(frBE, frBE))
        assertEquals(1, VoiceRegions.rank(frBE, frFR))
        assertTrue(VoiceRegions.rank(frBE, frBE) < VoiceRegions.rank(frBE, frFR))
        // A region beats quality: a mediocre fr-FR voice wins over nothing,
        // and a fr-BE voice wins over a better fr-FR one.
        assertTrue(VoiceRegions.sortKey(frBE, frBE, 200) < VoiceRegions.sortKey(frBE, frFR, 500))
    }

    @Test fun `a generic voice with no region is acceptable but ranks last`() {
        val generic = VoiceRegions.rank(frBE, frGeneric)
        assertTrue(generic < Int.MAX_VALUE)
        assertTrue(generic > VoiceRegions.rank(frBE, frFR))
    }

    @Test fun `quality only breaks ties inside the same region`() {
        assertTrue(VoiceRegions.sortKey(frBE, frFR, 500) < VoiceRegions.sortKey(frBE, frFR, 100))
    }

    @Test fun `English gets British first and never French`() {
        assertEquals(0, VoiceRegions.rank(Locale.UK, Locale.UK))
        assertTrue(VoiceRegions.rank(Locale.UK, Locale.US) > 0)
        assertFalse(VoiceRegions.acceptable(Locale.UK, frFR))
        assertFalse(VoiceRegions.acceptable(frBE, Locale.UK))
    }

    @Test fun `a language with no opinion accepts anything in that language`() {
        val pl = Locale("pl", "PL")
        assertFalse(VoiceRegions.isRestricted("pl"))
        assertEquals(0, VoiceRegions.rank(pl, Locale("pl", "PL")))
        assertEquals(0, VoiceRegions.rank(pl, Locale("pl")))
        assertEquals(Int.MAX_VALUE, VoiceRegions.rank(pl, Locale.UK))
    }

    @Test fun `a null candidate is never acceptable`() {
        assertFalse(VoiceRegions.acceptable(frBE, null))
    }

    // ---- and the setting that reaches it ------------------------------------

    @Test fun `the language codes the Setup screen offers all resolve`() {
        // These are the exact strings MainActivity writes to preferences.
        assertSame(Phrases.forCode("en"), Phrases.forCode("en"))
        assertEquals("en", Phrases.forCode("en").code)
        assertEquals(frBE, Phrases.forCode("fr-BE").locale)
        assertEquals(frFR, Phrases.forCode("fr-FR").locale)
        // "" means follow the device, which is handled in Prefs, not here.
        assertEquals("en", Phrases.forCode("").code)
        // The legacy value earlier builds saved.
        assertEquals(frBE, Phrases.forCode("fr").locale)
    }

    @Test fun `picking English really does change the phrases`() {
        val en = Phrases.forCode("en")
        val be = Phrases.forCode("fr-BE")
        assertFalse(en.locale == be.locale)
        assertTrue(be.overLimit(90).contains("nonante"))
        assertTrue(en.overLimit(90).contains("90"))
    }
}
