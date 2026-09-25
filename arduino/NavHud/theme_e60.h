// ---------------------------------------------------------------------------
//  Theme: BMW E60
//
//  The 2004-2010 5-series head-up display was monochrome amber, projected on
//  the windscreen: a large speed reading with navigation underneath, and very
//  little else. This reproduces that, with one deliberate departure -- the
//  speed turns red when you are over the limit, and the roundel blinks between
//  red and amber (never to black, so the number stays readable).
//
//  TYPOGRAPHY. The real thing used a DIN-like face. TFT_eSPI's built-in fonts
//  are the fallback here and look more "digital clock" than "BMW". For the
//  authentic look, convert a DIN-alike TTF (D-DIN and Barlow Condensed are both
//  free) to TFT_eSPI's .vlw format with the Processing sketch that ships with
//  the library, put it in SPIFFS, and define E60_SMOOTH_FONT. See docs/BUILD.md.
// ---------------------------------------------------------------------------

#ifndef THEME_E60_H
#define THEME_E60_H

// ---- palette (RGB565) ------------------------------------------------------
#define E60_AMBER       0xFCE0    // #FF9D00, the projected colour
#define E60_AMBER_DIM   0xA2E0    // #A05C00, secondary information
#define E60_AMBER_FAINT 0x4940    // #4A2A00, rules and inactive outlines
#define E60_LANE_LIT    0x2100    // #221000, the backing behind a usable lane
#define E60_RED         0xF9C4    // #FF3B20, over the limit
#define E60_BG          TFT_BLACK

// what the shared alignment pattern draws with
#define HUD_ALIGN_FG    E60_AMBER
#define HUD_ALIGN_DIM   E60_AMBER_FAINT
#define HUD_ALIGN_BG    E60_BG

// ---- geometry --------------------------------------------------------------
//
// Laid out for 480x320, not scaled up from 320x240. Two and a quarter times
// the area buys three things that were previously compromises: a speed limit
// roundel big enough to read at a glance rather than squint at, a distance
// readout at font 6 with the unit beside it instead of under it, and a lane
// strip with room for a real arrow instead of a tick.
//
// Vertical budget, so no two zones can ever touch:
//   speed 10..146   rule 154   nav 166..262   footer 268..312
static const int E60_SPD_CX = 300, E60_SPD_CY = 70;
static const int E60_UNIT_Y = 130;                      // baseline
static const int E60_LIM_CX = 76,  E60_LIM_CY = 74, E60_LIM_R = 48;
static const int E60_RULE_Y = 154;
static const int E60_ARR_CX = 64,  E60_ARR_CY = 212, E60_ARR_R = 44;
static const int E60_DIST_X = 132, E60_DIST_Y = 228;    // baseline
static const int E60_ST_Y   = 262;                      // baseline
static const int E60_FOOT_Y = 302;                      // baseline
static const int E60_FOOT_MARGIN = 20;                  // left/right inset

// Where the bottom band starts. Lanes, the camera bar and the footer all
// share it, so they share the number too -- three hard-coded 210s was how the
// old layout ended up with a two-pixel overlap nobody could see on a bench.
static const int E60_BAND_Y = 268;

// Blink period for the over-limit roundel.
static const uint32_t E60_BLINK_MS = 900;
static const uint32_t E60_BLINK_ON = 550;

static bool e60BlinkPhase = true;
static bool e60RuleDrawn  = false;

// ---------------------------------------------------------------------------

static void e60DrawRule() {
  // A centre gap, the way BMW's cluster graphics break their rules.
  tft.drawFastHLine(20, E60_RULE_Y, 190, E60_AMBER_FAINT);
  tft.drawFastHLine(270, E60_RULE_Y, SCR_W - 290, E60_AMBER_FAINT);
  e60RuleDrawn = true;
}

