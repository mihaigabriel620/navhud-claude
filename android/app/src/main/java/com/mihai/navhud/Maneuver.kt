package com.mihai.navhud

/** Maneuver codes. Must stay in lock-step with hud_protocol.h and PROTOCOL.md. */
object Man {
    const val NONE = 0
    const val LEFT = 1
    const val RIGHT = 2
    const val SLIGHT_LEFT = 3
    const val SLIGHT_RIGHT = 4
    const val SHARP_LEFT = 5
    const val SHARP_RIGHT = 6
    const val UTURN = 7
    const val STRAIGHT = 8
    const val MERGE_LEFT = 9
    const val MERGE_RIGHT = 10
    const val FORK_LEFT = 11
    const val FORK_RIGHT = 12
    const val ROUNDABOUT = 13
    const val DEPART = 14
    const val ARRIVE = 15
    const val RAMP_LEFT = 16
    const val RAMP_RIGHT = 17
    const val KEEP_LEFT = 18
    const val KEEP_RIGHT = 19

    private fun leftish(mod: String) =
        mod.contains("left")

    /**
     * Maps an OSRM/Mapbox `maneuver.type` + `maneuver.modifier` pair onto our
     * wire codes. Unknown combinations degrade to STRAIGHT rather than blanking
     * the screen -- a wrong-but-plausible arrow beats no arrow at 120 km/h.
     */
    fun fromMapbox(type: String?, modifier: String?): Int {
        val t = (type ?: "").lowercase()
        val m = (modifier ?: "").lowercase()

        when (t) {
            "depart" -> return DEPART
            "arrive" -> return ARRIVE
            "roundabout", "rotary", "roundabout turn",
            "exit roundabout", "exit rotary" -> return ROUNDABOUT
            "merge" -> return if (leftish(m)) MERGE_LEFT else MERGE_RIGHT
            "fork" -> return if (leftish(m)) FORK_LEFT else FORK_RIGHT
            "on ramp", "off ramp" -> return if (leftish(m)) RAMP_LEFT else RAMP_RIGHT
            "notification", "new name", "continue" ->
                if (m.isEmpty() || m == "straight") return STRAIGHT
        }

        return when (m) {
            "uturn" -> UTURN
            "sharp left" -> SHARP_LEFT
            "sharp right" -> SHARP_RIGHT
            "slight left" -> SLIGHT_LEFT
            "slight right" -> SLIGHT_RIGHT
            "left" -> LEFT
            "right" -> RIGHT
            "straight" -> STRAIGHT
            else -> STRAIGHT
        }
    }
}
