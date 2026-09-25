package com.mihai.navhud.map

import com.mihai.navhud.Geo
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Which way the car is pointing, between GPS fixes.
 *
 * A GPS bearing arrives once a second, is derived from the *movement* between
 * fixes rather than measured, and is meaningless below walking pace. Turn the
 * wheel and the marker sits there for most of a second and then swings. Every
 * nav app solves this the same way: integrate a gyroscope for the fast part of
 * the motion and let GPS correct the slow drift. That is all this is -- a
 * complementary filter, gyro on the short timescale, GPS on the long one.
 *
 * Two details that matter in a car:
 *
 *  - **The phone is not level.** A cradle sits at whatever angle it sits at, so
 *    the yaw rate is not the gyro's z axis. Projecting the rate vector onto the
 *    gravity direction gives rotation about the true vertical whatever the
 *    mounting angle, which is why gravity is a parameter here.
 *  - **Gyros drift.** A few tenths of a degree per second, integrated for ten
 *    minutes in a tunnel, is a wrong answer. The bias is learned whenever the
 *    car is stopped and the sensor is reading close to nothing -- the one time
 *    we know the true rate is zero.
 */
class HeadingFusion {

    // Thread safety, deliberately blunt.
    //
    // The sensor callbacks run on their own looper (they used to run on the
    // main one, where 150 events a second jittered the camera frames), while
    // GPS fixes and the HUD board's IMU rate arrive on the main thread from
    // the frame loop. Both mutate the same estimate. Every entry point is
    // therefore @Synchronized, and every published value is @Volatile so a
    // reader never has to take the lock. These calls are a few dozen
    // instructions each and the lock is essentially never contended, so the
    // cost is nothing next to being wrong.

