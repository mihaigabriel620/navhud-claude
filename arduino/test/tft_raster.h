// Render mode for the TFT_eSPI stub: a real 480x320 RGB565 framebuffer.
//
// Only compiled with -DHUD_RENDER (the `render` target). tft_stub.h forwards
// every primitive here as well as recording its bounding box, so the layout
// tests are untouched and the renderer sees exactly the calls they see.
//
// Every algorithm is a transcription of TFT_eSPI 2.5.43 (TFT_eSPI.cpp and
// Extensions/Smooth_font.cpp), not a nicer version of it, because the point
// is to see what the panel shows -- including the library's quirks:
//   * fillTriangle takes int32_t, so HudCanvas's float corners are truncated
//     toward zero on the way in, exactly as the implicit conversion does.
//   * smooth-font edge pixels are blended against the text BACKGROUND colour,
//     never against what is already on the screen.
//   * a smooth font draws a hollow box for a glyph it does not carry, and
//     wraps to the next line (textwrapX defaults to true) when a glyph would
//     run past the right edge.
//   * padding fills only when the string is narrower than the pad, and with
//     the library's odd right-datum arithmetic.
// The built-in fonts (2, 4, 6, 7, 8) are the library's own tables, read from
// a checkout of TFT_eSPI (see the Makefile). Font 1 (GLCD) is never reached by
// the firmware -- it is only named while a smooth font is loaded -- so it is
// not rasterised; reaching it is reported.
#ifndef TFT_RASTER_H
#define TFT_RASTER_H

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>
#include <algorithm>
#include "pgmspace.h"
#include <Fonts/Font16.h>
#include <Fonts/Font32rle.h>
#include <Fonts/Font64rle.h>
#include <Fonts/Font7srle.h>
#include <Fonts/Font72rle.h>

class HudRaster {
 public:
  static const int kW = HUD_SCR_W, kH = HUD_SCR_H;
  std::vector<uint16_t> fb = std::vector<uint16_t>(kW * kH, 0);
  int glyphBoxes = 0;      // smooth glyphs drawn as the library's hollow box
  int font1Calls = 0;      // GLCD text, which this renderer does not draw

  // ---- text state, named and defaulted as in TFT_eSPI::init ------------
  uint16_t textcolor = 0xFFFF, textbgcolor = 0x0000;
  bool     fillbg = false;
  uint16_t padX = 0;
  uint8_t  textdatum = 0, textsize = 1;
  bool     isDigits = false;     // HudCanvas::drawNumber goes via drawString
  bool     textwrapX = true;
  int32_t  cursor_x = 0, cursor_y = 0, last_cursor_x = 0, bg_cursor_x = 0;

  void setTextColor(uint16_t c) { textcolor = textbgcolor = c; }
  void setTextColor(uint16_t c, uint16_t b) { textcolor = c; textbgcolor = b; fillbg = false; }
  void setTextPadding(uint16_t w) { padX = w; }
  void setTextDatum(uint8_t d) { textdatum = d; }
  void setTextSize(uint8_t s) { if (s > 7) s = 7; textsize = s > 0 ? s : 1; }

