package com.mihai.navhud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How often the voice speaks for one turn.
 *
 * The complaint from the drive was that it never stopped, and that it said the
 * same turn twice: "in two hundred metres, bear left" and then "bear left now"
 * nine metres further on. Three stages per maneuver became two, and two
 * suppression rules close the gaps that let one turn be announced twice in the
 * space of a breath, or two turns run into each other.
 *
 * VoiceGuide itself needs a Context and a live text-to-speech engine, so what
 * is asserted here is the cadence and the two rules, which are pure.
 */
class VoiceGuideTest {

    // The two windows, restated so that changing either constant has to be a
    // deliberate act rather than a quiet one.
    private val MIN_GAP_MS = 4_000L
    private val PREPARE_QUIET_MS = 6_000L

    // Maneuver keys are distances along the route, so any two distinct longs.
    private val TURN = 1_200L
    private val NEXT_TURN = 1_450L

    private fun may(
        isFinal: Boolean,
        nowMs: Long,
        key: Long = TURN,
        lastSpokeKey: Long? = null,
        lastSpokeMs: Long = 0L,
        lastFinalKey: Long? = null,
        lastFinalMs: Long = 0L
    ) = VoiceGuide.mayAnnounce(isFinal, key, nowMs, lastSpokeKey, lastSpokeMs,
                               lastFinalKey, lastFinalMs)

    // ---- cadence ------------------------------------------------------------

    @Test fun `a turn is announced twice, not three times`() {
        assertEquals("motorway band", 2, VoiceGuide.FAST.size)
        assertEquals("main-road band", 2, VoiceGuide.MEDIUM.size)
        assertEquals("town band", 2, VoiceGuide.SLOW.size)
        assertArrayEquals(intArrayOf(800, 250), VoiceGuide.FAST)
        assertArrayEquals(intArrayOf(400, 150), VoiceGuide.MEDIUM)
        assertArrayEquals(intArrayOf(200, 60), VoiceGuide.SLOW)
    }

    @Test fun `the band follows the speed`() {
        assertArrayEquals(VoiceGuide.FAST, VoiceGuide.thresholdsFor(120))
        assertArrayEquals(VoiceGuide.FAST, VoiceGuide.thresholdsFor(90))
        assertArrayEquals(VoiceGuide.MEDIUM, VoiceGuide.thresholdsFor(89))
        assertArrayEquals(VoiceGuide.MEDIUM, VoiceGuide.thresholdsFor(50))
        assertArrayEquals(VoiceGuide.SLOW, VoiceGuide.thresholdsFor(49))
        assertArrayEquals(VoiceGuide.SLOW, VoiceGuide.thresholdsFor(0))
    }

    @Test fun `both calls land about the same time ahead, whatever the band`() {
        // The point of having bands at all: 250 m is eight seconds on a
        // motorway and half a minute in a town, so the same distance cannot
        // serve both. The prepare call is far enough out to change lane in,
        // the act call close enough to be about this junction.
        for ((kph, band) in listOf(110 to VoiceGuide.FAST,
                                   70 to VoiceGuide.MEDIUM,
                                   30 to VoiceGuide.SLOW)) {
            val mps = kph / 3.6
            val prepare = band[0] / mps
            val act = band[1] / mps
            assertTrue("prepare call at $kph km/h is ${prepare.toInt()} s ahead",
                prepare in 15.0..35.0)
            assertTrue("now call at $kph km/h is ${act.toInt()} s ahead",
                act in 5.0..15.0)
        }
    }

    @Test fun `each stage fires once, and only once it is reached`() {
        val t = VoiceGuide.FAST                              // 800 then 250
        assertEquals("900 m out there is nothing to say yet",
            0, VoiceGuide.stageFor(0, 900, t))
        assertEquals(1, VoiceGuide.stageFor(0, 800, t))
        // The whole 550 m in between is silence now: there is no middle call.
        assertEquals(1, VoiceGuide.stageFor(1, 600, t))
        assertEquals(1, VoiceGuide.stageFor(1, 251, t))
        assertEquals(2, VoiceGuide.stageFor(1, 250, t))
        // Past the last threshold the stage stops moving, which is what keeps
        // the junction itself quiet.
        assertEquals(2, VoiceGuide.stageFor(2, 10, t))
    }

    @Test fun `braking hard skips to the call that still matters`() {
        // Coming off a motorway both thresholds can fall inside one GPS tick.
        // "In eight hundred metres" for a turn 40 m away is worse than nothing.
        assertEquals(2, VoiceGuide.stageFor(0, 40, VoiceGuide.FAST))
    }

