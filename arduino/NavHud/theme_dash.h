// ---------------------------------------------------------------------------
//  theme_dash.h -- the merged layout: the car on top, the drive in the middle,
//  where you are at the bottom. Three bands, amber on black.
// ---------------------------------------------------------------------------
//
//  WHERE TO LOOK WHEN SOMETHING IS WRONG
//
//    a field sits in the wrong place -> the coordinate block below. Every
//                                      number came out of the HUD Bench
//                                      export; change them there, not here.
//    a field never appears           -> its draw function's early return: it
//                                      is stale, or the phone is not linked.
//    old digits show through         -> that field's setTextPadding()
//    the whole band flickers         -> something is calling a full redraw
//                                      every pass; see drawDelta().
//
//  DATA SOURCES. Two independent feeds, and either can be missing:
//     CAN  (hud_car.h) rpm, speed, PS, peak, battery, ignition
//     PHONE (hud_protocol.h) turn, distance, limit, street, camera
//  With no phone the car half still runs. With no CAN the phone half still
//  runs. Neither waits for the other.
//
//  COLOUR. Only amber, dim amber, faint amber and red -- all inside the
//  red/yellow/green gamut the factory 610 head-up display was built around
//  ("Ein Blauanteil ist nicht vorgesehen"). Never white, never blue: a white
//  pixel is a colour that unit could not make, and it is the one that turns
//  into a glowing smear on the glass.
//
// ---------------------------------------------------------------------------
#ifndef THEME_DASH_H
#define THEME_DASH_H

// Tells hud_theme.h not to supply the fallback car-only view: this theme has
// a real one.
#define HUD_THEME_HAS_CAR_VIEW 1

#include "hud_car.h"
#include "hud_arrows.h"

extern CarState car;          // filled by the CAN reader in NavHud.ino

// ---- palette ---------------------------------------------------------------
#define DASH_BG     0x0000    // #000000
#define DASH_AMBER  0xFCC3    // #FF9A1F
#define DASH_DIM    0x9B22    // #9E6413
#define DASH_FAINT  0x4940    // #4A2A00
#define DASH_RED    0xF9C4    // #FF3B20

// The keystone alignment pattern borrows the theme's palette so the grid you
// aim the screen with looks like the screen you are aiming.
#define HUD_ALIGN_BG   DASH_BG
#define HUD_ALIGN_FG   DASH_AMBER
#define HUD_ALIGN_DIM  DASH_DIM

// ---- typography ------------------------------------------------------------
//
// Set DASH_SMOOTH_FONTS to 0 and the theme falls back to TFT_eSPI's built-in
// bitmap fonts. Keep it at 1 unless flash runs out: the built-ins cannot
// reproduce the layout.
//
// The reason is worth writing down, because it looks like laziness otherwise.
// The layout was positioned in the browser bench in Barlow Condensed at
// specific pixel sizes. The built-in fonts come in exactly four lit-glyph
// heights -- 10, 17, 36 and 70 px -- and setTextSize only multiplies those by
// whole numbers, so of seventeen elements only two could hit their size at
// all. Worse, the built-in faces are far wider per unit of height than a
// condensed face: the battery reading at its designed 28 px would have been
// 140 px wide and would have run straight into the PS number, which is why
// simply scaling up was not an option either. Every position in this file
// matched the bench from the day it was written; only the sizes did not, and
// this is what fixes them.
//
// Each size is its own .vlw file, because setTextSize does nothing at all to a
// smooth font (TFT_eSPI's textWidth() and fontHeight() skip the multiply when
// fontLoaded is set). Generate them with tools/make_vlw.py.
//
// Cost: about 231 KB of flash, and roughly twelve bytes of heap per glyph
// while a font is loaded -- the bitmaps are indexed in place in PROGMEM and
// never copied to RAM, which is what makes a 130 px face affordable on a part
// with 40 KB of heap.
#ifndef DASH_SMOOTH_FONTS
#define DASH_SMOOTH_FONTS 1
#endif

