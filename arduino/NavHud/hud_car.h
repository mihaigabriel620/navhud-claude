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
//    speed reads high or low by a
//    steady percentage             -> CAR_SPEED_K. It changed in 2.3 and the
//                                     whole derivation is written next to it.
//    speed pegged or jumping       -> carSpeed_(): the 16-bit counter wrapped,
//                                     the baseline was not reset on an ignition
//                                     edge, or CAR_SPEED_WIN_MS is too short.
//    PS looks nothing like the dyno-> carTorque_() is the UNVERIFIED one.
//
//  Decodes are Mihai's, from his own logging on this car. Three tiers now:
//
//    VERIFIED on the car AND cross-checked against a second, independent
//    implementation -- r00li/CarCluster, which drives real E-series clusters
//    on simulator rigs and therefore has to get the encode exactly right:
//      0x130 ignition   0x1A6 speed   0x0AA rpm
//    CROSS-CHECKED ONLY -- from CarCluster, never yet seen on this car:
//      0x1D0 coolant
//    NEEDS ONE LOGGING DRIVE -- no second source at all:
//      0x0A8 torque     0x3B4 battery
//
//  Anything below the first tier is drawn dimmer in the theme until he says
//  otherwise, and the sanity limits below refuse obvious nonsense rather than
//  putting it on the glass.
//
//  THE SECOND SOURCE, and what it is worth. CarCluster is a transmitter: it
//  synthesises frames a cluster believes. This file is a receiver. They are
//  opposite ends of the same contract, so an encode there pins a decode here
//  -- if the cluster shows 100 km/h for the bytes CarCluster sends, then those
//  bytes mean 100 km/h, whoever put them on the wire. Where the two disagree,
//  the note says so rather than quietly picking one.
//    source: CarCluster/src/Clusters/BMW_E/BMWESeriesCluster.cpp
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
  CAR_ID_COOLANT  = 0x1D0,
  CAR_ID_BATTERY  = 0x3B4
};
// A value older than this is not shown at all. Mihai's rule, and the right
// one: a HUD showing a stale number is worse than one showing nothing.
#ifndef CAR_STALE_MS
  #define CAR_STALE_MS 800
#endif

/**
 * Coolant is a slow signal and gets a slow stale window.
 *
 * CarCluster sends 0x1D0 on its LONG timer, dashboardUpdateTimeLong = 500 ms
 * (BMWESeriesCluster.h:43). Against the 800 ms used for speed and revs, one
 * dropped frame would blank the number. Three seconds is six missed frames,
 * and a coolant temperature three seconds old is still true -- unlike a speed
 * three seconds old, which is a lie.
 */
#ifndef CAR_COOLANT_STALE_MS
  #define CAR_COOLANT_STALE_MS 3000
#endif

// 0x1A6 is a distance accumulator, so speed is a difference over time. Frames
// arrive every 100-300 ms and the interval varies, so every sample is
// timestamped and intervals outside this window are thrown away.
#define CAR_DT_MIN_MS  50
#define CAR_DT_MAX_MS  1000

/**
 * Counter ticks per millisecond -> km/h.  WAS 80.4672. NOW 100.
 *
 * This is the one number in this file that changed on the strength of the
 * second source, and it moves every speed reading by 24 %, so here is the
 * whole derivation rather than an assertion.
 *
 * CarCluster's transmitter, verbatim (BMWESeriesCluster.cpp:135):
 *
 *     void BMWESeriesCluster::sendSpeed(int speed, int deltaTime) {
 *       uint16_t speed_value = speed + previousSpeedValue;
 *       ...
 *       previousSpeedValue = speed_value;
 *     }
 *
 * so the 16-bit field at bytes 0-1 is an accumulator that gains `speed` on
 * every send. Three facts fix the scale, each from a line of their source:
 *
 *   1. the unit of `speed`  -- GameSimulation.h:109, `int speed = 0;
 *      // Car speed in km/h`, and both game front ends convert into it the
 *      same way (`someSpeed = someSpeed * 3.6;  // Speed is in m/s`).
 *   2. nothing scales it    -- mapSpeed() multiplies by
 *      configuration.speedCorrectionFactor, which is 1.00
 *      (GameSimulation.h:13) and exists only so a user can trim a gauge.
 *   3. the send period      -- sendSpeed() is called from the SHORT timer,
 *      dashboardUpdateTimeShort = 100 (BMWESeriesCluster.h:42).
 *
 * So the accumulator gains one count per km/h per 100 ms, and
 *
 *     km/h = (delta counts / delta ms) * 100.
 *
 * The old 80.4672 was never measured. It is 50 * 1.609344 to seven digits --
 * fifty miles per hour written in km/h -- which is the fingerprint of a
 * conversion done in mph-land, not of a calibration. It read 20 % LOW, and
 * that is worth knowing for a second reason: the app's CarLink learns a
 * correction against GPS ground speed but refuses anything outside
 * SCALE_MIN 0.90 .. SCALE_MAX 1.10, so a 24 % error would have sat pinned at
 * the clamp for ever, never converging and never complaining.
 *
 * If a drive disagrees, this is one line: put
 *     #define CAR_SPEED_K 80.4672f
 * in hud_config.h, above the include, and nothing else changes.
 */
