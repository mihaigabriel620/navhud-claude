package com.mihai.navhud

import com.mihai.navhud.alerts.CameraPolicy
import com.mihai.navhud.nav.Area
import com.mihai.navhud.nav.RoadWay
import com.mihai.navhud.nav.SpeedDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Free driving on a road nobody tagged.
 *
 * Half the drivable road length in Belgium has no `maxspeed` in OpenStreetMap,
 * and it is not spread evenly — measured by length on 2026-09-07, motorways are
 * 99.5 % tagged and residential streets 48.6 %. So the gap sits exactly where
 * the limit is least obvious from the road itself and most likely to be
 * enforced, and until now the display simply showed nothing there.
 *
 * A legal default is not a guess: a road with no sign *is* at the limit the law
 * sets for its kind. What has to be right is picking which default, and being
 * honest when that pick rests on an assumption.
 */
class DerivedLimitTest {

    private val lat0 = 50.8000
    private val lon0 = 4.3500

    private fun road(
        kind: String,
        limit: Int = 0,
        scheme: String = "",
        lit: Boolean? = null,
        name: String = "TEST STREET"
    ) = RoadWay(
        id = 1L,
        pts = Array(8) { doubleArrayOf(lat0 + it * 0.0009, lon0) },
        name = name, ref = "", limitKph = limit, kind = kind, onewayDir = 0,
        schemeTag = scheme, lit = lit
    )

    private fun area(vararg roads: RoadWay) =
        Area(lat0 + 0.003, lon0, 3000.0, roads.toList(), emptyList(), 0L)

    /** Drive north up the road and read the limit off the frame. */
    private fun drive(
        a: Area,
        country: String? = "BE",
        hour: Int = 12
    ): Pair<Int, Boolean> {
        val t = FreeTracker()
        t.area = a
        var frame = t.update(
            lat = lat0 + 0.0018, lon = lon0, speedMps = 13.9f, bearingDeg = 0f,
            hasFix = true, nowMs = 1_000L, policy = CameraPolicy.EXACT,
            country = country, localHour = hour
        )
        // A second tick, because the matcher needs a heading history before it
        // trusts a road, and the first frame is the one that establishes it.
        frame = t.update(
            lat = lat0 + 0.0027, lon = lon0, speedMps = 13.9f, bearingDeg = 0f,
            hasFix = true, nowMs = 1_500L, policy = CameraPolicy.EXACT,
            country = country, localHour = hour
        )
        return frame.limitKph to
            ((frame.flags and HudFrame.FLAG_LOW_CONF) != 0)
    }

    // ---- the point of the whole thing --------------------------------------

    @Test fun `an untagged Flemish residential street shows 50 instead of nothing`() {
        val (limit, lowConf) = drive(area(road("residential", scheme = "BE-VLG:urban")))
        assertEquals(50, limit)
        assertTrue("a derived limit must be marked low confidence", lowConf)
    }

    @Test fun `the same street in Wallonia and Brussels`() {
        assertEquals(50, drive(area(road("residential", scheme = "BE-WAL:urban"))).first)
        assertEquals(30, drive(area(road("residential", scheme = "BE-BRU:urban"))).first)
    }

    @Test fun `an untagged rural road is 70 in Flanders and 90 in Wallonia`() {
        // The twenty km/h that a country code alone cannot tell you. Flanders
        // dropped 90 to 70 on 1 January 2017; Wallonia did not.
        assertEquals(70, drive(area(road("unclassified", scheme = "BE-VLG:rural"))).first)
        assertEquals(90, drive(area(road("unclassified", scheme = "BE-WAL:rural"))).first)
    }

    // ---- where the region comes from when the road does not say ------------

    @Test fun `one tagged road in the window reveals the region for all of them`() {
        // This is the trick that makes the whole thing work without a network
        // call: ~148,000 Belgian ways carry source:maxspeed or zone:traffic,
        // every value starts with the region code, and a fetch window is
        // kilometres across while a region is hundreds of kilometres across.
        // So a neighbour that happens to be tagged settles it for the street
        // you are actually on.
        // A tertiary road with lit=no: the class says nothing about built-up,
        // but the lighting does, so only the region is still missing. An
        // *unclassified* road here would stay blank whatever the region --
        // that refusal is tested below and it is deliberate.
        val untagged = road("tertiary", lit = false, name = "UNTAGGED LANE")
        val neighbour = RoadWay(
            id = 2L,
            pts = Array(4) { doubleArrayOf(lat0 + it * 0.0009, lon0 + 0.02) },
            name = "SOMEWHERE ELSE", ref = "", limitKph = 50, kind = "residential",
            onewayDir = 0, schemeTag = "BE-WAL:urban", lit = null
        )
        val a = area(untagged, neighbour)
        assertEquals("BE-WAL", a.regionCode)
        // ...and the untagged rural lane therefore gets Wallonia's 90, not
        // Flanders' 70.
        assertEquals(90, drive(a).first)
    }

    @Test fun `with no region hint at all the lower value is used`() {
        // Nothing in the window says which region. Showing 90 where the truth
        // is 70 tells a Flemish driver they are legal when they are not; the
        // reverse only makes the display pessimistic.
        val a = area(road("tertiary", lit = false))
        assertEquals(null, a.regionCode)
        assertEquals(70, drive(a, country = "BE").first)

        // Same for the urban value: 30 is Brussels' and the lowest of the
        // three, so an unknown region takes it.
        val urban = area(road("residential"))
        assertEquals(30, drive(urban, country = "BE").first)
    }

