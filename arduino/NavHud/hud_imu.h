// ---------------------------------------------------------------------------
//  hud_imu.h -- the yaw-rate sensor.
//
//  Any of an MPU-6050, an MPU-6500, or the gyro half of an MPU-9250. They
//  answer on the same registers and this file cannot tell them apart, which is
//  fine, because all it wants is one number: how fast is the car turning, right
//  now. GPS already knows which way the car points; it only says so once a
//  second, and a rate is what fills the gap between.
//
//  WIRING
//      VCC -> 3V3          (the breakout boards carry a regulator; 5 V is also
//                           fine on those, 3V3 is not fine on a bare chip)
//      GND -> GND          shared with the ESP, or nothing works
//      SDA -> D3, GPIO0
//      SCL -> D4, GPIO2
//      AD0 -> leave open   address 0x68. Tie to 3V3 for 0x69.
//
//  NOT the usual D1/D2. On this build the display already has GPIO5 and GPIO4
//  for its DC and backlight, so the bus moved. Most breakout boards carry their
//  own 2.2k-4.7k pull-ups; a bare chip does not, and without them the bus is
//  silent in a way that looks exactly like a missing sensor. 'scan' will tell
//  you which it is.
//
//  Wire.begin() is NOT called here. It happens once in setup(), before anything
//  on the bus is touched, because the compass is on the same two wires and has
//  no business depending on whether this chip is fitted.
// ---------------------------------------------------------------------------
#ifndef HUD_IMU_H
#define HUD_IMU_H

#ifdef HUD_HOST_TEST
  #include "tft_stub.h"
#else
  #include <Arduino.h>
#endif
#include <Wire.h>
#ifdef HUD_MAG
#endif

#ifndef HUD_IMU_ADDR
#define HUD_IMU_ADDR 0x68
#endif

// Which pins the bus runs on. ESP8266 I2C is bit-banged in software, so any
// two GPIOs will do and there is no penalty for not using the default pair.
#if !defined(HUD_IMU_SDA) && !defined(HUD_IMU_SCL)
  #if defined(ARDUINO_ARCH_ESP8266)
    #define HUD_IMU_SDA 0     // D3
    #define HUD_IMU_SCL 2     // D4
  #elif defined(ESP32)
    #define HUD_IMU_SDA 21
    #define HUD_IMU_SCL 22
  #endif
#endif

// A pin used twice is a fault you debug with an oscilloscope. Catch it here.
#if defined(HUD_IMU_SDA)
  #if defined(TFT_DC) && (TFT_DC == HUD_IMU_SDA)
    #error "HUD_IMU_SDA is the same pin as TFT_DC. Move one of them."
  #endif
  #if defined(TFT_CS) && (TFT_CS >= 0) && (TFT_CS == HUD_IMU_SDA)
    #error "HUD_IMU_SDA is the same pin as TFT_CS. Move one of them."
  #endif
  #if defined(TFT_SCLK) && (TFT_SCLK == HUD_IMU_SDA)
    #error "HUD_IMU_SDA is the SPI clock pin. Move it."
  #endif
  #if defined(TFT_MOSI) && (TFT_MOSI == HUD_IMU_SDA)
    #error "HUD_IMU_SDA is the SPI data pin. Move it."
  #endif
  #if defined(BACKLIGHT_PIN) && (BACKLIGHT_PIN >= 0) && (BACKLIGHT_PIN == HUD_IMU_SDA)
    #error "HUD_IMU_SDA is the same pin as BACKLIGHT_PIN. Move one of them."
  #endif
#endif
#if defined(HUD_IMU_SCL)
  #if defined(TFT_DC) && (TFT_DC == HUD_IMU_SCL)
    #error "HUD_IMU_SCL is the same pin as TFT_DC. Move one of them."
  #endif
  #if defined(TFT_CS) && (TFT_CS >= 0) && (TFT_CS == HUD_IMU_SCL)
    #error "HUD_IMU_SCL is the same pin as TFT_CS. Move one of them."
  #endif
  #if defined(TFT_SCLK) && (TFT_SCLK == HUD_IMU_SCL)
    #error "HUD_IMU_SCL is the SPI clock pin. Move it."
  #endif
  #if defined(TFT_MOSI) && (TFT_MOSI == HUD_IMU_SCL)
    #error "HUD_IMU_SCL is the SPI data pin. Move it."
  #endif
  #if defined(BACKLIGHT_PIN) && (BACKLIGHT_PIN >= 0) && (BACKLIGHT_PIN == HUD_IMU_SCL)
    #error "HUD_IMU_SCL is the same pin as BACKLIGHT_PIN. Move one of them."
  #endif
