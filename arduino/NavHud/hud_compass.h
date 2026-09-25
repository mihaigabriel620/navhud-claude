// ---------------------------------------------------------------------------
//  hud_compass.h -- the QMC5883P magnetometer, driven directly.
//
//  This file finds the chip, starts it, and hands over one field sample at a
//  time in microtesla, in the box's frame. What the samples mean -- heading,
//  tilt, calibration -- is hud_heading.h.
//
//  DRIVEN THE WAY 2.9 DROVE IT: plain register reads and writes through
//  hud_i2c.h, the same bytes in the same order, which the owner's part is
//  known to work with. 3.0 first went through Adafruit's QMC5883P library and
//  the part was reported "not found" on the bench, twice; the owner asked for
//  the old method back.
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
//  Starting it and then reconfiguring it while it runs left the bench part
//  present, addressable, configured, and never measuring.
//
//  THE SCALE IS READ BACK, NEVER ASSUMED. On the bench part, 0BH reads 0x00
//  after being written 0x08: the range write does not stick and it sits at
//  +-30 G, 1000 LSB per gauss. Scaling for the 8 G it was ASKED for gave 13.5 uT
//  where Belgium is 49; scaling for the 30 G it REPORTS gives 50.7. Ask, then
//  believe the answer. 0AH is only reported, never checked: 3.0's first build
//  configured the chip through the library and then checked 0AH, and wrote
//  off a working chip.
// ---------------------------------------------------------------------------
#ifndef HUD_COMPASS_H
#define HUD_COMPASS_H

#include <stdint.h>
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
// example is 0xC3, the same but at 10 Hz.
#define QMCP_CTRL1_RUN  0xCB
// Set/Reset On, range 8 G. Requested; not necessarily granted -- see above.
// Set-and-reset on is the setting that matters: 9.2.3 says "in SET ONLY ON or
// SET AND RESET OFF mode, the offset is not renewed during measuring", so these
// bits at 00 are what buy the per-measurement degaussing.
#define QMCP_CTRL2_RUN  0x08

/**
 * No new sample for this long and the chip is not measuring: it browned out
 * (it comes back in suspend mode) or it left the bus. It runs at 100 Hz, so
 * this is a hundred missed samples. read() then stops calling it present, and
 * hud_sensors.h brings it up again.
 */
#define COMPASS_SILENT_MS 1000

class HudCompass {
 public:
  /** Full scale the chip REPORTS, in gauss. 0 before begin() succeeds. */
  uint8_t rangeG = 0;
  /** CTRL1/CTRL2 as read back after configuration, for `status`. */
  uint8_t ctrl1 = 0, ctrl2 = 0;

  bool present() const { return present_; }

  const char* describe() const {
    return present_ ? "QMC5883P at 0x2C" : "no QMC5883P found at 0x2C";
  }

  /**
   * Find the chip and start it, in QST's order. `reset` soft-resets it first
   * and gives it the waits 2.9 gave it -- setup() only, because they block. A
   * chip found again on a retry has just come back from a brown-out, which is
   * a reset.
   */
  bool begin(bool reset) {
    present_ = false;
    if (!i2cPresent(QMCP_ADDR)) return false;

    uint8_t id = 0;
    if (!i2cRead(QMCP_ADDR, QMCP_REG_ID, &id, 1) || id != QMCP_ID_VALUE) return false;

    if (reset) {
      i2cWrite8(QMCP_ADDR, QMCP_REG_CTRL2, 0x80);         // 7.6 soft reset
      delay(50);                                          // no figure given; generous
    }
    i2cWrite8(QMCP_ADDR, QMCP_REG_SIGN,  0x06);           // sign for X Y Z
    i2cWrite8(QMCP_ADDR, QMCP_REG_CTRL2, QMCP_CTRL2_RUN); // Set/Reset On, 8 G
    i2cWrite8(QMCP_ADDR, QMCP_REG_CTRL1, QMCP_CTRL1_RUN); // continuous <- starts it
    if (reset) delay(20);

    i2cRead(QMCP_ADDR, QMCP_REG_CTRL1, &ctrl1, 1);
    i2cRead(QMCP_ADDR, QMCP_REG_CTRL2, &ctrl2, 1);
    static const float   lsbPerG[4] = { 1000.0f, 2500.0f, 3750.0f, 15000.0f };
    static const uint8_t rangeOf[4] = { 30, 12, 8, 2 };
    const uint8_t rng = (uint8_t)((ctrl2 >> 2) & 0x03);
    utPerLsb_ = 100.0f / lsbPerG[rng];
    rangeG    = rangeOf[rng];

    present_ = true;
    lastDrdyMs_ = millis();
    return true;
  }

  /** One sample, microtesla, box frame. True when a new one was read. */
  bool read(float* m) {
    if (!present_) return false;

    // Status FIRST and on its own. On the P, DRDY is cleared by reading the
    // STATUS register, not the data -- fold them into one burst and the flag is
    // consumed before it is tested.
    uint8_t st = 0;
    const bool ok = i2cRead(QMCP_ADDR, QMCP_REG_STATUS, &st, 1);
    if (!ok || !(st & 0x01)) {                            // nothing new
      // A second of nothing is not a quiet chip: it browned out and came back
      // in suspend, or it is gone.
      if ((uint32_t)(millis() - lastDrdyMs_) > COMPASS_SILENT_MS) present_ = false;
      return false;
    }
    lastDrdyMs_ = millis();                               // measuring, even on overflow
    if (st & 0x02) return false;                          // overflow: a magnet, not a field

    uint8_t d[6];
    if (!i2cRead(QMCP_ADDR, QMCP_REG_DATA, d, 6)) return false;
    const int16_t rx = (int16_t)((uint16_t)d[1] << 8 | d[0]);
    const int16_t ry = (int16_t)((uint16_t)d[3] << 8 | d[2]);
    const int16_t rz = (int16_t)((uint16_t)d[5] << 8 | d[4]);
    remap_(rx * utPerLsb_, ry * utPerLsb_, rz * utPerLsb_, m);
    return true;
  }

  /** Is anything answering at 0x2C, and what does it say its id is? For `status`. */
  bool probe(uint8_t& id) {
    id = 0;
    if (!i2cPresent(QMCP_ADDR)) return false;
    i2cRead(QMCP_ADDR, QMCP_REG_ID, &id, 1);
    return true;
  }

 private:
  bool     present_ = false;
  uint32_t lastDrdyMs_ = 0;       // the last time the chip said it had a sample
  float    utPerLsb_ = 0.1f;      // microtesla per count, from the range REPORTED

  /**
   * Chip axes to box axes: X forward, Y left, Z up. See MAG_AXIS_ORDER and
   * MAG_AXIS_SIGN in hud_config.h.
   */
  static void remap_(float cx, float cy, float cz, float* out) {
    const float c[3] = { cx, cy, cz };
    static const uint8_t order[3] = { MAG_AXIS_ORDER };
    static const int8_t  sign[3]  = { MAG_AXIS_SIGN };
    for (uint8_t i = 0; i < 3; i++) out[i] = c[order[i]] * (float)sign[i];
  }
};

#endif  // HUD_COMPASS_H
