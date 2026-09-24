package com.mihai.navhud

/**
 * When to tell the driver they are over the limit -- once, and then leave them
 * alone.
 *
 * The old rule chimed at limit + 5 km/h the instant the speed crossed it,
 * re-armed the instant it dropped back, and repeated every nine seconds: on a
 * GPS speed that wobbles by a few km/h at 4 Hz it beeped constantly, and every
 * beep paused the music. This one wants a real, sustained excess, says so
 * once, and then stays quiet unless something genuinely changes.
 *
 *  - **Margin by band**: +10 up to 50, +20 up to 90, +30 above -- a tolerance
 *    that scales with the limit, roughly what fixed cameras allow for.
 *  - **Sustained**: over the margin continuously for [SUSTAIN_MS].
 *  - **Cooldown**: then silent for [COOLDOWN_MS]...
 *  - **...unless** the driver clearly slowed down (at or below limit - 10 for
 *    [UNDER_HOLD_MS]) or the known limit changed: both make a new warning news.
 *  - **Unknown limit** (0 unknown, -1 derestricted) neither triggers nor
 *    re-arms, and wipes the sustain timer: a gap in the data must not let two
 *    separate seconds of speeding add up to a warning.
 *
 * Pure, so it is tested against simulated drives.
 */
class SpeedingRule {

    companion object {
        const val SUSTAIN_MS = 3_000L
        const val COOLDOWN_MS = 180_000L
        const val UNDER_MARGIN_KPH = 10
        const val UNDER_HOLD_MS = 5_000L
    }

    fun marginFor(limitKph: Int): Int = when {
        limitKph <= 50 -> 10
        limitKph <= 90 -> 20
        else -> 30
    }

    private var armed = true
    private var warnedAtMs = 0L
    private var overSinceMs = -1L          // -1: not over the margin
    private var underSinceMs = -1L         // -1: not clearly under
    private var knownLimit = 0             // last known limit, across gaps

    /** True exactly when a warning should be spoken now. */
    fun update(nowMs: Long, speedKph: Int, limitKph: Int): Boolean {
        if (limitKph <= 0 || speedKph < 0) {
            overSinceMs = -1L
            underSinceMs = -1L
            return false
        }
        if (knownLimit > 0 && limitKph != knownLimit) {
            // A new limit is news, and the excess has to be earned against it.
            armed = true
            overSinceMs = -1L
            underSinceMs = -1L
        }
        knownLimit = limitKph

        if (!armed && nowMs - warnedAtMs >= COOLDOWN_MS) armed = true

        if (speedKph <= limitKph - UNDER_MARGIN_KPH) {
            if (underSinceMs < 0) underSinceMs = nowMs
            if (nowMs - underSinceMs >= UNDER_HOLD_MS) armed = true
        } else {
            underSinceMs = -1L
        }

        if (speedKph > limitKph + marginFor(limitKph)) {
            if (overSinceMs < 0) overSinceMs = nowMs
        } else {
            overSinceMs = -1L
        }

        if (armed && overSinceMs >= 0 && nowMs - overSinceMs >= SUSTAIN_MS) {
            armed = false
            warnedAtMs = nowMs
            return true
        }
        return false
    }
}
