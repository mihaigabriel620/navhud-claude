// ---------------------------------------------------------------------------
//  hud_aa.h -- anti-aliased glyphs, drawn as ONE shape, pixel by pixel.
// ---------------------------------------------------------------------------
//
//  A glyph is a few simple parts -- round-ended strokes, triangles, the top
//  half of a ring -- and aaFill() paints their UNION. Each pixel's coverage is
//  the largest any part gives it, so where two parts overlap there is no edge
//  to blend and no seam, and along the outline the edge is blended against the
//  background exactly as TFT_eSPI's own smooth line blends it (same distance
//  rule, same thresholds, same fastBlend). Drawing the parts one after another
//  with the library's smooth primitives cannot do that: each part's soft edge
//  lands on the part drawn before it and leaves a dark line inside the glyph.
//
//  Cost: a pixel is only worked out where some part's box covers it, the
//  square root only on the one-pixel edge band, and runs of solid pixels go
//  out as one drawFastHLine. Glyphs are redrawn only when the manoeuvre
//  changes, never on the 4 Hz distance tick.
//
//  Under keystone a pixel cannot be mapped on its own (a stretched area would
//  come out with holes), so each part is drawn with the library's smooth
//  primitive through HudCanvas instead: still smooth, with the faint seams
//  that approach has.
// ---------------------------------------------------------------------------
#ifndef HUD_AA_H
#define HUD_AA_H

enum : uint8_t { AA_CAP = 0, AA_TRI = 1, AA_TOPRING = 2, AA_RING = 3 };

/** One part of a glyph. Build with aaCap / aaTri / aaTopRing. */
struct AaPrim {
  uint8_t kind;
  float ax, ay, bx, by, cx, cy;   // CAP: a to b. TRI: the corners. TOPRING: centre a.
  float r;                        // CAP: half the width. TOPRING: middle radius.
  float hw;                       // TOPRING: half the width.
};

/** A stroke from a to b, `r` either side, with round ends. a == b is a disc. */
static inline AaPrim aaCap(float ax, float ay, float bx, float by, float r) {
  AaPrim p; memset(&p, 0, sizeof p);
  p.kind = AA_CAP; p.ax = ax; p.ay = ay; p.bx = bx; p.by = by; p.r = r;
  return p;
}

static inline AaPrim aaTri(float ax, float ay, float bx, float by, float cx, float cy) {
  AaPrim p; memset(&p, 0, sizeof p);
  p.kind = AA_TRI; p.ax = ax; p.ay = ay; p.bx = bx; p.by = by; p.cx = cx; p.cy = cy;
  return p;
}

/** The half of a ring above its centre (the U-turn's bend). */
static inline AaPrim aaTopRing(float cx, float cy, float rm, float hw) {
  AaPrim p; memset(&p, 0, sizeof p);
  p.kind = AA_TOPRING; p.ax = cx; p.ay = cy; p.r = rm; p.hw = hw;
  return p;
}

/** A whole ring. drawArc(cx, cy, ro, ri) is aaRing(cx, cy, (ro+ri)/2, (ro-ri)/2 + 0.5). */
static inline AaPrim aaRing(float cx, float cy, float rm, float hw) {
  AaPrim p = aaTopRing(cx, cy, rm, hw);
  p.kind = AA_RING;
  return p;
}

/**
 * An arrow head pointing `angle` degrees from (cx, cy), 0 up and +90 right:
 * tip at `r`, base `headW` back from it, 0.62 * headW either side.
 */
static AaPrim aaHead(float cx, float cy, float angle, float r, float headW) {
  const float a = angle * DEG_TO_RAD;
  const float ux = sinf(a), uy = -cosf(a);
  const float bx = cx + ux * (r - headW), by = cy + uy * (r - headW);
  const float px = -uy * headW * 0.62f, py = ux * headW * 0.62f;
  return aaTri(cx + ux * r, cy + uy * r, bx + px, by + py, bx - px, by - py);
}

/** TFT_eSPI's fastBlend (TFT_eSPI.h), so edges blend exactly as the library's. */
static uint16_t aaBlend(uint8_t alpha, uint16_t fgc, uint16_t bgc) {
  uint32_t rxb = bgc & 0xF81F;
  rxb += ((fgc & 0xF81F) - rxb) * (alpha >> 2) >> 6;
  uint32_t xgx = bgc & 0x07E0;
  xgx += ((fgc & 0x07E0) - xgx) * alpha >> 8;
  return (uint16_t)((rxb & 0xF81F) | (xgx & 0x07E0));
}