  // ---- primitives ---------------------------------------------------------
  void drawPixel(int32_t x, int32_t y, uint16_t c) {
    if (x < 0 || y < 0 || x >= kW || y >= kH) return;
    fb[y * kW + x] = c;
  }
  void drawFastHLine(int32_t x, int32_t y, int32_t w, uint16_t c) {
    if (y < 0 || x >= kW || y >= kH) return;
    if (x < 0) { w += x; x = 0; }
    if (x + w > kW) w = kW - x;
    if (w < 1) return;
    for (int32_t i = 0; i < w; i++) fb[y * kW + x + i] = c;
  }
  void drawFastVLine(int32_t x, int32_t y, int32_t h, uint16_t c) {
    if (x < 0 || x >= kW || y >= kH) return;
    if (y < 0) { h += y; y = 0; }
    if (y + h > kH) h = kH - y;
    if (h < 1) return;
    for (int32_t i = 0; i < h; i++) fb[(y + i) * kW + x] = c;
  }
  void fillRect(int32_t x, int32_t y, int32_t w, int32_t h, uint16_t c) {
    if (x >= kW || y >= kH) return;
    if (x < 0) { w += x; x = 0; }
    if (y < 0) { h += y; y = 0; }
    if (x + w > kW) w = kW - x;
    if (y + h > kH) h = kH - y;
    if (w < 1 || h < 1) return;
    for (int32_t j = 0; j < h; j++)
      for (int32_t i = 0; i < w; i++) fb[(y + j) * kW + x + i] = c;
  }
  void drawRect(int32_t x, int32_t y, int32_t w, int32_t h, uint16_t c) {
    drawFastHLine(x, y, w, c);
    drawFastHLine(x, y + h - 1, w, c);
    drawFastVLine(x, y + 1, h - 2, c);
    drawFastVLine(x + w - 1, y + 1, h - 2, c);
  }

  void drawCircle(int32_t x0, int32_t y0, int32_t r, uint16_t color) {
    if (r <= 0) return;
    int32_t f = 1 - r, ddF_y = -2 * r, ddF_x = 1, xs = -1, xe = 0, len = 0;
    bool first = true;
    do {
      while (f < 0) { ++xe; f += (ddF_x += 2); }
      f += (ddF_y += 2);
      if (xe - xs > 1) {
        if (first) {
          len = 2 * (xe - xs) - 1;
          drawFastHLine(x0 - xe, y0 + r, len, color);
          drawFastHLine(x0 - xe, y0 - r, len, color);
          drawFastVLine(x0 + r, y0 - xe, len, color);
          drawFastVLine(x0 - r, y0 - xe, len, color);
          first = false;
        } else {
          len = xe - xs++;
          drawFastHLine(x0 - xe, y0 + r, len, color);
          drawFastHLine(x0 - xe, y0 - r, len, color);
          drawFastHLine(x0 + xs, y0 - r, len, color);
          drawFastHLine(x0 + xs, y0 + r, len, color);
          drawFastVLine(x0 + r, y0 + xs, len, color);
          drawFastVLine(x0 + r, y0 - xe, len, color);
          drawFastVLine(x0 - r, y0 - xe, len, color);
          drawFastVLine(x0 - r, y0 + xs, len, color);
        }
      } else {
        ++xs;
        drawPixel(x0 - xe, y0 + r, color);
        drawPixel(x0 - xe, y0 - r, color);
        drawPixel(x0 + xs, y0 - r, color);
        drawPixel(x0 + xs, y0 + r, color);
        drawPixel(x0 + r, y0 + xs, color);
        drawPixel(x0 + r, y0 - xe, color);
        drawPixel(x0 - r, y0 - xe, color);
        drawPixel(x0 - r, y0 + xs, color);
      }
      xs = xe;
    } while (xe < --r);
  }

  void fillCircle(int32_t x0, int32_t y0, int32_t r, uint16_t color) {
    int32_t x = 0, dx = 1, dy = r + r, p = -(r >> 1);
    drawFastHLine(x0 - r, y0, dy + 1, color);
    while (x < r) {
      if (p >= 0) {
        drawFastHLine(x0 - x, y0 + r, dx, color);
        drawFastHLine(x0 - x, y0 - r, dx, color);
        dy -= 2; p -= dy; r--;
      }
      dx += 2; p += dx; x++;
      drawFastHLine(x0 - r, y0 + x, dy + 1, color);
      drawFastHLine(x0 - r, y0 - x, dy + 1, color);
    }
  }

