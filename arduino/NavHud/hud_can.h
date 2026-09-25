// ---------------------------------------------------------------------------
//  hud_can.h -- the MCP2515, through the library, with a pre-flight of our own.
//
//  coryjfowler's MCP_CAN does the talking. What it does NOT do is tell you anything useful when it fails. It
//  returns CAN_FAILINIT and prints "Entering Configuration Mode Failure", which
//  covers "the chip is not powered", "MISO is on the wrong pin", "chip select
//  never arrives" and "the crystal is not oscillating" with one message. So
//  before the library is called at all, canBegin() asks the chip one question
//  itself and reports the raw answer, because the raw answer is the diagnosis:
//
//    0x80          CANSTAT after reset. The chip is there and talking.
//    0x00 always   MISO stuck low. Not wired to D6, no 3V3 at the module, or
//                  ground not shared with the ESP.
//    0xFF always   MISO floating high. Nothing is driving it: CS is not
//                  arriving, the chip has no power, or -- the one that cost
//                  this project months -- the DISPLAY is still holding the
//                  line because its SDO is wired and does not tri-state. See
//                  hud_pins.h.
//    anything else Something real is answering. The pre-flight does not try to
//                  judge it further -- the library is the better judge of that
//                  and is called next regardless.
//
//  Reading it four times separates a stuck line from an intermittent one.
//
//  FILTERS. The library is started with MCP_ANY, which its own header
//  documents as "Disables Masks and Filters". That is deliberate and it matters
//  more than it looks: listen-only mode does NOT bypass the acceptance filters.
//  A controller in listen-only with RXM left at 00 still filters, so a bus that
//  is talking constantly looks silent. MCP_ANY sets RXM to 11 on both buffers,
//  which is the setting that actually means "give me everything".
// ---------------------------------------------------------------------------
#ifndef HUD_CAN_H
#define HUD_CAN_H

#include <Arduino.h>
#include "hud_pins.h"
#include "hud_bus.h"
#include "hud_config.h"

#ifdef HUD_CAN

#include <mcp_can.h>

/** Why bring-up failed, so `status` can say something true. */
enum CanFault : uint8_t {
  CANFAULT_NONE = 0,
  CANFAULT_MISO_LOW,       // 0x00 from every read
  CANFAULT_MISO_HIGH,      // 0xFF from every read
  CANFAULT_LIB_INIT,       // chip answers, library could not configure it
  CANFAULT_LIB_MODE,       // configured, but would not enter listen-only
};

// Defined in hud_state.h, which the sketch includes AFTER this file. canPump()
// clears it when the controller stops answering, so the rest of the firmware
// stops believing a dead bus.
extern bool canOk;

static MCP_CAN canDev(&SPI, PIN_CAN_CS);
static CanFault canFault = CANFAULT_NONE;
static uint8_t  canProbeByte = 0;

/**
 * Ask the chip for CANSTAT ourselves, four times, slowly.
 *
 * 1 MHz on purpose. If the wiring is marginal this still works when 8 MHz does
 * not, and a probe that fails for the same reason as the thing it is probing
 * is worth nothing.
 */
static bool canPreflight_(uint8_t* seen) {
  SPI.begin();
  // SPI.begin() has just reset the clock to 1 MHz, the bit order to MSB and the
  // duplex bit back on. The display repairs its own settings on its next write
  // because SUPPORT_TRANSACTIONS is on -- see hud_bus.h. Without that this line
  // would leave the panel at 1 MHz for the rest of the session.
  for (uint8_t i = 0; i < 4; i++) {
    SPI.beginTransaction(SPISettings(1000000, MSBFIRST, CAN_SPI_MODE));
    digitalWrite(PIN_CAN_CS, LOW);
    SPI.transfer(0x03);            // READ
    SPI.transfer(0x0E);            // CANSTAT
    seen[i] = SPI.transfer(0x00);
    digitalWrite(PIN_CAN_CS, HIGH);
    SPI.endTransaction();
    delay(2);
  }
  bool same = true;
  for (uint8_t i = 1; i < 4; i++) if (seen[i] != seen[0]) same = false;
  canProbeByte = seen[0];

  if (same && seen[0] == 0x00) { canFault = CANFAULT_MISO_LOW;  return false; }
  if (same && seen[0] == 0xFF) { canFault = CANFAULT_MISO_HIGH; return false; }
  canFault = CANFAULT_NONE;
  // 0x80 is the documented reset value, but the chip may already be configured
  // from a previous run without a power cycle, so anything that is not one of
  // the two stuck patterns counts as "something real is answering".
  return true;
}

