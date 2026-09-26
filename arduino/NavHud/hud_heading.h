// ---------------------------------------------------------------------------
//  hud_heading.h -- which way the car points, and how fast it is turning.
//
//  Pure maths: vectors in, numbers out. No Arduino, no I2C, so the host tests
//  can drive it with any orientation they like. The chips are hud_compass.h
//  and hud_motion.h; hud_sensors.h feeds their samples in here.
//
//  THE FRAME is the box's own: X forward, Y left, Z up, which is what both
//  chips report after their MAG_AXIS_* / MPU_AXIS_* remap. Gravity is kept as
//  the unit vector `up`, in that frame.
//
//  THE HEADING is the box's forward axis, projected onto the true horizontal:
//
//      E = m x up        east, whatever way the box is tilted
//      N = up x E        north
//      heading = atan2(E.x, N.x)
//
//  With the box level (up = Z) that is atan2(my, mx), the flat compass the
//  firmware had before. Tilted, it is still right, because the Earth's field
//  dips about 65 degrees here: without gravity every degree of tilt was about
//  two degrees of heading error, which is why tilting the screen swung the
//  arrow.
//
//  GRAVITY comes from the MPU-6050. Its accelerometer alone cannot be trusted
//  while driving -- braking and cornering push it about as much as gravity
//  does -- so `up` is carried by the gyro and only pulled back towards the
//  accelerometer when the car is not accelerating:
//
//    parked      a whole second of stillness sets `up` to the average reading
//                and learns the gyro's bias from it
//    driving     a slow pull (5 s), and only when the reading has the size of
//                gravity, points within 3 degrees of `up`, and the car is not
//                turning; the same pull slowly learns the tilt axes' bias.
//                Ten seconds of disagreeing at a steady speed means `up` is
//                what is wrong, and then it is pulled back regardless.
//
//  THE TURN RATE for $IMU is the gyro about `up`, not about the chip's Z, so a
//  box mounted at an angle still reports how fast the CAR turns.
//
//  No MPU: `up` stays Z and the heading is the flat one, as before.
// ---------------------------------------------------------------------------
#ifndef HUD_HEADING_H
#define HUD_HEADING_H

#include <math.h>
#include <stdint.h>
#include <string.h>

/** Samples that must be collected before a `spin` calibration can be kept. */
#ifndef COMPASS_CAL_MIN_SAMPLES
  #define COMPASS_CAL_MIN_SAMPLES 120
#endif
/** How far each horizontal direction must sweep, microtesla. */
#ifndef COMPASS_CAL_MIN_SPAN_UT
  #define COMPASS_CAL_MIN_SPAN_UT 25.0f
#endif
/**
 * A flat circle stays within this along the vertical it started from,
 * microtesla. A sloping car park moves it by a few, a hand turning the box on
 * the desk with 8 degrees of wobble by about 8.
 */
#ifndef COMPASS_CAL_FLAT_UT
  #define COMPASS_CAL_FLAT_UT 12.0f
#endif
/**
 * Turned every way: how thick the cloud of samples must be in its thinnest
 * direction, as a fraction of the field. A flat circle is 0.02; tipping the box
 * 25 degrees each way while turning it, 0.11; 60 degrees, 0.25.
 */
#ifndef COMPASS_CAL_COVER
  #define COMPASS_CAL_COVER 0.1f
#endif
/** Turned every way, the samples must lie this close to a sphere, as a fraction of it. */
#define COMPASS_CAL_FIT 0.1f
/** Earth's field is 25-65 uT; outside this something magnetic is close. */
#define COMPASS_MIN_FIELD_UT  15.0f
#define COMPASS_MAX_FIELD_UT  120.0f

