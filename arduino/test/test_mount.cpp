// Host tests for the mounting calibration.
//
// This is the file with the least chance of being caught any other way. A sign
// error here does not crash, does not warn, and does not look wrong on a bench:
// it produces a compass that reads correctly at north and south and mirrored at
// east and west, or an arrow that points at the back of the car. The only way
// to find that before the car is to build the rotations here, on the host, from
// known angles, and check the answers come back.
#include <cstdio>
#include <cstring>
#include <cmath>
#include "../NavHud/hud_mount.h"

static int fails = 0;
#define CHECK(cond, ...) do { if (!(cond)) { \
  printf("  FAIL %s:%d  ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); fails++; } } while (0)

static const float DEG = 0.01745329251f;

/** Rotate v about an arbitrary unit axis by `ang` radians (Rodrigues). */
static void rot(const float* v, const float* axis, float ang, float* out) {
  const float c = cosf(ang), s = sinf(ang);
  float cr[3]; mountCross(axis, v, cr);
  const float d = mountDot(axis, v);
  for (int i = 0; i < 3; i++) out[i] = v[i] * c + cr[i] * s + axis[i] * d * (1 - c);
}

/** Signed smallest difference between two bearings, degrees. */
static float dDeg(float a, float b) {
  float d = fmodf(a - b + 540.0f, 360.0f) - 180.0f;
  return d;
}

// ---------------------------------------------------------------------------
//  A simulated car and a simulated sensor bolted into it at a known angle.
// ---------------------------------------------------------------------------
struct Rig {
  // The rotation taking a VEHICLE-frame vector into SENSOR axes. Built from
  // three successive rotations so the test can describe a mount the way a
  // person would: "tipped back this much, rolled that much, twisted this much".
  float R[9];

  void set(float yawDeg, float pitchDeg, float rollDeg) {
    const float zAx[3] = { 0, 0, 1 }, yAx[3] = { 0, 1, 0 }, xAx[3] = { 1, 0, 0 };
    float e[3][3] = { {1,0,0}, {0,1,0}, {0,0,1} };
    for (int i = 0; i < 3; i++) {
      float t1[3], t2[3];
      rot(e[i], zAx, yawDeg * DEG, t1);
      rot(t1,   yAx, pitchDeg * DEG, t2);
      rot(t2,   xAx, rollDeg * DEG, e[i]);
      (void)t2;
    }
    // e[i] is where vehicle basis vector i lands, expressed in sensor axes.
    for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) R[j * 3 + i] = e[i][j];
  }

  /** Vehicle-frame vector -> what the chip reads. */
  void toSensor(const float* v, float* out) const {
    for (int i = 0; i < 3; i++)
      out[i] = R[i * 3 + 0] * v[0] + R[i * 3 + 1] * v[1] + R[i * 3 + 2] * v[2];
  }
};

/**
 * What the chip reads for gravity and the magnetic field, given a car pointing
 * `headingDeg` and a mount `rig`.
 *
 * Gravity: an MPU-9250 at rest reads +1 g on whichever axis points UP, so in
 * vehicle coordinates that is +9.81 on Z. (NXP's application notes negate the
 * accelerometer and would write -9.81 here; that convention difference is one
 * of the two classic ways this goes wrong.)
 *
 * Field: 49 uT total at 65 degrees of dip, which is central Europe. The
 * horizontal part points at magnetic north; the vertical part points DOWN in
 * the northern hemisphere, so it is negative on a Z-up axis.
 */
static void simulate(const Rig& rig, float headingDeg, float* aOut, float* mOut,
                     float dipDeg = 65.0f, float total = 49.0f) {
    const float aVeh[3] = { 0, 0, 9.81f };

    const float h = total * cosf(dipDeg * DEG);
    const float vDown = total * sinf(dipDeg * DEG);
    // North, expressed in vehicle axes. A car heading 90 (east) has north on
    // its left, i.e. +Y.
    const float th = headingDeg * DEG;
    const float mVeh[3] = { h * cosf(th), h * sinf(th), -vDown };

    rig.toSensor(aVeh, aOut);
    rig.toSensor(mVeh, mOut);
}

