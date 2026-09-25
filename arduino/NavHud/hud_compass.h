// ---------------------------------------------------------------------------
//  hud_compass.h -- which way the car is pointing.
//
//  The QMC5883P, driven directly. No library, and there is a reason.
//
//  Every value here is from QST doc 13-52-19 (Table 14 register map, section 7
//  application examples). The ORDER of the last two writes is the part that
//  cost days:
//
//      7.2 Continuous Mode Setup Example
//        Write Register 29H by 0x06   (sign for X Y and Z axis)
//        Write Register 0BH by 0x08   (Set/Reset On, Field Range 8 Gauss)
//        Write Register 0AH by 0xC3   (set continuous mode)
//
//  0BH first, 0AH LAST, because writing 0AH is what STARTS the chip -- suspend
//  is the default state both after power-on and after a soft reset (6.2.4).
//  Adafruit's library writes mode/ODR/OSR/DSR into 0AH and only afterwards puts
//  range and set/reset into 0BH: it starts the sensor, then reconfigures it
//  while it runs. On this chip that produced status 0x08 with DRDY never
//  setting -- present, addressable, configured, and not measuring.
//
//  THE SCALE IS READ BACK, NEVER ASSUMED. On the bench part, 0BH reads 0x00
//  after being written 0x08: the range write does not stick and it sits at
//  +-30 G, 1000 LSB per gauss. Scaling for the 8 G it was ASKED for gave 13.5 uT
//  where Belgium is 49; scaling for the 30 G it REPORTS gives 50.7. Ask, then
//  believe the answer.
//
//  WHAT THIS DELIBERATELY DOES NOT DO
//
//  No tilt compensation, because that needs gravity and there is no
//  accelerometer on this board. It assumes the chip is level. Magnetic dip in
//  central Europe is about 65 degrees and NXP AN4249 gives the error
//  amplification as tan(dip) = 2.14, so every degree out of level becomes 2.1
//  degrees of heading error. Mount it flat.
// ---------------------------------------------------------------------------
#ifndef HUD_COMPASS_H
#define HUD_COMPASS_H

#include <math.h>
#include <stdint.h>
#include <string.h>
#include "hud_i2c.h"
#include "hud_config.h"

#define QMCP_ADDR       0x2C
#define QMCP_REG_ID     0x00      // reads 0x80
#define QMCP_ID_VALUE   0x80
#define QMCP_REG_DATA   0x01      // XL XH YL YH ZL ZH, low byte first
#define QMCP_REG_STATUS 0x09      // bit0 DRDY, bit1 OVFL
#define QMCP_REG_CTRL1  0x0A      // OSR2[7:6] OSR1[5:4] ODR[3:2] MODE[1:0]
#define QMCP_REG_CTRL2  0x0B      // SOFT_RST[7] SELF_TEST[6] rfu RNG[3:2] SETRESET[1:0]
#define QMCP_REG_SIGN   0x29      // undocumented; appears only in section 7

// 0xCB = OSR2 11, OSR1 00, ODR 10 (100 Hz), MODE 11 (continuous). QST's literal
// example is 0xC3, the same but at 10 Hz -- too slow to feed a calibration from.
#define QMCP_CTRL1_RUN  0xCB
// Set/Reset On, range 8 G. Requested; not necessarily granted -- see above.
// Set-and-reset on is the setting that matters: 9.2.3 says "in SET ONLY ON or
// SET AND RESET OFF mode, the offset is not renewed during measuring", so these
// bits at 00 are what buy the per-measurement degaussing.
#define QMCP_CTRL2_RUN  0x08

static const float   QMCP_LSB_PER_G[4] = { 1000.0f, 2500.0f, 3750.0f, 15000.0f };
static const uint8_t QMCP_RANGE_G[4]   = { 30, 12, 8, 2 };

/** How far the field must move before a calibration sample shifts a bound. */
#define COMPASS_CAL_STEP_UT   1.0f
/** Below this the numbers are noise. Earth's field is 25-65 uT. */
#define COMPASS_MIN_FIELD_UT  15.0f
/** Above this something magnetic is sitting on the sensor. */
#define COMPASS_MAX_FIELD_UT  120.0f
/**
 * No new sample for this long and the chip is not measuring: it browned out
 * (it comes back in suspend mode) or it left the bus. It runs at 100 Hz, so
 * this is a hundred missed samples. update() then stops calling it present,
 * and the sketch's MAG_RETRY_MS retry brings it up again.
 */
#define COMPASS_SILENT_MS     1000

