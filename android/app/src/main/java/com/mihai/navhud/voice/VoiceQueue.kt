package com.mihai.navhud.voice

/**
 * What the voice says next, and when.
 *
 * Handing every line straight to the TTS engine, with QUEUE_FLUSH for anything
 * urgent, meant urgent lines cut each other off mid-word: a camera warning
 * killed "turn right", the speed warning killed the camera warning, and a chime
 * could land on top of either. This keeps its own short queue and gives the
 * engine one item at a time; the next one starts when the engine says the last
 * one is done. Nothing that has started is ever interrupted -- only the driver
 * can do that ([flush], for the settings sample and for stop).
 *
 *  - **Priority** decides the order of what is *waiting*, never what is playing.
 *  - **Expiry**: a line that waited too long is dropped, not said late. "In
 *    four hundred metres" six seconds late is a lie.
 *  - **LOW** lines (a level crossing, roadworks, "recalculating") are only worth
 *    saying into silence, so they are dropped if anything is playing or queued.
 *  - A newer call about the same maneuver ([Item.key]) replaces a waiting one.
 *
 * Every public method is synchronized: lines are queued from the tick thread,
 * the settings screen and stop come from the main thread, and completions from
 * the TTS binder thread. The [Speaker] is called with the lock held.
 */
class VoiceQueue(
    private val speaker: Speaker,
    private val clock: () -> Long = System::currentTimeMillis
) {
    enum class Priority { LOW, NORMAL, HIGH }

    /** An optional chime and then a line, as one item so nothing comes between them. */
    class Item(
        val text: String,
        val priority: Priority,
        /** Wall-clock time after which it is no longer worth saying. */
        val expiresAtMs: Long,
        val earcon: String? = null,
        /** The maneuver it is about, if any. */
        val key: Long? = null
    )

    interface Speaker {
        /**
         * Start playing [item]. Its end must be reported to [onDone] with [id].
         * False if the engine refused it outright, so no end will be reported.
         */
        fun start(item: Item, id: String): Boolean
        /** Cut off whatever is playing. */
        fun stop()
        /** Nothing is playing or waiting any more. */
        fun idle()
    }

    companion object {
        /**
         * A line whose end never came back is written off after this long. An
         * engine that dies mid-sentence sends no callback, and without this the
         * queue would think it was talking for the rest of the drive.
         */
        const val STUCK_MS = 20_000L
    }

    private val waiting = ArrayList<Item>()
    private var playing: Item? = null
    private var playingId: String? = null
    private var playingSinceMs = 0L
    private var lastEndMs = 0L
    private var seq = 0

    /** Something is playing or waiting to play. */
    val busy: Boolean
        @Synchronized get() {
            unstick()
            return playing != null || waiting.isNotEmpty()
        }

    /**
     * When the voice last fell silent: the *end* of the last line, or now while
     * anything is playing or waiting. What the minimum gap is measured from.
     */
    @Synchronized fun quietSinceMs(): Long = if (busy) clock() else lastEndMs

    /** Queue [item]; false if it was dropped on arrival. */
    @Synchronized fun add(item: Item): Boolean {
        if (item.priority == Priority.LOW && busy) return false
        unstick()
        if (clock() > item.expiresAtMs) return false
        if (item.key != null) waiting.removeAll { it.key == item.key }
        // After everything of the same or higher priority: FIFO within a level.
        val at = waiting.indexOfFirst { it.priority < item.priority }
        if (at < 0) waiting.add(item) else waiting.add(at, item)
        pump()
        return true
    }

    /** The engine finished (or failed, or stopped) the item with this id. */
    @Synchronized fun onDone(id: String?) {
        if (id == null || id != playingId) return        // an old, flushed item
        finishPlaying()
        pump()
    }

    /** Drop everything and cut off what is playing. The only interruption there is. */
    @Synchronized fun flush() {
        waiting.clear()
        if (playing != null) finishPlaying()
        speaker.stop()
        speaker.idle()
    }

    private fun finishPlaying() {
        playing = null
        playingId = null
        lastEndMs = clock()
    }

    private fun unstick() {
        if (playing == null || clock() - playingSinceMs <= STUCK_MS) return
        finishPlaying()
        pump()
    }

    private fun pump() {
        while (playing == null) {
            if (waiting.isEmpty()) { speaker.idle(); return }
            val next = waiting.removeAt(0)
            if (clock() > next.expiresAtMs) continue
            val id = "navhud-${++seq}"
            playing = next
            playingId = id
            playingSinceMs = clock()
            // Refused: no callback will come, so move straight on.
            if (!speaker.start(next, id)) finishPlaying()
        }
    }
}