class HudHeading {
 public:
  // ---- results -------------------------------------------------------------
  /** Degrees clockwise from magnetic north, plus the north offset. NAN until
   *  the first compass sample. */
  float deg = NAN;
  /** Magnitude of the field after the hard-iron offset, microtesla. */
  float fieldUt = 0;
  /** The last compass sample as it came in, box frame, microtesla. */
  float field[3] = { 0, 0, 0 };
  /** Gravity: unit vector pointing up, box frame. Z until the MPU says. */
  float up[3] = { 0, 0, 1 };
  bool  haveUp = false;
  /** The gyro's zero-rate reading, deg/s, learned while parked. */
  float bias[3] = { 0, 0, 0 };
  bool  biasKnown = false;

  // ---- stored --------------------------------------------------------------
  /** Hard-iron offset, microtesla, box frame. */
  float offset[3] = { 0, 0, 0 };
  /** Added to the heading: the box's yaw on the dash, set by `north`. */
  float northOffsetDeg = 0;

  bool calibrating() const  { return calOn_; }
  bool calibrated() const   { return haveCal_; }
  uint16_t calCount() const { return calN_; }

  /** Is the field a plausible one to navigate by? */
  bool healthy() const {
    return fieldUt >= COMPASS_MIN_FIELD_UT && fieldUt <= COMPASS_MAX_FIELD_UT;
  }

  /** Nose up is positive. */
  float pitchDeg() const { return atan2f(up[0], sqrtf(up[1] * up[1] + up[2] * up[2])) * kDeg; }
  /** Right side down is positive. */
  float rollDeg() const  { return atan2f(up[1], up[2]) * kDeg; }

  /**
   * One MPU sample: acceleration in g, rotation in deg/s, both box frame, and
   * the time since the last sample. `speedMps` is the car's speed from the
   * bus, or negative when there is no bus to ask.
   */
  void motion(const float acc[3], const float gyr[3], float dtS, float speedMps) {
    if (!(dtS > 0.0f)) return;
    const float an = norm_(acc);
    const bool plausible = an > 0.5f && an < 1.5f;

    if (!haveUp) {                       // the first reading is the best there is
      if (plausible) { for (uint8_t i = 0; i < 3; i++) up[i] = acc[i] / an; haveUp = true; }
      return;
    }

    // A stall -- a full repaint is ~0.2 s, `wipe` three -- leaves a gap no single
    // sample can speak for. Carry on from the next one; the block is spoilt.
    if (dtS > kMaxDtS) { blockReset_(); return; }
    blockAdd_(acc, gyr, dtS, speedMps);

    if (!biasKnown) return;              // a gyro with an unknown bias is no use yet

    float w[3];                          // bias-corrected, rad/s
    for (uint8_t i = 0; i < 3; i++) w[i] = (gyr[i] - bias[i]) * kRad;
    const float wUp = dot_(w, up);       // about the vertical, counter-clockwise +

    rateSum_ += -wUp * kDeg * dtS;       // clockwise positive, like a bearing
    rateT_   += dtS;

    // The slow pull, while driving and while parked alike (parked, the block
    // average below does the real work). Gated, because the accelerometer is
    // only gravity when nothing else is pushing the car.
    if (plausible && fabsf(an - gRef_) < kAccGateG && norm_(w) < kTurnGateRad &&
        (speedMps < 0.0f || speedMps * fabsf(wUp) < kLatGateMps2)) {
      float a[3], e[3];
      for (uint8_t i = 0; i < 3; i++) a[i] = acc[i] / an;
      cross_(a, up, e);                  // |e| = sin(angle between them)
      const bool agrees = norm_(e) < kDirGate;
      if (agrees || resync_) {
        // Mahony's filter, gravity only: the proportional term turns `up`
        // towards the reading, the integral term learns the bias about the two
        // tilt axes. The bias about the vertical cannot be seen from gravity;
        // that one is learned while parked.
        for (uint8_t i = 0; i < 3; i++) {
          w[i]    += kKp * e[i];
          bias[i] -= kKi * e[i] * dtS * kDeg;
        }
        disagreeT_ = 0;
        if (agrees) resync_ = false;
      } else {
        // More than the gate out: normally the car speeding up or slowing
        // down, and ignored. But a gyro bias that jumps can carry `up` past the
        // gate, and then every reading disagrees for ever. A car cannot keep
        // accelerating for kResyncS without its speed showing it, so a long
        // disagreement at a steady speed means `up` is what is wrong.
        if (disagreeT_ == 0.0f) disagreeV_ = speedMps;
        disagreeT_ += dtS;
        if (speedMps >= 0.0f && fabsf(speedMps - disagreeV_) > kResyncDvMps) disagreeT_ = 0;
        if (disagreeT_ > kResyncS) resync_ = true;
      }
    }

    // Carry `up` through the car's own rotation: a vector fixed in the world,
    // seen from a frame turning at w, turns at -w.
    float d[3];
    cross_(up, w, d);
    for (uint8_t i = 0; i < 3; i++) up[i] += d[i] * dtS;
    normalize_(up);
  }

