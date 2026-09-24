package com.mihai.navhud

import com.mihai.navhud.nav.LatLon
import com.mihai.navhud.nav.MapboxProvider
import com.mihai.navhud.nav.NavException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * No signal must never crash anything or strand the driver.
 *
 * Every way a request can fail -- no network, a timeout, an error page, a body
 * that is not JSON, a router that found nothing -- has to come out of the
 * provider as the one exception the service catches, and the service retries
 * on a back-off rather than giving up or spinning. The Overpass side of this
 * is in AreaCacheTest.
 */
class NetworkFailureTest {

    private val from = LatLon(50.85, 4.35)
    private val to = LatLon(50.80, 4.40)

    private fun provider(http: (String) -> String) = MapboxProvider("pk.test", "en", http)

    private fun routesFail(http: (String) -> String): NavException {
        try {
            provider(http).routes(from, to, alternatives = true, headingDeg = 90.0)
        } catch (e: NavException) {
            return e
        } catch (e: Throwable) {
            fail("escaped as ${e::class.simpleName}: ${e.message}")
        }
        fail("a failure must not look like a route")
        throw AssertionError()
    }

    @Test fun `no network is a NavException`() {
        routesFail { throw java.net.UnknownHostException("api.mapbox.com") }
        routesFail { throw java.net.SocketTimeoutException("read timed out") }
        routesFail { throw java.io.IOException("connection reset") }
    }

    @Test fun `an error page or garbage is a NavException, not a JSONException`() {
        routesFail { "<html>502 Bad Gateway</html>" }
        routesFail { "" }
        routesFail { """{"code":"NoRoute","message":"No route found"}""" }
        routesFail { """{"code":"Ok","routes":[]}""" }
        routesFail { """{"code":"Ok","routes":[{"geometry":""}]}""" }
    }

    @Test fun `the request asks for the car's direction and alternatives`() {
        var url = ""
        runCatching { provider { u -> url = u; throw java.io.IOException("x") }
            .routes(from, to, alternatives = true, headingDeg = 93.4) }
        assertTrue(url, url.contains("alternatives=true"))
        assertTrue(url, url.contains("bearings=93,45;"))
    }

    @Test fun `the country lookup fails quietly`() {
        assertNull(provider { throw java.net.UnknownHostException("x") }.countryAt(from))
        assertNull(provider { "not json" }.countryAt(from))
        assertNull(provider { """{"features":[]}""" }.countryAt(from))
    }

    @Test fun `the country lookup returns a code or nothing, never a name`() {
        assertEquals("BE", provider {
            """{"features":[{"text":"Belgique","properties":{"short_code":"be"}}]}"""
        }.countryAt(from))
        // No short code: the name used to come back, and "Belgique" is not "BE".
        assertNull(provider {
            """{"features":[{"text":"Belgique","properties":{}}]}"""
        }.countryAt(from))
    }

    @Test fun `a failed reroute backs off and never gives up`() {
        val waits = (0..7).map { RerouteRule.retryDelayMs(it) }
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 20000L, 20000L, 20000L, 20000L), waits)
        assertEquals(2000L, RerouteRule.retryDelayMs(-1))
    }

    // ---- the country, from the route itself ----------------------------------

    /** Ten vertices ~100 m apart; the border between vertex 6 and the rest. */
    private fun borderRoute(): String {
        val pts = Array(10) { doubleArrayOf(50.40 + it * 0.0009, 6.10) }
        val geom = encode(pts)
        return """
        {"code":"Ok","routes":[{"distance":900.0,"duration":60.0,"geometry":"$geom",
          "legs":[{
            "admins":[{"iso_3166_1":"BE","iso_3166_1_alpha3":"BEL"},
                      {"iso_3166_1":"DE","iso_3166_1_alpha3":"DEU"}],
            "annotation":{"duration":[1,1,1,1,1,1,1,1,1]},
            "steps":[
              {"maneuver":{"type":"depart","location":[6.10,50.40]},"distance":600,
               "intersections":[{"geometry_index":0,"admin_index":0},
                                {"geometry_index":3,"admin_index":0}]},
              {"maneuver":{"type":"continue","location":[6.10,50.4054]},"distance":300,
               "intersections":[{"geometry_index":6,"admin_index":1}]}
            ]}]}]}
        """.trimIndent()
    }

    @Test fun `the country along the route comes from the router's admins`() {
        val r = provider { borderRoute() }.routes(from, to, alternatives = false).single()
        assertEquals(listOf("BE", "DE"), r.countryCodes)
        assertEquals("BE", r.countryAt(50.0))
        assertEquals("BE", r.countryAt(r.cum[5] + 10.0))
        assertEquals("DE", r.countryAt(r.cum[6] + 10.0))
        assertEquals("DE", r.countryAt(r.cum.last()))
    }

    @Test fun `a route with no admins knows no country, and says so`() {
        val body = borderRoute().replace(Regex("\"admins\":\\[[^]]*],"), "")
        val r = provider { body }.routes(from, to, alternatives = false).single()
        assertNull(r.countryAt(100.0))
    }

    private fun encode(pts: Array<DoubleArray>): String {
        val sb = StringBuilder()
        var lastLat = 0L
        var lastLon = 0L
        for (p in pts) {
            val lat = Math.round(p[0] * 1e6)
            val lon = Math.round(p[1] * 1e6)
            for (v in longArrayOf(lat - lastLat, lon - lastLon)) {
                var value = if (v < 0) (v shl 1).inv() else (v shl 1)
                while (value >= 0x20) {
                    sb.append((((value and 0x1f) or 0x20) + 63).toInt().toChar())
                    value = value shr 5
                }
                sb.append((value + 63).toInt().toChar())
            }
            lastLat = lat; lastLon = lon
        }
        return sb.toString()
    }
}
