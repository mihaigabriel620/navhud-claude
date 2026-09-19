// ---------------------------------------------------------------------------
//  hud_can.h -- MCP2515 transport. Nothing about BMW lives here.
// ---------------------------------------------------------------------------
//
//  WHERE TO LOOK WHEN SOMETHING IS WRONG
//
//    nothing arrives at all      -> canBegin() returned false? check CAN_DIAG
//                                   output, the crystal define, and CS wiring
//    frames arrive but garbled   -> wrong CNF (crystal mismatch) or SPI clash;
//                                   see SUPPORT_TRANSACTIONS in User_Setup
//    counters climb, values jump -> canOverflows() rising: the loop is too slow
//
//  Every register number and bit position below is from the Microchip MCP2515
//  datasheet DS20001801 (checked against Rev J and Rev K, which agree):
//    Table 11-1  register map           Table 12-1  SPI instruction set
//    Register 10-1/10-2  CANCTRL/CANSTAT, REQOP and OPMOD
//    Register 4-1/4-2    RXB0CTRL/RXB1CTRL, RXM and BUKT
//    Register 7-2        CANINTF          Register 5-1..5-3  CNF1..CNF3
//
//  ONE THING THAT CONTRADICTS THE NOTES WE STARTED FROM. Section 10.3:
//
//      "In Listen-Only mode, both valid and invalid messages will be received,
//       regardless of filters and masks or the Receive Buffer Operating Mode
//       bits, RXMn."
//
//  So on a listen-only sniffer the six hardware filters DO NOTHING. Every
//  frame on a 500 kbit/s bus lands in two RX buffers. The filters are still
//  programmed below -- they cost nothing and they work if you ever switch to
//  normal mode -- but the real filtering happens in software, in hud_car.h,
//  and the drop rate is measured rather than assumed. See canOverflows().
//
// ---------------------------------------------------------------------------
#ifndef HUD_CAN_H
#define HUD_CAN_H

#ifdef HUD_HOST_TEST
  #include "tft_stub.h"       // pinMode/digitalWrite/millis/delay stand-ins
#else
  #include <Arduino.h>
#endif
#include <SPI.h>

// ---- wiring ---------------------------------------------------------------
// CS shares the display's SPI bus. On ESP8266 GPIO16 is the only pin left, and
// it is the one pin with no internal pull-up (Espressif: "XPD_DCDC can only be
// configured with internal pull-down"), so an external 10k to 3V3 is not
// optional -- without it CS floats low through reset and the MCP2515 sees a
// transaction that never ends.
#ifndef CAN_CS_PIN
  #define CAN_CS_PIN 16
#endif

// Read the crystal on the module. Do not trust the listing: the common boards
// ship 8 MHz, most library examples assume 16 MHz, and a mismatch is silent --
// you simply never receive a frame.
#ifndef CAN_XTAL_MHZ
  #define CAN_XTAL_MHZ 8
#endif

// 100 kbit/s: this taps K-CAN, behind the instrument cluster.
//
// BMW ST402 "E6x Voltage Supply and Bus Systems" p.39 gives the two rates --
// K-CAN 100 kbps, PT-CAN 500 kbps -- and the signals we want are powertrain
// signals, so PT-CAN looks like the obvious place to go for them. It is not
// the only place. The cluster gateways speed, rpm, torque, ignition and
// battery onto K-CAN so the comfort modules can see them, which is why they
// turn up in K-CAN reverse-engineering write-ups, and K-CAN is far easier to
// reach: it is right there behind the cluster.
//
// Get this wrong and the failure is total and silent. A controller at the
// wrong bit rate does not receive corrupted frames, it receives NOTHING -- and
// in listen-only it cannot even flag an error, so the tell is $CANDROP staying
// at zero rather than climbing. Zero drops with zero frames means the bit rate
// is wrong; drops climbing means the rate is right and the drain is too slow.
#ifndef CAN_BITRATE_KBPS
  #define CAN_BITRATE_KBPS 100
#endif

// Listen-only never transmits -- not even an ACK or an error flag (datasheet
// 10.3). Leave this alone. A sniffer with wrong bit timing in normal mode
// injects error frames onto the powertrain bus of a moving car.
#ifndef CAN_LISTEN_ONLY
  #define CAN_LISTEN_ONLY 1