/**
 * Bring the controller up. Returns false and leaves canFault set.
 *
 * Listen-only, always. This taps K-CAN behind the instrument cluster on a car
 * that is driven on public roads; nothing here will ever transmit, and
 * listen-only is the mode where the chip physically cannot -- it does not even
 * send acknowledge bits, so a firmware bug cannot put a dominant level on a bus
 * that the airbag controller is also using.
 */
static bool canBegin() {
  canFault = CANFAULT_NONE;
  pinMode(PIN_CAN_CS, OUTPUT);
  digitalWrite(PIN_CAN_CS, HIGH);

  // The pre-flight is INFORMATIONAL ONLY. It used to return early and skip the
  // library entirely when it did not like what it saw on MISO, which meant my
  // own diagnostic could veto a controller the library might have brought up
  // perfectly well. A probe that can prevent the thing it is probing from
  // being tried is worse than no probe: it turns one of my mistakes into a
  // dead feature. So it records what it saw and gets out of the way.
  uint8_t seen[4];
  canPreflight_(seen);

  if (canDev.begin(MCP_ANY, CAN_BITRATE, CAN_XTAL) != CAN_OK) {
    // Only claim "the chip answers but would not configure" when the pre-flight
    // actually saw it answer. It sets MISO_LOW or MISO_HIGH when the line was
    // stuck, and that is a far more useful thing to print -- overwriting it
    // here sent every failure down the crystal trail, which is exactly the
    // wrong trail and cost weeks the last time.
    if (canFault == CANFAULT_NONE) canFault = CANFAULT_LIB_INIT;
    return false;
  }
  // LISTEN-ONLY, not MCP_NORMAL, and this one is not negotiable.
  //
  // The library's example uses MCP_NORMAL "so the MCP2515 sends acks to
  // received data" -- correct for a bench with two nodes and a deliberate
  // transmitter. This taps K-CAN on a car that gets driven on public roads,
  // sharing a bus with the cluster and the body modules. In listen-only the
  // chip physically cannot put a dominant level on the wire: no frames, not
  // even acknowledge bits. A firmware bug can then break the HUD and nothing
  // else. That is worth more than an ACK nobody is waiting for.
  if (canDev.setMode(MCP_LISTENONLY) != CAN_OK) {
    canFault = CANFAULT_LIB_MODE;
    return false;
  }
  canFault = CANFAULT_NONE;
  return true;
}

/** Human-readable, with the wire to check. */
static const char* canFaultText() {
  switch (canFault) {
    case CANFAULT_NONE:      return "ok";
    case CANFAULT_MISO_LOW:  return "MISO stuck LOW -- not on D6, no 3V3 at the module, or GND not shared";
    case CANFAULT_MISO_HIGH: return "MISO stuck HIGH -- nothing driving it: CS not arriving, no power at the MCP2515's own VDD (pin 18), or the display's SDO is still wired and holding the line";
    case CANFAULT_LIB_INIT:  return "the chip answers but would not configure -- crystal not oscillating, or CAN_XTAL is wrong for this module";
    case CANFAULT_LIB_MODE:  return "configured, but refused listen-only";
    default:                 return "unknown";
  }
}

