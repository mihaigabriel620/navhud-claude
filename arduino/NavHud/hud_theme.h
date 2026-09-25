// Shared drawing primitives and the theme selector.
//
// The dash theme is the default; defining HUD_THEME_E60_CLASSIC selects the
// other one, which only the host tests do. Both implement the same five entry
// points, so the main loop does not know or care which is in.

#ifndef HUD_THEME_H
#define HUD_THEME_H

#include <TFT_eSPI.h>
#include "hud_protocol.h"
#include "hud_geom.h"
#include "hud_canvas.h"

// Not a TFT_eSPI. Every primitive below goes through HudCanvas, which applies
// the mirror and the keystone on the way to the panel -- so no theme has to
// know that either exists. With keystone off it forwards each call verbatim.
extern HudCanvas tft;

static const int SCR_W = HUD_SCR_W, SCR_H = HUD_SCR_H;

#include "hud_aa.h"           // anti-aliased glyphs, drawn as one shape

// ---------------------------------------------------------------------------
//  primitives
// ---------------------------------------------------------------------------

// The flat strokes and plain triangles these glyphs were drawn with are
// replaced by hud_aa.h's aaCap and aaHead (same head geometry).

// One arrow, parameterised so both themes share it: a stem up from the
// bottom, bending to `angle` at the centre, and a head. Anti-aliased and
// drawn as one shape (hud_aa.h). The strokes have round ends, so the free end
// of the stem is pulled in by its radius to end where the flat one did, and
// the bend is round without a separate disc.
static void turnArrow(int cx, int cy, int r, int shaftW, int headW,
                      float angle, uint16_t col, uint16_t bg) {
  const float sr = shaftW * 0.5f;
  AaPrim p[3];
  if (fabsf(angle) < 1.0f) {
    p[0] = aaCap(cx, cy + r - sr, cx, cy - r + headW, sr);
    p[1] = aaHead(cx, cy, 0, r, headW);
    aaFill(p, 2, col, bg);
    return;
  }
  const float a = angle * DEG_TO_RAD;
  p[0] = aaCap(cx, cy + r - sr, cx, cy, sr);
  p[1] = aaCap(cx, cy, cx + sinf(a) * (r - headW * 0.8f),
               cy - cosf(a) * (r - headW * 0.8f), sr);
  p[2] = aaHead(cx, cy, angle, r, headW);
  aaFill(p, 3, col, bg);
}

// The U-turn: up one leg, round the top of a ring, down the other to the
// head. The bend is the top half of a ring (it used to be thirty short flat
// strokes, which left gaps and spikes round the curve). The legs start two
// pixels up inside the bend so the two parts overlap instead of meeting on a
// line, and the leg in is pulled in by its radius to end where the flat one
// did. The returning leg runs to cy + 12, past the head's base at cy + 10:
// the head is anchored at cy + 8 and measured from there, so its base lands
// at (cy + 8) + (r + 4) - (r + 2).
static void drawUturnArt(int cx, int cy, int r, int w, uint16_t col, uint16_t bg) {
  const float hw = w * 0.5f;
  AaPrim p[4];
  p[0] = aaTopRing(cx, cy, r, hw);
  p[1] = aaCap(cx + r, cy - 2, cx + r, cy + r + 10 - hw, hw);
  p[2] = aaCap(cx - r, cy - 2, cx - r, cy + 12, hw);
  p[3] = aaHead(cx - r, cy + 8, 180, r + 4, r + 2);
  aaFill(p, 4, col, bg);
}

/** Arrow angle for a maneuver code. 0 = straight on. */
static float angleForManeuver(uint8_t man) {
  switch (man) {
    case MAN_LEFT:         return -90.f;
    case MAN_RIGHT:        return  90.f;
    case MAN_SLIGHT_LEFT:
    case MAN_FORK_LEFT:
    case MAN_KEEP_LEFT:
    case MAN_RAMP_LEFT:
    case MAN_MERGE_LEFT:   return -42.f;
    case MAN_SLIGHT_RIGHT:
    case MAN_FORK_RIGHT:
    case MAN_KEEP_RIGHT:
    case MAN_RAMP_RIGHT:
    case MAN_MERGE_RIGHT:  return  42.f;
    case MAN_SHARP_LEFT:   return -135.f;
    case MAN_SHARP_RIGHT:  return  135.f;
    default:               return 0.f;
  }
}

/** Splits a distance into a number string and a unit. */
static void formatDistance(int32_t m, char* num, size_t n, const char** unit) {
  if (m < 1000) { snprintf(num, n, "%ld", (long)m); *unit = "m"; }
  else if (m < 10000) {
    snprintf(num, n, "%ld.%ld", (long)(m / 1000), (long)((m % 1000) / 100));
    *unit = "km";
  } else { snprintf(num, n, "%ld", (long)(m / 1000)); *unit = "km"; }
}

static void formatEta(int32_t seconds, char* out, size_t n, bool upper) {
  long mins = seconds / 60;
  if (mins < 60) snprintf(out, n, upper ? "%ld MIN" : "%ld min", mins);
  else snprintf(out, n, upper ? "%ldH%02ld" : "%ldh%02ld", mins / 60, mins % 60);
}