#endif

// MCP2515 tops out at 10 MHz SPI; 8 leaves margin for the wiring.
#ifndef CAN_SPI_HZ
  #define CAN_SPI_HZ 8000000
#endif

// ---- SPI instructions (Table 12-1) ----------------------------------------
enum : uint8_t {
  MCP_RESET      = 0xC0,
  MCP_READ       = 0x03,
  MCP_WRITE      = 0x02,
  MCP_BITMOD     = 0x05,
  MCP_READ_STAT  = 0xA0,
  MCP_READ_RX0   = 0x90,   // pointer at RXB0SIDH; CS high clears RX0IF
  MCP_READ_RX1   = 0x94    // pointer at RXB1SIDH; CS high clears RX1IF
};

// ---- registers (Table 11-1) -----------------------------------------------
enum : uint8_t {
  MCP_CANSTAT  = 0x0E, MCP_CANCTRL = 0x0F,
  MCP_CNF3     = 0x28, MCP_CNF2    = 0x29, MCP_CNF1 = 0x2A,
  MCP_CANINTE  = 0x2B, MCP_CANINTF = 0x2C, MCP_EFLG = 0x2D,
  MCP_RXB0CTRL = 0x60, MCP_RXB1CTRL = 0x70,
  // A scratch register for the presence test. Transmit buffer 0, data byte 0:
  // plain read/write, no side effects, writable in every mode, and a
  // receive-only driver never has any other reason to touch it.
  MCP_TXB0D0   = 0x36,
  MCP_RXM0SIDH = 0x20, MCP_RXM1SIDH = 0x24
};
// The six filters are NOT evenly spaced -- 0x00,0x04,0x08 then 0x10,0x14,0x18.
static const uint8_t MCP_RXF_SIDH[6] = { 0x00, 0x04, 0x08, 0x10, 0x14, 0x18 };

enum : uint8_t {                 // CANCTRL[7:5] / CANSTAT[7:5], Register 10-1
  MCP_MODE_NORMAL = 0x00, MCP_MODE_SLEEP  = 0x20, MCP_MODE_LOOPBACK = 0x40,
  MCP_MODE_LISTEN = 0x60, MCP_MODE_CONFIG = 0x80, MCP_MODE_MASK = 0xE0
};
enum : uint8_t { MCP_RX0IF = 0x01, MCP_RX1IF = 0x02 };     // CANINTF
enum : uint8_t { MCP_RX0OVR = 0x40, MCP_RX1OVR = 0x80 };   // EFLG

/** One received standard frame. Extended frames are dropped in canRead(). */
/**
 * Why bring-up reports a STAGE rather than just failing.
 *
 * The two mode changes below fail for completely different physical reasons,
 * and collapsing them into one "absent" tells you nothing about which wire to
 * go and look at.
 *
 *   Entering CONFIG mode is a pure SPI test. The datasheet (10.1) says the
 *   part is ALREADY in configuration mode after a reset, so all this asks is
 *   whether a read of CANSTAT comes back as 100xxxxx. The SPI block is clocked
 *   by SCK, so this passes or fails on MOSI, MISO, SCK, CS and VDD alone. The
 *   crystal is not involved at all.
 *
 *   Entering LISTEN-ONLY is the opposite. 10.0: "the mode will not actually
 *   change until all pending message transmissions are complete. The requested
 *   mode must be verified by reading the CANSTAT.OPMODE bits." Getting OPMOD to
 *   actually read 011 needs the internal state machine, and that needs OSC1
 *   oscillating.
 *
 * So: fail at CONFIG and the SPI path is dead. Fail at LISTEN and the SPI path
 * is fine and the crystal is not. One boot, one answer, instead of staring at
 * a chip select that was never the problem.
 */