class HudCompass {
 public:
  /** Full scale the chip REPORTS, in gauss. 0 before begin() succeeds. */
  uint8_t rangeG = 0;
  /** CTRL1/CTRL2 as read back after configuration, for `status`. */
  uint8_t ctrl1 = 0, ctrl2 = 0;
  /** Did a real sample arrive during bring-up? */
  bool dataSeen = false;

  /** Heading, degrees, 0 = north, 90 = east. NAN until the first reading. */
  float headingDeg = NAN;
  /** Magnitude of the corrected field, microtesla. */
  float fieldUt = 0;

  /** Stored: hard-iron offset and per-axis gain, in the CAR's frame. */
  float offset[3] = { 0, 0, 0 };
  float gain[3]   = { 1, 1, 1 };
  /** Stored: added to the computed heading. Declination plus mount yaw. */
  float northOffsetDeg = 0;

  bool present() const     { return present_; }
  bool calibrating() const { return calOn_; }
  bool calibrated() const  { return haveCal_; }
  uint16_t calCount() const { return calN_; }

  const char* describe() const {
    return present_ ? "QMC5883P at 0x2C" : "no QMC5883P found at 0x2C";
  }

  /** Find the chip and start it, in QST's order. */
  bool begin() {
    present_ = false;
    if (!i2cPresent(QMCP_ADDR)) return false;

    uint8_t id = 0;
    if (!i2cRead(QMCP_ADDR, QMCP_REG_ID, &id, 1) || id != QMCP_ID_VALUE) return false;

    i2cWrite8(QMCP_ADDR, QMCP_REG_CTRL2, 0x80);           // 7.6 soft reset
    delay(50);                                            // no figure given; generous
    i2cWrite8(QMCP_ADDR, QMCP_REG_SIGN,  0x06);           // sign for X Y Z
    i2cWrite8(QMCP_ADDR, QMCP_REG_CTRL2, QMCP_CTRL2_RUN); // Set/Reset On, 8 G
    i2cWrite8(QMCP_ADDR, QMCP_REG_CTRL1, QMCP_CTRL1_RUN); // continuous <- starts it
    delay(20);

    i2cRead(QMCP_ADDR, QMCP_REG_CTRL1, &ctrl1, 1);
    i2cRead(QMCP_ADDR, QMCP_REG_CTRL2, &ctrl2, 1);
    const uint8_t rng = (uint8_t)((ctrl2 >> 2) & 0x03);
    utPerLsb_ = 100.0f / QMCP_LSB_PER_G[rng];
    rangeG    = QMCP_RANGE_G[rng];

    // A configured chip that never finishes a measurement is the most confusing
    // failure available, so bring-up does not claim success until a real sample
    // has landed.
    dataSeen = false;
    for (uint8_t i = 0; i < 40 && !dataSeen; i++) {       // up to 400 ms
      uint8_t st = 0;
      if (i2cRead(QMCP_ADDR, QMCP_REG_STATUS, &st, 1) && (st & 0x01)) dataSeen = true;
      else delay(10);
    }

    present_ = true;
    lastDrdyMs_ = millis();
    return true;
  }

  /** One sample. True when a new reading was taken. */
  bool update() {
    if (!present_) return false;

    // Status FIRST and on its own. On the P, DRDY is cleared by reading the
    // STATUS register, not the data -- fold them into one burst and the flag is
    // consumed before it is tested, and update() never returns true again.
    uint8_t st = 0;
    const bool read = i2cRead(QMCP_ADDR, QMCP_REG_STATUS, &st, 1);
    if (!read || !(st & 0x01)) {                          // nothing new
      // A second of nothing is not a quiet chip: it browned out and came back
      // in suspend, or it is gone. Before, it stayed "present" for ever, the
      // retry never ran, and $MAG stopped for the rest of the drive.
      if ((uint32_t)(millis() - lastDrdyMs_) > COMPASS_SILENT_MS) present_ = false;
      return false;
    }
    lastDrdyMs_ = millis();                               // measuring, even on overflow
    if (st & 0x02)   return false;                        // overflow

    uint8_t d[6];
    if (!i2cRead(QMCP_ADDR, QMCP_REG_DATA, d, 6)) return false;
    const int16_t rx = (int16_t)((uint16_t)d[1] << 8 | d[0]);
    const int16_t ry = (int16_t)((uint16_t)d[3] << 8 | d[2]);
    const int16_t rz = (int16_t)((uint16_t)d[5] << 8 | d[4]);

    float v[3];
    remap_(rx * utPerLsb_, ry * utPerLsb_, rz * utPerLsb_, v);
    if (calOn_) feed_(v);

    const float mx = (v[0] - offset[0]) * gain[0];
    const float my = (v[1] - offset[1]) * gain[1];
    const float mz = (v[2] - offset[2]) * gain[2];
    fieldUt = sqrtf(mx * mx + my * my + mz * mz);

    // X forward, Y left, Z up, heading clockwise from north.
    //
    // There is NO minus sign, and that is worth defending because the reference
    // everyone reaches for has one: NXP AN4248 derives atan2(-my, mx) for a
    // frame with Y pointing RIGHT and Z DOWN. This frame is Y left, Z up.
    // Pointing north the horizontal field is straight ahead -- mx = +H, my = 0,
    // atan2(0, H) = 0. Pointing east it is off the left wing -- mx = 0, my = +H,
    // atan2(H, 0) = +90, which is east. Put the minus back and the compass reads
    // correctly at north and south and mirrored at east and west.
    float deg = atan2f(my, mx) * 57.2957795f + northOffsetDeg;
    while (deg < 0)       deg += 360.0f;
    while (deg >= 360.0f) deg -= 360.0f;
    headingDeg = deg;
    return true;
  }