#if DASH_SMOOTH_FONTS
#if !defined(SMOOTH_FONT) && !defined(HUD_TEST)
#error "DASH_SMOOTH_FONTS needs #define SMOOTH_FONT in User_Setup.h. \
Copy arduino/config/User_Setup_ESP8266.h over TFT_eSPI's User_Setup.h, or set \
DASH_SMOOTH_FONTS to 0 to fall back to the built-in fonts."
#endif
#include "fonts/DashBig.h"      // 130 px digits   big speed
#include "fonts/DashSpeed.h"    //  75 px digits   speed
#include "fonts/DashPower.h"    //  45 px digits   PS, speed limit
#include "fonts/DashDist.h"     //  33 px digits   distance to manoeuvre
#include "fonts/DashStreet.h"   //  31 px text     street name, camera
#include "fonts/DashMid.h"      //  28 px digits+V battery, peak PS
#include "fonts/DashUnit.h"     //  20 px text     PS, km/h
#include "fonts/DashSmall.h"    //  17 px text     distance unit
#include "fonts/DashTiny.h"     //  13 px text     PEAK label

enum : uint8_t {
  DF_NONE = 0, DF_BIG, DF_SPEED, DF_POWER, DF_DIST,
  DF_STREET, DF_MID, DF_UNIT, DF_SMALL, DF_TINY
};

static uint8_t dashFontLoaded_ = DF_NONE;

/**
 * Make `want` the current font, doing nothing if it already is.
 *
 * Only one smooth font can be loaded at a time -- loadFont() frees the
 * previous one's metrics before reading the new ones -- so this is a switch,
 * not a stack. It is cheap because the fonts are PROGMEM arrays: a switch is
 * seven frees, seven mallocs and 24 + 28*glyphs byte reads out of flash, well
 * under a millisecond for a twelve-glyph digit face. Only the metrics are
 * unpacked; the glyph bitmaps stay in flash and are read pixel by pixel at
 * draw time.
 *
 * The no-op case matters more than the switch: this theme renders deltas, so
 * in steady driving the only field changing is the speed, its font stays
 * loaded, and nothing is reloaded at all.
 */
static void dashFont_(uint8_t want) {
  if (dashFontLoaded_ == want) return;
  switch (want) {
    case DF_BIG:    tft.loadFont(DashBig);    break;
    case DF_SPEED:  tft.loadFont(DashSpeed);  break;
    case DF_POWER:  tft.loadFont(DashPower);  break;
    case DF_DIST:   tft.loadFont(DashDist);   break;
    case DF_STREET: tft.loadFont(DashStreet); break;
    case DF_MID:    tft.loadFont(DashMid);    break;
    case DF_UNIT:   tft.loadFont(DashUnit);   break;
    case DF_SMALL:  tft.loadFont(DashSmall);  break;
    case DF_TINY:   tft.loadFont(DashTiny);   break;
    default:        tft.unloadFont();         break;
  }
  dashFontLoaded_ = want;
}

