// A stand-in for TFT_eSPI and the Arduino core, so the sketch and both themes
// can be compiled and exercised on a PC.
//
// It does not draw anything. It records the bounding box of every primitive,
// tagged with whichever screen zone is currently active, which is enough to
// prove two things that otherwise only show up on real glass:
//   * nothing is drawn outside HUD_SCR_W x HUD_SCR_H
//   * no two zones ever write the same pixel
//
// Font metrics are approximations of TFT_eSPI's built-in fonts -- close enough
// for collision detection, not for pixel-perfect layout.

#ifndef TFT_STUB_H
#define TFT_STUB_H

// This stub stands in for a *correctly configured* TFT_eSPI, so it declares
// what that configuration declares. The sketch has a #error that fires when
// the library was never set up for this panel -- the white-screen case -- and
// the host build has to look like a set-up library, not like an escape hatch.
// If these two ever stop matching config/User_Setup_ESP8266.h, the guard is
// testing something that does not ship.
#define ST7796_DRIVER
#define TFT_DC   5
#define TFT_CS   15
#define TFT_SCLK 14
#define TFT_MOSI 13
#define TFT_RST  -1
#define SPI_FREQUENCY 20000000

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <cstdlib>
#include <cmath>
#include "../NavHud/hud_protocol.h"
#include <string>
#include <vector>
#include <algorithm>

// ---- Arduino core shims ----------------------------------------------------
#ifndef DEG_TO_RAD
#define DEG_TO_RAD 0.017453292519943295
#endif

// A stand-in for the WiFi object. Only ever used to switch the radio off --
// which, on this hardware, is the difference between the panel initialising
// and not.
struct WiFiStub {
  void persistent(bool) {}
  void disconnect(bool = false) {}
  void mode(int) {}
  void forceSleepBegin() {}
};
static WiFiStub WiFi;
#define WIFI_OFF 0

static uint32_t g_millis = 0;
inline uint32_t millis() { return g_millis; }
inline void delay(uint32_t) {}
inline void delayMicroseconds(uint32_t) {}
inline void yield() {}
static uint32_t g_micros = 0;
inline uint32_t micros() { g_micros += 1000; return g_micros; }
inline void pinMode(int, int) {}
#define HUD_STUB_HAS_DIGITALWRITE 1
static int g_pinState[64] = {0};
inline void digitalWrite(int p, int v) { if (p >= 0 && p < 64) g_pinState[p] = v; }
inline int  digitalRead(int p)         { return (p >= 0 && p < 64) ? g_pinState[p] : 0; }
inline void analogWrite(int, int) {}
#define OUTPUT 1
#ifndef INPUT
#define INPUT 0
#endif
#ifndef LOW
#define LOW 0
#endif
#ifndef HIGH
#define HIGH 1
#endif
#ifndef DEG_TO_RAD
#define DEG_TO_RAD 0.017453292519943295
#endif

class String {
 public:
  String() {}
  String(const char* s) : s_(s) {}
  String(int v) { char b[16]; snprintf(b, sizeof b, "%d", v); s_ = b; }
  String(long v) { char b[24]; snprintf(b, sizeof b, "%ld", v); s_ = b; }
  const char* c_str() const { return s_.c_str(); }
  size_t length() const { return s_.size(); }
 private:
  std::string s_;
};

class SerialStub {
 public:
  void begin(long) {}
  int available() { return (int)(in_.size() - pos_); }
  int read() { return pos_ < in_.size() ? (uint8_t)in_[pos_++] : -1; }
  void print(const char* s) { out_ += s; }
  void print(const String& s) { out_ += s.c_str(); }
  void feed(const std::string& s) { in_ += s; }
  std::string out_, in_;
  size_t pos_ = 0;
};
static SerialStub Serial;

// ---- colours and datums ----------------------------------------------------
#define TFT_BLACK 0x0000
#define TFT_WHITE 0xFFFF
#define TFT_RED   0xF800
#define TFT_GREEN 0x07E0
#define TFT_BLUE  0x001F

enum { TL_DATUM = 0, TC_DATUM, TR_DATUM, ML_DATUM, MC_DATUM, MR_DATUM,
       BL_DATUM, BC_DATUM, BR_DATUM };

