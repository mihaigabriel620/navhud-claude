// ---------------------------------------------------------------------------
//  hud_mag.h -- the compass: calibration, health, and the heading it feeds.
//
//  The CHIP lives in hud_qmc.h. This file is everything that is true whatever
//  chip is fitted -- the hard-iron offset, whether the reading can still be
//  believed, and the flash it is all kept in -- because none of that is about
//  the magnetometer. It is about the car.
//
//  A gyro reports a turn RATE. It can say how far the car turned and never
//  which way it is pointing, so it cannot answer "which way do I pull out of
//  this parking space". That is the question this exists for.
//
//  WHY THIS NO LONGER HANGS OFF THE IMU
//
//  It used to be a member of HudImu, started from inside HudImu::begin() after
//  the MPU had answered. That was right for the AK8963 it was written for: that
//  chip is inside the MPU-9250 package, behind the MPU's I2C bypass, and there
//  is genuinely no way to reach it without the MPU.
//
//  A QMC5883 is its own board on the same two wires. Keeping the old structure
//  meant an MPU that was unplugged, dead, or never fitted took the compass down
//  with it -- the IMU returned early on its WHO_AM_I and the compass was never
//  started at all. It answers perfectly well; nothing was asking it anything.
// ---------------------------------------------------------------------------
#ifndef HUD_MAG_H
#define HUD_MAG_H

#ifdef HUD_HOST_TEST
  #include "tft_stub.h"
#else
  #include <Arduino.h>
#endif
#include <Wire.h>
#include <math.h>


/** How the attempt to bring the compass up ended. */
enum MagStatus : uint8_t {
  MAG_OK = 0,
  MAG_NO_BYPASS,      // could not talk to the MPU at all
  MAG_ABSENT,         // nothing answered at either QMC5883 address
  MAG_WRONG_ID,       // something answers, but it is not a QMC5883
};

#include "hud_qmc.h"

class HudMag {
 public:
  bool     present = false;
  MagStatus status = MAG_ABSENT;

  HudQmc chip;

  /** Last reading in microtesla, already rotated into the MPU's axes. */
  float x = 0, y = 0, z = 0;

  /**
   * Open the bypass and start the compass at 100 Hz.
   *
   * @param mpuAddr the MPU's own address, needed to reach USER_CTRL and
   *        INT_PIN_CFG. Wire.begin() is the caller's job -- hud_imu.h has
   *        already done it by the time this runs, and calling it twice with
   *        different pins is how you lose an afternoon.
   */
  /**
   * Find and start the compass. Takes nothing, depends on nothing.
   *
   * It used to be started from inside HudImu::begin(), after the MPU had
   * answered -- correctly, for the AK8963 it was written for, because that
   * chip lives INSIDE the MPU package behind its I2C bypass and genuinely
   * cannot be reached without it.
   *
   * A QMC5883 is its own board on the same two wires and has no such
   * relationship. Leaving the old structure in place meant an MPU that was
   * unplugged, dead, or simply not fitted took the compass down with it: the
   * IMU returned early on its WHO_AM_I and this was never called at all. The
   * symptom is a compass that "does not answer" while sitting there answering
   * perfectly well.
   *
   * Wire.begin() is the caller's job and must already have happened.
   */
  bool begin() {
    present = false;
    if (!chip.begin()) { status = MAG_ABSENT; return false; }
    status  = MAG_OK;
    present = true;
    return true;
  }

  /**
   * Take a reading. Returns false when there is nothing new or the sample is
   * unusable, in which case x/y/z are left alone.
   */
  bool read() {
    if (!present) return false;
    if (!chip.read()) return false;
    // The QMC is a separate board, so its axes are whatever you glued them to
    // and need not match the MPU's. MAG_AXIS_ORDER and MAG_AXIS_SIGN in
    // hud_config.h say how the two relate; the default is "the same way up",
    // which is what you get by sticking both to the same face.
    //
    // Getting this wrong does not look like a fault. It looks like a compass
    // that is confidently wrong in a way that changes with heading -- which is
    // why magDipDeg() below exists to catch it.
    static const uint8_t ord[3]  = { MAG_AXIS_ORDER };
    static const int8_t  sgn[3]  = { MAG_AXIS_SIGN };
    const float raw[3] = { chip.x, chip.y, chip.z };
    x = sgn[0] * raw[ord[0]] - hard_[0];
    y = sgn[1] * raw[ord[1]] - hard_[1];
    z = sgn[2] * raw[ord[2]] - hard_[2];
    return true;
  }