// With a smooth font loaded the font-number argument to drawString/drawNumber
// is ignored, so these pass 1 and let dashFont_ decide. Padding is in pixels
// of rendered width either way; the numbers below were measured from the
// actual faces rather than guessed.
#define DASH_FONT(id) dashFont_(id)
// For screens that are drawn with the built-in fonts. Without it they inherit
// whatever face the dash left loaded, and since the font-number argument is
// ignored while a smooth font is loaded, their labels come out as hollow
// boxes in a digits-only face.
#define HUD_THEME_HAS_FONT_RESET 1
static void themeResetFont() { dashFont_(DF_NONE); }
// Ignored while a smooth font is loaded, but drawString still needs an
// argument; 1 is the smallest valid built-in and is never actually used.
#define DASH_FN_BIG    1
#define DASH_FN_SPEED  1
#define DASH_FN_POWER  1
#define DASH_FN_DIST   1
#define DASH_FN_STREET 1
#define DASH_FN_MID    1
#define DASH_FN_UNIT   1
#define DASH_FN_SMALL  1
#define DASH_FN_TINY   1
#define DASH_BIG_SCALE 1        // the 130 px face is a face, not a scaled one
// Padding is a rendered width, so it belongs to the font, not to the field.
#define DASH_PAD_SPD_BIG  244
#define DASH_PAD_SPD      144
#else
#define DASH_FONT(id) ((void)0)
#define DF_NONE 0
#define DF_BIG 0
#define DF_SPEED 0
#define DF_POWER 0
#define DF_DIST 0
#define DF_STREET 0
#define DF_MID 0
#define DF_UNIT 0
#define DF_SMALL 0
#define DF_TINY 0
#define DASH_FN_BIG    8
#define DASH_FN_SPEED  8
#define DASH_FN_POWER  6
#define DASH_FN_DIST   6
#define DASH_FN_STREET 4
#define DASH_FN_MID    4
#define DASH_FN_UNIT   2
#define DASH_FN_SMALL  2
#define DASH_FN_TINY   2
#define DASH_BIG_SCALE 2        // built-in font 8 doubled, the old behaviour
// Built-in font 8 digits are 55 px wide, so "888" is 165 px and 330 doubled.
// Leaving the smooth-font numbers here meant the fallback never cleared a
// digit, because TFT_eSPI skips the padding fill when the string is wider.
#define DASH_PAD_SPD_BIG  340
#define DASH_PAD_SPD      170
#endif

// ---- band edges ------------------------------------------------------------
#define DASH_B1_TOP     0
#define DASH_B1_BOT    60
#define DASH_RULE_Y    62
#define DASH_B2_TOP    66
#define DASH_B2_BOT   250
#define DASH_B3_TOP   258
// Band 3 runs to the bottom of the panel rather than stopping at 314. The
// street name is now 31 px with real descenders, and a "g" or a "y" reaches
// below where the old 17 px font ever did -- anything not inside the clear
// rectangle stays on the glass when the name changes.
#define DASH_B3_BOT   SCR_H

// ---- band 1: the car -------------------------------------------------------
// Ten segments, 0 to the limiter, last two red and blinking. Coarse on
// purpose: at 500 rpm a segment it sits still at a steady throttle, which a
// finer bar would not.
#define TACHO_X        10
#define TACHO_Y        18
#define TACHO_SEG_W    14
#define TACHO_SEG_H    26
#define TACHO_PITCH    17
#define TACHO_COUNT    10
#define TACHO_TOP      5000    // measured on the car, not read off a spec sheet
#define TACHO_REDSEG    2      // the last two
#define TACHO_STEP     (TACHO_TOP / TACHO_COUNT)
#define TACHO_BLINK_MS 167     // half period -> 3 Hz

#define DASH_VOLT_X   204      // ML datum
#define DASH_VOLT_Y    32
#define DASH_PS_X     363      // MR datum
#define DASH_PS_Y      30
#define DASH_PSLAB_X  375      // ML datum
#define DASH_PSLAB_Y   15
#define DASH_PKLAB_X  472      // MR datum
#define DASH_PKLAB_Y   14
#define DASH_PEAK_X   471      // MR datum
#define DASH_PEAK_Y    39

// ---- band 2: the drive -----------------------------------------------------
#define DASH_ARROW_X   65
#define DASH_ARROW_Y  127
#define DASH_ARROW_SZ  97
#define DASH_DIST_X    53      // MC datum
#define DASH_DIST_Y   201
#define DASH_DUNIT_X   53
#define DASH_DUNIT_Y  239
#define DASH_SPD_X    243      // MC datum
#define DASH_SPD_Y    142
#define DASH_SUNIT_X  244
#define DASH_SUNIT_Y  214
#define DASH_BIGSPD_X 237      // no phone: speed takes the whole band
#define DASH_BIGSPD_Y 170
#define DASH_BUNIT_X  239
#define DASH_BUNIT_Y  278
#define DASH_LIM_X    414
#define DASH_LIM_Y    140
#define DASH_LIM_R     47
#define DASH_LIM_RING   7

