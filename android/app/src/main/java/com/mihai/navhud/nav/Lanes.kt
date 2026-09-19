package com.mihai.navhud.nav

import org.json.JSONArray

/**
 * Lane guidance and junction sign text, pulled out of Mapbox's
 * `bannerInstructions`.
 *
 * Lane directions are a bitmask so the whole thing fits in one small integer
 * per lane and can be pushed down the serial link without a second protocol.
 *
 * ## What the data actually looks like
 *
 * Mapbox puts lanes in `bannerInstructions[].sub.components[]`, one component
 * of `type: "lane"` per lane, **ordered left to right as you see them through
 * the windscreen**. Each carries:
 *
 *  - `directions`: every movement that lane is legally allowed to make, e.g.
 *    `["straight", "right"]` for a lane you may either continue in or turn
 *    right from. The values are `none, straight, slight left, left,
 *    sharp left, uturn, slight right, right, sharp right`.
 *  - `active`: true when you may use this lane for *your* route.
 *  - `active_direction`: **which one** of that lane's `directions` is the one
 *    to follow. Only present when `active` is true, and only emitted by the
 *    `mapbox/driving` profile.
 *
 * That third field is the whole difference between useful and useless lane
 * guidance. A shared through-and-exit lane is legal for both movements, and
 * showing both arrows equally lit tells you nothing; showing the exit arrow
 * lit and the straight arrow dimmed tells you to be in that lane *and* to
 * peel off. So the display has three states, not two:
 *
 *  1. the chosen movement of a usable lane — full amber, thick;
 *  2. the other movements the same lane allows — dim amber;
 *  3. every movement of a lane you must not be in — grey.
 *
 * When `active_direction` is missing (the `driving-traffic` profile does not
 * send it) it is reconstructed: a usable lane with one direction has an
 * obvious answer, and otherwise the banner's own `primary.modifier` — the
 * word the instruction card is about to say — is matched against the lane's
 * directions.
 */
object Lane {
    const val UTURN        = 1 shl 0
    const val SHARP_LEFT   = 1 shl 1
    const val LEFT         = 1 shl 2
    const val SLIGHT_LEFT  = 1 shl 3
    const val STRAIGHT     = 1 shl 4
    const val SLIGHT_RIGHT = 1 shl 5
    const val RIGHT        = 1 shl 6
    const val SHARP_RIGHT  = 1 shl 7

    /** Left to right, the order arrows are drawn in inside one lane cell. */
    val ORDER = intArrayOf(
        UTURN, SHARP_LEFT, LEFT, SLIGHT_LEFT,
        STRAIGHT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT
    )

    fun fromName(s: String): Int = when (s.lowercase().trim()) {
        "uturn", "u-turn" -> UTURN
        "sharp left" -> SHARP_LEFT
        "left" -> LEFT
        "slight left" -> SLIGHT_LEFT
        "straight", "none" -> STRAIGHT
        "slight right" -> SLIGHT_RIGHT
        "right" -> RIGHT
        "sharp right" -> SHARP_RIGHT
        else -> 0
    }

    /** For drawing: the arrow angle a lane's primary direction implies. */
    fun angleOf(bits: Int): Float = when {
        bits and UTURN != 0 -> 180f
        bits and SHARP_LEFT != 0 -> -135f
        bits and LEFT != 0 -> -90f
        bits and SLIGHT_LEFT != 0 -> -40f
        bits and SLIGHT_RIGHT != 0 -> 40f
        bits and RIGHT != 0 -> 90f
        bits and SHARP_RIGHT != 0 -> 135f
        else -> 0f
    }

    /** Only the lowest set bit, so "one movement" really is one movement. */
    fun single(bits: Int): Int = bits and (-bits)

    fun countBits(bits: Int): Int = Integer.bitCount(bits and 0xFF)

    /** -1 leftwards, +1 rightwards, 0 straight on or a u-turn. */
    fun sideOf(bits: Int): Int = when {
        bits and (SHARP_LEFT or LEFT or SLIGHT_LEFT) != 0 -> -1
        bits and (SHARP_RIGHT or RIGHT or SLIGHT_RIGHT) != 0 -> 1
        else -> 0
    }
}

/**
 * @param lanes      permitted direction bitmask per lane, left to right
 * @param activeMask bit i set when lane i can be used for this maneuver
 * @param chosen     the single direction to follow in lane i, 0 when unknown
 * @param clippedLeft  lanes were dropped off the left of the window
 * @param clippedRight lanes were dropped off the right of the window
 */
