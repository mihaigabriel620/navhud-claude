package com.mihai.navhud.map

import com.mihai.navhud.Geo
import kotlin.math.abs

/**
 * Which way the car was pointing when you switched it off, so it still knows
 * when you come back.
 *
 * ## The problem this exists for
 *
 * You park, walk away, come back an hour later, start a route, and the first
 * instruction is "turn left in 40 m". Left of *what*? The car has not moved, so
 * GPS has no bearing to give — a GPS course is derived from the movement
 * between fixes, and there has not been any. So you guess, and half the time
 * you guess wrong and spend the next 200 m looking for somewhere to turn round.
 *
 * A magnetometer would answer this directly, and there is not one anywhere in
 * this system: the head unit has no sensors at all, and the HUD board carries a
 * gyroscope and an accelerometer but no compass. So this file answers it a
 * different way — not by measuring the heading now, but by remembering the
 * heading from when the car was last moving, and proving it still applies.
 *
 * ## Why this needs the HUD's gyroscope
 *
 * The naive version — "store the last GPS bearing" — gets the wrong answer, and
 * gets it wrong in the worst possible way. The last GPS bearing you had was
 * while you were still rolling up the road at 20 km/h looking for a space. What
 * happens next is the part that matters: you slow to walking pace (GPS course
 * goes to noise and stops being reported), swing the wheel, and reverse in. You
 * finish somewhere between 90 and 180 degrees away from the bearing you would
 * have stored. Storing that is worse than storing nothing, because it is wrong
 * *confidently*.
 *
 * The MPU-6050 on the HUD board is what closes that gap. It keeps reporting
 * yaw rate all the way down to standstill, so the manoeuvre between the last
 * valid GPS bearing and the parked position gets integrated rather than lost.
 * A parking manoeuvre is 20 to 60 seconds; with the bias re-zeroed at the
 * previous stop the residual is a few tenths of a degree per second at worst,
 * so the accumulated error is single-digit degrees. Well inside what "which way
 * do I pull out" needs, which is about plus or minus 30.
 *
 * ## Why a position check and not a timestamp
 *
 * Age is not what invalidates the record — a car left for a fortnight is
 * pointing exactly where it was left. Being *moved* is what invalidates it, and
 * a position check catches that directly: if the first fix after startup is
 * more than [MATCH_RADIUS_M] from where the heading was saved, someone drove
 * the car and the stored heading means nothing. [MAX_AGE_MS] is a backstop for
 * the cases position cannot see (a ferry, a transporter, a tow truck), not the
 * primary guard.
 *
 * ## What this is not
 *
 * It does not survive a genuine cold start — first ever run, or the car moved
 * while everything was powered down. Nothing without a compass can. It covers
 * "I parked here and came back", which is the case that actually happens.
 *
 * Pure logic, no Android imports, so it unit-tests like the rest of the nav
 * code. The caller owns the clock and the storage.
 */
class ParkedHeading {

    companion object {
        /**
         * Above this speed a GPS course is trustworthy and becomes the truth
         * the integration is re-based on.
         *
         * 2 m/s is 7.2 km/h. The same threshold [com.mihai.navhud.HudService]
         * already uses before it will pass a bearing to the router, and it sits
         * comfortably above where course-over-ground turns to noise — receivers
         * generally stop reporting a usable course somewhere under walking
         * pace, because there is not enough displacement between fixes to take
         * a direction from.
         */
        const val GPS_VALID_MPS = 2.0

        /**
         * How close the first fix has to be to the saved position for the saved
         * heading to still apply.
         *
         * This is a *position* tolerance, not an accuracy one. 15 m is roughly
         * two car lengths: enough to absorb the drift of a cold GPS fix taken
         * under trees or beside a building, tight enough that a car moved to a
         * different space in the same car park fails the check.
         */
        const val MATCH_RADIUS_M = 15.0

        /**
         * Backstop for the ways a car moves without the position changing much
         * — a car transporter, a ferry, a tow. Thirty days.
         */
        const val MAX_AGE_MS = 30L * 24 * 3600 * 1000

        /**
         * Do not write more often than this. SharedPreferences is a file, and
         * the heading changes on every fix.
         */
        const val SAVE_INTERVAL_MS = 5_000L

        /** Or when it has turned this far since the last write, whichever first. */
        const val SAVE_DELTA_DEG = 10.0

        /**
         * Stop integrating the gyro after this long without a new sample.
         *
         * The rate is a rate, not a position: re-applying a stale one just
         * spins the estimate. `$IMU` arrives at 20 Hz, so anything past a
         * second and a half means the cable is out or the board is resetting.
         */
        const val IMU_STALE_MS = 1_500L

        /**
         * Refuse to integrate across a gap longer than this in one step.
         *
         * A dt that large means the process was suspended, not that the car
         * turned for four seconds. Integrating it would apply the instantaneous
         * rate across the whole gap.
         */
        const val MAX_STEP_S = 1.0
    }

    /** What gets written to storage and read back on the next run. */
    data class Record(
        val lat: Double,
        val lon: Double,
        val headingDeg: Double,
        val savedAtMs: Long,
    )

    /** The live heading estimate, or null while we have never had one. */
    @Volatile
    var heading: Double? = null
        private set

