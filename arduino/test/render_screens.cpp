// Draws every HUD screen to a PNG, pixel for pixel as the firmware draws it.
//   make render   ->  out/<theme>_<nn>_<name>.png   (dash and e60)
//
// The themes run unmodified against tft_stub.h built with -DHUD_RENDER, which
// rasterises each TFT_eSPI call with the library's own algorithms and fonts
// (tft_raster.h). What comes out is the panel's framebuffer:
//   * in the READABLE orientation. On the car the windscreen mirror is a
//     MADCTL bit in the ST7796 (HUD_DEFAULT_MIRROR_X), applied by the panel
//     after drawing, so the glass shows these flipped left-right and the
//     reflection shows them as here.
//   * with keystone off, the factory default: HudCanvas forwards every call
//     verbatim.
//   * in RGB565, expanded to 8 bits a channel.
#include "tft_stub.h"

const char* g_zone = nullptr;
std::vector<Box> g_boxes;
int g_outOfBounds = 0;
int g_badGlyphs = 0;
bool TFT_eSPI::g_reportOob = true;

#include "../NavHud/hud_geom.h"
#include "../NavHud/hud_canvas.h"

// The same pair the sketch declares.
TFT_eSPI  rawTft;
HudCanvas tft(rawTft);

#include "../NavHud/hud_protocol.h"
#if !defined(HUD_THEME_E60_CLASSIC)
#include "../NavHud/hud_car.h"
CarState car;                          // theme_dash.h reads the CAN half from here
#endif
#include "../NavHud/hud_theme.h"
#include "corpus.h"
#include "png_write.h"

#if defined(HUD_THEME_E60_CLASSIC)
static const char* kTheme = "e60";
#else
static const char* kTheme = "dash";
#endif

static std::string g_outDir = "out";
static std::string g_name;
static int g_index = 0, g_failed = 0;

/** Draw one screen from scratch and save the framebuffer. */
template <typename F>
static void screen(const std::string& name, F draw) {
  g_name = name;
  g_zone = g_name.c_str();             // labels any off-panel or missing-glyph report
  const int boxes0 = rawTft.ras.glyphBoxes, font1 = rawTft.ras.font1Calls;
  draw();
  char path[512];
  snprintf(path, sizeof path, "%s/%s_%02d_%s.png", g_outDir.c_str(), kTheme, g_index++, name.c_str());
  if (!writePng565(path, rawTft.ras.fb.data(), HUD_SCR_W, HUD_SCR_H)) {
    printf("  cannot write %s\n", path);
    g_failed++;
    return;
  }
  printf("  %s", path);
  if (rawTft.ras.glyphBoxes > boxes0)
    printf("   [%d hollow glyph box(es)]", rawTft.ras.glyphBoxes - boxes0);
  if (rawTft.ras.font1Calls > font1)
    printf("   [GLCD font 1 text not drawn]");
  printf("\n");
}

// A fixed clock. 10020 ms puts the tacho's 3 Hz blink in its lit half.
static const uint32_t kNow = 10020;

#if !defined(HUD_THEME_E60_CLASSIC)
/** The CAN half. speedFresh = false leaves the speed to the phone. */
static void carLive(float kmh, bool speedFresh, uint16_t rpm, float volts,
                    int16_t ps, int16_t peak) {
  car = CarState();
  car.ignitionOn = true; car.displayOn = true;
  const uint32_t t = kNow - 50;
  car.rpm = rpm;     car.tRpm = t;
  car.volts = volts; car.tVolt = t;
  car.ps = ps; car.peakPs = peak; car.tTorque = t;
  car.kmh = kmh;     car.tSpeed = speedFresh ? t : 0;
}
static void carSilent() { car = CarState(); }     // no CAN at all: everything stale
#endif

