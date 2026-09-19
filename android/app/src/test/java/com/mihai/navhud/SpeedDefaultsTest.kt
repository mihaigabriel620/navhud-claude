package com.mihai.navhud

import com.mihai.navhud.nav.Area
import com.mihai.navhud.nav.RoadWay
import com.mihai.navhud.nav.SpeedDefaults
import com.mihai.navhud.nav.SpeedDefaults.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wrong speed limit is worse than none: the display says you are legal when
 * you are not. So every value here is checked against the law it comes from,
 * and every case where the law cannot be resolved from what the app knows has
 * to come back marked WEAK rather than confident.
 */
class SpeedDefaultsTest {

    private fun be(region: String, highway: String, urban: Boolean?, dual: Boolean = false) =
        SpeedDefaults.implied(region, highway, urban, dual)

    // ---- Belgium, the reason this file exists ------------------------------

    @Test fun `the Flanders and Wallonia rural split is twenty km per hour`() {
        // Flanders dropped 90 -> 70 outside built-up areas on 1 January 2017.
        // Wallonia did not. Same country, same road class.
        assertEquals(70, be("BE-VLG", "unclassified", urban = false).kph)
        assertEquals(90, be("BE-WAL", "unclassified", urban = false).kph)
        assertEquals(70, be("BE-BRU", "unclassified", urban = false).kph)
    }

    @Test fun `Brussels is a thirty zone and the other regions are fifty`() {
        assertEquals(30, be("BE-BRU", "residential", urban = true).kph)
        assertEquals(50, be("BE-VLG", "residential", urban = true).kph)
        assertEquals(50, be("BE-WAL", "residential", urban = true).kph)
    }

    @Test fun `not knowing the Belgian region gives the lower value, weakly`() {
        // The one that cannot tell a Walloon driver they are legal when they
        // are not. 70 is Flanders' number and the lower of the two.
        val rural = be("BE", "unclassified", urban = false)
        assertEquals(70, rural.kph)
        assertEquals(Confidence.WEAK, rural.confidence)

        val urban = be("BE", "residential", urban = true)
        assertEquals(30, urban.kph)          // Brussels' number, the lowest
        assertEquals(Confidence.WEAK, urban.confidence)

        // ...and knowing it is not weak.
        assertEquals(Confidence.DERIVED, be("BE-VLG", "residential", true).confidence)
    }

    @Test fun `a Belgian dual carriageway outside town is 120 in both regions`() {
        assertEquals(120, be("BE-VLG", "primary", urban = false, dual = true).kph)
        assertEquals(120, be("BE-WAL", "primary", urban = false, dual = true).kph)
    }

    @Test fun `motorways are 120 and do not need the region`() {
        assertEquals(120, be("BE", "motorway", urban = null).kph)
        assertEquals(120, be("BE-WAL", "motorway_link", urban = null).kph)
        assertEquals(Confidence.DERIVED, be("BE", "motorway", null).confidence)
    }

    // ---- the honest-uncertainty cases --------------------------------------

    @Test fun `France rural returns the lower of 80 and 90, and admits it`() {
        // 90 -> 80 by décret of 15 June 2018, then the LOM of December 2019 let
        // departments revert; ~33,400 km across 37 departments had by 2021.
        // Not derivable from a country code, so: the lower value, marked weak.
        val r = SpeedDefaults.implied("FR", "secondary", urban = false)
        assertEquals(80, r.kph)
        assertEquals(Confidence.WEAK, r.confidence)

        // Urban France has no such ambiguity.
        val u = SpeedDefaults.implied("FR", "residential", urban = true)
        assertEquals(50, u.kph)
        assertEquals(Confidence.DERIVED, u.confidence)
    }