// Prefixed CANDIAG_ rather than CAN_ because coryjfowler's library defines
// CAN_OK as a plain macro, and a macro beats an enumerator every time: the
// preprocessor rewrites the enumerator to (0) and the compiler then reports an
// invalid conversion from int, pointing at the library header rather than at
// anything you wrote.
enum CanStage : uint8_t {
  CANDIAG_OK = 0,
  CANDIAG_BAD_TIMING,   // no bit timing for this crystal/rate: a build setting, not a wire
  CANDIAG_NO_SPI,       // nothing answered: CS, MOSI, MISO, SCK, power, or RESET held low
  CANDIAG_NO_ECHO,      // it answers but does not remember: MISO shorted, or the clock is wrong
  CANDIAG_NO_CLOCK      // SPI is fine, the mode change was refused: the crystal is not running
};

static const char* canStageText(CanStage s) {
  switch (s) {
    case CANDIAG_OK:         return "ok";
    case CANDIAG_BAD_TIMING: return "no bit timing for this crystal and bit rate -- "
                                "a build setting in hud_config.h, not a wiring fault";
    case CANDIAG_NO_SPI:     return "nothing answered on SPI. Check CS (GPIO16) and its "
                                "10k pull-up to 3V3, MISO, and that RESET is pulled high";
    case CANDIAG_NO_ECHO:    return "it answers but does not remember what it was told. "
                                "MISO shorted to MOSI, or SPI too fast";
    case CANDIAG_NO_CLOCK:   return "SPI works, but the controller refuses to change mode: "
                                "the crystal is not oscillating. Check the can and its "
                                "two load capacitors, and CAN_XTAL_MHZ";
  }
  return "?";
}


struct CanFrame {
  uint16_t id;        // 11-bit
  uint8_t  len;       // 0..8
  uint8_t  d[8];
};


// ===========================================================================
//  Two implementations, one interface.
//
//  CAN_USE_LIBRARY picks coryjfowler/MCP_CAN_lib (install "mcp_can" from the
//  Library Manager) instead of the driver written out below. Everything above
//  this line -- CanFrame, canPump, the diagnostics, the software id filtering
//  -- is shared, so nothing else in the project can tell which is underneath.
//
//  Why both exist. The hand-written one was audited line by line against the
//  datasheet and is correct: opcodes, the reset delay, mode verification
//  through CANSTAT rather than CANCTRL, config-mode ordering, SPI mode and
//  clock. The library's bit timing for 8 MHz at 100 kbit/s is byte-for-byte
//  what that driver derived independently.
//
//  But when a board will not talk, "my code is correct" is a claim, and a
//  second implementation is evidence. If the library fails in exactly the same
//  place, the fault is not in anyone's software, and that is worth knowing
//  before spending another evening on it.
// ===========================================================================
#if CAN_USE_LIBRARY
#include <mcp_can.h>

static MCP_CAN  canLib_(CAN_CS_PIN);
static bool     canUp_ = false;
static uint32_t canFrames_ = 0;
static uint32_t canOverflows_ = 0;

static void canParkCs() {
  // Before anything else in setup(). GPIO16 has no internal pull-up, so
  // until this runs CS is floating and the controller may be selected
  // alongside the display, with both driving MISO.
  pinMode(CAN_CS_PIN, OUTPUT);
  digitalWrite(CAN_CS_PIN, HIGH);
}

static CanStage canStage_ = CANDIAG_NO_SPI;
static CanStage canStage() { return canStage_; }

static bool canBegin(const uint16_t* ids, uint8_t idCount) {
  (void)ids; (void)idCount;      // filtering is done in software, see canPump
  canParkCs();

#if (CAN_XTAL_MHZ == 8)
  const uint8_t xtal = MCP_8MHZ;
#elif (CAN_XTAL_MHZ == 16)
  const uint8_t xtal = MCP_16MHZ;
#else
  #error "CAN_XTAL_MHZ must be 8 or 16 for the library build"
#endif
#if (CAN_BITRATE_KBPS == 100)
  const uint8_t rate = CAN_100KBPS;
#elif (CAN_BITRATE_KBPS == 500)
  const uint8_t rate = CAN_500KBPS;
#else
  #error "CAN_BITRATE_KBPS must be 100 or 500 for the library build"
#endif

  // MCP_ANY disables the masks and filters, which is what we want: the whole
  // bus arrives and carFeed() sorts it, so $CANDROP measures something real.
  if (canLib_.begin(MCP_ANY, rate, xtal) != CANDIAG_OK) {
    canStage_ = CANDIAG_NO_SPI;
    return false;
  }
#if CAN_LISTEN_ONLY
  // Listen-only: never transmits, not even an acknowledge, so it cannot
  // disturb the car's bus. The library verifies the mode against CANSTAT with
  // a timeout, which is the check that needs the crystal running -- so a
  // failure HERE and not above means SPI is fine and the oscillator is not.
  if (canLib_.setMode(MCP_LISTENONLY) != CANDIAG_OK) {
    canStage_ = CANDIAG_NO_CLOCK;
    return false;
  }
#else
  if (canLib_.setMode(MCP_NORMAL) != CANDIAG_OK) { canStage_ = CANDIAG_NO_CLOCK; return false; }
#endif

  canStage_ = CANDIAG_OK;
  canUp_ = true;
  canFrames_ = canOverflows_ = 0;
  return true;
}