  void drawCircleHelper(int32_t x0, int32_t y0, int32_t rr, uint8_t corner, uint16_t color) {
    if (rr <= 0) return;
    int32_t f = 1 - rr, ddF_x = 1, ddF_y = -2 * rr, xe = 0, xs = 0, len = 0;
    do {
      while (f < 0) { ++xe; f += (ddF_x += 2); }
      f += (ddF_y += 2);
      if (xe - xs == 1) {
        if (corner & 0x1) { drawPixel(x0 - xe, y0 - rr, color); drawPixel(x0 - rr, y0 - xe, color); }
        if (corner & 0x2) { drawPixel(x0 + rr, y0 - xe, color); drawPixel(x0 + xs + 1, y0 - rr, color); }
        if (corner & 0x4) { drawPixel(x0 + xs + 1, y0 + rr, color); drawPixel(x0 + rr, y0 + xs + 1, color); }
        if (corner & 0x8) { drawPixel(x0 - rr, y0 + xs + 1, color); drawPixel(x0 - xe, y0 + rr, color); }
      } else {
        len = xe - xs++;
        if (corner & 0x1) { drawFastHLine(x0 - xe, y0 - rr, len, color); drawFastVLine(x0 - rr, y0 - xe, len, color); }
        if (corner & 0x2) { drawFastVLine(x0 + rr, y0 - xe, len, color); drawFastHLine(x0 + xs, y0 - rr, len, color); }
        if (corner & 0x4) { drawFastHLine(x0 + xs, y0 + rr, len, color); drawFastVLine(x0 + rr, y0 + xs, len, color); }
        if (corner & 0x8) { drawFastVLine(x0 - rr, y0 + xs, len, color); drawFastHLine(x0 - xe, y0 + rr, len, color); }
      }
      xs = xe;
    } while (xe < rr--);
  }

  void drawRoundRect(int32_t x, int32_t y, int32_t w, int32_t h, int32_t r, uint16_t color) {
    drawFastHLine(x + r, y, w - r - r, color);
    drawFastHLine(x + r, y + h - 1, w - r - r, color);
    drawFastVLine(x, y + r, h - r - r, color);
    drawFastVLine(x + w - 1, y + r, h - r - r, color);
    drawCircleHelper(x + r, y + r, r, 1, color);
    drawCircleHelper(x + w - r - 1, y + r, r, 2, color);
    drawCircleHelper(x + w - r - 1, y + h - r - 1, r, 4, color);
    drawCircleHelper(x + r, y + h - r - 1, r, 8, color);
  }

  void fillTriangle(int32_t x0, int32_t y0, int32_t x1, int32_t y1,
                    int32_t x2, int32_t y2, uint16_t color) {
    int32_t a, b, y, last;
    if (y0 > y1) { std::swap(y0, y1); std::swap(x0, x1); }
    if (y1 > y2) { std::swap(y2, y1); std::swap(x2, x1); }
    if (y0 > y1) { std::swap(y0, y1); std::swap(x0, x1); }
    if (y0 == y2) {
      a = b = x0;
      if (x1 < a) a = x1; else if (x1 > b) b = x1;
      if (x2 < a) a = x2; else if (x2 > b) b = x2;
      drawFastHLine(a, y0, b - a + 1, color);
      return;
    }
    int32_t dx01 = x1 - x0, dy01 = y1 - y0, dx02 = x2 - x0, dy02 = y2 - y0,
            dx12 = x2 - x1, dy12 = y2 - y1, sa = 0, sb = 0;
    last = (y1 == y2) ? y1 : y1 - 1;
    for (y = y0; y <= last; y++) {
      a = x0 + sa / dy01; b = x0 + sb / dy02;
      sa += dx01; sb += dx02;
      if (a > b) std::swap(a, b);
      drawFastHLine(a, y, b - a + 1, color);
    }
    sa = dx12 * (y - y1);
    sb = dx02 * (y - y0);
    for (; y <= y2; y++) {
      a = x1 + sa / dy12; b = x0 + sb / dy02;
      sa += dx12; sb += dx02;
      if (a > b) std::swap(a, b);
      drawFastHLine(a, y, b - a + 1, color);
    }
  }

  static uint16_t alphaBlend(uint8_t alpha, uint16_t fgc, uint16_t bgc) {
    uint32_t rxb = bgc & 0xF81F;
    rxb += ((fgc & 0xF81F) - rxb) * (alpha >> 2) >> 6;
    uint32_t xgx = bgc & 0x07E0;
    xgx += ((fgc & 0x07E0) - xgx) * alpha >> 8;
    return (rxb & 0xF81F) | (xgx & 0x07E0);
  }