// ---------------------------------------------------------------------------
static void testHeadingSquare() {
  printf("a sensor mounted square with the car\n");
  HudMount m;                                   // identity, valid == false
  Rig rig; rig.set(0, 0, 0);
  const float want[] = { 0, 45, 90, 135, 180, 225, 270, 315 };
  for (float w : want) {
    float a[3], mag[3];
    simulate(rig, w, a, mag);
    const float got = m.headingDeg(mag, a);
    CHECK(fabsf(dDeg(got, w)) < 0.5f,
          "heading %.0f should read %.0f, got %.1f", w, w, got);
  }
  // The signature of the classic sign error: right at north and south, mirrored
  // east for west. Assert east really is 90 and not 270.
  float a[3], mag[3];
  simulate(rig, 90.0f, a, mag);
  const float east = m.headingDeg(mag, a);
  CHECK(fabsf(dDeg(east, 90.0f)) < 0.5f,
        "pointing east must read 90, not 270 -- got %.1f", east);
  CHECK(fabsf(dDeg(east, 270.0f)) > 90.0f, "east read as west: mirrored compass");
}

static void testHeadingTilted() {
  printf("...and at every silly angle somebody might glue it\n");
  struct { float y, p, r; const char* what; } mounts[] = {
    {   0,   0,   0, "square" },
    {  37,   0,   0, "twisted 37 about vertical" },
    {   0,  25,   0, "tipped back 25" },
    {   0,   0,  40, "rolled 40" },
    { 137,  25, -40, "every axis at once" },
    {  90,  90,   0, "flat on the back of the panel (gimbal-lock angle)" },
    { -73, -62,  18, "upside down and backwards" },
  };
  for (auto& mt : mounts) {
    Rig rig; rig.set(mt.y, mt.p, mt.r);
    // Learn the mount from a perfect measurement of up and forward, which is
    // what the calibration is trying to recover.
    const float upVeh[3] = { 0, 0, 1 }, fwdVeh[3] = { 1, 0, 0 };
    float upS[3], fwdS[3];
    rig.toSensor(upVeh, upS);
    rig.toSensor(fwdVeh, fwdS);
    HudMount m;
    CHECK(m.build(upS, fwdS), "%s: build failed", mt.what);

    float worst = 0;
    for (float w = 0; w < 360; w += 15) {
      float a[3], mag[3];
      simulate(rig, w, a, mag);
      const float e = fabsf(dDeg(m.headingDeg(mag, a), w));
      if (e > worst) worst = e;
    }
    CHECK(worst < 0.5f, "%s: worst heading error %.2f deg", mt.what, worst);
    printf("    %-52s worst %.3f deg\n", mt.what, worst);
  }
}

static void testUncalibratedIsWrong() {
  printf("an uncalibrated twisted mount is wrong by the twist\n");
  // The point of the whole exercise: without the calibration the heading is off
  // by exactly the mounting yaw, silently and with total confidence.
  Rig rig; rig.set(60, 0, 0);
  HudMount m;                                   // never calibrated
  float a[3], mag[3];
  simulate(rig, 0.0f, a, mag);
  const float got = m.headingDeg(mag, a);
  CHECK(fabsf(dDeg(got, 0.0f)) > 45.0f,
        "a 60 degree twist should show up as a large error, got %.1f", got);
  printf("    car pointing north, uncalibrated reads %.1f\n", got);
}

