#pragma once
// Host stand-in for Arduino Wire: one I2C bus carrying the two chips the
// firmware talks to, both mounted in one simulated box.
//
//   0x2C  QMC5883P compass       (register map: QST 13-52-19, Table 14)
//   0x68  MPU-6050 motion sensor (register map: InvenSense RM-MPU-6000A)
//
// The box has an orientation in the world -- heading, pitch, roll -- a turn
// rate, and optionally a linear acceleration (braking). Each chip reports what
// it would measure in that orientation, in its own axes and at the full-scale
// range its registers ask for: the Earth's field (central Europe, about 20 uT
// north and 44 uT down) and gravity. Tests move the box and check what the
// firmware makes of it.
//
// FRAMES. World: x east, y north, z up. Box: X forward, Y left, Z up -- the
// car's frame. The compass measures in it (the owner's QMC reads the car's way
// round). The MPU is mounted like the owner's: on the back of the screen PCB,
// components facing the dash, X arrow forward -- upside down, so it reads the
// box's Y and Z negated, which hud_config.h's MPU_AXIS_SIGN undoes. An
// accelerometer reads the reaction to gravity, so a level box reads +1 g up. A
// gyro reads rotation about its own axes, right-hand rule: turning right
// (clockwise seen from above) is a NEGATIVE rate about up.
#include <stdint.h>
#include <stddef.h>
#include <cmath>
#include <deque>

struct SimBox {
  // The Earth's field, microtesla. Central Europe: ~20 north, ~44 down.
  double fieldNorthUt = 20.0, fieldDownUt = 44.0;
  // Orientation. Heading clockwise from magnetic north; pitch nose up; roll
  // right side down.
  double headingDeg = 0, pitchDeg = 0, rollDeg = 0;
  // Rotation rates in the box's own axes, deg/s, right-hand rule.
  double rateXDps = 0, rateYDps = 0, rateZDps = 0;
  // Acceleration the box is undergoing, g, in its own axes (braking is -X).
  double linX = 0, linY = 0, linZ = 0;

  /** A world vector in box axes. */
  void toBox(const double w[3], double b[3]) const {
    const double k = M_PI / 180.0;
    const double h = headingDeg * k, p = pitchDeg * k, r = rollDeg * k;
    // The box's axes in the world, forward then left then up, before roll...
    const double fx = sin(h) * cos(p), fy = cos(h) * cos(p), fz = sin(p);
    const double lx = -cos(h), ly = sin(h), lz = 0;
    const double ux = -sin(p) * sin(h), uy = -sin(p) * cos(h), uz = cos(p);
    // ...then rolled about forward: right side down lifts the left.
    const double Lx = lx * cos(r) + ux * sin(r), Ly = ly * cos(r) + uy * sin(r),
                 Lz = lz * cos(r) + uz * sin(r);
    const double Ux = ux * cos(r) - lx * sin(r), Uy = uy * cos(r) - ly * sin(r),
                 Uz = uz * cos(r) - lz * sin(r);
    b[0] = w[0] * fx + w[1] * fy + w[2] * fz;
    b[1] = w[0] * Lx + w[1] * Ly + w[2] * Lz;
    b[2] = w[0] * Ux + w[1] * Uy + w[2] * Uz;
  }
  void fieldUt(double b[3]) const {
    const double w[3] = { 0, fieldNorthUt, -fieldDownUt };
    toBox(w, b);
  }
  void accelG(double b[3]) const {
    const double w[3] = { 0, 0, 1.0 };
    toBox(w, b);
    b[0] += linX; b[1] += linY; b[2] += linZ;
  }
  /** Turn on the spot, clockwise seen from above, for `dtS` seconds. */
  void turn(double clockwiseDps, double dtS) {
    headingDeg = fmod(headingDeg + clockwiseDps * dtS + 360.0, 360.0);
    rateXDps = 0; rateYDps = 0; rateZDps = -clockwiseDps;
  }
};

class TwoWire {
 public:
  // ---- the simulated world and the chips' own quirks ----
  SimBox box;
  bool qmcPresent = true;           // false: nothing ACKs at 0x2C
  /** The bench part ignores the range write and reads back 0 (+-30 G). */
  bool qmcRangeSticks = true;
  /**
   * False: CTRL1 reads back 0x00 whatever was written, while the chip runs on
   * what was written. The owner's part answers 0x80 and then fails a CTRL1
   * read-back check, and measured fine under 2.9, which never read it.
   */
  bool qmcCtrl1ReadBack = true;
  bool mpuPresent = true;           // false: nothing ACKs at 0x68
  uint8_t mpuWhoAmI = 0x68;         // 0x70 for an MPU-6500, 0x98 for some clones
  /** What an uncalibrated MPU adds, in its own axes: accel offsets (g), gyro bias (deg/s). */
  double mpuAccelErr[3] = { 0, 0, 0 };
  double mpuGyroBias[3] = { 0, 0, 0 };
  /** Transactions each chip has seen, so a test can see reads happening. */
  uint32_t qmcReads = 0, mpuReads = 0;

  /** The compass loses power and comes back: every register at reset. */
  void brownOut() { for (int i = 0; i < 256; i++) qmc_[i] = 0; }
  /** The MPU loses power: reset values, asleep. */
  void mpuBrownOut() { for (int i = 0; i < 256; i++) mpu_[i] = 0; mpu_[0x6B] = 0x40; }

