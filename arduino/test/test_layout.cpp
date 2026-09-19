// Compiles both themes on the host and checks their layout geometry.
//   g++ -std=c++17 -Wall -I. -I../NavHud test_layout.cpp -o layout && ./layout
//
// Two properties are checked for every state the drive produces:
//   1. nothing is drawn outside the 480x320 panel
//   2. no two screen zones ever write to the same pixel
// Which is exactly the class of bug that otherwise only shows up once the
// thing is glued to your dashboard.

#include "tft_stub.h"

const char* g_zone = nullptr;
std::vector<Box> g_boxes;
int g_outOfBounds = 0;
int g_badGlyphs = 0;
bool TFT_eSPI::g_reportOob = true;

#include "../NavHud/hud_geom.h"
#include "../NavHud/hud_canvas.h"

// Exactly what the sketch declares: the driver, and the geometry layer that
// every theme actually draws through. Testing the themes against a bare
// TFT_eSPI would test a stack that does not ship.
TFT_eSPI  rawTft;
HudCanvas tft(rawTft);

#include "../NavHud/hud_protocol.h"
#include "../NavHud/hud_theme.h"          // pulls in the theme selected below

// ---------------------------------------------------------------------------

static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { printf("  FAIL: %s\n", msg); failures++; } } while (0)

static HudState mk(int speed, int limit, int man, int ex, int dist,
                   int eta, int rem, int flags, const char* street) {
  HudState s; hudStateInit(s);
  s.speed = speed; s.limit = limit; s.maneuver = man; s.rbExit = ex;
  s.distToMan = dist; s.etaSeconds = eta; s.remaining = rem; s.flags = flags;
  strncpy(s.street, street, HUD_STREET_MAX - 1);
  return s;
}

// Every state worth drawing: each maneuver, each edge case.
static std::vector<HudState> corpus() {
  std::vector<HudState> v;
  const char* longName = "CHAUSSEE DE LOUVAIN";
  for (int m = 0; m < MAN_COUNT; m++)
    v.push_back(mk(72, 50, m, m == MAN_ROUNDABOUT ? 3 : 0, 180, 845, 7300,
                   FLAG_GPS_OK, longName));
  v.push_back(mk(128, 120, MAN_MERGE_LEFT, 0, 6200, 1810, 52000,
                FLAG_GPS_OK | FLAG_OVER_LIMIT, "E40"));
  v.push_back(mk(128, 120, MAN_RAMP_RIGHT, 0, 2500, 900, 3700,
                FLAG_GPS_OK | FLAG_OVER_LIMIT | FLAG_LOW_CONF, "EXIT 22"));
  v.push_back(mk(19, 30, MAN_ARRIVE, 0, 50, 0, 50,
                FLAG_GPS_OK | FLAG_ARRIVED, "DESTINATION"));
  v.push_back(mk(-1, 0, MAN_NONE, 0, 0, 0, 0, 0, ""));                 // no fix
  v.push_back(mk(0, -1, MAN_STRAIGHT, 0, 99999, 7200, 250000,          // derestricted
                FLAG_GPS_OK, "A"));
  v.push_back(mk(999, 130, MAN_UTURN, 0, 12000, 99999, 999999,         // absurd
                FLAG_GPS_OK | FLAG_OVER_LIMIT | FLAG_OFF_ROUTE, "XXXXXXXXXXXXXXXXXXXX"));
  v.push_back(mk(31, 30, MAN_ROUNDABOUT, 7, 90, 1204, 9100,            // 7th exit
                FLAG_GPS_OK, "N9"));

  // Lane guidance, including the three-state case: lane 2 allows both
  // straight on and the exit, and the exit is the movement to follow.
  {
    HudState l = mk(118, 120, MAN_RAMP_RIGHT, 0, 700, 900, 3700, FLAG_GPS_OK, "E40");
    l.laneCount = 4; l.laneActive = 0b1100;
    l.lanes[0] = LANE_STRAIGHT; l.lanes[1] = LANE_STRAIGHT;
    l.lanes[2] = LANE_STRAIGHT | LANE_RIGHT; l.lanes[3] = LANE_RIGHT;
    l.laneChosen[2] = LANE_RIGHT; l.laneChosen[3] = LANE_RIGHT;
    v.push_back(l);
  }
  // The widest strip the wire format allows, every lane carrying three
  // movements: the case that would run arrows off the edge of the panel.
  {
    HudState l = mk(46, 50, MAN_LEFT, 0, 120, 300, 1400, FLAG_GPS_OK, "N4");
    l.laneCount = 8; l.laneActive = 0b00000011;
    for (int i = 0; i < 8; i++)
      l.lanes[i] = LANE_LEFT | LANE_STRAIGHT | LANE_RIGHT;
    l.laneChosen[0] = LANE_LEFT; l.laneChosen[1] = LANE_LEFT;
    v.push_back(l);
  }
  // A usable lane the router gave no chosen movement for: everything it
  // allows stays lit rather than one being picked at random.
  {
    HudState l = mk(28, 30, MAN_UTURN, 0, 40, 200, 900, FLAG_GPS_OK, "PLACE");
    l.laneCount = 3; l.laneActive = 0b001;
    l.lanes[0] = LANE_UTURN | LANE_LEFT; l.lanes[1] = LANE_STRAIGHT;
    l.lanes[2] = LANE_SHARP_RIGHT;
    v.push_back(l);
  }
  return v;
}