static void testYawRate() {
  printf("yaw rate is about the true vertical, clockwise positive\n");
  Rig rig; rig.set(137, 25, -40);
  const float upVeh[3] = { 0, 0, 1 }, fwdVeh[3] = { 1, 0, 0 };
  float upS[3], fwdS[3];
  rig.toSensor(upVeh, upS); rig.toSensor(fwdVeh, fwdS);
  HudMount m; m.build(upS, fwdS);

  // A right turn: clockwise seen from above is NEGATIVE about the up axis.
  const float gVeh[3] = { 0, 0, -20.0f * DEG };      // rad/s
  float gS[3], aS[3];
  rig.toSensor(gVeh, gS);
  rig.toSensor((const float[]){0, 0, 9.81f}, aS);
  const float dps = m.yawDps(gS, aS);
  CHECK(fabsf(dps - 20.0f) < 0.2f, "a right turn must read +20 dps, got %.2f", dps);

  const float gLeft[3] = { 0, 0, +20.0f * DEG };
  rig.toSensor(gLeft, gS);
  CHECK(fabsf(m.yawDps(gS, aS) + 20.0f) < 0.2f, "a left turn must read -20 dps");
}

static void testTriadPutsGravityFirst() {
  printf("TRIAD reproduces gravity exactly and only levels the forward guess\n");
  HudMount m;
  const float up[3] = { 0, 0, 1 };
  // A forward estimate that is 20 degrees out of level -- which is what a real
  // one looks like, because braking pitches the car.
  const float fwdBad[3] = { cosf(20 * DEG), 0, sinf(20 * DEG) };
  CHECK(m.build(up, fwdBad), "build");
  CHECK(fabsf(m.up[2] - 1.0f) < 1e-5f, "gravity must survive untouched");
  CHECK(fabsf(mountDot(m.fwd, m.up)) < 1e-5f, "forward must come back level");
  CHECK(fabsf(mountNorm(m.fwd) - 1.0f) < 1e-5f, "and unit length");
  CHECK(fabsf(mountDot(m.fwd, m.left)) < 1e-5f, "orthogonal to left");
  // Right-handed: forward x left = up.
  float c[3]; mountCross(m.fwd, m.left, c);
  CHECK(mountDot(c, m.up) > 0.999f, "frame must be right-handed (fwd x left = up)");
}

static void testDegenerate() {
  printf("a forward estimate parallel to gravity is refused\n");
  HudMount m;
  const float up[3] = { 0, 0, 1 };
  const float straightUp[3] = { 0, 0, 9.0f };
  CHECK(!m.build(up, straightUp), "a car does not drive vertically");
  CHECK(!m.valid, "and nothing was adopted");
  const float zero[3] = { 0, 0, 0 };
  CHECK(!m.build(zero, straightUp), "no gravity, no calibration");
}

static void testFlash() {
  printf("it survives a power cycle\n");
  Rig rig; rig.set(137, 25, -40);
  const float upVeh[3] = { 0, 0, 1 }, fwdVeh[3] = { 1, 0, 0 };
  float upS[3], fwdS[3];
  rig.toSensor(upVeh, upS); rig.toSensor(fwdVeh, fwdS);
  HudMount a; a.build(upS, fwdS);

  uint8_t b[HUD_MOUNT_BYTES];
  a.pack(b);
  HudMount c;
  CHECK(c.unpack(b), "unpack");
  CHECK(c.valid, "and it counts as calibrated");
  float worst = 0;
  for (float w = 0; w < 360; w += 30) {
    float acc[3], mag[3];
    simulate(rig, w, acc, mag);
    const float e = fabsf(dDeg(c.headingDeg(mag, acc), a.headingDeg(mag, acc)));
    if (e > worst) worst = e;
  }
  CHECK(worst < 0.05f, "quantisation cost %.4f deg", worst);
  printf("    %d bytes, worst quantisation error %.4f deg\n", HUD_MOUNT_BYTES, worst);

  // Blank flash and damaged flash both leave it alone.
  HudMount d; d.build(upS, fwdS);
  uint8_t blank[HUD_MOUNT_BYTES]; memset(blank, 0xFF, sizeof blank);
  CHECK(!d.unpack(blank), "blank flash must not be adopted");
  a.pack(b); b[5] ^= 0x20;
  CHECK(!d.unpack(b), "a flipped bit must be caught by the checksum");
}