data class LaneGuidance(
    val lanes: IntArray,
    val activeMask: Int,
    val chosen: IntArray = IntArray(lanes.size),
    val clippedLeft: Boolean = false,
    val clippedRight: Boolean = false
) {
    val count: Int get() = lanes.size

    fun isActive(i: Int) = (activeMask shr i) and 1 == 1

    /** The movement to follow in lane i, or 0 if this lane is not ours. */
    fun chosenOf(i: Int): Int = if (i in chosen.indices) chosen[i] else 0

    /** Index of the leftmost usable lane, or -1. */
    val firstActive: Int get() {
        for (i in 0 until count) if (isActive(i)) return i
        return -1
    }

    val lastActive: Int get() {
        for (i in count - 1 downTo 0) if (isActive(i)) return i
        return -1
    }

    val activeCount: Int get() = Integer.bitCount(activeMask and ((1 shl count) - 1))

    /**
     * Which way the route leaves this junction: -1 left, +1 right, 0 straight.
     * Taken from the chosen movements, falling back to where the usable lanes
     * sit in the carriageway.
     */
    val turnSide: Int get() {
        for (i in 0 until count) {
            if (!isActive(i)) continue
            val s = Lane.sideOf(chosenOf(i).takeIf { it != 0 } ?: lanes[i])
            if (s != 0) return s
        }
        val f = firstActive
        if (f < 0) return 0
        return when {
            lastActive == count - 1 && f > 0 -> 1
            f == 0 && lastActive < count - 1 -> -1
            else -> 0
        }
    }

    /**
     * How many lanes at the turn side actually *leave* the carriageway.
     *
     * Not the same as "how many lanes are usable". A lane that allows both
     * straight on and the exit is still part of the through carriageway — it
     * is the one you can sit in and change your mind about — so it must not be
     * drawn peeling away. Only a contiguous run of lanes at the turn side
     * whose permitted movements are *all* turns counts as the slip road.
     *
     * Returns 0 for a junction that is not a diverge at all.
     */
    fun rampLaneCount(): Int {
        val side = turnSide
        if (side == 0) return 0
        var n = 0
        val order = if (side > 0) (count - 1) downTo 0 else 0 until count
        for (i in order) {
            val bits = lanes[i]
            val leaves = bits != 0 &&
                bits and Lane.STRAIGHT == 0 &&
                Lane.sideOf(bits) == side
            if (leaves) n++ else break
        }
        // A "slip road" made of every lane on the road is not a slip road, it
        // is a bend; drawing it as a diverge would show a carriageway peeling
        // away from nothing.
        return if (n >= count) 0 else n
    }

    /**
     * `$LANE,<count>,<activeMask>,<d0>,…,<dN-1>,<c0>,…,<cN-1>`
     *
     * The chosen-direction bytes are appended rather than interleaved so a
     * firmware built against protocol v2 — which reads exactly `3 + count`
     * fields — keeps working unchanged and simply ignores the tail.
     */
    fun encodeBody(): String =
        "LANE,$count,$activeMask," + lanes.joinToString(",") +
            (if (count > 0) "," + chosen.joinToString(",") else "")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LaneGuidance) return false
        return activeMask == other.activeMask &&
            lanes.contentEquals(other.lanes) &&
            chosen.contentEquals(other.chosen)
    }

    override fun hashCode(): Int =
        31 * (31 * lanes.contentHashCode() + activeMask) + chosen.contentHashCode()

    companion object {
        /**
         * Never send more than this down the wire; the display cannot show
         * more. 99.8% of tagged carriageways in OpenStreetMap have six lanes
         * or fewer, so eight covers all but a handful of American-style
         * approaches — and those get windowed rather than truncated.
         */
        const val MAX_LANES = 8

        fun fromBanners(banners: JSONArray?): LaneGuidance? {
            if (banners == null) return null
            // Take the last banner that actually carries lanes: Mapbox emits a
            // far-out banner and a close-in one, and the close-in one is the
            // one whose lane data is final.
            for (i in banners.length() - 1 downTo 0) {
                val b = banners.optJSONObject(i) ?: continue
                val comps = b.optJSONObject("sub")?.optJSONArray("components") ?: continue
                val lanes = ArrayList<Int>()
                val chosen = ArrayList<Int>()
                var active = 0
                for (j in 0 until comps.length()) {
                    val c = comps.optJSONObject(j) ?: continue
                    if (c.optString("type") != "lane") continue
                    var bits = 0
                    val dirs = c.optJSONArray("directions")
                    if (dirs != null) {
                        for (k in 0 until dirs.length()) bits = bits or Lane.fromName(dirs.optString(k))
                    }
                    val on = c.optBoolean("active", false)
                    if (on) active = active or (1 shl lanes.size)
                    // "active_direction" is the field that makes a shared lane
                    // readable. It is only sent when the lane is active.
                    val ad = if (on) Lane.fromName(c.optString("active_direction", "")) else 0
                    lanes.add(bits)
                    chosen.add(if (ad != 0 && bits and ad != 0) ad else 0)
                }
                if (lanes.isEmpty()) continue

                // Reconstruct the chosen movement where the profile did not
                // send one.
                val hint = Lane.fromName(b.optJSONObject("primary")?.optString("modifier", "") ?: "")
                for (k in lanes.indices) {
                    if (chosen[k] != 0) continue
                    if ((active shr k) and 1 == 0) continue
                    val bits = lanes[k]
                    chosen[k] = when {
                        bits == 0 -> 0
                        Lane.countBits(bits) == 1 -> bits
                        hint != 0 && bits and hint != 0 -> hint
                        else -> 0
                    }
                }
                return window(lanes.toIntArray(), active, chosen.toIntArray())
            }
            return null
        }

        /**
         * Squeeze a wide approach into MAX_LANES without ever dropping the
         * lane you are supposed to be in.
         *
         * A naive `break` at eight lanes is worse than useless on the kind of
         * junction this feature exists for: if the exit is the tenth lane of
         * eleven, truncating from the left throws away precisely the lane the
         * driver needs and confidently shows them eight through lanes. So the
         * window is placed around the usable lanes and the view is told which
         * side it lost, so it can draw a torn edge instead of pretending the
         * carriageway ends there.
         */
        fun window(lanes: IntArray, activeMask: Int, chosen: IntArray): LaneGuidance {
            val n = lanes.size
            if (n <= MAX_LANES) return LaneGuidance(lanes, activeMask, chosen)

            var lo = 0
            var hi = n - 1
            for (i in 0 until n) if ((activeMask shr i) and 1 == 1) { lo = i; break }
            for (i in n - 1 downTo 0) if ((activeMask shr i) and 1 == 1) { hi = i; break }
            if (hi < lo) { lo = 0; hi = 0 }

            // Centre the window on the usable block, then clamp into range.
            var start = ((lo + hi) / 2) - (MAX_LANES / 2 - 1)
            start = start.coerceIn(0, n - MAX_LANES)
            // If the usable block still falls outside, prefer showing its end.
            if (hi >= start + MAX_LANES) start = (hi - MAX_LANES + 1).coerceIn(0, n - MAX_LANES)
            if (lo < start) start = lo.coerceIn(0, n - MAX_LANES)
            val end = start + MAX_LANES

            var mask = 0
            for (i in start until end) if ((activeMask shr i) and 1 == 1) mask = mask or (1 shl (i - start))
            return LaneGuidance(
                lanes.copyOfRange(start, end),
                mask,
                chosen.copyOfRange(start, end),
                clippedLeft = start > 0,
                clippedRight = end < n
            )
        }
    }
}

