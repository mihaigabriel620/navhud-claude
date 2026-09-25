// The heading maths (hud_heading.h), driven by a simulated box in a simulated
// car: the box can be tilted any way, turned, pitched over a hill, braked and
// thrown round a bend, and the chips' readings are what they would measure.
//
// What has to hold, and each one is something a driver would see:
//
//   1. Level, it is exactly the flat compass the firmware had before.
//   2. Tilted -- the screen angled up, a hill -- the heading does not move.
//      Without gravity it moved about two degrees per degree of tilt.
//   3. Braking and cornering do not bend gravity, so they do not bend the
//      heading either.
//   4. The turn rate is the car's, about the true vertical, whatever angle the
//      box is mounted at, and nothing is sent until the gyro's bias is known.
//   5. A calibration taken with the box tilted is right at that tilt.
//   6. What is saved comes back, and nothing damaged is believed.
#include <cstdio>
#include <cmath>
#include <cstring>
#include "Wire.h"                          // SimBox: the world the chips live in
#include "../NavHud/hud_heading.h"

static int failures = 0;
#define CHECK(cond, msg) do { \
  if (!(cond)) { printf("  FAIL: %s\n", msg); failures++; } } while (0)

static const double kRad = M_PI / 180.0, kG = 9.80665;

/** Signed difference a - b, folded into -180..180. */
static double angDiff(double a, double b) {
  double d = fmod(a - b + 540.0, 360.0) - 180.0;
  return d;
}