static void testStaleness() {
  printf("it notices when somebody moves the display\n");
  HudMount m;
  const float up[3] = { 0, 0, 1 }, fwd[3] = { 1, 0, 0 };
  m.build(up, fwd);
  const float same[3] = { 0, 0, 9.81f };
  CHECK(m.matchesGravity(same), "unmoved must not cry wolf");

  // A country slope: 8 % is 4.6 degrees, and the sources call that an ordinary
  // low-standard grade. It must NOT trip.
  const float slope[3] = { 9.81f * sinf(4.6f * DEG), 0, 9.81f * cosf(4.6f * DEG) };
  CHECK(m.matchesGravity(slope), "a driveway slope is not a moved sensor");
  CHECK(fabsf(m.gravityErrorDeg(slope) - 4.6f) < 0.1f, "and it is measured, not guessed");

  // Somebody re-stuck the display.
  const float moved[3] = { 9.81f * sinf(30 * DEG), 0, 9.81f * cosf(30 * DEG) };
  CHECK(!m.matchesGravity(moved), "30 degrees is a moved sensor");
}

static void testLearning() {
  printf("learning it from a simulated drive\n");
  Rig rig; rig.set(137, 25, -40);
  HudMount m;
  m.learnStart();

  // Parked. Gravity, with a little noise, for long enough to settle.
  float aS[3];
  rig.toSensor((const float[]){0, 0, 9.81f}, aS);
  uint32_t t = 1000;
  for (int i = 0; i < 200; i++) {
    const float n = (i % 7 - 3) * 0.002f;
    const float a[3] = { aS[0] + n, aS[1] - n, aS[2] + n * 0.5f };
    m.feedStill(a, t);
    t += 100;
  }
  CHECK(m.haveUp(), "gravity should be settled after 20 s of standing still");

  // Now drive: alternating gentle acceleration and braking in a straight line.
  for (int i = 0; i < 400; i++) {
    const float dvdt = (i / 40) % 2 == 0 ? 1.6f : -1.9f;
    const float aVeh[3] = { dvdt, 0.05f, 9.81f };     // a little camber
    float a[3]; rig.toSensor(aVeh, a);
    m.feedMoving(a, 0.4f, dvdt);
  }
  CHECK(m.fwdSamples() >= MOUNT_FWD_SAMPLES, "enough samples, got %u",
        (unsigned)m.fwdSamples());
  CHECK(m.correlation() > MOUNT_CORR_MIN, "correlation %.3f", m.correlation());
  CHECK(m.learnFinish(), "should have adopted the result");

  float worst = 0;
  for (float w = 0; w < 360; w += 15) {
    float acc[3], mag[3];
    simulate(rig, w, acc, mag);
    const float e = fabsf(dDeg(m.headingDeg(mag, acc), w));
    if (e > worst) worst = e;
  }
  CHECK(worst < 3.0f, "learned mount is %.2f deg out", worst);
  printf("    learned from 400 samples, worst heading error %.2f deg\n", worst);
}

