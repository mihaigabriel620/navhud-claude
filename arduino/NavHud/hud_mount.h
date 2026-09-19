// ---------------------------------------------------------------------------
//  hud_mount.h -- which way the sensor is bolted in.
//
//  The board is glued to the back of a display that is wedged onto a dashboard.
//  Nobody measured the angle, nobody is going to, and the compass cannot work
//  without it: a heading is an absolute direction, so the firmware has to know
//  where the car's nose is relative to the chip before it can say which way the
//  car points.
//
//  So it is learned, once, from two measurements the car makes for free:
//
//    UP      the accelerometer while parked. Gravity is the one direction that
//            is always available and never ambiguous.
//    FORWARD the accelerometer while accelerating or braking in a straight
//            line, with the sign settled by whether the CAN speed is rising or
//            falling. This is the trick that makes it work without GPS, and it
//            is Ford's, from US20130081442A1: "the speed of the vehicle...
//            should be used to differentiate between accelerating and
//            decelerating. If the heading is estimated during a decelerating
//            period the heading angle should be increased by 180 degrees."
//
//  Then TRIAD turns the two into a rotation. Gravity goes in as the PRIMARY
//  vector, and that ordering is not arbitrary -- Shuster proves the algorithm
//  "treats the two measurements unsymmetrically", satisfying the first exactly
//  and only minimising the error in the second. Gravity averaged over a minute
//  of standing still is worth a fraction of a degree; the forward estimate
//  carries several. Put them in the wrong order and you throw away the good
//  measurement to protect the bad one.
//
//  WHY IT IS STORED AS TWO VECTORS AND NOT AS THREE ANGLES
//
//  Roll, pitch and yaw would be 6 bytes and one line of code, and they would
//  fail exactly here. u-blox document the failure for their own ADR receivers:
//  at "+/- 90 degrees around the IMU-mount pitch axis... IMU-mount roll and
//  IMU-mount yaw cannot be distinguished from each other" and "these angles
//  start to heavily fluctuate". A sensor glued flat to the BACK of a dash panel
//  is plausibly at exactly that angle. Two unit vectors have no singularity,
//  and the matrix is rebuilt from them at boot.
//
//  WHY IT IS CHECKED AGAIN ON EVERY BOOT
//
//  u-blox deliberately refuse to persist their estimate at all, and instead run
//  a continuous plausibility check that raises an error flag "either due to a
//  wrong initialization or a change in the physical mounting of the device".
//  Saving it, as asked, is the right call for something bolted down -- but a
//  saved calibration with no staleness check is a calibration that goes quietly
//  wrong the first time somebody knocks the display. So the stored UP is
//  compared against measured gravity whenever the car is standing still, and
//  the whole thing is disowned if they part company.
//
//  HOW WRONG "a bit off" IS
//
//  Not a bit. Magnetic dip in central Europe is about 65 degrees, and NXP
//  AN4249 gives the amplification as tan(dip) -- 2.14. Every degree of error in
//  the up vector becomes 2.1 degrees of heading error. VectorNav measure the
//  same thing independently: "A pitch or roll error of 0.5 deg equates to about
//  1 deg error in the magnetic heading".
// ---------------------------------------------------------------------------
#ifndef HUD_MOUNT_H
#define HUD_MOUNT_H

#include <math.h>
#include <stdint.h>

/** Bytes in EEPROM: magic, version, 6 x int16, checksum. */
#define HUD_MOUNT_BYTES 16

/** Unit-vector components are stored as int16 at this scale. */
#define HUD_MOUNT_SCALE 20000.0f

/** Gravity has to be this steady before a stationary sample is believed. */
#ifndef MOUNT_STILL_TOL
  #define MOUNT_STILL_TOL   0.35f
#endif     // m/s^2 of wobble across the window
/** ...for this long. */
#ifndef MOUNT_STILL_MS
  #define MOUNT_STILL_MS    3000UL
#endif

/**
 * A straight-line acceleration event has to reach this to be worth having.
 *
 * Ford's own threshold is 1 m/s^2 ("m_thr = 1"). Below that the horizontal
 * accelerometer is mostly road camber and suspension.
 */
#ifndef MOUNT_ACCEL_MIN
  #define MOUNT_ACCEL_MIN   0.8f
#endif

/** ...and the car must be turning less than this, or it is not a straight line. */
#ifndef MOUNT_YAW_MAX_DPS
  #define MOUNT_YAW_MAX_DPS 3.0f
