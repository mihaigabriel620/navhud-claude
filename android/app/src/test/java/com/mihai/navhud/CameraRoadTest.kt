package com.mihai.navhud

import com.mihai.navhud.alerts.SpeedCameras
import com.mihai.navhud.nav.Area
import com.mihai.navhud.nav.RoadWay
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "It tells you about a camera on a road you are not even going on."
 *
 * A camera sits at the roadside of the carriageway it watches, so distance
 * from our route cannot separate "beside my road" from "on the next street
 * over" — in a town both are a few tens of metres. The discriminator is which
 * road the camera is *nearest to*.
 */
class CameraRoadTest {

    /** Two parallel streets 30 m apart, running north. */
    private fun twoStreets(): Area {
        val west = Array(3) { i -> doubleArrayOf(50.8000 + i * 0.0009, 4.3000) }
        val east = Array(3) { i -> doubleArrayOf(50.8000 + i * 0.0009, 4.30043) }
        return Area(
            centreLat = 50.8009, centreLon = 4.3002, radiusM = 500.0, fetchedAtMs = 0L,
            roads = listOf(
                RoadWay(id = 1, pts = west, name = "Westerstraat", ref = "",
                        limitKph = 50, kind = "residential", onewayDir = 0),
                RoadWay(id = 2, pts = east, name = "Oosterstraat", ref = "",
                        limitKph = 50, kind = "residential", onewayDir = 0)
            ),
            cameras = emptyList()
        )
    }

    @Test
    fun `a camera hugging the other street is not ours`() {
        val area = twoStreets()
        // 4 m west of the eastern street, so ~26 m from the western one, which
        // is the route. Under the old 28 m tolerance this snapped onto us.
        val camLat = 50.8009
        val camLon = 4.30038
        val routeCross = Geo.haversine(camLat, camLon, camLat, 4.3000)
        assertTrue("setup: it should be within the old tolerance", routeCross in 20.0..30.0)
        assertFalse(SpeedCameras.onOurRoad(area, camLat, camLon, routeCross))
    }

    @Test
    fun `a camera at the side of our own road is ours`() {
        val area = twoStreets()
        // 6 m east of the western street: at its kerb, and far from the other.
        val camLat = 50.8009
        val camLon = 4.30008
        val routeCross = Geo.haversine(camLat, camLon, camLat, 4.3000)
        assertTrue("setup", routeCross < 10.0)
        assertTrue(SpeedCameras.onOurRoad(area, camLat, camLon, routeCross))
    }

    @Test
    fun `a near tie keeps the camera rather than throwing it away`() {
        val area = twoStreets()
        // Dead between the two: a service road running alongside a main road
        // looks like this, and dropping the camera would be the worse mistake.
        val camLat = 50.8009
        val camLon = 4.300215
        val routeCross = Geo.haversine(camLat, camLon, camLat, 4.3000)
        assertTrue(SpeedCameras.onOurRoad(area, camLat, camLon, routeCross))
    }

    @Test
    fun `with no road network loaded the camera is trusted`() {
        assertTrue(SpeedCameras.onOurRoad(null, 50.8, 4.3, 5.0))
    }

    @Test
    fun `the route corridor fits a generalised route line, not a city block`() {
        // Was capped at 15 m, which dropped cameras on our own road: a routed
        // polyline sits 5-12 m off its carriageway. At 35 m the street next
        // door can get into the corridor -- and the first test above shows
        // onOurRoad rejecting it there, which is now where that job is done.
        assertTrue(SpeedCameras.MATCH_TOLERANCE_M in 30.0..40.0)
    }
}