    @Test fun `the Dutch motorway limit follows the clock`() {
        // Signed 100 from 06:00 to 19:00 since 16 March 2020; the statutory 130
        // applies outside those hours and is deliberately not signed.
        assertEquals(100, SpeedDefaults.implied("NL", "motorway", null, localHour = 6).kph)
        assertEquals(100, SpeedDefaults.implied("NL", "motorway", null, localHour = 13).kph)
        assertEquals(100, SpeedDefaults.implied("NL", "motorway", null, localHour = 18).kph)
        assertEquals(130, SpeedDefaults.implied("NL", "motorway", null, localHour = 19).kph)
        assertEquals(130, SpeedDefaults.implied("NL", "motorway", null, localHour = 3).kph)
        // Both are weak: a handful of stretches went back to 130 by day in 2025.
        assertEquals(Confidence.WEAK,
            SpeedDefaults.implied("NL", "motorway", null, localHour = 13).confidence)
    }

    @Test fun `a German autobahn is derestricted, not unknown`() {
        val r = SpeedDefaults.implied("DE", "motorway", null)
        assertEquals(SpeedDefaults.DERESTRICTED, r.kph)
        assertTrue(r.known)
        assertEquals(100, SpeedDefaults.implied("DE", "secondary", urban = false).kph)
        assertEquals(50, SpeedDefaults.implied("DE", "residential", urban = true).kph)
    }

    @Test fun `Luxembourg`() {
        assertEquals(50, SpeedDefaults.implied("LU", "residential", true).kph)
        assertEquals(90, SpeedDefaults.implied("LU", "secondary", false).kph)
        assertEquals(130, SpeedDefaults.implied("LU", "motorway", null).kph)
    }

    // ---- refusing to answer ------------------------------------------------

    @Test fun `an unknown country produces nothing rather than a plausible number`() {
        assertFalse(SpeedDefaults.implied("PL", "residential", true).known)
        assertFalse(SpeedDefaults.implied(null, "residential", true).known)
        assertFalse(SpeedDefaults.implied("", "residential", true).known)
    }

    @Test fun `service roads and tracks get no default at all`() {
        // A car park aisle and a farm track are not "the urban default", and a
        // sign reading 50 in a supermarket yard is worse than a blank.
        assertFalse(be("BE-VLG", "service", urban = true).known)
        assertFalse(be("BE-VLG", "track", urban = false).known)
    }

    @Test fun `an unclassified road with no built-up answer stays blank`() {
        // 21,467 km of Belgian unclassified road has no maxspeed, and the class
        // covers both a village high street and a lane between two fields.
        // Guessing is how you show 70 in a 30 zone.
        assertFalse(be("BE-VLG", "unclassified", urban = null).known)
        assertFalse(be("BE-VLG", "tertiary", urban = null).known)
    }

    @Test fun `road classes that answer the built-up question themselves`() {
        assertEquals(true, SpeedDefaults.impliedUrban("residential"))
        assertEquals(true, SpeedDefaults.impliedUrban("living_street"))
        assertEquals(false, SpeedDefaults.impliedUrban("trunk"))
        assertEquals(false, SpeedDefaults.impliedUrban("motorway"))
        assertNull(SpeedDefaults.impliedUrban("unclassified"))
        assertNull(SpeedDefaults.impliedUrban("tertiary"))

        // ...and a residential street needs no urban flag from the caller.
        assertEquals(50, be("BE-VLG", "residential", urban = null).kph)
        // but it is weaker than being told.
        assertEquals(Confidence.WEAK, be("BE-VLG", "residential", urban = null).confidence)
        assertEquals(Confidence.DERIVED, be("BE-VLG", "residential", urban = true).confidence)
    }

    @Test fun `living streets are 20, or 15 in the Netherlands`() {
        assertEquals(20, be("BE-VLG", "living_street", null).kph)
        assertEquals(20, SpeedDefaults.implied("FR", "living_street", null).kph)
        assertEquals(15, SpeedDefaults.implied("NL", "living_street", null).kph)
    }

    // ---- OSM's own scheme tags ---------------------------------------------