// ---- the recorder ----------------------------------------------------------
struct Box { int x0, y0, x1, y1; const char* zone; const char* op; };

extern const char* g_zone;
extern std::vector<Box> g_boxes;
extern int g_outOfBounds;
extern int g_badGlyphs;

class TFT_eSPI {
 public:
  TFT_eSPI() {}
  void init() {}
  // Modelled, not stubbed.
  //
  // rotationCap is how many orientations the pretend library implements: 8 for
  // a current TFT_eSPI, 4 for one whose rotation table stops at 3. Asking an
  // old one for 7 does not fail loudly -- it falls out of a switch with no
  // matching case, leaving the driver's idea of the panel at whatever it was
  // set up as, which for an ST7796 is portrait. Every landscape draw then gets
  // clipped away and the screen stays at its power-on white.
  //
  // That is the failure setPanelRotation() exists to survive, so the stub has
  // to be able to produce it.
  void setRotation(int r) {
    if (r < rotationCap) { rotation = r; return; }
    rotation = 0;                     // native portrait: nothing landscape fits
  }
  int rotation = 1;
  int rotationCap = 8;
  void writecommand(uint8_t) {}
  void writedata(uint8_t) {}

  void fillScreen(uint16_t) { /* clears everything: not a collision */ }

  // The driver's own idea of the panel size, which follows setRotation.
  // Rotations 1, 3, 5 and 7 are the four landscape orientations of an ST7796;
  // 0, 2, 4 and 6 are portrait. A sketch that reads width()/height() has to
  // see the same thing here as it does on the glass.
  int16_t width()  const { return (rotation & 1) ? HUD_SCR_W : HUD_SCR_H; }
  int16_t height() const { return (rotation & 1) ? HUD_SCR_H : HUD_SCR_W; }

  void fillRect(int x, int y, int w, int h, uint16_t) {
    // A background fill is a clear, not a mark -- record it as such so the
    // collision check can ignore it.
    rec(x, y, x + w - 1, y + h - 1, "fillRect", /*isClear=*/true);
  }
  void drawRect(int x, int y, int w, int h, uint16_t) {
    rec(x, y, x + w - 1, y + h - 1, "drawRect");
  }
  void drawRoundRect(int x, int y, int w, int h, int, uint16_t) {
    rec(x, y, x + w - 1, y + h - 1, "drawRoundRect");
  }
  void drawCircle(int cx, int cy, int r, uint16_t) {
    rec(cx - r, cy - r, cx + r, cy + r, "drawCircle");
  }
  void fillCircle(int cx, int cy, int r, uint16_t) {
    rec(cx - r, cy - r, cx + r, cy + r, "fillCircle");
  }
  void fillTriangle(float x0, float y0, float x1, float y1,
                    float x2, float y2, uint16_t) {
    int lo_x = (int)std::floor(std::min({x0, x1, x2}));
    int hi_x = (int)std::ceil (std::max({x0, x1, x2}));
    int lo_y = (int)std::floor(std::min({y0, y1, y2}));
    int hi_y = (int)std::ceil (std::max({y0, y1, y2}));
    rec(lo_x, lo_y, hi_x, hi_y, "fillTriangle");
  }
  void drawFastHLine(int x, int y, int w, uint16_t) { rec(x, y, x + w - 1, y, "hline"); }
  void drawFastVLine(int x, int y, int h, uint16_t) { rec(x, y, x, y + h - 1, "vline"); }