  /**
   * One compass sample, microtesla, box frame. Works out the heading.
   * False when the field is too weak to say anything.
   */
  bool mag(const float m[3]) {
    if (calOn_) calFeed_(m);
    magAdd_(m);
    memcpy(field, m, sizeof field);

    float c[3];
    for (uint8_t i = 0; i < 3; i++) c[i] = m[i] - offset[i];
    fieldUt = norm_(c);

    float e[3], n[3];
    cross_(c, up, e);                    // east
    if (norm_(e) < 1.0f) return false;   // no horizontal field: pointing at the pole
    cross_(up, e, n);                    // north
    deg = wrap360_(atan2f(e[0], n[0]) * kDeg + northOffsetDeg);
    return true;
  }

  /**
   * The average turn rate since the last call, deg/s, clockwise positive --
   * the heading change over that time, which is what the app integrates.
   * False until the gyro's bias is known: a rate with a bias in it turns the
   * app's arrow for ever.
   */
  bool takeRate(float& dps) {
    if (!biasKnown || !(rateT_ > 0.0f)) return false;
    dps = rateSum_ / rateT_;
    rateSum_ = 0; rateT_ = 0;
    return true;
  }

  // ---- calibration -----------------------------------------------------------

  /** What the last calFinish() found, for `spin stop` to report. */
  bool  calEveryWay = false;             // the one accepted measured all three axes
  float calCover = 0;                    // see COMPASS_CAL_COVER
  float calRadiusUt = 0, calFitUt = 0;   // the sphere, and how far the samples were off it
  /** How far the samples swept along the two horizontal directions (0, 1) and the vertical (2). */
  float calSpan(uint8_t i) const { return hi_[i] > lo_[i] ? hi_[i] - lo_[i] : 0.0f; }

  /**
   * Start a hard-iron calibration. Two ways, told apart at the end:
   *
   * FLAT: drive a slow full circle, or turn the box round on the dash's plane.
   * The circle the field traces is flat in the TRUE horizontal, which is only
   * the box's XY plane when the box is level. So the extremes are taken along
   * two horizontal directions worked out from `up` now, and the offset kept is
   * the circle's centre in that plane. Taking the centre along the box's own
   * axes -- as the flat compass did -- puts part of the Earth's vertical field
   * into the offset as soon as the box is tilted, and the tilt compensation
   * then works from a field that is not the Earth's.
   *
   * EVERY WAY: on the desk, tip the box forward, back and onto both sides while
   * turning it, like a phone's figure 8. The field then traces a sphere round
   * the offset, and its centre is all three axes of it -- including the
   * vertical part a flat circle cannot see, which is what tilting the screen
   * brings into the heading: 15 uT of it swung the heading 32 degrees at 45
   * degrees of tilt.
   */
  void calStart() {
    calOn_ = true; calN_ = 0;
    const float x[3] = { 1, 0, 0 }, y[3] = { 0, 1, 0 };
    // Forward, flattened onto the horizontal; left from that. A box standing
    // on its nose has no horizontal forward, so it uses its left instead.
    float h[3];
    for (uint8_t i = 0; i < 3; i++) h[i] = x[i] - dot_(x, up) * up[i];
    if (norm_(h) < 0.3f) for (uint8_t i = 0; i < 3; i++) h[i] = y[i] - dot_(y, up) * up[i];
    normalize_(h);
    memcpy(h1_, h, sizeof h1_);
    cross_(up, h1_, h2_);
    memcpy(h3_, up, sizeof h3_);
    for (uint8_t i = 0; i < 3; i++) { lo_[i] = 1e9f; hi_[i] = -1e9f; }
    sN_ = sQ_ = sQQ_ = 0;
    for (uint8_t i = 0; i < 3; i++) { sU_[i] = 0; sQU_[i] = 0; }
    for (uint8_t i = 0; i < 6; i++) sUU_[i] = 0;
  }