    @Test fun `scheme tags carry both the region and the built-up answer`() {
        assertEquals("BE-VLG" to true, SpeedDefaults.fromSchemeTag("BE-VLG:urban"))
        assertEquals("BE-WAL" to false, SpeedDefaults.fromSchemeTag("BE-WAL:rural"))
        assertEquals("BE-BRU" to true, SpeedDefaults.fromSchemeTag("BE-BRU:urban"))
        // A zone is always built-up whatever it is called.
        assertEquals("BE" to true, SpeedDefaults.fromSchemeTag("BE:zone30"))
        assertEquals("DE" to true, SpeedDefaults.fromSchemeTag("DE:zone:30"))
        assertEquals("FR" to false, SpeedDefaults.fromSchemeTag("FR:motorway"))
    }

    @Test fun `a scheme tag that means nothing yields nothing`() {
        assertEquals(null to null, SpeedDefaults.fromSchemeTag(null))
        assertEquals(null to null, SpeedDefaults.fromSchemeTag(""))
        assertEquals(null to null, SpeedDefaults.fromSchemeTag("sign"))
        // "sign" and "survey" are common and say nothing about region or urban.
        val (region, urban) = SpeedDefaults.fromSchemeTag("survey")
        assertNull(region)
        assertNull(urban)
    }

    @Test fun `every derived value is a legal number, never an interpolation`() {
        // A limit is one of a small set of legal values. If this file ever
        // returns 63 or 85 something has gone badly wrong.
        val legal = setOf(-1, 15, 20, 30, 50, 70, 80, 90, 100, 110, 120, 130)
        val regions = listOf("BE-VLG", "BE-WAL", "BE-BRU", "BE", "FR", "NL", "DE", "LU")
        val classes = listOf("motorway", "motorway_link", "trunk", "primary", "secondary",
            "tertiary", "unclassified", "residential", "living_street", "service", "track")
        var produced = 0
        for (r in regions) for (h in classes) for (u in listOf(true, false, null))
            for (d in listOf(true, false)) for (hr in 0..23) {
                val res = SpeedDefaults.implied(r, h, u, d, hr)
                if (res.known) {
                    assertTrue("$r/$h/urban=$u/dual=$d/${hr}h gave ${res.kph}",
                        res.kph in legal)
                    produced++
                }
            }
        assertTrue("expected a lot of coverage, got $produced", produced > 2000)
    }

    // ---- forRoad: the entry point both drive modes share --------------------

    private fun road(kind: String, scheme: String = "", lit: Boolean? = null) = RoadWay(
        id = 1L,
        pts = arrayOf(doubleArrayOf(50.80, 4.35), doubleArrayOf(50.81, 4.35)),
        name = "TEST STREET", ref = "", limitKph = 0, kind = kind, onewayDir = 0,
        schemeTag = scheme, lit = lit
    )

    private fun window(vararg roads: RoadWay) =
        Area(50.80, 4.35, 3000.0, roads.toList(), emptyList(), 0L)

    @Test fun `forRoad tells a derestricted road apart from an unknown one`() {
        // The reason forRoad returns Int? and not Int. Three outcomes, and a
        // plain Int can only carry two of them: the moment "unknown" needs a
        // number, the obvious caller-side test becomes `> 0` and every German
        // autobahn reads as missing data -- the one road class whose legal
        // default is never in doubt.
        val autobahn = SpeedDefaults.forRoad(road("motorway"), null, "DE", 12)
        val nothing = SpeedDefaults.forRoad(road("unclassified"), null, "BE", 12)
        val street = SpeedDefaults.forRoad(road("residential", "BE-VLG:urban"), null, "BE", 12)

        assertEquals(SpeedDefaults.DERESTRICTED, autobahn)   // an answer
        assertNull(nothing)                                  // no answer
        assertEquals(50, street)                             // an ordinary answer

        // All three are distinguishable, which is the whole contract.
        assertNotNull(autobahn)
        assertNotEquals(autobahn, nothing)
        assertNotEquals(autobahn, street)
        assertTrue("derestricted must stay negative, not become 0 or 999",
            autobahn!! < 0)
    }

