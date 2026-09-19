package com.mihai.navhud

import java.text.Normalizer

/** One screen's worth of state, plus the wire encoder. */
data class HudFrame(
    val speedKph: Int = -1,        // -1 = no fix
    val limitKph: Int = 0,         // 0 = unknown, -1 = unlimited
    val maneuver: Int = Man.NONE,
    val roundaboutExit: Int = 0,
    val distToManeuverM: Int = 0,
    val etaSeconds: Int = 0,
    val remainingM: Int = 0,
    val flags: Int = 0,
    val street: String = "",
    /**
     * Which way the roundabout exit really points, degrees from the approach:
     * 0 straight ahead, positive to the right. Null when it is not a roundabout
     * or the router did not say, in which case the HUD falls back to guessing
     * from the exit number, as both displays always used to.
     */
    val roundaboutBearing: Int? = null
) {
    companion object {
        const val FLAG_OVER_LIMIT = 1
        const val FLAG_OFF_ROUTE = 2
        const val FLAG_GPS_OK = 4
        const val FLAG_ARRIVED = 8
        const val FLAG_LOW_CONF = 16
        const val FLAG_NIGHT = 32

        /**
         * There is an itinerary being followed.
         *
         * Set by RouteTracker and by nothing else -- FreeTracker cannot set it,
         * which is the point. The HUD used to work out "there is a route" from
         * "the maneuver is not NONE", and that inference put a straight-ahead
         * arrow on the glass the moment the app opened with no destination.
         *
         * Firmware that predates this bit ignores it, and an app that predates
         * it leaves it clear, which newer firmware reads as "no route" -- so
         * the mismatch fails towards no arrow rather than a wrong one.
         */
        const val FLAG_ROUTE = 64

        const val STREET_MAX = 20

        fun checksum(body: String): Int {
            var cs = 0
            for (c in body) cs = cs xor (c.code and 0xFF)
            return cs and 0xFF
        }

        fun wrap(body: String): String =
            "$" + body + "*" + String.format("%02X", checksum(body)) + "\r\n"

        fun ping(): String = wrap("PING")

        /**
         * The Arduino's fonts are ASCII, so "Chaussée d'Ixelles" has to become
         * "Chaussee d'Ixelles" rather than a row of question marks.
         */
        fun sanitize(raw: String): String {
            val folded = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            val cleaned = StringBuilder(folded.length)
            for (c in folded) {
                when {
                    c == ',' || c == '*' || c == '$' -> cleaned.append(' ')
                    c.code in 32..126 -> cleaned.append(c)
                    else -> cleaned.append(' ')
                }
            }
            return cleaned.toString().trim().replace(Regex(" +"), " ")
                .take(STREET_MAX)
        }
    }

    fun encode(): String = wrap(
        "HUD,$speedKph,$limitKph,$maneuver,$roundaboutExit," +
            "$distToManeuverM,$etaSeconds,$remainingM,$flags,${sanitize(street)}"
    )

    /**
     * `$RAB,<exit>,<bearing>` -- the real exit direction, as its own frame.
     *
     * It cannot ride on $HUD, and that is forced rather than chosen: the $HUD
     * frame ends with the street name, and the street is deliberately allowed to
     * contain commas so the firmware can take the rest of the line verbatim.
     * Nothing can ever be appended after it, and inserting a field before it
     * would shift every later position and break a board on older firmware.
     * $CAM and $LANE are separate frames for the same reason.
     *
     * The exit number is sent with the angle so the firmware can tie the two
     * together. Frames arrive independently, and without it an angle measured at
     * the last roundabout could be aimed at the next one -- looking every bit as
     * authoritative as a correct one.
     *
     * Null when there is nothing to say, and the caller simply sends nothing.
     * Firmware older than 2.7 ignores the line; firmware 2.7 without the line
     * falls back to the exit-number table exactly as before.
     */
    fun rabLine(): String? {
        val b = roundaboutBearing ?: return null
        if (roundaboutExit !in 1..12) return null
        if (b < -180 || b > 180) return null
        return wrap("RAB,$roundaboutExit,$b")
    }
}