/** States the layout corpus does not carry: cameras, a real roundabout bearing. */
static std::vector<std::pair<std::string, HudState>> extras() {
  std::vector<std::pair<std::string, HudState>> v;
  HudState s = mk(88, 90, MAN_RIGHT, 0, 900, 600, 5200, FLAG_GPS_OK, "RUE DE LA LOI");
  s.camKind = CAM_FIXED; s.camDistance = 350; s.camLimit = 70;
  v.push_back({"camera_fixed_350m", s});
  s = mk(47, 50, MAN_STRAIGHT, 0, 1400, 600, 5200, FLAG_GPS_OK, "TUNNEL LEOPOLD II");
  s.camKind = CAM_ZONE; s.camDistance = 0;
  v.push_back({"camera_zone", s});
  s = mk(34, 50, MAN_ROUNDABOUT, 2, 120, 700, 4100, FLAG_GPS_OK, "N5");
  s.rbAngle = -95; s.rbAngleExit = 2;              // the phone's measured bearing
  s.rbAngleDist = s.distToMan;                     // ...stamped as it arrived
  v.push_back({"roundabout_exit2_real_bearing", s});
  v.push_back({"limit_unknown", mk(64, 0, MAN_SLIGHT_RIGHT, 0, 450, 1500, 21000,
                                   FLAG_GPS_OK, "RING R0")});
  // What the app really sends: HudFrame.sanitize() folds accents but keeps case.
  v.push_back({"street_mixed_case", mk(42, 50, MAN_LEFT, 0, 260, 400, 2600,
                                       FLAG_GPS_OK, "Chaussee d'Ixelles")});
  v.push_back({"off_route", mk(57, 70, MAN_NONE, 0, 0, 900, 8000,
                               FLAG_GPS_OK | FLAG_OFF_ROUTE, "BOULEVARD DU ROI")});
  return v;
}

/** A roundabout as the phone sends it: $HUD, then $RAB with the exit's angle. */
static HudState rab(int exitNo, int angle, bool left = false, int dist = 150) {
  HudState s = mk(38, 50, MAN_ROUNDABOUT, exitNo, dist, 700, 4100,
                  FLAG_GPS_OK | FLAG_ROUTE | (left ? FLAG_LEFT_HAND : 0), "N5");
  s.rbAngle = (int16_t)angle; s.rbAngleExit = (uint8_t)exitNo;
  s.rbAngleDist = dist;                            // stamped as it arrived
  return s;
}

/** The roundabout gallery: every case the owner is shown before flashing. */
static std::vector<std::pair<std::string, HudState>> roundabouts() {
  std::vector<std::pair<std::string, HudState>> v;
  // Clock positions: 12 is straight on, 3 is right, 9 is left.
  v.push_back({"rab_9_oclock",           rab(3, -90)});
  v.push_back({"rab_10_oclock",          rab(3, -60)});
  v.push_back({"rab_12_oclock",          rab(2, 0)});
  v.push_back({"rab_1_oclock",           rab(2, 30)});
  v.push_back({"rab_4_oclock",           rab(1, 120)});
  v.push_back({"rab_uturn",              rab(4, -178)});
  v.push_back({"rab_left_traffic_9",     rab(1, -90, true)});
  v.push_back({"rab_left_traffic_3",     rab(3, 90, true)});
  v.push_back({"rab_left_traffic_uturn", rab(4, 178, true)});
  v.push_back({"rab_far_dimmed",         rab(3, -90, false, 1400)});
  // No angle from the phone: the exit-number guess.
  v.push_back({"rab_no_angle_table", mk(38, 50, MAN_ROUNDABOUT, 3, 150, 700, 4100,
                                        FLAG_GPS_OK | FLAG_ROUTE, "N5")});
  // Exit 9, no angle, no table entry: a bare ring.
  v.push_back({"rab_no_angle_bare_ring", mk(38, 50, MAN_ROUNDABOUT, 9, 150, 700, 4100,
                                            FLAG_GPS_OK | FLAG_ROUTE, "N5")});
  // The last roundabout's angle, with the next one 400 m away: not used.
  HudState s = rab(2, 90, false, 5);
  s.distToMan = 400;
  v.push_back({"rab_stale_angle_ignored", s});
  return v;
}

// ---- pixel checks ------------------------------------------------------------
//
// The layout test proves boxes do not collide, but it cannot see a text
// field's padding fill, which is where both 2.8 layout bugs lived: the PS
// number's clear erased the battery's "V", and the E60 km/h label's background
// cell cut the bottom rows off the speed digits. So: draw a field alone, then
// the whole screen, and every pixel the field lit must still be lit.
static std::vector<uint16_t> snap() { return rawTft.ras.fb; }