  /** Start a calibration. Drive a slow full circle, or turn the board round. */
  void calStart() {
    calOn_ = true; calN_ = 0;
    for (uint8_t i = 0; i < 3; i++) { lo_[i] = 1e9f; hi_[i] = -1e9f; }
  }

  /**
   * Finish. Returns false -- and keeps the session running -- when what was
   * collected is not good enough. Gates BEFORE the flag is cleared: the other
   * way round, an early `spin stop` silently ends the session while printing
   * "keep going".
   */
  bool calFinish() {
    if (calN_ < COMPASS_CAL_MIN_SAMPLES) return false;

    float span[3];
    for (uint8_t i = 0; i < 3; i++) span[i] = hi_[i] - lo_[i];

    // Both horizontal axes must have swept a real arc. One axis alone means the
    // board was rocked, not turned, and the offset from that is nonsense.
    if (span[0] < COMPASS_CAL_MIN_SPAN_UT || span[1] < COMPASS_CAL_MIN_SPAN_UT) return false;

    for (uint8_t i = 0; i < 3; i++) offset[i] = (hi_[i] + lo_[i]) * 0.5f;

    // Soft iron, the cheap version: scale each axis so both horizontals sweep
    // the same amount. A clean sensor traces a sphere; steel squashes it into an
    // ellipsoid, and matching the radii un-squashes it along the axes. Z is
    // excluded from the average because a turning car sweeps X and Y through a
    // full circle and Z hardly at all.
    const float mean = (span[0] + span[1]) * 0.5f;
    for (uint8_t i = 0; i < 3; i++) {
      gain[i] = (span[i] > 1.0f) ? (mean / span[i]) : 1.0f;
      if (gain[i] < 0.25f) gain[i] = 0.25f;
      if (gain[i] > 4.0f)  gain[i] = 4.0f;
    }
    gain[2] = 1.0f;

    calOn_ = false;
    haveCal_ = true;
    return true;
  }

  void calAbort() { calOn_ = false; }

  void forget() {
    for (uint8_t i = 0; i < 3; i++) { offset[i] = 0; gain[i] = 1; }
    northOffsetDeg = 0;
    haveCal_ = false;
    calOn_ = false;
  }

  /**
   * "The car is pointing this way." Stores the difference as the offset.
   *
   * The whole of mounting alignment, by hand, in one command. Without an
   * accelerometer the board cannot discover how it is bolted in, but one stored
   * offset handles any rotation about the vertical -- the only one that matters
   * for a sensor mounted flat.
   */
  bool setNorth(float trueHeadingDeg) {
    if (isnan(headingDeg)) return false;
    float raw = headingDeg - northOffsetDeg;       // undo what is already there
    float off = trueHeadingDeg - raw;
    while (off < 0)       off += 360.0f;
    while (off >= 360.0f) off -= 360.0f;
    northOffsetDeg = off;
    return true;
  }

  /** Is the field a plausible one to navigate by? */
  bool healthy() const {
    return fieldUt >= COMPASS_MIN_FIELD_UT && fieldUt <= COMPASS_MAX_FIELD_UT;
  }

  // ---- flash ---------------------------------------------------------------
  // 31 bytes: magic, a flag, seven floats, checksum. 2 + 28 + 1.
  static const uint8_t STORE_BYTES = 31;
  static const uint8_t STORE_MAGIC = 0xC3;

