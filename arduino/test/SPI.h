#pragma once
// Host stand-in for Arduino SPI carrying a small MCP2515 SIMULATOR.
//
// A stub that just echoes bytes proves nothing: it cannot tell a correct
// register protocol from a broken one. This one keeps a register file and
// implements the five SPI instructions the driver uses, straight from
// datasheet Table 12-1 -- so a test that passes here means the driver would
// have talked correctly to the real chip.
#include <stdint.h>
#include <deque>
#include <vector>
#include <cstring>

#ifndef MSBFIRST
#define MSBFIRST 1
#endif
#ifndef SPI_MODE0
#define SPI_MODE0 0
#endif

struct SPISettings {
  uint32_t clock; uint8_t order; uint8_t mode;
  SPISettings(uint32_t c = 1000000, uint8_t o = MSBFIRST, uint8_t m = SPI_MODE0)
    : clock(c), order(o), mode(m) {}
};

class SPIClass {
 public:
  // ---- observable by the test ----
  uint8_t  reg[256] = {0};
  uint32_t clock = 0;
  int      transactions = 0, open = 0, maxOpen = 0;
  int      resets = 0;
  std::deque<std::vector<uint8_t>> rxb0;   // frames waiting in RX buffer 0
  std::deque<std::vector<uint8_t>> rxb1;

  void begin() {}
  void beginTransaction(SPISettings s) {
    clock = s.clock; transactions++; open++;
    if (open > maxOpen) maxOpen = open;
    step_ = 0; cmd_ = 0; addr_ = 0; out_.clear();
  }
  void endTransaction() { open--; }

  void reset() {
    memset(reg, 0, sizeof reg);
    reg[0x0F] = 0x80; reg[0x0E] = 0x80;      // CANCTRL/CANSTAT: config after POR
    clock = 0; transactions = open = maxOpen = resets = 0; filtered = 0;
    rxb0.clear(); rxb1.clear();
    step_ = 0; cmd_ = 0; out_.clear();
  }

  /**
   * Would the acceptance filters let this id into this buffer?
   *
   * Modelled rather than assumed, because it being unmodelled is what let a
   * real bug live: the driver configured RXM = 00, which leaves the hardware
   * filters ON, while every comment around it said they were bypassed. With no
   * filter model here, RXM was inert and every test passed either way.
   *
   * RXM<1:0> lives in bits 6:5 of RXBnCTRL. 11 means "turn mask/filters off;
   * receive any message"; 00 means "receive all valid messages that meet
   * filter criteria". RXB0 is served by filters 0-1, RXB1 by filters 2-5.
   */
  bool accepts_(bool buf1, uint16_t id) const {
    const uint8_t ctrl = reg[buf1 ? 0x70 : 0x60];
    if (((ctrl >> 5) & 0x03) == 0x03) return true;         // RXM = 11: wide open
    const uint8_t maskAt = buf1 ? 0x24 : 0x20;             // RXM1SIDH / RXM0SIDH
    const uint16_t mask = (uint16_t)((reg[maskAt] << 3) | (reg[maskAt + 1] >> 5));
    static const uint8_t filtAt[6] = { 0x00, 0x04, 0x08, 0x0C, 0x10, 0x14 };
    const uint8_t lo = buf1 ? 2 : 0, hi = buf1 ? 6 : 2;
    for (uint8_t i = lo; i < hi; i++) {
      const uint16_t f = (uint16_t)((reg[filtAt[i]] << 3) | (reg[filtAt[i] + 1] >> 5));
      if (((id ^ f) & mask) == 0) return true;
    }
    return false;
  }

  /** How many frames the filters turned away. */
  int filtered = 0;

  /** Queue a standard frame into a receive buffer and raise its flag. */
  void deliver(bool buf1, uint16_t id, const uint8_t* d, uint8_t len, bool extended = false) {
    if (!accepts_(buf1, id)) { filtered++; return; }
    std::vector<uint8_t> b(13, 0);
    b[0] = (uint8_t)(id >> 3);
    b[1] = (uint8_t)((id & 0x07) << 5) | (extended ? 0x08 : 0x00);
    b[4] = len;
    for (uint8_t i = 0; i < len && i < 8; i++) b[5 + i] = d[i];
    if (buf1) { rxb1.push_back(b); reg[0x2C] |= 0x02; }
    else      { rxb0.push_back(b); reg[0x2C] |= 0x01; }
  }

  uint8_t transfer(uint8_t b) {
    uint8_t r = 0x00;
    if (step_ == 0) {
      cmd_ = b;
      if (cmd_ == 0xC0) {                     // RESET
        resets++;
        memset(reg, 0, sizeof reg);
        reg[0x0F] = 0x80; reg[0x0E] = 0x80;
      } else if ((cmd_ & 0xF9) == 0x90) {     // READ RX BUFFER 0x90/0x92/0x94/0x96
        const bool one = (cmd_ & 0x04) != 0;
        auto& q = one ? rxb1 : rxb0;
        if (!q.empty()) { out_.assign(q.front().begin(), q.front().end()); q.pop_front(); }
        else out_.assign(13, 0);
        // "The associated RX flag bit will be cleared after bringing CS high"
        // -- clearing it here is indistinguishable from the test's side.
        if (one) { if (rxb1.empty()) reg[0x2C] &= ~0x02; }
        else     { if (rxb0.empty()) reg[0x2C] &= ~0x01; }
      }
    } else if (cmd_ == 0x03) {                // READ
      if (step_ == 1) addr_ = b;
      else            r = reg[addr_++];
    } else if (cmd_ == 0x02) {                // WRITE
      if (step_ == 1) addr_ = b;
      else            writeReg_(addr_++, b);
    } else if (cmd_ == 0x05) {                // BIT MODIFY
      if (step_ == 1) addr_ = b;
      else if (step_ == 2) mask_ = b;
      else if (step_ == 3) writeReg_(addr_, (uint8_t)((reg[addr_] & ~mask_) | (b & mask_)));
    } else if ((cmd_ & 0xF9) == 0x90) {       // READ RX BUFFER payload
      const size_t i = step_ - 1;
      r = (i < out_.size()) ? out_[i] : 0x00;
    }
    step_++;
    return r;
  }

 private:
  void writeReg_(uint8_t a, uint8_t v) {
    reg[a] = v;
    // The real chip mirrors the requested mode into CANSTAT once it takes.
    if (a == 0x0F) reg[0x0E] = (uint8_t)((reg[0x0E] & 0x1F) | (v & 0xE0));
  }
  int      step_ = 0;
  uint8_t  cmd_ = 0, addr_ = 0, mask_ = 0;
  std::vector<uint8_t> out_;
};
extern SPIClass SPI;
