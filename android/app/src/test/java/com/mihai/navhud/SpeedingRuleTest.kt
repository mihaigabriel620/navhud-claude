package com.mihai.navhud

import com.mihai.navhud.voice.English
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "It beeps all the time and the music stops." The over-limit warning now
 * wants a sustained excess, says so once, and keeps quiet for three minutes
 * unless the driver slowed down properly or the limit changed.
 */
class SpeedingRuleTest {

    /** Drives at 4 Hz from [fromMs] for [ms], returning the times it warned. */
    private fun SpeedingRule.drive(fromMs: Long, ms: Long, kph: Int, limit: Int): List<Long> {
        val out = ArrayList<Long>()
        var t = fromMs
        while (t < fromMs + ms) {
            if (update(t, kph, limit)) out.add(t)
            t += 250
        }
        return out
    }

    @Test fun `the margin grows with the limit`() {
        val r = SpeedingRule()
        assertEquals(10, r.marginFor(30))
        assertEquals(10, r.marginFor(50))
        assertEquals(20, r.marginFor(51))
        assertEquals(20, r.marginFor(70))
        assertEquals(20, r.marginFor(90))
        assertEquals(30, r.marginFor(91))
        assertEquals(30, r.marginFor(120))
    }

    @Test fun `only an excess beyond the margin counts`() {
        val r = SpeedingRule()
        assertTrue("60 in a 50 is within the margin", r.drive(0, 20_000, 60, 50).isEmpty())
        assertTrue("110 in a 90 is within the margin", r.drive(20_000, 20_000, 110, 90).isEmpty())
        assertTrue("150 in a 120 is within the margin", r.drive(40_000, 20_000, 150, 120).isEmpty())
        assertEquals(1, SpeedingRule().drive(0, 20_000, 61, 50).size)
        assertEquals(1, SpeedingRule().drive(0, 20_000, 111, 90).size)
        assertEquals(1, SpeedingRule().drive(0, 20_000, 151, 120).size)
    }

    @Test fun `it takes three seconds of speeding, not a wobble`() {
        val r = SpeedingRule()
        assertTrue(r.drive(0, 2_750, 65, 50).isEmpty())
        assertTrue("dropping back resets the clock", r.drive(2_750, 250, 55, 50).isEmpty())
        assertTrue(r.drive(3_000, 2_750, 65, 50).isEmpty())
        assertEquals(listOf(6_000L), r.drive(5_750, 1_000, 65, 50))
    }

    @Test fun `then three minutes of silence while still speeding`() {
        val r = SpeedingRule()
        val warned = r.drive(0, 200_000, 75, 50)
        assertEquals("once at 3 s, once more when the cooldown ends",
            listOf(3_000L, 183_000L), warned)
    }

    @Test fun `slowing to ten under for five seconds re-arms it early`() {
        val r = SpeedingRule()
        assertEquals(1, r.drive(0, 5_000, 65, 50).size)
        assertTrue("41 is not clearly under a 50", r.drive(5_000, 10_000, 41, 50).isEmpty())
        assertTrue("not re-armed by 41", r.drive(15_000, 5_000, 65, 50).isEmpty())
        assertTrue(r.drive(20_000, 4_750, 40, 50).isEmpty())     // 20.0-24.5 s: 4.5 s under
        assertTrue("4.5 s at 40 is not enough", r.drive(24_750, 5_000, 65, 50).isEmpty())
        assertTrue(r.drive(29_750, 5_250, 40, 50).isEmpty())     // 29.75-34.75 s: a full 5 s
        assertEquals("five seconds at 40 in a 50 re-arms it",
            listOf(38_000L), r.drive(35_000, 5_000, 65, 50))
    }

    @Test fun `a new speed limit re-arms it`() {
        val r = SpeedingRule()
        assertEquals(listOf(3_000L), r.drive(0, 10_000, 75, 50))
        // Into a 30 zone at the same speed: a warning is news again, after the
        // excess has lasted three seconds against the new limit.
        assertEquals(listOf(13_000L), r.drive(10_000, 10_000, 75, 30))
        // Same limit, still speeding: cooldown.
        assertTrue(r.drive(20_000, 10_000, 75, 30).isEmpty())
    }

