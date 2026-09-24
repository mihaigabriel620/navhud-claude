package com.mihai.navhud.location

/**
 * What the car itself says, arriving up the cable from the HUD board.
 *
 * The board has been reading the powertrain bus and rendering speed, rpm and
 * volts on the panel since the MCP2515 went in. None of it was reaching the
 * phone side, which meant the navigation was still working from GPS-derived
 * speed while a far better number sat one serial line away and got thrown out.
 *
 * ## Why the car's own speed is worth having
 *
 * A GPS speed is differentiated position -- or, on a decent receiver, Doppler
 * velocity, which is better but still goes to noise at a standstill. A wheel
 * speed is measured. Three things follow from that:
 *
 *  - **Standstill is knowable.** A parked car reads exactly zero on the bus and
 *    keeps reading zero. GPS reports a metre or two a second of multipath
 *    wander, which is why a parked map marker drifts around the car park. This
 *    is what [stopped] is for.
 *  - **It works with no sky.** Tunnels, multi-storey car parks, the covered bit
 *    of a motorway junction. Dead reckoning wants speed and yaw; the board
 *    supplies both, and u-blox measure the difference between gyro plus
 *    odometry and gyro alone as 2% of distance travelled against 10%.
 *  - **It does not lag.** 0x1A6 updates every 100-300 ms. A GPS fix is once a
 *    second and describes where you were when it was computed.
 *
 * ## The scale factor
 *
 * A wheel speed is a wheel speed: tyre wear, pressure and a non-standard size
 * all shift it, and BMW's cluster reads deliberately optimistic on top. So the
 * raw number is not treated as truth for distance -- [scale] is learned against
 * GPS ground speed whenever both are trustworthy, and the correction is applied
 * by [speedMps]. Until it has been learned it is 1.0, which is no worse than
 * where we started.
 *
 * Pure logic, no Android imports, so it tests on a PC like the rest of the nav
 * code.
 */
class CarLink {

    companion object {
        /** `$CAR,<kmh>,<rpm>,<ps>,<peakPs>,<volts>,<ignition>[,<voltsRaw>]` */
        const val PREFIX = "\$CAR,"

        /**
         * No `$CAR` for this long and the car data is treated as gone.
         *
         * The board sends every 250 ms alongside the frame it draws. Two
         * seconds is eight missed sends: the cable is out, or the board reset,
         * or the ignition went off and took it with it. Deliberately longer
         * than the IMU's 1.5 s, because a stale *speed* is more dangerous than
         * a stale rate -- a rate that stops rotating is merely wrong, a speed
         * that sticks at 50 keeps dead reckoning driving down the road.
         */
        const val STALE_MS = 2_000L

        /**
         * Below this the bus is reporting a stop rather than a crawl.
         *
         * The E60 reports in whole km/h, so this is really "the field is 0".
         * Kept as a threshold rather than an equality test because 0x1A6's
         * decode is a subtraction of two counters and one LSB of jitter should
         * not un-stop the car.
         */
        const val STOPPED_KPH = 1

        /** At or above this the line is a misread, not a speed. */
        const val MAX_PLAUSIBLE_KPH = 350

        /** Standing still for this long before anything is frozen. */
        const val STOPPED_SETTLE_MS = 1_500L

        /** Learn the scale factor only above this, where GPS speed is solid. */
        const val LEARN_ABOVE_MPS = 8.0

        /** One GPS sample moves the scale factor by this much of the error. */
        const val LEARN_ALPHA = 0.02

        /** Refuse to believe a correction outside this. */
        const val SCALE_MIN = 0.90
        const val SCALE_MAX = 1.10

        /**
         * The bus reading zero while GPS says this much, for [ZERO_SUSPECT_MS],
         * is not a stopped car: the board sends 0 when its own speed frame has
         * gone stale. 15 km/h is well clear of GPS standstill wander.
         */
        const val ZERO_SUSPECT_GPS_MPS = 15.0 / 3.6
        const val ZERO_SUSPECT_MS = 3_000L
    }

    @Volatile var speedKph: Int = 0; private set
    @Volatile var rpm: Int = 0; private set
    @Volatile var ps: Int = 0; private set
    @Volatile var volts: Double = 0.0; private set
    @Volatile var voltsRaw: Int = 0; private set
    @Volatile var ignitionOn: Boolean = false; private set

    /** Monotonic time of the last line, or 0 if none has ever arrived. */
    @Volatile var atMs: Long = 0L; private set

    /** Learned wheel-speed correction. 1.0 until GPS has had its say. */
    @Volatile var scale: Double = 1.0; private set

    private var stoppedSinceMs = 0L