struct ZonePaint { std::string zone; std::vector<uint8_t> px; };

// Renders one state zone by zone and reports any pixel two zones both touch.
static int overlapsFor(const HudState& s,
                       const std::vector<std::pair<const char*, void(*)(const HudState&)>>& zones) {
  std::vector<ZonePaint> paints;
  for (auto& z : zones) {
    g_boxes.clear();
    g_zone = z.first;
    z.second(s);
    ZonePaint p; p.zone = z.first; p.px.assign(HUD_SCR_W * HUD_SCR_H, 0);
    for (const Box& b : g_boxes) {
      if (!b.zone) continue;                     // fillRect clears do not count
      for (int y = std::max(0, b.y0); y <= std::min(HUD_SCR_H - 1, b.y1); y++)
        for (int x = std::max(0, b.x0); x <= std::min(HUD_SCR_W - 1, b.x1); x++)
          p.px[y * HUD_SCR_W + x] = 1;
    }
    paints.push_back(std::move(p));
  }
  int clashes = 0;
  for (size_t i = 0; i < paints.size(); i++)
    for (size_t j = i + 1; j < paints.size(); j++) {
      int n = 0;
      for (size_t k = 0; k < paints[i].px.size(); k++)
        if (paints[i].px[k] && paints[j].px[k]) n++;
      if (n > 0) {
        printf("    %s x %s overlap on %d px\n",
               paints[i].zone.c_str(), paints[j].zone.c_str(), n);
        clashes++;
      }
    }
  return clashes;
}

