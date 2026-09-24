package com.mihai.navhud

import com.mihai.navhud.alerts.CameraAlert
import com.mihai.navhud.alerts.CameraPolicy
import com.mihai.navhud.alerts.CountryRules
import com.mihai.navhud.alerts.SpeedCamera
import com.mihai.navhud.voice.FrenchBelgium
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Camera rules for a drive across Europe, Brussels to Bucharest and around.
 * The interesting cases are the borders, and the lookups that do not answer
 * in ISO codes.
 */
class CountryPolicyTest {

    @Test fun `Brussels to Bucharest, country by country`() {
        val trip = listOf(
            "BE" to CameraPolicy.EXACT,
            "DE" to CameraPolicy.OFF,        // §23 StVO: nothing at all
            "AT" to CameraPolicy.EXACT,
            "HU" to CameraPolicy.EXACT,
            "RO" to CameraPolicy.EXACT
        )
        for ((c, p) in trip) assertEquals(c, p, CountryRules.policyFor(c))
    }

    @Test fun `where warnings are banned outright they are off`() {
        for (c in listOf("DE", "CH", "LI", "CY", "MK", "TR")) {
            assertEquals(c, CameraPolicy.OFF, CountryRules.policyFor(c))
            assertEquals("the user cannot switch them back on in $c",
                CameraPolicy.OFF, CountryRules.effective(c, CameraPolicy.EXACT))
        }
    }

    @Test fun `France is danger zones and the unverified are too`() {
        assertEquals(CameraPolicy.ZONE, CountryRules.policyFor("FR"))
        for (c in listOf("CZ", "SK", "SI", "RS", "BG")) {
            assertEquals(c, CameraPolicy.ZONE, CountryRules.policyFor(c))
        }
    }

    @Test fun `neighbours where position warnings are legal`() {
        for (c in listOf("NL", "LU", "IT", "ES", "PL", "HR")) {
            assertEquals(c, CameraPolicy.EXACT, CountryRules.policyFor(c))
        }
    }

    @Test fun `a country name or region code is read as its ISO code`() {
        assertEquals("BE", CountryRules.normalise("be"))
        assertEquals("BE", CountryRules.normalise("BE-VLG"))
        assertEquals("DE", CountryRules.normalise("DE:urban"))
        assertEquals("BE", CountryRules.normalise("BEL"))
        assertEquals("DE", CountryRules.normalise("Allemagne"))
        assertEquals("FR", CountryRules.normalise("France"))
        assertNull(CountryRules.normalise(null))
        assertNull(CountryRules.normalise("Atlantis"))
        // The one that mattered: Mapbox's name fallback in French, in Germany,
        // used to fall through to ZONE -- danger-zone warnings where any
        // warning is an offence.
        assertEquals(CameraPolicy.OFF, CountryRules.policyFor("Allemagne"))
        assertEquals(CameraPolicy.OFF, CountryRules.policyFor("Deutschland"))
        assertEquals(CameraPolicy.EXACT, CountryRules.policyFor("Belgique"))
    }

    @Test fun `every country has an explanation`() {
        for (c in listOf(null, "BE", "DE", "FR", "CH", "LI", "TR", "RO", "SK", "Allemagne")) {
            assertTrue(CountryRules.explain(c).isNotBlank())
        }
        assertTrue(CountryRules.explain("Allemagne").startsWith("Germany"))
    }

    @Test fun `in Belgium the voice says radar, not danger zone`() {
        assertEquals(CameraPolicy.EXACT, CountryRules.effective("BE", null))
        var now = 1_000_000L
        val speaker = FakeSpeaker()
        val g = VoiceGuide(null, FrenchBelgium, speaker) { now }
        val cam = SpeedCamera(1, 50.8, 4.3, 0.0, 70, null, SpeedCamera.Kind.FIXED)
        val zoneMode = CountryRules.effective("BE", null) == CameraPolicy.ZONE
        g.announceCamera(CameraAlert(cam, 700, zoneMode))
        assertEquals(listOf("Radar dans sept cents mètres, limite septante."), speaker.texts)
    }
}
