// Every draw call, on its way to the panel.
//
// The themes call `tft.fillRect(...)`, `tft.drawString(...)` and so on. This
// class has the same method names, so `tft` becomes a HudCanvas and not one
// line of theme code has to know that keystone exists. Each call maps its
// coordinates through Geom and then forwards to the real driver.
//
// Three rules it follows, and each one is there because the alternative is a
// bug you would only find in a car:
//
//   * Identity forwards verbatim. Not "maps to the same numbers" -- calls the
//     identical driver method with the identical arguments. With keystone off,
//     which is how it leaves the factory, this file is not allowed to change a
//     single pixel of what shipped before it existed. Note that the windscreen
//     mirror does NOT take it off that path: mirroring is a MADCTL bit in the
//     panel, so the common case -- mirrored, no keystone -- costs nothing.
//
//   * A rectangle under a projective transform is a quadrilateral, not a
//     rectangle. It is drawn as two triangles. Drawing it as a rect with
//     transformed corners would leave the fill un-keystoned and the outline
//     keystoned, which looks like a rendering fault rather than a setting.
//
//   * Text is positioned and sized by the transform, never resampled. Warping
//     glyph pixels is what makes projector keystone look soft, and this thing
//     is read through a windscreen reflection in daylight.

#ifndef HUD_CANVAS_H
#define HUD_CANVAS_H

#include <TFT_eSPI.h>
#include "hud_geom.h"

/**
 * The subset of TFT_eSPI the themes actually use, with geometry applied.
 *
 * Deliberately not a subclass. TFT_eSPI's methods are not virtual, so
 * inheriting would let a missed override silently bypass the transform -- the
 * failure would be one widget in the wrong place, which is exactly the kind of
 * thing that survives a review. Composition means anything not wrapped is a
 * compile error instead.
 */
class HudCanvas {
 public:
  Geom geom;

  explicit HudCanvas(TFT_eSPI& target) : t_(target) {}

  TFT_eSPI& raw() { return t_; }

  // ---- pass-through: no geometry involved -------------------------------
  void init()                       { t_.init(); }
  void setRotation(int r)           { t_.setRotation(r); }
  void writecommand(uint8_t c)      { t_.writecommand(c); }
  void writedata(uint8_t d)         { t_.writedata(d); }
  void fillScreen(uint16_t c)       { t_.fillScreen(c); }
  // The datum is not the transform's business: drawString maps the anchor
  // point, and the anchor is what the datum names. Nothing here needs to
  // know which corner of the glyph box it is.
  void setTextDatum(uint8_t d)      { t_.setTextDatum(d); }
  void setTextColor(uint16_t f)     { t_.setTextColor(f); }
  void setTextColor(uint16_t f, uint16_t b) { t_.setTextColor(f, b); }
  void setTextPadding(int p)        { t_.setTextPadding(p); }
  void setTextSize(uint8_t n)       { t_.setTextSize(n); }

  // Smooth fonts pass straight through: which glyph bitmaps are loaded has
  // nothing to do with where on the panel they end up, which is all this
  // class is for. Guarded because the E60 and modern themes do not use them
  // and their builds do not define SMOOTH_FONT.
#if defined(SMOOTH_FONT) || defined(HUD_TEST)
  void loadFont(const uint8_t* f)   { t_.loadFont(f); }
  void unloadFont()                 { t_.unloadFont(); }
#endif
  int  textWidth(const char* s, uint8_t f) const { return t_.textWidth(s, f); }
  int  textWidth(const String& s, uint8_t f) const { return t_.textWidth(s, f); }

  // ---- filled shapes ------------------------------------------------------

  void fillRect(int x, int y, int w, int h, uint16_t col) {
    if (geom.identity) { t_.fillRect(x, y, w, h, col); return; }
    quad((float)x, (float)y, float(x + w), (float)y,
         float(x + w), float(y + h), (float)x, float(y + h), col);
  }

  void drawRect(int x, int y, int w, int h, uint16_t col) {
    if (geom.identity) { t_.drawRect(x, y, w, h, col); return; }
    outline((float)x, (float)y, float(x + w), (float)y,
            float(x + w), float(y + h), (float)x, float(y + h), col);
  }

  void drawRoundRect(int x, int y, int w, int h, int r, uint16_t col) {
    if (geom.identity) { t_.drawRoundRect(x, y, w, h, r, col); return; }
    // The rounding is a few pixels on a corner that is about to be moved
    // anyway; a straight outline is honest and cheap.
    drawRect(x, y, w, h, col);
  }

  void fillTriangle(float x0, float y0, float x1, float y1,
                    float x2, float y2, uint16_t col) {
    if (geom.identity) {
      t_.fillTriangle(x0, y0, x1, y1, x2, y2, col);
      return;
    }
    float ax, ay, bx, by, cx, cy;
    geom.map(x0, y0, &ax, &ay);
    geom.map(x1, y1, &bx, &by);
    geom.map(x2, y2, &cx, &cy);
    t_.fillTriangle(ax, ay, bx, by, cx, cy, col);
  }

  // ---- circles ------------------------------------------------------------
  //
  // A circle under a projective transform is an ellipse, and the driver has no
  // ellipse. Every circle in this UI is small -- an elbow joint, a roundabout
  // ring, a dot -- so it is drawn as a circle at the mapped centre with the
  // radius scaled by the local stretch. At the sizes involved the difference
  // from a true ellipse is under a pixel.

