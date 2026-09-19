package com.mihai.navhud

import com.mihai.navhud.nav.GeoResult
import com.mihai.navhud.nav.Geocoders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address parser and the response parsers, without a network. The first
 * test is the exact query that failed on the road — it stays here so search
 * can never quietly regress on the address format Belgians actually type.
 */
class GeocodersTest {

    // ---- the parser --------------------------------------------------------

    @Test fun `the exact query that found nothing`() {
        val p = Geocoders.parseAddress("avenue de la couronne 329A 1050 ixelles")!!
        assertEquals("avenue de la couronne", p.street)
        assertEquals("329A", p.houseNumber)
        assertEquals("1050", p.postcode)
        assertEquals("ixelles", p.city)
    }

    @Test fun `belgian order with a comma`() {
        val p = Geocoders.parseAddress("Rue de la Loi 16, 1000 Bruxelles")!!
        assertEquals("Rue de la Loi", p.street)
        assertEquals("16", p.houseNumber)
        assertEquals("1000", p.postcode)
        assertEquals("Bruxelles", p.city)
    }

    @Test fun `commune before the postcode`() {
        val p = Geocoders.parseAddress("avenue de la couronne 329a ixelles 1050")!!
        assertEquals("avenue de la couronne", p.street)
        assertEquals("329A", p.houseNumber)
        assertEquals("1050", p.postcode)
        assertEquals("ixelles", p.city)
    }

    @Test fun `french habit, number first`() {
        val p = Geocoders.parseAddress("16 rue de la loi 1000 bruxelles")!!
        assertEquals("16", p.houseNumber)
        assertEquals("1000", p.postcode)
        assertEquals("bruxelles", p.city)
        assertTrue(p.street.startsWith("rue de la loi"))
    }

    @Test fun `no postcode, city after the number`() {
        val p = Geocoders.parseAddress("Calea Victoriei 120 Bucuresti")!!
        assertEquals("Calea Victoriei", p.street)
        assertEquals("120", p.houseNumber)
        assertNull(p.postcode)
        assertEquals("Bucuresti", p.city)
    }

    @Test fun `german five-digit postcode`() {
        val p = Geocoders.parseAddress("Hauptstraße 5, 10115 Berlin")!!
        assertEquals("Hauptstraße", p.street)
        assertEquals("5", p.houseNumber)
        assertEquals("10115", p.postcode)
        assertEquals("Berlin", p.city)
    }

    @Test fun `a name search is not an address`() {
        assertNull(Geocoders.parseAddress("carrefour ixelles"))
        assertNull(Geocoders.parseAddress("gare centrale"))
        // postcode-and-commune with no street number: fuzzy engines' job
        assertNull(Geocoders.parseAddress("1050 ixelles"))
    }

    @Test fun `a four-digit token at the end with no commune is a house number`() {
        // "Chaussée de Wavre 1205" — 1205 is the house number, not a postcode.
        val p = Geocoders.parseAddress("Chaussée de Wavre 1205")!!
        assertEquals("1205", p.houseNumber)
        assertNull(p.postcode)
    }

    // ---- response parsing --------------------------------------------------

    @Test fun `photon geojson parses, lon-lat order and all`() {
        val body = """
        {"features":[
          {"geometry":{"coordinates":[4.3781,50.8232],"type":"Point"},
           "properties":{"housenumber":"329A","street":"Avenue de la Couronne",
                         "postcode":"1050","city":"Ixelles","country":"Belgium"}},
          {"geometry":{"coordinates":[4.35,50.85],"type":"Point"},
           "properties":{"name":"Carrefour","city":"Ixelles"}}
        ]}
        """.trimIndent()
        val r = Geocoders.parsePhoton(body)
        assertEquals(2, r.size)
        assertEquals("Avenue de la Couronne 329A, 1050 Ixelles", r[0].label)
        assertEquals(50.8232, r[0].lat, 1e-9)
        assertEquals(4.3781, r[0].lon, 1e-9)
        assertTrue(r[0].precise)
        assertEquals("Carrefour, Ixelles", r[1].label)
        assertTrue(!r[1].precise)
    }

    @Test fun `nominatim jsonv2 parses its string coordinates`() {
        val body = """
        [{"lat":"50.82316","lon":"4.37811","addresstype":"house",
          "display_name":"329A, Avenue de la Couronne, Ixelles, Bruxelles-Capitale, 1050, Belgique"},
         {"lat":"50.84","lon":"4.35","addresstype":"road",
          "display_name":"Avenue de la Couronne, Ixelles, Belgique"}]
        """.trimIndent()
        val r = Geocoders.parseNominatim(body)
        assertEquals(2, r.size)
        assertEquals(50.82316, r[0].lat, 1e-9)
        assertTrue(r[0].precise)
        assertTrue(!r[1].precise)
        // labels are trimmed, not the full seven-part display_name
        assertTrue(r[0].label.split(",").size <= 4)
    }

    @Test fun `google geocoding parses and flags street addresses`() {
        val body = """
        {"status":"OK","results":[
          {"formatted_address":"Avenue de la Couronne 329A, 1050 Ixelles, Belgium",
           "geometry":{"location":{"lat":50.82316,"lng":4.37811}},
           "types":["street_address"]}]}
        """.trimIndent()
        val r = Geocoders.parseGoogle(body)
        assertEquals(1, r.size)
        assertTrue(r[0].precise)
        assertEquals(50.82316, r[0].lat, 1e-9)
        // an error status yields nothing rather than garbage
        assertTrue(Geocoders.parseGoogle("""{"status":"REQUEST_DENIED"}""").isEmpty())
    }

    @Test fun `duplicates from two engines collapse to one`() {
        val a = GeoResult("Avenue de la Couronne 329A, 1050 Ixelles", 50.82316, 4.37811, "osm", true)
        val b = GeoResult("Avenue de la Couronne 329a, Ixelles", 50.82320, 4.37815, "photon", true)   // ~6 m away
        val c = GeoResult("Somewhere else", 50.9000, 4.5000, "photon", false)
        val out = Geocoders.dedupe(listOf(a, b, c))
        assertEquals(2, out.size)
        assertEquals("osm", out[0].source)      // first engine wins
    }
}