/** The box's axes, in the world: forward, left, up. */
static void axes(const SimBox& b, double f[3], double l[3], double u[3]) {
  const double ex[3] = { 1, 0, 0 }, ey[3] = { 0, 1, 0 }, ez[3] = { 0, 0, 1 };
  double bx[3], by[3], bz[3];
  b.toBox(ex, bx); b.toBox(ey, by); b.toBox(ez, bz);
  for (int i = 0; i < 3; i++) {
    const double* c = i == 0 ? bx : i == 1 ? by : bz;
    f[i] = c[0]; l[i] = c[1]; u[i] = c[2];
  }
}
static double dot3(const double* a, const double* b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

/**
 * A car with the box in it. The box's pose is SimBox's; the car moves it by
 * heading, pitch and roll steps, and every step produces what the MPU and the
 * compass would read, fed to HudHeading the way hud_sensors.h does.
 */
struct Car {
  SimBox box;
  HudHeading h;
  double speed = 0;                  // m/s along the nose; negative: no bus
  double accelMps2 = 0;              // along the nose (braking is negative)
  double gyroBias[3] = { 0, 0, 0 };  // what the MPU adds, deg/s
  double hardIron[3] = { 0, 0, 0 };  // the car's own field at the box, uT, box frame
  double t = 0;

  /** Where gravity really is, box frame. */
  void trueUp(double u[3]) const { const double w[3] = { 0, 0, 1 }; box.toBox(w, u); }

  /** Degrees between HudHeading's `up` and the real one. */
  double upErrDeg() const {
    double u[3]; trueUp(u);
    const double d = u[0] * h.up[0] + u[1] * h.up[1] + u[2] * h.up[2];
    return acos(fmin(1.0, d)) / kRad;
  }

  /**
   * One 20 ms step: the car turns `turnDps` clockwise, pitches `pitchDps`
   * nose-up and rolls `rollDps` right-side-down, all as seen from the world.
   */
  void step(double turnDps, double pitchDps = 0, double rollDps = 0, double dt = 0.02,
            bool withMag = true) {
    double f0[3], l0[3], u0[3];
    axes(box, f0, l0, u0);
    box.headingDeg = fmod(box.headingDeg + turnDps * dt + 360.0, 360.0);
    box.pitchDeg += pitchDps * dt;
    box.rollDeg  += rollDps * dt;
    double f1[3], l1[3], u1[3];
    axes(box, f1, l1, u1);

    // Body rates from the two poses: rotating about X takes left towards up,
    // about Y takes up towards forward, about Z takes forward towards left.
    float gyr[3];
    gyr[0] = (float)(dot3(l1, u0) / dt / kRad + gyroBias[0]);
    gyr[1] = (float)(dot3(u1, f0) / dt / kRad + gyroBias[1]);
    gyr[2] = (float)(dot3(f1, l0) / dt / kRad + gyroBias[2]);

    // What the car's motion adds to gravity: along the nose, and towards the
    // centre of the turn (a right turn pulls to the right).
    const double hr = box.headingDeg * kRad;
    const double fwdH[3] = { sin(hr), cos(hr), 0 }, leftH[3] = { -cos(hr), sin(hr), 0 };
    const double v = speed > 0 ? speed : 0;
    const double aLat = -v * turnDps * kRad;               // m/s^2 towards the left
    double aw[3], ab[3];
    for (int i = 0; i < 3; i++) aw[i] = (accelMps2 * fwdH[i] + aLat * leftH[i]) / kG;
    box.toBox(aw, ab);
    box.linX = ab[0]; box.linY = ab[1]; box.linZ = ab[2];

    double a[3], m[3];
    box.accelG(a);
    box.fieldUt(m);
    const float acc[3] = { (float)a[0], (float)a[1], (float)a[2] };
    h.motion(acc, gyr, (float)dt, (float)speed);
    if (withMag) {
      const float mag[3] = { (float)(m[0] + hardIron[0]), (float)(m[1] + hardIron[1]),
                             (float)(m[2] + hardIron[2]) };
      h.mag(mag);
    }
    if (speed >= 0) speed = fmax(0.0, speed + accelMps2 * dt);
    t += dt;
  }

  void hold(double seconds) { for (int i = 0; i < (int)(seconds / 0.02 + 0.5); i++) step(0); }
};

/** The flat compass the firmware had before: atan2(my, mx). */
static double flatHeading(const SimBox& b) {
  double m[3]; b.fieldUt(m);
  double d = atan2(m[1], m[0]) / kRad;
  return d < 0 ? d + 360.0 : d;
}

int main() {
  printf("1. level and with no MPU, it is the flat compass it replaces\n");
  {
    double worst = 0;
    for (int hd = 0; hd < 360; hd += 7) {
      HudHeading h;
      SimBox b; b.headingDeg = hd;
      double m[3]; b.fieldUt(m);
      const float mag[3] = { (float)m[0], (float)m[1], (float)m[2] };
      CHECK(h.mag(mag), "a level box has a heading");
      worst = fmax(worst, fabs(angDiff(h.deg, flatHeading(b))));
      worst = fmax(worst, fabs(angDiff(h.deg, hd)));
    }
    CHECK(worst < 0.01, "matches atan2(my, mx) and the true heading");
    printf("    worst %.4f deg over 52 headings\n", worst);
  }

  printf("2. tilted, the heading does not move -- and the flat compass did\n");
  {
    double worst = 0, flatWorst = 0;
    const double tilts[][2] = { { 20, 0 }, { 0, -15 }, { 25, -10 }, { -12, 18 }, { 35, 5 } };
    for (const auto& tl : tilts) {
      for (int hd = 0; hd < 360; hd += 15) {
        Car c;
        c.box.headingDeg = hd; c.box.pitchDeg = tl[0]; c.box.rollDeg = tl[1];
        c.hold(0.1);
        worst = fmax(worst, fabs(angDiff(c.h.deg, hd)));
        flatWorst = fmax(flatWorst, fabs(angDiff(flatHeading(c.box), hd)));
      }
    }
    CHECK(worst < 0.2, "within 0.2 deg at every heading and tilt");
    CHECK(flatWorst > 20, "and the test means something: flat, it was far out");
    printf("    tilt-compensated worst %.3f deg; the flat compass %.1f deg\n", worst, flatWorst);
  }

  printf("3. the gyro's bias is learned while parked, and no rate is sent before\n");
  {
    Car c;
    c.gyroBias[0] = 1.5; c.gyroBias[1] = -0.8; c.gyroBias[2] = 2.3;
    c.box.pitchDeg = 10;
    float r = 0;
    c.hold(0.5);
    CHECK(!c.h.biasKnown && !c.h.takeRate(r), "nothing sent while the bias is unknown");
    c.hold(2.2);
    CHECK(c.h.biasKnown, "known after two parked seconds");
    CHECK(fabs(c.h.bias[0] - 1.5) < 0.01 && fabs(c.h.bias[1] + 0.8) < 0.01 &&
          fabs(c.h.bias[2] - 2.3) < 0.01, "and it is the bias");
    c.hold(0.2);
    CHECK(c.h.takeRate(r) && fabs(r) < 0.02, "parked, the rate is zero");
    printf("    bias %.3f %.3f %.3f, parked rate %.4f deg/s\n",
           c.h.bias[0], c.h.bias[1], c.h.bias[2], r);

    // A moving car is never "parked", however steady.
    Car m;
    m.speed = 25;                               // a straight at 90 km/h
    m.hold(5);
    CHECK(!m.h.biasKnown, "no bias learned while the bus says moving");
  }

  printf("4. the turn rate is about the true vertical, whatever the mount\n");
  {
    Car c;
    c.box.pitchDeg = 22; c.box.rollDeg = -14;    // the box mounted crooked
    c.gyroBias[2] = -1.1;
    c.hold(3);
    float r = 0; c.h.takeRate(r);                // drop what was parked
    c.speed = 4;                                 // a car park, 15 km/h
    double worstUp = 0;
    for (int i = 0; i < 250; i++) { c.step(18); worstUp = fmax(worstUp, c.upErrDeg()); }
    CHECK(c.h.takeRate(r) && fabs(r - 18.0) < 0.1, "18 deg/s clockwise reads 18");
    const float right = r;
    for (int i = 0; i < 100; i++) { c.step(-9); worstUp = fmax(worstUp, c.upErrDeg()); }
    CHECK(c.h.takeRate(r) && fabs(r + 9.0) < 0.1, "and counter-clockwise is negative");
    CHECK(worstUp < 0.5, "gravity held through the turns, cornering force and all");
    CHECK(fabs(angDiff(c.h.deg, c.box.headingDeg)) < 0.5, "so the heading did too");
    printf("    rates %.3f and %.3f deg/s, gravity off by at most %.3f deg\n",
           right, r, worstUp);
  }

  printf("5. braking does not bend gravity\n");
  {
    Car c;
    c.box.headingDeg = 90;                       // east: where pitch errors hurt most
    c.hold(3);
    c.speed = 25;
    double worstUp = 0, worstHdg = 0;
    c.accelMps2 = -3.0;                          // firm braking, 0.3 g, for 5 s
    for (int i = 0; i < 250; i++) {
      c.step(0);
      worstUp = fmax(worstUp, c.upErrDeg());
      worstHdg = fmax(worstHdg, fabs(angDiff(c.h.deg, c.box.headingDeg)));
    }
    c.accelMps2 = 2.5;                           // then pulling away hard
    for (int i = 0; i < 250; i++) {
      c.step(0);
      worstUp = fmax(worstUp, c.upErrDeg());
      worstHdg = fmax(worstHdg, fabs(angDiff(c.h.deg, c.box.headingDeg)));
    }
    CHECK(worstUp < 0.3, "gravity stays put");
    CHECK(worstHdg < 0.5, "and so does the heading");
    printf("    gravity off by at most %.3f deg, heading %.3f deg\n", worstUp, worstHdg);
  }

  printf("6. a hill: the gyro carries gravity over the crest\n");
  {
    Car c;
    c.box.headingDeg = 270;
    c.hold(3);
    c.speed = 20;
    double worstUp = 0, worstHdg = 0;
    for (int i = 0; i < 150; i++) {              // 3 s tipping nose-up 6 deg
      c.step(0, 2.0);
      worstUp = fmax(worstUp, c.upErrDeg());
      worstHdg = fmax(worstHdg, fabs(angDiff(c.h.deg, c.box.headingDeg)));
    }
    for (int i = 0; i < 500; i++) {              // 10 s of climbing
      c.step(0);
      worstUp = fmax(worstUp, c.upErrDeg());
      worstHdg = fmax(worstHdg, fabs(angDiff(c.h.deg, c.box.headingDeg)));
    }
    for (int i = 0; i < 300; i++) {              // over the top, down 6 deg
      c.step(0, -4.0);
      worstUp = fmax(worstUp, c.upErrDeg());
      worstHdg = fmax(worstHdg, fabs(angDiff(c.h.deg, c.box.headingDeg)));
    }
    CHECK(worstUp < 0.3, "gravity follows the slope");
    CHECK(worstHdg < 0.6, "and the heading does not notice it");
    printf("    gravity off by at most %.3f deg, heading %.3f deg\n", worstUp, worstHdg);
  }

  printf("7. a bias that moves while driving is followed, not locked out\n");
  {
    Car c;
    c.hold(3);
    c.speed = 30;
    c.gyroBias[0] = 0.6; c.gyroBias[1] = -0.4;   // the cabin warmed up, all at once
    double worstUp = 0;
    for (int i = 0; i < 6000; i++) { c.step(0); worstUp = fmax(worstUp, c.upErrDeg()); }
    const double endUp = c.upErrDeg();
    CHECK(worstUp < 2.9, "gravity stays inside the gate");
    CHECK(endUp < 0.05, "and settles back once the drift is learned");
    CHECK(fabs(c.h.bias[0] - 0.6) < 0.01 && fabs(c.h.bias[1] + 0.4) < 0.01,
          "the tilt axes' bias is learned on the move");
    printf("    worst %.2f deg, after two minutes %.3f deg, bias %.3f %.3f\n",
           worstUp, endUp, c.h.bias[0], c.h.bias[1]);

    // A jump big enough to carry `up` past the gate. Every reading then
    // disagrees; at a steady speed that can only be `up` being wrong.
    Car j;
    j.hold(3);
    j.speed = 30;
    j.gyroBias[1] = 1.5;
    double past = 0;
    for (int i = 0; i < 9000; i++) { j.step(0); past = fmax(past, j.upErrDeg()); }
    CHECK(past > 3.0, "the jump did carry it past the gate");
    CHECK(j.upErrDeg() < 0.1, "and it came back without a stop");
    printf("    a 1.5 deg/s jump: out by %.1f deg at worst, %.3f deg three minutes on\n",
           past, j.upErrDeg());

    // A long slip road: 0.06 g for 30 s is a steady disagreement too, but the
    // speed shows it, so it must not be taken for a wrong `up`.
    Car r;
    r.hold(3);
    r.speed = 15;
    r.accelMps2 = 0.6;
    double worstRamp = 0;
    for (int i = 0; i < 1500; i++) { r.step(0); worstRamp = fmax(worstRamp, r.upErrDeg()); }
    CHECK(worstRamp < 0.3, "a long gentle acceleration is not mistaken for drift");
    printf("    30 s at 0.06 g: gravity off by at most %.3f deg\n", worstRamp);
  }

  printf("8. a calibration taken tilted is right at that tilt\n");
  {
    Car c;
    c.box.pitchDeg = 15; c.box.rollDeg = 5;
    c.hardIron[0] = 12; c.hardIron[1] = -8; c.hardIron[2] = 10;
    c.hold(3);
    double before = 0;
    for (int hd = 0; hd < 360; hd += 30) {
      c.box.headingDeg = hd; c.hold(0.1);
      before = fmax(before, fabs(angDiff(c.h.deg, hd)));
    }
    c.h.calStart();
    c.speed = 3;                                 // a slow circle in a car park
    for (int i = 0; i < 1100; i++) c.step(20);   // 440 deg
    c.speed = 0;
    CHECK(c.h.calFinish(), "a full circle is accepted");
    double worst = 0;
    for (int hd = 0; hd < 360; hd += 10) {
      c.box.headingDeg = hd; c.hold(0.1);
      worst = fmax(worst, fabs(angDiff(c.h.deg, hd)));
    }
    CHECK(before > 20, "the car's own field was pulling it well off");
    CHECK(worst < 1.0, "calibrated, within a degree at every heading");
    printf("    before %.1f deg, after %.3f deg; offset %.2f %.2f %.2f uT, field %.1f uT\n",
           before, worst, c.h.offset[0], c.h.offset[1], c.h.offset[2], c.h.fieldUt);
  }

  printf("9. a calibration that did not go round is refused\n");
  {
    Car c;
    c.hold(3);
    c.h.calStart();
    for (int i = 0; i < 60; i++) c.step(20);     // 24 deg: nowhere near a circle
    CHECK(!c.h.calFinish() && c.h.calibrating(), "too little turning: refused, still running");
    for (int i = 0; i < 200; i++) c.step(20);    // now past 100 deg
    CHECK(!c.h.calFinish(), "a quarter circle is still refused");
    for (int i = 0; i < 900; i++) c.step(20);
    CHECK(c.h.calFinish() && !c.h.calibrating(), "the rest of the circle is accepted");
  }

  printf("10. north, and what survives a power cycle\n");
  {
    Car c;
    c.box.headingDeg = 37;
    c.hold(3);
    CHECK(c.h.setNorth(90) && fabs(angDiff(c.h.deg, 90)) < 0.01, "`north 90` reads 90 at once");
    c.hardIron[0] = 5;
    c.h.calStart();
    for (int i = 0; i < 1000; i++) c.step(20);
    CHECK(c.h.calFinish(), "calibrated");
    uint8_t blob[HudHeading::STORE_BYTES];
    c.h.pack(blob);
    HudHeading back;
    CHECK(back.unpack(blob), "a saved calibration reads back");
    CHECK(back.calibrated() && back.northOffsetDeg == c.h.northOffsetDeg &&
          memcmp(back.offset, c.h.offset, sizeof back.offset) == 0, "exactly");

    uint8_t bad[HudHeading::STORE_BYTES];
    memcpy(bad, blob, sizeof bad); bad[5] ^= 0x10;
    CHECK(!HudHeading().unpack(bad), "a flipped bit is refused");
    memset(bad, 0xFF, sizeof bad);
    CHECK(!HudHeading().unpack(bad), "erased flash is refused");
    memcpy(bad, blob, sizeof bad); bad[0] = 0xC3;
    CHECK(!HudHeading().unpack(bad), "the flat compass's old block is refused");
    float nan = NAN;
    memcpy(bad, blob, sizeof bad); memcpy(bad + 2, &nan, 4);
    uint8_t s = 0; for (int i = 0; i < HudHeading::STORE_BYTES - 1; i++) s = (uint8_t)(s + bad[i]);
    bad[HudHeading::STORE_BYTES - 1] = s;
    CHECK(!HudHeading().unpack(bad), "a NaN with a good checksum is refused");
    c.h.forget();
    CHECK(!c.h.calibrated() && c.h.offset[0] == 0 && c.h.northOffsetDeg == 0, "forget forgets");
  }

  printf("11. creeping round a parking space does not become the bias\n");
  {
    Car c;
    c.hold(3);
    c.speed = 0.1;                               // the bus reads under 1 km/h
    for (int i = 0; i < 500; i++) c.step(2.0);   // 10 s at 2 deg/s
    CHECK(fabs(c.h.bias[2]) < 1.05, "a parked second only nudges it");
    c.speed = 0;
    c.hold(20);
    CHECK(fabs(c.h.bias[2]) < 0.02, "and real parking puts it back");
    printf("    bias about Z %.3f deg/s after\n", c.h.bias[2]);
  }

  printf("12. with no bus, a steady curve is not taken for a car park\n");
  {
    Car c;
    c.speed = -1;
    c.hold(3);
    CHECK(c.h.biasKnown, "a still box learns its bias without a bus");
    for (int i = 0; i < 500; i++) c.step(1.5);   // a long gentle bend, 10 s
    CHECK(fabs(c.h.bias[2]) < 0.01, "the turn did not move the bias");
  }

  printf(failures ? "\n%d CHECK(s) FAILED\n" : "\nall checks passed\n", failures);
  return failures ? 1 : 0;
}