    @Test fun `forRoad never returns the UNKNOWN sentinel`() {
        // UNKNOWN is Result's way of saying nothing; forRoad's way is null.
        // A 0 leaking out would be shown as a speed limit of zero.
        val regions = listOf("BE-VLG", "BE-WAL", "BE-BRU", "BE", "FR", "NL", "DE", "LU", "PL")
        val classes = listOf("motorway", "motorway_link", "trunk", "primary", "secondary",
            "tertiary", "unclassified", "residential", "living_street", "service", "track")
        for (c in regions) for (k in classes) for (lit in listOf(true, false, null))
            for (h in listOf(3, 13, 22)) {
                val v = SpeedDefaults.forRoad(road(k, lit = lit), null, c, h)
                assertNotEquals("$c/$k/lit=$lit/${h}h returned the unknown sentinel",
                    SpeedDefaults.UNKNOWN, v)
            }
    }

    @Test fun `forRoad prefers the road's own tag to the window vote and the country`() {
        // Three sources, least certain last. A road that says which region it
        // is in outranks a vote of its neighbours, which outranks the country.
        val flemishWindow = window(road("residential", "BE-VLG:urban"))
        assertEquals("BE-VLG", flemishWindow.regionCode)

        // The road says Wallonia while the window says Flanders: 90, not 70.
        assertEquals(90, SpeedDefaults.forRoad(
            road("unclassified", "BE-WAL:rural"), flemishWindow, "BE", 12))

        // No tag on this road, so the window settles it: Flanders' 70.
        assertEquals(70, SpeedDefaults.forRoad(
            road("unclassified", lit = false), flemishWindow, "BE", 12))

        // Neither: the bare country, which for Belgium means the lower value.
        assertEquals(70, SpeedDefaults.forRoad(
            road("unclassified", lit = false), window(), "BE", 12))
    }

    @Test fun `forRoad falls back to lit for the built-up question`() {
        // `tertiary` says nothing about being built-up, so without lighting
        // there is no answer at all -- and with it, there is.
        assertNull(SpeedDefaults.forRoad(road("tertiary", "BE-VLG:x"), null, "BE", 12))
        assertEquals(50, SpeedDefaults.forRoad(
            road("tertiary", "BE-VLG:x", lit = true), null, "BE", 12))
        assertEquals(70, SpeedDefaults.forRoad(
            road("tertiary", "BE-VLG:x", lit = false), null, "BE", 12))

        // The tag's own answer beats `lit`: a lit rural road is still rural.
        assertEquals(70, SpeedDefaults.forRoad(
            road("tertiary", "BE-VLG:rural", lit = true), null, "BE", 12))
    }

    @Test fun `forRoad says nothing when nothing names a country`() {
        assertNull(SpeedDefaults.forRoad(road("residential"), null, null, 12))
        assertNull(SpeedDefaults.forRoad(road("residential"), window(), null, 12))
        // A country with no table entry is the same kind of silence.
        assertNull(SpeedDefaults.forRoad(road("residential"), null, "PL", 12))
        // ...but a scheme tag alone is enough to answer without a country.
        assertEquals(50, SpeedDefaults.forRoad(
            road("residential", "BE-VLG:urban"), null, null, 12))
    }

    @Test fun `forRoad refuses yards and farm tracks`() {
        assertNull(SpeedDefaults.forRoad(road("service", "BE-VLG:urban"), null, "BE", 12))
        assertNull(SpeedDefaults.forRoad(road("track", "BE-VLG:rural"), null, "BE", 12))
    }

    @Test fun `forRoad passes the clock through to the Dutch motorway limit`() {
        assertEquals(100, SpeedDefaults.forRoad(road("motorway"), null, "NL", 13))
        assertEquals(130, SpeedDefaults.forRoad(road("motorway"), null, "NL", 22))
    }
}