    companion object {
        /** How fast GPS pulls the estimate back. Small = trust the gyro longer. */
        const val TAU_GPS_S = 1.0

        /**
         * Weight of one new compass sample, per sample.
         *
         * OsmAnd's number, unchanged in their tree since 2013, applied the same
         * way: as an exponential average of sin and cos rather than of the
         * angle, which is what makes it correct across the 0/360 seam. At the
         * ~15 Hz the rotation vector reports, 0.08 settles in about half a
         * second — quick enough to feel live, slow enough that one bad sample
         * moves the arrow by three degrees rather than forty.
         */
        const val COMPASS_ALPHA = 0.08

        /**
         * How much of the compass/gyro disagreement to remove per compass
         * sample, while a real rate sensor is driving the estimate.
         *
         * At the ~15 Hz a rotation vector reports, 0.05 removes about half the
         * error in a second -- fast enough that drift never accumulates,
         * gentle enough that one bad reading beside a speaker moves the arrow
         * by a degree rather than by forty.
         */
        const val COMPASS_TRIM_WITH_RATE = 0.05

        /**
         * No `$MAG` for this long and the board's compass is treated as gone.
         *
         * The board sends one every 200 ms, so two seconds is ten missed
         * sends: the cable is out, or the board reset, or the magnetometer
         * stopped answering. Until then the phone's compass stays out of the
         * way entirely -- see [onCompass].
         */
        const val HUD_COMPASS_STALE_MS = 2_000L

        /**
         * Smoothing time constant for the compass, seconds.
         *
         * A per-sample weight of 0.08 measured out at 53 degrees of lag while
         * a parked car swings at 60 deg/s, and two and a half seconds to
         * settle after a 60 degree step — which is not "acts like a compass",
         * it is an arrow being dragged along behind the car. 0.35 s settles in
         * about a second and still costs a single bad sample most of its
         * effect. The three gates upstream are what make a shorter constant
         * safe: what reaches this filter has already been checked.
         */
        const val TAU_COMPASS_S = 0.35

        /**
         * A GPS fix at or above this speed takes the heading, m/s.
         *
         * 0.7 m/s is 2.5 km/h — walking pace. It is not a number I picked:
         * Organic Maps and CoMaps arrived at it independently in 2025, both
         * having started higher (Organic Maps was at 2.8 m/s, itself copied
         * from LocusMap) and both having come down. OsmAnd is lower still, at
         * 0.1 m/s.
         *
         * The reasoning is the same everywhere. A compass inside a car is a
         * coarse instrument — a steel shell, speaker magnets, a couple of
         * hundred amps past the dashboard — and its one advantage is that it
         * works standing still. The moment the car is moving, GPS is measuring
         * the direction of travel from orbit and nothing in the car can bend
         * it. There is no speed band where the compass is the better answer
         * *and* the car is moving.
         */
        const val GPS_HEADING_MIN_MPS = 0.7

        /**
         * How long a GPS bearing keeps the compass locked out, ms.
         *
         * Organic Maps uses 3 s, CoMaps 5 s, and the contributor whose report
         * is cited in their source explains the trade-off exactly: shorter
         * brings the compass back promptly when you stop, and *"if you make it
         * very small, the map might start twitching from constant switches
         * during stops."* Four seconds sits between the two.
         */
        const val GPS_HEADING_LIFETIME_MS = 4000L

        /**
         * Above this, GPS alone turns the arrow. 5 km/h.
         *
         * Below it the compass drives, which is what makes the marker behave
         * the way a compass behaves: turn the car and the arrow turns, at a
         * standstill, at walking pace, shunting into a space — no waiting for
         * the fix to accumulate enough displacement to have a bearing at all.
         *
         * This used to be 19.44 — 70 km/h — and that was the right answer for
         * the hardware it was written for: a phone in a cradle, whose compass
         * is being lied to by a steel shell full of speaker magnets and a
         * couple of hundred amps, and which is therefore worth preferring to
         * GPS only where GPS has nothing better to offer. Keeping the compass
         * up to motorway speed bought back a fast-responding arrow in town,
         * and the mounting offset relearned from every GPS bearing was what
         * made that safe.
         *
         * None of that reasoning survives the move to a head unit. The
         * magnetometer is on the HUD board now, bolted to the car, hard iron
         * calibrated out once and constant thereafter — a genuinely good
         * instrument, but still a magnetometer, and still a worse measurement
         * of the direction of travel than a GPS bearing taken from orbit,
         * which nothing in the car can bend. So the crossover belongs just
         * above walking pace: the compass covers the standstill, the
         * three-point turn and the car park, which is exactly the band where
         * GPS has no bearing to give, and hands over the moment there is one
         * worth having.
         */
        const val COMPASS_MAX_MPS = 1.39

        /**
         * Hand it back to the compass below this. 3.5 km/h.
         *
         * A km/h and a half of hysteresis, so creeping forward in a queue does
         * not flip the source back and forth with every gust of GPS speed.
         */
        const val COMPASS_RESUME_MPS = 0.97

        /**
         * Disagreement with GPS this large, sustained for
         * [DISTRUST_AFTER_MS], means the compass is being lied to.
         *
         * ArduPilot's `use_compass()` test, numbers included. The sustain is
         * the important half: a magnetometer in a car throws single readings
         * tens of degrees out all the time, and reacting to one of those is
         * how you get an arrow that flicks. Reacting to two seconds of them is
         * how you notice that the mount is sitting on a speaker.
         */
        const val DISTRUST_OVER_DEG = 45.0
        const val DISTRUST_AFTER_MS = 2000L

        /** A compass reading older than this is not evidence of anything. */
        const val COMPASS_STALE_MS = 5000L

        /** Forgive a distrusted compass after this long without a fresh row. */
        const val FORGIVE_AFTER_MS = 60_000L

        /** Below this a GPS bearing is noise, not a direction. */
        const val GPS_TRUST_MPS = 2.5

        /**
         * Learn how far the board's compass is out, against the GPS course, at
         * or above this speed -- where a GPS bearing is a measurement.
         */
        const val HUD_LEARN_MPS = GPS_TRUST_MPS

        /** How quickly the learned correction follows the evidence, seconds. */
        const val HUD_LEARN_TAU_S = 3.0

        /**
         * A GPS course that turned more than this since the previous fix is a
         * bend being driven. GPS lags the car through it, so the two disagree
         * there for reasons that are not the compass's.
         */
        const val HUD_LEARN_MAX_TURN_DEG = 8.0

        /** The previous bearing is only "the previous fix" within this, ms. */
        const val HUD_LEARN_GAP_MS = 2_500L

        /** Below this the car is parked, so the gyro should be reading zero. */
        const val STATIONARY_MPS = 0.7

        /** A gap longer than this means we missed samples; don't integrate it. */
        const val MAX_GYRO_STEP_S = 0.25

        /** Bias is learned slowly -- it is a property of the chip, not the drive. */
        const val BIAS_TAU_S = 12.0

        /** ~2.9 deg/s. Anything larger while parked is real motion, not bias. */
        const val BIAS_LEARN_LIMIT_RADS = 0.05

        /** Cap on the learned correction, so one bad idle cannot poison it. */
        const val MAX_BIAS_RADS = 0.05

        /** Disagreeing with GPS by more than this means we are simply wrong. */
        const val SNAP_IF_OFF_BY_DEG = 60.0

        /**
         * A raw gyro reading below this, while parked, is noise rather than the
         * car turning: ~1.7 deg/s. Integrating noise for the twenty minutes the
         * app sits on a driveway would rotate the map through a slow, ghostly
         * turn with the handbrake on.
         *
         * The rotation-vector path does not need this — it is absolute, so it
         * cannot accumulate anything — which is exactly why it is preferred.
         */
        const val PARKED_DEADBAND_RADS = 0.03

        /**
         * Ignore a rotation-vector step longer than this; the sensor was
         * probably paused (screen off, app backgrounded) and the "delta" is
         * really the whole rotation since it stopped reporting.
         */
        const val MAX_ORIENTATION_STEP_S = 0.5

        /**
         * Extract the yaw component of a small rotation matrix, in radians,
         * positive **clockwise seen from above** (i.e. heading increasing).
         *
         * `m` is a world-frame relative rotation, row-major 3x3. For a rotation
         * matrix the axis-angle vector is (m21-m12, m02-m20, m10-m01)/2 to
         * first order in the angle, and in Android's ENU world frame the third
         * component is the rotation about the local vertical. Sampling at 50 Hz
         * makes the angle per step a fraction of a degree, so "to first order"
         * is not an approximation worth worrying about.
         *
         * Doing it this way rather than through SensorManager.getOrientation is
         * deliberate. getOrientation reports the azimuth of the phone's own y
         * axis, which is meaningless when the phone is upright in a cradle —
         * that axis points at the sky, and the azimuth degenerates exactly at
         * the mounting angle a car phone holder puts it at. A vertical-axis
         * projection has no such singularity, and needs to know nothing about
         * how the phone is mounted.
         */
        fun yawOf(m: DoubleArray): Double = -(m[3] - m[1]) / 2.0
    }