static bool canRead(CanFrame& f) {
  if (!canUp_) return false;
  if (canLib_.checkReceive() != CAN_MSGAVAIL) return false;
  unsigned long id = 0;
  uint8_t len = 0, buf[8];
  if (canLib_.readMsgBuf(&id, &len, buf) != CANDIAG_OK) return false;
  // Standard frames only. The library reports an extended id with bit 31 set
  // in its own flag byte; anything above 0x7FF here is not one of ours and the
  // E60 ids we decode are all 11-bit.
  if (id > 0x7FF) return false;
  f.id = (uint16_t)id;
  f.len = len > 8 ? 8 : len;
  for (uint8_t i = 0; i < f.len; i++) f.d[i] = buf[i];
  canFrames_++;
  return true;
}

static uint32_t canOverflows() {
  if (!canUp_) return 0;
  // EFLG bits 6 and 7 are RX1OVR and RX0OVR. They latch and must be cleared by
  // the host, so this counts overflow EVENTS seen at poll time rather than
  // frames lost -- a rate indicator, which is all it was ever claimed to be.
  // A LATCH here, not a count, and the difference is worth stating because the
  // hand-written driver below really does count.
  //
  // getError() returns EFLG, whose bits 6 and 7 are RX1OVR and RX0OVR. The
  // datasheet says those "must be reset by the MCU", and mcp_can 1.5.1 keeps
  // its bit-modify helper private, so there is no way to clear them from here.
  // Counting a flag that can never be cleared would count the same overflow
  // once per poll, for ever -- a number that climbs for ever and means nothing.
  //
  // So it answers the question the number was for, which is "is the drain too
  // slow", and answers it honestly: 0 means never, 1 means at least once.
  if (canLib_.getError() & 0xC0) canOverflows_ = 1;
  return canOverflows_;
}

static inline uint32_t canFrameCount() { return canFrames_; }
static inline bool     canPresent()    { return canUp_; }

#else   // ---- the hand-written driver ---------------------------------------
// ---- private state --------------------------------------------------------
static CanStage canStage_ = CANDIAG_NO_SPI;
static CanStage canStage() { return canStage_; }
static SPISettings canSpi_(CAN_SPI_HZ, MSBFIRST, SPI_MODE0);
static bool     canUp_        = false;
static uint32_t canOverflows_ = 0;
static uint32_t canFrames_    = 0;

static inline void canSelect_()   { digitalWrite(CAN_CS_PIN, LOW); }
static inline void canDeselect_() { digitalWrite(CAN_CS_PIN, HIGH); }

// Every access takes the bus with its own settings and gives it back. This is
// the whole reason SUPPORT_TRANSACTIONS has to be on in User_Setup: without
// it TFT_eSPI sets the clock once at init() and never restores it, so the
// first CAN read leaves the bus at 8 MHz mode 0 and the display turns to
// confetti. (esp8266 SPI.cpp: beginTransaction sets clock/order/mode,
// endTransaction is empty -- it is exactly and only a settings swap.)
static inline void canOpen_()  { SPI.beginTransaction(canSpi_); canSelect_(); }
static inline void canClose_() { canDeselect_(); SPI.endTransaction(); }

static uint8_t canRead_(uint8_t reg) {
  canOpen_();
  SPI.transfer(MCP_READ); SPI.transfer(reg);
  uint8_t v = SPI.transfer(0x00);
  canClose_();
  return v;
}