    /**
     * Feed one line from the board. Returns true if it was a `$CAR` we
     * understood.
     *
     * Checksum verification is the caller's job -- it already does it for
     * every other line and there is no reason to do it twice.
     */
    @Synchronized
    fun feed(line: String, nowMs: Long): Boolean {
        if (!line.startsWith(PREFIX)) return false
        val star = line.lastIndexOf('*')
        val body = if (star > 0) line.substring(PREFIX.length, star)
                   else line.substring(PREFIX.length)
        val p = body.split(',')
        if (p.size < 6) return false

        val kph = p[0].trim().toIntOrNull() ?: return false
        // An E60's speedometer stops at 260 and the car stops well before
        // that; anything at or over this is 0x1A6 misread, not a speed.
        // Rejected rather than clamped, because a clamped 350 still reads as
        // "moving fast" and the whole point of this number is that everything
        // downstream trusts it more than it trusts GPS.
        if (kph < 0 || kph >= MAX_PLAUSIBLE_KPH) return false

        speedKph = kph
        rpm = p[1].trim().toIntOrNull()?.coerceIn(0, 9000) ?: rpm
        ps = p[2].trim().toIntOrNull() ?: ps
        volts = p[4].trim().toDoubleOrNull() ?: volts
        ignitionOn = p[5].trim() == "1"
        // Optional seventh field: the undivided battery count. Present only on
        // newer firmware, so its absence is not an error -- it is there so the
        // board's volts-per-count scale can be checked against a multimeter,
        // and the true scale is simply (measured volts / this).
        voltsRaw = p.getOrNull(6)?.trim()?.toIntOrNull() ?: voltsRaw
        atMs = nowMs

        if (kph <= STOPPED_KPH) {
            if (stoppedSinceMs == 0L) stoppedSinceMs = nowMs
        } else {
            stoppedSinceMs = 0L
        }
        return true
    }

    /** True while the board is reporting recently enough to be believed. */
    fun fresh(nowMs: Long): Boolean = atMs != 0L && nowMs - atMs < STALE_MS

    /** Latched by [checkPlausible]; cleared by the bus reporting motion again. */
    @Volatile var zeroSuspect: Boolean = false; private set
    private var zeroSinceMs = 0L

    /** Fresh, and not a stuck zero. */
    fun usable(nowMs: Long): Boolean = fresh(nowMs) && !zeroSuspect

    /**
     * Cross-check a zero on the bus against GPS. Call on every tick with the
     * current GPS speed, or null when there is no recent fix -- which can
     * neither raise nor clear the suspicion.
     */
    @Synchronized
    fun checkPlausible(gpsMps: Double?, nowMs: Long) {
        if (speedKph > STOPPED_KPH) { zeroSuspect = false; zeroSinceMs = 0L; return }
        if (!fresh(nowMs) || gpsMps == null) return
        if (gpsMps <= ZERO_SUSPECT_GPS_MPS) { zeroSinceMs = 0L; return }
        if (zeroSinceMs == 0L) zeroSinceMs = nowMs
        if (nowMs - zeroSinceMs >= ZERO_SUSPECT_MS) zeroSuspect = true
    }

    /**
     * Corrected road speed in m/s, or null when there is no usable car data.
     *
     * Null rather than zero, deliberately: a caller that cannot tell "the car
     * says nothing" from "the car says stopped" will happily freeze the map
     * because a cable fell out.
     */
    fun speedMps(nowMs: Long): Double? =
        if (!usable(nowMs)) null else speedKph / 3.6 * scale

    /**
     * What the car's own speedometer path says, uncorrected, or null.
     *
     * The number to *show*: the HUD firmware draws the raw bus speed, so the
     * app gauge and the over-limit check must use the same one or the two
     * displays disagree by the learned scale. [speedMps] is for distance.
     */
    fun rawSpeedMps(nowMs: Long): Double? =
        if (!usable(nowMs)) null else speedKph / 3.6

    /**
     * The car has been standing still long enough to act on it.
     *
     * Used to freeze the marker rather than let GPS multipath walk it around
     * the car park, and to re-zero the gyro bias, which is the one moment the
     * true rate is known to be zero.
     */
    fun stopped(nowMs: Long): Boolean =
        usable(nowMs) && stoppedSinceMs != 0L && nowMs - stoppedSinceMs >= STOPPED_SETTLE_MS

    /**
     * Nudge the wheel-speed correction using a GPS ground speed.
     *
     * Only above [LEARN_ABOVE_MPS], where a GPS speed is worth comparing
     * against, and only while the car data is fresh. Slow, because this is a
     * property of the tyres and it has all day to converge.
     */
    @Synchronized
    fun learnScale(gpsMps: Double, nowMs: Long) {
        if (!fresh(nowMs)) return
        if (gpsMps < LEARN_ABOVE_MPS) return
        val raw = speedKph / 3.6
        if (raw < LEARN_ABOVE_MPS) return
        val want = gpsMps / raw
        if (want < SCALE_MIN || want > SCALE_MAX) return   // one of them is wrong
        scale = (scale + LEARN_ALPHA * (want - scale)).coerceIn(SCALE_MIN, SCALE_MAX)
    }

    @Synchronized
    fun reset() {
        speedKph = 0; rpm = 0; ps = 0; volts = 0.0; voltsRaw = 0
        ignitionOn = false; atMs = 0L; stoppedSinceMs = 0L
        zeroSuspect = false; zeroSinceMs = 0L
        // scale survives: it is a property of the car's tyres, not of this
        // drive, and throwing it away means relearning it every trip.
    }

    fun describe(nowMs: Long): String = when {
        !fresh(nowMs) -> "car: no data"
        stopped(nowMs) -> "car: stopped"
        else -> "car: %d km/h, %d rpm, x%.3f".format(speedKph, rpm, scale)
    }
}
