// ---------------------------------------------------------------------------
//  hud_car.h -- what the E60 says on the bus, turned into numbers.
// ---------------------------------------------------------------------------
//
//  WHERE TO LOOK WHEN SOMETHING IS WRONG
//
//    one field wrong, rest fine    -> that ID's decode, below. Each is in its
//                                     own function with its source note.
//    everything blank              -> car.ignitionOn false, or every field
//                                     stale: hud_can.h, not here.
//    speed pegged or jumping       -> carSpeed_(): the 16-bit counter wrapped
//                                     or the baseline was not reset on wake.
//    PS looks nothing like the dyno-> carTorque_() is the UNVERIFIED one.
//
//  Decodes are Mihai's, from his own logging on this car. Two tiers:
//
//    VERIFIED against the dash over a 24 h Belgium->Romania drive:
//      0x130 ignition   0x1A6 speed   0x0AA rpm
//    NEEDS ONE LOGGING DRIVE:
//      0x0A8 torque     0x3B4 battery
//
//  Anything in the second tier is drawn dimmer in the theme until he says
//  otherwise, and the sanity limits below refuse obvious nonsense rather than
//  putting it on the glass.
//
//  Nothing here talks to hardware. It takes CanFrames and a millis() value, so
//  it compiles and can be tested on a PC.
//
// ---------------------------------------------------------------------------
#ifndef HUD_CAR_H
#define HUD_CAR_H

#include <stdint.h>

// ---- the frames we care about ---------------------------------------------
enum : uint16_t {
  CAR_ID_TORQUE   = 0x0A8,
  CAR_ID_RPM      = 0x0AA,
  CAR_ID_IGNITION = 0x130,
  CAR_ID_SPEED    = 0x1A6,
  CAR_ID_BATTERY  = 0x3B4
};
static const uint16_t CAR_IDS[] = {
  CAR_ID_TORQUE, CAR_ID_RPM, CAR_ID_IGNITION, CAR_ID_SPEED, CAR_ID_BATTERY
};
static const uint8_t CAR_ID_COUNT = sizeof(CAR_IDS) / sizeof(CAR_IDS[0]);

// A value older than this is not shown at all. Mihai's rule, and the right
// one: a HUD showing a stale number is worse than one showing nothing.
#ifndef CAR_STALE_MS
  #define CAR_STALE_MS 800
#endif

// 0x1A6 is a distance accumulator, so speed is a difference over time. Frames
// arrive every 100-300 ms and the interval varies, so every sample is
// timestamped and intervals outside this window are thrown away.
#define CAR_DT_MIN_MS  50
#define CAR_DT_MAX_MS  1000
#define CAR_SPEED_K    80.4672f      // counter ticks per ms -> km/h

// PS = Nm * rpm / 7024 (the metric-horsepower constant for Nm and rpm).
#define CAR_PS_DIVISOR 7024.0f

// Refuse obvious nonsense rather than drawing it.
#define CAR_MAX_KMH    300
#define CAR_MAX_RPM    8000
#define CAR_MAX_PS     500

struct CarState {
  // ---- ignition (0x130) ----
  bool     ignitionOn = false;   // byte0 > 0x41
  /** Should the panel be lit? See carIgnition_ for why this is not ignitionOn. */
  bool     displayOn = false;
  bool     cranking   = false;   // byte0 == 0x55: keep the panel lit, blank numbers
  uint8_t  ignRaw     = 0;

  // ---- verified ----
  float    kmh   = 0.0f;
  uint16_t rpm   = 0;

  // ---- needs a logging drive ----
  int16_t  torqueNm = 0;
  int16_t  ps       = 0;
  int16_t  peakPs   = 0;         // held until the ignition cycles
  float    volts    = 0.0f;
  /** The undivided count, so the scale above can be checked against a meter. */
  uint16_t voltsRaw = 0;

  // ---- when each was last heard; 0 = never ----
  uint32_t tIgn = 0, tSpeed = 0, tRpm = 0, tTorque = 0, tVolt = 0;