// ---- band 3: where you are -------------------------------------------------
#define DASH_ST_X       7      // TL datum
#define DASH_ST_Y     278      // top of the capitals, as positioned in the bench

// How far the font's box top sits above a capital. Zero on the fallback path,
// where the built-in font's box IS its capital height.
#if DASH_SMOOTH_FONTS
#define DASH_ST_CAPTOP DashStreet_CAPTOP
#else
#define DASH_ST_CAPTOP 0
#endif

// Turn far enough away that the arrow stops shouting about it.
#define DASH_FAR_M    800

// ---------------------------------------------------------------------------
//  paint state -- what is on the glass, so a redraw only touches what moved
// ---------------------------------------------------------------------------
struct DashShown {
  int8_t   lit      = -1;      // tacho segments
  bool     blinkOn  = true;
  int16_t  ps       = -32768;
  int16_t  peak     = -32768;
  int16_t  volts10  = -32768;  // tenths, so the compare is integer
  int16_t  speed    = -32768;
  bool     bigSpeed = false;
  bool     over     = false;   // over the posted limit
  int16_t  limit    = -32768;
  uint8_t  maneuver = 255;
  int32_t  dist     = -1;
  char     street[HUD_STREET_MAX] = { 0 };
  bool     camera   = false;
  bool     far      = false;   // the turn is far enough to be drawn dim
  // The "PS" label never changes, so it is drawn once and then left alone.
  // Cleared by dashForget_ along with everything else, which is right: every
  // caller of it has just wiped the screen.
  bool     psLabelDrawn = false;
};
static DashShown dash_;

static inline void dashForget_() { dash_ = DashShown(); }

// ---------------------------------------------------------------------------
//  band 1
// ---------------------------------------------------------------------------
static void dashTacho_(uint32_t now) {
  const bool blank = car.cranking || carStale(car.tRpm, now);
  int8_t lit = 0;
  if (!blank)
    for (int8_t i = 0; i < TACHO_COUNT; i++)
      if (car.rpm >= (uint16_t)((i + 1) * TACHO_STEP)) lit = i + 1;

  const bool blinking = (lit > TACHO_COUNT - TACHO_REDSEG);
  const bool on = blinking ? ((now / TACHO_BLINK_MS) & 1) == 0 : true;

  if (lit == dash_.lit && (!blinking || on == dash_.blinkOn)) return;
  dash_.lit = lit;
  dash_.blinkOn = on;

  for (int8_t i = 0; i < TACHO_COUNT; i++) {
    const int x = TACHO_X + i * TACHO_PITCH;
    const bool red = (i >= TACHO_COUNT - TACHO_REDSEG);
    if (i < lit) {
      const uint16_t col = red ? DASH_RED : DASH_AMBER;
      // Blinking is local to the red pair on purpose: flashing all ten turns
      // the left third of the screen into a strobe over the road.
      tft.fillRect(x, TACHO_Y, TACHO_SEG_W, TACHO_SEG_H,
                   (red && blinking && !on) ? DASH_BG : col);
    } else {
      // An empty slot is an OUTLINE, not a faint block. A filled dark block
      // vanishes into the panel when nothing beside it is lit -- which is the
      // state the bar is in most of the time -- and the outline also emits
      // less light than the filled version did.
      tft.fillRect(x, TACHO_Y, TACHO_SEG_W, TACHO_SEG_H, DASH_BG);
      tft.drawRect(x, TACHO_Y, TACHO_SEG_W, TACHO_SEG_H, DASH_DIM);
      tft.drawRect(x + 1, TACHO_Y + 1, TACHO_SEG_W - 2, TACHO_SEG_H - 2, DASH_DIM);
    }
  }
}