static void testLearningRefusesRubbish() {
  printf("...and refuses it when the drive was not good enough\n");
  Rig rig; rig.set(20, 10, 0);
  float aS[3]; rig.toSensor((const float[]){0, 0, 9.81f}, aS);

  {  // Never stood still: no gravity, no calibration.
    HudMount m; m.learnStart();
    for (int i = 0; i < 400; i++) m.feedMoving(aS, 0.0f, 2.0f);
    CHECK(!m.learnFinish(), "no up vector means no answer");
  }
  {  // Stood still, but never accelerated in a straight line.
    HudMount m; m.learnStart();
    uint32_t t = 0;
    for (int i = 0; i < 200; i++) { m.feedStill(aS, t); t += 100; }
    CHECK(m.haveUp(), "gravity settled");
    for (int i = 0; i < 400; i++) {
      const float aVeh[3] = { 2.0f, 0, 9.81f };
      float a[3]; rig.toSensor(aVeh, a);
      m.feedMoving(a, 25.0f, 2.0f);            // turning hard the whole time
    }
    CHECK(m.fwdSamples() == 0, "a turn is not a straight line");
    CHECK(!m.learnFinish(), "and there is nothing to adopt");
  }
  {  // Accelerometer that has nothing to do with the speed on the bus.
    HudMount m; m.learnStart();
    uint32_t t = 0;
    for (int i = 0; i < 200; i++) { m.feedStill(aS, t); t += 100; }
    for (int i = 0; i < 400; i++) {
      // Horizontal acceleration of a fixed size that ignores dv/dt entirely,
      // e.g. a sensor reading a rattling bracket rather than the car.
      const float aVeh[3] = { 3.0f, 0, 9.81f };
      float a[3]; rig.toSensor(aVeh, a);
      const float dvdt = (i % 2 == 0) ? 1.0f : 4.0f;
      m.feedMoving(a, 0.0f, dvdt);
    }
    CHECK(m.correlation() < MOUNT_CORR_MIN,
          "a constant accel against a varying dv/dt should not correlate: %.3f",
          m.correlation());
    CHECK(!m.learnFinish(), "and must be refused");
  }
}

static void testBrakingOnlyStillFindsTheNose() {
  printf("braking alone still finds the nose, not the boot\n");
  // The 180-degree ambiguity is the whole reason the CAN speed is needed. A
  // drive made entirely of deceleration must still point forwards.
  Rig rig; rig.set(45, 0, 0);
  HudMount m; m.learnStart();
  float aS[3]; rig.toSensor((const float[]){0, 0, 9.81f}, aS);
  uint32_t t = 0;
  for (int i = 0; i < 200; i++) { m.feedStill(aS, t); t += 100; }
  for (int i = 0; i < 400; i++) {
    // Braking, always -- but at varying rates, because a correlation over a
    // constant is undefined and is correctly refused. That refusal is asserted
    // separately below; this test is about the 180-degree ambiguity.
    const float dvdt = -1.0f - (i % 5) * 0.5f;
    const float aVeh[3] = { dvdt, 0, 9.81f };
    float a[3]; rig.toSensor(aVeh, a);
    m.feedMoving(a, 0.0f, dvdt);
  }
  CHECK(m.learnFinish(), "adopted (corr %.3f)", m.correlation());
  float acc[3], mag[3];
  simulate(rig, 0.0f, acc, mag);
  const float got = m.headingDeg(mag, acc);
  CHECK(fabsf(dDeg(got, 0.0f)) < 3.0f,
        "car pointing north should read 0, not 180 -- got %.1f", got);
  printf("    all-braking drive, car pointing north reads %.1f\n", got);

  // And the flip side: a drive with no variation in the speed derivative has
  // nothing to correlate against, so it is refused rather than guessed at. It
  // is a "keep driving" answer, not a wrong one.
  {
    HudMount c; c.learnStart();
    float aStill[3]; rig.toSensor((const float[]){0, 0, 9.81f}, aStill);
    uint32_t tt = 0;
    for (int i = 0; i < 200; i++) { c.feedStill(aStill, tt); tt += 100; }
    for (int i = 0; i < 400; i++) {
      const float aVeh[3] = { -2.0f, 0, 9.81f };
      float a[3]; rig.toSensor(aVeh, a);
      c.feedMoving(a, 0.0f, -2.0f);
    }
    CHECK(c.correlation() < MOUNT_CORR_MIN,
          "a constant deceleration has no variance to correlate: %.3f",
          c.correlation());
    CHECK(!c.learnFinish(), "so it must say keep driving, not guess");
  }
}