static void e60DrawLimit(const HudState& s) {
  const bool over = (s.flags & FLAG_OVER_LIMIT) != 0;
  const uint16_t col = over ? (e60BlinkPhase ? E60_RED : E60_AMBER) : E60_AMBER;

  tft.fillRect(E60_LIM_CX - E60_LIM_R - 3, E60_LIM_CY - E60_LIM_R - 3,
               (E60_LIM_R + 3) * 2, (E60_LIM_R + 3) * 2, E60_BG);

  if (s.limit == 0) {                       // no data: a broken ring
    for (int d = 0; d < 360; d += 24) {
      float a0 = d * DEG_TO_RAD, a1 = (d + 12) * DEG_TO_RAD;
      thickLine(E60_LIM_CX + cosf(a0) * E60_LIM_R, E60_LIM_CY + sinf(a0) * E60_LIM_R,
                E60_LIM_CX + cosf(a1) * E60_LIM_R, E60_LIM_CY + sinf(a1) * E60_LIM_R,
                3, E60_AMBER_FAINT);
    }
    return;
  }

  // A ring, not a filled sign -- a white disc would blow out the reflection.
  for (int i = 0; i < 5; i++) tft.drawCircle(E60_LIM_CX, E60_LIM_CY, E60_LIM_R - i, col);

  if (s.flags & FLAG_LOW_CONF) {            // held over: dashed inner ring
    for (int d = 0; d < 360; d += 30) {
      float a0 = d * DEG_TO_RAD, a1 = (d + 15) * DEG_TO_RAD;
      int r = E60_LIM_R - 8;
      thickLine(E60_LIM_CX + cosf(a0) * r, E60_LIM_CY + sinf(a0) * r,
                E60_LIM_CX + cosf(a1) * r, E60_LIM_CY + sinf(a1) * r,
                2, E60_AMBER_DIM);
    }
  }

  tft.setTextDatum(MC_DATUM);
  tft.setTextColor(col, E60_BG);
  if (s.limit < 0) {
    tft.drawString("---", E60_LIM_CX, E60_LIM_CY, 2);   // derestricted
  } else {
    tft.drawNumber(s.limit, E60_LIM_CX, E60_LIM_CY, 6);
  }
}

// The speed block, brackets included, lives between these two columns. The
// clear has to cover the brackets as well as the digits: Font 8 is 55 px per
// digit, so a three-digit speed puts the left bracket at x = 105 -- left of
// the old clear rectangle's x = 120, and left of everything e60DrawLimit
// clears. Drop from 128 to a legal 110 and the red bracket stayed on the
// windscreen until a link drop forced a full repaint.
#define E60_SPD_CLEAR_X 130
#define E60_SPD_CLEAR_W (SCR_W - E60_SPD_CLEAR_X - 6)

static void e60DrawSpeed(const HudState& s) {
  const bool over = (s.flags & FLAG_OVER_LIMIT) != 0;
  tft.fillRect(E60_SPD_CLEAR_X, 10, E60_SPD_CLEAR_W, 136, E60_BG);

  tft.setTextDatum(MC_DATUM);
  tft.setTextColor(over ? E60_RED : E60_AMBER, E60_BG);
  if (s.speed < 0) {
    tft.drawString("--", E60_SPD_CX, E60_SPD_CY, 8);
  } else {
    tft.drawNumber(s.speed, E60_SPD_CX, E60_SPD_CY, 8);
  }

  // BMW brackets a value when it wants your attention.
  if (over) {
    char spd[8];
    snprintf(spd, sizeof spd, "%d", s.speed);
    int halfW = tft.textWidth(spd, 8) / 2 + 13;
    for (int sgn = -1; sgn <= 1; sgn += 2) {
      int x = E60_SPD_CX + sgn * halfW;
      tft.drawFastVLine(x, E60_SPD_CY - 44, 88, E60_RED);
      tft.drawFastVLine(x + sgn, E60_SPD_CY - 44, 88, E60_RED);
      tft.drawFastHLine(sgn < 0 ? x : x - 9, E60_SPD_CY - 44, 10, E60_RED);
      tft.drawFastHLine(sgn < 0 ? x : x - 9, E60_SPD_CY + 43, 10, E60_RED);
    }
  }

  // No background colour: font 4's 26 px cell starts at y 104, four rows
  // above the bottom of the font 8 digits, and a background fill cut those
  // rows off every speed. The whole block was cleared at the top of this
  // function, so there is nothing under the label to paint over.
  tft.setTextColor(E60_AMBER_DIM);
  tft.setTextDatum(BC_DATUM);
  tft.drawString("km/h", E60_SPD_CX, E60_UNIT_Y, 4);
}