static void dashVolts_(uint32_t now) {
  // 14.2 every single drive carries no information. It earns attention only
  // when it stops being 14.2 -- so it sits dim, and goes red when the
  // alternator is not doing its job. Cranking is exempt: ~9 V is normal there.
  const bool blank = car.cranking || carStale(car.tVolt, now);
  const int16_t v10 = blank ? -32768 : (int16_t)(car.volts * 10.0f + 0.5f);
  if (v10 == dash_.volts10) return;
  dash_.volts10 = v10;

  DASH_FONT(DF_MID);
  tft.setTextDatum(ML_DATUM);
  tft.setTextPadding(74);            // "14.2V" measures 69 px at 28
  if (blank) {
    tft.setTextColor(DASH_BG, DASH_BG);
    tft.drawString(" ", DASH_VOLT_X, DASH_VOLT_Y, DASH_FN_MID);
  } else {
    const bool bad = (v10 < 130 || v10 > 150);
    char buf[10];
    snprintf(buf, sizeof buf, "%d.%dV", v10 / 10, v10 % 10);
    tft.setTextColor(bad ? DASH_RED : DASH_DIM, DASH_BG);
    tft.drawString(buf, DASH_VOLT_X, DASH_VOLT_Y, DASH_FN_MID);
  }
  tft.setTextPadding(0);
}

static void dashPower_(uint32_t now) {
  const bool blank = car.cranking || carStale(car.tTorque, now);
  const int16_t ps   = blank ? -32768 : car.ps;
  const int16_t peak = blank ? -32768 : (car.peakPs > car.ps ? car.peakPs : car.ps);

  if (ps != dash_.ps) {
    dash_.ps = ps;
    DASH_FONT(DF_POWER);
    tft.setTextDatum(MR_DATUM);
    // Sized for the widest string this field can *ever* show, not the widest
    // it usually shows: hud_car.h allows -500..500 and says it must go
    // negative on overrun. "-888" is 98 px. When a string is wider than the
    // padding TFT_eSPI skips the fill completely, so an unpadded minus sign
    // stays lit for the rest of the drive.
    tft.setTextPadding(106);
    tft.setTextColor(DASH_AMBER, DASH_BG);
    if (blank) tft.drawString(" ", DASH_PS_X, DASH_PS_Y, DASH_FN_POWER);
    else       tft.drawNumber(ps, DASH_PS_X, DASH_PS_Y, DASH_FN_POWER);
    tft.setTextPadding(0);
    // The label really is drawn once now. It used to be inside this block
    // with a comment claiming otherwise, so it was re-inked -- and cost a font
    // switch -- on every PS change, which is the 0x0A8 frame rate.
    if (!dash_.psLabelDrawn) {
      dash_.psLabelDrawn = true;
      tft.setTextDatum(ML_DATUM);
      tft.setTextColor(DASH_DIM, DASH_BG);
      DASH_FONT(DF_UNIT);
      tft.drawString("PS", DASH_PSLAB_X, DASH_PSLAB_Y, DASH_FN_UNIT);
    }
  }
  if (peak != dash_.peak) {
    dash_.peak = peak;
    tft.setTextDatum(MR_DATUM);
    tft.setTextColor(DASH_DIM, DASH_BG);
    DASH_FONT(DF_TINY);
    tft.drawString("PEAK", DASH_PKLAB_X, DASH_PKLAB_Y, DASH_FN_TINY);
    DASH_FONT(DF_MID);
    tft.setTextPadding(66);          // "-888" at 28, same reason as PS
    if (blank) tft.drawString(" ", DASH_PEAK_X, DASH_PEAK_Y, DASH_FN_MID);
    else       tft.drawNumber(peak, DASH_PEAK_X, DASH_PEAK_Y, DASH_FN_MID);
    tft.setTextPadding(0);
  }
}

