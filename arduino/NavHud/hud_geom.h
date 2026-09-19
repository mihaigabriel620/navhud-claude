// Screen geometry: mirroring for the windscreen reflection, and keystone.
//
// ---------------------------------------------------------------------------
//  Why the correction is on the coordinates and not on the pixels
// ---------------------------------------------------------------------------
//
// A projector corrects keystone by warping the finished image. That needs the
// finished image in memory: 480 x 320 x 16 bpp is 307,200 bytes, and an
// ESP8266 has about 40 KB of heap. Even at 8-bit colour it is 150 KB. There is
// no arrangement of this hardware where a full-frame warp fits, so that
// approach is not slow here, it is impossible.
//
// But a head-up display does not draw photographs. It draws lines, boxes,
// triangles and text -- geometry with known coordinates. So the transform goes
// in *before* rasterising: every coordinate passes through a homography on its
// way to the panel. It costs no RAM at all, it composes with the delta
// renderer, and it leaves glyphs crisp, because nothing is ever resampled.
// On a screen read through a windscreen reflection in daylight, sharp text
// matters more than geometric perfection.
//
// The honest limit: a glyph is placed and sized by the transform, not sheared
// by it. Your layout goes rectangular; a capital H stays upright rather than
// leaning into the trapezoid. Below about fifteen degrees of correction you
// will not see it.
//
// ---------------------------------------------------------------------------
//  The maths
// ---------------------------------------------------------------------------
//
// Heckbert's square-to-quad homography ("Fundamentals of Texture Mapping and
// Image Warping", 1989, section 2.2). The source is always the whole screen,
// so it normalises to the unit square and there is a closed form -- no 8x8
// solve, no iteration, and it is exact.
//
//      X = (a*u + b*v + c) / (g*u + h*v + 1)
//      Y = (d*u + e*v + f) / (g*u + h*v + 1)
//
// The eight coefficients are computed once, when the app sends new corners,
// and every draw call after that is eight multiplies and one divide.
//
// No Arduino headers here on purpose: this file compiles on the host, so the
// maths is tested with a compiler and not with a car.

#ifndef HUD_GEOM_H
#define HUD_GEOM_H

#include <stdint.h>
#include <math.h>

/** How far a corner may be dragged, as a fraction of the screen. */
#define GEOM_MAX_PULL 0.35f

/**
 * Corner offsets, in pixels, in the order top-left, top-right, bottom-right,
 * bottom-left. Positive x is right, positive y is down. All zero is identity.
 */
struct GeomCorners {
  int16_t dx[4];
  int16_t dy[4];
};

struct Geom {
  // ---- what the app sets ----
  //
  // MIRRORING IS NOT DONE HERE. It is a MADCTL bit in the panel, applied by
  // picking a rotation (see geomRotation below): free, exact, and it leaves
  // every fillRect a fillRect instead of two triangles. `map()` ignores these
  // two flags on purpose; they live here only because they travel in the same
  // $GEOM message and are saved in the same block of flash.
  //
  // Which also settles the question the corner offsets raise: what space are
  // they in? The panel mirror and the windscreen reflection are both flips of
  // the same axis, so they cancel, and a layout coordinate is exactly what the
  // driver sees. The corners are therefore in the driver's view -- drag the
  // top-left one on the phone and the top-left of the reflection moves. No
  // un-mirroring anywhere.
  bool     mirrorX = false;      // reflected in the windscreen: usually true
  bool     mirrorY = false;
  GeomCorners corners = {{0, 0, 0, 0}, {0, 0, 0, 0}};

  // ---- derived ----
  float a = 1, b = 0, c = 0;
  float d = 0, e = 1, f = 0;
  float g = 0, h = 0;
  bool  identity = true;
  int   scrW = 480, scrH = 320;