  // ---- smooth fonts -------------------------------------------------------
  //
  // The stub parses the real .vlw PROGMEM array rather than modelling it. That
  // matters: the layout test's whole job is to prove nothing collides and
  // nothing is drawn off the panel, and it can only do that against the widths
  // the panel will actually produce. It also catches a missing glyph, which is
  // a live risk now that the character sets are trimmed to save flash -- a
  // street name containing a character the font does not carry draws nothing
  // at all on the hardware, silently.
  void loadFont(const uint8_t* arr) {
    smooth_ = true;
    glyphs_.clear();
    auto be32 = [&](size_t o) -> int32_t {
      return (int32_t)(((uint32_t)arr[o] << 24) | ((uint32_t)arr[o + 1] << 16) |
                       ((uint32_t)arr[o + 2] << 8) | (uint32_t)arr[o + 3]);
    };
    const int32_t count = be32(0);
    // Exactly what TFT_eSPI does, which is not what you would guess.
    //
    // loadFont() sets maxAscent = the *header* ascent and never raises it: the
    // block in loadMetrics() that would take it from the glyph table is
    // commented out in the library ("this method can generate bad values for
    // non-existent glyphs... disable this code for now"). maxDescent IS raised
    // from the glyphs. Modelling both from the glyph table hid a real bug --
    // the generator was writing the face's ascent, 181 px where the digits are
    // 129, and the background box that came with it erased the band rule.
    int32_t maxD = 0;
    for (int32_t i = 0; i < count; i++) {
      const size_t o = 24 + (size_t)i * 28;
      Glyph g;
      g.height  = be32(o + 4);
      g.width   = be32(o + 8);
      g.advance = be32(o + 12);
      const int32_t dY = be32(o + 16);
      if (g.height - dY > maxD) maxD = g.height - dY;
      glyphs_[(uint16_t)be32(o)] = g;
    }
    ascent_  = be32(16);                      // header, never raised
    descent_ = maxD > be32(20) ? maxD : be32(20);
  }
  void unloadFont() { smooth_ = false; glyphs_.clear(); }

  void setTextDatum(uint8_t d) { datum_ = d; }
  void setTextColor(uint16_t, uint16_t) {}
  void setTextColor(uint16_t) {}
  void setTextPadding(int) {}
  void setTextSize(uint8_t n) { size_ = n ? n : 1; }

  int textWidth(const char* s, uint8_t font) const {
    if (smooth_) return smoothWidth(s);
    return (int)(strlen(s) * charW(font)) * size_;
  }
  int textWidth(const String& s, uint8_t font) const {
    return textWidth(s.c_str(), font);
  }

  int drawString(const char* s, int x, int y, uint8_t font) {
    checkGlyphs(s, font);
    // setTextSize does nothing to a smooth font -- TFT_eSPI skips the multiply
    // in both textWidth() and fontHeight() when one is loaded -- so the stub
    // must skip it too or every layout assertion is wrong by a factor of two.
    int w = textWidth(s, font);
    int h = smooth_ ? (ascent_ + descent_) : (fontH(font) * size_);
    recText(x, y, w, h, "drawString");
    return w;
  }
  int drawString(const String& s, int x, int y, uint8_t font) {
    return drawString(s.c_str(), x, y, font);
  }
  int drawNumber(long v, int x, int y, uint8_t font) {
    char b[24]; snprintf(b, sizeof b, "%ld", v);
    return drawString(b, x, y, font);
  }

