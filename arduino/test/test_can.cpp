// Host tests for the CAN half: the MCP2515 driver against a scripted SPI
// slave, and the E60 decoders against hand-built frames.
//
// This is the code with the least chance of being verified any other way --
// the register writes go into a chip I cannot see, and the decoders turn bytes
// into numbers that end up in front of a driver. So they get tested here.
#include "tft_stub.h"
#include "SPI.h"
SPIClass SPI;

#include <cstdio>
#include <cstring>
#include "hud_can.h"
#include "hud_car.h"

static int fails = 0;
#define CHECK(cond, ...) do { if (!(cond)) { \
  printf("  FAIL %s:%d  ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); fails++; } } while (0)

static void testBringUp() {
  printf("MCP2515 bring-up\n");
  SPI.reset();
  bool ok = canBegin(CAR_IDS, CAR_ID_COUNT);
  CHECK(ok, "bring-up should succeed against a working controller");
  CHECK(SPI.resets == 1, "the chip must be reset exactly once, saw %d", SPI.resets);
  CHECK(SPI.open == 0, "every transaction must be closed, %d left open", SPI.open);
  CHECK(SPI.maxOpen <= 1, "transactions must not nest on a shared bus");
  CHECK(SPI.clock <= 10000000u, "MCP2515 tops out at 10 MHz, driver asked for %u", SPI.clock);

  // ---- bit timing, the one thing a crystal mismatch silently breaks ------
  //
  // Pinned against a second, independent source: coryjfowler/MCP_CAN_lib's
  // published tables (mcp_can_dfs.h). Those bytes were arrived at separately
  // from ours -- ours were decoded by hand from the datasheet's
  // TQ = 2*(BRP+1)/FOSC and the segment fields of Registers 5-1..5-3 -- and
  // they agree exactly. Two derivations landing on the same four bytes is
  // worth more than either one alone, because this is the number that fails
  // silently: a controller at the wrong bit rate does not receive corrupted
  // frames, it receives NOTHING, and $CANDROP sits at zero looking healthy.
  //
  // Every combination is asserted, and the Makefile builds all four, because
  // an assertion behind an #if for a configuration nobody compiles is not a
  // test. This block used to cover only 8 MHz / 500 kbit/s, and when the
  // default moved to K-CAN's 100 kbit/s it silently stopped running at all.
#if   (CAN_XTAL_MHZ == 8)  && (CAN_BITRATE_KBPS == 500)
  const uint8_t w1 = 0x00, w2 = 0xD1, w3 = 0x81;   //  8 TQ x 250 ns
#elif (CAN_XTAL_MHZ == 8)  && (CAN_BITRATE_KBPS == 100)
  const uint8_t w1 = 0x81, w2 = 0xF6, w3 = 0x84;   // 20 TQ x 500 ns, K-CAN
#elif (CAN_XTAL_MHZ == 16) && (CAN_BITRATE_KBPS == 500)
  const uint8_t w1 = 0x40, w2 = 0xE5, w3 = 0x83;   // 16 TQ x 125 ns
#elif (CAN_XTAL_MHZ == 16) && (CAN_BITRATE_KBPS == 100)
  const uint8_t w1 = 0x44, w2 = 0xE5, w3 = 0x83;   // 16 TQ x 625 ns
#else
  #error "No pinned bit timing for this crystal and bit rate. Add one, with the arithmetic, before shipping it."
#endif
  CHECK(SPI.reg[MCP_CNF1] == w1, "CNF1 = 0x%02X for %dk/%dMHz, got 0x%02X",
        w1, (int)CAN_BITRATE_KBPS, (int)CAN_XTAL_MHZ, SPI.reg[MCP_CNF1]);
  CHECK(SPI.reg[MCP_CNF2] == w2, "CNF2 = 0x%02X for %dk/%dMHz, got 0x%02X",
        w2, (int)CAN_BITRATE_KBPS, (int)CAN_XTAL_MHZ, SPI.reg[MCP_CNF2]);
  CHECK(SPI.reg[MCP_CNF3] == w3, "CNF3 = 0x%02X for %dk/%dMHz, got 0x%02X",
        w3, (int)CAN_BITRATE_KBPS, (int)CAN_XTAL_MHZ, SPI.reg[MCP_CNF3]);

  // And the arithmetic itself, so a byte that matches the library but means
  // the wrong thing still fails. CNF1[5:0] is BRP-1, CNF2[2:0] is PRSEG-1,
  // CNF2[5:3] is PHSEG1-1, CNF3[2:0] is PHSEG2-1; a bit is 1 + PRSEG + PHSEG1
  // + PHSEG2 quanta and a quantum is 2*(BRP+1)/FOSC.
  {
    const int brp    = (w1 & 0x3F) + 1;
    const int prseg  = (w2 & 0x07) + 1;
    const int phseg1 = ((w2 >> 3) & 0x07) + 1;
    const int phseg2 = (w3 & 0x07) + 1;
    const int tq     = 1 + prseg + phseg1 + phseg2;
    const long bps   = (long)(CAN_XTAL_MHZ * 1000000L) / (2L * brp * tq);
    const int  sample = (100 * (1 + prseg + phseg1)) / tq;
    CHECK(bps == CAN_BITRATE_KBPS * 1000L,
          "%d TQ at BRP %d on a %d MHz crystal is %ld bit/s, wanted %ld",
          tq, brp, (int)CAN_XTAL_MHZ, bps, CAN_BITRATE_KBPS * 1000L);
    CHECK(sample >= 70 && sample <= 80,
          "sample point %d%% is outside 70-80%%, which is where a car bus wants it",
          sample);
    printf("  %3dk/%2dMHz: %2d TQ, BRP %d, sample %d%%  (CNF %02X %02X %02X)\n",
           (int)CAN_BITRATE_KBPS, (int)CAN_XTAL_MHZ, tq, brp, sample, w1, w2, w3);
  }

  // ---- the mode it ends up in ------------------------------------------
#if CAN_LISTEN_ONLY
  CHECK((SPI.reg[MCP_CANCTRL] & MCP_MODE_MASK) == MCP_MODE_LISTEN,
        "must end in listen-only so it can never transmit on a live bus");
#endif

  // ---- receive path setup ----------------------------------------------
  CHECK((SPI.reg[MCP_RXB0CTRL] & 0x04) != 0, "BUKT must be set: RXB0 rolls into RXB1");
  // RXM = 11, masks and filters genuinely off. 00 leaves them ON, which is what
  // this used to assert -- and it passed, because the simulator had no filter
  // model at all. It worked on the car by coincidence: all five ids were
  // programmed into filters with exact-match masks, so all five arrived either
  // way. Add a sixth, or change one, and they would simply have stopped.
  CHECK((SPI.reg[MCP_RXB0CTRL] & 0x60) == 0x60, "RXB0 RXM must be 11: filters off");
  CHECK((SPI.reg[MCP_RXB1CTRL] & 0x60) == 0x60, "RXB1 RXM must be 11: filters off");

  // The behaviour that setting actually buys, checked rather than asserted.
  // An id that is NOT one of ours must still reach the driver, because software
  // is where the sorting happens and $CANDROP only means anything if the
  // hardware is handing us the whole bus.
  {
    const uint8_t junk[8] = { 1, 2, 3, 4, 5, 6, 7, 8 };
    SPI.filtered = 0;
    SPI.deliver(false, 0x3FE, junk, 8);        // an id in no filter anywhere
    CHECK(SPI.filtered == 0, "a frame we do not want must still be delivered");
    CanFrame f;
    CHECK(canRead(f) && f.id == 0x3FE,
          "...and must reach the driver, which then ignores it in software");
  }
  CHECK(SPI.reg[MCP_CANINTE] == 0x00, "no INT pin is wired, so no interrupts enabled");

  // ---- masks and filters -----------------------------------------------
  CHECK(SPI.reg[MCP_RXM0SIDH] == 0xFF && (SPI.reg[MCP_RXM0SIDH + 1] & 0xE0) == 0xE0,
        "mask 0 should be an exact match on all eleven id bits");
  // 0x0A8 -> SIDH 0x15, SIDL 0x00
  CHECK(SPI.reg[MCP_RXF_SIDH[0]] == (CAR_IDS[0] >> 3),
        "filter 0 should carry the first id we asked for");
  // The extended-id filter bytes MUST be zero: with RXM=00 and a standard
  // frame the chip matches them against the first two DATA bytes, and junk
  // there makes frames vanish for no visible reason (Register 4-1).
  for (int i = 0; i < 6; i++) {
    CHECK(SPI.reg[MCP_RXF_SIDH[i] + 2] == 0x00 && SPI.reg[MCP_RXF_SIDH[i] + 3] == 0x00,
          "filter %d extended bytes must be zeroed", i);
    CHECK((SPI.reg[MCP_RXF_SIDH[i] + 1] & 0x08) == 0x00,
          "filter %d EXIDE must be clear: we want standard frames", i);
  }
}

static void testFrameParse() {
  printf("frame parse\n");
  SPI.reset();
  canBegin(CAR_IDS, CAR_ID_COUNT);
  const uint8_t payload[8] = { 0x10, 0x27, 0, 0, 0, 0, 0, 0 };
  SPI.deliver(false, 0x1A6, payload, 8);

  CanFrame f;
  CHECK(canRead(f), "a frame was waiting and should have been read");
  CHECK(f.id == 0x1A6, "id should be 0x1A6, got 0x%03X", f.id);
  CHECK(f.len == 8, "dlc should be 8, got %u", f.len);
  CHECK(f.d[0] == 0x10 && f.d[1] == 0x27, "payload should survive intact");

  // An extended frame is drained but is not ours. It must NOT report "bus
  // empty" -- that stops the caller's drain loop with frames still queued.
  SPI.deliver(false, 0x1A6, payload, 8, /*extended=*/true);
  CHECK(canRead(f), "an extended frame is still a frame taken off the bus");
  CHECK(f.id == CAN_ID_NOT_OURS, "...but it must be marked as not ours");
  {
    CarState c; CarState before = c;
    CHECK(!carFeed(c, f.id, f.d, f.len, 1000), "and the decoder must refuse it");
    CHECK(memcmp(&before, &c, sizeof c) == 0, "...without touching the state");
  }

  CHECK(!canRead(f), "with both buffers empty there is no frame");

  // The rollover buffer has to be read too, or half the traffic is invisible.
  const uint8_t p2[2] = { 0xAA, 0xBB };
  SPI.deliver(true, 0x0AA, p2, 2);
  CHECK(canRead(f), "a frame in RXB1 must be read as well as one in RXB0");
  CHECK(f.id == 0x0AA, "id from RXB1 should be 0x0AA, got 0x%03X", f.id);
}

static void testIgnition() {
  printf("0x130 ignition\n");
  CarState c;
  uint8_t d[1];
  d[0] = 0x00; carFeed(c, CAR_ID_IGNITION, d, 1, 1000);
  CHECK(!c.ignitionOn, "key removed is off");
  d[0] = 0x41; carFeed(c, CAR_ID_IGNITION, d, 1, 1010);
  CHECK(!c.ignitionOn, "0x41 is position 1 / engine stopped, still off");
  d[0] = 0x55; carFeed(c, CAR_ID_IGNITION, d, 1, 1020);
  CHECK(c.ignitionOn && c.cranking, "0x55 is cranking: on, and flagged");
  d[0] = 0x45; carFeed(c, CAR_ID_IGNITION, d, 1, 1030);
  CHECK(c.ignitionOn && !c.cranking, "running");

  // peak must reset across an ignition cycle
  c.peakPs = 200;
  d[0] = 0x00; carFeed(c, CAR_ID_IGNITION, d, 1, 2000);
  d[0] = 0x45; carFeed(c, CAR_ID_IGNITION, d, 1, 2010);
  CHECK(c.peakPs == 0, "peak PS holds until the ignition cycles, then clears");
  CHECK(!c.haveLast, "and the speed baseline must be dropped on that edge");

  // displayOn is a LOWER bar than ignitionOn, and 0x41 is the one value where
  // they disagree: engine off with the key at position 1 is a driver sitting
  // in the car, so the panel is lit while the car data view is not.
  d[0] = 0x00; carFeed(c, CAR_ID_IGNITION, d, 1, 3000);
  CHECK(!c.displayOn, "no key: dark");
  d[0] = 0x40; carFeed(c, CAR_ID_IGNITION, d, 1, 3010);
  CHECK(!c.displayOn, "key going in, not yet turned: still dark");
  d[0] = 0x41; carFeed(c, CAR_ID_IGNITION, d, 1, 3020);
  CHECK(c.displayOn && !c.ignitionOn,
        "position 1: panel lit, but the engine is not running");
  d[0] = 0x45; carFeed(c, CAR_ID_IGNITION, d, 1, 3030);
  CHECK(c.displayOn && c.ignitionOn, "running: both");
  d[0] = 0x55; carFeed(c, CAR_ID_IGNITION, d, 1, 3040);
  CHECK(c.displayOn, "cranking must not blink the panel off");
}

static void testSpeed() {
  printf("0x1A6 speed\n");
  CarState c;
  uint8_t d[2];
  auto feed = [&](uint16_t cnt, uint32_t t) {
    d[0] = (uint8_t)(cnt & 0xFF); d[1] = (uint8_t)(cnt >> 8);
    carFeed(c, CAR_ID_SPEED, d, 2, t);
  };
  feed(1000, 1000);
  CHECK(carStale(c.tSpeed, 1000), "the first frame is a baseline, not a speed");

  // 100 ticks in 100 ms -> 1 tick/ms * 80.4672 = 80.5 km/h
  feed(1100, 1100);
  CHECK(c.kmh > 80.0f && c.kmh < 81.0f, "expected ~80.5 km/h, got %.2f", c.kmh);

  // the 16-bit counter wrapping must not produce a spike
  c.lastCnt = 65500; c.lastCntMs = 2000; c.haveLast = true; c.kmh = 80.0f;
  feed(36, 2100);            // 65500 -> 36 is a wrap of 72 ticks
  CHECK(c.kmh > 57.0f && c.kmh < 58.0f, "wrap should read ~57.9 km/h, got %.2f", c.kmh);

  // a gap longer than the window is not a speed, but must still re-baseline
  c.haveLast = false; c.kmh = 50.0f; c.tSpeed = 3000;
  feed(1000, 3000); feed(60000, 8000);
  CHECK(c.kmh == 50.0f, "a 5 s gap must be discarded, not turned into a number");
  CHECK(c.lastCnt == 60000, "...but the baseline still advances");

  // and an implausible delta is refused outright
  c.haveLast = true; c.lastCnt = 0; c.lastCntMs = 9000; c.kmh = 10.0f;
  feed(60000, 9100);
  CHECK(c.kmh == 10.0f, "a 48000 km/h delta must not reach the glass");
}

static void testRpmTorqueVolts() {
  printf("0x0AA rpm, 0x0A8 torque, 0x3B4 battery\n");
  CarState c;
  uint8_t d[8] = {0};
  // rpm: bytes 4-5 little-endian, /4.  2400 rpm -> raw 9600 -> 0x2580
  d[4] = 0x80; d[5] = 0x25;
  carFeed(c, CAR_ID_RPM, d, 8, 1000);
  CHECK(c.rpm == 2400, "expected 2400 rpm, got %u", c.rpm);

  // torque: 12 bits at bit 12, 0.5 Nm/bit. 300 Nm -> raw 600 -> 0x258
  memset(d, 0, sizeof d);
  d[1] = 0x80; d[2] = 0x25;          // low nibble of d1 is a counter: ignored
  carFeed(c, CAR_ID_TORQUE, d, 8, 1010);
  CHECK(c.torqueNm == 300, "expected 300 Nm, got %d", c.torqueNm);
  CHECK(c.ps == 103, "300 Nm at 2400 rpm is 102.5 PS, rounds to 103, got %d", c.ps);
  CHECK(c.peakPs == 103, "peak follows the live value up");

  // overrun: the field must go negative, which is the on-road sanity check
  memset(d, 0, sizeof d);
  int16_t raw = -200;                // -100 Nm
  uint16_t u = (uint16_t)(raw & 0x0FFF);
  d[1] = (uint8_t)((u & 0x0F) << 4); d[2] = (uint8_t)(u >> 4);
  carFeed(c, CAR_ID_TORQUE, d, 8, 1020);
  CHECK(c.torqueNm == -100, "overrun torque should be -100 Nm, got %d", c.torqueNm);
  CHECK(c.peakPs == 103, "and peak must not fall when the live value does");

  // Battery: the low 12 bits, divided by 68. Both bytes below are the exact
  // samples from the E60 K-CAN documentation, with the voltages it states --
  // so this test fails the moment somebody "simplifies" the scale back to the
  // 0.015 that was there before, which reads 2 % high.
  memset(d, 0, sizeof d);
  d[0] = 0xDB; d[1] = 0xF3;                    // documented: 14.51 V, running
  carFeed(c, CAR_ID_BATTERY, d, 8, 1030);
  CHECK(c.volts > 14.46f && c.volts < 14.56f,
        "documented 0xF3DB should be 14.51 V, got %.2f", c.volts);
  CHECK(c.voltsRaw == 987, "raw count should be 987, got %u",
        (unsigned)c.voltsRaw);

  d[0] = 0x3E; d[1] = 0xF3;                    // documented: 12.20 V, resting
  carFeed(c, CAR_ID_BATTERY, d, 8, 1035);
  CHECK(c.volts > 12.15f && c.volts < 12.26f,
        "documented 0xF33E should be 12.20 V, got %.2f", c.volts);

  d[0] = 0x00; d[1] = 0x00;
  carFeed(c, CAR_ID_BATTERY, d, 8, 1040);
  CHECK(c.volts > 12.15f, "0 V is not a car battery and must be refused");
}

static void testStaleness() {
  printf("staleness and stopped detection\n");
  CarState c;
  uint8_t d[8] = {0};
  d[4] = 0x80; d[5] = 0x25;
  carFeed(c, CAR_ID_RPM, d, 8, 10000);
  CHECK(!carStale(c.tRpm, 10500), "500 ms old is still fresh");
  CHECK(carStale(c.tRpm, 11000), "1000 ms old is stale and must be blanked");
  CHECK(carStale(c.tVolt, 10000), "never heard is stale, not zero");

  CHECK(!carStopped(c, 10000), "no speed frame yet: we do not know it is stopped");
  c.tSpeed = 10000; c.kmh = 0.0f;
  CHECK(carStopped(c, 10100), "a fresh zero is a real standstill");
  c.kmh = 30.0f;
  CHECK(!carStopped(c, 10100), "moving");
}

static void testUnknownIds() {
  printf("software filtering\n");
  CarState c;
  uint8_t d[8] = {0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF};
  // Listen-only mode ignores the hardware filters, so every id on the bus
  // reaches carFeed. Anything not ours must change nothing at all.
  CarState before = c;
  CHECK(!carFeed(c, 0x0BB, d, 8, 1000), "an id we do not use is not ours");
  CHECK(memcmp(&before, &c, sizeof c) == 0, "...and must not touch the state");
}

int main() {
  testBringUp();
  testFrameParse();
  testIgnition();
  testSpeed();
  testRpmTorqueVolts();
  testStaleness();
  testUnknownIds();
  if (fails) { printf("\n%d CHECK(s) failed\n", fails); return 1; }
  printf("\nall CAN and E60 decode checks passed\n");
  return 0;
}