  /**
   * The angle between the measured field and the horizontal plane, degrees.
   *
   * A free check that the axis mapping is right, and it needs no reference and
   * no calibration. The Earth's field dips into the ground at an angle that is
   * a property of WHERE YOU ARE and nothing else -- about 65 degrees in
   * central Europe, 0 at the magnetic equator, 90 at the poles. It does not
   * care which way the car is pointing.
   *
   * So: turn the car through a full circle and this number should barely move.
   * If it swings by tens of degrees as you turn, the magnetometer's axes are
   * not the accelerometer's, and the heading is wrong in a way that no amount
   * of hard-iron calibration will fix.
   *
   * @param a accelerometer in the SAME frame the mapping above produces
   */
  float dipDeg(const float* a) const {
    const float an = sqrtf(a[0]*a[0] + a[1]*a[1] + a[2]*a[2]);
    const float mn = sqrtf(x*x + y*y + z*z);
    if (an < 1e-3f || mn < 1e-3f) return NAN;
    // Gravity points UP on an MPU at rest, and the field dips DOWN in the
    // northern hemisphere, so the two are more than 90 degrees apart and the
    // sign here makes the answer the positive dip everyone quotes.
    const float c = -(x*a[0] + y*a[1] + z*a[2]) / (mn * an);
    return asinf(c < -1.0f ? -1.0f : (c > 1.0f ? 1.0f : c)) * 57.29577951f;
  }

  /**
   * Heading in degrees clockwise from magnetic north, tilt-compensated.
   *
   * Takes the accelerometer's gravity vector rather than assuming the board is
   * level, because it is not: it sits at whatever angle the dash slopes at,
   * and the car pitches under braking. An uncompensated compass is only right
   * when it is flat -- and the error is worst exactly where you need it, since
   * the projection of a tilted horizontal axis swings fast for a small change
   * in tilt.
   *
   * Standard eCompass form (NXP AN4248): rotate the field back through roll
   * and pitch, then take the arctangent of the horizontal components.
   */
  float headingDeg(float ax, float ay, float az) const {
    const float roll  = atan2f(ay, az);
    const float pitch = atan2f(-ax, sqrtf(ay * ay + az * az));
    const float sr = sinf(roll),  cr = cosf(roll);
    const float sp = sinf(pitch), cp = cosf(pitch);

    const float hx = x * cp + y * sr * sp + z * cr * sp;
    const float hy = y * cr - z * sr;
    // atan2(hy, hx), NOT atan2(-hy, hx).
    //
    // The negated form is the one written down in NXP AN4248, and it is right
    // for the frame that note uses: Y to the right, Z down. This frame is the
    // MPU's -- X out of the nose, Z up (hud_imu.h reads +1 g on the up axis),
    // and therefore, being right-handed, Y to the LEFT. Point the nose east
    // and magnetic north is off the left wing, so hy is positive and the
    // answer is +90. The negated form gives 270. North and south come out
    // right either way, which is exactly what makes this the kind of mistake
    // you confirm on the bench and then discover on the road.
    float deg = atan2f(hy, hx) * 180.0f / (float)M_PI;
    if (deg < 0) deg += 360.0f;
    return deg;
  }

  // ---- is it actually working? --------------------------------------------
  //
  // A stored calibration tells you the numbers survived the power cycle. It
  // does not tell you they are still *right*, and the difference matters:
  // somebody adds a phone mount with a magnet in it, the backlight wiring gets
  // rerouted, a speaker goes in the parcel shelf, and the compass carries on
  // reporting a heading with total confidence.
  //
  // The check is free and it needs no reference value. Earth's field has a
  // fixed magnitude at a given place -- about 49 uT in Belgium -- so once the
  // hard-iron offset is right, sqrt(x^2+y^2+z^2) reads the SAME at every
  // heading. Turn the car and watch it: if the magnitude swings, either the
  // offset is stale or there is a new magnetic source riding along, and in
  // both cases the heading is wrong at some angles and fine at others, which
  // is the most confusing possible failure.
  //
  // This will not catch soft iron. A steel bracket stretches the circle into
  // an ellipse, and the min and max of |B| then differ by design rather than
  // by fault -- which is exactly what this reports, so a large spread that
  // will not calibrate away is itself the diagnosis: something ferrous is
  // close by, and no offset correction can fix it.