#endif

// ±250 deg/s full scale: a car never comes close, and it is the most sensitive
// range, so the quantisation step is a hundredth of a degree per second.
#define HUD_IMU_LSB_PER_DPS 131.0f
/** AFS_SEL 0 is +-2 g over 16 bits: 16384 counts per g. */
#define HUD_IMU_LSB_PER_G   16384.0f

class HudImu {
 public:
  bool  present   = false;
  /** Whatever the chip answered on 0x75, for the diagnostics line. */
  uint8_t whoAmI  = 0;
  /** Last gravity vector in g, for tilt-compensating the compass. */
  float accelX = 0, accelY = 0, accelZ = 1.0f;
#ifdef HUD_MAG
#endif
  float yawDps    = 0.0f;   // clockwise positive, i.e. heading increasing
  float pitchDeg  = 0.0f;
  float rollDeg   = 0.0f;
  float headingRel = 0.0f;  // integrated, drifts; informational only

  /**
   * Start the MPU. Wire.begin() is the CALLER's job and has already happened.
   *
   * It used to be done here, which quietly made every device on the I2C bus
   * depend on this function being reached -- and the compass is not on the MPU,
   * it is a separate board on the same two wires. An MPU that was unplugged or
   * never fitted therefore took the compass down with it.
   */
  bool begin() {
    // PWR_MGMT_1: wake up, clock from the X gyro (more stable than the RC osc)
    if (!write8(0x6B, 0x01)) return false;
    write8(0x1A, 0x03);      // CONFIG: 44 Hz DLPF -- kills engine vibration
    write8(0x1B, 0x00);      // GYRO_CONFIG: +-250 deg/s
    write8(0x1C, 0x00);      // ACCEL_CONFIG: +-2 g
    delay(50);
    // WHO_AM_I, reported rather than judged. 0x68, 0x70, 0x71, 0x72 and 0x73
    // are all parts that answer on exactly the registers used here, so there
    // is nothing to gain by insisting on one of them: anything that is not
    // all-ones or all-zeroes is something, and something is enough.
    //
    // The value still goes on the serial line, because it is the difference
    // between "the sensor is not wired up" and "the sensor is not the one on
    // the label" -- and 0x00 specifically means the read did not even happen.
    uint8_t who = 0;
    if (!read(0x75, &who, 1)) return false;
    whoAmI = who;
    present = (who != 0x00 && who != 0xFF);
    if (present) {
      pickUpAxis();
      calibrate(500);
      levelNow();          // learn the mounting angle while the car is parked
    }
    return present;
  }

  /**
   * Learn the zero-rate offset. Called at startup and again whenever the phone
   * reports the car is stopped -- a gyro's bias wanders with temperature, and
   * a stationary car is the one moment we know the true rate is zero.
   */
  void calibrate(uint16_t samples) {
    if (!present) return;
    int32_t sum = 0;
    uint16_t used = 0;
    for (uint16_t i = 0; i < samples; i++) {
      int16_t g[3];
      if (!readGyro(g)) return;
      sum += g[upAxis_];
      used++;
      delayMicroseconds(1200);
      // 500 samples of 100 kHz I2C is about a second of straight-line code.
      // The ESP8266's software watchdog fires at ~3.2 s and the radio stack
      // wants servicing whatever we think of it, so hand the CPU back.
      if ((i & 0x1F) == 0x1F) yield();
    }
    if (used) { biasLsb_ = (float)sum / (float)used; }
  }

  /**
   * The same re-zero, spread over many loop passes.
   *
   * calibrate(200) blocks for about 440 ms, and 440 ms without calling
   * Serial.read() at 115200 baud is roughly 5,000 bytes into a 256-byte
   * ring buffer -- twenty times over. $HUD frames are re-sent four times a
   * second so they heal, but a $CAM or $LANE transition emitted in that window
   * is edge-triggered and gone for good. So gather the samples a few at a time
   * between reads instead of standing on the UART.
   */
  void calibrateStep() {
    if (!present) return;
    if (calRemaining_ == 0) return;
    for (uint8_t i = 0; i < CAL_PER_PASS && calRemaining_ > 0; i++) {
      int16_t g[3];
      if (!readGyro(g)) { calRemaining_ = 0; return; }
      calSum_  += g[upAxis_];
      calSumX_ += g[0]; calSumY_ += g[1]; calSumZ_ += g[2];
      calUsed_++;
      calRemaining_--;
    }
    if (calRemaining_ == 0 && calUsed_) {
      biasLsb_ = (float)calSum_  / (float)calUsed_;
      biasX_   = (float)calSumX_ / (float)calUsed_;
      biasY_   = (float)calSumY_ / (float)calUsed_;
      biasZ_   = (float)calSumZ_ / (float)calUsed_;
    }
  }

