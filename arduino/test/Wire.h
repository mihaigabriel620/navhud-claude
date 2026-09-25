#pragma once
// Host stand-in for Arduino Wire, answering as the QMC5883P the firmware talks
// to (hud_compass.h): address 0x2C, chip id 0x80 at register 0x00, control
// registers that read back what was written, data always ready, and a steady
// field like central Europe's: about 20 uT horizontal, along +X, and 44 uT
// down.
#include <stdint.h>
#include <deque>

class TwoWire {
 public:
  // ---- test hooks ----
  bool present = true;            // false: nothing ACKs at 0x2C
  int16_t x = 750, y = 0, z = -1650;   // raw counts at 8 G (3750 LSB/G)

  void begin() {}
  void begin(int, int) {}
  void setClock(uint32_t) {}
  void beginTransmission(uint8_t a) { addr_ = a; txn_ = 0; }
  size_t write(uint8_t b) {
    if (txn_ == 0) reg_ = b;
    else if (addr_ == 0x2C) regs_[(uint8_t)(reg_ + txn_ - 1)] = b;
    txn_++;
    return 1;
  }
  uint8_t endTransmission(bool = true) { return (present && addr_ == 0x2C) ? 0 : 2; }
  uint8_t requestFrom(int a, int n, bool = true) {
    rx_.clear();
    if (!present || a != 0x2C) return 0;
    for (int i = 0; i < n; i++) rx_.push_back(value_((uint8_t)(reg_ + i)));
    return (uint8_t)n;
  }
  int available() { return (int)rx_.size(); }
  int read() { if (rx_.empty()) return 0; int v = rx_.front(); rx_.pop_front(); return v; }

 private:
  uint8_t value_(uint8_t r) const {
    switch (r) {
      case 0x00: return 0x80;                               // chip id
      case 0x01: return (uint8_t)(x & 0xFF);
      case 0x02: return (uint8_t)((uint16_t)x >> 8);
      case 0x03: return (uint8_t)(y & 0xFF);
      case 0x04: return (uint8_t)((uint16_t)y >> 8);
      case 0x05: return (uint8_t)(z & 0xFF);
      case 0x06: return (uint8_t)((uint16_t)z >> 8);
      case 0x09: return 0x01;                               // DRDY
      default:   return regs_[r];
    }
  }
  uint8_t addr_ = 0, reg_ = 0; int txn_ = 0;
  uint8_t regs_[256] = {0};
  std::deque<uint8_t> rx_;
};
extern TwoWire Wire;