  // ---- smooth (.vlw) fonts ------------------------------------------------
  bool fontLoaded = false;
  const uint8_t* gArray = nullptr;
  uint16_t gCount = 0, yAdvance = 0, spaceWidth = 0, maxAscent = 0, maxDescent = 0;
  int16_t  ascent = 0, descent = 0;
  std::vector<uint16_t> gUnicode;
  std::vector<uint8_t>  gHeight, gWidth, gxAdvance;
  std::vector<int16_t>  gdY;
  std::vector<int8_t>   gdX;
  std::vector<uint32_t> gBitmap;

  void loadFont(const uint8_t* arr) {
    if (fontLoaded) unloadFont();
    gArray = arr;
    uint32_t p = 0;
    gCount   = (uint16_t)rd32(p);
                         rd32(p);                 // encoder version
    yAdvance = (uint16_t)rd32(p);                 // point size, overwritten below
                         rd32(p);
    ascent   = (int16_t)(uint16_t)rd32(p);
    descent  = (int16_t)(uint16_t)rd32(p);
    maxAscent = ascent; maxDescent = descent;
    yAdvance = ascent + descent;
    spaceWidth = yAdvance / 4;
    fontLoaded = true;
    // loadMetrics()
    uint32_t bitmapPtr = 24 + (uint32_t)gCount * 28;
    gUnicode.assign(gCount, 0); gHeight.assign(gCount, 0); gWidth.assign(gCount, 0);
    gxAdvance.assign(gCount, 0); gdY.assign(gCount, 0); gdX.assign(gCount, 0);
    gBitmap.assign(gCount, 0);
    for (uint16_t n = 0; n < gCount; n++) {
      gUnicode[n]  = (uint16_t)rd32(p);
      gHeight[n]   = (uint8_t)rd32(p);
      gWidth[n]    = (uint8_t)rd32(p);
      gxAdvance[n] = (uint8_t)rd32(p);
      gdY[n]       = (int16_t)rd32(p);
      gdX[n]       = (int8_t)rd32(p);
                     rd32(p);
      // maxAscent is deliberately NOT raised: that block is commented out in
      // the library. maxDescent is, for printable code points only.
      if (((int16_t)gHeight[n] - (int16_t)gdY[n]) > maxDescent) {
        const uint16_t u = gUnicode[n];
        if ((u > 0x20 && u < 0xA0 && u != 0x7F) || u > 0xFF)
          maxDescent = gHeight[n] - gdY[n];
      }
      gBitmap[n] = bitmapPtr;
      bitmapPtr += gWidth[n] * gHeight[n];
    }
    yAdvance = maxAscent + maxDescent;
    spaceWidth = (ascent + descent) * 2 / 7;
  }

  void unloadFont() {
    fontLoaded = false;
    gUnicode.clear(); gHeight.clear(); gWidth.clear(); gxAdvance.clear();
    gdY.clear(); gdX.clear(); gBitmap.clear();
  }

  bool getUnicodeIndex(uint16_t u, uint16_t* index) const {
    for (uint16_t i = 0; i < gCount; i++)
      if (gUnicode[i] == u) { *index = i; return true; }
    return false;
  }