    @Test fun `an unknown limit neither warns nor re-arms`() {
        val r = SpeedingRule()
        assertTrue("no limit, no warning at any speed", r.drive(0, 20_000, 180, 0).isEmpty())
        assertTrue("derestricted counts as unknown", r.drive(20_000, 20_000, 180, -1).isEmpty())
        assertEquals(listOf(43_000L), r.drive(40_000, 5_000, 75, 50))
        // A long gap with no limit is not "slowed down", and coming back to
        // the same 50 is not a new limit: still in the cooldown.
        assertTrue(r.drive(45_000, 30_000, 20, 0).isEmpty())
        assertTrue(r.drive(75_000, 10_000, 75, 50).isEmpty())
    }

    @Test fun `a gap in the limit does not stitch two short excesses into a warning`() {
        val r = SpeedingRule()
        assertTrue(r.drive(0, 2_000, 75, 50).isEmpty())
        assertTrue(r.drive(2_000, 5_000, 75, 0).isEmpty())
        assertTrue("2 s + 1 s is not 3 s continuous", r.drive(7_000, 1_000, 75, 50).isEmpty())
        assertEquals(listOf(10_000L), r.drive(8_000, 3_000, 75, 50))
    }

    @Test fun `no fix is not a speed`() {
        val r = SpeedingRule()
        assertTrue(r.drive(0, 10_000, -1, 50).isEmpty())
    }

    // ---- through VoiceGuide ------------------------------------------------

    @Test fun `one chime and one line per warning, nothing repeated`() {
        var now = 1_000_000L
        val speaker = FakeSpeaker()
        val g = VoiceGuide(null, English, speaker) { now }
        repeat(4 * 60) {                                  // a minute at 4 Hz, 75 in a 50
            g.onFrame(HudFrame(speedKph = 75, limitKph = 50), null)
            if (speaker.started.isNotEmpty()) g.onUtteranceDone(speaker.lastId)
            now += 250
        }
        assertEquals(listOf("Speed limit 50."), speaker.texts)
        assertEquals(VoiceGuide.EARCON_WARN, speaker.started[0].first.earcon)
        assertFalse(speaker.started.any { it.first.text.isEmpty() })
    }

    /**
     * The app swiped away: the voice keeps the warnings and nothing else. It
     * used to fall silent with the app, the over-limit warning included --
     * "if possible receive warning when I go over" (app 1.33).
     */
    @Test fun `with the app closed only the warnings are spoken`() {
        var now = 1_000_000L
        val speaker = FakeSpeaker()
        val g = VoiceGuide(null, English, speaker) { now }
        g.warningsOnly = true

        // Not with the app closed: a turn, roadworks, a level crossing.
        g.onFrame(HudFrame(speedKph = 40, limitKph = 50, maneuver = Man.RIGHT,
                           distToManeuverM = 140), 5_000L)
        g.onClosure(1_000, 7L)
        g.onRoadFeature(com.mihai.navhud.nav.RoadFeature.LEVEL_CROSSING, 1L)
        assertTrue(speaker.texts.toString(), speaker.texts.isEmpty())

        // Over the limit for long enough: said, once.
        repeat(4 * 5) {
            g.onFrame(HudFrame(speedKph = 75, limitKph = 50), null)
            if (speaker.started.isNotEmpty()) g.onUtteranceDone(speaker.lastId)
            now += 250
        }
        assertEquals(listOf("Speed limit 50."), speaker.texts)

        // And a camera.
        val cam = com.mihai.navhud.alerts.SpeedCamera(1, 0.0, 0.0, 0.0, 70, null,
            com.mihai.navhud.alerts.SpeedCamera.Kind.FIXED)
        g.announceCamera(com.mihai.navhud.alerts.CameraAlert(cam, 700, zoneMode = false))
        assertEquals(listOf("Speed limit 50.", "Speed camera in 700 metres, limit 70."),
            speaker.texts)
    }

    /** The same calls with the app open: the rest is spoken as before. */
    @Test fun `with the app open the level crossing is still spoken`() {
        val speaker = FakeSpeaker()
        val g = VoiceGuide(null, English, speaker) { 1_000_000L }
        g.onRoadFeature(com.mihai.navhud.nav.RoadFeature.LEVEL_CROSSING, 1L)
        assertEquals(1, speaker.texts.size)
    }
}