  void fillCircle(int cx, int cy, int r, uint16_t col) {
    if (geom.identity) { t_.fillCircle(cx, cy, r, col); return; }
    float ox, oy;
    geom.map((float)cx, (float)cy, &ox, &oy);
    t_.fillCircle((int)lroundf(ox), (int)lroundf(oy), scaledLen(cx, cy, r), col);
  }

  void drawCircle(int cx, int cy, int r, uint16_t col) {
    if (geom.identity) { t_.drawCircle(cx, cy, r, col); return; }
    float ox, oy;
    geom.map((float)cx, (float)cy, &ox, &oy);
    t_.drawCircle((int)lroundf(ox), (int)lroundf(oy), scaledLen(cx, cy, r), col);
  }

  // ---- hairlines ----------------------------------------------------------
  //
  // A horizontal line stops being horizontal once the screen is keystoned, so
  // these become triangles. One pixel thick, so the two triangles of a quad
  // would collapse; a single thin quad is drawn instead.

  void drawFastHLine(int x, int y, int w, uint16_t col) {
    if (geom.identity) { t_.drawFastHLine(x, y, w, col); return; }
    thinQuad((float)x, (float)y, float(x + w - 1), (float)y, col);
  }

  void drawFastVLine(int x, int y, int h, uint16_t col) {
    if (geom.identity) { t_.drawFastVLine(x, y, h, col); return; }
    thinQuad((float)x, (float)y, (float)x, float(y + h - 1), col);
  }

  // ---- text ---------------------------------------------------------------

  int drawString(const char* s, int x, int y, uint8_t font) {
    if (geom.identity) return t_.drawString(s, x, y, font);
    float ox, oy;
    geom.map((float)x, (float)y, &ox, &oy);
    return t_.drawString(s, (int)lroundf(ox), (int)lroundf(oy), fontFor(x, y, font));
  }

  int drawString(const String& s, int x, int y, uint8_t font) {
    return drawString(s.c_str(), x, y, font);
  }

  int drawNumber(long v, int x, int y, uint8_t font) {
    char b[24];
    snprintf(b, sizeof b, "%ld", v);
    return drawString(b, x, y, font);
  }

 private:
  TFT_eSPI& t_;

  /** A length at this point, after the local stretch. Never rounds to zero. */
  int scaledLen(int x, int y, int len) const {
    int v = (int)lroundf(len * geom.scaleAt((float)x, (float)y));
    return v < 1 ? 1 : v;
  }

  /**
   * Step a font down when its corner has been squeezed.
   *
   * The built-in fonts are a discrete ladder, so this cannot scale text
   * smoothly -- and that is the right trade. Resampling a glyph to 0.87 of its
   * size is how projector keystone turns small text to mush; keeping it at the
   * next size down keeps every pixel of every stroke exactly where the font
   * designer put it. Only a real squeeze moves it, so at modest angles nothing
   * changes size at all.
   */
  uint8_t fontFor(int x, int y, uint8_t font) const {
    const float s = geom.scaleAt((float)x, (float)y);
    if (s > 0.80f) return font;
    switch (font) {
      case 8: return 7;
      case 7: return 6;
      case 6: return 4;
      case 4: return 2;
      default: return font;
    }
  }

  void quad(float x0, float y0, float x1, float y1,
            float x2, float y2, float x3, float y3, uint16_t col) {
    float a[8];
    geom.map(x0, y0, &a[0], &a[1]);
    geom.map(x1, y1, &a[2], &a[3]);
    geom.map(x2, y2, &a[4], &a[5]);
    geom.map(x3, y3, &a[6], &a[7]);
    t_.fillTriangle(a[0], a[1], a[2], a[3], a[4], a[5], col);
    t_.fillTriangle(a[0], a[1], a[4], a[5], a[6], a[7], col);
  }

  void outline(float x0, float y0, float x1, float y1,
               float x2, float y2, float x3, float y3, uint16_t col) {
    float a[8];
    geom.map(x0, y0, &a[0], &a[1]);
    geom.map(x1, y1, &a[2], &a[3]);
    geom.map(x2, y2, &a[4], &a[5]);
    geom.map(x3, y3, &a[6], &a[7]);
    for (int i = 0; i < 4; i++) {
      const int j = (i + 1) & 3;
      hair(a[i * 2], a[i * 2 + 1], a[j * 2], a[j * 2 + 1], col);
    }
  }

  /** A one-pixel line between two already-mapped points. */
  void hair(float ax, float ay, float bx, float by, uint16_t col) {
    const float dx = bx - ax, dy = by - ay;
    const float len = sqrtf(dx * dx + dy * dy);
    if (len < 0.5f) return;
    const float px = -dy / len * 0.5f, py = dx / len * 0.5f;
    t_.fillTriangle(ax + px, ay + py, ax - px, ay - py, bx + px, by + py, col);
    t_.fillTriangle(bx + px, by + py, bx - px, by - py, ax - px, ay - py, col);
  }

  /** A one-pixel line between two screen points, mapped on the way. */
  void thinQuad(float x0, float y0, float x1, float y1, uint16_t col) {
    float ax, ay, bx, by;
    geom.map(x0, y0, &ax, &ay);
    geom.map(x1, y1, &bx, &by);
    hair(ax, ay, bx, by, col);
  }
};

#endif  // HUD_CANVAS_H