static void e60DrawManeuver(const HudState& s) {
  tft.fillRect(6, 166, 118, 98, E60_BG);
  const uint16_t col = E60_AMBER;
  switch (s.maneuver) {
    case MAN_ROUNDABOUT:
      drawRoundaboutArt(E60_ARR_CX, E60_ARR_CY - 2, 27, 12, s.rbExit, col, 4,
                        E60_ARR_CY + E60_ARR_R);
      break;
    case MAN_UTURN:
      drawUturnArt(E60_ARR_CX, E60_ARR_CY - 10, 24, 15, col);
      break;
    case MAN_ARRIVE:
      for (int i = 0; i < 6; i++) tft.drawCircle(E60_ARR_CX, E60_ARR_CY, 28 - i, col);
      tft.fillCircle(E60_ARR_CX, E60_ARR_CY, 11, col);
      break;
    case MAN_NONE:
      // Nothing to say. The fillRect above has already cleared the box, so
      // falling through to the default here is all it takes to draw a
      // straight-ahead arrow at a car with no route -- angleForManeuver()
      // returns 0 for anything it does not recognise, and 0 is "straight on".
      // That is exactly the bug that put an arrow on the glass the moment the
      // app opened. It cannot happen through the frame any more --
      // displayRouteRule strips the maneuver out before this is reached -- but
      // a theme that draws a turn for "there is no turn" is a trap left lying
      // about, so it is closed here as well.
      break;
    default:
      turnArrow(E60_ARR_CX, E60_ARR_CY, E60_ARR_R, 18, 30,
                angleForManeuver(s.maneuver), col);
      break;
  }
}

static void e60DrawDistance(const HudState& s) {
  // BL_DATUM subtracts the *full* font height, not the baseline, so a font 6
  // string drawn at y = E60_DIST_Y occupies rows E60_DIST_Y-48 upwards. The
  // clear used to start at 124 and miss the top two, so every narrower number
  // left the top scanlines of the wider one it replaced -- a dotted amber line
  // that built up above a countdown refreshing four times a second.
  tft.fillRect(E60_DIST_X - 2, E60_DIST_Y - 50, SCR_W - E60_DIST_X + 2, 54, E60_BG);
  if (s.distToMan <= 0) return;
  char num[10]; const char* unit;
  formatDistance(s.distToMan, num, sizeof num, &unit);
  tft.setTextDatum(BL_DATUM);
  tft.setTextColor(E60_AMBER, E60_BG);
  int x = E60_DIST_X;
  x += tft.drawString(num, x, E60_DIST_Y, 6);
  tft.setTextColor(E60_AMBER_DIM, E60_BG);
  tft.drawString(unit, x + 7, E60_DIST_Y, 4);
}

static void e60DrawStreet(const HudState& s) {
  // Font 4 is a 26 px cell, so BL_DATUM puts its top at E60_ST_Y - 26. The
  // old rectangle started at -22 and left four rows of the previous name
  // behind: turning off a long street onto a short one left a ghost of it.
  tft.fillRect(E60_DIST_X - 2, E60_ST_Y - 28, SCR_W - E60_DIST_X + 2, 34, E60_BG);
  if (!s.street[0]) return;
  // BMW sets road names in caps on the HUD.
  char up[HUD_STREET_MAX];
  for (size_t i = 0; i < sizeof up; i++) {
    char c = s.street[i];
    up[i] = (c >= 'a' && c <= 'z') ? (char)(c - 32) : c;
    if (!c) break;
  }
  up[HUD_STREET_MAX - 1] = '\0';
  int avail = SCR_W - E60_DIST_X - 10;
  int font = (tft.textWidth(up, 4) <= avail) ? 4 : 2;
  tft.setTextDatum(BL_DATUM);
  tft.setTextColor(E60_AMBER, E60_BG);
  tft.drawString(up, E60_DIST_X, E60_ST_Y, font);
}

/**
 * The footer is shared. A camera warning outranks everything; lane guidance
 * outranks the ETA. There is not room on 320x240 to show all three at once, and
 * a screen you cannot read in half a second is worse than one showing less.
 */