  // ---- speed differentiation state ----
  uint16_t lastCnt  = 0;
  uint32_t lastCntMs = 0;
  bool     haveLast = false;
};

/** True when that field is too old to draw. `t == 0` means never heard. */
static inline bool carStale(uint32_t t, uint32_t now) {
  return t == 0 || (uint32_t)(now - t) > CAR_STALE_MS;
}

/**
 * Forget the speed baseline. Must be called on any wake and on every
 * ignition-on edge: the counter kept running (or did not) while we were away,
 * and one bogus enormous delta puts 300 km/h on the glass.
 */
static inline void carRebaseline(CarState& c) { c.haveLast = false; }

// ---- 0x130 ignition (CAS) -- VERIFIED --------------------------------------
//   0x00 key removed, 0x41 key in position 1 / engine stopped,
//   0x55 cranking, anything above 0x41 is "on".
static void carIgnition_(CarState& c, const uint8_t* d, uint8_t len, uint32_t now) {
  if (len < 1) return;
  const bool wasOn = c.ignitionOn;
  c.ignRaw     = d[0];
  c.cranking   = (d[0] == 0x55);
  c.ignitionOn = (d[0] > 0x41);
  // Deliberately a lower bar than ignitionOn, and they answer different
  // questions. ignitionOn gates the car-only display and the peak-PS reset,
  // and wants the engine actually running or cranking (0x45, 0x55). The
  // display wants to be lit at key position 1 as well (0x41, "engine off, key
  // in position 1"), because that is a driver sitting in the car with the
  // ignition on -- and a panel that stays dark until the engine fires looks
  // broken. So: above 0x40 is lit, 0x40 (key going in) and 0x00 (no key) are
  // not.
  c.displayOn  = (d[0] > 0x40);
  c.tIgn       = now;
  if (c.ignitionOn && !wasOn) {
    carRebaseline(c);      // the counter moved while we were asleep
    c.peakPs = 0;          // peak hold resets on an ignition cycle
  }
}

// ---- 0x1A6 speed -- VERIFIED -----------------------------------------------
//   16-bit little-endian distance accumulator. Differentiate it. uint16_t
//   subtraction handles the wrap for free -- do NOT make these signed.
static void carSpeed_(CarState& c, const uint8_t* d, uint8_t len, uint32_t now) {
  if (len < 2) return;
  const uint16_t cnt = (uint16_t)d[0] | ((uint16_t)d[1] << 8);

  if (c.haveLast) {
    const uint32_t dt = now - c.lastCntMs;
    if (dt >= CAR_DT_MIN_MS && dt <= CAR_DT_MAX_MS) {
      const uint16_t delta = (uint16_t)(cnt - c.lastCnt);   // wraps correctly
      const float kmh = ((float)delta / (float)dt) * CAR_SPEED_K;
      if (kmh >= 0.0f && kmh <= CAR_MAX_KMH) {
        c.kmh    = kmh;
        c.tSpeed = now;
      }
    }
    // Outside the window the sample is not usable, but the baseline still
    // advances below -- otherwise one long gap poisons every later delta.
  }
  c.lastCnt   = cnt;
  c.lastCntMs = now;
  c.haveLast  = true;
}

// ---- 0x0AA rpm -- VERIFIED -------------------------------------------------
//   bytes 4-5 little-endian, divided by 4.
static void carRpm_(CarState& c, const uint8_t* d, uint8_t len, uint32_t now) {
  if (len < 6) return;
  const uint16_t raw = (((uint16_t)d[5] << 8) | d[4]) >> 2;
  if (raw > CAR_MAX_RPM) return;
  c.rpm  = raw;
  c.tRpm = now;
}