int main() {
#if defined(HUD_THEME_MODERN)
  const char* themeName = "modern";
  std::vector<std::pair<const char*, void(*)(const HudState&)>> zones = {
    {"arrow",    [](const HudState& s) { mDrawManeuver(s, M_ARROW); }},
    {"distance", mDrawDistance},
    {"street",   mDrawStreet},
    {"limit",    mDrawLimit},
    {"speed",    mDrawSpeed},
    {"bar",      mDrawBar},
  };
#else
  const char* themeName = "e60";
  std::vector<std::pair<const char*, void(*)(const HudState&)>> zones = {
    {"limit",    e60DrawLimit},
    {"speed",    e60DrawSpeed},
    {"arrow",    e60DrawManeuver},
    {"distance", e60DrawDistance},
    {"street",   e60DrawStreet},
    {"footer",   e60DrawFooter},
  };
#endif

  printf("theme: %s\n\n", themeName);
  auto states = corpus();

  printf("1. every primitive stays inside %dx%d\n", HUD_SCR_W, HUD_SCR_H);
  g_outOfBounds = 0;
  for (const HudState& s : states) {
    g_boxes.clear(); g_zone = "full";
    themeRenderFull(s);
  }
  CHECK(g_outOfBounds == 0, "nothing drawn off-panel");
  CHECK(g_badGlyphs == 0, "every glyph exists in the font it was drawn with");
  printf("    %d states rendered, %d out-of-bounds primitives\n",
         (int)states.size(), g_outOfBounds);

  printf("2. no two zones write the same pixel\n");
  TFT_eSPI::g_reportOob = false;
  int totalClashes = 0;
  for (size_t i = 0; i < states.size(); i++) {
    int c = overlapsFor(states[i], zones);
    if (c) printf("    (state %d: maneuver %d, limit %d, speed %d)\n",
                  (int)i, states[i].maneuver, states[i].limit, states[i].speed);
    totalClashes += c;
  }
  CHECK(totalClashes == 0, "zones are disjoint");
  printf("    %d zone pairs clash across %d states\n", totalClashes, (int)states.size());
  TFT_eSPI::g_reportOob = true;

  printf("3. the delta renderer only repaints what changed\n");
  {
    HudState a = states[0], b = states[0];
    g_boxes.clear(); g_zone = "delta";
    themeRenderDelta(a, b);
    size_t noChange = g_boxes.size();
    b.speed = a.speed + 1;
    g_boxes.clear();
    themeRenderDelta(b, a);
    size_t speedChange = g_boxes.size();
    CHECK(noChange == 0, "identical states draw nothing");
    CHECK(speedChange > 0, "a speed change draws something");
    printf("    identical: %zu primitives, speed changed: %zu\n", noChange, speedChange);
  }

  printf("4. the splash screens and the alignment pattern render\n");
  {
    g_outOfBounds = 0; g_boxes.clear(); g_zone = "splash";
    themeSplash("NavHUD", "waiting for the phone...", false);
    themeSplash("NO LINK", "check the USB cable", true);
    // The pattern draws right up against all four edges by design -- it is
    // what you line the reflection up against -- so it is the one screen most
    // likely to fall a pixel off, and it is drawn while somebody is watching.
    themeAlignPattern();
    CHECK(g_outOfBounds == 0, "splash and pattern stay on-panel");
    printf("    ok\n");
  }

#if !defined(HUD_THEME_MODERN)
  printf("5. the over-limit blink repaints the roundel and nothing else\n");
  {
    HudState s = mk(128, 120, MAN_STRAIGHT, 0, 500, 600, 4000,
                    FLAG_GPS_OK | FLAG_OVER_LIMIT, "E40");
    e60BlinkPhase = true;
    g_boxes.clear(); g_zone = "blink";
    themeTick(s, 0);                 // phase stays true -> no repaint
    size_t same = g_boxes.size();
    g_boxes.clear();
    themeTick(s, 700);               // 700 % 900 = 700 >= 550 -> phase flips
    size_t flipped = g_boxes.size();
    CHECK(same == 0, "no repaint while the phase holds");
    CHECK(flipped > 0, "repaint when the phase flips");
    printf("    hold: %zu primitives, flip: %zu\n", same, flipped);

    HudState ok = s; ok.flags = FLAG_GPS_OK;
    g_boxes.clear();
    themeTick(ok, 700);
    CHECK(g_boxes.empty(), "no blinking when under the limit");
  }
#endif

  printf("6. a keystone correction keeps the layout on the panel\n");
  {
    // The realistic shape: the dash tilts back, so the top of the reflection
    // is wider than the bottom and the driver pulls the two top corners in.
    // Every pull is inward, so a layout that fitted before must still fit.
    tft.geom.corners.dx[0] =  48; tft.geom.corners.dy[0] =  18;
    tft.geom.corners.dx[1] = -48; tft.geom.corners.dy[1] =  18;
    tft.geom.corners.dx[2] = -10; tft.geom.corners.dy[2] = -6;
    tft.geom.corners.dx[3] =  10; tft.geom.corners.dy[3] = -6;
    CHECK(tft.geom.rebuild(HUD_SCR_W, HUD_SCR_H), "the correction is usable");
    CHECK(!tft.geom.identity, "and it is actually doing something");

    g_outOfBounds = 0;
    for (const HudState& s : states) {
      g_boxes.clear(); g_zone = "keystone";
      themeRenderFull(s);
    }
    themeSplash("NavHUD", "waiting for the phone...", false);
    themeAlignPattern();
    CHECK(g_outOfBounds == 0, "keystoned layout stays on-panel");
    printf("    %d states, %d out-of-bounds primitives\n",
           (int)states.size(), g_outOfBounds);
  }

  printf("7. the worst legal correction still produces finite coordinates\n");
  {
    // Corners dragged outward to the clamp. This one is *allowed* to run off
    // the panel -- there are no pixels out there and the driver asked for it --
    // so the check is not "on-panel" but "not at plus or minus two billion",
    // which is what a divide by a near-zero denominator would give and what
    // would make the driver spend a whole frame rasterising a triangle the
    // size of a country.
    tft.geom.corners.dx[0] = -900; tft.geom.corners.dy[0] = -900;
    tft.geom.corners.dx[1] =  900; tft.geom.corners.dy[1] = -900;
    tft.geom.corners.dx[2] =  900; tft.geom.corners.dy[2] =  900;
    tft.geom.corners.dx[3] = -900; tft.geom.corners.dy[3] =  900;
    tft.geom.rebuild(HUD_SCR_W, HUD_SCR_H);
    TFT_eSPI::g_reportOob = false;
    int wild = 0;
    for (const HudState& s : states) {
      g_boxes.clear(); g_zone = "extreme";
      themeRenderFull(s);
      for (const Box& b : g_boxes) {
        if (b.x0 < -4 * HUD_SCR_W || b.x1 > 4 * HUD_SCR_W ||
            b.y0 < -4 * HUD_SCR_H || b.y1 > 4 * HUD_SCR_H) wild++;
      }
    }
    TFT_eSPI::g_reportOob = true;
    CHECK(wild == 0, "no coordinate ran away");
    printf("    %d runaway primitives\n", wild);
    tft.geom.corners = GeomCorners{{0, 0, 0, 0}, {0, 0, 0, 0}};
    tft.geom.rebuild(HUD_SCR_W, HUD_SCR_H);
  }

  printf(failures ? "\n%d CHECK(s) FAILED\n" : "\nall checks passed\n", failures);
  return failures ? 1 : 0;
}