  void beginCalibration(uint16_t samples) {
    if (!present) return;
    calSum_ = calSumX_ = calSumY_ = calSumZ_ = 0;
    calUsed_ = 0; calRemaining_ = samples;
  }

  /** Call as often as you like; it integrates on its own clock. */
  void update() {
    if (!present) return;
    if (calRemaining_ > 0) {
      calibrateStep();
      // Say "not turning" rather than repeating the last rate: sendImu() keeps
      // going at 20 Hz throughout, and the phone integrates whatever it is
      // given. Zero is the truth here -- the car is standing still, which is
      // the only reason a re-zero was started.
      yawDps = 0.0f;
      lastUs_ = micros();
      return;
    }
    const uint32_t now = micros();
    const float dt = lastUs_ ? (now - lastUs_) / 1e6f : 0.0f;
    lastUs_ = now;

    int16_t g[3], a[3];
    if (!readGyro(g) || !readAccel(a)) return;

    // Kept for the compass, which needs a live gravity vector to take the tilt
    // out of a heading. Deliberately the *raw* reading rather than the
    // mounting angles measured at boot: those are the constant part, and what
    // tilt compensation wants is the whole of it, braking and camber included.
    // In g, so the units match what headingDeg expects; only the direction
    // matters to it, but a caller reading these should get something sane.
    accelX = (float)a[0] / HUD_IMU_LSB_PER_G;
    accelY = (float)a[1] / HUD_IMU_LSB_PER_G;
    accelZ = (float)a[2] / HUD_IMU_LSB_PER_G;

    // Yaw rate about the TRUE vertical, not about whichever sensor axis
    // happens to be nearest it. A dash is not level -- the E60's slopes -- and
    // ignoring the tilt costs cos(tilt): 1.5 % at 10 degrees, 6 % at 20, 13 %
    // at 30, plus it leaks real yaw onto the axis you are not reading.
    //
    // The correction is the Euler-rate transformation, not a plain dot product
    //   psi_dot = (q*sin(roll) + r*cos(roll)) / cos(pitch)
    // and the roll/pitch it needs come from the mounting, measured ONCE while
    // standing still (levelNow()). Deriving them from the accelerometer every
    // pass would be worse than useless: braking and cornering tilt the
    // measured gravity vector exactly when the yaw rate matters most.
    const float gx = (g[0] - biasX_) / HUD_IMU_LSB_PER_DPS;
    const float gy = (g[1] - biasY_) / HUD_IMU_LSB_PER_DPS;
    const float gz = (g[2] - biasZ_) / HUD_IMU_LSB_PER_DPS;
    float aboutUp;
    if (levelled_) {
      // body p,q,r mapped through the stored mount roll/pitch
      // p (roll rate) does not enter the heading-rate term at zero pitch and
      // is dominated by the 1/cos(pitch) factor at any pitch a road allows.
      const float q = gy, r = gz;
      aboutUp = (q * sinf(mountRoll_) + r * cosf(mountRoll_)) / cosf(mountPitch_);
    } else {
      // not levelled yet: fall back to the dominant axis, which is what this
      // did before and is right to within the mounting tilt
      const float raw[3] = { gx, gy, gz };
      aboutUp = upSign_ * raw[upAxis_];
    }
    // Right-hand-rule positive about "up" is anticlockwise seen from above,
    // which is a left turn -- and the phone wants clockwise positive.
    yawDps = -aboutUp;

    if (dt > 0.0f && dt < 0.25f) {
      headingRel += yawDps * dt;
      while (headingRel >= 360.0f) headingRel -= 360.0f;
      while (headingRel < 0.0f)    headingRel += 360.0f;
    }

    // Pitch and roll from gravity. Only worth having as a sanity display; the
    // phone ignores them.
    const float ax = a[0], ay = a[1], az = a[2];
    pitchDeg = atan2f(-ax, sqrtf(ay * ay + az * az)) * 57.2957795f;
    rollDeg  = atan2f(ay, az) * 57.2957795f;
  }