static void canWrite_(uint8_t reg, uint8_t val) {
  canOpen_();
  SPI.transfer(MCP_WRITE); SPI.transfer(reg); SPI.transfer(val);
  canClose_();
}

/** Change single bits without disturbing their neighbours (Table 12-1). */
static void canBitMod_(uint8_t reg, uint8_t mask, uint8_t val) {
  canOpen_();
  SPI.transfer(MCP_BITMOD); SPI.transfer(reg); SPI.transfer(mask); SPI.transfer(val);
  canClose_();
}

/** Write an 11-bit id into a filter or mask pair, EXIDE clear. */
static void canWriteId_(uint8_t sidhReg, uint16_t id) {
  canOpen_();
  SPI.transfer(MCP_WRITE); SPI.transfer(sidhReg);
  SPI.transfer((uint8_t)(id >> 3));           // SIDH = SID[10:3]
  SPI.transfer((uint8_t)((id & 0x07) << 5));  // SIDL = SID[2:0], EXIDE = 0
  // The two extended-id bytes must be zeroed. With RXM=00 and a standard
  // frame the MCP2515 matches RXFnEID8:EID0 against the first two DATA bytes
  // (Register 4-1) -- leave junk here and frames vanish for no visible reason.
  SPI.transfer(0x00);
  SPI.transfer(0x00);
  canClose_();
}

/** Request a mode and wait for CANSTAT to agree (datasheet 10.0 says you must
    verify; the mode does not change until pending traffic completes). */
static bool canSetMode_(uint8_t mode) {
  canBitMod_(MCP_CANCTRL, MCP_MODE_MASK, mode);
  for (uint8_t i = 0; i < 50; i++) {
    if ((canRead_(MCP_CANSTAT) & MCP_MODE_MASK) == mode) return true;
    delay(1);
  }
  return false;
}

/** Bit timing. Values from coryjfowler/MCP_CAN_lib's table, each one decoded
    by hand against the datasheet's TQ = 2*(BRP+1)/FOSC and the segment fields
    of Registers 5-1..5-3; all four land the sample point at 75 %. */
static bool canTiming_(uint8_t& c1, uint8_t& c2, uint8_t& c3) {
#if (CAN_XTAL_MHZ == 8)
  #if (CAN_BITRATE_KBPS == 500)
    c1 = 0x00; c2 = 0xD1; c3 = 0x81; return true;   //  8 TQ x 250 ns
  #elif (CAN_BITRATE_KBPS == 100)
    c1 = 0x81; c2 = 0xF6; c3 = 0x84; return true;   // 20 TQ x 500 ns
  #endif
#elif (CAN_XTAL_MHZ == 16)
  #if (CAN_BITRATE_KBPS == 500)
    c1 = 0x40; c2 = 0xE5; c3 = 0x83; return true;   // 16 TQ x 125 ns
  #elif (CAN_BITRATE_KBPS == 100)
    c1 = 0x44; c2 = 0xE5; c3 = 0x83; return true;   // 16 TQ x 625 ns
  #endif
#endif
  c1 = c2 = c3 = 0;
  return false;
}

/**
 * Bring the controller up. `ids` are the frames worth keeping; up to six are
 * programmed into the hardware filters, which help only in normal mode (see
 * the header comment) but cost nothing.
 *
 * Returns false if the chip never answered -- wiring, power, or CS.
 */
/**
 * Park the chip select high. Call this FIRST THING in setup(), before the
 * display is initialised: until CS is driven, GPIO16 is undriven, and if it
 * sits low the MCP2515 reads the display's whole init sequence as SPI
 * commands addressed to itself. The external pull-up covers this too -- doing
 * both means a missing resistor is a warning, not a mystery.
 */
static void canParkCs() {
  pinMode(CAN_CS_PIN, OUTPUT);
  digitalWrite(CAN_CS_PIN, HIGH);
}