static void testRattleIsRefused() {
  printf("an accelerometer that is not measuring the car is refused\n");
  // The test that was missing, and its absence let a structurally broken
  // acceptance gate ship. Both earlier learning tests drove with a
  // SINGLE-SIGNED dv/dt -- all acceleration, or all braking -- which is the one
  // case where the sign taken from the bus is constant and the correlation
  // measures something real. Every actual drive has both signs, and with both
  // signs present the old pairing agreed with itself by construction and
  // scored ~0.9 on data containing no direction information at all.
  Rig rig; rig.set(20, 10, 0);
  float aStill[3]; rig.toSensor((const float[]){0, 0, 9.81f}, aStill);

  struct { bool varyMag; const char* what; } cases[] = {
    { true,  "a loose bracket rattling" },
    { false, "a constant push in a random direction each sample" },
  };
  for (auto& c : cases) {
    HudMount m; m.learnStart();
    uint32_t t = 0;
    for (int i = 0; i < 200; i++) { m.feedStill(aStill, t); t += 100; }
    CHECK(m.haveUp(), "%s: gravity should still settle", c.what);

    uint32_t seed = 12345;
    for (int i = 0; i < 600; i++) {
      // A horizontal acceleration whose direction has nothing to do with the
      // car, against a speed derivative that swings both ways.
      seed = seed * 1103515245u + 12345u;
      const float ang = (float)((seed >> 16) & 0xFFFF) / 65535.0f * 6.2831853f;
      const float mag = c.varyMag ? 1.0f + (float)((seed >> 8) & 0xFF) / 128.0f : 3.0f;
      const float aVeh[3] = { mag * cosf(ang), mag * sinf(ang), 9.81f };
      float a[3]; rig.toSensor(aVeh, a);
      seed = seed * 1103515245u + 12345u;
      const float dvdt = ((seed >> 16) % 2 ? 1.0f : -1.0f) *
                         (1.0f + (float)((seed >> 8) & 0xFF) / 128.0f);
      m.feedMoving(a, 0.0f, dvdt);
    }
    const float r = m.correlation();
    CHECK(r < MOUNT_CORR_MIN, "%s: scored %.3f, gate is %.2f", c.what, r, MOUNT_CORR_MIN);
    CHECK(!m.learnFinish(), "%s: must be refused", c.what);
    printf("    %-46s r = %+.3f  refused\n", c.what, r);
  }

  // ...and the honest drive still passes, so the gate discriminates rather
  // than simply refusing everything.
  {
    HudMount m; m.learnStart();
    uint32_t t = 0;
    for (int i = 0; i < 200; i++) { m.feedStill(aStill, t); t += 100; }
    for (int i = 0; i < 400; i++) {
      const float dvdt = (i / 20) % 2 == 0 ? 1.4f + (i % 5) * 0.2f
                                           : -1.7f - (i % 3) * 0.3f;
      const float aVeh[3] = { dvdt, 0.05f, 9.81f };
      float a[3]; rig.toSensor(aVeh, a);
      m.feedMoving(a, 0.3f, dvdt);
    }
    const float r = m.correlation();
    CHECK(r > MOUNT_CORR_MIN, "an honest mixed drive scored only %.3f", r);
    CHECK(m.learnFinish(), "and must be accepted");
    printf("    %-46s r = %+.3f  accepted\n", "an honest drive, both signs", r);
  }
}