  // ---- the Wire API the firmware and its libraries use ----
  void begin() {}
  void begin(int, int) {}
  void end() {}
  void setClock(uint32_t) {}
  void beginTransmission(uint8_t a) { addr_ = a; txn_ = 0; }
  void beginTransmission(int a) { beginTransmission((uint8_t)a); }
  size_t write(uint8_t b) {
    if (txn_ == 0) reg_ = b;
    else writeReg_((uint8_t)(reg_ + txn_ - 1), b);
    txn_++;
    return 1;
  }
  size_t write(const uint8_t* b, size_t n) {
    for (size_t i = 0; i < n; i++) write(b[i]);
    return n;
  }
  uint8_t endTransmission(bool = true) { return acks_(addr_) ? 0 : 2; }
  uint8_t requestFrom(int a, int n, int = 1) {
    rx_.clear();
    if (!acks_((uint8_t)a)) return 0;
    if ((uint8_t)a == 0x2C) qmcReads++; else mpuReads++;
    for (int i = 0; i < n; i++) rx_.push_back(readReg_((uint8_t)a, (uint8_t)(reg_ + i)));
    return (uint8_t)n;
  }
  int available() { return (int)rx_.size(); }
  int read() { if (rx_.empty()) return 0; int v = rx_.front(); rx_.pop_front(); return v; }

  TwoWire() { mpuBrownOut(); }

 private:
  bool acks_(uint8_t a) const {
    return (a == 0x2C && qmcPresent) || (a == 0x68 && mpuPresent);
  }

  void writeReg_(uint8_t r, uint8_t v) {
    if (addr_ == 0x2C) {
      // CTRL2 bit 7 is SOFT_RST: every register back to its reset value, the
      // chip in suspend, and the bit clears itself.
      if (r == 0x0B && (v & 0x80)) { brownOut(); return; }
      qmc_[r] = v;
    } else if (addr_ == 0x68) {
      if (r == 0x6B && (v & 0x80)) { mpuBrownOut(); return; }   // DEVICE_RESET
      mpu_[r] = v;
    }
  }

  static uint8_t lo_(int v) { return (uint8_t)(v & 0xFF); }
  static uint8_t hi_(int v) { return (uint8_t)((v >> 8) & 0xFF); }
  static int clip_(double v) {
    if (v > 32767) return 32767;
    if (v < -32768) return -32768;
    return (int)lround(v);
  }

  uint8_t readReg_(uint8_t a, uint8_t r) const {
    if (a == 0x2C) {
      // Range: CTRL2 bits 3:2 -> 30, 12, 8, 2 G. The bench part's range write
      // does not stick, so it reads back 0 and measures at 30 G.
      const uint8_t ctrl2 = qmcRangeSticks ? qmc_[0x0B] : (uint8_t)(qmc_[0x0B] & ~0x0C);
      static const double lsbPerG[4] = { 1000, 2500, 3750, 15000 };
      const double k = lsbPerG[(ctrl2 >> 2) & 3] / 100.0;     // counts per uT
      double f[3]; box.fieldUt(f);
      const int x = clip_(f[0] * k), y = clip_(f[1] * k), z = clip_(f[2] * k);
      switch (r) {
        case 0x00: return 0x80;                              // chip id
        case 0x01: return lo_(x); case 0x02: return hi_(x);
        case 0x03: return lo_(y); case 0x04: return hi_(y);
        case 0x05: return lo_(z); case 0x06: return hi_(z);
        // DRDY only in continuous (or normal) mode: after power-on, a brown-out
        // or a soft reset the chip is in suspend until it is told to measure.
        case 0x09: return (qmc_[0x0A] & 0x03) ? 0x01 : 0x00;
        case 0x0A: return qmcCtrl1ReadBack ? qmc_[0x0A] : 0x00;
        case 0x0B: return ctrl2;
        default:   return qmc_[r];
      }
    }
    // MPU-6050. Asleep (PWR_MGMT_1 bit 6) it measures nothing.
    if (r == 0x75) return mpuWhoAmI;
    const bool awake = !(mpu_[0x6B] & 0x40);
    if (r >= 0x3B && r <= 0x48) {
      if (!awake) return 0;
      const double lsbPerG = 16384.0 / (1 << ((mpu_[0x1C] >> 3) & 3));
      const double lsbPerDps = 131.0 / (1 << ((mpu_[0x1B] >> 3) & 3));
      double acc[3]; box.accelG(acc);
      const double rate[3] = { box.rateXDps, box.rateYDps, box.rateZDps };
      static const int mount[3] = { 1, -1, -1 };             // upside down, X forward
      int v[7];
      for (int i = 0; i < 3; i++) v[i] = clip_((acc[i] * mount[i] + mpuAccelErr[i]) * lsbPerG);
      v[3] = clip_((25.0 - 36.53) * 340.0);                  // 25 C
      for (int i = 0; i < 3; i++) v[4 + i] = clip_((rate[i] * mount[i] + mpuGyroBias[i]) * lsbPerDps);
      const int k = r - 0x3B;                                // big-endian pairs
      return (k & 1) ? lo_(v[k / 2]) : hi_(v[k / 2]);
    }
    return mpu_[r];
  }

  uint8_t addr_ = 0, reg_ = 0; int txn_ = 0;
  uint8_t qmc_[256] = {0};
  uint8_t mpu_[256] = {0};
  std::deque<uint8_t> rx_;
};
extern TwoWire Wire;