static void e60DrawLanes(const HudState& s) {
  const int y0 = E60_BAND_Y, h = 44;
  tft.fillRect(0, y0 - 4, SCR_W, SCR_H - y0 + 4, E60_BG);
  if (s.laneCount == 0) return;
  const int w = SCR_W / s.laneCount;
  const int cy = y0 + h / 2;
  static const uint8_t order[8] = {
    LANE_UTURN, LANE_SHARP_LEFT, LANE_LEFT, LANE_SLIGHT_LEFT,
    LANE_STRAIGHT, LANE_SLIGHT_RIGHT, LANE_RIGHT, LANE_SHARP_RIGHT
  };
  static const float ang[8] = { 180.f, -135.f, -90.f, -40.f, 0.f, 40.f, 90.f, 135.f };

  for (uint8_t i = 0; i < s.laneCount; i++) {
    const int cx = w * i + w / 2;
    const bool on = (s.laneActive >> i) & 1;
    const uint8_t bits = s.lanes[i];
    const uint8_t pick = s.laneChosen[i];

    // Three states, not two. A lane that allows both straight on and the exit
    // used to get two equally bright arrows, which told the driver nothing
    // about which one the route meant. The phone now sends the movement to
    // follow, so: full amber for it, dim for the lane's other movements, faint
    // for lanes that are not ours.
    if (on) tft.fillRect(w * i + 1, y0, w - 2, h, E60_LANE_LIT);

    uint8_t drawn = 0;
    for (uint8_t k = 0; k < 8 && drawn < 3; k++) {
      if (!(bits & order[k])) continue;
      if (order[k] == pick) continue;            // drawn last, on top
      const uint16_t col = on ? E60_AMBER_DIM : E60_AMBER_FAINT;
      arrowHead(cx, cy, ang[k], 16, 12, col);
      thickLine(cx, cy + 15, cx, cy - 3, 4, col);
      drawn++;
    }
    if (pick) {
      for (uint8_t k = 0; k < 8; k++) {
        if (order[k] != pick) continue;
        arrowHead(cx, cy, ang[k], 19, 14, E60_AMBER);
        thickLine(cx, cy + 15, cx, cy - 3, 6, E60_AMBER);
        break;
      }
    } else if (drawn == 0) {
      thickLine(cx, cy + 15, cx, cy - 10, 4, on ? E60_AMBER : E60_AMBER_FAINT);
    }
    if (i + 1 < s.laneCount) tft.drawFastVLine(w * (i + 1), y0 + 5, h - 10, E60_AMBER_FAINT);
  }
}

static void e60DrawCameraBar(const HudState& s) {
  tft.fillRect(0, E60_BAND_Y - 4, SCR_W, SCR_H - E60_BAND_Y + 4, E60_BG);
  const bool zone = (s.camKind == CAM_ZONE);
  tft.drawRect(8, E60_BAND_Y + 2, SCR_W - 16, 36, E60_RED);
  tft.setTextDatum(ML_DATUM);
  tft.setTextColor(E60_RED, E60_BG);
  tft.drawString(zone ? "ZONE" : "RADAR", 18, E60_BAND_Y + 20, 4);
  tft.setTextDatum(MR_DATUM);
  char buf[24];
  if (zone) {
    tft.setTextColor(E60_AMBER, E60_BG);
    tft.drawString("attention", SCR_W - 18, E60_BAND_Y + 20, 4);
  } else {
    if (s.camLimit > 0) snprintf(buf, sizeof buf, "%ld m   %d", (long)s.camDistance, s.camLimit);
    else                snprintf(buf, sizeof buf, "%ld m", (long)s.camDistance);
    tft.setTextColor(E60_AMBER, E60_BG);
    tft.drawString(buf, SCR_W - 18, E60_BAND_Y + 20, 4);
  }
}

static void e60DrawFooter(const HudState& s) {
  // Priority: camera warning, then lane guidance, then the ETA line.
  if (s.camKind != CAM_NONE) { e60DrawCameraBar(s); return; }
  if (s.laneCount > 0) { e60DrawLanes(s); return; }

  tft.fillRect(0, E60_BAND_Y - 4, SCR_W, SCR_H - E60_BAND_Y + 4, E60_BG);
  char buf[24];
  tft.setTextColor(E60_AMBER_DIM, E60_BG);
  if (s.etaSeconds > 0) {
    formatEta(s.etaSeconds, buf, sizeof buf, true);
    tft.setTextDatum(BL_DATUM);
    tft.drawString(buf, E60_FOOT_MARGIN, E60_FOOT_Y, 4);
  }
  if (s.remaining > 0) {
    formatRemaining(s.remaining, buf, sizeof buf, true);
    tft.setTextDatum(BR_DATUM);
    tft.drawString(buf, SCR_W - E60_FOOT_MARGIN, E60_FOOT_Y, 4);
  }
  tft.setTextDatum(BC_DATUM);
  if (!(s.flags & FLAG_GPS_OK)) {
    tft.setTextColor(E60_RED, E60_BG);
    tft.drawString("NO GPS", SCR_W / 2, E60_FOOT_Y, 4);
  } else if (s.flags & FLAG_OFF_ROUTE) {
    tft.setTextColor(E60_AMBER, E60_BG);
    tft.drawString("REROUTE", SCR_W / 2, E60_FOOT_Y, 4);
  }
}