#endif

/** Samples of straight-line acceleration before the forward axis is offered. */
#ifndef MOUNT_FWD_SAMPLES
  #define MOUNT_FWD_SAMPLES 150
#endif

/**
 * Agreement between the projected longitudinal acceleration and the speed
 * derivative, below which the answer is rejected.
 *
 * Ford's acceptance test, and their number: "If this correlation is more than a
 * threshold, say corr_threshold, then the estimated angle is accepted"...
 * "corr_thr = 0.6".
 */
#ifndef MOUNT_CORR_MIN
  #define MOUNT_CORR_MIN    0.6f
#endif

/**
 * How far measured gravity may drift from the stored up vector before the
 * calibration is disowned, in degrees.
 *
 * Generous, because the thing it must not do is cry wolf: a car parks on
 * cambers and slopes, and Swedish road-standard figures put ordinary country
 * grades at 6 % (3.4 deg) and up to 8 % (4.6 deg). Twelve degrees is well clear
 * of any driveway and nowhere near "somebody re-stuck the display".
 */
#ifndef MOUNT_STALE_DEG
  #define MOUNT_STALE_DEG   12.0f
#endif

static inline void mountCross(const float* a, const float* b, float* o) {
  o[0] = a[1] * b[2] - a[2] * b[1];
  o[1] = a[2] * b[0] - a[0] * b[2];
  o[2] = a[0] * b[1] - a[1] * b[0];
}
static inline float mountDot(const float* a, const float* b) {
  return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}
static inline float mountNorm(const float* a) { return sqrtf(mountDot(a, a)); }
static inline bool mountUnit(float* a) {
  const float n = mountNorm(a);
  if (n < 1e-6f) return false;
  a[0] /= n; a[1] /= n; a[2] /= n;
  return true;
}

class HudMount {
 public:
  /** Rows of the sensor->vehicle rotation: forward, left, up. */
  float fwd[3] = { 1, 0, 0 };
  float left[3] = { 0, 1, 0 };
  float up[3]  = { 0, 0, 1 };

  /** False until something better than "the chip is already square" is known. */
  bool valid = false;

  /**
   * Set when gravity stopped agreeing with the saved mounting.
   *
   * It is not enough to record it. u-blox raise an alignment error for the
   * same condition and stop trusting the fusion; here `usable()` goes false,
   * the `$MAG` sentence carries the flag so the phone can stop steering the
   * arrow with it, and `status` says SUSPECT. Leaving it as a note that only
   * a typed command reveals means the driver gets a confidently wrong compass
   * for the rest of the installation's life.
   */
  bool suspect = false;

  /** Calibrated, and not since contradicted by gravity. */
  bool usable() const { return valid && !suspect; }

  // ---- using it ----------------------------------------------------------

  /** Rotate a sensor-frame vector into vehicle coordinates. */
  void toVehicle(const float* v, float* out) const {
    out[0] = mountDot(fwd, v);
    out[1] = mountDot(left, v);
    out[2] = mountDot(up, v);
  }

  /**
   * Magnetic heading of the car's nose, degrees clockwise from magnetic north.
   *
   * The vector form, not NXP AN4248's Euler form, for two reasons. It has no
   * singularity at any mounting angle, and it cannot inherit AN4248's frame:
   * that document is written for X-forward, Y-RIGHT, Z-DOWN with the
   * accelerometer negated, and its heading is atan2(-Bfy, Bfx). Carry that
   * minus sign into a Y-left, Z-up frame -- which is what an MPU-9250 reports
   * and what ISO 8855 specifies for a vehicle -- and the compass is right at
   * north and south and mirrored east for west. Honeywell AN-203, whose own
   * figure is labelled "X (forward)" and "Y (left)", has no minus sign.
   *
   * @param m calibrated field in SENSOR axes, microtesla
   * @param a accelerometer in SENSOR axes; only its direction is used
   */
  float headingDeg(const float* m, const float* a) const {
    // Gravity now beats the stored up vector, when there is gravity to be had:
    // the stored one is where the chip was bolted, this one is where the car is
    // standing, and a driveway camber belongs in the answer.
    float u[3] = { a[0], a[1], a[2] };
    if (!mountUnit(u)) { u[0] = up[0]; u[1] = up[1]; u[2] = up[2]; }

    // The horizontal part of the field, with the vertical component removed.
    const float d = mountDot(m, u);
    const float h[3] = { m[0] - d * u[0], m[1] - d * u[1], m[2] - d * u[2] };

    // The car's nose and its left, both levelled against the same gravity, so
    // that a slope tilts the frame and the field together and cancels out.
    float l[3];  mountCross(u, fwd, l);
    if (!mountUnit(l)) return NAN;
    float f[3];  mountCross(l, u, f);

    const float deg = atan2f(mountDot(h, l), mountDot(h, f)) * 57.29577951f;
    return deg < 0 ? deg + 360.0f : deg;
  }