  /** Magnitude of the corrected field, microtesla. */
  float field() const { return sqrtf(x * x + y * y + z * z); }

  /**
   * Feed the health tracker. Call after each successful read().
   *
   * Keeps a rolling window rather than an all-time min/max: an all-time one
   * would latch on the single moment a starter motor fired and never recover,
   * so the display would say "recalibrate" for the rest of the drive because
   * of one event two hours ago.
   */
  void healthFeed(uint32_t nowMs) {
    const float f = field();
    if (healthStartMs_ == 0 || nowMs - healthStartMs_ > HEALTH_WINDOW_MS) {
      healthStartMs_ = nowMs;
      // Carry the finished window forward so there is always something to
      // report, rather than a blank for the first half minute.
      lastLo_ = winLo_; lastHi_ = winHi_;
      winLo_ = winHi_ = f;
      return;
    }
    if (f < winLo_) winLo_ = f;
    if (f > winHi_) winHi_ = f;
  }

  /**
   * How much the magnitude moved across the last full window, as a fraction
   * of its middle. 0 is perfect; 0.15 is where it stops being reassuring.
   */
  float fieldSpread() const {
    const float lo = lastLo_ > 0 ? lastLo_ : winLo_;
    const float hi = lastHi_ > 0 ? lastHi_ : winHi_;
    const float mid = (hi + lo) * 0.5f;
    if (mid < 1.0f) return 0.0f;
    return (hi - lo) / mid;
  }

  /** Earth's field is 25-65 uT worldwide; outside that, something is wrong. */
  bool fieldPlausible() const {
    const float f = field();
    return f > 20.0f && f < 80.0f;
  }

  // ---- hard-iron calibration ----------------------------------------------
  //
  // The car is a steel box with magnets in the speakers and a hundred amps
  // going through it, so the field the chip sees is the Earth's plus a large
  // constant offset. Subtracting that offset is the whole of hard-iron
  // calibration, and it is the difference between a compass that works and one
  // that reads the same direction whichever way you point it.
  //
  // The offset is a constant only because the board is bolted to the car and
  // never moves relative to it. That is exactly the thing a phone cannot do,
  // and it is why this is worth doing here and was not worth doing there.
  //
  // Method: drive a slow full circle in an empty car park with calibration
  // running, then take the centre of the box the readings swept out. Cruder
  // than an ellipsoid fit, and enough -- soft-iron distortion scales the
  // circle into an ellipse, which costs a few degrees, while hard iron shifts
  // its centre, which costs tens.

  void calStart() {
    for (uint8_t i = 0; i < 3; i++) { lo_[i] = 1e6f; hi_[i] = -1e6f; }
    calSamples_ = 0;
    calibrating_ = true;
  }

  /** Feed the raw (offset-free) field. Call after each successful read(). */
  void calFeed() {
    if (!calibrating_) return;
    const float v[3] = { x + hard_[0], y + hard_[1], z + hard_[2] };
    for (uint8_t i = 0; i < 3; i++) {
      if (v[i] < lo_[i]) lo_[i] = v[i];
      if (v[i] > hi_[i]) hi_[i] = v[i];
    }
    calSamples_++;
  }

  /**
   * Finish, and adopt the result only if the drive actually swept a circle.
   *
   * A calibration taken while parked has a tiny spread and would centre the
   * offset on wherever the car happened to be pointing -- which is worse than
   * no calibration, because it looks calibrated. 20 uT is about half the
   * Earth's horizontal field in Belgium, so a genuine full turn clears it
   * easily and a wiggle in a parking space does not.
   */
  bool calFinish() {
    if (calSamples_ < 200) return false;
    for (uint8_t i = 0; i < 2; i++)                 // X and Y carry the heading
      if (hi_[i] - lo_[i] < 20.0f) return false;
    // Only now. Clearing it above the gates -- which is where it used to be --
    // meant that asking to save too early ended the calibration while printing
    // "turn it further and try again", and calFeed() then ignored everything
    // that came after. Worse here than for the mounting, because starting over
    // wipes lo_/hi_ and throws away the turning already done.
    calibrating_ = false;
    // Assignment, not +=. calFeed reconstructs the RAW field (it adds hard_
    // back on), so the midpoint of the box it swept IS the whole offset. Adding
    // it to what was already there double-counted on any second calibration in
    // one power cycle, and on the first calibration after a saved offset was
    // restored at boot -- which is the normal case, not the exotic one.
    for (uint8_t i = 0; i < 3; i++) hard_[i] = (hi_[i] + lo_[i]) * 0.5f;
    return true;
  }

