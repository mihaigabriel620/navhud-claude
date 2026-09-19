package com.mihai.navhud

import com.mihai.navhud.map.MapIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test that should have existed before 1.11 shipped.
 *
 * Two style-id constants drifted onto the same string, MapLibre threw
 * `CannotAddLayerException` from inside the style callback, and the app could
 * not be opened at all — on the road, with no way back except a new APK. The
 * mistake was visible in a thirteen-line column of constants, which is exactly
 * the length at which an eye stops reading and a test does not.
 */
class MapIdsTest {

    @Test
    fun `every style id is unique`() {
        val all = MapIds.all
        val duplicates = all.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("duplicate style ids: $duplicates", duplicates.isEmpty())
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `layers, sources and images do not share names with each other`() {
        // MapLibre keeps three separate namespaces, so this is not strictly
        // required — but a layer and a source called the same thing is a
        // reading hazard in every stack trace they ever appear in.
        for (l in MapIds.layers) {
            assertTrue("$l is also a source", l !in MapIds.sources)
            assertTrue("$l is also an image", l !in MapIds.images)
        }
        for (s in MapIds.sources) {
            assertTrue("$s is also an image", s !in MapIds.images)
        }
    }

    @Test
    fun `the lists cover every constant declared on the object`() {
        // A new id added to the object but forgotten in `all` would be
        // invisible to the uniqueness test above, which is the one way this
        // safety net could quietly stop working.
        val declared = MapIds::class.java.declaredFields
            .filter { it.type == String::class.java }
            .mapNotNull { it.isAccessible = true; it.get(MapIds) as? String }
            .filter { it.startsWith("navhud-") }
            .toSet()
        val listed = MapIds.all.toSet()
        assertEquals("ids declared but not in MapIds.all: ${declared - listed}",
            emptySet<String>(), declared - listed)
    }

    @Test
    fun `ids are namespaced so they cannot collide with the base style`() {
        // The E60 style has its own layers -- road-motorway, water, building.
        // Anything we add has to be obviously ours.
        for (id in MapIds.all) assertTrue(id, id.startsWith("navhud-"))
    }
}