// ---------------------------------------------------------------------------
//  band 2
// ---------------------------------------------------------------------------
static void dashSpeed_(const HudState& s, bool phoneUp, uint32_t now) {
  // The bus is the better source: 0x1A6 updates every 100-300 ms, works in
  // tunnels and has none of the GPS lag. The phone is the fallback.
  int16_t v;
  if (!car.cranking && !carStale(car.tSpeed, now)) v = (int16_t)(car.kmh + 0.5f);
  else if (phoneUp && s.speed >= 0)                v = s.speed;
  else                                             v = -32768;

  // Over the limit turns the speed itself red. It does not blink: the speed
  // is the one number you always want to be able to read, and the red tacho
  // pair already owns blinking on this panel -- two blinking things at once
  // and neither means anything.
  const bool over = phoneUp && (s.flags & FLAG_OVER_LIMIT) != 0;
  const bool big  = !phoneUp;
  if (v == dash_.speed && big == dash_.bigSpeed && over == dash_.over) return;
  if (big != dash_.bigSpeed) {                      // the field changed size
    tft.fillRect(0, DASH_B2_TOP, SCR_W, DASH_B2_BOT - DASH_B2_TOP, DASH_BG);
    dash_.limit = -32768; dash_.maneuver = 255; dash_.dist = -1;
  }
  dash_.speed = v; dash_.bigSpeed = big; dash_.over = over;

  const int x  = big ? DASH_BIGSPD_X : DASH_SPD_X;
  const int y  = big ? DASH_BIGSPD_Y : DASH_SPD_Y;
  const int uy = big ? DASH_BUNIT_Y  : DASH_SUNIT_Y;

  DASH_FONT(big ? DF_BIG : DF_SPEED);
  tft.setTextDatum(MC_DATUM);
  tft.setTextColor(over ? DASH_RED : DASH_AMBER, DASH_BG);
  tft.setTextSize(big ? DASH_BIG_SCALE : 1);
  // Measured: "888" is 234 px at 130 and 135 px at 75.
  tft.setTextPadding(big ? DASH_PAD_SPD_BIG : DASH_PAD_SPD);
  if (v == -32768) tft.drawString("--", x, y, DASH_FN_BIG);
  else             tft.drawNumber(v, x, y, DASH_FN_BIG);
  tft.setTextSize(1);
  tft.setTextPadding(0);

  tft.setTextColor(DASH_DIM, DASH_BG);
  DASH_FONT(DF_UNIT);
  tft.drawString("km/h", big ? DASH_BUNIT_X : DASH_SUNIT_X, uy, DASH_FN_UNIT);
}

static void dashLimit_(const HudState& s, bool phoneUp) {
  const int16_t lim = phoneUp ? s.limit : 0;
  if (lim == dash_.limit) return;
  dash_.limit = lim;

  const int r = DASH_LIM_R;
  tft.fillRect(DASH_LIM_X - r, DASH_LIM_Y - r, r * 2 + 1, r * 2 + 1, DASH_BG);
  if (lim == 0) return;                             // nothing known: draw nothing

  for (int i = 0; i < DASH_LIM_RING; i++)
    tft.drawCircle(DASH_LIM_X, DASH_LIM_Y, r - i, DASH_AMBER);

  tft.setTextDatum(MC_DATUM);
  tft.setTextColor(DASH_AMBER, DASH_BG);
  if (lim < 0) {
    DASH_FONT(DF_POWER);                                         // derestricted
    tft.drawString("---", DASH_LIM_X, DASH_LIM_Y, DASH_FN_POWER);
  } else {
    DASH_FONT(DF_POWER);
    tft.drawNumber(lim, DASH_LIM_X, DASH_LIM_Y, DASH_FN_POWER);
  }
}