  /** Yaw rate about the true vertical, degrees per second, clockwise positive. */
  float yawDps(const float* g, const float* a) const {
    float u[3] = { a[0], a[1], a[2] };
    if (!mountUnit(u)) { u[0] = up[0]; u[1] = up[1]; u[2] = up[2]; }
    // Negated: a positive rate about UP is anticlockwise seen from above, i.e.
    // turning left, i.e. the heading decreasing.
    return -mountDot(g, u) * 57.29577951f;
  }

  // ---- building it -------------------------------------------------------

  /**
   * TRIAD. Gravity is the primary vector and is reproduced exactly; the
   * forward estimate only contributes its component perpendicular to gravity.
   *
   * This is the same construction Android's getRotationMatrix() uses for
   * gravity and the magnetic field, for the same reason: the accurate vector
   * goes first and the noisy one is never allowed to perturb the vertical.
   */
  bool build(const float* upIn, const float* fwdIn) {
    float u[3] = { upIn[0], upIn[1], upIn[2] };
    if (!mountUnit(u)) return false;
    float l[3];
    mountCross(u, fwdIn, l);
    // A forward estimate parallel to gravity is not a shallow angle, it is a
    // physical impossibility -- the car does not drive straight up -- so this
    // means the estimate is garbage rather than merely poor. Android guards the
    // same degeneracy with the same test.
    if (mountNorm(l) < 0.15f * mountNorm(fwdIn)) return false;
    mountUnit(l);
    float f[3];
    mountCross(l, u, f);
    for (uint8_t i = 0; i < 3; i++) { up[i] = u[i]; left[i] = l[i]; fwd[i] = f[i]; }
    valid = true;
    suspect = false;
    return true;
  }

  /** Straight out of the box: the chip's own axes are the car's. */
  void identity() {
    fwd[0] = 1; fwd[1] = 0; fwd[2] = 0;
    left[0] = 0; left[1] = 1; left[2] = 0;
    up[0] = 0; up[1] = 0; up[2] = 1;
    valid = false;
    suspect = false;
  }

  // ---- learning ----------------------------------------------------------

  void learnStart() {
    gN_ = 0; for (uint8_t i = 0; i < 3; i++) gSum_[i] = 0;
    stillSince_ = 0; haveUp_ = false;
    fN_ = 0; for (uint8_t i = 0; i < 3; i++) fSum_[i] = 0;
    sA_ = sB_ = sAA_ = sBB_ = sAB_ = 0; cN_ = 0;
    learning_ = true;
  }
  void learnStop() { learning_ = false; }
  bool learning() const { return learning_; }
  bool haveUp() const { return haveUp_; }
  uint16_t fwdSamples() const { return fN_; }

  /**
   * One accelerometer sample while the car is standing still.
   *
   * Requires the reading to have been steady for MOUNT_STILL_MS first, so that
   * a door slam or somebody getting in does not end up in the average. The
   * literature prefers the median for exactly that reason (US10876859B2 uses
   * "the median of the raw accelerometer data collected during a mini-trip"),
   * but a median wants the samples kept and this has 80 KB of RAM and a display
   * to drive; a settle gate plus a mean is the affordable version of the same
   * idea.
   */
  void feedStill(const float* a, uint32_t nowMs) {
    if (!learning_) return;
    const float n = mountNorm(a);
    if (n < 5.0f || n > 15.0f) { stillSince_ = 0; return; }   // not gravity alone
    if (lastN_ > 0 && fabsf(n - lastN_) > MOUNT_STILL_TOL) { stillSince_ = 0; }
    lastN_ = n;
    if (stillSince_ == 0) { stillSince_ = nowMs; return; }
    if (nowMs - stillSince_ < MOUNT_STILL_MS) return;
    for (uint8_t i = 0; i < 3; i++) gSum_[i] += a[i];
    gN_++;
    haveUp_ = gN_ >= 20;
  }