  /**
   * Finish. Returns false -- and keeps the session running -- when what was
   * collected is not good enough. Gates BEFORE the flag is cleared: the other
   * way round, an early `spin stop` silently ends the session while printing
   * "keep going".
   */
  bool calFinish() {
    if (calN_ < COMPASS_CAL_MIN_SAMPLES) return false;
    float o[3];
    const bool everyWay = sphere_(o);
    // Flat is looked at first: its centre is the midpoint of extremes, which
    // stays exact for a field the metal round the chip has made egg-shaped,
    // where a sphere fitted to part of the egg is pulled off centre. Both
    // horizontal directions must have swept a real arc -- one alone means the
    // box was rocked, not turned -- and the box must have stayed flat, or the
    // extremes are not the circle's.
    if (calSpan(0) >= COMPASS_CAL_MIN_SPAN_UT && calSpan(1) >= COMPASS_CAL_MIN_SPAN_UT &&
        calSpan(2) <= COMPASS_CAL_FLAT_UT) {
      // The centre, in the horizontal plane only. Along `up` the car's own
      // field and the Earth's vertical one look the same and cannot be told
      // apart, so that part stays what it was: measured by an every-way
      // calibration, or nothing.
      const float c1 = (hi_[0] + lo_[0]) * 0.5f, c2 = (hi_[1] + lo_[1]) * 0.5f;
      const float keep = dot_(offset, h3_);
      for (uint8_t i = 0; i < 3; i++) offset[i] = c1 * h1_[i] + c2 * h2_[i] + keep * h3_[i];
      calEveryWay = false;
    } else if (everyWay) {
      memcpy(offset, o, sizeof offset);
      calEveryWay = true;
    } else {
      return false;
    }
    calOn_ = false;
    haveCal_ = true;
    return true;
  }

  void calAbort() { calOn_ = false; }

  void forget() {
    for (uint8_t i = 0; i < 3; i++) offset[i] = 0;
    northOffsetDeg = 0;
    haveCal_ = false;
    calOn_ = false;
  }

  /**
   * "The car is pointing this way." Stores the difference as the offset: the
   * box's yaw on the dash, the one mounting angle gravity cannot reveal.
   */
  bool setNorth(float trueHeadingDeg) {
    if (isnan(deg)) return false;
    northOffsetDeg = wrap360_(trueHeadingDeg - (deg - northOffsetDeg));
    deg = wrap360_(trueHeadingDeg);
    return true;
  }

  // ---- flash -----------------------------------------------------------------
  // 19 bytes: magic, a flag, four floats, checksum. A new magic: the flat
  // compass's 31-byte block had per-axis gains and an offset that held part of
  // the vertical field, and neither means anything to this one.
  static const uint8_t STORE_BYTES = 19;
  static const uint8_t STORE_MAGIC = 0xC4;

