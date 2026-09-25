package com.mihai.navhud

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import com.mihai.navhud.alerts.CameraAlert
import com.mihai.navhud.voice.Phrases
import com.mihai.navhud.voice.VoiceQueue
import com.mihai.navhud.voice.VoiceQueue.Priority
import com.mihai.navhud.voice.VoiceRegions
import kotlin.math.abs

/**
 * Spoken guidance.
 *
 * Two things make the difference between guidance that helps and guidance that
 * makes you turn the volume down:
 *
 *  - **Thresholds scale with speed.** "In 300 metres, turn right" is useless at
 *    120 km/h, where 300 m is nine seconds away. The distances below are picked
 *    so each announcement lands roughly the same number of seconds ahead of the
 *    turn regardless of how fast you are going.
 *  - **Each stage fires once per maneuver.** Announcements are keyed on the
 *    maneuver's position along the route, so a reroute or a GPS wobble cannot
 *    make it repeat itself.
 *  - **Two turns close together do not talk over each other.** A "now" call
 *    keeps the next maneuver's "in x metres" call quiet for a few seconds, and
 *    a "now" call is itself dropped when its own "in x metres" call has only
 *    just been spoken -- that pairing is what said "in two hundred metres,
 *    bear left" and then "bear left now" nine metres further on.
 *  - **Nothing talks over anything.** Every line goes through [VoiceQueue]:
 *    one at a time, most important first, stale ones dropped.
 */