  bool  calibrating() const { return calibrating_; }
  uint16_t calCount() const { return calSamples_; }

  /** Abandon a calibration in progress, keeping whatever was there before. */
  void calStop() { calibrating_ = false; }

  /**
   * How far axis i has swept so far, microtesla.
   *
   * Worth showing while a calibration runs, because it is the only way to see
   * WHICH part of the turn is missing. Turned in the hand through every face,
   * all three should reach roughly twice the local field -- about 100 uT in
   * Europe. Driven in a circle instead, X and Y fill and Z stays near zero,
   * because a car cannot roll over: that is not a fault, it is the reason a
   * driven circle cannot find the vertical offset at all.
   */
  float calSpan(uint8_t i) const {
    if (i > 2 || hi_[i] < lo_[i]) return 0.0f;
    return hi_[i] - lo_[i];
  }

  /** How many axes have swept enough to have been genuinely covered. */
  uint8_t calAxesCovered() const {
    uint8_t n = 0;
    for (uint8_t i = 0; i < 3; i++) if (calSpan(i) >= 20.0f) n++;
    return n;
  }

  void hardIron(float* out) const { for (uint8_t i = 0; i < 3; i++) out[i] = hard_[i]; }
  void setHardIron(const float* in) { for (uint8_t i = 0; i < 3; i++) hard_[i] = in[i]; }

  /**
   * Pack the hard-iron offsets into 13 bytes: three int16 in tenths of a
   * microtesla, a magic byte and a checksum.
   *
   * Tenths because the field is tens of microtesla and a tenth is far below
   * what a car's own iron varies by; int16 covers +-3276 uT, which is beyond
   * anything the chip can even report.
   */
  void pack(uint8_t* b) const {
    b[0] = 0xA8;                                   // "there is a calibration here"
    for (uint8_t i = 0; i < 3; i++) {
      int32_t v = (int32_t)lroundf(hard_[i] * 10.0f);
      if (v >  32767) v =  32767;
      if (v < -32768) v = -32768;
      b[1 + i * 2] = (uint8_t)((uint16_t)v >> 8);
      b[2 + i * 2] = (uint8_t)((uint16_t)v & 0xFF);
    }
    uint8_t sum = 0;
    for (uint8_t i = 0; i < 7; i++) sum ^= b[i];
    b[7] = sum;
  }

  /** Returns false and changes nothing when the block is blank or damaged. */
  bool unpack(const uint8_t* b) {
    if (b[0] != 0xA8) return false;
    uint8_t sum = 0;
    for (uint8_t i = 0; i < 7; i++) sum ^= b[i];
    if (sum != b[7]) return false;
    for (uint8_t i = 0; i < 3; i++) {
      const int16_t v = (int16_t)(((uint16_t)b[1 + i * 2] << 8) | b[2 + i * 2]);
      hard_[i] = v / 10.0f;
    }
    return true;
  }

  bool calibrated() const {
    return hard_[0] != 0.0f || hard_[1] != 0.0f || hard_[2] != 0.0f;
  }

  const char* describe() const { return chip.describe(); }

 private:
  static const uint32_t HEALTH_WINDOW_MS = 30000;
  uint32_t healthStartMs_ = 0;
  float winLo_ = 0, winHi_ = 0, lastLo_ = 0, lastHi_ = 0;

  float adj_[3]  = { 1.0f, 1.0f, 1.0f };
  float hard_[3] = { 0, 0, 0 };
  float lo_[3]   = { 0, 0, 0 };
  float hi_[3]   = { 0, 0, 0 };
  uint16_t calSamples_ = 0;
  bool  calibrating_ = false;

  static bool write8(uint8_t addr, uint8_t reg, uint8_t val) {
    Wire.beginTransmission(addr);
    Wire.write(reg);
    Wire.write(val);
    return Wire.endTransmission() == 0;
  }

  static bool read(uint8_t addr, uint8_t reg, uint8_t* buf, uint8_t n) {
    Wire.beginTransmission(addr);
    Wire.write(reg);
    if (Wire.endTransmission(false) != 0) return false;
    if (Wire.requestFrom((int)addr, (int)n) != n) return false;
    for (uint8_t i = 0; i < n; i++) buf[i] = Wire.read();
    return true;
  }
};

#endif  // HUD_MAG_H
