package com.mihai.navhud

import com.mihai.navhud.alerts.CameraPolicy
import com.mihai.navhud.alerts.CameraWatcher
import com.mihai.navhud.alerts.CountryRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The French danger-zone rules, which are a legal constraint rather than a
 * design preference: 300 m in town, 2 km on an ordinary road, 4 km on a
 * motorway, and never a number precise enough to locate the camera.
 */
class ZonePolicyTest {

    @Test
    fun `zone length follows the kind of road`() {
        assertEquals(4000, CameraWatcher.zoneLengthFor(130))
        assertEquals(4000, CameraWatcher.zoneLengthFor(110))
        assertEquals(4000, CameraWatcher.zoneLengthFor(100))
        assertEquals(2000, CameraWatcher.zoneLengthFor(90))
        assertEquals(2000, CameraWatcher.zoneLengthFor(70))
        assertEquals(300, CameraWatcher.zoneLengthFor(50))
        assertEquals(300, CameraWatcher.zoneLengthFor(30))
        // Unknown limit gets the middle case, not the shortest.
        assertEquals(2000, CameraWatcher.zoneLengthFor(0))
    }

    @Test
    fun `the distance shown inside a zone cannot locate the camera`() {
        // On a motorway the granularity is a kilometre...
        assertEquals(2000, CameraWatcher.blurZoneDistance(1840, 4000))
        assertEquals(2000, CameraWatcher.blurZoneDistance(2300, 4000))
        // ...so two very different true distances read the same.
        assertEquals(
            CameraWatcher.blurZoneDistance(1600, 4000),
            CameraWatcher.blurZoneDistance(2400, 4000)
        )
        // In town the zone is short, so the step is fine enough to be useful.
        assertEquals(200, CameraWatcher.blurZoneDistance(180, 300))
    }

    @Test
    fun `a country can only ever be tightened by the user`() {
        // Belgium allows exact positions; the driver may still ask for less.
        assertEquals(CameraPolicy.ZONE,
            CountryRules.effective("BE", CameraPolicy.ZONE))
        // France does not allow exact, and asking for it changes nothing.
        assertEquals(CameraPolicy.ZONE,
            CountryRules.effective("FR", CameraPolicy.EXACT))
        // Germany is off, and stays off.
        assertEquals(CameraPolicy.OFF,
            CountryRules.effective("DE", CameraPolicy.EXACT))
        assertEquals(CameraPolicy.OFF,
            CountryRules.effective("CH", CameraPolicy.EXACT))
    }

    @Test
    fun `the countries checked against a source are the ones treated as known`() {
        for (c in listOf("BE", "FR", "DE", "CH", "AT", "HU")) {
            assertTrue(c, CountryRules.isVerified(c))
        }
        // Romania has no verified rule, so it gets the cautious default.
        assertEquals(CameraPolicy.ZONE, CountryRules.policyFor("RO"))
        assertEquals(CameraPolicy.ZONE, CountryRules.policyFor(null))
    }

    @Test
    fun `austria and hungary allow exact positions`() {
        assertEquals(CameraPolicy.EXACT, CountryRules.policyFor("AT"))
        assertEquals(CameraPolicy.EXACT, CountryRules.policyFor("HU"))
    }
}