static void formatRemaining(int32_t m, char* out, size_t n, bool upper) {
  if (m < 1000) snprintf(out, n, upper ? "%ld M" : "%ld m", (long)m);
  else snprintf(out, n, upper ? "%ld.%ld KM" : "%ld.%ld km",
                (long)(m / 1000), (long)((m % 1000) / 100));
}

// ---------------------------------------------------------------------------
//  theme selection
// ---------------------------------------------------------------------------
#if defined(HUD_THEME_E60_CLASSIC)
  #include "hud_arrows.h"     // the roundabout, shared with the dash
  #include "theme_e60.h"      // the nav-only layout, before CAN
#else
  #include "hud_arrows.h"
  #include "theme_dash.h"     // default: nav + CAN, three bands
#endif

// The nav-only themes have nothing to show when the phone is away, so they get
// a stand-in rather than a compile error. Keeping this here means the sketch
// never has to know which theme it was built with.
#ifndef HUD_THEME_HAS_CAR_VIEW
static void themeRenderCarOnly(uint32_t nowMs, bool relayout) {
  (void)nowMs;
  if (relayout) themeSplash("NO LINK", "check the USB cable", true);
}
#endif

// ---------------------------------------------------------------------------
//  the keystone alignment pattern
// ---------------------------------------------------------------------------
//
// Shown while the driver is adjusting the corners, instead of the drive
// display. Three reasons it is not just "the normal screen, warped":
//
//   * You are aligning the *edges* of the projection to the windscreen, and
//     the drive display has no edges -- it is mostly black. A border and a
//     grid give you something to line up.
//   * It is cheap. A full repaint of the drive display is about a tenth of a
//     second at 20 MHz SPI; at ten slider movements a second the board would
//     fall a second behind within ten seconds of dragging. This is a few
//     dozen lines.
//   * The corner labels tell you whether the mirror is the right way round.
//     "TL" appearing at the top right of the reflection is the whole answer,
//     and it is not a question you can answer from a speed reading.
//
static void themeAlignPattern() {
  // Back to the built-in fonts for this screen. It is drawn with font 4 and
  // the font argument is ignored while a smooth font is loaded, so without
  // this the corner labels render as hollow boxes in whatever face the dash
  // was last using -- and "TL appearing at the top right of the reflection"
  // is the entire point of the screen.
#ifdef HUD_THEME_HAS_FONT_RESET
  themeResetFont();
#endif
  tft.fillScreen(HUD_ALIGN_BG);

  // Outer border, one pixel in from each edge so it cannot be clipped away.
  tft.drawRect(1, 1, SCR_W - 2, SCR_H - 2, HUD_ALIGN_FG);
  tft.drawRect(2, 2, SCR_W - 4, SCR_H - 4, HUD_ALIGN_DIM);

  // Thirds, so a bowed edge shows up against a straight one.
  for (int i = 1; i <= 2; i++) {
    tft.drawFastVLine(SCR_W * i / 3, 2, SCR_H - 4, HUD_ALIGN_DIM);
    tft.drawFastHLine(2, SCR_H * i / 3, SCR_W - 4, HUD_ALIGN_DIM);
  }

  // Centre cross.
  tft.drawFastHLine(SCR_W / 2 - 26, SCR_H / 2, 52, HUD_ALIGN_FG);
  tft.drawFastVLine(SCR_W / 2, SCR_H / 2 - 26, 52, HUD_ALIGN_FG);

  // Corner brackets, and the label for the corner the app is dragging.
  const int a = 40;
  tft.drawFastHLine(2, 2, a, HUD_ALIGN_FG);          tft.drawFastVLine(2, 2, a, HUD_ALIGN_FG);
  tft.drawFastHLine(SCR_W - 2 - a, 2, a, HUD_ALIGN_FG);  tft.drawFastVLine(SCR_W - 3, 2, a, HUD_ALIGN_FG);
  tft.drawFastHLine(SCR_W - 2 - a, SCR_H - 3, a, HUD_ALIGN_FG); tft.drawFastVLine(SCR_W - 3, SCR_H - 2 - a, a, HUD_ALIGN_FG);
  tft.drawFastHLine(2, SCR_H - 3, a, HUD_ALIGN_FG);  tft.drawFastVLine(2, SCR_H - 2 - a, a, HUD_ALIGN_FG);

  tft.setTextColor(HUD_ALIGN_FG, HUD_ALIGN_BG);
  tft.setTextDatum(TL_DATUM); tft.drawString("TL", 12, 12, 4);
  tft.setTextDatum(TR_DATUM); tft.drawString("TR", SCR_W - 12, 12, 4);
  tft.setTextDatum(BR_DATUM); tft.drawString("BR", SCR_W - 12, SCR_H - 12, 4);
  tft.setTextDatum(BL_DATUM); tft.drawString("BL", 12, SCR_H - 12, 4);

  // If this reads backwards, the mirror setting is wrong -- which is the one
  // fault you cannot diagnose from a screen full of numbers.
  tft.setTextDatum(MC_DATUM);
  tft.setTextColor(HUD_ALIGN_DIM, HUD_ALIGN_BG);
  tft.drawString("ALIGN", SCR_W / 2, SCR_H / 2 - 44, 4);
}

#endif  // HUD_THEME_H