  /**
   * Recompute the coefficients. Call after changing anything above.
   *
   * Returns false and falls back to identity when the corners describe a quad
   * that is degenerate or turned inside out -- a bad value arriving over the
   * cable must not be able to make the display undrawable, because the only
   * way back would be a screen you can no longer read.
   */
  bool rebuild(int screenW, int screenH) {
    scrW = screenW;
    scrH = screenH;

    identity = true;
    for (int i = 0; i < 4; i++) {
      if (corners.dx[i] != 0 || corners.dy[i] != 0) { identity = false; break; }
    }
    if (identity) {
      a = 1; b = 0; c = 0; d = 0; e = 1; f = 0; g = 0; h = 0;
      return true;
    }

    // Clamp before use. A corner dragged past a third of the screen is a typo
    // or a corrupted frame, not an intention.
    const float maxX = scrW * GEOM_MAX_PULL, maxY = scrH * GEOM_MAX_PULL;
    float qx[4], qy[4];
    static const float sx[4] = {0.f, 1.f, 1.f, 0.f};
    static const float sy[4] = {0.f, 0.f, 1.f, 1.f};
    for (int i = 0; i < 4; i++) {
      float ox = (float)corners.dx[i];
      float oy = (float)corners.dy[i];
      if (ox >  maxX) ox =  maxX;
      if (ox < -maxX) ox = -maxX;
      if (oy >  maxY) oy =  maxY;
      if (oy < -maxY) oy = -maxY;
      qx[i] = sx[i] * scrW + ox;
      qy[i] = sy[i] * scrH + oy;
    }

    // Reject a quad that is not convex and anticlockwise-consistent: a
    // self-crossing quad has no usable inverse and would send coordinates to
    // infinity.
    if (!convex(qx, qy)) { setIdentity(); return false; }

    const float dx1 = qx[1] - qx[2], dx2 = qx[3] - qx[2];
    const float dx3 = qx[0] - qx[1] + qx[2] - qx[3];
    const float dy1 = qy[1] - qy[2], dy2 = qy[3] - qy[2];
    const float dy3 = qy[0] - qy[1] + qy[2] - qy[3];

    if (nearZero(dx3) && nearZero(dy3)) {
      // Affine: the quad is a parallelogram, so there is no perspective term.
      a = qx[1] - qx[0]; b = qx[2] - qx[1]; c = qx[0];
      d = qy[1] - qy[0]; e = qy[2] - qy[1]; f = qy[0];
      g = 0; h = 0;
    } else {
      const float den = dx1 * dy2 - dx2 * dy1;
      if (nearZero(den)) { setIdentity(); return false; }
      g = (dx3 * dy2 - dx2 * dy3) / den;
      h = (dx1 * dy3 - dx3 * dy1) / den;
      a = qx[1] - qx[0] + g * qx[1];
      b = qx[3] - qx[0] + h * qx[3];
      c = qx[0];
      d = qy[1] - qy[0] + g * qy[1];
      e = qy[3] - qy[0] + h * qy[3];
      f = qy[0];
    }
    return true;
  }

  /**
   * Layout point in, panel point out. The homography, and nothing else.
   *
   * This deliberately does NOT mirror -- see the note above `mirrorX`. Doing
   * it here as well as in the panel would cancel out to no mirror at all.
   */
  void map(float x, float y, float* ox, float* oy) const {
    if (identity) { *ox = x; *oy = y; return; }

    const float u = x / (float)scrW;
    const float v = y / (float)scrH;
    float den = g * u + h * v + 1.f;
    // Behind the eye point. Cannot happen for a quad that passed convex(),
    // but a divide by ~zero here would put a triangle at +/-2 billion and the
    // panel driver would spend a frame drawing it.
    if (den < 0.05f && den > -0.05f) den = (den < 0.f) ? -0.05f : 0.05f;
    *ox = (a * u + b * v + c) / den;
    *oy = (d * u + e * v + f) / den;
  }