#ifndef CAR_SPEED_K
  #define CAR_SPEED_K 100.0f
#endif

/**
 * How far back to difference the accumulator. 0 restores frame-to-frame.
 *
 * The counter moves in WHOLE km/h once per frame, so a frame-to-frame
 * difference carries one full count of quantisation plus the error of two
 * timestamps taken in a loop that also repaints a display. At 100 km/h that is
 * 100 counts over a nominal 100 ms: a 5 ms timestamp error on its own is
 * 5 km/h, ten times a second, and the number on the glass would shiver.
 *
 * Differencing against a sample about this old divides that error by the ratio
 * of the two windows while still producing a fresh number on every frame --
 * a sliding window, not a slower update rate. The cost is lag of at most half
 * the window: 150 ms, about 1 km/h under hard braking.
 */
#ifndef CAR_SPEED_WIN_MS
  #define CAR_SPEED_WIN_MS 300
#endif

/**
 * The history that window is taken from.
 *
 * SPACING is a floor on how close together two stored samples may be, so that
 * HIST samples always span at least HIST * SPACING = 330 ms, comfortably more
 * than the window. Without it a bus sending 0x1A6 every few milliseconds could
 * fill the ring with samples all too young to difference against, and the
 * speed would simply stop updating.
 */
#define CAR_SPEED_HIST       12
#define CAR_SPEED_SPACING_MS 30

// PS = Nm * rpm / 7024 (the metric-horsepower constant for Nm and rpm).
#define CAR_PS_DIVISOR 7024.0f

// Refuse obvious nonsense rather than drawing it.
#define CAR_MAX_KMH    300
#define CAR_MAX_RPM    8000
#define CAR_MAX_PS     500
// The 12-bit field reaches +-1023 Nm on an engine that makes about 300. Without
// this, one corrupt frame wrote a number AND marked it fresh, so the theme --
// which only checks staleness -- drew it. 600 leaves room for any tune.
#define CAR_MAX_NM     600

// Coolant: one degree per count with this offset. -40 C is a cold Romanian
// morning and 160 C is a head gasket; 0xFF (an unpopulated byte) is 207 and
// falls outside, which is the point.
#define CAR_COOLANT_OFFSET  48
#define CAR_MIN_COOLANT_C  (-40)
#define CAR_MAX_COOLANT_C   160

struct CarState {
  // ---- ignition (0x130) ----
  bool     ignitionOn = false;   // byte0 > 0x41
  /** Should the panel be lit? See carIgnition_ for why this is not ignitionOn. */
  bool     displayOn = false;
  bool     cranking   = false;   // byte0 == 0x55: keep the panel lit, blank numbers

  // ---- verified ----
  float    kmh   = 0.0f;
  uint16_t rpm   = 0;

  // ---- cross-checked against CarCluster, not yet seen on this car ----
  int16_t  coolantC = 0;

  // ---- needs a logging drive ----
  int16_t  torqueNm = 0;
  int16_t  ps       = 0;
  int16_t  peakPs   = 0;         // held until the ignition cycles
  float    volts    = 0.0f;
  /** The undivided count, so the scale above can be checked against a meter. */
  uint16_t voltsRaw = 0;