static void expectSurvives(const std::string& what, const std::vector<uint16_t>& alone,
                           const std::vector<uint16_t>& full) {
  int lit = 0, lost = 0;
  for (size_t i = 0; i < alone.size(); i++) {
    if (alone[i] == 0) continue;
    lit++;
    if (full[i] == 0) lost++;
  }
  if (lit == 0 || lost) {
    printf("  FAIL %s: %d of %d lit pixel(s) erased\n", what.c_str(), lost, lit);
    g_failed++;
  } else {
    printf("  ok   %s (%d px)\n", what.c_str(), lit);
  }
}

static void pixelChecks() {
  printf("pixel checks\n");
#if !defined(HUD_THEME_E60_CLASSIC)
  // The battery reading beside every PS value the field can show, including
  // the overrun values and one past the widest.
  const int16_t psValues[] = { 64, 500, -35, -99, -150, -500 };
  for (int16_t ps : psValues) {
    carLive(87, true, 2200, 14.2f, ps, 212);
    tft.fillScreen(DASH_BG); dashForget_();
    dashVolts_(kNow);
    const std::vector<uint16_t> alone = snap();
    themeRenderCarOnly(kNow, true);
    expectSurvives("battery reading beside PS " + std::to_string(ps), alone, snap());
  }
  // A battery reading that goes stale must leave the glass: the blank is a
  // padded space, and TFT_eSPI fills padding only when the text colour differs
  // from the background.
  {
    carLive(87, true, 2200, 14.2f, 64, 212);
    tft.fillScreen(DASH_BG); dashForget_();
    dashVolts_(kNow);
    car.tVolt = 0;                                   // 0x3B4 gone quiet
    dashVolts_(kNow);
    int left = 0;
    const std::vector<uint16_t> fb = snap();
    for (int y = 0; y < DASH_B1_BOT; y++)
      for (int x = DASH_VOLT_X; x < DASH_VOLT_X + 74; x++)
        if (fb[(size_t)y * HUD_SCR_W + x]) left++;
    if (left) { printf("  FAIL stale battery reading: %d px still lit\n", left); g_failed++; }
    else      printf("  ok   a stale battery reading is cleared\n");
  }
#endif
  // Every roundabout: the glyph drawn alone must survive the whole screen (no
  // other field's clear cuts into it), and the glyph's own clear must take
  // every pixel of it off again when the manoeuvre changes (nothing left on
  // the glass outside its box).
  for (const auto& e : roundabouts()) {
    const HudState s = e.second;
    HudState none = s;
    none.maneuver = MAN_NONE;
    tft.fillScreen(0);
#if !defined(HUD_THEME_E60_CLASSIC)
    dashForget_();
    dashTurn_(s, true);
    const std::vector<uint16_t> alone = snap();
    themeRenderFull(s);
    expectSurvives(e.first + " survives the screen", alone, snap());
    tft.fillScreen(0); dashForget_();
    dashTurn_(s, true);
    dashTurn_(none, true);
#else
    e60DrawManeuver(s);
    const std::vector<uint16_t> alone = snap();
    themeRenderFull(s);
    expectSurvives(e.first + " survives the screen", alone, snap());
    tft.fillScreen(0);
    e60DrawManeuver(s);
    e60DrawManeuver(none);
#endif
    int left = 0;
    for (uint16_t p : snap()) if (p) left++;
    if (left) { printf("  FAIL %s: %d px left after its clear\n", e.first.c_str(), left); g_failed++; }
    else      printf("  ok   %s is inside its clear box\n", e.first.c_str());
  }
#if defined(HUD_THEME_E60_CLASSIC)
  // The speed digits under the km/h label, at two and three digits, over and
  // under the limit (over adds the brackets).
  const int speeds[] = { 72, 88, 188 };
  for (int v : speeds) {
    for (int over = 0; over < 2; over++) {
      HudState s = mk(v, over ? 50 : 200, MAN_NONE, 0, 0, 0, 0,
                      FLAG_GPS_OK | (over ? FLAG_OVER_LIMIT : 0), "");
      tft.fillScreen(E60_BG);
      tft.setTextDatum(MC_DATUM);
      tft.setTextColor(over ? E60_RED : E60_AMBER, E60_BG);
      tft.drawNumber(v, E60_SPD_CX, E60_SPD_CY, 8);
      const std::vector<uint16_t> alone = snap();
      tft.fillScreen(E60_BG);
      e60DrawSpeed(s);
      const std::vector<uint16_t> full = snap();
      expectSurvives("speed " + std::to_string(v) + (over ? " (over)" : "") +
                     " under the km/h label", alone, full);
      // ...and the label does not touch them: at least two clear rows between
      // the lowest digit pixel and the highest label pixel.
      const int lw = tft.textWidth("km/h", 4);
      int digitsBottom = -1, labelTop = HUD_SCR_H;
      for (int y = 0; y < HUD_SCR_H; y++)
        for (int x = E60_SPD_CX - lw / 2; x <= E60_SPD_CX + lw / 2; x++) {
          const size_t i = (size_t)y * HUD_SCR_W + x;
          if (alone[i]) digitsBottom = y;
          else if (full[i] && y > E60_SPD_CY && y < labelTop) labelTop = y;
        }
      const int gap = labelTop - digitsBottom - 1;
      if (gap < 2) {
        printf("  FAIL km/h label %d row(s) under speed %d\n", gap, v);
        g_failed++;
      } else {
        printf("  ok   km/h label %d rows under speed %d\n", gap, v);
      }
    }
  }
#endif
}