  void pack(uint8_t* b) const {
    b[0] = STORE_MAGIC;
    b[1] = haveCal_ ? 1 : 0;
    memcpy(b + 2,  offset, 12);
    memcpy(b + 14, &northOffsetDeg, 4);
    b[STORE_BYTES - 1] = sum_(b);
  }

  bool unpack(const uint8_t* b) {
    if (b[0] != STORE_MAGIC || b[STORE_BYTES - 1] != sum_(b)) return false;
    float o[3], n;
    memcpy(o, b + 2, 12);
    memcpy(&n, b + 14, 4);
    // Believe it only if it is believable. Erased flash reads 0xFF, which
    // decodes to NaN, and NaN propagates silently all the way to an arrow that
    // points nowhere and never recovers.
    for (uint8_t i = 0; i < 3; i++) if (!(fabsf(o[i]) <= 2000.0f)) return false;
    if (!(n >= -360.0f && n <= 360.0f)) return false;
    memcpy(offset, o, sizeof offset);
    northOffsetDeg = n;
    haveCal_ = (b[1] != 0);
    return true;
  }

 private:
  static constexpr float kDeg = 57.2957795f, kRad = 0.0174532925f;
  // Gates and gains for the slow pull. See the header for what each is for.
  static constexpr float kMaxDtS      = 0.25f;
  static constexpr float kAccGateG    = 0.05f;
  static constexpr float kTurnGateRad = 3.0f * kRad;
  static constexpr float kLatGateMps2 = 0.2f;     // speed x turn rate
  static constexpr float kDirGate     = 0.0523f;  // sin(3 deg)
  static constexpr float kKp          = 0.2f;     // 1/s: a 5 s pull
  static constexpr float kKi          = 0.01f;    // 1/s^2, critically damped with kKp
  static constexpr float kResyncS     = 10.0f;    // disagreeing this long at a steady speed...
  static constexpr float kResyncDvMps = 3.0f;     // ...meaning within this much of it
  // What "parked" means, over one block of kBlockS.
  static constexpr float kBlockS        = 1.0f;
  static constexpr float kStillGyroDps  = 1.0f;   // max - min, each axis
  static constexpr float kStillAccG     = 0.05f;
  static constexpr float kStillMagUt    = 1.5f;
  static constexpr float kStillMps      = 0.05f;  // wheels not turning: the bus sees 3 cm
  static constexpr float kNoBusTurnDps  = 0.3f;   // no bus: a turn this slow looks parked
  static constexpr float kBiasStepDps   = 0.1f;   // most a parked second may move the bias

  bool     calOn_ = false, haveCal_ = false;
  uint16_t calN_ = 0;
  float    h1_[3] = { 1, 0, 0 }, h2_[3] = { 0, 1, 0 }, h3_[3] = { 0, 0, 1 };
  float    lo_[3] = { 1e9f, 1e9f, 1e9f }, hi_[3] = { -1e9f, -1e9f, -1e9f };
  // The every-way sums, of u = m - ref_ and q = |u|^2. Doubles: they are what
  // the sphere is solved from, and a few thousand samples of q*q overrun a
  // float's seven digits.
  float    ref_[3] = { 0, 0, 0 };
  double   sN_ = 0, sQ_ = 0, sQQ_ = 0, sU_[3] = { 0, 0, 0 }, sQU_[3] = { 0, 0, 0 };
  double   sUU_[6] = { 0, 0, 0, 0, 0, 0 };  // xx xy xz yy yz zz

  float    gRef_ = 1.0f;                 // what this accelerometer reads for 1 g
  float    rateSum_ = 0, rateT_ = 0;
  float    disagreeT_ = 0, disagreeV_ = 0;
  bool     resync_ = false;
  uint8_t  stillRun_ = 0;                // parked blocks in a row, until the bias is known

