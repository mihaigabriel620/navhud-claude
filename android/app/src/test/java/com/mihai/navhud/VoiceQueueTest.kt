package com.mihai.navhud

import com.mihai.navhud.alerts.CameraAlert
import com.mihai.navhud.alerts.SpeedCamera
import com.mihai.navhud.voice.English
import com.mihai.navhud.voice.VoiceQueue
import com.mihai.navhud.voice.VoiceQueue.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stands in for the TTS engine: records what it was asked to play. */
class FakeSpeaker : VoiceQueue.Speaker {
    val started = ArrayList<Pair<VoiceQueue.Item, String>>()
    var stops = 0
    var idles = 0
    var refuse = false

    override fun start(item: VoiceQueue.Item, id: String): Boolean {
        if (refuse) return false
        started.add(item to id)
        return true
    }
    override fun stop() { stops++ }
    override fun idle() { idles++ }

    val texts: List<String> get() = started.map { it.first.text }
    val lastId: String get() = started.last().second
}

/**
 * "It cuts itself off": a camera warning chopped the turn instruction in half,
 * the speed warning chopped the camera warning, and a beep landed on top of
 * both. Nothing that has started may be interrupted any more; what waits is
 * ordered by importance and thrown away when it goes stale.
 */
class VoiceQueueTest {

    private var now = 1_000_000L
    private val speaker = FakeSpeaker()
    private val q = VoiceQueue(speaker) { now }

    private fun item(text: String, p: Priority = Priority.HIGH, ttl: Long = 5_000L,
                     key: Long? = null, earcon: String? = null) =
        VoiceQueue.Item(text, p, now + ttl, earcon, key)

    @Test fun `a line that has started is never cut off`() {
        q.add(item("turn right"))
        q.add(item("speed camera"))
        q.add(item("speed limit 50"))
        assertEquals("only one line at a time", listOf("turn right"), speaker.texts)
        assertEquals("nothing was flushed", 0, speaker.stops)

        now += 2_000; q.onDone(speaker.lastId)
        assertEquals(listOf("turn right", "speed camera"), speaker.texts)
        now += 2_000; q.onDone(speaker.lastId)
        assertEquals(listOf("turn right", "speed camera", "speed limit 50"), speaker.texts)
        assertEquals(0, speaker.stops)
    }

    @Test fun `the most important waiting line goes next`() {
        q.add(item("level crossing", Priority.NORMAL))            // plays at once: queue empty
        q.add(item("in 400 metres, bear left", Priority.NORMAL))
        q.add(item("turn right", Priority.HIGH))
        q.add(item("speed camera", Priority.HIGH))
        q.onDone(speaker.lastId)
        q.onDone(speaker.lastId)
        q.onDone(speaker.lastId)
        assertEquals(
            listOf("level crossing", "turn right", "speed camera", "in 400 metres, bear left"),
            speaker.texts
        )
    }

    @Test fun `a stale line is dropped, not said late`() {
        q.add(item("roundabout, third exit", ttl = 10_000))
        q.add(item("in 800 metres, keep left", Priority.NORMAL, ttl = 6_000))
        now += 7_000                                       // a long roundabout instruction
        q.onDone(speaker.lastId)
        assertEquals(listOf("roundabout, third exit"), speaker.texts)
        assertFalse(q.busy)
        assertTrue("the queue said it had gone quiet", speaker.idles > 0)
    }

    @Test fun `a line already stale on arrival is refused`() {
        assertFalse(q.add(VoiceQueue.Item("late", Priority.HIGH, now - 1)))
        assertTrue(speaker.started.isEmpty())
    }

    @Test fun `minor lines are only said into silence`() {
        q.add(item("turn right"))
        assertFalse("dropped while something plays", q.add(item("level crossing", Priority.LOW)))
        q.add(item("speed camera"))
        q.onDone(speaker.lastId)
        assertFalse("dropped while something waits or plays",
            q.add(item("recalculating", Priority.LOW)))
        q.onDone(speaker.lastId)
        assertTrue("said once the voice is idle", q.add(item("level crossing", Priority.LOW)))
        assertEquals(listOf("turn right", "speed camera", "level crossing"), speaker.texts)
    }