    /**
     * True when [heading] came from a stored record rather than from this
     * drive's own GPS. Purely for the diagnostics row and for deciding whether
     * to trust it enough to draw a departure arrow.
     */
    @Volatile
    var restored: Boolean = false
        private set

    /** Where we were at the last position update, for the record we write. */
    private var lat: Double? = null
    private var lon: Double? = null

    private var lastImuMs = 0L
    private var lastSavedMs = 0L
    private var lastSavedHeading: Double? = null

    /**
     * Adopt a stored record if the car is still where it was left.
     *
     * @param rec what came out of storage, or null if there was nothing
     * @param fixLat first fix of this run
     * @param fixLon first fix of this run
     * @param wallMs wall-clock time. Must be the same clock [recordToSave]
     *        stamped the record with, and it has to survive a reboot, so this
     *        is `System.currentTimeMillis()` and not `elapsedRealtime()`.
     * @return true if the record was adopted
     */
    @Synchronized
    fun restore(rec: Record?, fixLat: Double, fixLon: Double, wallMs: Long): Boolean {
        if (rec == null) return false
        if (heading != null) return false          // this drive already knows better
        if (wallMs - rec.savedAtMs > MAX_AGE_MS) return false
        // A clock that went backwards (the head unit picking up NTP after boot,
        // which these things do routinely) must not make a good record look
        // like it came from the future and get thrown away.
        if (rec.savedAtMs > wallMs && rec.savedAtMs - wallMs > MAX_AGE_MS) return false
        if (Geo.haversine(rec.lat, rec.lon, fixLat, fixLon) > MATCH_RADIUS_M) return false

        heading = Geo.normalizeDeg(rec.headingDeg)
        restored = true
        lat = fixLat
        lon = fixLon
        return true
    }

    /**
     * A GPS fix.
     *
     * Above [GPS_VALID_MPS] the course is the truth and replaces whatever the
     * gyro had integrated to — that is the drift correction. Below it the
     * bearing is ignored entirely and the gyro carries the estimate, which is
     * the whole point of the exercise.
     */
    @Synchronized
    fun onFix(
        fixLat: Double,
        fixLon: Double,
        bearingDeg: Double?,
        speedMps: Double,
    ) {
        lat = fixLat
        lon = fixLon
        if (bearingDeg != null && speedMps >= GPS_VALID_MPS) {
            heading = Geo.normalizeDeg(bearingDeg)
            restored = false
        }
    }

    /**
     * A `$IMU` yaw rate from the HUD board, degrees per second, clockwise
     * positive — the same sign convention as a compass bearing, so it adds
     * straight on.
     *
     * @param atMs when the sample arrived, on the caller's clock
     */
    @Synchronized
    fun onYawRate(degPerSec: Double, atMs: Long) {
        val prev = lastImuMs
        lastImuMs = atMs
        val h = heading ?: return                  // nothing to integrate yet
        if (prev == 0L) return                     // first sample sets the clock only
        if (atMs <= prev) return                   // clock went backwards or repeated
        if (atMs - prev > IMU_STALE_MS) return     // the board was away; do not guess
        val dt = (atMs - prev) / 1000.0
        if (dt > MAX_STEP_S) return
        heading = Geo.normalizeDeg(h + degPerSec * dt)
    }

    /**
     * The record to write now, or null if there is nothing worth writing or it
     * is too soon since the last one.
     *
     * Throttled two ways because the two failure modes are different: the timer
     * stops a stationary car writing the same value forever, and the angle
     * catches a manoeuvre that has to be captured before the ignition goes off
     * and takes the power with it.
     *
     * Two clocks, deliberately. [nowMs] drives the throttle and must be
     * monotonic (`elapsedRealtime`), because a device that corrects its clock
     * from the network mid-drive would otherwise either block writes for hours
     * or fire one every tick. [wallMs] is what goes *into* the record, and has
     * to be wall-clock so it still means something after a reboot.
     */
    @Synchronized
    fun recordToSave(nowMs: Long, wallMs: Long = nowMs): Record? {
        val h = heading ?: return null
        val la = lat ?: return null
        val lo = lon ?: return null
        val turned = lastSavedHeading?.let { abs(Geo.signedDelta(h, it)) } ?: Double.MAX_VALUE
        val due = lastSavedMs == 0L ||
            nowMs - lastSavedMs >= SAVE_INTERVAL_MS ||
            turned >= SAVE_DELTA_DEG
        if (!due) return null
        lastSavedMs = nowMs
        lastSavedHeading = h
        return Record(la, lo, h, wallMs)
    }

    /**
     * Force a write on the next [recordToSave] regardless of the throttle.
     *
     * Call this when the ignition goes off. The heading at that instant is the
     * one that matters, and the throttle could otherwise be holding a write
     * that never happens because the power is about to go.
     */
    @Synchronized
    fun saveNow() {
        lastSavedMs = 0L
        lastSavedHeading = null
    }

    @Synchronized
    fun reset() {
        heading = null
        restored = false
        lat = null
        lon = null
        lastImuMs = 0L
        lastSavedMs = 0L
        lastSavedHeading = null
    }

    /** One line for the diagnostics row. */
    fun describe(): String = when {
        heading == null -> "parked heading: none"
        restored -> "parked heading: restored %.0f".format(heading)
        else -> "parked heading: live %.0f".format(heading)
    }
}