  // The current block: sums and spans of everything, to decide "parked".
  float    bT_ = 0, bAcc_[3] = { 0, 0, 0 }, bGyr_[3] = { 0, 0, 0 };
  float    bGyrLo_[3] = { 0 }, bGyrHi_[3] = { 0 }, bAccLo_[3] = { 0 }, bAccHi_[3] = { 0 };
  float    bMagLo_[3] = { 0 }, bMagHi_[3] = { 0 };
  uint16_t bN_ = 0, bMagN_ = 0;
  bool     bMoving_ = false;             // the bus said moving at some point

  static float dot_(const float* a, const float* b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
  static float norm_(const float* a) { return sqrtf(dot_(a, a)); }
  static void cross_(const float* a, const float* b, float* o) {
    o[0] = a[1] * b[2] - a[2] * b[1];
    o[1] = a[2] * b[0] - a[0] * b[2];
    o[2] = a[0] * b[1] - a[1] * b[0];
  }
  static void normalize_(float* a) {
    const float n = norm_(a);
    if (n > 0.0f) for (uint8_t i = 0; i < 3; i++) a[i] /= n;
  }
  static float wrap360_(float d) {
    d = fmodf(d, 360.0f);
    return d < 0.0f ? d + 360.0f : d;
  }
  static uint8_t sum_(const uint8_t* b) {
    uint8_t s = 0;
    for (uint8_t i = 0; i < STORE_BYTES - 1; i++) s = (uint8_t)(s + b[i]);
    return s;
  }

  void blockReset_() {
    bT_ = 0; bN_ = 0; bMagN_ = 0; bMoving_ = false;
    for (uint8_t i = 0; i < 3; i++) { bAcc_[i] = 0; bGyr_[i] = 0; }
  }

  static void span_(const float* v, float* lo, float* hi, bool first) {
    for (uint8_t i = 0; i < 3; i++) {
      if (first || v[i] < lo[i]) lo[i] = v[i];
      if (first || v[i] > hi[i]) hi[i] = v[i];
    }
  }
  static bool within_(const float* lo, const float* hi, float limit) {
    for (uint8_t i = 0; i < 3; i++) if (hi[i] - lo[i] >= limit) return false;
    return true;
  }

  void magAdd_(const float* m) {
    span_(m, bMagLo_, bMagHi_, bMagN_ == 0);
    if (bMagN_ < 65000) bMagN_++;
  }

  void blockAdd_(const float* acc, const float* gyr, float dtS, float speedMps) {
    span_(acc, bAccLo_, bAccHi_, bN_ == 0);
    span_(gyr, bGyrLo_, bGyrHi_, bN_ == 0);
    for (uint8_t i = 0; i < 3; i++) { bAcc_[i] += acc[i]; bGyr_[i] += gyr[i]; }
    bN_++;
    bT_ += dtS;
    if (speedMps >= kStillMps) bMoving_ = true;
    if (bT_ >= kBlockS) { blockEnd_(speedMps < 0.0f); blockReset_(); }
  }

  /**
   * A second has passed. If the car was parked for all of it, the averages are
   * the best gravity and gyro bias there will be: take them.
   *
   * "Parked" is the bus saying so -- the wheels not turning for the whole
   * second; 0x1A6 counts every 3 cm, so even creeping round a parking space at
   * walking pace reads as moving -- and the gyro and accelerometer agreeing,
   * which a door or somebody climbing in does not. With no bus the readings alone decide, and a long gentle
   * curve is as steady as a car park, so then the compass must be steady too,
   * and a mean turn of more than kNoBusTurnDps from the known bias is taken as
   * the car turning, not as the bias moving.
   */
  void blockEnd_(bool noBus) {
    if (bMoving_ || bN_ == 0) { stillRun_ = 0; return; }
    if (!within_(bGyrLo_, bGyrHi_, kStillGyroDps) || !within_(bAccLo_, bAccHi_, kStillAccG)) {
      stillRun_ = 0; return;
    }

    float g[3], a[3];
    for (uint8_t i = 0; i < 3; i++) { g[i] = bGyr_[i] / bN_; a[i] = bAcc_[i] / bN_; }
    if (noBus) {
      if (bMagN_ > 0 && !within_(bMagLo_, bMagHi_, kStillMagUt)) { stillRun_ = 0; return; }
      if (biasKnown) {
        for (uint8_t i = 0; i < 3; i++) if (fabsf(g[i] - bias[i]) > kNoBusTurnDps) return;
      }
    }

    const float an = norm_(a);
    if (an > 0.5f && an < 1.5f) {
      for (uint8_t i = 0; i < 3; i++) up[i] = a[i] / an;
      gRef_ = an;
    }

    if (!biasKnown) {
      // Two parked seconds in a row, averaged, before the rate is trusted.
      for (uint8_t i = 0; i < 3; i++) bias[i] = stillRun_ ? (bias[i] + g[i]) * 0.5f : g[i];
      if (++stillRun_ >= 2) biasKnown = true;
    } else {
      // A bias drifts with temperature, slowly, and a parked second that was
      // somehow not parked -- no bus, a car on a ferry -- must not be able to
      // throw it: each may nudge the bias, not replace it.
      for (uint8_t i = 0; i < 3; i++) {
        float d = g[i] - bias[i];
        if (d >  kBiasStepDps) d =  kBiasStepDps;
        if (d < -kBiasStepDps) d = -kBiasStepDps;
        bias[i] += d;
      }
    }
  }

  void calFeed_(const float* m) {
    // Plain extremes. Noise pushes both ends out alike, which moves the span
    // and not the centre; a dead band on the bounds, which the flat compass
    // had, held each end short by up to its width and moved the centre.
    const float p[3] = { dot_(m, h1_), dot_(m, h2_), dot_(m, h3_) };
    for (uint8_t i = 0; i < 3; i++) {
      if (p[i] < lo_[i]) lo_[i] = p[i];
      if (p[i] > hi_[i]) hi_[i] = p[i];
    }
    if (calN_ < 65000) calN_++;

    // Every way. Relative to the first sample, so the sums stay small.
    if (sN_ == 0) memcpy(ref_, m, sizeof ref_);
    double u[3];
    for (uint8_t i = 0; i < 3; i++) u[i] = (double)m[i] - ref_[i];
    const double q = u[0] * u[0] + u[1] * u[1] + u[2] * u[2];
    sN_ += 1; sQ_ += q; sQQ_ += q * q;
    for (uint8_t i = 0; i < 3; i++) { sU_[i] += u[i]; sQU_[i] += q * u[i]; }
    sUU_[0] += u[0] * u[0]; sUU_[1] += u[0] * u[1]; sUU_[2] += u[0] * u[2];
    sUU_[3] += u[1] * u[1]; sUU_[4] += u[1] * u[2]; sUU_[5] += u[2] * u[2];
  }

  /**
   * The every-way offset: the centre of the sphere the samples lie on. Least
   * squares on |u|^2 = 2 u.c + k, which is linear in the centre c and in k =
   * R^2 - |c|^2. False unless the samples cover enough of the sphere -- a flat
   * circle fits any sphere through it, and the vertical then comes out as
   * noise -- and lie on it.
   */
  bool sphere_(float* o) {
    calCover = calRadiusUt = calFitUt = 0;
    const double n = sN_;
    if (n < 4) return false;
    const double* S = sUU_;
    double a[4][5] = {
      { 4 * S[0], 4 * S[1], 4 * S[2], 2 * sU_[0], 2 * sQU_[0] },
      { 4 * S[1], 4 * S[3], 4 * S[4], 2 * sU_[1], 2 * sQU_[1] },
      { 4 * S[2], 4 * S[4], 4 * S[5], 2 * sU_[2], 2 * sQU_[2] },
      { 2 * sU_[0], 2 * sU_[1], 2 * sU_[2], n, sQ_ } };
    double c[4];
    if (!solve4_(a, c)) return false;
    const double r2 = c[3] + c[0] * c[0] + c[1] * c[1] + c[2] * c[2];
    if (!(r2 > 0)) return false;
    const double r = sqrt(r2);

    // How far off the sphere the samples were: the equation's residual is
    // about 2R times the distance. Summed from the same sums, no second pass.
    const double cSc = c[0] * c[0] * S[0] + c[1] * c[1] * S[3] + c[2] * c[2] * S[5] +
                       2 * (c[0] * c[1] * S[1] + c[0] * c[2] * S[2] + c[1] * c[2] * S[4]);
    const double ssr = sQQ_ + 4 * cSc + n * c[3] * c[3] -
                       4 * (c[0] * sQU_[0] + c[1] * sQU_[1] + c[2] * sQU_[2]) - 2 * c[3] * sQ_ +
                       4 * c[3] * (c[0] * sU_[0] + c[1] * sU_[1] + c[2] * sU_[2]);

    // How thick the cloud is in its thinnest direction. For the covariance,
    // det / (sum of its principal 2x2 minors) lies between a third of the
    // smallest eigenvalue and the smallest eigenvalue, with no eigen-solver.
    const double m0 = sU_[0] / n, m1 = sU_[1] / n, m2 = sU_[2] / n;
    const double xx = S[0] / n - m0 * m0, xy = S[1] / n - m0 * m1, xz = S[2] / n - m0 * m2,
                 yy = S[3] / n - m1 * m1, yz = S[4] / n - m1 * m2, zz = S[5] / n - m2 * m2;
    const double det = xx * (yy * zz - yz * yz) - xy * (xy * zz - yz * xz) + xz * (xy * yz - yy * xz);
    const double minors = xx * yy - xy * xy + xx * zz - xz * xz + yy * zz - yz * yz;

    calRadiusUt = (float)r;
    calFitUt = (float)(sqrt(ssr > 0 ? ssr / n : 0) / (2 * r));
    calCover = (float)(det > 0 && minors > 0 ? sqrt(det / minors) / r : 0);
    if (calCover < COMPASS_CAL_COVER) return false;
    if (r < COMPASS_MIN_FIELD_UT || r > COMPASS_MAX_FIELD_UT) return false;
    if (calFitUt > COMPASS_CAL_FIT * r) return false;
    for (uint8_t i = 0; i < 3; i++) o[i] = (float)(ref_[i] + c[i]);
    return true;
  }

  /** a x = the last column, 4 by 4, partial pivoting. False when singular. */
  static bool solve4_(double a[4][5], double* x) {
    for (uint8_t c = 0; c < 4; c++) {
      uint8_t p = c;
      for (uint8_t r = c + 1; r < 4; r++) if (fabs(a[r][c]) > fabs(a[p][c])) p = r;
      if (!(fabs(a[p][c]) > 1e-9)) return false;
      if (p != c) for (uint8_t k = 0; k < 5; k++) { const double t = a[c][k]; a[c][k] = a[p][k]; a[p][k] = t; }
      for (uint8_t r = c + 1; r < 4; r++) {
        const double f = a[r][c] / a[c][c];
        for (uint8_t k = c; k < 5; k++) a[r][k] -= f * a[c][k];
      }
    }
    for (int8_t r = 3; r >= 0; r--) {
      double s = a[r][4];
      for (uint8_t k = r + 1; k < 4; k++) s -= a[r][k] * x[k];
      x[r] = s / a[r][r];
    }
    return true;
  }
};

// hud_store.h reserves the flash bytes for this by hand, in a file included
// before this one. The build fails if the two disagree, rather than every
// saved calibration failing its own checksum on reload. Guarded because the
// heading test compiles this file on its own, without the flash layout.
#ifdef HUD_MAG_STORE_BYTES
static_assert(HudHeading::STORE_BYTES == HUD_MAG_STORE_BYTES,
              "hud_store.h reserves a different number of flash bytes than HudHeading packs");
#endif

#endif  // HUD_HEADING_H
