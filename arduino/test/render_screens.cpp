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
  return g_failed ? 1 : 0;
}