static void testRefusingDoesNotEndTheSession() {
  printf("asking to save too early does not kill the calibration\n");
  // The help text says "type 'status' to watch it fill up, then 'mount save'",
  // which invites exactly this. Clearing the learning flag before the gates
  // meant an early save ended the session while printing "keep driving and try
  // again" -- and the counter then never moved again, however far you drove.
  Rig rig; rig.set(30, 0, 0);
  HudMount m; m.learnStart();
  float aStill[3]; rig.toSensor((const float[]){0, 0, 9.81f}, aStill);
  uint32_t t = 0;
  for (int i = 0; i < 200; i++) { m.feedStill(aStill, t); t += 100; }

  for (int i = 0; i < 40; i++) {                 // not nearly enough yet
    const float dvdt = i % 2 ? 1.5f : -1.8f;
    const float aVeh[3] = { dvdt, 0, 9.81f };
    float a[3]; rig.toSensor(aVeh, a);
    m.feedMoving(a, 0.0f, dvdt);
  }
  const uint16_t before = m.fwdSamples();
  CHECK(!m.learnFinish(), "40 samples is not enough and must be refused");
  CHECK(m.learning(), "but the session must still be running");

  for (int i = 0; i < 400; i++) {
    const float dvdt = i % 2 ? 1.5f + (i % 7) * 0.1f : -1.8f - (i % 5) * 0.2f;
    const float aVeh[3] = { dvdt, 0, 9.81f };
    float a[3]; rig.toSensor(aVeh, a);
    m.feedMoving(a, 0.0f, dvdt);
  }
  CHECK(m.fwdSamples() > before, "the counter must keep moving: %u then %u",
        (unsigned)before, (unsigned)m.fwdSamples());
  CHECK(m.learnFinish(), "and the second save must succeed");
  printf("    refused at %u samples, carried on to %u, then saved\n",
         (unsigned)before, (unsigned)m.fwdSamples());
}

static void testItFinishesItself() {
  printf("it completes on its own, with nothing typed\n");
  // The loop calls learnFinish() on every qualifying pass rather than waiting
  // to be asked, so this drives the same pattern: no command, no procedure,
  // just samples arriving. It has to complete at the earliest trustworthy
  // moment and not one sample before.
  Rig rig; rig.set(112, 18, -27);
  HudMount m; m.learnStart();
  float aStill[3]; rig.toSensor((const float[]){0, 0, 9.81f}, aStill);

  uint32_t t = 0;
  bool done = false;
  int  doneAt = -1;
  for (int i = 0; i < 200 && !done; i++) {
    m.feedStill(aStill, t); t += 100;
    if (m.fwdSamples() >= MOUNT_FWD_SAMPLES && m.learnFinish()) done = true;
  }
  CHECK(!done, "standing still alone must never be enough: it has no forward");
  CHECK(m.haveUp(), "but gravity should have settled");

  for (int i = 0; i < 600 && !done; i++) {
    const float dvdt = (i / 15) % 2 == 0 ? 1.3f + (i % 6) * 0.15f
                                         : -1.6f - (i % 4) * 0.25f;
    const float aVeh[3] = { dvdt, 0.04f, 9.81f };
    float a[3]; rig.toSensor(aVeh, a);
    m.feedMoving(a, 0.5f, dvdt);
    if (m.fwdSamples() >= MOUNT_FWD_SAMPLES && m.learnFinish()) { done = true; doneAt = i; }
  }
  CHECK(done, "an ordinary drive must finish it without being asked");
  CHECK(doneAt >= MOUNT_FWD_SAMPLES - 1,
        "it finished at sample %d, before it had the evidence", doneAt);
  CHECK(m.valid && !m.learning(), "and the session should be closed");

  float worst = 0;
  for (float w = 0; w < 360; w += 15) {
    float acc[3], mag[3];
    simulate(rig, w, acc, mag);
    const float e = fabsf(dDeg(m.headingDeg(mag, acc), w));
    if (e > worst) worst = e;
  }
  CHECK(worst < 3.0f, "worst heading error %.2f deg", worst);
  printf("    finished by itself after %d driving samples, worst error %.2f deg\n",
         doneAt + 1, worst);
}

int main() {
  printf("mounting calibration\n\n");
  testHeadingSquare();
  testHeadingTilted();
  testUncalibratedIsWrong();
  testYawRate();
  testTriadPutsGravityFirst();
  testDegenerate();
  testFlash();
  testStaleness();
  testLearning();
  testLearningRefusesRubbish();
  testBrakingOnlyStillFindsTheNose();
  testRattleIsRefused();
  testRefusingDoesNotEndTheSession();
  testItFinishesItself();
  printf(fails ? "\n%d CHECK(s) FAILED\n" : "\nall mount checks passed\n", fails);
  return fails ? 1 : 0;
}
