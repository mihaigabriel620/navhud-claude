// ---------------------------------------------------------------------------
//  hud_compass.h -- the QMC5883P magnetometer, through Adafruit's library.
//
//  This file finds the chip, starts it, and hands over one field sample at a
//  time in microtesla, in the box's frame. What the samples mean -- heading,
//  tilt, calibration -- is hud_heading.h.
//
//  THE ORDER OF THE WRITES is the part that cost days. QST doc 13-52-19,
//  section 7.2, Continuous Mode Setup Example:
//
//      Write Register 29H by 0x06   (sign for X Y and Z axis)
//      Write Register 0BH by 0x08   (Set/Reset On, Field Range 8 Gauss)
//      Write Register 0AH by 0xC3   (set continuous mode)
//
//  0BH first, 0AH LAST, because writing the mode into 0AH is what STARTS the
//  chip -- suspend is the default after power-on and after a soft reset
//  (6.2.4). Starting it and then reconfiguring it while it runs left the bench
//  part present, addressable, configured, and never measuring. 0AH is written
//  0xCB: QST's example, but at 100 Hz rather than 10.
//
//  WHOLE BYTES, NOT THE LIBRARY'S SETTERS. Each setter reads its register,
//  changes its own bits and writes it back, and the owner's part does not read
//  its control registers back as written (0BH, below; and in 3.0, 0AH). 3.0
//  configured it through the setters and then required 0AH to read back
//  "continuous": the chip answered 0x80 at 0x2C and was reported "not found".
//  2.9 wrote the three bytes and never read 0AH, and measured fine. So the
//  bytes go down whole, through the library's bus layer, and the library does
//  the rest: finding the chip and reading the field.
//
//  THE SCALE IS READ BACK, NEVER ASSUMED. On the bench part, 0BH reads 0x00
//  after being written 0x08: the range write does not stick and it sits at
//  +-30 G, 1000 LSB per gauss. Scaling for the 8 G it was ASKED for gave 13.5 uT
//  where Belgium is 49; scaling for the 30 G it REPORTS gives 50.7. Ask, then
//  believe the answer.
// ---------------------------------------------------------------------------
#ifndef HUD_COMPASS_H
#define HUD_COMPASS_H

#include <Adafruit_QMC5883P.h>
#include "hud_config.h"

#define QMCP_ADDR       0x2C
#define QMCP_REG_SIGN   0x29      // undocumented; appears only in section 7
#define QMCP_REG_STATUS 0x09      // bit0 DRDY, bit1 OVFL
#define QMCP_REG_CTRL1  0x0A      // OSR2[7:6] OSR1[5:4] ODR[3:2] MODE[1:0]
#define QMCP_REG_CTRL2  0x0B      // SOFT_RST[7] SELF_TEST[6] rfu RNG[3:2] SETRESET[1:0]

// 0xCB = OSR2 11, OSR1 00, ODR 10 (100 Hz), MODE 11 (continuous). QST's literal
// example is 0xC3, the same but at 10 Hz.
#define QMCP_CTRL1_RUN  0xCB
// Set/reset on, range 8 G. Requested; not necessarily granted -- see above.
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
   * and waits 50 ms -- setup() only. A chip found again on a retry has just
   * come back from a brown-out, which is a reset.
   */
  bool begin(bool reset) {
    present_ = false;
    if (!chip_.begin(QMCP_ADDR, &Wire)) return false;    // ACK, and chip id 0x80
    if (reset) {
      reg_(QMCP_REG_CTRL2).write(0x80);                   // 7.6 soft reset
      delay(50);                                          // no figure given; generous
    }
    // Set/reset on (CTRL2 bits 1:0 = 00) is the setting that matters: 9.2.3
    // says "in SET ONLY ON or SET AND RESET OFF mode, the offset is not renewed
    // during measuring", so these bits at 00 buy the per-measurement degaussing.
    if (!reg_(QMCP_REG_SIGN).write(0x06)) return false;              // sign for X Y Z
    if (!reg_(QMCP_REG_CTRL2).write(QMCP_CTRL2_RUN)) return false;   // set/reset on, 8 G
    if (!reg_(QMCP_REG_CTRL1).write(QMCP_CTRL1_RUN)) return false;   // continuous <- starts it

    // For `status`, and the range: read back, never assumed. CTRL1 is only
    // reported -- see above for why it cannot be a test.
    reg_(QMCP_REG_CTRL1).read(&ctrl1);
    if (!reg_(QMCP_REG_CTRL2).read(&ctrl2)) return false;
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
    if (!reg_(QMCP_REG_STATUS).read(&st) || !(st & 0x01)) {
      // A second of nothing is not a quiet chip: it browned out and came back
      // in suspend, or it is gone.
      if ((uint32_t)(millis() - lastDrdyMs_) > COMPASS_SILENT_MS) present_ = false;
      return false;
    }
    lastDrdyMs_ = millis();                               // measuring, even on overflow
    if (st & 0x02) return false;                          // overflow: a magnet, not a field

    int16_t x, y, z;
    if (!chip_.getRawMagnetic(&x, &y, &z)) return false;
    remap_(x * utPerLsb_, y * utPerLsb_, z * utPerLsb_, m);
    return true;
  }

  /** Is anything answering at 0x2C, and what does it say its id is? For `status`. */
  bool probe(uint8_t& id) {
    id = 0;
    if (!dev_.detected()) return false;
    reg_(0x00).read(&id);
    return true;
  }

 private:
  Adafruit_QMC5883P chip_;
  // The registers the library does not reach -- 29H, the status byte in one
  // read, the read-backs -- through the library's own bus layer.
  Adafruit_I2CDevice dev_{ QMCP_ADDR, &Wire };
  bool     present_ = false;
  uint32_t lastDrdyMs_ = 0;       // the last time the chip said it had a sample
  float    utPerLsb_ = 0.1f;      // microtesla per count, from the range REPORTED

  Adafruit_BusIO_Register reg_(uint8_t r) { return Adafruit_BusIO_Register(&dev_, r); }

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