int main(int argc, char** argv) {
  if (argc > 1) g_outDir = argv[1];
  g_millis = kNow;
  printf("theme %s -> %s/\n", kTheme, g_outDir.c_str());

  // ---- the screens the sketch shows before and between drives -------------
#if !defined(HUD_THEME_E60_CLASSIC)
  carSilent();
#endif
  screen("splash_starting", [] { themeSplash("NavHUD", "starting...", false); });
  screen("splash_waiting", [] { themeSplash("NavHUD", "waiting for the phone...", false); });
  screen("no_link_usb", [] { themeSplash("NO LINK", "check the USB cable", true); });
  screen("no_link_no_can", [] { themeSplash("NO LINK", "no phone, and no CAN either", true); });
  screen("align_pattern", [] { themeAlignPattern(); });

  // ---- the drive: every layout-corpus state, then the extras --------------
#if !defined(HUD_THEME_E60_CLASSIC)
  // Band 1 needs the car. Sample values, with the speed left to the phone so
  // each picture shows the corpus speed.
  carLive(0, false, 2400, 14.2f, 96, 188);
#endif
  const std::vector<HudState> states = corpus();
  for (size_t i = 0; i < states.size(); i++) {
    const HudState s = states[i];
    screen(corpusName(i), [&] { themeRenderFull(s); });
  }
  for (const auto& e : extras()) {
    const HudState s = e.second;
    screen(e.first, [&] { themeRenderFull(s); });
  }
  for (const auto& e : roundabouts()) {
    const HudState s = e.second;
    screen(e.first, [&] { themeRenderFull(s); });
  }

#if !defined(HUD_THEME_E60_CLASSIC)
  // ---- dash only: the same drive with no CAN, and the no-phone car view ---
  carSilent();
  {
    const HudState s = states[1];
    screen("nav_without_can", [&] { themeRenderFull(s); });
  }
  carLive(87, true, 2200, 14.2f, 64, 212);
  screen("car_only_cruise", [] { themeRenderCarOnly(kNow, true); });
  carLive(132, true, 4900, 12.4f, 305, 318);   // 9 segments: the red pair blinks
  screen("car_only_redline_low_battery", [] { themeRenderCarOnly(kNow, true); });
  carLive(54, true, 1500, 14.4f, -35, 212);    // overrun: negative power
  screen("car_only_overrun", [] { themeRenderCarOnly(kNow, true); });
  carSilent();
  screen("car_only_no_can", [] { themeRenderCarOnly(kNow, true); });
#endif

  printf("%d screens, %d missing-glyph report(s), %d off-panel primitive(s)\n",
         g_index, g_badGlyphs, g_outOfBounds);
  pixelChecks();
  return g_failed ? 1 : 0;
}