  void drawGlyph(uint16_t code) {
    uint16_t fg = textcolor, bg = textbgcolor;
    if (last_cursor_x != cursor_x) { bg_cursor_x = cursor_x; last_cursor_x = cursor_x; }
    if (code < 0x21) {
      if (code == 0x20) {
        if (fillbg) fillRect(bg_cursor_x, cursor_y, (cursor_x + spaceWidth) - bg_cursor_x, yAdvance, bg);
        cursor_x += spaceWidth;
        bg_cursor_x = cursor_x; last_cursor_x = cursor_x;
        return;
      }
      if (code == '\n') {
        cursor_x = 0; bg_cursor_x = 0; last_cursor_x = 0;
        cursor_y += yAdvance;
        return;
      }
    }
    uint16_t g = 0;
    if (getUnicodeIndex(code, &g)) {
      if (textwrapX && (cursor_x + gWidth[g] + gdX[g] > kW)) {
        cursor_y += yAdvance; cursor_x = 0; bg_cursor_x = 0;
      }
      if (cursor_x == 0) cursor_x -= gdX[g];
      const uint8_t* gPtr = gArray;
      int16_t cy = cursor_y + maxAscent - gdY[g];
      int16_t cx = cursor_x + gdX[g];
      int16_t fxs = cx; uint32_t fl = 0;
      int16_t bxs = cx; uint32_t bl = 0;
      int16_t bx = 0;
      int16_t fillwidth = 0, fillheight = 0;
      if (fillbg) {
        fillwidth = (cursor_x + gxAdvance[g]) - bg_cursor_x;
        if (fillwidth > 0) {
          fillheight = maxAscent - gdY[g];
          if (fillheight > 0) fillRect(bg_cursor_x, cursor_y, fillwidth, fillheight, textbgcolor);
        } else {
          fillwidth = 0;
        }
        if (bg_cursor_x < cx) fillRect(bg_cursor_x, cy, cx - bg_cursor_x, gHeight[g], textbgcolor);
        if (bg_cursor_x > cx) bx = bg_cursor_x - cx;
        if (cx + gWidth[g] < cursor_x + gxAdvance[g])
          fillRect(cx + gWidth[g], cy, (cursor_x + gxAdvance[g]) - (cx + gWidth[g]), gHeight[g], textbgcolor);
      }
      for (int32_t y = 0; y < gHeight[g]; y++) {
        for (int32_t x = 0; x < gWidth[g]; x++) {
          uint8_t pixel = pgm_read_byte(gPtr + gBitmap[g] + x + gWidth[g] * y);
          if (pixel) {
            if (bl) { drawFastHLine(bxs, y + cy, bl, bg); bl = 0; }
            if (pixel != 0xFF) {
              if (fl) {
                if (fl == 1) drawPixel(fxs, y + cy, fg);
                else drawFastHLine(fxs, y + cy, fl, fg);
                fl = 0;
              }
              drawPixel(x + cx, y + cy, alphaBlend(pixel, fg, bg));
            } else {
              if (fl == 0) fxs = x + cx;
              fl++;
            }
          } else {
            if (fl) { drawFastHLine(fxs, y + cy, fl, fg); fl = 0; }
            if (fillbg && x >= bx) {
              if (bl == 0) bxs = x + cx;
              bl++;
            }
          }
        }
        if (fl) { drawFastHLine(fxs, y + cy, fl, fg); fl = 0; }
        if (bl) { drawFastHLine(bxs, y + cy, bl, bg); bl = 0; }
      }
      if (fillwidth > 0) {
        fillheight = (cursor_y + yAdvance) - (cy + gHeight[g]);
        if (fillheight > 0) fillRect(bg_cursor_x, cy + gHeight[g], fillwidth, fillheight, textbgcolor);
      }
      cursor_x += gxAdvance[g];
    } else {
      // Not in the font: the library's hollow box.
      drawRect(cursor_x, cursor_y + maxAscent - ascent, spaceWidth, ascent, fg);
      cursor_x += spaceWidth + 1;
      glyphBoxes++;
    }
    bg_cursor_x = cursor_x;
    last_cursor_x = cursor_x;
  }

  // ---- built-in fonts -------------------------------------------------------
  struct Builtin { const unsigned char* const* chr; const unsigned char* wid; int height, baseline; };
  static const Builtin* builtin(uint8_t font) {
    static const Builtin f2{chrtbl_f16, widtbl_f16, chr_hgt_f16, baseline_f16};
    static const Builtin f4{chrtbl_f32, widtbl_f32, chr_hgt_f32, baseline_f32};
    static const Builtin f6{chrtbl_f64, widtbl_f64, chr_hgt_f64, baseline_f64};
    static const Builtin f7{chrtbl_f7s, widtbl_f7s, chr_hgt_f7s, baseline_f7s};
    static const Builtin f8{chrtbl_f72, widtbl_f72, chr_hgt_f72, baseline_f72};
    switch (font) {
      case 2: return &f2; case 4: return &f4; case 6: return &f6;
      case 7: return &f7; case 8: return &f8; default: return nullptr;
    }
  }

