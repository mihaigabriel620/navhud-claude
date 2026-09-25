// The drive states every host test draws: each maneuver, each edge case.
//
// Shared by the layout check (test_layout.cpp) and the screen renderer
// (render_screens.cpp), so the pictures show exactly the states the layout
// was proven on. Add a state here and give it a name in corpusName().
#ifndef HUD_TEST_CORPUS_H
#define HUD_TEST_CORPUS_H

#include <string>
#include <vector>
#include "../NavHud/hud_protocol.h"

static HudState mk(int speed, int limit, int man, int ex, int dist,
                   int eta, int rem, int flags, const char* street) {
  HudState s; hudStateInit(s);
  s.speed = speed; s.limit = limit; s.maneuver = man; s.rbExit = ex;
  s.distToMan = dist; s.etaSeconds = eta; s.remaining = rem; s.flags = flags;
  strncpy(s.street, street, HUD_STREET_MAX - 1);
  s.street[HUD_STREET_MAX - 1] = '\0';   // a 20-char name fills it: strncpy leaves no NUL
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

/** A short file-name-safe label for corpus()[i]. */
static std::string corpusName(size_t i) {
  static const char* kMan[MAN_COUNT] = {
    "none", "left", "right", "slight_left", "slight_right", "sharp_left",
    "sharp_right", "uturn", "straight", "merge_left", "merge_right",
    "fork_left", "fork_right", "roundabout_exit3", "depart", "arrive",
    "ramp_left", "ramp_right", "keep_left", "keep_right"
  };
  static const char* kRest[] = {
    "over_limit_merge_6km", "over_limit_low_conf_ramp", "arrived",
    "no_fix", "derestricted", "absurd_values", "roundabout_exit7",
    "lanes_4", "lanes_8", "lanes_3_no_pick"
  };
  if (i < (size_t)MAN_COUNT) return std::string("man_") + kMan[i];
  i -= MAN_COUNT;
  if (i < sizeof kRest / sizeof kRest[0]) return kRest[i];
  return "state" + std::to_string(i + MAN_COUNT);
}

#endif  // HUD_TEST_CORPUS_H
