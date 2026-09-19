// ---------------------------------------------------------------------------
//  hud_qmc.h -- QST QMC5883 magnetometer, both the P and the L.
//
//  They share a name and almost nothing else. Different I2C address, different
//  chip-ID register, the data registers start one byte apart, the status
//  register is somewhere else, the two control registers swap places, and the
//  full-scale range lives in a different register on a different pair of bits.
//  A QMC5883L driver pointed at a QMC5883P finds nothing at all, because it
//  knocks on 0x0D and the P is at 0x2C.
//
//  So the chip is DETECTED, not configured. You asked for a switch in case the
//  wrong one turned up in the box, and there is one -- but the default probes
//  for both, because the boxes are the problem: GY-271 boards silkscreened
//  HMC5883L have been shipping QMC5883Ls for years, and the current wave of
//  them ships QMC5883Ps marked "HP5883". Detecting costs two I2C transactions
//  at boot and removes the whole question.
//
//  Everything below is from the QST datasheets:
//    QMC5883P Rev C, doc 13-52-19
//    QMC5883L Rev A/B, doc 13-52-04  (the two revisions are identical in the
//                                     register map, ID, sensitivity and init)
//
//  WHAT IS DELIBERATELY NOT COPIED FROM THE POPULAR DRIVERS
//
//  Several widely used implementations have bugs that a reader would otherwise
//  assume were deliberate, so they are named here rather than silently avoided:
//  ArduPilot's QMC5883P init writes 0x29 into register 0x06 with the arguments
//  the wrong way round; its range defines read 0x10 and 0x11 as if they were
//  binary, producing 0x40 which collides with the self-test bit; its P scale
//  factor is the L's (3000 rather than 3750 LSB/G, a 25% error); and its L
//  data-ready test reads bit 2, which is "data skipped", not bit 0.
// ---------------------------------------------------------------------------
#ifndef HUD_QMC_H
#define HUD_QMC_H

#include <Wire.h>
#include <math.h>
#include <stdint.h>

enum QmcVariant : uint8_t { QMC_NONE = 0, QMC_P, QMC_L };

// ---- QMC5883P -------------------------------------------------------------
#define QMCP_ADDR        0x2C
#define QMCP_REG_ID      0x00     // reads 0x80
#define QMCP_ID_VALUE    0x80
#define QMCP_REG_DATA    0x01     // X LSB, X MSB, Y LSB, Y MSB, Z LSB, Z MSB
#define QMCP_REG_STATUS  0x09     // bit0 DRDY (clears on reading THIS), bit1 OVFL
#define QMCP_REG_CTRL1   0x0A     // OSR2[7:6] OSR1[5:4] ODR[3:2] MODE[1:0]
#define QMCP_REG_CTRL2   0x0B     // RST[7] SELFTEST[6] RNG[3:2] SETRESET[1:0]
#define QMCP_REG_SIGN    0x29     // undocumented; see qmcSignWrite below

// CTRL1 = 0xCB: OSR2 downsample 8 (11), OSR1 8 (00), ODR 100 Hz (10),
// continuous (11). QST's own example uses 0xC3, which is the same but at the
// 10 Hz ODR -- too slow to feed a calibration from.
#define QMCP_CTRL1_RUN   0xCB
// CTRL2 = 0x08: no reset, no self-test, range 8 G (10), set+reset ON (00).
//
// Set-and-reset on is the setting that matters and it is the default for a
// reason: 9.2.3 says "in SET ONLY ON or SET AND RESET OFF mode, the offset is
// not renewed during measuring". Leaving it at 00 is what buys the
// per-measurement degaussing that keeps the chip's own offset near zero.
//
// 8 G rather than the more precise 2 G because this sits in a steel car: 2 G
// is only twice the Earth's total field, and a speaker magnet or a door loom
// can eat that headroom. At 8 G one count is 0.027 uT, which is far below
// anything that matters for a heading.
#define QMCP_CTRL2_RUN   0x08
#define QMCP_UT_PER_LSB  (100.0f / 3750.0f)     // 3750 LSB/G at 8 G, 1 G = 100 uT