    /**
     * Multiply a row-major 3x3 by the transpose of another: `a * bᵀ`.
     * The relative rotation from orientation `b` to orientation `a`.
     */
    private fun timesTranspose(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) {
            var v = 0.0
            for (k in 0..2) v += a[r * 3 + k] * b[c * 3 + k]
            out[r * 3 + c] = v
        }
        return out
    }

    /**
     * Where the heading is coming from right now.
     *
     * All three work. They differ in how quickly the map answers the steering
     * wheel: the HUD's own sensor is bolted to the car and cannot be knocked,
     * the phone's is nearly as good but sits in a cradle, and plain GPS turns
     * about a second late. Plenty of Android head units have no gyroscope at
     * all, so the last one is a real configuration and not a failure state.
     */
    enum class Source { HUD_IMU, PHONE_ROTATION, PHONE_GYRO, GPS_ONLY }

    @Volatile
    var source: Source = Source.GPS_ONLY
        private set

    /** Fused heading in degrees, or null until the first usable input. */
    @Volatile
    var heading: Double? = null
        private set

    /** Learned zero-rate offset, rad/s. Exposed for the diagnostics screen. */
    var biasRadS = 0.0
        private set

    /** True once a rate sensor has actually delivered something. */
    var hasRateSensor = false
        private set

    private var stationary = true

    /**
     * Above [COMPASS_MAX_MPS], until back under [COMPASS_RESUME_MPS].
     *
     * While moving, the heading is the GPS course and nothing else: the gyro,
     * the orientation sensor and the board's yaw rate are ignored, as the
     * compass already was. Integrated turn rates on a head unit drifted the
     * arrow off the road it was plainly driving along, and at speed the GPS
     * course -- or the road bearing, when snapped -- is the better answer.
     * Standing still and crawling they still do everything they did.
     */
    @Volatile
    var moving = false
        private set

    private fun noteSpeed(mps: Double) {
        lastSpeedMps = mps
        stationary = mps < STATIONARY_MPS
        moving = if (moving) mps >= COMPASS_RESUME_MPS else mps > COMPASS_MAX_MPS
    }

    /**
     * One gyroscope sample.
     *
     * @param gx,gy,gz angular rate in rad/s, device axes, right-hand rule
     * @param ux,uy,uz gravity direction in the same axes; Android's gravity
     *                 sensor points *up*, which is the convention assumed here
     * @param dtS      seconds since the previous sample
     */
    @Synchronized
    fun onGyro(
        gx: Double, gy: Double, gz: Double,
        ux: Double, uy: Double, uz: Double,
        dtS: Double
    ) {
        if (dtS <= 0.0 || dtS > MAX_GYRO_STEP_S) return
        val n = sqrt(ux * ux + uy * uy + uz * uz)
        if (n < 1e-3) return
        hasRateSensor = true
        // Read the source *before* claiming it. Writing PHONE_GYRO here first
        // and testing for PHONE_ROTATION afterwards made the test unreachable,
        // so on any phone with both sensors -- which is almost all of them --
        // the gyro and the rotation vector each integrated the same turn and
        // the map rotated through twice the angle the car did.
        val advisoryOnly = source == Source.PHONE_ROTATION || source == Source.HUD_IMU
        if (source == Source.GPS_ONLY) source = Source.PHONE_GYRO

        // Rate about the vertical. Positive is anticlockwise seen from above,
        // i.e. turning left, i.e. heading decreasing -- hence the minus below.
        val wUp = (gx * ux + gy * uy + gz * uz) / n

        // The rotation vector is strictly better; if it is feeding us, this
        // sample is only here to keep the bias estimate warm.
        if (stationary) {
            if (abs(wUp) < BIAS_LEARN_LIMIT_RADS) {
                val k = 1.0 - exp(-dtS / BIAS_TAU_S)
                biasRadS = (biasRadS + k * (wUp - biasRadS))
                    .coerceIn(-MAX_BIAS_RADS, MAX_BIAS_RADS)
            }
            // Parked used to mean "do not integrate at all", which is why the
            // arrow was frozen on a stationary car: turn the wheel into a
            // parking space and the map sat there pointing the way you came
            // in. It moves now — but only for a rate clearly above the noise
            // floor, so a phone sitting still does not drift.
            if (advisoryOnly || abs(wUp - biasRadS) < PARKED_DEADBAND_RADS) return
        }

        if (advisoryOnly || moving) return
        val h = heading ?: return
        heading = Geo.normalizeDeg(h - Math.toDegrees((wUp - biasRadS) * dtS))
    }

    /**
     * A yaw rate straight from the HUD's own IMU, in degrees per second,
     * positive clockwise. The module is bolted to the car rather than sitting in
     * a cradle that gets knocked, so no gravity projection is needed.
     */
    @Synchronized
    fun onExternalYawRate(degPerSec: Double, dtS: Double) {
        if (dtS <= 0.0) return
        // Claim the source BEFORE the step check, not after.
        //
        // This used to read `if (dtS <= 0 || dtS > MAX_GYRO_STEP_S) return`
        // above these two lines, which left a dead band: a caller clamping to
        // 0.5 s (which MapActivity does) could hand over a dt between 0.25 and
        // 0.5, the sample was dropped, and `source` stayed on whatever it was
        // before -- so a single frame-loop hitch handed the heading back to the
        // phone's rotation vector while the HUD was plugged in and reporting.
        // The board is connected and talking either way; that fact does not
        // depend on how long the last gap happened to be.
        hasRateSensor = true
        source = Source.HUD_IMU
        // A gap longer than one step is a scheduling stall, not a four-second
        // turn. Integrate what the step is worth rather than discarding the
        // rotation entirely: losing a whole roundabout because one frame was
        // late is worse than being slightly short.
        val step = if (dtS > MAX_GYRO_STEP_S) MAX_GYRO_STEP_S else dtS
        if (moving) return
        // Bolted to the car, so it turns when the car turns even at a
        // standstill — a three-point turn, a car park, a ferry ramp. The
        // deadband keeps its own noise from creeping while parked.
        if (stationary && abs(degPerSec) < Math.toDegrees(PARKED_DEADBAND_RADS)) return
        val h = heading ?: return
        heading = Geo.normalizeDeg(h + degPerSec * step)
    }

    /**
     * The HUD has stopped reporting — unplugged, or it never had a sensor.
     * Falls back to the phone's gyroscope, or to GPS alone if there isn't one.
     */
    @Synchronized
    fun onExternalLost(phoneGyroPresent: Boolean) {
        if (source != Source.HUD_IMU) return
        source = when {
            lastR != null -> Source.PHONE_ROTATION
            phoneGyroPresent -> Source.PHONE_GYRO
            else -> Source.GPS_ONLY
        }
        hasRateSensor = phoneGyroPresent || lastR != null
    }

    private var lastR: DoubleArray? = null
    private var lastFrameId = Int.MIN_VALUE

    /** Set once the phone has produced a usable compass reading at all. */
    @Volatile
    var hasCompass = false
        private set

    /**
     * One sample from the phone's orientation sensor, as a row-major 3x3
     * device-to-world rotation matrix.
     *
     * This is the answer to "make the arrow point where the car points even
     * when standing still". A gyroscope cannot do it: integrate it at a
     * standstill and you integrate its drift, so the honest thing was to
     * freeze. Android's rotation vector is gyroscope, accelerometer and
     * magnetometer fused into an *absolute* orientation, so the change between
     * two samples is a real rotation, not an accumulating guess — turn the car
     * in a car park with the engine idling and the map comes round with it.
     *
     * Only the change is used, never the absolute compass bearing. A car is a
     * steel box full of speakers and current, and the magnetic north it
     * reports can be tens of degrees out; but that error is roughly constant
     * over the seconds a turn takes, so it cancels in the difference. North
     * comes from GPS once you are moving, as before.
     */
    @Synchronized
    fun onOrientation(r: DoubleArray, dtS: Double, frameId: Int = 0) {
        if (r.size != 9) return
        // A difference is only meaningful between two readings of the *same*
        // sensor. Android's rotation vectors report the same physical
        // orientation in different yaw frames -- the game variant has no
        // magnetometer, so its zero is arbitrary and drifts -- and subtracting
        // one from the other yields the angle between two reference frames
        // rather than any motion of the car. Fed alternately, that made the
        // arrow spin on the spot.
        //
        // The caller is supposed to send one sensor. This makes it impossible
        // for a mistake there to become a phantom turn: a new frame resets the
        // reference instead of being differenced against the old one, costing
        // a single sample.
        val prev = if (frameId == lastFrameId) lastR else null
        lastFrameId = frameId
        // Always kept current, even when something better is driving the
        // heading. It used to stop updating while the HUD's own IMU was
        // connected, so the first sample after the HUD dropped out compared a
        // minutes-old orientation against the present one and applied the
        // whole difference in a single 20 ms step -- a visible jerk of up to
        // 30 degrees exactly when you unplugged the cable.
        lastR = r.copyOf()
        hasRateSensor = true
        if (source == Source.HUD_IMU) return
        source = Source.PHONE_ROTATION
        if (moving || prev == null || dtS <= 0.0 || dtS > MAX_ORIENTATION_STEP_S) return
        val h = heading ?: return
        val dYaw = Math.toDegrees(yawOf(timesTranspose(r, prev)))
        // A single step is a fraction of a degree. Anything bigger is the
        // sensor resuming after a gap, not the car.
        if (abs(dYaw) > 30.0) return
        heading = Geo.normalizeDeg(h + dYaw)
    }

    /**
     * A GPS fix. Sets the reference the gyro drifts away from.
     *
     * @param dtS seconds since the previous fix, which sets how hard to pull.
     */
    /** Milliseconds, supplied by the caller so this stays testable. */
    /** 0 means "GPS has never given us a bearing", not "at time zero". */
    @Volatile
    private var lastGpsHeadingMs = 0L
    @Volatile
    private var disagreeingSinceMs = 0L
    @Volatile
    private var nowMs = 0L

    /**
     * True while the compass is the thing driving the arrow.
     *
     * Speed decides, not recency of a GPS fix. Hysteresis around the
     * changeover: once GPS has taken over it keeps the arrow until the car
     * drops back under [COMPASS_RESUME_MPS].
     */
    val usingCompass: Boolean
        get() {
            if (!hasCompass || compassDistrusted) return false
            // A *fresh* reading, not merely "this phone has a magnetometer".
            //
            // hasCompass latches on the first sample and never clears, and the
            // compass can be silenced without ever being distrusted: the
            // magnetic-field gate rejects everything on a neodymium mount, a
            // multi-storey kills the field, a tunnel does the same. With only
            // the latch to go on, usingCompass stayed true, onFix returned
            // without ever writing `heading`, and nothing could correct the
            // arrow below the crossover for the rest of the drive -- while the
            // distrust path that exists for exactly this sat unreachable,
            // because it needs a fresh compass reading to compare against.
            if (lastCompassMs == 0L || nowMs - lastCompassMs > COMPASS_STALE_MS) return false
            val ceiling = if (compassDrove) COMPASS_MAX_MPS else COMPASS_RESUME_MPS
            return lastSpeedMps < ceiling
        }

    /** Which side of the hysteresis we came from. */
    @Volatile
    private var compassDrove = true

    /** Speed of the last fix, m/s. Drives the compass/GPS changeover. */
    @Volatile
    private var lastSpeedMps = 0.0

    /**
     * Set when the compass has been arguing with GPS for too long.
     *
     * Cleared by agreement, and also simply by time: the clearing path used to
     * be reachable only while the car was moving above walking pace, so a
     * two-second disagreement as you pulled up switched the standing-still
     * compass off until the next drive — which is the one moment it exists for.
     */
    val compassDistrusted: Boolean
        get() = distrustedAtMs != 0L && nowMs - distrustedAtMs < FORGIVE_AFTER_MS

    @Volatile
    private var distrustedAtMs = 0L

    /** The caller's clock. Call before onFix/onCompass in a tick. */
    @Synchronized
    fun setClock(ms: Long) { nowMs = ms }

    @Synchronized
    fun onFix(bearingDeg: Double?, speedMps: Double, dtS: Double) {
        noteSpeed(speedMps)
        compassDrove = usingCompass
        if (bearingDeg == null || speedMps < GPS_HEADING_MIN_MPS) return
        val g = Geo.normalizeDeg(bearingDeg)

        // The latch, first, unconditionally. A usable GPS bearing takes the
        // heading and locks the compass out for a few seconds — this *is* the
        // arbitration, and it has to happen before any of the branches below
        // can return early. It sat after them once, behind a condition that is
        // false whenever the compass is the thing driving, so it never fired
        // at all in exactly the case it exists for.
        lastGpsHeadingMs = nowMs
        gpsFixedHeading = true

        // While we have both, watch whether they agree.
        // Only against a *fresh* compass reading. Drive into a car park, lose
        // the magnetometer to the concrete for ten minutes, come out facing the
        // other way, and the last reading is now 180 degrees out through no
        // fault of the sensor -- three fixes later the compass is demoted on
        // the strength of data it never produced.
        val c = lastCompass?.takeIf { nowMs - lastCompassMs <= COMPASS_STALE_MS }
        if (c == null) {
            // No usable compass to judge, so there is no *run* of disagreement
            // in progress. Leaving the timestamp standing meant a single spike
            // minutes ago, followed by a tunnel, made the next disagreeing fix
            // satisfy "sustained for two seconds" instantly -- which is exactly
            // the hysteresis this exists to provide.
            disagreeingSinceMs = 0L
        } else {
            if (abs(Geo.signedDelta(c, g)) > DISTRUST_OVER_DEG) {
                if (disagreeingSinceMs == 0L) disagreeingSinceMs = nowMs
                else if (nowMs - disagreeingSinceMs >= DISTRUST_AFTER_MS) distrustedAtMs = nowMs
            } else {
                disagreeingSinceMs = 0L
                distrustedAtMs = 0L
            }
        }

        learnHudCorrection(g, speedMps, dtS)

        // Below the changeover the compass owns the arrow, and this fix is
        // only here to be compared against it (the distrust test above) and to
        // let the caller re-learn the mounting offset from it. Writing the GPS
        // bearing into `heading` here is what used to make the compass
        // irrelevant the moment the car started rolling.
        if (usingCompass) return

        val h = heading
        // Nothing to blend with, or the compass was in charge until now: take
        // the bearing outright rather than easing from a stale estimate.
        if (h == null || !hasRateSensor) { heading = g; return }

        val err = Geo.signedDelta(g, h)
        // Wildly out: the gyro estimate is not worth blending, start again.
        if (abs(err) > SNAP_IF_OFF_BY_DEG) { heading = g; return }

        val k = if (dtS <= 0.0) 1.0
                else (1.0 - exp(-dtS / TAU_GPS_S)).coerceIn(0.0, 1.0)
        heading = Geo.normalizeDeg(h + k * err)
    }

    /**
     * A speed measured by the car rather than derived from GPS.
     *
     * Overrides what [onFix] concluded about standing still, and only that --
     * the heading itself is untouched, because a speed says nothing about
     * direction. Worth a separate entry point because the two numbers are not
     * interchangeable: GPS speed floors out around 1-2 m/s of noise on a
     * parked car and the bus reports zero.
     */
    /**
     * An absolute heading from the HUD board's own magnetometer.
     *
     * Routed through the same machinery as the phone's compass, because it is
     * the same kind of measurement and the arbitration that was written for
     * one is right for the other: it establishes the heading when there is
     * none, and eases it the rest of the time so the board's gyro still does
     * the fast part.
     *
     * Better than a phone compass in the one way that matters, though. The
     * board is bolted to the car, so its hard-iron offset is a constant and
     * has been calibrated out; a phone in a cradle is a different magnetic
     * situation every time it is picked up.
     */
    @Synchronized
    fun onExternalCompass(headingDeg: Double, nowMs: Long) {
        // The board's `$MAG` row carries its own timestamp, and on a head unit
        // it is the only thing that ever advances the clock.
        //
        // `nowMs` is a parameter here and also a field, and the parameter wins:
        // the two lines below stamped `lastCompassMs` correctly while the field
        // stayed at 0, because the only callers of setClock were the GPS-fix
        // handler and the phone's own sensor callback -- neither of which ever
        // fires on a unit with no sensors and no fix. onCompass then wrote
        // `lastCompassMs = nowMs` from the *field*, i.e. 0, and usingCompass
        // reads a zero there as "the compass has never reported". So the one
        // absolute source in the system was permanently disqualified by the
        // freshness test, and the arrow froze while the phone was correctly
        // refusing to use sensors it does not have.
        setClock(nowMs)
        hasCompass = true
        lastCompassMs = nowMs
        lastHudCompassMs = nowMs
        lastCompass = Geo.normalizeDeg(headingDeg)
        onCompass(lastCompass!!, fromHud = true)
    }

    /**
     * What to add to the board's compass to get the car's heading. Learned
     * against the GPS course while driving; null until something has been.
     *
     * The board is flat on the dashboard, so on a hill it is tilted with the
     * car, and with the Earth's field dipping ~65 degrees here every degree
     * of tilt is about two of heading: a 10 % slope is 10-15 degrees. It
     * cannot see that on its own -- there is no accelerometer, and facing
     * east or west a tilt barely moves any axis it has except the heading.
     * The same goes for which way round the box sits on the dash, and for a
     * calibration or a declination that is a little out.
     *
     * None of that needs to be known. Driving, GPS measures the direction of
     * travel, and the difference from the compass is learned here, on the
     * road the car is on. Pull up and the compass takes the arrow with that
     * difference added: the same slope, facing the same way, so the arrow
     * stays exactly where GPS left it, and turning at a crawl turns it by what
     * the compass saw change. Carried across restarts by the map screen, so a
     * car that has not moved starts pointing the way it was left.
     */
    @Volatile
    var hudCorrection: Double? = null
        private set

    /** The previous usable GPS bearing, and when, to tell a straight from a bend. */
    private var lastGpsBearing: Double? = null
    private var lastGpsBearingMs = 0L

    /** A correction carried over from the last drive, if none has been learned yet. */
    @Synchronized
    fun restoreHudCorrection(deg: Double?) {
        if (hudCorrection == null && deg != null && !deg.isNaN()) {
            hudCorrection = Geo.signedDelta(deg, 0.0)
        }
    }

    /**
     * One GPS bearing's worth of evidence about the board's compass: only on a
     * straight, at a speed where the bearing means something, against a fresh
     * board reading. The first piece is taken whole; after that it is eased in
     * over [HUD_LEARN_TAU_S], so one bad bearing moves it by a fraction.
     */
    private fun learnHudCorrection(g: Double, speedMps: Double, dtS: Double) {
        val prev = lastGpsBearing?.takeIf { nowMs - lastGpsBearingMs <= HUD_LEARN_GAP_MS }
        lastGpsBearing = g
        lastGpsBearingMs = nowMs
        if (speedMps < HUD_LEARN_MPS || !hudCompassLive) return
        if (prev == null || abs(Geo.signedDelta(g, prev)) > HUD_LEARN_MAX_TURN_DEG) return
        val c = lastCompass ?: return
        val fresh = Geo.signedDelta(g, c)
        val cur = hudCorrection
        hudCorrection = if (cur == null) fresh else {
            val k = if (dtS <= 0.0) 1.0
                    else (1.0 - exp(-dtS / HUD_LEARN_TAU_S)).coerceIn(0.0, 1.0)
            Geo.signedDelta(cur + k * Geo.signedDelta(fresh, cur), 0.0)
        }
    }

    /** The compass reading for the arrow: the board's, with what driving taught us. */
    private fun arrowCompass(): Double? {
        val c = lastCompass ?: return null
        val k = hudCorrection?.takeIf { hudCompassLive } ?: return c
        return Geo.normalizeDeg(c + k)
    }

    @Synchronized
    fun noteVehicleSpeed(mps: Double) = noteSpeed(mps)

    /** Force the estimate, e.g. from the road direction when snapped to route. */
    @Synchronized
    fun set(headingDeg: Double) { heading = Geo.normalizeDeg(headingDeg) }

    /** True once a GPS bearing at road speed has set the absolute reference. */
    @Volatile
    var gpsFixedHeading = false
        private set

    /**
     * An absolute heading from the phone's compass, already corrected by the
     * learned mounting offset. See [com.mihai.navhud.map.Compass].
     *
     * This is only allowed to *set* the heading, never to correct one GPS has
     * already established. Once the car has moved at road speed, the direction
     * it moved in is the truth and the compass is a worse measurement of the
     * same thing — but until then it is the only thing in the phone that knows
     * which way the car is pointing, and without it the arrow points north.
     */
    /**
     * The heading from the phone's compass, below [COMPASS_MAX_MPS].
     *
     * This is the *primary* source below walking pace rather than a fallback,
     * which is what makes the arrow behave the way a compass behaves: point the
     * car somewhere and the arrow points there, standing still, immediately,
     * with no dead reckoning to go wrong. What it replaces was a gyroscope
     * integrating differences with GPS as its only absolute reference — more
     * machinery, worse behaviour at exactly the speeds where you look at it.
     *
     * Eased rather than snapped, because a magnetometer in a car jitters by a
     * degree or two, and a marker that twitches is worse than one that lags by
     * a quarter of a second.
     */
    @Synchronized
    fun onCompass(headingDeg: Double, dtS: Double = 0.2, fromHud: Boolean = false) {
        // The board's compass outranks the phone's, and it is not close.
        //
        // Both of them used to arrive here with nothing to tell them apart, so
        // whichever reported more often won -- and the phone reports far more
        // often. The symptom was exact and small: with the HUD plugged in and
        // driving the heading, picking up the phone and turning it moved the
        // arrow *slightly*. Slightly, because this is a 5 %-per-sample trim
        // rather than an integration, which is what made it look like a
        // mystery rather than an obvious second source.
        //
        // The board is bolted to the car. Its hard-iron offset is a constant
        // and has been calibrated out, it does not care which way the driver
        // is holding anything, and on a head unit -- which has no sensors at
        // all -- the phone's "compass" is at best a vendor stub. There is no
        // situation where the phone's reading improves on the board's.
        //
        // Note this is NOT the guard that used to live further down and was
        // removed as wrong. That one blocked every compass whenever the board
        // supplied a yaw *rate*, which locked out the only absolute source in
        // the system and left the arrow never appearing at all. This blocks
        // the phone's compass only while the board's own compass is reporting,
        // and falls straight back to the phone the moment the cable is out.
        if (!fromHud && hudCompassLive) return
        hasCompass = true
        val g = Geo.normalizeDeg(headingDeg)
        // Time-based, so the filter behaves the same whether the sensor is
        // reporting at 15 Hz or 50 Hz. A fixed per-sample weight made the
        // response three times slower on a phone with no game rotation vector,
        // silently, because that path registers the sensor at a faster rate.
        val a = 1.0 - exp(-dtS.coerceIn(0.005, 1.0) / TAU_COMPASS_S)
        // Kept even when it is not driving anything, so the agreement test in
        // onFix has something to compare against.
        lastCompass = if (lastCompass == null) g
                      else Compass.emaStep(lastCompass!!, g, a)
        lastCompassMs = nowMs
        // Note there is no HUD_IMU guard here. There was, and it was wrong: an
        // IMU reports a yaw *rate*, which can tell you how far the car turned
        // and never which way it is pointing. Blocking the compass on account
        // of it left the one absolute source unable to set anything, so with
        // the HUD plugged in and no GPS the arrow simply never appeared.
        if (!usingCompass) return
        // Corrected by what driving taught us (hudCorrection): the raw reading
        // stays in lastCompass, which is what the learning compares.
        val c = arrowCompass() ?: return
        val h = heading

        // With no estimate yet, or no board reporting, take the reading whole.
        //
        // This branch has to exist. A gyro reports a turn *rate*: it can say
        // how far the car turned and never which way it is pointing, so it can
        // never establish a heading from nothing and must not be allowed to
        // block the one sensor that can.
        if (h == null || source != Source.HUD_IMU) { heading = c; return }

        // But when the board *is* integrating, ease toward the compass instead
        // of restarting from it. Taking it whole ran at 5-15 Hz for as long as
        // the compass was driving, which meant every increment the board
        // contributed was discarded before the next frame -- the gyro was
        // connected, reporting, and changing nothing. Easing keeps the
        // absolute reference (the drift
        // still gets corrected, in about a second) while letting the rate
        // sensor do the fast part, which is the whole point of having both.
        val err = Geo.signedDelta(c, h)
        heading = Geo.normalizeDeg(h + COMPASS_TRIM_WITH_RATE * err)
    }

    /** When the compass last produced a usable reading. */
    @Volatile
    private var lastCompassMs = 0L

    /** When the *board's* magnetometer last reported. 0 if it never has. */
    @Volatile
    private var lastHudCompassMs = 0L

    /**
     * True while the board's own magnetometer is reporting.
     *
     * Separate from [source] on purpose. `source` is about who supplies the
     * turn *rate*; this is about who supplies the absolute *direction*. They
     * are different questions and conflating them is what caused the bug this
     * exists to fix.
     */
    val hudCompassLive: Boolean
        get() = lastHudCompassMs != 0L && nowMs - lastHudCompassMs < HUD_COMPASS_STALE_MS

    /** The smoothed compass reading, whether or not it is in charge. */
    @Volatile
    var lastCompass: Double? = null
        private set

    @Synchronized
    fun reset() {
        heading = null
        stationary = true
        moving = false
        lastSpeedMps = 0.0
        compassDrove = true
        gpsFixedHeading = false
        lastR = null
        lastFrameId = Int.MIN_VALUE
        lastHudCompassMs = 0L
        lastGpsBearing = null
        // The bias is a property of the hardware, and hudCorrection of the
        // board and where the car is standing: both survive a route change.
    }

    /** One line for the diagnostics row. */
    @Synchronized
    fun describe(): String = when {
        // The source line comes first now. It used to sit below the speed
        // branch, so anywhere above the resume point the diagnostics row said
        // "heading: GPS" while `source` was HUD_IMU and the board's gyro was
        // the only thing rotating the estimate -- which is exactly the sort of
        // thing that makes you chase a bug that is not there.
        source == Source.HUD_IMU -> "heading: HUD gyro"
        compassDistrusted -> "heading: GPS (compass disagrees)"
        usingCompass -> "heading: compass"
        // No km/h in the string any more. It named a speed band, and the band
        // moved from "motorway" to "faster than a walk" -- a row that says
        // "over 60 km/h" while the car does 4 reads as a broken gauge.
        lastSpeedMps >= COMPASS_RESUME_MPS -> "heading: GPS (moving)"
        else -> describeSource()
    }

    private fun describeSource(): String = when (source) {
        Source.HUD_IMU -> "heading: HUD gyro"
        Source.PHONE_ROTATION -> "heading: phone orientation"
        Source.PHONE_GYRO -> "heading: phone gyro"
        Source.GPS_ONLY -> "heading: GPS only"
    }
}