  /**
   * TFT_eSPI's big built-in fonts are digits and a handful of punctuation --
   * nothing else. Font 6 is "1234567890:-.apm", fonts 7 and 8 are "1234567890:-."
   * and that is the whole glyph set. Ask any of them for a letter and you get
   * a blank or a wrong glyph, silently, on the hardware only.
   *
   * That is a hard class of bug to see: the layout is right, the colour is
   * right, and a word is simply missing. So the stub refuses it here.
   */
  void checkGlyphs(const char* s, uint8_t font) const {
    // A smooth font draws a hollow rectangle for a glyph it does not carry,
    // and the font-number argument is ignored while one is loaded -- so an
    // element pointed at the wrong face renders as a row of empty boxes with
    // no other symptom. The charsets are trimmed hard to save flash (the unit
    // faces carry twenty-one glyphs), which makes this easy to get wrong and
    // impossible to see from the code. So it fails the build instead.
    if (smooth_) {
      for (const unsigned char* p = (const unsigned char*)s; *p; ) {
        uint16_t cp = *p;
        if (cp < 0x80) { p++; }
        else if ((cp & 0xE0) == 0xC0) { cp = ((cp & 0x1F) << 6) | (p[1] & 0x3F); p += 2; }
        else if ((cp & 0xF0) == 0xE0) {
          cp = ((cp & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F); p += 3;
        } else { p++; continue; }
        if (glyphs_.find(cp) == glyphs_.end()) {
          if (g_reportOob) {
            printf("    loaded font has no glyph U+%04X in \"%s\" [%s]\n",
                   cp, s, g_zone ? g_zone : "?");
          }
          g_badGlyphs++;
          return;
        }
      }
      return;
    }
    if (font != 6 && font != 7 && font != 8) return;
    static const char* kFont6 = " 0123456789:-.apm";
    static const char* kFont78 = " 0123456789:-.";
    const char* ok = (font == 6) ? kFont6 : kFont78;
    for (const char* p = s; *p; p++) {
      if (strchr(ok, *p) == nullptr) {
        if (g_reportOob) {
          printf("    font %u cannot draw '%c' in \"%s\" [%s]\n",
                 font, *p, s, g_zone ? g_zone : "?");
        }
        g_badGlyphs++;
        return;
      }
    }
  }

  static float charW(uint8_t f) {
    switch (f) { case 1: return 6; case 2: return 8; case 4: return 14;
                 // Font 8 digits are 55 px wide in TFT_eSPI's Font72rle.c,
                 // not 45. Understating it by 22% put the over-limit brackets
                 // exactly on the boundary of the cleared rectangle in the
                 // layout test, so a real off-panel overdraw looked fine here.
                 case 6: return 28; case 7: return 29; case 8: return 55;
                 default: return 8; }
  }
  static int fontH(uint8_t f) {
    switch (f) { case 1: return 8; case 2: return 16; case 4: return 26;
                 case 6: return 48; case 7: return 48; case 8: return 75;
                 default: return 16; }
  }

 private:
  struct Glyph { int width = 0; int height = 0; int advance = 0; };
  bool smooth_ = false;
  int ascent_ = 0, descent_ = 0;
  std::map<uint16_t, Glyph> glyphs_;

  /** UTF-8 aware, because the street font carries accented characters. */
  int smoothWidth(const char* s) const {
    int w = 0;
    for (const unsigned char* p = (const unsigned char*)s; *p; ) {
      uint16_t cp = *p;
      if (cp < 0x80) { p++; }
      else if ((cp & 0xE0) == 0xC0) { cp = ((cp & 0x1F) << 6) | (p[1] & 0x3F); p += 2; }
      else if ((cp & 0xF0) == 0xE0) {
        cp = ((cp & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F); p += 3;
      } else { p++; continue; }
      auto it = glyphs_.find(cp);
      if (it != glyphs_.end()) w += it->second.advance;
    }
    return w;
  }

  void recText(int x, int y, int w, int h, const char* op) {
    int x0 = x, y0 = y;
    switch (datum_) {
      case MC_DATUM: x0 = x - w / 2; y0 = y - h / 2; break;
      case BC_DATUM: x0 = x - w / 2; y0 = y - h;     break;
      case BL_DATUM: x0 = x;         y0 = y - h;     break;
      case BR_DATUM: x0 = x - w;     y0 = y - h;     break;
      case ML_DATUM: x0 = x;         y0 = y - h / 2; break;
      case TC_DATUM: x0 = x - w / 2; y0 = y;         break;
      case TR_DATUM: x0 = x - w;     y0 = y;         break;
      default:       x0 = x;         y0 = y;         break;
    }
    rec(x0, y0, x0 + w - 1, y0 + h - 1, op);
  }

  void rec(int x0, int y0, int x1, int y1, const char* op, bool isClear = false) {
    if (x0 > x1) std::swap(x0, x1);
    if (y0 > y1) std::swap(y0, y1);
    if (x0 < -2 || y0 < -2 || x1 > HUD_SCR_W + 1 || y1 > HUD_SCR_H + 1) {
      if (g_reportOob) {
        printf("    out of bounds [%s/%s] (%d,%d)-(%d,%d)\n",
               g_zone ? g_zone : "?", op, x0, y0, x1, y1);
      }
      g_outOfBounds++;
    }
    g_boxes.push_back({x0, y0, x1, y1, isClear ? nullptr : g_zone, op});
  }

  uint8_t datum_ = TL_DATUM;
  uint8_t size_ = 1;
 public:
  static bool g_reportOob;
};

#endif  // TFT_STUB_H