  void pack(uint8_t* b) const {
    b[0] = STORE_MAGIC;
    b[1] = haveCal_ ? 1 : 0;
    uint8_t* p = b + 2;
    for (uint8_t i = 0; i < 3; i++) { memcpy(p, &offset[i], 4); p += 4; }
    for (uint8_t i = 0; i < 3; i++) { memcpy(p, &gain[i],   4); p += 4; }
    memcpy(p, &northOffsetDeg, 4);
    uint8_t cs = 0;
    for (uint8_t i = 0; i < STORE_BYTES - 1; i++) cs = (uint8_t)(cs + b[i]);
    b[STORE_BYTES - 1] = cs;
  }

  bool unpack(const uint8_t* b) {
    if (b[0] != STORE_MAGIC) return false;
    uint8_t cs = 0;
    for (uint8_t i = 0; i < STORE_BYTES - 1; i++) cs = (uint8_t)(cs + b[i]);
    if (cs != b[STORE_BYTES - 1]) return false;

    float o[3], g[3], n;
    const uint8_t* p = b + 2;
    for (uint8_t i = 0; i < 3; i++) { memcpy(&o[i], p, 4); p += 4; }
    for (uint8_t i = 0; i < 3; i++) { memcpy(&g[i], p, 4); p += 4; }
    memcpy(&n, p, 4);

    // Believe it only if it is believable. Erased flash reads 0xFF, which
    // decodes to NaN, and NaN propagates silently all the way to an arrow that
    // points nowhere and never recovers.
    for (uint8_t i = 0; i < 3; i++) {
      if (isnan(o[i]) || fabsf(o[i]) > 2000.0f) return false;
      if (isnan(g[i]) || g[i] < 0.2f || g[i] > 5.0f) return false;
    }
    if (isnan(n) || n < -360.0f || n > 360.0f) return false;

    for (uint8_t i = 0; i < 3; i++) { offset[i] = o[i]; gain[i] = g[i]; }
    northOffsetDeg = n;
    haveCal_ = (b[1] != 0);
    return true;
  }

 private:
  bool     present_ = false;
  uint32_t lastDrdyMs_ = 0;      // the last time the chip said it had a sample
  bool     calOn_   = false;
  bool     haveCal_ = false;
  uint16_t calN_    = 0;
  float    lo_[3]   = { 1e9f, 1e9f, 1e9f };
  float    hi_[3]   = { -1e9f, -1e9f, -1e9f };

  /** Microtesla per count, from the range the chip REPORTS. */
  float utPerLsb_ = 100.0f / 1000.0f;

  /**
   * Chip axes to car axes: X forward, Y left, Z up. See MAG_AXIS_ORDER and
   * MAG_AXIS_SIGN in hud_config.h -- this is the one thing that cannot be
   * discovered without an accelerometer and has to be told.
   */
  static void remap_(float cx, float cy, float cz, float* out) {
    const float c[3] = { cx, cy, cz };
    static const uint8_t order[3] = { MAG_AXIS_ORDER };
    static const int8_t  sign[3]  = { MAG_AXIS_SIGN };
    for (uint8_t i = 0; i < 3; i++) out[i] = c[order[i]] * (float)sign[i];
  }

  void feed_(const float* v) {
    // Every sample counts toward calN_, because that is what the word means to
    // whoever reads "72 samples of 120 needed". Counting only bound-extending
    // samples -- which an earlier version did -- means a full circle scores
    // about a quarter of what the gate asks for and nothing is ever accepted.
    // The step threshold still guards the BOUNDS so noise cannot creep them out.
    for (uint8_t i = 0; i < 3; i++) {
      if (v[i] < lo_[i] - COMPASS_CAL_STEP_UT) lo_[i] = v[i];
      if (v[i] > hi_[i] + COMPASS_CAL_STEP_UT) hi_[i] = v[i];
    }
    if (calN_ < 65000) calN_++;
  }
};

// hud_store.h reserves a fixed number of flash bytes for this, by hand, in a
// file included before this one. It was a comment saying "keep these in step"
// and nothing enforcing it -- and the last time they drifted, every saved
// calibration failed its own checksum on reload. Now the build fails instead.
// Guarded because the compass test compiles this file on its own, without the
// flash layout.
#ifdef HUD_MAG_STORE_BYTES
static_assert(HudCompass::STORE_BYTES == HUD_MAG_STORE_BYTES,
              "hud_store.h reserves a different number of flash bytes than HudCompass packs");
#endif

#endif  // HUD_COMPASS_H