/**
 * What a motorway exit sign says: the exit number and where the road goes.
 * Sygic draws a photo-real junction picture here; that imagery is licensed and
 * not something a DIY build can get, so this is the schematic version — the
 * same information, drawn rather than photographed.
 */
data class JunctionSign(
    val exitNumber: String?,     // "22", "14b"
    val destinations: String?    // "Liège / Namur"
) {
    val isEmpty: Boolean get() = exitNumber.isNullOrBlank() && destinations.isNullOrBlank()

    companion object {
        fun fromBanners(banners: JSONArray?, stepRef: String?, stepDest: String?): JunctionSign {
            var exit: String? = null
            if (banners != null) {
                outer@ for (i in 0 until banners.length()) {
                    val primary = banners.optJSONObject(i)?.optJSONObject("primary") ?: continue
                    val comps = primary.optJSONArray("components") ?: continue
                    for (j in 0 until comps.length()) {
                        val c = comps.optJSONObject(j) ?: continue
                        if (c.optString("type") == "exit-number") {
                            exit = c.optString("text").ifBlank { null }
                            break@outer
                        }
                    }
                }
            }
            val dest = stepDest?.replace(",", " / ")?.trim()?.ifBlank { null }
            return JunctionSign(exit ?: stepRef?.ifBlank { null }, dest)
        }
    }
}