    // ---- the turn that was told twice ---------------------------------------

    @Test fun `a turn is not told twice in the space of a breath`() {
        // The complaint, with the new numbers. In town the turn first appears
        // 65 m away -- a reroute, or a junction the router only just sent us --
        // so the prepare call goes out quoting the 200 m threshold, and the
        // 60 m threshold is reached nine metres later.
        val stage1 = VoiceGuide.stageFor(0, 65, VoiceGuide.SLOW)
        assertEquals("the prepare call, for a turn already 65 m away", 1, stage1)
        assertEquals("nine metres on, the now call", 2,
            VoiceGuide.stageFor(stage1, 56, VoiceGuide.SLOW))

        val prepareAt = 800_000L
        assertFalse("\"bear left now\" one second after \"in 200 metres, bear left\"",
            may(isFinal = true, nowMs = prepareAt + 1_000,
                lastSpokeKey = TURN, lastSpokeMs = prepareAt))
        assertFalse(may(isFinal = true, nowMs = prepareAt + MIN_GAP_MS - 1,
                        lastSpokeKey = TURN, lastSpokeMs = prepareAt))
        assertTrue("far enough apart to be two useful calls",
            may(isFinal = true, nowMs = prepareAt + MIN_GAP_MS,
                lastSpokeKey = TURN, lastSpokeMs = prepareAt))
    }

    @Test fun `a now call still gets through against anything else`() {
        // Only the turn's *own* prepare call silences it. The previous turn, a
        // camera warning or a level crossing a second ago must not be allowed
        // to delay "turn right now" -- by the time they are done, the junction
        // has been passed.
        val t = 900_000L
        assertTrue("the last thing said was about another maneuver",
            may(isFinal = true, nowMs = t + 500, lastSpokeKey = NEXT_TURN, lastSpokeMs = t))
        assertTrue("the last thing said was not a maneuver at all",
            may(isFinal = true, nowMs = t + 500, lastSpokeKey = null, lastSpokeMs = t))
        assertTrue("the previous turn's now call does not hold this one back",
            may(isFinal = true, key = NEXT_TURN, nowMs = t + 500,
                lastFinalKey = TURN, lastFinalMs = t))
    }

    // ---- two turns close together -------------------------------------------

    @Test fun `a new turn's prepare call waits for the last turn's now call`() {
        val t0 = 800_000L                    // "turn right now" for TURN
        // Five seconds is already clear of the ordinary gap between
        // announcements, and still too close: "turn right now" and then "in
        // four hundred metres, bear left" is heard as one instruction.
        assertFalse("five seconds after the previous turn",
            may(isFinal = false, key = NEXT_TURN, nowMs = t0 + 5_000,
                lastSpokeKey = TURN, lastSpokeMs = t0, lastFinalKey = TURN, lastFinalMs = t0))
        assertFalse(may(isFinal = false, key = NEXT_TURN, nowMs = t0 + PREPARE_QUIET_MS - 1,
                        lastSpokeKey = TURN, lastSpokeMs = t0,
                        lastFinalKey = TURN, lastFinalMs = t0))
        assertTrue("six seconds is room enough for a second instruction",
            may(isFinal = false, key = NEXT_TURN, nowMs = t0 + PREPARE_QUIET_MS,
                lastSpokeKey = TURN, lastSpokeMs = t0, lastFinalKey = TURN, lastFinalMs = t0))
    }

    @Test fun `the quiet window is between two turns, never within one`() {
        // A maneuver re-announced from the start -- after a reroute, or when
        // the spoken map is trimmed -- is not competing with anything, so its
        // own earlier now call must not silence it.
        val t0 = 800_000L
        assertTrue(may(isFinal = false, key = TURN, nowMs = t0 + 1_000,
                       lastSpokeKey = TURN, lastSpokeMs = t0 - MIN_GAP_MS,
                       lastFinalKey = TURN, lastFinalMs = t0))
    }

    @Test fun `prepare calls still keep their distance from whatever was just said`() {
        // The original gap rule, unchanged: a prepare call is never urgent
        // enough to tread on the tail of anything.
        val t = 700_000L
        assertFalse(may(isFinal = false, nowMs = t + MIN_GAP_MS - 1, lastSpokeMs = t))
        assertTrue(may(isFinal = false, nowMs = t + MIN_GAP_MS, lastSpokeMs = t))
    }

    @Test fun `the first announcement of the drive is not suppressed by an empty history`() {
        val t = 1_000_000L
        assertTrue(may(isFinal = false, nowMs = t))
        assertTrue(may(isFinal = true, nowMs = t))
    }
}