  /**
   * Record how the sensor is bolted in. Call it once with the car standing on
   * level ground; the result is what makes the yaw rate independent of the
   * mounting angle. Cheap enough to redo on every long standstill.
   */
  void levelNow() {
    if (!present) return;      // boot only -- see noteStopped() for why
    int32_t acc[3] = {0, 0, 0};
    uint8_t used = 0;
    for (uint8_t i = 0; i < 32; i++) {
      int16_t a[3];
      if (!readAccel(a)) return;
      acc[0] += a[0]; acc[1] += a[1]; acc[2] += a[2];
      used++;
      delayMicroseconds(600);
    }
    if (!used) return;
    const float ax = (float)acc[0] / used, ay = (float)acc[1] / used,
                az = (float)acc[2] / used;
    // NXP AN3461 eq. 25-26: roll = atan2(ay, az), pitch = atan(-ax / hypot)
    mountRoll_  = atan2f(ay, az);
    mountPitch_ = atan2f(-ax, sqrtf(ay * ay + az * az));
    // A near-vertical mount would divide by ~cos(90) and blow up. Refuse it
    // and say so by staying unlevelled rather than reporting nonsense.
    levelled_ = (fabsf(mountPitch_) < 1.0f);      // < ~57 degrees
  }

  /** Re-zero when the car has been standing still. */
  void noteStopped(bool stopped) {
    if (!present) return;
    if (stopped) {
      if (++stoppedTicks_ == 200) beginCalibration(200);   // ~a second of standing
      // Deliberately NOT re-levelling here. levelNow() is 32 blocking I2C
      // reads, about 45 ms -- and 45 ms at 115200 baud is 500 bytes arriving
      // into a 256-byte UART buffer, so a $CAM or $LANE transition emitted in
      // that window is edge-triggered and gone for good. The mounting angle
      // does not change while the car is parked anyway: boot is enough.
    } else {
      stoppedTicks_ = 0;
      // Cancel a re-zero the moment the car pulls away. Otherwise the samples
      // taken while accelerating out of a turn are averaged into the bias, and
      // the board then reports a permanent few degrees per second of yaw --
      // the map arrow rotating steadily while driving dead straight.
      calRemaining_ = 0;
    }
  }

 private:
  uint8_t  upAxis_ = 2;
  int8_t   upSign_ = 1;
  float    biasLsb_ = 0.0f;              // legacy single-axis bias, still used
  float    biasX_ = 0.0f, biasY_ = 0.0f, biasZ_ = 0.0f;
  float    mountRoll_ = 0.0f, mountPitch_ = 0.0f;
  bool     levelled_ = false;
  uint32_t lastUs_ = 0;
  uint16_t stoppedTicks_ = 0;
  static const uint8_t CAL_PER_PASS = 4;
  int32_t  calSum_ = 0, calSumX_ = 0, calSumY_ = 0, calSumZ_ = 0;
  uint16_t calUsed_ = 0;
  uint16_t calRemaining_ = 0;

  /** Whichever axis gravity is along is the vertical one. */
  void pickUpAxis() {
    int32_t acc[3] = {0, 0, 0};
    for (uint8_t i = 0; i < 32; i++) {
      int16_t a[3];
      if (!readAccel(a)) return;
      for (uint8_t k = 0; k < 3; k++) acc[k] += a[k];
      delay(2);
    }
    uint8_t best = 0;
    for (uint8_t k = 1; k < 3; k++) if (labs(acc[k]) > labs(acc[best])) best = k;
    upAxis_ = best;
    // At rest an accelerometer reads +1 g along the axis pointing up.
    upSign_ = (acc[best] >= 0) ? 1 : -1;
  }

  bool write8(uint8_t reg, uint8_t val) {
    Wire.beginTransmission(HUD_IMU_ADDR);
    Wire.write(reg);
    Wire.write(val);
    return Wire.endTransmission() == 0;
  }

  bool read(uint8_t reg, uint8_t* buf, uint8_t n) {
    Wire.beginTransmission(HUD_IMU_ADDR);
    Wire.write(reg);
    if (Wire.endTransmission(false) != 0) return false;
    if (Wire.requestFrom((int)HUD_IMU_ADDR, (int)n) != n) return false;
    for (uint8_t i = 0; i < n; i++) buf[i] = Wire.read();
    return true;
  }

  bool readTriple(uint8_t reg, int16_t* out) {
    uint8_t b[6];
    if (!read(reg, b, 6)) return false;
    for (uint8_t i = 0; i < 3; i++)
      out[i] = (int16_t)((b[i * 2] << 8) | b[i * 2 + 1]);
    return true;
  }

  bool readGyro(int16_t* g)  { return readTriple(0x43, g); }
  bool readAccel(int16_t* a) { return readTriple(0x3B, a); }
};

#endif  // HUD_IMU_H