class VoiceGuide internal constructor(
    private val ctx: Context?,
    phrases: Phrases,
    /** Stands in for the TTS engine in unit tests; null on a device. */
    testSpeaker: VoiceQueue.Speaker?,
    private val clock: () -> Long
) {
    constructor(ctx: Context, phrases: Phrases = Phrases.forDevice()) :
        this(ctx, phrases, null, System::currentTimeMillis)

    companion object {
        private const val TAG = "VoiceGuide"

        /**
         * Metres at which stages fire, per speed band. Fast roads first.
         *
         * Two stages, not three. The outermost call was always the one nobody
         * used: two kilometres out there is nothing to do yet, and by the time
         * there is, it has been forgotten. Worse, three calls per maneuver on a
         * road with junctions a few hundred metres apart leaves no silence at
         * all -- the tail of one turn runs into the head of the next. One call
         * to prepare, one to act.
         */
        internal val FAST   = intArrayOf(800, 250)   // >= 90 km/h
        internal val MEDIUM = intArrayOf(400, 150)   // >= 50 km/h
        internal val SLOW   = intArrayOf(200, 60)    // town

        /**
         * Motorway exits and forks get three calls, like Waze and Google Maps:
         * a lane change at 120 km/h needs warning well before the gore, and a
         * single "in 800 metres" was forgotten by the time the exit came. The
         * same seconds ahead at every speed, so the bands scale down. Not in
         * town: below 50 km/h a fork or slip road gets the normal two calls --
         * three on close turns was the app talking too much.
         */
        internal val FAST_EXIT   = intArrayOf(1200, 500, 200)   // >= 90 km/h
        internal val MEDIUM_EXIT = intArrayOf(700, 300, 120)    // >= 50 km/h

        /** Exits, slip roads and forks: the maneuvers that get three calls. */
        internal fun isExit(maneuver: Int): Boolean = when (maneuver) {
            Man.RAMP_LEFT, Man.RAMP_RIGHT, Man.FORK_LEFT, Man.FORK_RIGHT,
            Man.KEEP_LEFT, Man.KEEP_RIGHT -> true
            else -> false
        }

        /**
         * Say something about a closure from this far out. Roughly a minute at
         * motorway speed, which is enough to take the exit before it rather
         * than sit in the queue after it.
         */
        const val CLOSURE_ANNOUNCE_M = 2000

        /**
         * Minimum gap between two announcements. Crossing a speed band changes
         * which thresholds apply, which can otherwise fire two stages of the
         * same maneuver a couple of seconds apart. Camera warnings ignore this
         * -- those always get through -- and so does the final "now" call,
         * except against its own prepare call: see [mayAnnounce].
         *
         * Measured from the *end* of the last line ([VoiceQueue.quietSinceMs]),
         * not from when it was asked for: from the request, a five-second
         * roundabout instruction left no gap at all behind it.
         */
        private const val MIN_GAP_MS = 4_000L

        /**
         * How long each kind of line may wait in the queue before it is not
         * worth saying. A "now" call four seconds late is in the junction; a
         * prepare call quotes a distance that stops being true.
         */
        private const val TTL_FINAL_MS = 4_000L
        private const val TTL_PREPARE_MS = 6_000L
        private const val TTL_ALERT_MS = 5_000L
        private const val TTL_ARRIVAL_MS = 10_000L
        private const val TTL_MINOR_MS = 3_000L
        private const val TTL_SAMPLE_MS = 10_000L

        /**
         * How long a maneuver's "now" call keeps the *next* maneuver's prepare
         * call quiet. Two junctions can be seconds apart, and "turn right now"
         * followed immediately by "in four hundred metres, bear left" is heard
         * as one instruction with a confusing ending. The prepare call is
         * dropped rather than held back, because by the time there is room for
         * it the distance it would quote is no longer true; the "now" call for
         * that turn still comes.
         */
        private const val PREPARE_QUIET_MS = 6_000L

        /** How long the arrival flag must stay clear before "arrived" re-arms. */
        private const val ARRIVAL_RELATCH_MS = 10_000L

        /**
         * "Turn right, then turn left": the second maneuver is folded into the
         * first one's "now" call when it follows within this many metres, or
         * this many seconds at the current speed, whichever is further.
         */
        private const val THEN_MIN_M = 150
        private const val THEN_SECONDS = 8.0
        /** Rounding slack when matching the folded maneuver's key. */
        private const val THEN_KEY_SLACK_M = 15L

        /** How long a reroute remembers the last turn it announced. */
        private const val REROUTE_MEMORY_MS = 120_000L
        /** GPS slack when deciding a rerouted turn is the one already announced. */
        private const val REROUTE_SLACK_M = 30

        /**
         * The two chimes, played *by the TTS engine* as earcons rather than by
         * a ToneGenerator. A ToneGenerator on STREAM_MUSIC took no audio focus
         * and looked to a head unit like a new media source starting, so the
         * radio paused for a 150 ms beep and often never came back. As
         * earcons they carry the same navigation-guidance attributes as the
         * speech, sit in the same queue, and are covered by the same focus.
         */
        internal const val EARCON_WARN = "[navhud_warn]"
        internal const val EARCON_CAMERA = "[navhud_camera]"

        /** How long after the last line the music is given back. */
        private const val FOCUS_RELEASE_MS = 300L

        /** Utterance id suffix for an item's chime; only the line's end counts. */
        private const val CHIME_SUFFIX = ".chime"

        /** The voice actually in use, so the Setup screen can show it. */
        @Volatile var lastVoiceInfo: String = "not started"
            internal set

        /** Which stage distances apply at this speed. */
        internal fun thresholdsFor(speedKph: Int, maneuver: Int = Man.NONE): IntArray {
            val exit = isExit(maneuver)
            return when {
                speedKph >= 90 -> if (exit) FAST_EXIT else FAST
                speedKph >= 50 -> if (exit) MEDIUM_EXIT else MEDIUM
                else -> SLOW
            }
        }

        /**
         * The deepest stage passed, not every stage in between -- braking hard
         * off a motorway you can cross both thresholds in one GPS tick, and
         * what matters then is the call nearest the junction, not the one that
         * has already expired. Returns [done] unchanged when nothing new has
         * been passed, which is the signal to stay quiet.
         */
        internal fun stageFor(done: Int, distToManeuverM: Int, thresholds: IntArray): Int {
            var stage = done
            for (i in done until thresholds.size) {
                if (distToManeuverM <= thresholds[i]) stage = i + 1
            }
            return stage
        }

        /**
         * Whether this call is worth hearing, or would only crowd the last one.
         *
         * @param isFinal      the "now" call, as opposed to the prepare call
         * @param key          the maneuver about to be announced
         * @param lastSpokeKey the maneuver of the last announcement made
         * @param lastSpokeMs  when anything was last said
         * @param lastFinalKey the maneuver whose "now" call was the last one
         * @param lastFinalMs  when that "now" call went out
         */
        internal fun mayAnnounce(
            isFinal: Boolean,
            key: Long,
            nowMs: Long,
            lastSpokeKey: Long?,
            lastSpokeMs: Long,
            lastFinalKey: Long?,
            lastFinalMs: Long
        ): Boolean {
            // A prepare call is never urgent enough to interrupt anything.
            if (!isFinal && nowMs - lastSpokeMs < MIN_GAP_MS) return false
            // The turn just taken has priority over the one after it. Only a
            // *different* maneuver is held back: nothing needs protecting from
            // its own "now" call.
            if (!isFinal && key != lastFinalKey && nowMs - lastFinalMs < PREPARE_QUIET_MS) {
                return false
            }
            // The mirror of that, and the double-speak the driver complained
            // about: a maneuver whose prepare call was spoken seconds ago does
            // not also need "now". This happens when a turn first appears
            // already inside the outer threshold -- after a reroute, or on a
            // junction the router only just handed us -- so both stages fall
            // into the same few seconds and say the same thing twice.
            if (isFinal && key == lastSpokeKey && nowMs - lastSpokeMs < MIN_GAP_MS) return false
            return true
        }
    }

    private var tts: TextToSpeech? = null
    @Volatile private var ready = testSpeaker != null
    /**
     * The engine refused to start (not merely still starting), so this guide
     * will stay silent; the service builds a new one on its next start.
     */
    @Volatile var initFailed = false
        private set
    private val audio = ctx?.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
    private var focusRequest: AudioFocusRequest? = null

    private val main = Handler(Looper.getMainLooper())
    private val queue = VoiceQueue(testSpeaker ?: TtsSpeaker(), clock)

    private val spoken = HashMap<Long, Int>()
    private val speeding = SpeedingRule()
    // What the two suppression rules are measured against: the maneuver of the
    // last announcement, and the maneuver and time of the last "now" call.
    private var lastSpokeKey: Long? = null
    private var lastFinalKey: Long? = null
    private var lastFinalMs = 0L
    private var arrivalSpoken = false
    private var arrivalClearMs = 0L

    var enabled: Boolean = true

    /**
     * The app has been swiped away and only the HUD is left: the voice says
     * the two things worth hearing with the phone put away -- over the limit,
     * and cameras -- and nothing else. No arrival, turns, reroutes, roadworks,
     * crossings or bumps. It used to be silent altogether, and the over-limit
     * warning was the one thing the owner missed (app 1.33).
     */
    var warningsOnly: Boolean = false

    /** Changing this re-inits the engine's language. */
    var phrases: Phrases = phrases
        set(value) {
            // Compare the locale, not the code: fr-BE and fr-FR share "fr" but
            // are different voices and different numerals.
            if (field.locale == value.locale) return
            field = value
            applyLanguage()
        }

    init {
        val c = ctx
        if (c != null && testSpeaker == null) tts = TextToSpeech(c) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // `ready` is set last, below: a line spoken before the audio
                // attributes are in place goes out as plain media, and a head
                // unit takes a new media source as a reason to stop the music.
                applyLanguage()
                tts?.setSpeechRate(1.05f)
                // Called on a binder thread; the queue is synchronized. An
                // error or a stop ends the item exactly as completion does, so
                // the queue moves on and focus is always given back.
                runCatching {
                    tts?.setOnUtteranceProgressListener(
                        object : android.speech.tts.UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) = onUtteranceDone(utteranceId)
                            @Deprecated("required override")
                            override fun onError(utteranceId: String?) = onUtteranceDone(utteranceId)
                            override fun onError(utteranceId: String?, errorCode: Int) =
                                onUtteranceDone(utteranceId)
                            override fun onStop(utteranceId: String?, interrupted: Boolean) =
                                onUtteranceDone(utteranceId)
                        }
                    )
                }
                // Tell the mixer this is navigation guidance, not media: the
                // radio ducks under it instead of being paused, and on a head
                // unit it comes out of the right speakers.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    runCatching {
                        tts?.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                    }
                }
                runCatching {
                    tts?.addEarcon(EARCON_WARN, c.packageName, R.raw.beep_warn)
                    tts?.addEarcon(EARCON_CAMERA, c.packageName, R.raw.beep_camera)
                }
                ready = true
            } else {
                Log.w(TAG, "TTS init failed: $status")
                initFailed = true
            }
        }
    }

    /** Which voice ended up being used, for the Setup screen. */
    var voiceInfo: String = "default"
        private set(v) { field = v; lastVoiceInfo = v }

    private fun applyLanguage() {
        val t = tts ?: return
        val want = phrases.locale
        var r = t.setLanguage(want)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            // No fr-BE voice on most devices. Fall back down the region list
            // rather than to the bare language, because the bare language lets
            // the engine pick the region and it does not pick the one you want.
            for (cc in VoiceRegions.regionsFor(want.language)) {
                r = t.setLanguage(java.util.Locale(want.language, cc))
                if (r >= TextToSpeech.LANG_AVAILABLE) break
            }
        }
        if (r < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "no voice data for $want at all; using the device default")
            t.setLanguage(java.util.Locale.getDefault())
        }
        adjustVoice(t, want)
    }

    /**
     * Keep the phone's own voice unless it is the wrong language.
     *
     * The old version always overrode it, ranking every installed voice by
     * quality and preferring network voices — clever, and wrong twice over. It
     * fought whatever the driver had chosen in Android's own text-to-speech
     * settings, and its ranking is what let a Canadian French voice win. The
     * engine default is almost always the right answer; this only steps in
     * when it is speaking the wrong language or the wrong region entirely.
     */
    private fun adjustVoice(t: TextToSpeech, want: java.util.Locale) {
        val current = runCatching { t.voice }.getOrNull()
        val currentLoc = runCatching { current?.locale }.getOrNull()
        if (current != null && VoiceRegions.acceptable(want, currentLoc)) {
            voiceInfo = "${current.name} (${currentLoc})"
            Log.i(TAG, "keeping the device voice: $voiceInfo")
            return
        }

        val voices = runCatching { t.voices }.getOrNull()
        if (voices.isNullOrEmpty()) {
            voiceInfo = "engine default for $want"
            return
        }
        var best: Voice? = null
        var bestKey = Int.MAX_VALUE
        for (v in voices) {
            val loc = runCatching { v.locale }.getOrNull() ?: continue
            // "notInstalled" voices are advertised but would need a download.
            if (v.features?.contains("notInstalled") == true) continue
            // Region first, then quality: a high-quality voice from a region
            // we did not ask for is not a candidate at all.
            val key = VoiceRegions.sortKey(want, loc, v.quality)
            if (key < bestKey) { bestKey = key; best = v }
        }
        val chosen = best
        if (chosen != null && runCatching { t.setVoice(chosen) }.getOrNull() == TextToSpeech.SUCCESS) {
            voiceInfo = "${chosen.name} (${chosen.locale})"
            Log.i(TAG, "voice: $voiceInfo")
            return
        }
        voiceInfo = "engine default for $want"
    }

    /**
     * Say one line in the language just chosen.
     *
     * Changing the language used to be silent, which made a broken change
     * indistinguishable from a working one — and it *was* broken: the setting
     * was saved and never reached the running service, so the voice carried on
     * in whatever it had started with. Speaking immediately means the setting
     * proves itself.
     */
    fun sample() {
        if (!ready || !enabled) return
        // The driver asked for it, so it may cut off whatever was playing.
        queue.flush()
        say(phrases.overLimit(70), Priority.HIGH, TTL_SAMPLE_MS)
    }

    /**
     * @param maneuverKey stable identity of the upcoming maneuver -- its distance
     *                    along the route works, and survives GPS jitter.
     * @param thenManeuver the maneuver after that one, if known
     * @param thenGapM     metres between the two
     * @param allowFinal   false while coasting on a held GPS speed: the
     *                     distance is an estimate, and a "now" said early uses
     *                     up the call the real junction needed. It waits for
     *                     the first real fix instead.
     */
    fun onFrame(
        f: HudFrame, maneuverKey: Long?, thenManeuver: Int? = null, thenGapM: Int? = null,
        allowFinal: Boolean = true
    ) {
        if (!enabled || !ready) return
        if (warningsOnly) { chimeIfSpeeding(f); return }

        if (f.flags and HudFrame.FLAG_ARRIVED != 0) {
            if (!arrivalSpoken) {
                arrivalSpoken = true
                say(phrases.arrived(), Priority.HIGH, TTL_ARRIVAL_MS)
            }
            arrivalClearMs = 0L
            return
        }
        // Don't un-latch on the first frame without the flag. Parked at the
        // destination on a marginal fix, the arrival test toggles across its
        // threshold on jitter, and every re-entry said "you have arrived"
        // again. Ten clear seconds means the car has actually driven off.
        val nowMs = clock()
        if (arrivalSpoken) {
            if (arrivalClearMs == 0L) arrivalClearMs = nowMs
            if (nowMs - arrivalClearMs >= ARRIVAL_RELATCH_MS) {
                arrivalSpoken = false
                arrivalClearMs = 0L
            }
        }

        chimeIfSpeeding(f)

        // Keys are distances along the *old* route and mean nothing on the new
        // one, so they go -- but what they recorded is carried across below.
        val rerouted = rerouteRequested
        if (rerouted) {
            rerouteRequested = false
            spoken.clear()
            thenCovered = null
        }

        if (maneuverKey == null || f.distToManeuverM <= 0) return
        val thresholds = thresholdsFor(f.speedKph, f.maneuver)

        if (rerouted) carryAcrossReroute(f, maneuverKey, thresholds, nowMs)
        // Already folded into the previous "now" call as "..., then turn left":
        // its prepare call would only say it again.
        thenCovered?.let { (key, man) ->
            if (man == f.maneuver && abs(maneuverKey - key) <= THEN_KEY_SLACK_M &&
                spoken[maneuverKey] == null) {
                spoken[maneuverKey] = 1
            }
        }

        val done = spoken[maneuverKey] ?: 0
        val stage = stageFor(done, f.distToManeuverM, thresholds)
        if (stage == done) return
        if (!allowFinal && stage >= thresholds.size) return
        // Trim *before* recording, not after: clearing the map right after
        // putting this maneuver in it threw away the record of the very
        // announcement about to be made, so the next tick recomputed the same
        // stage and said "turn right onto Rue Neuve" a second time, in the
        // junction.
        if (spoken.size > 64) spoken.clear()
        spoken[maneuverKey] = stage
        // Said or deliberately skipped, this stage is used up; a reroute that
        // hands the same turn back under a new key must know that.
        lastTurn = Announced(maneuverKey, signature(f), stage, f.distToManeuverM, nowMs)

        val isFinal = stage >= thresholds.size

        // The destination is handled by the arrival flag above, so don't also
        // announce a maneuver for it.
        if (f.maneuver == Man.ARRIVE && isFinal) return

        val now = clock()
        if (!mayAnnounce(isFinal, maneuverKey, now,
                         lastSpokeKey, queue.quietSinceMs(), lastFinalKey, lastFinalMs)) return

        val instruction = if (f.maneuver == Man.ARRIVE) phrases.willArrive()
                          else phrases.instruction(f.maneuver, f.roundaboutExit, f.street)
        val merge = isFinal && shouldMergeThen(thenManeuver, thenGapM, f.speedKph)
        val text = when {
            merge -> phrases.immediateThen(instruction, phrases.brief(thenManeuver!!))
            isFinal -> phrases.immediate(instruction)
            else -> phrases.advance(phrases.distance(thresholds[stage - 1]), instruction)
        }
        if (merge) thenCovered = (maneuverKey + thenGapM!!) to thenManeuver!!
        if (isFinal) {
            // What the next maneuver's prepare call has to keep clear of.
            lastFinalKey = maneuverKey
            lastFinalMs = now
        }
        lastSpokeKey = maneuverKey
        // The last call before the turn is time-critical -- that is why it is
        // allowed to skip MIN_GAP_MS -- so it goes to the front of the queue.
        // It no longer cuts off what is playing: flushing is what made a
        // camera warning and a turn call chop each other in half. A waiting
        // prepare call for the same turn is replaced by it.
        say(text, if (isFinal) Priority.HIGH else Priority.NORMAL,
            if (isFinal) TTL_FINAL_MS else TTL_PREPARE_MS, key = maneuverKey)
    }

    /** Camera warnings go to the front of the queue: they are time-critical. */
    fun announceCamera(alert: CameraAlert) {
        if (!enabled || !ready) return
        // The stage's round number, never the live distance; right on top of
        // the camera there is no number worth saying ("in 13 metres").
        val near = alert.spokenM < com.mihai.navhud.alerts.CameraWatcher.SPOKEN_MIN_M
        val anpr = alert.camera.kind == com.mihai.navhud.alerts.SpeedCamera.Kind.ANPR
        val text = when {
            alert.zoneMode -> phrases.dangerZone()
            anpr && near -> phrases.anprHere()
            anpr -> phrases.anprAhead(phrases.distance(alert.spokenM))
            near -> phrases.cameraHere(alert.camera.limitKph)
            else -> phrases.cameraAhead(phrases.distance(alert.spokenM), alert.camera.limitKph)
        }
        say(text, Priority.HIGH, TTL_ALERT_MS, earcon = EARCON_CAMERA)
    }

    /** Only into silence: the new route's first instruction matters more. */
    fun announceReroute() {
        if (!enabled || !ready || warningsOnly) return
        say(phrases.reroute(), Priority.LOW, TTL_MINOR_MS)
    }

    fun reset() {
        arrivalClearMs = 0L
        spoken.clear()
        arrivalSpoken = false
        thenCovered = null
        lastTurn = null
    }

    /**
     * A new route replaced the old one mid-drive. Unlike [reset], this must not
     * make the voice repeat itself: the router re-plans from where the car is,
     * so the new route usually starts with the very turn that was just
     * announced, under a new key, and reset() had it said all over again.
     *
     * Safe from any thread: the work is done on the next [onFrame].
     */
    fun onReroute() {
        rerouteRequested = true
    }

    // -----------------------------------------------------------------------

    /** What was last said about a maneuver, to recognise it on a new route. */
    private data class Announced(
        val key: Long, val signature: String, val stage: Int, val distM: Int, val atMs: Long
    )

    @Volatile private var rerouteRequested = false
    private var lastTurn: Announced? = null
    /** The key and code of the maneuver folded into the last "then" call. */
    private var thenCovered: Pair<Long, Int>? = null

    private fun signature(f: HudFrame) = "${f.maneuver}/${f.roundaboutExit}/${f.street}"

    /**
     * First frame on a new route. Two things are true of its first maneuver:
     *
     *  - If it is the turn just announced -- same instruction, and no further
     *    away than when it was announced -- it keeps the stage it had reached,
     *    so neither call is repeated. A turn that was *missed* reappears
     *    further away than that, and is announced afresh, as it should be.
     *  - If we are already inside its prepare distance, "in 400 metres" is
     *    stale before it is said; the "now" call is the one that matters.
     */
    private fun carryAcrossReroute(f: HudFrame, key: Long, thresholds: IntArray, nowMs: Long) {
        val prev = lastTurn
        val same = prev != null && prev.signature == signature(f) &&
            nowMs - prev.atMs <= REROUTE_MEMORY_MS &&
            f.distToManeuverM <= prev.distM + REROUTE_SLACK_M
        val carried = if (same) prev!!.stage else 0
        val inside = if (f.distToManeuverM <= thresholds[0]) 1 else 0
        val stage = maxOf(carried, inside)
        if (stage > 0) spoken[key] = stage
        if (same) {
            // The suppression rules compare keys; follow the turn to its new one.
            if (lastSpokeKey == prev!!.key) lastSpokeKey = key
            if (lastFinalKey == prev.key) lastFinalKey = key
        }
    }

    /**
     * Fold the next maneuver into this "now" call when there will be no time
     * to announce it on its own: within 150 m, or eight seconds at this speed
     * on a fast road. Continuing straight on is not worth a "then".
     */
    private fun shouldMergeThen(thenManeuver: Int?, thenGapM: Int?, speedKph: Int): Boolean {
        if (thenManeuver == null || thenGapM == null || thenGapM <= 0) return false
        if (thenManeuver == Man.NONE || thenManeuver == Man.STRAIGHT ||
            thenManeuver == Man.DEPART) return false
        val reachM = maxOf(THEN_MIN_M, (speedKph.coerceAtLeast(0) / 3.6 * THEN_SECONDS).toInt())
        return thenGapM <= reachM
    }

    /**
     * Roadworks or a closure on the route ahead.
     *
     * Once per closure, and only from far enough out to be useful — a warning
     * at 200 m is not a warning, it is narration. The key is the closure's own
     * distance along the route, so approaching the same one after a stop does
     * not repeat it, but a different one later in the drive still gets said.
     */
    fun onClosure(metresAhead: Int, closureKey: Long) {
        if (!enabled || !ready || warningsOnly) return
        if (metresAhead !in 1..CLOSURE_ANNOUNCE_M) return
        if (spokenClosures.contains(closureKey)) return
        // Only marked said once the queue took it: a closure that arrived
        // while something else was playing is tried again on the next tick.
        if (!say(phrases.roadworks(phrases.distance(metresAhead)), Priority.LOW, TTL_MINOR_MS)) return
        if (spokenClosures.size > 64) spokenClosures.clear()
        spokenClosures.add(closureKey)
    }

    /**
     * A level crossing, a bump or a toll booth coming up.
     *
     * Once each, keyed on the OSM node id, and never while something more
     * important is being said — these are the smallest warnings the app makes
     * and they must not talk over a turn instruction.
     */
    fun onRoadFeature(kind: Int, id: Long) {
        if (!enabled || !ready || warningsOnly) return
        if (spokenFeatures.contains(id)) return
        val text = when (kind) {
            com.mihai.navhud.nav.RoadFeature.LEVEL_CROSSING -> phrases.levelCrossing()
            com.mihai.navhud.nav.RoadFeature.SPEED_BUMP -> phrases.speedBump()
            com.mihai.navhud.nav.RoadFeature.TOLL_BOOTH -> phrases.tollBooth()
            else -> return
        }
        // LOW: dropped if anything is playing, and then tried again next tick.
        if (!say(text, Priority.LOW, TTL_MINOR_MS)) return
        if (spokenFeatures.size > 256) spokenFeatures.clear()
        spokenFeatures.add(id)
    }

    /** Cut off anything mid-sentence. Used when the app is swiped away. */
    fun stopNow() {
        queue.flush()
        dropFocus()
    }

    fun shutdown() {
        queue.flush()
        runCatching { tts?.shutdown() }
        dropFocus()
        tts = null
    }

    // -----------------------------------------------------------------------

    private val spokenClosures = HashSet<Long>()
    private val spokenFeatures = HashSet<Long>()

    /**
     * One chime and the limit out loud, as a single item, when [SpeedingRule]
     * says so -- a bare beep does not tell you what you are supposed to be
     * doing, and a beep every few seconds is how the app gets muted.
     */
    private fun chimeIfSpeeding(f: HudFrame) {
        if (speeding.update(clock(), f.speedKph, f.limitKph)) {
            say(phrases.overLimit(f.limitKph), Priority.HIGH, TTL_ALERT_MS, earcon = EARCON_WARN)
        }
    }

    /** The engine finished, failed or stopped an utterance. Any thread. */
    internal fun onUtteranceDone(utteranceId: String?) = queue.onDone(utteranceId)

    /** Queue one line (and its chime); false if the queue dropped it. */
    private fun say(
        text: String, priority: Priority, ttlMs: Long,
        earcon: String? = null, key: Long? = null
    ): Boolean = queue.add(VoiceQueue.Item(text, priority, clock() + ttlMs, earcon, key))

    /**
     * The TTS engine as the queue sees it: one item at a time, always
     * QUEUE_ADD. A chime and its line are two engine utterances; only the
     * line's id is the item's, so the item ends when the line does.
     */
    private inner class TtsSpeaker : VoiceQueue.Speaker {
        override fun start(item: VoiceQueue.Item, id: String): Boolean {
            val t = tts ?: return false
            holdFocus()
            // Watchdog: an engine that never reports the end of a line never
            // makes the queue idle, and the music stayed ducked (or paused, on
            // a head unit) with nothing left to say. releaseFocus asks the
            // queue, which writes a stuck line off after STUCK_MS.
            main.postDelayed(releaseFocus, VoiceQueue.STUCK_MS + 1_000L)
            val earcon = item.earcon
            if (earcon != null) {
                val chimeId = if (item.text.isEmpty()) id else id + CHIME_SUFFIX
                val r = runCatching { t.playEarcon(earcon, TextToSpeech.QUEUE_ADD, null, chimeId) }
                    .getOrDefault(TextToSpeech.ERROR)
                if (item.text.isEmpty()) return r == TextToSpeech.SUCCESS
            }
            return runCatching { t.speak(item.text, TextToSpeech.QUEUE_ADD, null, id) }
                .getOrDefault(TextToSpeech.ERROR) == TextToSpeech.SUCCESS
        }

        override fun stop() {
            runCatching { tts?.stop() }
        }

        /**
         * Give the radio back when we stop talking.
         *
         * Focus was requested before the first announcement and abandoned only
         * in shutdown(), so a single "in two kilometres, turn right" ducked the
         * music for the entire drive -- hours of quiet radio for three seconds
         * of speech a minute. Android's own guidance is to abandon focus as
         * soon as there is nothing left to play.
         * https://developer.android.com/media/optimize/audio-focus
         *
         * Not *at once*, though: abandoning between a chime and its line, or
         * between two queued lines, un-ducks and re-ducks the music in a
         * fraction of a second, and some head units answer an abandon by
         * resuming playback they then do not pause again. One hold for the
         * whole burst, released a moment after the last item.
         */
        override fun idle() {
            main.removeCallbacks(releaseFocus)
            main.postDelayed(releaseFocus, FOCUS_RELEASE_MS)
        }
    }

    // Focus state is touched from the tick thread (announcements), the main
    // thread (settings, stop, the delayed release) and the TTS binder thread
    // (completion), so every change to it happens under the queue's own lock
    // -- the one the queue already holds when it starts an item. A second lock
    // would have to be taken in the opposite order somewhere, and deadlock.
    private val releaseFocus = Runnable {
        synchronized(queue) { if (!queue.busy) abandonFocus() }
    }

    /** Take focus if we do not have it, and cancel any release in flight. */
    private fun holdFocus() = synchronized(queue) {
        main.removeCallbacks(releaseFocus)
        requestFocus()
    }

    /** Give focus back now, cancelling the delayed release. */
    private fun dropFocus() = synchronized(queue) {
        main.removeCallbacks(releaseFocus)
        abandonFocus()
    }

    private fun requestFocus() {
        if (focusHeld) return
        val audio = audio ?: return
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build()
            focusRequest = req
            runCatching { audio.requestAudioFocus(req) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audio.requestAudioFocus(legacyFocusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        }
        // Refused (a phone call, typically): the line still plays -- a missed
        // turn is worse than a line spoken over a ring -- but nothing is marked
        // held, so there is nothing to abandon and the next line asks again.
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focusHeld) {
            focusRequest = null
            Log.w(TAG, "audio focus refused: $result")
        }
    }

    private fun abandonFocus() {
        if (!focusHeld) return
        focusHeld = false
        val audio = audio ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audio.abandonAudioFocus(legacyFocusListener) }
        }
        focusRequest = null
    }

    @Volatile private var focusHeld = false

    /**
     * API 24-25 needs a listener object, and the *same* object, to abandon.
     * Passing null both ways is accepted but abandons nothing.
     */
    private val legacyFocusListener = AudioManager.OnAudioFocusChangeListener { }
}