  /**
   * How much the transform stretches things around this point, 1.0 = unchanged.
   *
   * Used to pick a font size and a line width, so a corner that has been
   * squeezed does not keep drawing at full size and overflow its neighbour.
   * It is the geometric mean of the two axis scales, which for a modest
   * keystone is within a percent of the proper Jacobian determinant and costs
   * four calls to map() instead of a matrix.
   */
  float scaleAt(float x, float y) const {
    if (identity) return 1.f;
    const float d0 = 4.f;
    float ax, ay, bx, by, cx0, cy0, dx0, dy0;
    map(x - d0, y, &ax, &ay);
    map(x + d0, y, &bx, &by);
    map(x, y - d0, &cx0, &cy0);
    map(x, y + d0, &dx0, &dy0);
    const float sx2 = hypotf(bx - ax, by - ay) / (2.f * d0);
    const float sy2 = hypotf(dx0 - cx0, dy0 - cy0) / (2.f * d0);
    float s = sqrtf(sx2 * sy2);
    if (s < 0.35f) s = 0.35f;
    if (s > 2.5f)  s = 2.5f;
    return s;
  }

 private:
  void setIdentity() {
    identity = true;
    a = 1; b = 0; c = 0; d = 0; e = 1; f = 0; g = 0; h = 0;
  }

  static bool nearZero(float v) { return v > -1e-6f && v < 1e-6f; }

  /** All four cross products the same sign: convex and not self-crossing. */
  static bool convex(const float* x, const float* y) {
    int sign = 0;
    for (int i = 0; i < 4; i++) {
      const int j = (i + 1) & 3, k = (i + 2) & 3;
      const float z = (x[j] - x[i]) * (y[k] - y[j]) - (y[j] - y[i]) * (x[k] - x[j]);
      if (z > 1e-3f)      { if (sign < 0) return false; sign = 1; }
      else if (z < -1e-3f) { if (sign > 0) return false; sign = -1; }
      else return false;                       // three corners in a line
    }
    return sign != 0;
  }
};

/**
 * The TFT_eSPI rotation that gives landscape plus the requested mirrors.
 *
 * ST7796 (and ILI9341, whose table is identical) accepts rotations 0..7, not
 * 0..3 -- `rotation = m % 8` in TFT_Drivers/ST7796_Rotation.h. Cases 4..7 are
 * the mirrored halves, and every one of 1/3/5/7 sets _width=480, _height=320,
 * so clipping and the viewport stay right. Hand-writing MADCTL over the top of
 * setRotation(1) would flip the pixels and leave _width/_height stale, which is
 * the bug this function exists to avoid.
 *
 *   rot 1 = MV|BGR       0x28   landscape
 *   rot 7 = MY|MV|BGR    0xA8   landscape, mirrored left-right
 *   rot 5 = MV|MX|BGR    0x68   landscape, mirrored top-bottom
 *   rot 3 = MX|MY|MV|BGR 0xE8   landscape, both (= rotated 180 degrees)
 *
 * MV swaps the address axes, so in landscape it is MY that flips the long
 * axis: MX is the *vertical* mirror here and MY the horizontal one. Getting
 * that backwards is a five-minute mistake on the bench and an hour of
 * head-scratching in a car, which is why it is written down.
 */
inline uint8_t geomRotation(bool mirrorX, bool mirrorY) {
  if (mirrorX && mirrorY) return 3;
  if (mirrorX)            return 7;
  if (mirrorY)            return 5;
  return 1;
}

/** True when two settings blocks would put identical pixels on the panel. */
inline bool geomSame(const Geom& a, const Geom& b) {
  if (a.mirrorX != b.mirrorX || a.mirrorY != b.mirrorY) return false;
  for (int i = 0; i < 4; i++) {
    if (a.corners.dx[i] != b.corners.dx[i]) return false;
    if (a.corners.dy[i] != b.corners.dy[i]) return false;
  }
  return true;
}

#endif  // HUD_GEOM_H