/**
 * Frames the controller threw away because nobody collected them in time.
 *
 * EFLG bits 6 and 7 are RX0OVR and RX1OVR, and they LATCH -- the chip sets them
 * and never clears them itself. So this reads and clears them, and it must run
 * whether or not the phone is listening: gating it on a connected phone meant a
 * phone-free drive left the bits set, after which every later check saw the
 * same stale overflow and the count stopped counting.
 *
 * If this climbs on the road, the loop is too slow -- a full repaint is about
 * 185 ms and the two buffers hold roughly 2.2 ms of a busy bus -- and that is a
 * software problem, not a wiring one.
 */
static uint32_t canOverflowCount_ = 0;
static uint32_t canFrameCount_ = 0;

static uint32_t canOverflows() {
  // Straight at the register rather than through the library, which exposes
  // getError() but no way to clear the two overflow bits -- and a latched flag
  // you cannot clear is a flag that is true for ever after the first drop.
  //
  // EFLG is 0x2D. 0x03 is READ, 0x05 is BIT MODIFY (address, mask, data).
  const uint8_t EFLG = 0x2D, RXOVR = 0xC0;   // RX1OVR | RX0OVR

  busCanBegin();
  SPI.transfer(0x03); SPI.transfer(EFLG);
  const uint8_t eflg = SPI.transfer(0x00);
  busCanEnd();

  if (eflg & RXOVR) {
    canOverflowCount_++;
    busCanBegin();
    SPI.transfer(0x05); SPI.transfer(EFLG); SPI.transfer(RXOVR); SPI.transfer(0x00);
    busCanEnd();
  }
  return canOverflowCount_;
}

static uint32_t canFrameCount() { return canFrameCount_; }

/**
 * Drain the receive buffers.
 *
 * Bounded, and the bound is the point. The MCP2515 has two receive buffers;
 * at 100 kbit/s a full standard frame is about 1.1 ms, so the two of them hold
 * roughly 2.2 ms of a busy bus. A full repaint of this panel takes about
 * 185 ms. There is no way to not drop frames during a repaint, so the job here
 * is to never make it worse by sitting in this loop instead of returning to
 * draw -- which is why it takes at most CAN_DRAIN_MAX per call rather than
 * looping until empty. A bus that is talking faster than we can read is a bus
 * we fall behind on gracefully, not one that locks up the display.
 */
/**
 * Is the controller still there, and is the frame it is offering safe to read?
 *
 * Both questions are asked with raw SPI, before the library is allowed near the
 * receive buffer, and both exist because of the same defect in it.
 * mcp_can.cpp masks the DLC nibble to 4 bits and then reads that many bytes
 * into `INT8U m_nDta[8]`:
 *
 *     m_nDlc &= MCP_DLC_MASK;                                   // 0x0F, so 0..15
 *     mcp2515_readRegisterS(mcp_addr+5, &(m_nDta[0]), m_nDlc);  // writes up to 15
 *
 * Seven bytes past the end of the array, which on this object lands on the
 * `SPIClass*` it uses for every later transfer. The next register access then
 * dereferences whatever was written there. It also copies those 15 bytes into
 * the caller's 8-byte buffer.
 *
 * Two ways in. A DLC of 9..15 is legal on the wire -- the standard says a
 * receiver must treat it as 8 -- and this is a listen-only sniffer with no
 * acceptance filtering at all, so every id on a body bus reaches it. And if the
 * module loses power or a wire lifts mid-drive, MISO floats high, the status
 * byte reads 0xFF, `checkReceive()` says a frame is waiting on every single
 * call for ever, and the DLC reads 15 every time. That one takes a single loop
 * pass to fire.
 *
 * So: read the status register ourselves, and the flagged buffer's DLC, and
 * only hand the library a frame it can survive.
 */
#define CAN_CMD_READ_STATUS 0xA0
#define CAN_REG_RXB0DLC     0x65
#define CAN_REG_RXB1DLC     0x75
#define CAN_REG_CANINTF     0x2C