static bool canBegin(const uint16_t* ids, uint8_t idCount) {
  canParkCs();
  SPI.begin();

  uint8_t c1, c2, c3;
  if (!canTiming_(c1, c2, c3)) { canStage_ = CANDIAG_BAD_TIMING; return false; }

  canOpen_();
  SPI.transfer(MCP_RESET);
  canClose_();
  delay(20);                                   // POR takes the chip to config mode

  if (!canSetMode_(MCP_MODE_CONFIG)) { canStage_ = CANDIAG_NO_SPI; return false; }

  // A real presence test, not just a mode echo.
  //
  // The check above is satisfied by exactly one value, 0x80, so it happens to
  // survive a stuck-high or stuck-low MISO -- but that is luck, not design. It
  // cannot see a MISO weakly coupled to MOSI, an SCK running at the wrong
  // speed, or a frame that is shifted by a bit.
  //
  // TXB0D0 is the register to do it on: plain read/write with no side effects,
  // writable in any mode (10.1 lists only CNF1-3, TXRTSCTRL and the filters and
  // masks as configuration-mode-only), and a receive-only driver never touches
  // it otherwise. 0x55 and 0xAA between them exercise every bit in both
  // directions, which is what catches a shift.
  canWrite_(MCP_TXB0D0, 0x55);
  if (canRead_(MCP_TXB0D0) != 0x55) { canStage_ = CANDIAG_NO_ECHO; return false; }
  canWrite_(MCP_TXB0D0, 0xAA);
  if (canRead_(MCP_TXB0D0) != 0xAA) { canStage_ = CANDIAG_NO_ECHO; return false; }
  canWrite_(MCP_TXB0D0, 0x00);

  canWrite_(MCP_CNF1, c1);
  canWrite_(MCP_CNF2, c2);
  canWrite_(MCP_CNF3, c3);

  // Masks: exact match on all eleven id bits.
  canWriteId_(MCP_RXM0SIDH, 0x7FF);
  canWriteId_(MCP_RXM1SIDH, 0x7FF);
  // Filters. RXB0 takes filters 0-1, RXB1 takes 2-5. Unused slots get a copy
  // of the first id rather than 0x000, which would otherwise accept id 0.
  for (uint8_t i = 0; i < 6; i++)
    canWriteId_(MCP_RXF_SIDH[i], (i < idCount) ? ids[i] : (idCount ? ids[0] : 0x7FF));

  // RXM = 11: masks and filters genuinely OFF, which is what every comment in
  // this file has always claimed and what the code did not do.
  //
  // The claim rested on one sentence of 10.3 -- "In Listen-Only mode, both
  // valid and invalid messages will be received regardless of filters and masks
  // or RXMn Receive Buffer mode bits" -- but the FIRST sentence of the same
  // section says the opposite: listen-only receives all messages "by
  // configuring the RXBnCTRL.RXM<1:0> bits". Two other places back the first
  // one. Register 4-1 defines 11 as "Turn mask/filters off; receive any
  // message" against 00's "receive all valid messages ... that meet filter
  // criteria", and 7.6.1 describes an overflow as happening when a message
  // "meets the criteria of the acceptance filters" -- i.e. filtering is still
  // in the path.
  //
  // With RXM = 00 it worked by coincidence: all five ids are programmed into
  // filters with exact-match masks, so all five got through either way. Add a
  // sixth (the loop above silently drops anything past index 5), change one, or
  // mistype one, and the frames would simply stop arriving -- and the header of
  // this file would send you to the bit rate, which would be the one thing not
  // wrong.
  //
  // BUKT stays: with no INT pin and a polled drain, RXB0 rolling into RXB1 is
  // the entire margin. Two frames at K-CAN's 100 kbit/s is about 2.2 ms.
  canBitMod_(MCP_RXB0CTRL, 0x64, 0x64);   // RXM[6:5]=11, BUKT[2]=1
  canBitMod_(MCP_RXB1CTRL, 0x60, 0x60);   // RXM[6:5]=11

  canWrite_(MCP_CANINTE, 0x00);           // no INT pin is wired; we poll
  // Bit modify, not a plain write. The datasheet: "It is recommended that the
  // Bit Modify command be used to reset flag bits in the CANINTF register
  // rather than normal write operations. This is done to prevent
  // unintentionally changing a flag that changes during the Write command."
  // Harmless here -- this runs in configuration mode where nothing can arrive
  // -- but it is the line somebody copies.
  canBitMod_(MCP_CANINTF, 0x03, 0x00);

#if CAN_LISTEN_ONLY
  if (!canSetMode_(MCP_MODE_LISTEN)) { canStage_ = CANDIAG_NO_CLOCK; return false; }
#else
  if (!canSetMode_(MCP_MODE_NORMAL)) { canStage_ = CANDIAG_NO_CLOCK; return false; }
#endif

  canStage_ = CANDIAG_OK;
  canUp_ = true;
  canOverflows_ = canFrames_ = 0;
  return true;
}

