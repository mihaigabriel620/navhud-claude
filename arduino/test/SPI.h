#pragma once
// Host stand-in for Arduino SPI carrying a small MCP2515 SIMULATOR.
//
// A stub that just echoes bytes proves nothing: it cannot tell a correct
// register protocol from a broken one. This one keeps a register file and
// implements the SPI instructions the firmware and mcp_can use, straight from
// datasheet Table 12-1 -- so a test that passes here means the driver would
// have talked correctly to the real chip.
#include <stdint.h>
#include <cstring>

#ifndef MSBFIRST
#define MSBFIRST 1
#endif
#ifndef SPI_MODE0
#define SPI_MODE0 0
#endif
// Only so Adafruit BusIO's SPI half compiles (test/sensor_libs.h); nothing on
// this board talks SPI through it.
#ifndef SPI_MODE1
#define SPI_MODE1 1
#define SPI_MODE2 2
#define SPI_MODE3 3
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

  void begin() {}
  void beginTransaction(SPISettings s) {
    clock = s.clock; transactions++; open++;
    if (open > maxOpen) maxOpen = open;
    step_ = 0; cmd_ = 0; addr_ = 0;
  }
  void endTransaction() { open--; }

  void reset() {
    memset(reg, 0, sizeof reg);
    reg[0x0F] = 0x80; reg[0x0E] = 0x80;      // CANCTRL/CANSTAT: config after POR
    clock = 0; transactions = open = maxOpen = resets = 0; filtered = 0;
    overflowed = 0; stuck = -1; stuckAfter = -1; stuckValue = 0xFF; stuckUntil = -1;
    // txCommands and normalModeRequests are NOT reset: they cover a whole run.
    deadReads = 0; runaway = false;
    step_ = 0; cmd_ = 0;
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

  /**
   * Put a standard frame on the wire and into a receive buffer.
   *
   * Modelled on the real chip rather than on a queue: one frame per buffer.
   * A frame arriving while its buffer's RXnIF is still set is lost and latches
   * RXnOVR in EFLG (datasheet 4.2, Register 6-6) -- which is exactly what
   * canOverflows() counts. The frame is written into the buffer's register
   * window (RXBnCTRL/SIDH/SIDL/EID8/EID0/DLC/D0..D7, Table 11-1), because that
   * is where mcp_can reads it from: it uses READ, not READ RX BUFFER.
   *
   * `dlc` is the raw 4-bit length on the wire. 9..15 is legal CAN and means
   * "8 bytes"; only the first eight data bytes exist on the chip.
   */
  void deliver(bool buf1, uint32_t id, const uint8_t* d, uint8_t len, bool extended = false,
               bool rtr = false) {
    // An extended id is 29 bits: the top 11 go where a standard id does, the
    // low 18 into SIDL<1:0>, EID8 and EID0 (Registers 4-5..4-8).
    const uint16_t sid = extended ? (uint16_t)(id >> 18) : (uint16_t)id;
    if (!accepts_(buf1, sid)) { filtered++; return; }
    const uint8_t flag = buf1 ? 0x02 : 0x01;
    if (reg[0x2C] & flag) { reg[0x2D] |= buf1 ? 0x80 : 0x40; overflowed++; return; }
    const uint8_t base = buf1 ? 0x70 : 0x60;
    reg[base] = (uint8_t)((reg[base] & ~0x08) | (rtr && !extended ? 0x08 : 0x00));
    reg[base + 1] = (uint8_t)(sid >> 3);
    reg[base + 2] = (uint8_t)(((sid & 0x07) << 5) |
                              (extended ? (0x08 | ((id >> 16) & 0x03)) : 0x00));
    reg[base + 3] = extended ? (uint8_t)(id >> 8) : 0;
    reg[base + 4] = extended ? (uint8_t)id : 0;
    reg[base + 5] = (uint8_t)((len & 0x0F) | (rtr && extended ? 0x40 : 0x00));
    for (uint8_t i = 0; i < 8; i++) reg[base + 6 + i] = (d && i < len) ? d[i] : 0;
    reg[0x2C] |= flag;
  }

  /** Frames lost because their buffer was still full. */
  int overflowed = 0;

  // ---- the listen-only guarantee --------------------------------------------
  // Anything that could put a bit on the car's bus, counted as it is clocked
  // in: REQUEST-TO-SEND (0x81..0x87), LOAD TX BUFFER (0x40..0x45), a TXREQ bit
  // set in TXBnCTRL (0x30/0x40/0x50), and a mode request for NORMAL (000) --
  // the one mode in which the chip acknowledges frames and sends error flags.
  // Loopback (010) is internal and listen-only (011) cannot transmit at all.
  int txCommands = 0;
  int normalModeRequests = 0;

  /**
   * A dead module: every byte clocked in reads as this, whatever was asked.
   * 0xFF is MISO floating high (no power, CS not arriving); 0x00 is MISO held
   * low. -1 is a working chip.
   */
  int stuck = -1;

  /**
   * The module dies part-way: MISO sticks at `stuckValue` from this
   * transaction number on. -1 = never.
   */
  int stuckAfter = -1;
  int stuckValue = 0xFF;
  /** ...and comes back after this transaction number. -1 = stays dead. */
  int stuckUntil = -1;

  /**
   * A drain that never ends cannot be tested by waiting for it. After this
   * many bytes read from a dead module the simulator gives up being dead, so
   * the loop can finish and the test can report the runaway instead of hanging.
   */
  long deadReads = 0;
  bool runaway = false;

  /** The buffer form Adafruit BusIO declares. Byte by byte, in place. */
  void transfer(void* buf, size_t n) {
    uint8_t* p = (uint8_t*)buf;
    for (size_t i = 0; i < n; i++) p[i] = transfer(p[i]);
  }

  uint8_t transfer(uint8_t b) {
    if (stuckAfter >= 0 && transactions >= stuckAfter) { stuck = stuckValue; stuckAfter = -1; }
    if (stuckUntil >= 0 && transactions > stuckUntil) { stuck = -1; stuckUntil = -1; }
    if (stuck >= 0) {
      step_++;
      if (++deadReads > 100000) { runaway = true; stuck = -1; reg[0x2C] = 0; }
      return (uint8_t)(stuck >= 0 ? stuck : 0);
    }
    uint8_t r = 0x00;
    if (step_ == 0) {
      cmd_ = b;
      if ((cmd_ >= 0x81 && cmd_ <= 0x87) || (cmd_ >= 0x40 && cmd_ <= 0x45)) txCommands++;
      if (cmd_ == 0xC0) {                     // RESET
        resets++;
        memset(reg, 0, sizeof reg);
        reg[0x0F] = 0x80; reg[0x0E] = 0x80;
      } else if ((cmd_ & 0xF9) == 0x90) {     // READ RX BUFFER 0x90/0x92/0x94/0x96
        // Reads the same register window READ would, starting at SIDH (n=0)
        // or D0 (n=1) of the buffer (Table 12-1).
        addr_ = (uint8_t)(((cmd_ & 0x04) ? 0x71 : 0x61) + ((cmd_ & 0x02) ? 5 : 0));
      }
    } else if (cmd_ == 0xA0) {                // READ STATUS (Figure 12-8)
      // bit0 RX0IF, bit1 RX1IF -- CANINTF's two receive flags. The transmit
      // bits are always clear: this node never transmits.
      r = (uint8_t)(reg[0x2C] & 0x03);
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
      r = reg[addr_++];
      // "The associated RX flag bit will be cleared after bringing CS high"
      // -- clearing it on the first data byte is indistinguishable here.
      reg[0x2C] &= (uint8_t)~((cmd_ & 0x04) ? 0x02 : 0x01);
    }
    step_++;
    return r;
  }

 private:
  void writeReg_(uint8_t a, uint8_t v) {
    if ((a == 0x30 || a == 0x40 || a == 0x50) && (v & 0x08)) txCommands++;     // TXREQ
    if (a == 0x0F && (v & 0xE0) == 0x00) normalModeRequests++;
    reg[a] = v;
    // The real chip mirrors the requested mode into CANSTAT once it takes.
    if (a == 0x0F) reg[0x0E] = (uint8_t)((reg[0x0E] & 0x1F) | (v & 0xE0));
  }
  int      step_ = 0;
  uint8_t  cmd_ = 0, addr_ = 0, mask_ = 0;
};
extern SPIClass SPI;