  int16_t drawChar(uint16_t uniCode, int32_t x, int32_t y, uint8_t font) {
    if (!uniCode) return 0;
    if (font == 1) { font1Calls++; return 6 * textsize; }
    if (font > 1 && font < 9 && (uniCode < 32 || uniCode > 127)) return 0;
    const Builtin* bf = builtin(font);
    if (!bf) return 0;
    uniCode -= 32;
    const uint8_t* data = bf->chr[uniCode];
    const int32_t width = bf->wid[uniCode], height = bf->height, ts = textsize;
    const bool clip = x < 0 || x + width * ts >= kW || y < 0 || y + height * ts >= kH;
    if (font == 2) {
      const int32_t w = (width + 6) / 8;          // bytes per row, as the library has it
      if (textcolor == textbgcolor || ts != 1 || clip) {
        for (int32_t i = 0; i < height; i++) {
          if (textcolor != textbgcolor) fillRect(x, y + i * ts, width * ts, ts, textbgcolor);
          for (int32_t k = 0; k < w; k++) {
            const uint8_t line = pgm_read_byte(data + w * i + k);
            for (int b = 0; b < 8; b++)
              if (line & (0x80 >> b)) fillRect(x + (k * 8 + b) * ts, y + i * ts, ts, ts, textcolor);
          }
        }
      } else {                                    // block write: exactly `width` pixels a row
        for (int32_t i = 0; i < height; i++)
          for (int32_t px = 0; px < width; px++) {
            const int32_t k = px / 8;
            const bool on = k < w && (pgm_read_byte(data + w * i + k) & (0x80 >> (px % 8)));
            drawPixel(x + px, y + i, on ? textcolor : textbgcolor);
          }
      }
      return width * ts;
    }
    // RLE fonts: a byte with the top bit set is (n & 0x7F) + 1 foreground
    // pixels, otherwise n + 1 background pixels, running across the rows.
    const int32_t total = width * height;
    int32_t pc = 0;
    while (pc < total) {
      uint8_t line = pgm_read_byte(data++);
      const bool fgRun = (line & 0x80) != 0;
      const int32_t n = (line & 0x7F) + 1;
      if (fgRun || textcolor != textbgcolor) {
        const uint16_t col = fgRun ? textcolor : textbgcolor;
        for (int32_t i = 0; i < n && pc + i < total; i++) {
          const int32_t px = (pc + i) % width, py = (pc + i) / width;
          fillRect(x + px * ts, y + py * ts, ts, ts, col);
        }
      }
      pc += n;
    }
    return width * ts;
  }

  // ---- strings ----------------------------------------------------------------
  int16_t textWidth(const char* string, uint8_t font) {
    int32_t w = 0;
    if (fontLoaded) {
      const uint8_t* p = (const uint8_t*)string;
      while (*p) {
        const uint16_t u = utf8(p, (uint16_t)strlen((const char*)p));
        if (!u) continue;
        if (u == 0x20) { w += spaceWidth; continue; }
        uint16_t g = 0;
        if (getUnicodeIndex(u, &g)) {
          if (w == 0 && gdX[g] < 0) w -= gdX[g];
          if (*p || isDigits) w += gxAdvance[g];
          else w += (gdX[g] + gWidth[g]);
        } else {
          w += spaceWidth + 1;
        }
      }
      isDigits = false;
      return (int16_t)w;
    }
    if (font > 1 && font < 9) {
      const Builtin* bf = builtin(font);
      if (bf)
        for (const unsigned char* p = (const unsigned char*)string; *p; p++)
          w += (*p > 31 && *p < 128) ? bf->wid[*p - 32] : bf->wid[0];
    } else {
      w = 6 * (int32_t)strlen(string);
    }
    isDigits = false;
    return (int16_t)(w * textsize);
  }

