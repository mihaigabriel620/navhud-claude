package com.mihai.navhud

import com.mihai.navhud.voice.English
import com.mihai.navhud.voice.FrenchBelgium
import com.mihai.navhud.voice.Phrases
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two turns close together, and reroutes.
 *
 * Two junctions a hundred metres apart used to get four calls with no room
 * between them; now the second is folded into the first one's "now" call.
 * And a reroute used to clear the voice's memory, so the turn it had just
 * announced was announced again under the new route's key.
 */
class CloseTurnsTest {

    private var now = 1_000_000L
    private val speaker = FakeSpeaker()

    private fun guide(p: Phrases = English) = VoiceGuide(null, p, speaker) { now }

    private fun frame(dist: Int, man: Int = Man.RIGHT, kph: Int = 60) =
        HudFrame(speedKph = kph, maneuver = man, distToManeuverM = dist)

    /** The engine finishes whatever it is saying, [ms] later. */
    private fun VoiceGuide.finish(ms: Long = 2_000) {
        now += ms
        onUtteranceDone(speaker.lastId)
    }

    // ---- "then" ------------------------------------------------------------

    @Test fun `a turn right after this one is said in the same breath`() {
        val g = guide()
        // Town band (200 / 60 m): the left turn is 140 m after the right one.
        g.onFrame(frame(55, kph = 40), 5_000L, Man.LEFT, 140)
        assertEquals(listOf("Turn right, then turn left."), speaker.texts)
        g.finish()
        now += 5_000                     // round the corner, well clear of the gap rule
        // Inside the left turn's prepare distance: already said, so silence...
        g.onFrame(frame(130, Man.LEFT, kph = 40), 5_140L)
        assertEquals(1, speaker.texts.size)
        // ...and its own "now" call still comes, at the junction.
        now += 7_000
        g.onFrame(frame(55, Man.LEFT, kph = 40), 5_140L)
        assertEquals(listOf("Turn right, then turn left.", "Turn left."), speaker.texts)
    }

    @Test fun `a coasted frame without the bus never says now, and the real fix still does`() {
        val g = guide()
        g.onFrame(frame(55, kph = 40), 5_000L, allowFinal = false)
        assertEquals(emptyList<String>(), speaker.texts)
        g.onFrame(frame(55, kph = 40), 5_000L)
        assertEquals(listOf("Turn right."), speaker.texts)
    }

    @Test fun `a turn far enough away keeps its own calls`() {
        val g = guide()
        // 60 km/h: eight seconds is 133 m, so the floor of 150 m applies.
        g.onFrame(frame(140), 5_000L, Man.LEFT, 400)
        assertEquals(listOf("Turn right."), speaker.texts)
    }

    @Test fun `the reach grows with speed`() {
        guide().onFrame(frame(240, kph = 120), 5_000L, Man.KEEP_LEFT, 250)
        assertEquals("250 m is under eight seconds at 120",
            listOf("Turn right, then keep left."), speaker.texts)

        val s2 = FakeSpeaker()
        VoiceGuide(null, English, s2) { now }.onFrame(frame(240, kph = 120), 5_000L, Man.KEEP_LEFT, 300)
        assertEquals(listOf("Turn right."), s2.texts)
    }

    @Test fun `carrying straight on is not worth a then`() {
        guide().onFrame(frame(140), 5_000L, Man.STRAIGHT, 60)
        assertEquals(listOf("Turn right."), speaker.texts)
    }

    @Test fun `a prepare call never carries a then`() {
        guide().onFrame(frame(390), 5_000L, Man.LEFT, 100)
        assertEquals(listOf("In 400 metres, turn right."), speaker.texts)
    }

    @Test fun `then is said in French too`() {
        guide(FrenchBelgium).onFrame(frame(140), 5_000L, Man.ROUNDABOUT, 90)
        assertEquals(listOf("Tournez à droite, puis prenez le rond-point."), speaker.texts)
        assertEquals("tournez à gauche", FrenchBelgium.brief(Man.LEFT))
        assertEquals("vous arriverez à destination", FrenchBelgium.brief(Man.ARRIVE))
        assertEquals("Turn left, then you will arrive at your destination.",
            English.immediateThen("turn left", English.brief(Man.ARRIVE)))
    }

    // ---- reroute -------------------------------------------------------------

    @Test fun `a reroute does not repeat the turn just announced`() {
        val g = guide()
        g.onFrame(frame(380), 5_000L)
        assertEquals(listOf("In 400 metres, turn right."), speaker.texts)
        g.finish()
        now += 5_000
        g.onReroute()
        // Same turn, now under the new route's key, 80 m closer.
        g.onFrame(frame(300), 300L)
        assertEquals(1, speaker.texts.size)
        now += 8_000
        g.onFrame(frame(140), 300L)
        assertEquals(listOf("In 400 metres, turn right.", "Turn right."), speaker.texts)
    }

    @Test fun `reset, by contrast, starts again from nothing`() {
        val g = guide()
        g.onFrame(frame(380), 5_000L)
        g.finish()
        now += 5_000
        g.reset()
        g.onFrame(frame(300), 300L)
        assertEquals(listOf("In 400 metres, turn right.", "In 400 metres, turn right."),
            speaker.texts)
    }

    @Test fun `the now call is not repeated by a reroute at the junction`() {
        val g = guide()
        g.onFrame(frame(140), 5_000L)
        g.finish()
        now += 5_000
        g.onReroute()
        g.onFrame(frame(120), 120L)
        assertEquals(listOf("Turn right."), speaker.texts)
    }

    @Test fun `a missed turn offered again further on is announced afresh`() {
        val g = guide()
        g.onFrame(frame(140), 5_000L)
        g.finish()
        now += 5_000
        g.onReroute()
        g.onFrame(frame(450), 450L)          // the next right, further than we were
        assertEquals(1, speaker.texts.size)
        now += 2_000
        g.onFrame(frame(390), 450L)
        assertEquals(listOf("Turn right.", "In 400 metres, turn right."), speaker.texts)
    }

    @Test fun `a new turn already inside its prepare distance gets only its now call`() {
        val g = guide()
        g.onFrame(frame(380), 5_000L)
        g.finish()
        now += 6_000
        g.onReroute()
        g.onFrame(frame(300, Man.UTURN), 300L)
        assertEquals(1, speaker.texts.size)
        now += 8_000
        g.onFrame(frame(140, Man.UTURN), 300L)
        assertEquals(listOf("In 400 metres, turn right.", "Make a U-turn."), speaker.texts)
    }
}