    @Test fun `a newer call about the same turn replaces the waiting one`() {
        q.add(item("speed camera"))
        q.add(item("in 150 metres, turn left", Priority.NORMAL, key = 1200L))
        q.add(item("turn left", Priority.HIGH, key = 1200L))
        q.onDone(speaker.lastId)
        q.onDone(speaker.lastId)
        assertEquals(listOf("speed camera", "turn left"), speaker.texts)
    }

    @Test fun `the gap is measured from the end of the last line`() {
        q.add(item("at the roundabout, take the third exit"))
        now += 5_000
        assertEquals("while it plays, the voice has not been quiet at all",
            now, q.quietSinceMs())
        val endedAt = now
        q.onDone(speaker.lastId)
        now += 1_500
        assertEquals(endedAt, q.quietSinceMs())
    }

    @Test fun `a chime travels with its line as one item`() {
        q.add(item("speed limit 50", earcon = VoiceGuide.EARCON_WARN))
        q.add(item("turn right"))
        assertEquals(1, speaker.started.size)
        assertEquals(VoiceGuide.EARCON_WARN, speaker.started[0].first.earcon)
    }

    @Test fun `a refused line does not wedge the queue`() {
        speaker.refuse = true
        q.add(item("turn right"))
        assertFalse(q.busy)
        assertTrue("idle, so focus is given back", speaker.idles > 0)
        speaker.refuse = false
        q.add(item("turn left"))
        assertEquals(listOf("turn left"), speaker.texts)
    }

    @Test fun `an end that never comes is written off`() {
        q.add(item("turn right"))
        q.add(item("speed camera", ttl = 60_000))
        now += VoiceQueue.STUCK_MS + 1
        assertTrue("the next line starts", q.busy)
        assertEquals(listOf("turn right", "speed camera"), speaker.texts)
    }

    @Test fun `flush is the one interruption, and old ends are ignored after it`() {
        q.add(item("turn right"))
        val old = speaker.lastId
        q.add(item("speed camera"))
        q.flush()
        assertEquals(1, speaker.stops)
        assertFalse(q.busy)
        q.add(item("speed limit 70"))
        q.onDone(old)                                    // the engine reports the cut-off line
        assertTrue("the new line is still the one playing", q.busy)
        assertEquals(listOf("turn right", "speed limit 70"), speaker.texts)
    }

    // ---- through VoiceGuide ------------------------------------------------

    private fun guide() = VoiceGuide(null, English, speaker) { now }

    private fun frame(dist: Int, man: Int = Man.RIGHT, kph: Int = 60, limit: Int = 0) =
        HudFrame(speedKph = kph, limitKph = limit, maneuver = man, distToManeuverM = dist)

    @Test fun `a camera warning waits for the turn call instead of cutting it off`() {
        val g = guide()
        g.onFrame(frame(140), 5_000L)                     // "Turn right." (medium band, 150 m)
        val cam = SpeedCamera(1, 0.0, 0.0, 0.0, 70, null, SpeedCamera.Kind.FIXED)
        g.announceCamera(CameraAlert(cam, 700, zoneMode = false))
        assertEquals("the camera waits", listOf("Turn right."), speaker.texts)
        assertEquals("nothing was flushed", 0, speaker.stops)
        now += 1_500
        g.onUtteranceDone(speaker.lastId)                 // the engine finishes the turn call
        g.onUtteranceDone(speaker.lastId)
        assertEquals(listOf("Turn right.", "Speed camera in 700 metres, limit 70."), speaker.texts)
        assertEquals(VoiceGuide.EARCON_CAMERA, speaker.started[1].first.earcon)
        assertEquals(0, speaker.stops)
    }

    @Test fun `recalculating is dropped rather than queued behind a turn`() {
        val g = guide()
        g.onFrame(frame(140), 5_000L)
        g.announceReroute()
        g.onUtteranceDone(speaker.lastId)
        assertEquals(listOf("Turn right."), speaker.texts)
    }

    @Test fun `the settings sample is the one line allowed to cut in`() {
        val g = guide()
        g.onFrame(frame(140), 5_000L)
        g.sample()
        assertEquals(1, speaker.stops)
        assertEquals(listOf("Turn right.", "Speed limit 70."), speaker.texts)
    }
}