// ---- QMC5883L -------------------------------------------------------------
#define QMCL_ADDR        0x0D
#define QMCL_REG_DATA    0x00     // X LSB .. Z MSB
#define QMCL_REG_STATUS  0x06     // bit0 DRDY, bit1 OVL, bit2 DOR
#define QMCL_REG_CTRL1   0x09     // OSR[7:6] RNG[5:4] ODR[3:2] MODE[1:0]
#define QMCL_REG_CTRL2   0x0A     // RST[7] ROL_PNT[6] ... INT_ENB[0]
#define QMCL_REG_SRPER   0x0B     // SET/RESET period, must be written 0x01
#define QMCL_REG_ID      0x0D     // reads 0xFF
#define QMCL_ID_VALUE    0xFF

// CTRL1 = 0x19: OSR 512 (00), range 8 G (01), ODR 100 Hz (10), continuous (01).
#define QMCL_CTRL1_RUN   0x19
#define QMCL_UT_PER_LSB  (100.0f / 3000.0f)     // 3000 LSB/G at 8 G

/**
 * The undocumented write in QST's own QMC5883P example.
 *
 * "Write Register 29H by 0x06 (Define the sign for X Y and Z axis)" appears
 * three times in the datasheet's application section and register 0x29 appears
 * nowhere in the register map. Adafruit's driver never writes it and works;
 * ArduPilot and Betaflight do write it. It is on by default here because it is
 * the manufacturer's own sequence, and behind a define because it is
 * undocumented and somebody may one day need it off.
 */
#ifndef QMC_WRITE_SIGN_REG
  #define QMC_WRITE_SIGN_REG 1
#endif

class HudQmc {
 public:
  QmcVariant variant = QMC_NONE;
  uint8_t    addr    = 0;

  /** Last reading in microtesla, in the magnetometer's OWN axes. */
  float x = 0, y = 0, z = 0;

  bool present() const { return variant != QMC_NONE; }

  /**
   * Find and start whichever chip is there.
   *
   * Wire.begin() is the caller's job. hud_imu.h has already done it by the
   * time this runs, and calling it twice with different pins is how you lose
   * an afternoon.
   */
  bool begin() {
    variant = QMC_NONE; addr = 0;
#if defined(HUD_MAG_FORCE_QMC5883L)
    if (startL()) return true;
#elif defined(HUD_MAG_FORCE_QMC5883P)
    if (startP()) return true;
#else
    // P first. Its identity test is the stronger of the two -- a specific
    // value in a dedicated register -- so trying it first means a board that
    // is a P can never be mistaken for anything else.
    if (startP()) return true;
    if (startL()) return true;
#endif
    return false;
  }

  /** One sample. False when there is nothing new, or the field overflowed. */
  bool read() {
    if (variant == QMC_P) {
      uint8_t st;
      // Status FIRST and on its own: on the P, DRDY is cleared by reading the
      // status register, not by reading the data. Read them in one burst and
      // the flag would be consumed before it was tested.
      if (!rd(QMCP_REG_STATUS, &st, 1)) return false;
      if (!(st & 0x01)) return false;                 // nothing new yet
      if (st & 0x02) return false;                    // OVFL: past +-30000 LSB
      uint8_t b[6];
      if (!rd(QMCP_REG_DATA, b, 6)) return false;
      return unpackLE(b, QMCP_UT_PER_LSB);
    }
    if (variant == QMC_L) {
      uint8_t st;
      if (!rd(QMCL_REG_STATUS, &st, 1)) return false;
      if (!(st & 0x01)) return false;                 // DRDY
      if (st & 0x02) return false;                    // OVL
      uint8_t b[6];
      // All six, through 0x05, and that is not negotiable. 6.2.1.4: "if any of
      // the six data register is accessed, data protection starts. During Data
      // protection period, data register cannot be updated until the last bits
      // 05H (ZOUT[15:8]) have been read." Stop short and the chip stops
      // updating for good, which looks exactly like a dead sensor.
      if (!rd(QMCL_REG_DATA, b, 6)) return false;
      return unpackLE(b, QMCL_UT_PER_LSB);
    }
    return false;
  }