  // ---- when each was last heard; 0 = never ----
  uint32_t tIgn = 0, tSpeed = 0, tRpm = 0, tTorque = 0, tVolt = 0, tCoolant = 0;

  // ---- speed differentiation state ----
  // A ring, not a single previous sample: see CAR_SPEED_WIN_MS. Entries are
  // scanned by age rather than walked in order, so the ring needs no notion of
  // where the oldest one is.
  uint16_t histCnt[CAR_SPEED_HIST] = {0};
  uint32_t histMs [CAR_SPEED_HIST] = {0};
  uint8_t  histHead = 0;         // next slot to overwrite
  uint8_t  histN    = 0;         // how many of them are real, 0..CAR_SPEED_HIST
  uint32_t histNewestMs = 0;     // for the SPACING floor
  bool     haveLast = false;     // any speed sample at all since the last rebaseline
};

/** True when that field is older than `limit`. `t == 0` means never heard. */
static inline bool carStaleMs(uint32_t t, uint32_t now, uint32_t limit) {
  return t == 0 || (uint32_t)(now - t) > limit;
}

/** True when that field is too old to draw. `t == 0` means never heard. */
static inline bool carStale(uint32_t t, uint32_t now) {
  return carStaleMs(t, now, CAR_STALE_MS);
}

/** Coolant moves slowly and is allowed to be older. See CAR_COOLANT_STALE_MS. */
static inline bool carCoolantStale(const CarState& c, uint32_t now) {
  return carStaleMs(c.tCoolant, now, CAR_COOLANT_STALE_MS);
}

/**
 * Forget the speed baseline. Called on every ignition-on edge, and by canPump()
 * after a gap too long to timestamp honestly: the counter kept running (or did
 * not) while we were away, and one bogus enormous delta puts 300 km/h on the
 * glass.
 */
static inline void carRebaseline(CarState& c) {
  c.histN = 0;
  c.histHead = 0;
  c.histNewestMs = 0;
  c.haveLast = false;
}