// ---- the five entry points every theme provides ---------------------------

static void themeInit() {
  tft.fillScreen(E60_BG);
  e60RuleDrawn = false;
}

static void themeSplash(const char* line1, const char* line2, bool error) {
  tft.fillScreen(E60_BG);
  e60RuleDrawn = false;
  tft.setTextDatum(MC_DATUM);
  tft.setTextColor(error ? E60_RED : E60_AMBER, E60_BG);
  // Font 4 doubled: the built-in fonts stop at 26 px for anything containing
  // letters (6, 7 and 8 are digits only), so on a 4" panel an un-scaled
  // headline reads as a caption.
  tft.setTextSize(2);
  tft.drawString(line1, SCR_W / 2, SCR_H / 2 - 26, 4);
  tft.setTextSize(1);
  if (line2) {
    tft.setTextColor(E60_AMBER_DIM, E60_BG);
    tft.drawString(line2, SCR_W / 2, SCR_H / 2 + 34, 4);
  }
}

static void themeRenderFull(const HudState& s) {
  tft.fillScreen(E60_BG);
  e60DrawRule();
  e60DrawLimit(s);
  e60DrawSpeed(s);
  e60DrawManeuver(s);
  e60DrawDistance(s);
  e60DrawStreet(s);
  e60DrawFooter(s);
}

static void themeRenderDelta(const HudState& cur, const HudState& shown) {
  if (!e60RuleDrawn) e60DrawRule();
  const uint8_t changed = cur.flags ^ shown.flags;
  if (cur.limit != shown.limit || (changed & (FLAG_OVER_LIMIT | FLAG_LOW_CONF)))
    e60DrawLimit(cur);
  if (cur.speed != shown.speed || (changed & FLAG_OVER_LIMIT))
    e60DrawSpeed(cur);
  if (cur.maneuver != shown.maneuver || cur.rbExit != shown.rbExit)
    e60DrawManeuver(cur);
  if (cur.distToMan != shown.distToMan) e60DrawDistance(cur);
  if (strcmp(cur.street, shown.street) != 0) e60DrawStreet(cur);
  if (cur.etaSeconds / 30 != shown.etaSeconds / 30 ||
      cur.remaining / 100 != shown.remaining / 100 ||
      cur.camKind != shown.camKind ||
      cur.camDistance / 50 != shown.camDistance / 50 ||
      cur.laneCount != shown.laneCount ||
      cur.laneActive != shown.laneActive ||
      memcmp(cur.lanes, shown.lanes, HUD_MAX_LANES) != 0 ||
      memcmp(cur.laneChosen, shown.laneChosen, HUD_MAX_LANES) != 0 ||
      (changed & (FLAG_OFF_ROUTE | FLAG_GPS_OK)))
    e60DrawFooter(cur);
}

/**
 * Called every loop so the things that should pulse, pulse: the roundel when
 * you are over the limit, and the camera bar when one is coming up. A static
 * warning stops being noticed after a minute; a blinking one does not.
 */
static void themeTick(const HudState& s, uint32_t nowMs) {
  const bool over = (s.flags & FLAG_OVER_LIMIT) != 0;
  const bool cam = (s.camKind != CAM_NONE);
  if (!over && !cam) { e60BlinkPhase = true; return; }

  const bool phase = (nowMs % E60_BLINK_MS) < E60_BLINK_ON;
  if (phase == e60BlinkPhase) return;
  e60BlinkPhase = phase;

  if (over) e60DrawLimit(s);
  if (cam) {
    // Flash the bar by drawing it, or clearing it, on alternate phases.
    if (phase) e60DrawCameraBar(s);
    else tft.fillRect(0, E60_BAND_Y - 4, SCR_W, SCR_H - E60_BAND_Y + 4, E60_BG);
  }
}

#endif  // THEME_E60_H