static void dashTurn_(const HudState& s, bool phoneUp) {
  const uint8_t man = phoneUp ? s.maneuver : (uint8_t)MAN_NONE;
  const int32_t d   = phoneUp ? s.distToMan : -1;
  if (man == dash_.maneuver && d == dash_.dist) return;
  // Past DASH_FAR_M the glyph is drawn dim. That is a COLOUR change, so it
  // needs the same clear a shape change does: paint dim over amber and the
  // bright pixels underneath survive, leaving a two-tone arrow.
  const bool far = (d > DASH_FAR_M);
  const bool shapeChanged = (man != dash_.maneuver) || (far != dash_.far);
  dash_.maneuver = man; dash_.dist = d; dash_.far = far;

  if (shapeChanged) {
    tft.fillRect(0, DASH_B2_TOP, DASH_ARROW_X + DASH_ARROW_SZ,
                 DASH_B2_BOT - DASH_B2_TOP, DASH_BG);
  }
  if (man == MAN_NONE) return;

  // Dimming the glyph AND its distance together is deliberate: they are one
  // thought, and an arrow that fades while its number stays bright looks like
  // a fault rather than a decision.
  const uint16_t col = far ? DASH_DIM : DASH_AMBER;

  // Only when it actually changed. The distance ticks down four times a
  // second; re-inking a 1400-pixel glyph at 4 Hz buys no new information and
  // costs a visible shimmer.
  if (shapeChanged) {
    DASH_FONT(DF_UNIT);
    arrowArt(DASH_ARROW_X, DASH_ARROW_Y, DASH_ARROW_SZ, man, s.rbExit, col,
             DASH_FN_UNIT);
  }

  if (d >= 0) {
    char num[12]; const char* unit = "m";
    formatDistance(d, num, sizeof num, &unit);
    tft.setTextDatum(MC_DATUM);
    tft.setTextColor(col, DASH_BG);
    DASH_FONT(DF_DIST);
    tft.setTextPadding(88);          // "8888" measures 79 px at 33
    tft.drawString(num, DASH_DIST_X, DASH_DIST_Y, DASH_FN_DIST);
    tft.setTextPadding(0);
    tft.setTextColor(DASH_DIM, DASH_BG);
    DASH_FONT(DF_SMALL);
    // Padding is what paints the background for a smooth font: with padX at 0
    // and _fillbg false, drawGlyph writes only the lit pixels. The built-in
    // fonts always filled their character cell, so this field never needed it
    // before -- and without it "km" going to "m" leaves the k on the glass.
    tft.setTextPadding(30);
    tft.drawString(unit, DASH_DUNIT_X, DASH_DUNIT_Y, DASH_FN_SMALL);
    tft.setTextPadding(0);
  }
}

// ---------------------------------------------------------------------------
//  band 3
// ---------------------------------------------------------------------------
static void dashStreet_(const HudState& s, bool phoneUp) {
  const bool cam = phoneUp && s.camKind != CAM_NONE;
  char line[HUD_STREET_MAX + 12];

  if (!phoneUp)      line[0] = '\0';
  else if (cam) {
    if (s.camDistance > 0) snprintf(line, sizeof line, "CAMERA %ld m", (long)s.camDistance);
    else                   snprintf(line, sizeof line, "CAMERA ZONE");
  } else {
    strncpy(line, s.street, sizeof line - 1);
    line[sizeof line - 1] = '\0';
  }

  if (cam == dash_.camera && strncmp(line, dash_.street, HUD_STREET_MAX - 1) == 0) return;
  dash_.camera = cam;
  strncpy(dash_.street, line, HUD_STREET_MAX - 1);
  dash_.street[HUD_STREET_MAX - 1] = '\0';

  tft.fillRect(0, DASH_B3_TOP, SCR_W, DASH_B3_BOT - DASH_B3_TOP, DASH_BG);
  if (line[0] == '\0') return;
  DASH_FONT(DF_STREET);
  // Top datum acts on the font's box, which reserves room above capitals for
  // accents. Subtracting CAPTOP puts the capital's top edge exactly where the
  // bench put it instead of CAPTOP pixels lower -- which, at the bottom of the
  // panel, is the difference between fitting and being clipped.
  tft.setTextDatum(TL_DATUM);
  tft.setTextColor(cam ? DASH_RED : DASH_AMBER, DASH_BG);
  // The band was cleared just above, so no padding is needed -- and padding
  // would be wrong here anyway, because the string is a different width every
  // time and a fixed pad would clip the long ones.
  tft.drawString(line, DASH_ST_X, DASH_ST_Y - DASH_ST_CAPTOP, DASH_FN_STREET);
}