// ---- 0x130 ignition (CAS) -- VERIFIED --------------------------------------
//   0x00 key removed, 0x41 key in position 1 / engine stopped,
//   0x55 cranking, anything above 0x41 is "on".
static void carIgnition_(CarState& c, const uint8_t* d, uint8_t len, uint32_t now) {
  if (len < 1) return;
  const bool wasOn = c.ignitionOn;
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

// ---- 0x1A6 speed -- VERIFIED, and cross-checked -----------------------------
//
//   16-bit little-endian distance accumulator at bytes 0-1. Differentiate it.
//   uint16_t subtraction handles the wrap for free -- do NOT make these signed.
//
//   ONLY BYTES 0-1 ARE READ, and that is deliberate. CarCluster copies the
//   same value into bytes 2-3 and 4-5 (BMWESeriesCluster.cpp:144-148) because
//   a simulator has exactly one speed to give. A real car almost certainly
//   does not: three counters in one frame on a car with four wheels reads like
//   per-wheel or per-axle counts, which differ in every corner and differ a
//   lot with one soft tyre. Averaging them might well be better than taking
//   one -- but it would be a guess, and a guess is what this file does not do.
//   One logging drive in a car park, turning, settles it.
//
//   Bytes 6-7 are a 12-bit field with 0xF forced into the top nibble, which
//   CarCluster advances by deltaTime * M_PI (line 138-139) -- so it is a time
//   counter of some sort and NOT a message sequence number. Nothing here reads
//   it; it is written down so the next person does not have to guess either.
static void carSpeedPush_(CarState& c, uint16_t cnt, uint32_t now) {
  // The SPACING floor: see CAR_SPEED_SPACING_MS. The first sample after a
  // rebaseline always goes in.
  if (c.histN > 0 && (uint32_t)(now - c.histNewestMs) < CAR_SPEED_SPACING_MS) return;
  c.histCnt[c.histHead] = cnt;
  c.histMs [c.histHead] = now;
  c.histHead = (uint8_t)((c.histHead + 1) % CAR_SPEED_HIST);
  if (c.histN < CAR_SPEED_HIST) c.histN++;
  c.histNewestMs = now;
}

static void carSpeed_(CarState& c, const uint8_t* d, uint8_t len, uint32_t now) {
  if (len < 2) return;
  const uint16_t cnt = (uint16_t)d[0] | ((uint16_t)d[1] << 8);

  // Choose what to difference against, before this sample joins the history.
  // Want: the NEWEST stored sample that is already at least a window old --
  // that is the shortest baseline which still meets CAR_SPEED_WIN_MS, so it
  // has the least lag. If nothing is that old yet (we have just woken, or the
  // window is configured to 0), fall back to the oldest usable sample, so the
  // first number arrives one frame after the first, not one window after.
  uint8_t  pick    = 0xFF; uint32_t pickAge   = 0;
  uint8_t  oldest  = 0xFF; uint32_t oldestAge = 0;
  for (uint8_t k = 0; k < c.histN; k++) {
    const uint32_t age = (uint32_t)(now - c.histMs[k]);
    if (age < CAR_DT_MIN_MS || age > CAR_DT_MAX_MS) continue;
    if (oldest == 0xFF || age > oldestAge) { oldestAge = age; oldest = k; }
    if (age >= CAR_SPEED_WIN_MS && (pick == 0xFF || age < pickAge)) {
      pickAge = age; pick = k;
    }
  }
  if (pick == 0xFF) pick = oldest;

  bool lostSync = false;
  if (pick != 0xFF) {
    const uint32_t dt    = (uint32_t)(now - c.histMs[pick]);
    const uint16_t delta = (uint16_t)(cnt - c.histCnt[pick]);   // wraps correctly
    const float    kmh   = ((float)delta / (float)dt) * CAR_SPEED_K;
    if (kmh <= (float)CAR_MAX_KMH) {          // delta is unsigned: never < 0
      c.kmh    = kmh;
      c.tSpeed = now;
    } else {
      lostSync = true;
    }
  }

  // An impossible delta means the history no longer describes this car -- a
  // reset, a long sleep, a corrupt frame. Keeping it would poison every
  // reading until the bad sample aged out of the window. Throw it away and
  // start again from here; the next frame re-acquires with a short baseline.
  if (lostSync) carRebaseline(c);
  carSpeedPush_(c, cnt, now);
  c.haveLast = true;
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

// ---- 0x1D0 coolant -- CROSS-CHECKED, NOT YET SEEN ON THIS CAR --------------
//   byte 0, one degree per count, offset 48.
//
//   CarCluster, BMWESeriesCluster.cpp:199-205:
//       engineTempFrame[0] = coolantTemperature + 48;
//       CAN.sendMsgBuf(0x1D0, 0, 8, engineTempFrame);
//
//   which is exactly Mihai's own note for this frame, arrived at separately.
//   Two independent sources agreeing on an offset is as close to verified as
//   this gets without the car, but it is still in its own tier until a drive
//   shows it climbing to ~90 C and staying there.
//
//   The range check earns its keep here: an unpopulated byte reads 0xFF, which
//   would otherwise become a confident 207 C.
static void carCoolant_(CarState& c, const uint8_t* d, uint8_t len, uint32_t now) {
  if (len < 1) return;
  const int16_t t = (int16_t)d[0] - CAR_COOLANT_OFFSET;
  if (t < CAR_MIN_COOLANT_C || t > CAR_MAX_COOLANT_C) return;
  c.coolantC = t;
  c.tCoolant = now;
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
  const int16_t nm = (int16_t)(raw / 2);        // 0.5 Nm/bit
  if (nm > CAR_MAX_NM || nm < -CAR_MAX_NM) return;
  c.torqueNm = nm;
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
    case CAR_ID_COOLANT:  carCoolant_ (c, d, len, now); return true;
    case CAR_ID_TORQUE:   carTorque_  (c, d, len, now); return true;
    case CAR_ID_BATTERY:  carBattery_ (c, d, len, now); return true;
    default:              return false;
  }
}

/** Standing still, and sure of it. It gates the deferred flash write, so it has
    to be certain rather than merely probable: a fresh speed frame reading zero. */
static inline bool carStopped(const CarState& c, uint32_t now) {
  return !carStale(c.tSpeed, now) && c.kmh < 1.0f;
}

#endif // HUD_CAR_H