    @Test fun `a plain BE region vote is ignored`() {
        // "BE:zone30" names no region, so it must not out-vote nothing and
        // then be treated as if it had.
        val a = area(road("residential", scheme = "BE:zone30"))
        assertEquals(null, a.regionCode)
    }

    // ---- refusing to invent ------------------------------------------------

    @Test fun `a posted limit always wins over a derived one`() {
        val (limit, lowConf) = drive(area(road("residential", limit = 30,
            scheme = "BE-VLG:urban")))
        assertEquals(30, limit)
        assertFalse("a posted limit is not low confidence", lowConf)
    }

    @Test fun `an unclassified road with nothing to go on stays blank`() {
        // The class covers both a village high street and a lane between two
        // fields. Guessing here is how you show 70 in a 30 zone.
        val a = area(road("unclassified", scheme = ""))
        // regionCode is null, so the country default applies -- but the
        // built-up question is still unanswered, and that is what blocks it.
        val t = FreeTracker()
        t.area = a
        val f = t.update(lat0 + 0.0018, lon0, 13.9f, 0f, true, 1_000L,
            CameraPolicy.EXACT, country = null)
        assertEquals(0, f.limitKph)
    }

    @Test fun `lit=yes is enough to call a road built-up`() {
        val dark = drive(area(road("tertiary", scheme = "BE-VLG:rural", lit = false)))
        assertEquals(70, dark.first)
        val lit = drive(area(road("tertiary", lit = true, scheme = "BE-VLG:urban")))
        assertEquals(50, lit.first)
    }

    @Test fun `a service road never gets a default`() {
        // A supermarket yard is not "the urban default", and a sign reading 50
        // in a car park is worse than a blank one.
        assertEquals(0, drive(area(road("service", scheme = "BE-VLG:urban"))).first)
    }

    @Test fun `an unknown country produces nothing rather than a plausible number`() {
        assertEquals(0, drive(area(road("residential")), country = null).first)
        assertEquals(0, drive(area(road("residential")), country = "PL").first)
    }

    // ---- the neighbouring countries ---------------------------------------

    @Test fun `a Dutch motorway follows the clock`() {
        // Signed 100 from 06:00 to 19:00 since 16 March 2020, statutory 130
        // outside those hours and deliberately unsigned.
        assertEquals(100, drive(area(road("motorway")), country = "NL", hour = 13).first)
        assertEquals(130, drive(area(road("motorway")), country = "NL", hour = 22).first)
    }

    @Test fun `a German autobahn reads as derestricted, not as unknown`() {
        val (limit, lowConf) = drive(area(road("motorway")), country = "DE")
        assertEquals(-1, limit)          // HudFrame's "no limit" value
        assertNotEquals(0, limit)        // ...and specifically not "unknown"
        assertTrue("still a derived limit, so still low confidence", lowConf)

        // Same road through the shared function: null is the only "nothing
        // here", so -1 has to come back as a value. A forRoad that returned a
        // plain Int would have to spend 0 on "unknown" and the caller would
        // test `> 0` -- which is how the autobahn became a blank sign.
        val autobahn = SpeedDefaults.forRoad(road("motorway"), null, "DE", 12)
        assertEquals(SpeedDefaults.DERESTRICTED, autobahn)
        assertNull(SpeedDefaults.forRoad(road("unclassified"), null, "DE", 12))
    }

    @Test fun `a derestricted road is never over the limit`() {
        // -1 is a sign, not a number. Comparing speed against it -- which is
        // what happens the moment anything treats the three outcomes as two --
        // flags 180 km/h on an autobahn as speeding.
        val t = FreeTracker()
        t.area = area(road("motorway"))
        t.update(lat0 + 0.0018, lon0, 50.0f, 0f, true, 1_000L,
            CameraPolicy.EXACT, country = "DE")
        val f = t.update(lat0 + 0.0027, lon0, 50.0f, 0f, true, 1_500L,
            CameraPolicy.EXACT, country = "DE")   // 180 km/h
        assertEquals(-1, f.limitKph)
        assertEquals(0, f.flags and HudFrame.FLAG_OVER_LIMIT)
    }

    @Test fun `a French rural road takes the lower of 80 and 90`() {
        // 90 -> 80 in 2018, then 37 departments reverted under the LOM. Not
        // derivable from a country code, so take the value that cannot tell
        // somebody they are legal when they are not.
        assertEquals(80, drive(area(road("secondary", lit = false)), country = "FR").first)
    }

    // ---- the failure mode I was most worried about -------------------------

    @Test fun `a derived limit does not survive onto the next road`() {
        // The hold exists so the sign does not flicker off at every untagged
        // junction. But a derived value is only as good as the road it came
        // from, so holding it across a road change would carry a residential
        // 50 onto a motorway slip road.
        val t = FreeTracker()
        t.area = area(road("residential", scheme = "BE-VLG:urban"))
        t.update(lat0 + 0.0018, lon0, 13.9f, 0f, true, 1_000L,
            CameraPolicy.EXACT, country = "BE")
        val onStreet = t.update(lat0 + 0.0027, lon0, 13.9f, 0f, true, 1_500L,
            CameraPolicy.EXACT, country = "BE")
        assertEquals(50, onStreet.limitKph)

        // Now drive somewhere with no road at all under us.
        t.area = area()
        val nowhere = t.update(lat0 + 0.5, lon0 + 0.5, 13.9f, 0f, true, 2_000L,
            CameraPolicy.EXACT, country = "BE")
        assertEquals("a derived limit must not be held across roads",
            0, nowhere.limitKph)
    }
}