// ---------------------------------------------------------------------------
//  the five entry points every theme implements
// ---------------------------------------------------------------------------
static void themeInit() {
  tft.fillScreen(DASH_BG);
  tft.setTextColor(DASH_AMBER, DASH_BG);
  dashForget_();
}

static void themeSplash(const char* line1, const char* line2, bool error) {
  tft.fillScreen(DASH_BG);
  dashForget_();
  tft.setTextDatum(MC_DATUM);
  tft.setTextColor(error ? DASH_RED : DASH_AMBER, DASH_BG);
  // No setTextSize here: it does nothing to a smooth font, so the doubling
  // this used to ask for was silently ignored. The headline is the 31 px
  // street face, which is the largest one that carries letters.
  tft.setTextSize(DASH_BIG_SCALE == 1 ? 1 : 2);
  DASH_FONT(DF_STREET);
  tft.drawString(line1, SCR_W / 2, SCR_H / 2 - 26, DASH_FN_STREET);
  tft.setTextSize(1);
  if (line2 && *line2) {
    tft.setTextColor(DASH_DIM, DASH_BG);
    // DF_STREET, not DF_UNIT: the unit face carries the characters of "PS"
    // and "km/h" and nothing else, and a smooth font draws a hollow box for a
    // glyph it does not have -- so "waiting for the phone..." came out as a
    // row of rectangles on every boot.
    DASH_FONT(DF_STREET);
    tft.drawString(line2, SCR_W / 2, SCR_H / 2 + 34, DASH_FN_STREET);
  }
}

/** Everything, from a blank screen. */
static void themeRenderFull(const HudState& s) {
  tft.fillScreen(DASH_BG);
  dashForget_();
  tft.drawFastHLine(0, DASH_RULE_Y, SCR_W, DASH_FAINT);
  const uint32_t now = millis();
  dashTacho_(now); dashVolts_(now); dashPower_(now);
  dashSpeed_(s, true, now); dashLimit_(s, true); dashTurn_(s, true);
  dashStreet_(s, true);
}

/** Only what moved. Called on every phone frame. */
static void themeRenderDelta(const HudState& cur, const HudState& shown) {
  (void)shown;                       // dash_ is the record of what is painted
  const uint32_t now = millis();
  dashSpeed_(cur, true, now); dashLimit_(cur, true);
  dashTurn_(cur, true);  dashStreet_(cur, true);
}

/**
 * Called every pass whether or not anything arrived. This is where the car
 * half lives, so the engine data keeps running when the phone is asleep, in a
 * pocket, or not there at all.
 */
static void themeTick(const HudState& s, uint32_t nowMs) {
  dashTacho_(nowMs);
  dashVolts_(nowMs);
  dashPower_(nowMs);
  dashSpeed_(s, true, nowMs);        // CAN speed keeps updating between frames
}

/** The no-phone screen: the car half only, speed enlarged to fill the middle. */
static void themeRenderCarOnly(uint32_t nowMs, bool relayout) {
  // `relayout` rather than a static flag: the phone comes and goes, and a
  // latch that never resets leaves the second disconnection painting onto
  // whatever the nav half left behind.
  if (relayout) {
    tft.fillScreen(DASH_BG);
    dashForget_();
    tft.drawFastHLine(0, DASH_RULE_Y, SCR_W, DASH_FAINT);
  }
  HudState none; hudStateInit(none);
  dashTacho_(nowMs); dashVolts_(nowMs); dashPower_(nowMs);
  dashSpeed_(none, false, nowMs);
  dashLimit_(none, false); dashTurn_(none, false); dashStreet_(none, false);
}

#endif  // THEME_DASH_H
