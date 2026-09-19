// A stand-in for the ESP8266 core's EEPROM library, close enough that the
// sketch's persistence path can be exercised on a PC.
//
// It models the two things that actually matter and are easy to get wrong:
//   * reads and writes go to a RAM shadow; only commit() touches "flash"
//   * commit() erases and rewrites a whole 4 KB sector every time, so the
//     test can count them and fail if the frame loop ever causes one
#pragma once
#include <cstdint>
#include <cstring>

class EEPROMStub {
 public:
  void begin(size_t size) { size_ = size > sizeof buf_ ? sizeof buf_ : size; }
  uint8_t read(int a) { return (a >= 0 && (size_t)a < size_) ? buf_[a] : 0; }
  void write(int a, uint8_t v) {
    if (a < 0 || (size_t)a >= size_) return;
    if (buf_[a] != v) dirty_ = true;
    buf_[a] = v;
  }
  bool commit() {
    if (!dirty_) return true;      // the real one is a free no-op here too
    dirty_ = false;
    memcpy(flash_, buf_, sizeof buf_);
    sectorErases++;
    return true;
  }
  size_t length() const { return size_; }

  // ---- test hooks ----
  int sectorErases = 0;
  /** Throw away the RAM shadow, as a power cycle would. */
  void powerCycle() { memcpy(buf_, flash_, sizeof buf_); dirty_ = false; }
  void wipe() { memset(buf_, 0xFF, sizeof buf_); memset(flash_, 0xFF, sizeof flash_); }

 private:
  uint8_t buf_[4096]  = {};
  uint8_t flash_[4096] = {};
  size_t  size_ = 0;
  bool    dirty_ = false;
};

static EEPROMStub EEPROM;