// What aaFill works out once per part.
struct AaPrep_ {
  int x0, y0, x1, y1;             // the pixels it can touch
  float k0, k1, k2, k3, k4, k5;   // CAP: b-a, 1/|b-a|^2, solid and clear radii^2
  float nx[3], ny[3], nc[3];      // TRI: outward edge normals
};

static float aaClamp01_(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

/** Coverage of one part at a pixel centre, 0..1. */
static float aaCover_(const AaPrim& p, const AaPrep_& q, float x, float y) {
  switch (p.kind) {
    case AA_CAP: {
      float h = ((x - p.ax) * q.k0 + (y - p.ay) * q.k1) * q.k2;
      h = h < 0.0f ? 0.0f : (h > 1.0f ? 1.0f : h);
      const float dx = x - p.ax - q.k0 * h, dy = y - p.ay - q.k1 * h;
      const float d2 = dx * dx + dy * dy;
      if (d2 <= q.k3) return 1.0f;
      if (d2 >= q.k4) return 0.0f;
      return aaClamp01_(p.r + 0.5f - sqrtf(d2));      // drawWedgeLine's alpha
    }
    case AA_TRI: {
      float sd = -1e9f;
      for (int i = 0; i < 3; i++) {
        const float d = q.nx[i] * x + q.ny[i] * y - q.nc[i];
        if (d > sd) sd = d;
      }
      return aaClamp01_(0.5f - sd);
    }
    default: {                                        // AA_TOPRING, AA_RING
      const float half = p.kind == AA_RING ? 1.0f : aaClamp01_(0.5f - (y - p.ay));
      if (half <= 0.0f) return 0.0f;
      const float dx = x - p.ax, dy = y - p.ay;
      const float d2 = dx * dx + dy * dy;
      if (d2 <= q.k2 || d2 >= q.k3) return 0.0f;
      const float ring = (d2 >= q.k0 && d2 <= q.k1)
          ? 1.0f : aaClamp01_(p.hw + 0.5f - fabsf(sqrtf(d2) - p.r));
      return ring < half ? ring : half;
    }
  }
}

/** Work out once what aaCover_ needs for one part, and the pixels it can touch. */
static void aaPrep_(const AaPrim& a, AaPrep_& e) {
  memset(&e, 0, sizeof e);
  float lx, ly, hx, hy;
  if (a.kind == AA_CAP) {
    e.k0 = a.bx - a.ax; e.k1 = a.by - a.ay;
    const float bb = e.k0 * e.k0 + e.k1 * e.k1;
    e.k2 = bb > 1e-6f ? 1.0f / bb : 0.0f;
    const float rs = a.r - 0.5f, rc = a.r + 0.5f;
    e.k3 = rs > 0.0f ? rs * rs : -1.0f;
    e.k4 = rc * rc;
    lx = fminf(a.ax, a.bx) - a.r; hx = fmaxf(a.ax, a.bx) + a.r;
    ly = fminf(a.ay, a.by) - a.r; hy = fmaxf(a.ay, a.by) + a.r;
  } else if (a.kind == AA_TRI) {
    const float vx[3] = { a.ax, a.bx, a.cx }, vy[3] = { a.ay, a.by, a.cy };
    const float cross = (a.bx - a.ax) * (a.cy - a.ay) - (a.by - a.ay) * (a.cx - a.ax);
    for (int k = 0; k < 3; k++) {
      const int j = (k + 1) % 3;
      float ex = vx[j] - vx[k], ey = vy[j] - vy[k];
      const float len = sqrtf(ex * ex + ey * ey);
      if (len < 1e-6f) { e.nc[k] = -1e9f; continue; }   // no area: covers nothing
      ex /= len; ey /= len;
      e.nx[k] = cross > 0 ? ey : -ey;
      e.ny[k] = cross > 0 ? -ex : ex;
      e.nc[k] = e.nx[k] * vx[k] + e.ny[k] * vy[k];
    }
    lx = fminf(a.ax, fminf(a.bx, a.cx)); hx = fmaxf(a.ax, fmaxf(a.bx, a.cx));
    ly = fminf(a.ay, fminf(a.by, a.cy)); hy = fmaxf(a.ay, fmaxf(a.by, a.cy));
  } else {
    const float ro = a.r + a.hw, ri = a.r - a.hw;
    e.k0 = (ri + 0.5f) * (ri + 0.5f);               // solid band
    e.k1 = (ro - 0.5f) * (ro - 0.5f);
    e.k2 = ri > 0.5f ? (ri - 0.5f) * (ri - 0.5f) : -1.0f;   // clear inside / outside
    e.k3 = (ro + 0.5f) * (ro + 0.5f);
    lx = a.ax - ro; hx = a.ax + ro; ly = a.ay - ro;
    hy = a.kind == AA_RING ? a.ay + ro : a.ay + 0.5f;
  }
  e.x0 = (int)floorf(lx) - 1; e.x1 = (int)ceilf(hx) + 1;
  e.y0 = (int)floorf(ly) - 1; e.y1 = (int)ceilf(hy) + 1;
}

/** Paint the union of `n` parts in `col`, edges blended against `bg`. */
static void aaFill(const AaPrim* p, int n, uint16_t col, uint16_t bg) {
  if (n <= 0 || n > 6) return;
  if (!tft.geom.identity) {
    for (int i = 0; i < n; i++) {
      const AaPrim& a = p[i];
      if (a.kind == AA_CAP) {
        tft.drawWideLine(a.ax, a.ay, a.bx, a.by, a.r * 2.0f, col, bg);
      } else if (a.kind == AA_TRI) {
        tft.fillTriangle(a.ax, a.ay, a.bx, a.by, a.cx, a.cy, col);
      } else {                          // TOPRING: 9 o'clock round to 3
        const bool top = a.kind == AA_TOPRING;
        tft.drawArc((int)lroundf(a.ax), (int)lroundf(a.ay), (int)lroundf(a.r + a.hw),
                    (int)lroundf(a.r - a.hw), top ? 90 : 0, top ? 270 : 360, col, bg, true);
      }
    }
    return;
  }

  AaPrep_ q[6];
  int bx0 = SCR_W, by0 = SCR_H, bx1 = -1, by1 = -1;
  for (int i = 0; i < n; i++) {
    AaPrep_& e = q[i];
    aaPrep_(p[i], e);
    if (e.x0 < bx0) bx0 = e.x0;
    if (e.y0 < by0) by0 = e.y0;
    if (e.x1 > bx1) bx1 = e.x1;
    if (e.y1 > by1) by1 = e.y1;
  }
  if (bx0 < 0) bx0 = 0;
  if (by0 < 0) by0 = 0;
  if (bx1 > SCR_W - 1) bx1 = SCR_W - 1;
  if (by1 > SCR_H - 1) by1 = SCR_H - 1;

  for (int y = by0; y <= by1; y++) {
    int run = -1;                                     // start of a solid run
    for (int x = bx0; x <= bx1 + 1; x++) {
      float a = 0.0f;
      if (x <= bx1) {
        for (int i = 0; i < n && a < 1.0f; i++) {
          const AaPrep_& e = q[i];
          if (x < e.x0 || x > e.x1 || y < e.y0 || y > e.y1) continue;
          const float c = aaCover_(p[i], e, (float)x, (float)y);
          if (c > a) a = c;
        }
      }
      if (a > 31.0f / 32.0f) { if (run < 0) run = x; continue; }
      if (run >= 0) { tft.drawFastHLine(run, y, x - run, col); run = -1; }
      if (a > 1.0f / 32.0f) tft.drawPixel(x, y, aaBlend((uint8_t)(a * 255.0f), col, bg));
    }
  }
}

/**
 * Two parts drawn one after the other with the library's smooth primitives,
 * `a` and `b`, both in `col`: where their soft edges overlap, the one drawn
 * second was blended against the background on top of the first, and that
 * corner comes out darker than either. Repaint just those pixels -- the ones
 * both edges only partly cover -- with the union, within `half` px of
 * (cx, cy). Every other pixel is left exactly as it was drawn. Nothing to do
 * under keystone, where the parts are not where these numbers say.
 */
static void aaMendSeam(const AaPrim& a, const AaPrim& b, float cx, float cy, float half,
                       uint16_t col, uint16_t bg) {
  if (!tft.geom.identity) return;
  AaPrep_ qa, qb;
  aaPrep_(a, qa);
  aaPrep_(b, qb);
  const int x0 = (int)floorf(cx - half), x1 = (int)ceilf(cx + half);
  const int y0 = (int)floorf(cy - half), y1 = (int)ceilf(cy + half);
  for (int y = y0 < 0 ? 0 : y0; y <= y1 && y < SCR_H; y++) {
    for (int x = x0 < 0 ? 0 : x0; x <= x1 && x < SCR_W; x++) {
      const float ca = aaCover_(a, qa, (float)x, (float)y);
      const float cb = aaCover_(b, qb, (float)x, (float)y);
      const float lo = 1.0f / 32.0f, hi = 31.0f / 32.0f;
      if (ca <= lo || cb <= lo || ca > hi || cb > hi) continue;
      const float c = ca > cb ? ca : cb;
      tft.drawPixel(x, y, aaBlend((uint8_t)(c * 255.0f), col, bg));
    }
  }
}

#endif  // HUD_AA_H
