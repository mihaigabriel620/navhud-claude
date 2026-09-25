// Host tests for the CAN half: canBegin()/canPump()/canOverflows() from
// hud_can.h against the MCP2515 simulator in SPI.h (through the mcp_can stand-in
// in mcp_can.h), and the E60 decoders in hud_car.h against hand-built frames.
//
// Since 2.4 the register protocol belongs to coryjfowler's library, so what is
// ours -- and tested here -- is the pre-flight probe, the listen-only mode, and
// the guards canPump() puts in front of the library: an impossible length, the
// extended and remote-request flags, a controller that has gone away, and the
// timestamp gap after a long repaint. The decoders turn bytes into numbers that
// end up in front of a driver, so they get tested here too.
#include "tft_stub.h"
#include "SPI.h"
SPIClass SPI;

#include <cstdio>
#include <cstring>
#include <cmath>
#include "../NavHud/hud_config.h"
#include "../NavHud/hud_car.h"
bool canOk = false;
#include "../NavHud/hud_can.h"

static int fails = 0;
#define CHECK(cond, ...) do { if (!(cond)) { \
  printf("  FAIL %s:%d  ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); fails++; } } while (0)

static void testBringUp() {
  printf("MCP2515 bring-up\n");
  SPI.reset();
  canOk = canBegin();
  CHECK(canOk, "bring-up should succeed against a working controller");
  CHECK(canFault == CANFAULT_NONE, "no fault on a working controller");
  CHECK(canProbeByte == 0x80, "the pre-flight reads CANSTAT's reset value, got 0x%02X",
        canProbeByte);
  // LISTEN-ONLY is the safety property: in it the chip cannot put a dominant
  // bit on the car's bus, not even an ACK.
  CHECK((SPI.reg[0x0E] & 0xE0) == MCP_LISTENONLY,
        "must end in listen-only so it can never transmit on a live bus (CANSTAT 0x%02X)",
        SPI.reg[0x0E]);
  CHECK((SPI.reg[0x60] & 0x60) == 0x60, "RXB0 RXM must be 11: filters off");
  CHECK((SPI.reg[0x70] & 0x60) == 0x60, "RXB1 RXM must be 11: filters off");
  CHECK(canDev.lastSpeedset == CAN_100KBPS, "K-CAN is 100 kbit/s");
  CHECK(canDev.lastClockset == MCP_8MHZ, "the module's crystal is 8 MHz");
  CHECK(SPI.open == 0, "every transaction must be closed, %d left open", SPI.open);
  CHECK(SPI.maxOpen <= 1, "transactions must not nest on a shared bus");

  // A dead module names its wire instead of "init failed".
  SPI.reset(); SPI.stuck = 0xFF;
  CHECK(!canBegin(), "MISO floating high is not a controller");
  CHECK(canFault == CANFAULT_MISO_HIGH, "...and says so, got %d", (int)canFault);
  SPI.reset(); SPI.stuck = 0x00;
  CHECK(!canBegin(), "MISO held low is not a controller");
  CHECK(canFault == CANFAULT_MISO_LOW, "...and says so, got %d", (int)canFault);
  CHECK(strlen(canFaultText()) > 10, "the fault text is a sentence");
  SPI.reset();
  canOk = canBegin();
}

/** Put one frame on the wire and drain it at time t. */
static uint8_t frame(CarState& c, uint32_t id, const uint8_t* d, uint8_t len, uint32_t t,
                     bool ext = false, bool rtr = false) {
  SPI.deliver(false, id, d, len, ext, rtr);
  return canPump(c, t);
}

static void testPump() {
  printf("canPump: what reaches the decoders\n");
  SPI.reset(); canOk = canBegin();
  CarState c;
  uint8_t d[8] = {0};

  d[0] = 0x45;
  CHECK(frame(c, CAR_ID_IGNITION, d, 1, 1000) == 1, "one frame drained");
  CHECK(c.ignitionOn, "0x130 reached the decoder");

  // 0x1A6 every 100 ms, 100 counts each: (100 / 100 ms) * CAR_SPEED_K.
  uint16_t cnt = 5000; uint32_t t = 1000;
  for (int i = 0; i < 5; i++) {
    d[0] = (uint8_t)cnt; d[1] = (uint8_t)(cnt >> 8);
    // Pump often enough that the stale-gap rule does not rebaseline.
    for (uint32_t k = t - 60; k < t; k += 30) canPump(c, k);
    frame(c, CAR_ID_SPEED, d, 8, t);
    cnt += 100; t += 100;
  }
  CHECK(!carStale(c.tSpeed, t), "the speed is fresh");
  CHECK(c.kmh > 99.0f && c.kmh < 101.0f, "100 counts per 100 ms is 100 km/h, got %.1f", c.kmh);
  const uint32_t tSpeed = c.tSpeed;

  // An extended frame whose low 16 bits happen to read 0x1A6 is somebody
  // else's frame -- the id is truncated to 16 bits on its way to carFeed, so
  // only the flag in bit 31 tells them apart.
  // Keep draining every 30 ms from here on, as the loop does. The payload is
  // a plausible next count, so only the guard can keep it out.
  d[0] = (uint8_t)(cnt + 10); d[1] = (uint8_t)((cnt + 10) >> 8);
  canPump(c, t - 60); canPump(c, t - 30);
  frame(c, 0x000001A6u, d, 8, t, /*ext=*/true);
  CHECK(c.tSpeed == tSpeed, "an extended frame must not be decoded as 0x1A6");
  // A remote request carries no data, whatever its length field says: the
  // bytes in the buffer are the last frame's.
  canPump(c, t + 30);
  frame(c, CAR_ID_SPEED, d, 8, t + 60, false, /*rtr=*/true);
  CHECK(c.tSpeed == tSpeed, "a remote request must not be decoded as 0x1A6");

  // A length of 9..15 is legal on the wire and overruns the library's buffer.
  const uint32_t bad = canBadDlc();
  SPI.deliver(false, CAR_ID_RPM, d, 12);
  canPump(c, t + 90);
  CHECK(canBadDlc() == bad + 1, "a DLC of 12 is refused and counted");
  CHECK(canDev.overruns == 0, "...before the library is allowed to read it");
  CHECK((SPI.reg[0x2C] & 0x01) == 0, "...and the buffer is released for the next frame");
  d[4] = 0x80; d[5] = 0x25;                 // 2400 rpm
  CHECK(frame(c, CAR_ID_RPM, d, 8, t + 120) == 1, "the next frame is read normally");
  CHECK(c.rpm == 2400, "2400 rpm, got %u", (unsigned)c.rpm);

  // Overflow: a second frame into a full buffer is lost, and EFLG latches it.
  const uint32_t ovf0 = canOverflows();
  SPI.deliver(false, CAR_ID_RPM, d, 8);
  SPI.deliver(false, CAR_ID_RPM, d, 8);
  CHECK(canOverflows() == ovf0 + 1, "a lost frame is counted once");
  CHECK((SPI.reg[0x2D] & 0xC0) == 0, "...and the latched flag cleared");
  CHECK(canOverflows() == ovf0 + 1, "a cleared flag is not counted again");
  canPump(c, t + 150);

  // A gap longer than CAN_STALE_GAP_MS: the frames waiting are older than the
  // stamp they would get, so the speed baseline is dropped.
  CHECK(c.haveLast, "a speed baseline exists before the gap");
  canPump(c, t + 150 + CAN_STALE_GAP_MS + 100);
  CHECK(!c.haveLast && c.histN == 0, "a long gap between drains drops the speed baseline");

  // The module dies DURING a drain: the status read at the top of canPump()
  // was fine, then MISO floats high. Every READ STATUS now says a frame is
  // waiting and every length reads 15, so a drain that does not count the
  // frames it throws away never ends -- and the ESP8266's watchdog resets the
  // board three seconds later.
  {
    CarState c2;
    canPump(c2, t + 200);
    SPI.deliver(false, CAR_ID_RPM, d, 8);
    SPI.stuckAfter = SPI.transactions + 2;   // canPump's own status read is still good
    const uint8_t n = canPump(c2, t + 230);
    CHECK(!SPI.runaway, "a module dying mid-drain must not spin the drain loop for ever");
    CHECK(n <= CAN_DRAIN_MAX, "at most CAN_DRAIN_MAX frames per call, got %u", (unsigned)n);
    CHECK(canPump(c2, t + 260) == 0 && !canOk, "and the next call sees the dead module and stops");
    SPI.stuck = -1; SPI.deadReads = 0;
    canOk = true;
  }

  // The module dies mid-drive: MISO floats high and every status reads 0xFF.
  SPI.stuck = 0xFF;
  CHECK(canPump(c, t + 400) == 0, "nothing is read from a dead controller");
  CHECK(!canOk, "canOk drops, so the rest of the firmware stops believing the bus");
  CHECK(canFault == CANFAULT_MISO_HIGH, "and the fault is named");
  CHECK(canDev.overruns == 0, "no 15-byte frame reached the library");
  const int before = SPI.transactions;
  canPump(c, t + 450);
  CHECK(SPI.transactions == before, "and the bus is not touched again");
  SPI.stuck = -1;
}

/**
 * A full repaint blocks the loop for 100-185 ms. The frames the MCP2515 holds
 * through it are the OLDEST of that window (a full buffer drops the newer
 * ones), but they are stamped when they are finally read. Differencing a fresh
 * frame against one of those reads the distance of the whole repaint over the
 * time since the read: a spike.
 */
static void testSpeedAfterRepaint() {
  printf("0x1A6 across a 150 ms repaint\n");
  SPI.reset(); canOk = canBegin();
  CarState c;
  uint8_t d[8] = {0};
  const uint16_t perFrame = 50;               // 50 km/h: 50 counts per 100 ms
  uint16_t cnt = 1000;
  uint32_t t = 10000;                         // frame times: every 100 ms
  auto put = [&](bool buf1) {
    d[0] = (uint8_t)cnt; d[1] = (uint8_t)(cnt >> 8);
    SPI.deliver(buf1, CAR_ID_SPEED, d, 8);
  };
  // Steady driving, drained every 30 ms.
  for (int i = 0; i < 8; i++) {
    put(false);
    for (uint32_t k = t; k < t + 100; k += 30) canPump(c, k);
    cnt += perFrame; t += 100;
  }
  CHECK(c.kmh > 45.0f && c.kmh < 55.0f, "steady 50 km/h before, got %.1f", c.kmh);

  // The repaint: nothing drained from t-10 to t+150. The frames at t and t+100
  // wait in RXB0 and RXB1; nothing else fits.
  canPump(c, t - 10);
  put(false); cnt += perFrame;
  put(true);  cnt += perFrame;
  float worst = 0.0f;
  uint32_t k = t + 150;
  for (int i = 0; i < 6; i++) {               // then frames every 100 ms again
    canPump(c, k);
    if (!carStale(c.tSpeed, k) && fabsf(c.kmh - 50.0f) > worst) worst = fabsf(c.kmh - 50.0f);
    put(false);
    const uint32_t next = t + 200 + 100 * (uint32_t)i;
    for (k = next; k < next + 100; k += 30) {
      canPump(c, k);
      if (fabsf(c.kmh - 50.0f) > worst) worst = fabsf(c.kmh - 50.0f);
    }
    cnt += perFrame;
  }
  CHECK(worst < 8.0f, "after the repaint the speed stays near 50, worst error %.1f km/h", worst);
  CHECK(!carStale(c.tSpeed, k) && c.tSpeed > t + 150, "and it is being measured again, not held");
  printf("  worst error after the repaint: %.1f km/h\n", worst);
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
  c.peakPs = 200; c.haveLast = true;
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

  // 100 ticks in 100 ms -> 1 tick/ms * CAR_SPEED_K (100) = 100 km/h
  feed(1100, 1100);
  CHECK(c.kmh > 99.0f && c.kmh < 101.0f, "expected ~100 km/h, got %.2f", c.kmh);

  // The 16-bit counter wrapping must not produce a spike.
  carRebaseline(c);
  feed(65500, 2000);
  feed(36, 2100);            // 65500 -> 36 is a wrap of 72 ticks
  CHECK(c.kmh > 71.0f && c.kmh < 73.0f, "wrap should read ~72 km/h, got %.2f", c.kmh);

  // A gap longer than CAR_DT_MAX_MS is not a speed.
  carRebaseline(c); c.kmh = 50.0f; c.tSpeed = 3000;
  feed(1000, 3000); feed(60000, 8000);
  CHECK(c.kmh == 50.0f, "a 5 s gap must be discarded, not turned into a number");
  CHECK(c.tSpeed == 3000, "...and must not be stamped fresh");

  // And an implausible delta is refused outright, and the history dropped so
  // it cannot poison the next readings.
  carRebaseline(c); c.kmh = 10.0f;
  feed(0, 9000); feed(60000, 9100);
  CHECK(c.kmh == 10.0f, "a 60000 km/h delta must not reach the glass");
  CHECK(c.histN == 1, "the history restarts from the frame after the bad one");

  // The sliding window: a steady 50 km/h with 100 ms frames whose timestamps
  // jitter by +-10 ms reads close to 50 once the window has filled.
  carRebaseline(c);
  uint16_t cnt = 0; uint32_t t = 20000;
  const int jit[8] = { 0, 10, -10, 5, -5, 10, -10, 0 };
  for (int i = 0; i < 8; i++) {
    feed(cnt, (uint32_t)(t + jit[i]));
    cnt += 50; t += 100;
  }
  CHECK(c.kmh > 46.0f && c.kmh < 54.0f, "jittered 50 km/h reads %.1f", c.kmh);
}

static void testRpmTorqueVolts() {
  printf("0x0AA rpm, 0x0A8 torque, 0x3B4 battery, 0x1D0 coolant\n");
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

  // An impossible torque is refused, not committed and marked fresh.
  memset(d, 0, sizeof d);
  d[2] = 0x7F; d[1] = 0xF0;          // raw 2047 -> 1023 Nm
  carFeed(c, CAR_ID_TORQUE, d, 8, 1025);
  CHECK(c.torqueNm == -100 && c.tTorque == 1020, "1023 Nm must be refused");

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

  // Coolant: byte 0, offset 48. 0xFF is an unpopulated byte, not 207 C.
  memset(d, 0, sizeof d);
  d[0] = 138;
  carFeed(c, CAR_ID_COOLANT, d, 8, 1050);
  CHECK(c.coolantC == 90, "138 - 48 = 90 C, got %d", c.coolantC);
  d[0] = 0xFF;
  carFeed(c, CAR_ID_COOLANT, d, 8, 1060);
  CHECK(c.coolantC == 90 && c.tCoolant == 1050, "0xFF must be refused");
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
  // millis() wraps after 49.7 days; the age must still come out right.
  CHECK(!carStale(0xFFFFFF00u, 0x00000100u), "a 512 ms age across the wrap is fresh");
  CHECK(carStale(0xFFFFF000u, 0x00000100u), "a 4 s age across the wrap is stale");

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
  // The filters are off (MCP_ANY), so every id on the bus reaches carFeed.
  // Anything not ours must change nothing at all.
  CarState before = c;
  CHECK(!carFeed(c, 0x0BB, d, 8, 1000), "an id we do not use is not ours");
  CHECK(memcmp(&before, &c, sizeof c) == 0, "...and must not touch the state");
}

int main() {
  testBringUp();
  testPump();
  testSpeedAfterRepaint();
  testIgnition();
  testSpeed();
  testRpmTorqueVolts();
  testStaleness();
  testUnknownIds();
  if (fails) { printf("\n%d CHECK(s) failed\n", fails); return 1; }
  printf("\nall CAN and E60 decode checks passed\n");
  return 0;
}