  /**
   * One accelerometer sample while the car is accelerating or braking in a
   * straight line.
   *
   * @param a       accelerometer, sensor axes, m/s^2
   * @param yawDps_ current yaw rate; a turn rotates the scatter and ruins it
   * @param dvdt    d(speed)/dt from the CAN bus, m/s^2
   */
  void feedMoving(const float* a, float yawDps_, float dvdt) {
    if (!learning_ || !haveUp_) return;
    if (fabsf(yawDps_) > MOUNT_YAW_MAX_DPS) return;
    if (fabsf(dvdt) < MOUNT_ACCEL_MIN) return;

    float u[3] = { gSum_[0], gSum_[1], gSum_[2] };
    if (!mountUnit(u)) return;

    // Level it: remove the gravity component, leaving what the car did.
    const float d = mountDot(a, u);
    float h[3] = { a[0] - d * u[0], a[1] - d * u[1], a[2] - d * u[2] };
    const float hn = mountNorm(h);
    if (hn < MOUNT_ACCEL_MIN) return;

    // The sign of the speed derivative is what makes this work with no GPS and
    // no PCA: braking pushes the horizontal acceleration BACKWARDS, so without
    // it the estimate is only an axis and the nose could be at either end of
    // it. With it, every sample is already pointing forwards and the mean of
    // them is the answer.
    const float s = dvdt > 0 ? 1.0f : -1.0f;

    // The acceptance test, and it has to be computed BEFORE fSum_ takes this
    // sample or it is grading its own homework.
    //
    // What is correlated matters more than it looks. The obvious pairing is
    // (s * |h|) against dvdt -- and it is worthless, because `s` is taken FROM
    // dvdt, so the two share a sign on every sample by construction. That
    // built-in agreement scores about 0.8 on data with no direction
    // information in it whatsoever: an accelerometer pointing in a random
    // direction every sample, a loose bracket rattling, a sensor that has come
    // unstuck. Measured at 0.90 and 0.95 on exactly those, against a gate of
    // 0.6, and then written to flash as a confident calibration with a nose
    // vector pulled out of the noise.
    //
    // Projecting onto the nose estimate so far removes the tautology: an
    // accelerometer that genuinely tracks the car projects strongly and
    // consistently onto it, and one that does not projects at random. Same two
    // rattling datasets score -0.03 and -0.01; an honest drive still scores
    // 1.00.
    float fhat[3] = { fSum_[0], fSum_[1], fSum_[2] };
    const float projected = mountUnit(fhat) ? mountDot(h, fhat) : s * hn;
    const float truth = dvdt;

    for (uint8_t i = 0; i < 3; i++) fSum_[i] += s * h[i];
    fN_++;
    sA_  += projected; sB_ += truth;
    sAA_ += projected * projected; sBB_ += truth * truth;
    sAB_ += projected * truth;
    cN_++;
  }

  /** Pearson r between the levelled longitudinal accel and the CAN speed
      derivative. Ford's acceptance test; below 0.6 the answer is not used. */
  float correlation() const {
    if (cN_ < 10) return 0.0f;
    const float n = (float)cN_;
    const float num = sAB_ - sA_ * sB_ / n;
    const float da = sAA_ - sA_ * sA_ / n;
    const float db = sBB_ - sB_ * sB_ / n;
    if (da <= 0 || db <= 0) return 0.0f;
    return num / sqrtf(da * db);
  }

  /**
   * Adopt the result, if there is enough of it and it agrees with the bus.
   *
   * The gates come FIRST and the session is only closed once one of them has
   * been passed. Clearing the flag up here instead -- which is the obvious
   * place for it -- means that typing `mount save` a minute too early ends the
   * calibration silently while printing "keep driving and try again", and the
   * sample counter is then frozen for the rest of the drive no matter how far
   * you go. Refusing has to leave the session running, or the advice is a lie.
   */
  bool learnFinish() {
    if (!haveUp_ || fN_ < MOUNT_FWD_SAMPLES) return false;
    if (correlation() < MOUNT_CORR_MIN) return false;
    learning_ = false;
    return build(gSum_, fSum_);
  }

  // ---- staying honest ----------------------------------------------------

  /**
   * Does measured gravity still agree with the stored up vector?
   *
   * Only meaningful while the car is standing still. Answers the question
   * u-blox raise their alignment error flag for: has the thing been moved.
   */
  bool matchesGravity(const float* a) const {
    if (!valid) return true;
    float u[3] = { a[0], a[1], a[2] };
    if (!mountUnit(u)) return true;
    const float c = mountDot(u, up);
    return c > cosf(MOUNT_STALE_DEG * 0.01745329f);
  }