static uint8_t canRawRead_(uint8_t reg) {
  busCanBegin();
  SPI.transfer(0x03); SPI.transfer(reg);
  const uint8_t v = SPI.transfer(0x00);
  busCanEnd();
  return v;
}

static uint8_t canRawStatus_() {
  busCanBegin();
  SPI.transfer(CAN_CMD_READ_STATUS);
  const uint8_t v = SPI.transfer(0x00);
  busCanEnd();
  return v;
}

/** Drop a frame the library must not be allowed to read. */
static void canDiscard_(uint8_t buf) {
  busCanBegin();
  SPI.transfer(0x05); SPI.transfer(CAN_REG_CANINTF);
  SPI.transfer((uint8_t)(1 << buf)); SPI.transfer(0x00);
  busCanEnd();
}

static uint32_t canBadDlc_ = 0;
static uint32_t canLastPumpMs_ = 0;
static bool     canHavePumped_ = false;

template <typename CarT>
static uint8_t canPump(CarT& car, uint32_t now) {
  if (!canOk) return 0;

  // Frames are timestamped when they are DRAINED, not when they arrived -- there
  // is no interrupt wire, so there is no other choice. The controller holds two
  // of them, about 2.2 ms of this bus, and a full repaint takes about 185 ms.
  // After a gap that long the frames handed over are old while the timestamp is
  // new, and 0x1A6's speed is a difference over time: the last drive's numbers
  // would dip and overshoot by nearly 40 % around every screen transition.
  // A speed we cannot timestamp honestly is not a speed, so the baseline is
  // dropped and re-acquired on the next frame, one frame later.
  if (canHavePumped_ && (uint32_t)(now - canLastPumpMs_) > CAN_STALE_GAP_MS)
    carRebaseline(car);
  canLastPumpMs_ = now;
  canHavePumped_ = true;

  const uint8_t st = canRawStatus_();
  if (st == 0xFF) {
    // Nothing is driving MISO any more: the module lost power, or a wire lifted.
    // Left alone this is an infinite supply of 15-byte frames.
    canOk = false;
    canFault = CANFAULT_MISO_HIGH;
    return 0;
  }

  // Bounded by buffers HANDLED, not frames delivered. A refused length used to
  // `continue` without counting, so a module that lost power part-way through
  // this loop -- every status then reads 0xFF: "a frame is waiting", length 15
  // -- kept it discarding for ever, and the watchdog reset the board. The dead
  // module is caught by the status check above on the next call.
  uint8_t got = 0;
  for (uint8_t n = 0; n < CAN_DRAIN_MAX && canDev.checkReceive() == CAN_MSGAVAIL; n++) {
    const uint8_t intf = canRawRead_(CAN_REG_CANINTF);
    const uint8_t which = (intf & 0x01) ? 0 : ((intf & 0x02) ? 1 : 0xFF);
    if (which == 0xFF) break;                    // the flag cleared under us

    const uint8_t dlc = canRawRead_(which ? CAN_REG_RXB1DLC : CAN_REG_RXB0DLC) & 0x0F;
    if (dlc > 8) { canDiscard_(which); canBadDlc_++; continue; }

    INT32U id = 0;
    uint8_t len = 0, buf[8];
    if (canDev.readMsgBuf(&id, &len, buf) != CAN_OK) break;
    got++; canFrameCount_++;

    // The library packs flags into the top of the id: bit 31 is "extended", bit
    // 30 is "remote request". Truncating to 16 bits throws both away, so an RTR
    // for 0x1A6 -- which carries NO DATA -- used to arrive looking like a normal
    // speed frame and was decoded from whatever bytes were left in the buffer.
    if (id & 0xC0000000) continue;
    if (len > 8) continue;                        // belt and braces

    carFeed(car, (uint16_t)id, buf, len, now);
  }
  return got;
}

/** Frames refused for an impossible length. Should be 0 for ever. */
static uint32_t canBadDlc() { return canBadDlc_; }

#endif  // HUD_CAN
#endif  // HUD_CAN_H
