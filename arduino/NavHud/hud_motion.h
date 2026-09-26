// ---------------------------------------------------------------------------
//  hud_motion.h -- the MPU-6050 (GY-521 board), through RobTillaart's GY521.
//
//  Optional. With it, the compass knows which way is down, so tilting the box
//  -- or the car, on a hill -- no longer swings the heading, and the gyro
//  gives the app the car's turn rate as $IMU. Without it, everything works as
//  it did: a flat compass, no $IMU.
//
//  This file finds the chip, sets it up and hands over one sample at a time:
//  acceleration in g and rotation in deg/s, in the box's frame. What the
//  samples mean is hud_heading.h.
//
//  Registers from InvenSense RM-MPU-6000A-00 rev 4.2:
//
//    PWR_MGMT_1 (6Bh)  0x01  awake, clocked from the X gyro's PLL. The reset
//                            value is 0x40, asleep; the library's wakeup()
//                            writes 0x00, the internal 8 MHz oscillator, and
//                            the register's own description "highly
//                            recommends" a gyro reference for stability.
//    CONFIG     (1Ah)  0x05  digital low-pass, 10 Hz (13.4 ms delay on the
//                            gyro): far more than a car turns at, and it keeps
//                            engine vibration out of 50 Hz sampling.
//    GYRO_CONFIG  (1Bh) 0x10 +-1000 deg/s, 32.8 LSB per deg/s. A hand lifting
//                            the screen turns it faster than 250 deg/s, and a
//                            rate the chip clips is rotation `up` never hears
//                            about: at +-250 a brisk lift swung the heading 37
//                            degrees until the box was still again. A car
//                            turns at under 100.
//    ACCEL_CONFIG (1Ch) 0x00 +-2 g, 16384 LSB per g. Anything harder than that
//                            is a pothole, and the heading ignores it anyway.
//
//  Both ranges are written through setRegister() rather than the library's
//  setGyroSensitivity() / setAccelSensitivity(): those refuse to write once
//  any earlier transfer has failed (0.6.2 never clears its error), which is
//  exactly the state a chip found again after a brown-out is in. So the
//  library keeps converting at its defaults, +-2 g and +-250 deg/s, and read()
//  scales the gyro by MPU_GYRO_SCALE.
// ---------------------------------------------------------------------------
#ifndef HUD_MOTION_H
#define HUD_MOTION_H

#include <GY521.h>
#include <GY521_registers.h>
#include "hud_config.h"

#define MPU_ADDR 0x68             // AD0 low, which is how the GY-521 comes

#define MPU_GYRO_CONFIG 0x10      // FS_SEL 2: +-1000 deg/s
/** The library's 131 LSB per deg/s is +-250's; +-1000 is a quarter of that. */
#define MPU_GYRO_SCALE  4.0f

/**
 * Failed reads in a row before the chip is written off. At 50 Hz this is a
 * fifth of a second; hud_sensors.h then looks for it again.
 */
#define MPU_FAILS_MAX 10

class HudMotion {
 public:
  /** WHO_AM_I as read at bring-up: 0x68 for an MPU-6050. For `status`. */
  uint8_t whoAmI = 0;

  bool present() const { return present_; }

  const char* describe() const {
    if (!present_) return "no MPU-6050 found at 0x68";
    return whoAmI == 0x68 ? "MPU-6050 at 0x68" : "MPU at 0x68, not a 6050 by its id";
  }

  bool begin() {
    present_ = false;
    if (!chip_.begin()) return false;                     // ACK, then awake
    if (chip_.setRegister(GY521_PWR_MGMT_1, 0x01) != GY521_OK) return false;
    if (chip_.setRegister(GY521_CONFIG, 0x05) != GY521_OK) return false;
    if (chip_.setRegister(GY521_GYRO_CONFIG, MPU_GYRO_CONFIG) != GY521_OK) return false;
    if (chip_.setRegister(GY521_ACCEL_CONFIG, 0x00) != GY521_OK) return false;
    chip_.setThrottle(false);                             // hud_sensors.h paces it
    whoAmI = chip_.getRegister(GY521_WHO_AM_I);
    fails_ = 0;
    present_ = true;
    return true;
  }

  /** One sample: acceleration in g, rotation in deg/s, box frame. */
  bool read(float* acc, float* gyr) {
    if (!present_) return false;
    if (chip_.read() != GY521_OK) return fail_();
    const float a[3] = { chip_.getAccelX(), chip_.getAccelY(), chip_.getAccelZ() };
    // All zeros is a chip asleep: one that browned out comes back at its reset
    // value, sleeping, and still answers on the bus. Awake, gravity alone
    // never reads zero.
    if (a[0] == 0.0f && a[1] == 0.0f && a[2] == 0.0f) return fail_();
    const float g[3] = { chip_.getGyroX() * MPU_GYRO_SCALE, chip_.getGyroY() * MPU_GYRO_SCALE,
                         chip_.getGyroZ() * MPU_GYRO_SCALE };
    fails_ = 0;
    remap_(a, acc);
    remap_(g, gyr);
    return true;
  }

  /** Is anything answering at 0x68? For `status`. */
  bool probe() { return chip_.isConnected(); }

 private:
  GY521   chip_{ MPU_ADDR, &Wire };
  bool    present_ = false;
  uint8_t fails_ = 0;

  bool fail_() {
    if (++fails_ >= MPU_FAILS_MAX) present_ = false;
    return false;
  }

  /**
   * Chip axes to box axes: X forward, Y left, Z up -- the compass's frame. See
   * MPU_AXIS_ORDER and MPU_AXIS_SIGN in hud_config.h.
   */
  static void remap_(const float* c, float* out) {
    static const uint8_t order[3] = { MPU_AXIS_ORDER };
    static const int8_t  sign[3]  = { MPU_AXIS_SIGN };
    for (uint8_t i = 0; i < 3; i++) out[i] = c[order[i]] * (float)sign[i];
  }
};

#endif  // HUD_MOTION_H
