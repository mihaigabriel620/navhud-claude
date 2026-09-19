// The keystone maths, checked against properties rather than screenshots.
//
// A homography has three properties that matter for a HUD, and each one is a
// bug you would otherwise find on a dashboard in traffic:
//
//   1. It maps the corners exactly where you asked. If it does not, the
//      adjustment in the app does not mean anything.
//   2. It keeps straight lines straight. That is what makes it safe to
//      transform only the endpoints of a line instead of every pixel along it
//      -- the whole design rests on this being true.
//   3. Identity is exactly identity, to the bit. Otherwise every screen moves
//      by half a pixel the moment the feature exists, whether or not anyone
//      turned it on.

#include <cstdio>
#include <cmath>
#include <cstring>
#include "../NavHud/hud_geom.h"
#include "../NavHud/hud_store.h"

static int failures = 0;
#define CHECK(cond, msg) do { \
  if (!(cond)) { printf("  FAIL: %s\n", msg); failures++; } } while (0)

static bool near(float a, float b, float tol = 0.02f) {
  return fabsf(a - b) <= tol;
}

static const int W = 480, H = 320;

int main() {
  printf("1. identity is exactly identity\n");
  {
    Geom g;
    CHECK(g.rebuild(W, H), "identity rebuild");
    CHECK(g.identity, "flagged identity");
    for (int y = 0; y <= H; y += 40) {
      for (int x = 0; x <= W; x += 40) {
        float ox, oy;
        g.map((float)x, (float)y, &ox, &oy);
        CHECK(ox == (float)x && oy == (float)y, "identity moved a point");
      }
    }
    CHECK(g.scaleAt(240, 160) == 1.f, "identity scale is 1");
  }

  printf("2. the corners land where the app asked\n");
  {
    // Vertical keystone: pull the top edge in, which is what a dash tilted
    // back does to the reflection.
    Geom g;
    g.corners.dx[0] =  40; g.corners.dy[0] = 0;    // top-left  in
    g.corners.dx[1] = -40; g.corners.dy[1] = 0;    // top-right in
    CHECK(g.rebuild(W, H), "keystone rebuild");
    CHECK(!g.identity, "not identity");

    struct { float x, y, wantX, wantY; } corner[4] = {
      {0,        0,        40.f,       0.f},
      {(float)W, 0,        (float)W - 40.f, 0.f},
      {(float)W, (float)H, (float)W,   (float)H},
      {0,        (float)H, 0.f,        (float)H},
    };
    for (int i = 0; i < 4; i++) {
      float ox, oy;
      g.map(corner[i].x, corner[i].y, &ox, &oy);
      char m[80];
      snprintf(m, sizeof m, "corner %d landed at %.2f,%.2f", i, ox, oy);
      CHECK(near(ox, corner[i].wantX) && near(oy, corner[i].wantY), m);
    }
  }

  printf("3. straight lines stay straight\n");
  {
    // The property the whole design rests on: transform two endpoints, draw a
    // straight line between them, and every point that *should* be on that
    // line is on it. Checked with a full projective quad, not just an affine
    // one, because affine keeps lines straight trivially.
    Geom g;
    g.corners.dx[0] =  55; g.corners.dy[0] =  12;
    g.corners.dx[1] = -48; g.corners.dy[1] =   9;
    g.corners.dx[2] =  -6; g.corners.dy[2] = -18;
    g.corners.dx[3] =  11; g.corners.dy[3] = -22;
    CHECK(g.rebuild(W, H), "projective rebuild");

    struct { float x0, y0, x1, y1; } seg[] = {
      {  0.f,  40.f, 480.f,  40.f},        // horizontal
      {120.f,   0.f, 120.f, 320.f},        // vertical
      {  0.f,   0.f, 480.f, 320.f},        // diagonal
      { 40.f, 300.f, 460.f,  20.f},        // the other diagonal
    };
    for (auto& s : seg) {
      float ax, ay, bx, by;
      g.map(s.x0, s.y0, &ax, &ay);
      g.map(s.x1, s.y1, &bx, &by);
      float worst = 0.f;
      for (int i = 1; i < 20; i++) {
        const float t = i / 20.f;
        float mx, my;
        g.map(s.x0 + (s.x1 - s.x0) * t, s.y0 + (s.y1 - s.y0) * t, &mx, &my);
        // Perpendicular distance from the mapped midpoint to the mapped chord.
        const float dx = bx - ax, dy = by - ay;
        const float len = sqrtf(dx * dx + dy * dy);
        const float dist = fabsf((mx - ax) * dy - (my - ay) * dx) / len;
        if (dist > worst) worst = dist;
      }
      char m[96];
      snprintf(m, sizeof m, "line bowed by %.2f px (endpoints only would lie)", worst);
      CHECK(worst < 0.5f, m);
    }
  }

  printf("4. a corrupted frame cannot make the screen unreadable\n");
  {
    // Bad values arrive: the cable is in a car. Falling back to identity is
    // the only safe answer, because a display warped into a spike is one you
    // can no longer use to reach the menu that would fix it.
    struct { const char* what; int16_t dx[4], dy[4]; } bad[] = {
      {"inside out",   { 400, -400,  400, -400}, {0, 0, 0, 0}},
      {"all collapsed",{ 240, -240, -240,  240}, {160, 160, -160, -160}},
      {"crossed",      {   0,    0,    0,    0}, {320, 0, -320, 0}},
    };
    for (auto& b : bad) {
      Geom g;
      for (int i = 0; i < 4; i++) { g.corners.dx[i] = b.dx[i]; g.corners.dy[i] = b.dy[i]; }
      g.rebuild(W, H);
      float ox, oy;
      g.map(240, 160, &ox, &oy);
      char m[96];
      snprintf(m, sizeof m, "%s: centre went to %.1f,%.1f", b.what, ox, oy);
      CHECK(std::isfinite(ox) && std::isfinite(oy) &&
            ox > -2000 && ox < 2000 && oy > -2000 && oy < 2000, m);
    }
  }

  printf("5. an extreme but legal pull is clamped, not obeyed\n");
  {
    Geom g;
    g.corners.dx[0] = 3000;                 // someone typed an extra zero
    g.rebuild(W, H);
    float ox, oy;
    g.map(0, 0, &ox, &oy);
    char m[80];
    snprintf(m, sizeof m, "clamped to %.1f (max %.1f)", ox, W * GEOM_MAX_PULL);
    CHECK(ox <= W * GEOM_MAX_PULL + 1.f, m);
  }

  printf("6. mirroring is the panel's job, not this file's\n");
  {
    // The windscreen flips the image left-right, and the panel flips it back
    // with one MADCTL bit -- for free, and without turning every fillRect into
    // two triangles. If map() mirrored as well the two would cancel and the
    // text would come out backwards on the glass, which is a fault you can
    // only see by sitting in the car.
    Geom g;
    g.mirrorX = true;
    g.rebuild(W, H);
    CHECK(g.identity, "a pure mirror is still the identity transform");
    float ox, oy;
    g.map(0, 100, &ox, &oy);
    CHECK(near(ox, 0.f) && near(oy, 100.f), "map() leaves the left edge alone");
    g.mirrorY = true;
    g.rebuild(W, H);
    g.map((float)W, (float)H, &ox, &oy);
    CHECK(near(ox, (float)W) && near(oy, (float)H), "and both flags together");

    // The four landscape rotations, from TFT_Drivers/ST7796_Rotation.h. All
    // four are 480x320; only the MADCTL byte differs.
    CHECK(geomRotation(false, false) == 1, "no mirror -> rotation 1 (0x28)");
    CHECK(geomRotation(true,  false) == 7, "left-right -> rotation 7 (0xA8)");
    CHECK(geomRotation(false, true)  == 5, "top-bottom -> rotation 5 (0x68)");
    CHECK(geomRotation(true,  true)  == 3, "both -> rotation 3 (0xE8)");
  }

  printf("6b. two settings blocks compare on what reaches the panel\n");
  {
    Geom a, b;
    CHECK(geomSame(a, b), "two fresh ones are the same");
    b.corners.dy[2] = 1;
    CHECK(!geomSame(a, b), "one pixel on one corner is a difference");
    b.corners.dy[2] = 0;
    b.mirrorY = true;
    CHECK(!geomSame(a, b), "so is a mirror flag");
    // This is what stands between a slider drag and a flash erase per frame.
  }

  printf("7. the scale hint tracks the squeeze\n");
  {
    // Pull the top edge in hard; the top of the screen is now compressed, so
    // things drawn there have to be drawn smaller or they will overlap.
    Geom g;
    g.corners.dx[0] =  70; g.corners.dx[1] = -70;
    g.rebuild(W, H);
    const float top = g.scaleAt(240.f, 10.f);
    const float bottom = g.scaleAt(240.f, 310.f);
    char m[96];
    snprintf(m, sizeof m, "top %.3f should be under bottom %.3f", top, bottom);
    CHECK(top < bottom, m);
    CHECK(top > 0.3f && bottom < 2.6f, "scale stays inside its clamps");
  }

  printf("8. the settings survive being turned into bytes and back\n");
  {
    Geom in;
    in.mirrorX = true; in.mirrorY = false;
    in.corners.dx[0] =  44; in.corners.dy[0] =  16;
    in.corners.dx[1] = -44; in.corners.dy[1] =  16;
    in.corners.dx[2] =  -8; in.corners.dy[2] =  -4;
    in.corners.dx[3] =   8; in.corners.dy[3] =  -4;

    uint8_t b[HUD_STORE_BYTES];
    hudStorePack(in, b);
    Geom out;
    CHECK(hudStoreUnpack(b, out), "a block we just wrote reads back");
    CHECK(geomSame(in, out), "and every field came back unchanged");

    // Negative offsets are the whole point of the little-endian int16 pair:
    // a naive single-byte encoding would have turned -44 into 212.
    CHECK(out.corners.dx[1] == -44, "negative offsets survive");

    for (int i = 0; i < HUD_STORE_BYTES; i++) {
      uint8_t c[HUD_STORE_BYTES];
      memcpy(c, b, sizeof c);
      c[i] ^= 0x40;
      Geom bad;
      char m[64];
      snprintf(m, sizeof m, "byte %d flipped is rejected", i);
      CHECK(!hudStoreUnpack(c, bad), m);
    }

    uint8_t blank[HUD_STORE_BYTES];
    memset(blank, 0xFF, sizeof blank);
    Geom fresh;
    CHECK(!hudStoreUnpack(blank, fresh), "erased flash is not a setting");
    memset(blank, 0x00, sizeof blank);
    CHECK(!hudStoreUnpack(blank, fresh), "and neither is a block of zeroes");
    // Zeroes matter: an all-zero block has a valid XOR checksum of zero, so
    // only the magic stands between a wiped sector and a "saved" identity.
  }

  if (failures) { printf("\n%d CHECK(s) FAILED\n", failures); return 1; }
  printf("\nall checks passed\n");
  return 0;
}
