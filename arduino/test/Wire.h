#pragma once
// Host stand-in for Arduino Wire, enough to compile and exercise hud_imu.h.
// It answers as a level MPU-6050 sitting still: WHO_AM_I, then zeros for the
// gyro and 1 g on Z for the accelerometer.
#include <stdint.h>
#include <deque>

class TwoWire {
 public:
  void begin() {}
  void begin(int, int) {}
  void setClock(uint32_t) {}
  void beginTransmission(uint8_t) { txReg_ = 0xFF; txn_ = 0; }
  size_t write(uint8_t b) { if (txn_++ == 0) txReg_ = b; return 1; }
  uint8_t endTransmission(bool = true) { return 0; }
  uint8_t requestFrom(uint8_t, uint8_t n, bool = true) {
    rx_.clear();
    for (uint8_t i = 0; i < n; i++) {
      uint8_t r = (uint8_t)(txReg_ + i);
      if (r == 0x75)      rx_.push_back(0x68);          // WHO_AM_I
      else if (r == 0x3F) rx_.push_back(0x40);          // ACCEL_Z high: ~1 g
      else                rx_.push_back(0x00);
    }
    return n;
  }
  int available() { return (int)rx_.size(); }
  int read() { if (rx_.empty()) return 0; int v = rx_.front(); rx_.pop_front(); return v; }
 private:
  uint8_t txReg_ = 0; int txn_ = 0;
  std::deque<uint8_t> rx_;
};
extern TwoWire Wire;