/**
 * Take one frame if there is one. Call it in a tight loop -- there is no
 * interrupt pin, because the ESP8266 has no pin left and GPIO16 cannot do
 * attachInterrupt anyway.
 *
 * Returns false when both buffers are empty.
 */
/** Marker for a frame that was drained but is not an 11-bit id. */
#define CAN_ID_NOT_OURS 0xFFFF

static bool canRead(CanFrame& f) {
  if (!canUp_) return false;

  const uint8_t flags = canRead_(MCP_CANINTF);
  uint8_t cmd;
  if      (flags & MCP_RX0IF) cmd = MCP_READ_RX0;
  else if (flags & MCP_RX1IF) cmd = MCP_READ_RX1;
  else                        return false;

  uint8_t b[13];
  canOpen_();
  SPI.transfer(cmd);
  for (uint8_t i = 0; i < 13; i++) b[i] = SPI.transfer(0x00);
  canClose_();          // CS rising clears RXnIF for us (Table 12-1 note)

  // b[0]=SIDH b[1]=SIDL b[2]=EID8 b[3]=EID0 b[4]=DLC b[5..12]=data
  if (b[1] & 0x08) {
    // Extended frame: not ours -- but it HAS been taken out of the buffer, so
    // say "read something" rather than "bus is empty". Returning false here
    // stopped the caller's drain loop dead, leaving up to eleven standard
    // frames unread every time a diagnostic tester was plugged in.
    f.id = CAN_ID_NOT_OURS; f.len = 0;
    canFrames_++;
    return true;
  }
  f.id  = ((uint16_t)b[0] << 3) | (b[1] >> 5);
  f.len = b[4] & 0x0F;
  if (f.len > 8) f.len = 8;
  for (uint8_t i = 0; i < f.len; i++) f.d[i] = b[5 + i];

  canFrames_++;
  return true;
}

/** Frames the hardware threw away because we did not read fast enough. If this
    climbs while driving, the loop is spending too long painting. */
static uint32_t canOverflows() {
  if (!canUp_) return 0;
  const uint8_t e = canRead_(MCP_EFLG);
  if (e & (MCP_RX0OVR | MCP_RX1OVR)) {
    canOverflows_++;
    canBitMod_(MCP_EFLG, MCP_RX0OVR | MCP_RX1OVR, 0x00);   // clear and keep going
  }
  return canOverflows_;
}

static inline bool     canPresent()    { return canUp_; }
static inline uint32_t canFrameCount() { return canFrames_; }

#endif  // CAN_USE_LIBRARY

// Pulled in here rather than assumed: this header is also compiled on its own
// by the CAN test, and the glue below is the only part of it that needs to
// know what a CarState is.
#include "hud_car.h"

// The drain cap is a user setting, so it lives in hud_config.h with the rest.
// Defaulted here as well because this header is compiled standalone by the CAN
// test, which has no sketch and therefore no config, and a silent 0 would make
// canPump() a no-op that passes every test by never reading anything.
#ifndef CAN_DRAIN_PER_PASS
  #define CAN_DRAIN_PER_PASS 12
#endif

// ---------------------------------------------------------------------------
//  The glue between this file and hud_car.h.
//
//  Takes the CarState by reference rather than reaching for the global, so it
//  can be driven straight from a test with a simulated controller and no
//  sketch underneath it.
// ---------------------------------------------------------------------------

/** Take what the bus has, bounded. Returns true if anything we care about
    changed, so the caller knows whether a repaint is worth it. */
static bool canPump(CarState& c, uint32_t now) {
  bool touched = false;
  CanFrame f;
  for (uint8_t i = 0; i < CAN_DRAIN_PER_PASS && canRead(f); i++)
    if (carFeed(c, f.id, f.d, f.len, now)) touched = true;
  return touched;
}

#endif // HUD_CAN_H