// ---- 0x0A8 engine torque -- NEEDS A LOGGING DRIVE --------------------------
//   12-bit signed at bit 12, 0.5 Nm/bit. Byte 0 is a checksum and the low
//   nibble of byte 1 is a message counter, so the extra "resolution" some
//   sites show is counter noise. Sanity check on the road: it must go
//   NEGATIVE on overrun -- foot off, still in gear.
static void carTorque_(CarState& c, const uint8_t* d, uint8_t len, uint32_t now) {
  if (len < 3) return;
  int16_t raw = (int16_t)((((uint16_t)d[2] << 4) | (d[1] >> 4)) & 0x0FFF);
  if (raw & 0x800) raw -= 4096;                 // sign-extend the 12-bit field
  c.torqueNm = (int16_t)(raw / 2);              // 0.5 Nm/bit
  c.tTorque  = now;

  // PS needs both halves fresh, or you get last minute's torque times this
  // minute's rpm.
  if (!carStale(c.tRpm, now)) {
    const float ps = (float)c.torqueNm * (float)c.rpm / CAR_PS_DIVISOR;
    const int16_t v = (int16_t)(ps + (ps >= 0 ? 0.5f : -0.5f));
    if (v >= -CAR_MAX_PS && v <= CAR_MAX_PS) {
      c.ps = v;
      if (v > c.peakPs) c.peakPs = v;           // peak is never below live
    }
  }
}

// ---- 0x3B4 battery -- NEEDS A LOGGING DRIVE --------------------------------
//   12 bits little-endian, 15 mV/bit. Check against a multimeter, ~0.2 V.
static void carBattery_(CarState& c, const uint8_t* d, uint8_t len, uint32_t now) {
  if (len < 2) return;
  // The raw count is not in dispute. Masking the top nibble off and the
  // documented ((d[1] - 240) * 256) + d[0] are the same arithmetic: the field
  // sits in the low 12 bits with 0xF in the high nibble, so both give 987 for
  // the documented 0xF3DB sample. Only the scale was ever in question.
  const uint16_t raw = (uint16_t)((((uint16_t)d[1] << 8) | d[0]) & 0x0FFF);

  // Divide by 68, not multiply by 0.015.
  //
  // Two sources, 2 % apart: 1/68 is 0.014706. On the documented samples 1/68
  // gives 14.51 V charging and 12.20 V resting, where 0.015 gives 14.81 and
  // 12.45 -- and 14.81 is high for a charging system where 14.51 is textbook.
  // Neither source proves it (the one quoting 1/68 marks the calculation
  // "TBC"), so this is the more plausible of two guesses rather than a fact,
  // and it is checkable in a minute: put a multimeter across the battery and
  // compare. voltsRaw is reported over $CAR for exactly that -- the true
  // scale is simply measured / raw.
  const float v = (float)raw / 68.0f;
  if (v < 5.0f || v > 18.0f) return;            // not a car battery
  c.volts    = v;
  c.voltsRaw = raw;
  c.tVolt    = now;
}

/**
 * Feed every frame the bus hands you, as a raw id and payload -- this file
 * deliberately does not know what an MCP2515 is, so it compiles on a PC.
 * Ids we do not use are dropped here --
 * which on a listen-only MCP2515 is ALL the filtering there is, because
 * listen-only mode ignores the hardware filters (datasheet 10.3).
 *
 * Returns true if the frame was one of ours.
 */
static bool carFeed(CarState& c, uint16_t id, const uint8_t* d, uint8_t len,
                    uint32_t now) {
  if (id > 0x7FF) return false;          // not an 11-bit id: nothing of ours
  switch (id) {
    case CAR_ID_IGNITION: carIgnition_(c, d, len, now); return true;
    case CAR_ID_SPEED:    carSpeed_   (c, d, len, now); return true;
    case CAR_ID_RPM:      carRpm_     (c, d, len, now); return true;
    case CAR_ID_TORQUE:   carTorque_  (c, d, len, now); return true;
    case CAR_ID_BATTERY:  carBattery_ (c, d, len, now); return true;
    default:              return false;
  }
}

/** Standing still, and sure of it. Used to re-zero the gyro, so it has to be
    certain rather than merely probable: a fresh speed frame reading zero. */
static inline bool carStopped(const CarState& c, uint32_t now) {
  return !carStale(c.tSpeed, now) && c.kmh < 1.0f;
}

#endif // HUD_CAR_H