  int16_t fontHeight(uint8_t font) const {
    if (font > 8) return 0;
    if (fontLoaded) return yAdvance;
    if (font == 1) return 8 * textsize;
    const Builtin* bf = builtin(font);
    return bf ? bf->height * textsize : 0;
  }

  int16_t drawString(const char* string, int32_t poX, int32_t poY, uint8_t font) {
    if (font > 8) return 0;
    int16_t sumX = 0;
    uint8_t padding = 1;
    uint16_t cwidth = textWidth(string, font);
    uint16_t cheight = 8 * textsize;
    if (fontLoaded) {
      cheight = fontHeight(font);
    } else if (font != 1) {
      cheight = fontHeight(font);
    }
    if (textdatum || padX) {
      switch (textdatum) {
        case 1: poX -= cwidth / 2; padding += 1; break;                      // TC
        case 2: poX -= cwidth;     padding += 2; break;                      // TR
        case 3: poY -= cheight / 2; break;                                   // ML
        case 4: poX -= cwidth / 2; poY -= cheight / 2; padding += 1; break;  // MC
        case 5: poX -= cwidth;     poY -= cheight / 2; padding += 2; break;  // MR
        case 6: poY -= cheight; break;                                       // BL
        case 7: poX -= cwidth / 2; poY -= cheight; padding += 1; break;      // BC
        case 8: poX -= cwidth;     poY -= cheight; padding += 2; break;      // BR
        default: break;   // the baseline datums are not used by the firmware
      }
    }
    const uint16_t len = (uint16_t)strlen(string);
    const uint8_t* p = (const uint8_t*)string;
    const uint8_t* end = p + len;
    if (fontLoaded) {
      cursor_x = poX; cursor_y = poY;
      const bool keep = fillbg;
      if (padX && !fillbg) fillbg = true;
      while (p < end) drawGlyph(utf8(p, (uint16_t)(end - p)));
      fillbg = keep;
      sumX += cwidth;
    } else {
      while (p < end) sumX += drawChar(utf8(p, (uint16_t)(end - p)), poX + sumX, poY, font);
    }
    if ((padX > cwidth) && (textcolor != textbgcolor)) {
      int16_t padXc = poX + cwidth;
      switch (padding) {
        case 1:
          fillRect(padXc, poY, padX - cwidth, cheight, textbgcolor);
          break;
        case 2:
          fillRect(padXc, poY, (padX - cwidth) >> 1, cheight, textbgcolor);
          padXc = poX - ((padX - cwidth) >> 1);
          fillRect(padXc, poY, (padX - cwidth) >> 1, cheight, textbgcolor);
          break;
        case 3:
          if (padXc > padX) padXc = padX;
          fillRect(poX + cwidth - padXc, poY, padXc - cwidth, cheight, textbgcolor);
          break;
      }
    }
    return sumX;
  }

 private:
  uint32_t rd32(uint32_t& p) const {
    uint32_t v = ((uint32_t)pgm_read_byte(gArray + p) << 24) | ((uint32_t)pgm_read_byte(gArray + p + 1) << 16) |
                 ((uint32_t)pgm_read_byte(gArray + p + 2) << 8) | (uint32_t)pgm_read_byte(gArray + p + 3);
    p += 4;
    return v;
  }

  /** TFT_eSPI::decodeUTF8(buf, index, remaining): one code point, advancing p. */
  static uint16_t utf8(const uint8_t*& p, uint16_t remaining) {
    uint16_t c = *p++;
    if ((c & 0x80) == 0x00) return c;
    if (((c & 0xE0) == 0xC0) && remaining > 1) return ((c & 0x1F) << 6) | (*p++ & 0x3F);
    if (((c & 0xF0) == 0xE0) && remaining > 2) {
      c = ((c & 0x0F) << 12) | ((*p++ & 0x3F) << 6);
      return c | (*p++ & 0x3F);
    }
    return c;
  }
};

#endif  // TFT_RASTER_H