  const char* describe() const {
    switch (variant) {
      case QMC_P: return "QMC5883P at 0x2C";
      case QMC_L: return "QMC5883L at 0x0D";
      default:    return "no QMC5883 found at 0x2C or 0x0D";
    }
  }

 private:
  bool startP() {
    uint8_t id = 0;
    addr = QMCP_ADDR;
    if (!rd(QMCP_REG_ID, &id, 1) || id != QMCP_ID_VALUE) { addr = 0; return false; }
    wr(QMCP_REG_CTRL2, 0x80);          // soft reset
    // The datasheet gives no settling time after a soft reset, for either
    // part. 50 ms is Adafruit's number for the P and is generous against the
    // 250 us the datasheet DOES specify for power-on.
    delay(50);
#if QMC_WRITE_SIGN_REG
    wr(QMCP_REG_SIGN, 0x06);
#endif
    if (!wr(QMCP_REG_CTRL2, QMCP_CTRL2_RUN)) { addr = 0; return false; }
    if (!wr(QMCP_REG_CTRL1, QMCP_CTRL1_RUN)) { addr = 0; return false; }
    variant = QMC_P;
    delay(20);
    return true;
  }

  bool startL() {
    addr = QMCL_ADDR;
    uint8_t id = 0;
    // A dummy read first. ArduPilot's driver does this with the comment
    // "Affected by other devices, must read registers 0x00 once or reset,
    // after can read the ID registers reliably" -- undocumented but
    // field-proven across a lot of hardware.
    uint8_t junk[1];
    rd(0x00, junk, 1);
    if (!rd(QMCL_REG_ID, &id, 1) || id != QMCL_ID_VALUE) { addr = 0; return false; }

    // 0xFF is a terrible identity check: an absent device on a pulled-up bus
    // reads 0xFF too. So prove something is actually THERE by writing a
    // register and reading it back. 0x0B is the one to use because it has to
    // be written anyway -- 9.2.5: "It is recommended that the register 0BH is
    // written by 0x01."
    if (!wr(QMCL_REG_SRPER, 0x01)) { addr = 0; return false; }
    uint8_t back = 0;
    if (!rd(QMCL_REG_SRPER, &back, 1) || back != 0x01) { addr = 0; return false; }

    if (!wr(QMCL_REG_CTRL1, QMCL_CTRL1_RUN)) { addr = 0; return false; }
    variant = QMC_L;
    delay(20);
    return true;
  }

  /** Six bytes, low byte first on both parts, 16-bit two's complement. */
  bool unpackLE(const uint8_t* b, float utPerLsb) {
    const int16_t rx = (int16_t)((uint16_t)b[1] << 8 | b[0]);
    const int16_t ry = (int16_t)((uint16_t)b[3] << 8 | b[2]);
    const int16_t rz = (int16_t)((uint16_t)b[5] << 8 | b[4]);
    x = rx * utPerLsb;
    y = ry * utPerLsb;
    z = rz * utPerLsb;
    return true;
  }

  bool wr(uint8_t reg, uint8_t val) {
    Wire.beginTransmission(addr);
    Wire.write(reg);
    Wire.write(val);
    return Wire.endTransmission() == 0;
  }

  bool rd(uint8_t reg, uint8_t* buf, uint8_t n) {
    Wire.beginTransmission(addr);
    Wire.write(reg);
    if (Wire.endTransmission(false) != 0) return false;
    if (Wire.requestFrom((int)addr, (int)n) != n) return false;
    for (uint8_t i = 0; i < n; i++) buf[i] = Wire.read();
    return true;
  }
};

#endif  // HUD_QMC_H
