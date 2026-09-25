#pragma once
// Host stand-in for coryjfowler's MCP_CAN 1.5.1 -- only the part hud_can.h
// calls, and done the way the library does it, over the MCP2515 simulator in
// SPI.h, so canPump()'s own raw-SPI guards are exercised against the same
// register traffic the real library produces.
//
// Checked against mcp_can.cpp / mcp_can_dfs.h at tag 1.5.1:
//   begin()        reset, config mode, then RXM=11 (+BUKT on RXB0) for MCP_ANY;
//                  the chip is left out of config mode (the library leaves it in
//                  loopback until setMode() is called).
//   setMode()      writes CANCTRL's mode bits and waits for CANSTAT to agree.
//   checkReceive() READ STATUS, CAN_MSGAVAIL when RX0IF|RX1IF.
//   readMsgBuf()   RXB0 first, else RXB1; SIDH..EID0 via READ, RXBnCTRL for
//                  RTR, DLC & 0x0F, then THAT MANY data bytes; flags into bits
//                  31 (extended) and 30 (remote) of the id; clears RXnIF.
//
// The one deliberate difference: the real library copies DLC & 0x0F bytes into
// an 8-byte array, so a length of 9..15 writes past its end. This stub counts
// that instead (`overruns`), so a test can prove canPump() never lets it happen.
#include <stdint.h>
#include <initializer_list>
#include "SPI.h"

#define INT8U  uint8_t
#define INT32U unsigned long

#define CAN_OK        (0)
#define CAN_FAILINIT  (1)
#define CAN_MSGAVAIL  (3)
#define CAN_NOMSG     (4)

#define MCP_ANY        3
#define MCP_16MHZ      1
#define MCP_8MHZ       2
#define CAN_100KBPS    9
#define CAN_500KBPS   13

#define MCP_NORMAL     0x00
#define MCP_LOOPBACK   0x40
#define MCP_LISTENONLY 0x60
#define MODE_CONFIG    0x80

class MCP_CAN {
 public:
  MCP_CAN(SPIClass* spi, INT8U cs) : spi_(spi), cs_(cs) { (void)cs_; }

  // ---- observable by the test ----
  int overruns = 0;          // a DLC > 8 was read into the 8-byte buffer
  INT8U lastSpeedset = 0, lastClockset = 0;

  INT8U begin(INT8U idmodeset, INT8U speedset, INT8U clockset) {
    lastSpeedset = speedset; lastClockset = clockset;
    spi_->begin();
    cmd_({0xC0});                                        // RESET
    if (!requestMode_(MODE_CONFIG)) return CAN_FAILINIT;
    if (idmodeset != MCP_ANY) return CAN_FAILINIT;       // only mode this firmware uses
    modify_(0x60, 0x60 | 0x04, 0x60 | 0x04);             // RXB0CTRL: RXM=11, BUKT
    modify_(0x70, 0x60, 0x60);                           // RXB1CTRL: RXM=11
    requestMode_(MCP_LOOPBACK);
    return CAN_OK;
  }

  INT8U setMode(INT8U opMode) { return requestMode_(opMode) ? 0 : 1; }

  INT8U checkReceive() {
    return (status_() & 0x03) ? CAN_MSGAVAIL : CAN_NOMSG;
  }

  INT8U readMsgBuf(INT32U* id, INT8U* len, INT8U* buf) {
    const INT8U st = status_();
    INT8U base, flag;
    if (st & 0x01)      { base = 0x60; flag = 0x01; }
    else if (st & 0x02) { base = 0x70; flag = 0x02; }
    else return CAN_NOMSG;

    INT8U b[4];
    for (int i = 0; i < 4; i++) b[i] = read_(base + 1 + i);
    INT32U v = ((INT32U)b[0] << 3) + (b[1] >> 5);
    bool ext = false;
    if (b[1] & 0x08) {
      v = (v << 2) + (b[1] & 0x03);
      v = (v << 8) + b[2];
      v = (v << 8) + b[3];
      ext = true;
    }
    const INT8U ctrl = read_(base);
    INT8U dlc = read_(base + 5) & 0x0F;
    const bool rtr = (ctrl & 0x08) != 0;
    if (dlc > 8) { overruns++; dlc = 8; }                 // see the note at the top
    for (INT8U i = 0; i < dlc; i++) buf[i] = read_(base + 6 + i);
    modify_(0x2C, flag, 0);                              // clear RXnIF

    if (ext) v |= 0x80000000UL;
    if (rtr) v |= 0x40000000UL;
    *id = v;
    *len = dlc;
    return CAN_OK;
  }

 private:
  SPIClass* spi_;
  INT8U cs_;

  void cmd_(std::initializer_list<INT8U> bytes) {
    spi_->beginTransaction(SPISettings(10000000, MSBFIRST, SPI_MODE0));
    for (INT8U x : bytes) spi_->transfer(x);
    spi_->endTransaction();
  }
  INT8U read_(INT8U reg) {
    spi_->beginTransaction(SPISettings(10000000, MSBFIRST, SPI_MODE0));
    spi_->transfer(0x03); spi_->transfer(reg);
    const INT8U v = spi_->transfer(0x00);
    spi_->endTransaction();
    return v;
  }
  void modify_(INT8U reg, INT8U mask, INT8U val) { cmd_({0x05, reg, mask, val}); }
  INT8U status_() {
    spi_->beginTransaction(SPISettings(10000000, MSBFIRST, SPI_MODE0));
    spi_->transfer(0xA0);
    const INT8U v = spi_->transfer(0x00);
    spi_->endTransaction();
    return v;
  }
  bool requestMode_(INT8U mode) {
    modify_(0x0F, 0xE0, mode);                           // CANCTRL REQOP
    return (read_(0x0E) & 0xE0) == mode;                 // CANSTAT OPMOD
  }
};