  /** Degrees between the stored up vector and measured gravity. */
  float gravityErrorDeg(const float* a) const {
    float u[3] = { a[0], a[1], a[2] };
    if (!mountUnit(u)) return NAN;
    float c = mountDot(u, up);
    if (c > 1.0f) c = 1.0f;
    if (c < -1.0f) c = -1.0f;
    return acosf(c) * 57.29577951f;
  }

  // ---- flash -------------------------------------------------------------

  /**
   * Sixteen bytes: a magic, a version, six int16, a checksum.
   *
   * Two unit VECTORS, not three Euler angles -- see the note at the top of the
   * file about gimbal lock at a 90-degree mount pitch, which is a realistic
   * angle for a sensor glued to the back of a dash panel. `left` is not stored
   * because it is a cross product of the other two, and storing a value that
   * can be derived is one more thing that can disagree with itself.
   *
   * int16 at 1/20000 covers -1.6..1.6 with a resolution of 0.00005, which for a
   * unit vector is about 0.003 degrees -- three orders of magnitude below what
   * the calibration itself is worth.
   */
  void pack(uint8_t* b) const {
    b[0] = 0xA9;
    b[1] = 1;                                    // format version
    const float* v[2] = { up, fwd };
    for (uint8_t k = 0; k < 2; k++)
      for (uint8_t i = 0; i < 3; i++) {
        int32_t q = (int32_t)lroundf(v[k][i] * HUD_MOUNT_SCALE);
        if (q >  32767) q =  32767;
        if (q < -32768) q = -32768;
        const uint8_t o = 2 + (k * 3 + i) * 2;
        b[o]     = (uint8_t)((uint16_t)q >> 8);
        b[o + 1] = (uint8_t)((uint16_t)q & 0xFF);
      }
    uint8_t sum = 0;
    for (uint8_t i = 0; i < HUD_MOUNT_BYTES - 2; i++) sum ^= b[i];
    b[HUD_MOUNT_BYTES - 2] = sum;
    b[HUD_MOUNT_BYTES - 1] = 0;
  }

  /** Returns false and changes nothing when the block is blank or damaged. */
  bool unpack(const uint8_t* b) {
    if (b[0] != 0xA9 || b[1] != 1) return false;
    uint8_t sum = 0;
    for (uint8_t i = 0; i < HUD_MOUNT_BYTES - 2; i++) sum ^= b[i];
    if (sum != b[HUD_MOUNT_BYTES - 2]) return false;
    float u[3], f[3];
    for (uint8_t k = 0; k < 2; k++)
      for (uint8_t i = 0; i < 3; i++) {
        const uint8_t o = 2 + (k * 3 + i) * 2;
        const int16_t q = (int16_t)(((uint16_t)b[o] << 8) | b[o + 1]);
        (k == 0 ? u : f)[i] = q / HUD_MOUNT_SCALE;
      }
    // Rebuilt rather than trusted: quantisation has just moved both vectors by
    // a few parts in a hundred thousand, and build() re-orthonormalises them.
    // Storing the third row instead and reading all three back would let a
    // damaged byte produce a matrix that is not a rotation at all.
    return build(u, f);
  }

 private:
  bool     learning_ = false;
  float    gSum_[3] = { 0, 0, 0 };
  uint16_t gN_ = 0;
  bool     haveUp_ = false;
  uint32_t stillSince_ = 0;
  float    lastN_ = 0;

  float    fSum_[3] = { 0, 0, 0 };
  uint16_t fN_ = 0;

  float    sA_ = 0, sB_ = 0, sAA_ = 0, sBB_ = 0, sAB_ = 0;
  uint16_t cN_ = 0;
};

// The buffer size is declared twice -- here, so this file compiles alone for
// the host tests, and in hud_store.h, which owns the EEPROM map. They describe
// the same array and pack() writes through one while saveMount() sizes through
// the other, so a disagreement is a stack smash rather than a mismatch. Make
// it refuse to build instead.
#ifdef HUD_MOUNT_STORE_BYTES
static_assert(HUD_MOUNT_BYTES == HUD_MOUNT_STORE_BYTES,
              "hud_mount.h and hud_store.h disagree about the mount blob size");
#endif

#endif  // HUD_MOUNT_H
