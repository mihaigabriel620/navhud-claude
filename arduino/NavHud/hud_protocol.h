// NavHUD serial protocol parser.
// Deliberately free of Arduino headers so it compiles on a PC and can be
// unit-tested with g++ (see test/test_protocol.cpp). No dynamic allocation,
// no String class, ~120 bytes of state -- happy on an ATmega328P.

#ifndef HUD_PROTOCOL_H
#define HUD_PROTOCOL_H

#include <stdint.h>
#include <string.h>
#include <stdlib.h>

// For GeomCorners. Also free of Arduino headers, so this still compiles on a
// PC; one definition of the corner order beats two that can drift apart.
#include "hud_geom.h"

// The panel. 4.0" ST7796S, 480x320, landscape.
//
// Here rather than in the theme because the host test stub needs it too: its
// off-panel check compared against a hard-coded 320x240 and would have passed
// every layout on the new screen without looking at it.
#define HUD_SCR_W 480
#define HUD_SCR_H 320

#define HUD_STREET_MAX 21   // 20 chars + NUL
#define HUD_LINE_MAX   96
#define HUD_MAX_LANES  8

// ---- maneuver codes (keep in sync with PROTOCOL.md and Maneuver.kt) --------
enum : uint8_t {
  MAN_NONE = 0, MAN_LEFT, MAN_RIGHT, MAN_SLIGHT_LEFT, MAN_SLIGHT_RIGHT,
  MAN_SHARP_LEFT, MAN_SHARP_RIGHT, MAN_UTURN, MAN_STRAIGHT,
  MAN_MERGE_LEFT, MAN_MERGE_RIGHT, MAN_FORK_LEFT, MAN_FORK_RIGHT,
  MAN_ROUNDABOUT, MAN_DEPART, MAN_ARRIVE, MAN_RAMP_LEFT, MAN_RAMP_RIGHT,
  MAN_KEEP_LEFT, MAN_KEEP_RIGHT, MAN_COUNT
};

// ---- flag bits -------------------------------------------------------------
enum : uint8_t {
  FLAG_OVER_LIMIT = 1 << 0,
  FLAG_OFF_ROUTE  = 1 << 1,
  FLAG_GPS_OK     = 1 << 2,
  FLAG_ARRIVED    = 1 << 3,
  FLAG_LOW_CONF   = 1 << 4,
  FLAG_NIGHT      = 1 << 5,
  // Set only by a tracker that is actually following a route. The free-drive
  // tracker cannot set it, so "there is an itinerary" stops being something
  // the HUD infers from "the maneuver is not NONE" and becomes something the
  // phone states outright.
  //
  // The inference was wrong in a way that showed on the glass: open the app
  // with no destination and the HUD had to guess, and one theme guessed
  // "straight on" and drew an arrow at a parked car. A phone too old to send
  // this bit leaves it clear, so old app plus new firmware means no arrow --
  // which is the safe direction for this particular mistake to fail in.
  FLAG_ROUTE      = 1 << 6
};

// Camera alert kinds, matching the app's CameraPolicy/Kind ordering.
enum : uint8_t {
  CAM_NONE = 0, CAM_FIXED, CAM_AVERAGE, CAM_TRAFFIC_LIGHT, CAM_ZONE
};

// Lane direction bits, matching Lanes.kt.
enum : uint8_t {
  LANE_UTURN = 1 << 0, LANE_SHARP_LEFT = 1 << 1, LANE_LEFT = 1 << 2,
  LANE_SLIGHT_LEFT = 1 << 3, LANE_STRAIGHT = 1 << 4, LANE_SLIGHT_RIGHT = 1 << 5,
  LANE_RIGHT = 1 << 6, LANE_SHARP_RIGHT = 1 << 7
};

/**
 * "The phone has not told us which way the exit actually points."
 *
 * Not 0, because 0 is a real bearing -- straight on, the single most common
 * roundabout exit there is.
 */
#define HUD_RB_ANGLE_NONE 32767

struct HudState {
  int16_t speed;        // km/h, -1 = no fix
  int16_t limit;        // km/h, 0 = unknown, -1 = unlimited
  uint8_t maneuver;
  uint8_t rbExit;
  int32_t distToMan;    // metres
  int32_t etaSeconds;
  int32_t remaining;    // metres
  uint8_t flags;
  char    street[HUD_STREET_MAX];

  // ---- protocol v2: sent as their own frames, kept in the same state ------
  uint8_t camKind;        // CAM_NONE when there is nothing to warn about
  int32_t camDistance;    // metres; 0 in zone mode, which has no position
  int16_t camLimit;       // enforced limit, 0 when not tagged

  /**
   * Where the exit REALLY is, in degrees from the road you came in on.
   * 0 is straight ahead, positive turns right. HUD_RB_ANGLE_NONE when unknown.
   *
   * The firmware used to derive this from the exit number alone, through a
   * seven-entry table. That is a guess, and a confident one: on a three-exit
   * roundabout "exit 2" is almost always dead ahead, and the table said 30
   * degrees. The arrow was always plausible and rarely right.
   *
   * rbAngleExit says which exit the angle belongs to, and the drawing code
   * refuses to use it for any other -- so an angle left over from the previous
   * roundabout can never be aimed at this one.
   */
  int16_t rbAngle;
  uint8_t rbAngleExit;

  uint8_t laneCount;
  uint8_t laneActive;     // bit i set when lane i is usable
  uint8_t lanes[HUD_MAX_LANES];         // every movement the lane allows
  uint8_t laneChosen[HUD_MAX_LANES];    // the one to follow, 0 when unknown
};

inline void hudStateInit(HudState& s) {
  s.speed = -1; s.limit = 0; s.maneuver = MAN_NONE; s.rbExit = 0;
  s.distToMan = 0; s.etaSeconds = 0; s.remaining = 0; s.flags = 0;
  s.street[0] = '\0';
  s.camKind = CAM_NONE; s.camDistance = 0; s.camLimit = 0;
  s.rbAngle = HUD_RB_ANGLE_NONE; s.rbAngleExit = 0;
  s.laneCount = 0; s.laneActive = 0;
  for (uint8_t i = 0; i < HUD_MAX_LANES; i++) { s.lanes[i] = 0; s.laneChosen[i] = 0; }
}

enum HudParseResult : uint8_t {
  HUD_NOTHING = 0,   // still accumulating
  HUD_FRAME,         // a valid $HUD frame landed in `out`
  HUD_PING,          // heartbeat
  HUD_CAM,           // $CAM: camera fields of `out` updated
  HUD_LANE,          // $LANE: lane fields of `out` updated
  HUD_RAB,           // $RAB: the roundabout exit bearing updated
  HUD_GEOM,          // $GEOM: parser.geom holds new screen geometry
  HUD_GEOM_SAVE,     // $GEOMSAVE: write the current geometry to flash
  HUD_GEOM_QUERY,    // $GEOM?: send the current geometry back up the cable
  HUD_MAG_CAL_ON,    // $MAGCAL,1: start learning the compass hard-iron offset
  HUD_MAG_CAL_OFF,   // $MAGCAL,0: stop, keep the result, write it to flash
  HUD_GEOM_TEST_ON,  // $GEOMTEST,1: show the alignment pattern
  HUD_GEOM_TEST_OFF, // $GEOMTEST,0: back to the drive display
  HUD_BAD            // checksum or syntax error (frame discarded)
};

/** Screen geometry as it arrives on the wire. */
struct HudGeomMsg {
  uint8_t     mirrorX = 0;
  uint8_t     mirrorY = 0;
  GeomCorners corners = {{0, 0, 0, 0}, {0, 0, 0, 0}};
};

// Streaming, byte-at-a-time parser. Feed it everything the UART gives you.
class HudParser {
 public:
  HudParser() { reset(); }

  void reset() { len_ = 0; active_ = false; }

  HudParseResult feed(char c, HudState& out) {
    if (c == '$') {              // a '$' always restarts a frame
      len_ = 0; active_ = true; return HUD_NOTHING;
    }
    if (!active_) return HUD_NOTHING;

    if (c == '\r') return HUD_NOTHING;
    if (c == '\n') {
      active_ = false;
      buf_[len_] = '\0';
      return parse_(out);
    }
    if (len_ >= HUD_LINE_MAX - 1) { active_ = false; return HUD_BAD; }
    buf_[len_++] = c;
    return HUD_NOTHING;
  }

  // Exposed so the encoder side and the tests can share one implementation.
  static uint8_t checksum(const char* body, size_t n) {
    uint8_t cs = 0;
    for (size_t i = 0; i < n; i++) cs ^= (uint8_t)body[i];
    return cs;
  }

 private:
  static int hexVal_(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
  }

  HudParseResult parse_(HudState& out) {
    // Split off "*CS"
    char* star = strrchr(buf_, '*');
    if (!star || (size_t)(star - buf_ + 3) != len_) return HUD_BAD;
    int hi = hexVal_(star[1]), lo = hexVal_(star[2]);
    if (hi < 0 || lo < 0) return HUD_BAD;
    uint8_t want = (uint8_t)((hi << 4) | lo);
    *star = '\0';
    if (checksum(buf_, (size_t)(star - buf_)) != want) return HUD_BAD;

    if (strncmp(buf_, "PING", 4) == 0 && buf_[4] == '\0') return HUD_PING;
    if (strncmp(buf_, "CAM,", 4) == 0)  return parseCam_(out);
    if (strncmp(buf_, "LANE,", 5) == 0) return parseLane_(out);
    if (strncmp(buf_, "RAB,", 4) == 0)  return parseRab_(out);
    if (strcmp(buf_, "GEOMTEST,1") == 0) return HUD_GEOM_TEST_ON;
    if (strcmp(buf_, "GEOMTEST,0") == 0) return HUD_GEOM_TEST_OFF;
    if (strcmp(buf_, "GEOMSAVE") == 0)  return HUD_GEOM_SAVE;
    if (strcmp(buf_, "GEOM?") == 0)     return HUD_GEOM_QUERY;
    if (strcmp(buf_, "MAGCAL,1") == 0)  return HUD_MAG_CAL_ON;
    if (strcmp(buf_, "MAGCAL,0") == 0)  return HUD_MAG_CAL_OFF;
    if (strncmp(buf_, "GEOM,", 5) == 0) return parseGeom_();
    if (strncmp(buf_, "HUD,", 4) != 0)  return HUD_BAD;

    // Nine comma-separated fields after the "HUD" tag.
    char* f[10];
    int n = 0;
    char* p = buf_;
    f[n++] = p;                        // f[0] == "HUD"
    while (*p && n < 10) {
      if (*p == ',') { *p = '\0'; f[n++] = p + 1; }
      p++;
    }
    if (n != 10) return HUD_BAD;

    HudState t = out;          // keep the camera and lane state
    t.speed     = (int16_t)atol(f[1]);
    t.limit     = (int16_t)atol(f[2]);
    long man    = atol(f[3]);
    t.maneuver  = (man >= 0 && man < (long)MAN_COUNT) ? (uint8_t)man : (uint8_t)MAN_NONE;
    long rbx    = atol(f[4]);
    t.rbExit    = (rbx >= 0 && rbx <= 12) ? (uint8_t)rbx : 0;
    t.distToMan = atol(f[5]);
    t.etaSeconds= atol(f[6]);
    t.remaining = atol(f[7]);
    t.flags     = (uint8_t)atol(f[8]);
    strncpy(t.street, f[9], HUD_STREET_MAX - 1);
    t.street[HUD_STREET_MAX - 1] = '\0';

    out = t;
    return HUD_FRAME;
  }

  // Splits buf_ in place. Returns how many fields were found, capped at max.
  int split_(char** f, int max) {
    int n = 0;
    char* p = buf_;
    f[n++] = p;
    while (*p && n < max) {
      if (*p == ',') { *p = '\0'; f[n++] = p + 1; }
      p++;
    }
    return n;
  }

  /**
   * $RAB,<exit>,<bearing>  -- which way the roundabout exit actually points.
   *
   * Its own frame rather than two more fields on $HUD, for the same reason
   * $CAM and $LANE are: the $HUD frame ends with the street name, and the
   * street is deliberately allowed to contain commas, so nothing can ever be
   * appended after it. Adding a field before it would shift every later
   * position and break an older phone. A separate frame costs nothing and
   * cannot break anything.
   *
   * The exit number is carried so the angle can be tied to the manoeuvre it
   * describes. Frames arrive independently; without this, an angle from the
   * last roundabout could be aimed at the next one with total confidence.
   */
  HudParseResult parseRab_(HudState& out) {
    char* f[4];
    if (split_(f, 4) != 3) return HUD_BAD;
    const long ex  = atol(f[1]);
    const long deg = atol(f[2]);
    if (ex < 1 || ex > 12) return HUD_BAD;
    // +-180 is the whole circle. Anything outside it is a corrupt digit, and a
    // corrupt digit must not become an arrow pointing somewhere confident.
    if (deg < -180 || deg > 180) return HUD_BAD;
    out.rbAngleExit = (uint8_t)ex;
    out.rbAngle     = (int16_t)deg;
    return HUD_RAB;
  }

  // $CAM,<kind>,<distance>,<limit>
  HudParseResult parseCam_(HudState& out) {
    char* f[5];
    if (split_(f, 5) != 4) return HUD_BAD;
    long kind = atol(f[1]);
    // An unrecognised kind used to be clamped to CAM_NONE, which *clears* an
    // active warning rather than ignoring a frame we do not understand. A
    // corrupted digit therefore silently took a live camera bar off the glass.
    // Treat anything unknown but non-zero as a plain fixed camera.
    if (kind <= 0)             out.camKind = CAM_NONE;
    else if (kind <= CAM_ZONE) out.camKind = (uint8_t)kind;
    else                       out.camKind = CAM_FIXED;
    out.camDistance = atol(f[2]);
    out.camLimit = (int16_t)atol(f[3]);
    return HUD_CAM;
  }

  // $LANE,<count>,<activeMask>,<d0>,...,<dN-1>[,<c0>,...,<cN-1>]
  //
  // The chosen-direction bytes are a v2.1 addition and are appended rather
  // than interleaved, so this parser also accepts a frame from an older phone
  // that stops after the direction list.
  HudParseResult parseLane_(HudState& out) {
    char* f[3 + 2 * HUD_MAX_LANES];
    int n = split_(f, 3 + 2 * HUD_MAX_LANES);
    if (n < 3) return HUD_BAD;
    long count = atol(f[1]);
    if (count < 0) count = 0;
    if (count > HUD_MAX_LANES) count = HUD_MAX_LANES;
    // The frame must actually carry as many lanes as it claims.
    if (n < 3 + (int)count) return HUD_BAD;
    const bool haveChosen = (n >= 3 + 2 * (int)count);
    out.laneCount = (uint8_t)count;
    out.laneActive = (uint8_t)atol(f[2]);
    for (int i = 0; i < HUD_MAX_LANES; i++) {
      out.lanes[i] = (i < count) ? (uint8_t)atol(f[3 + i]) : 0;
      out.laneChosen[i] =
        (haveChosen && i < count) ? (uint8_t)atol(f[3 + count + i]) : 0;
      // A chosen movement the lane does not allow is a bad frame, not a
      // reason to draw an arrow nobody may follow.
      if (out.laneChosen[i] & ~out.lanes[i]) out.laneChosen[i] = 0;
    }
    return HUD_LANE;
  }

  // $GEOM,<mirrorX>,<mirrorY>,<dx0>,<dy0>,<dx1>,<dy1>,<dx2>,<dy2>,<dx3>,<dy3>
  //
  // Corners run top-left, top-right, bottom-right, bottom-left, in the
  // driver's view -- see the note in hud_geom.h about why that is the same
  // thing as the layout coordinates even with the mirror on.
  //
  // Applied live, and NOT written to flash: the phone sends one of these on
  // every movement of a slider so the driver can see what they are doing.
  // Committing here would erase a 4 KB flash sector per frame and use up the
  // chip's endurance in one adjustment session. $GEOMSAVE is the write.
  HudParseResult parseGeom_() {
    char* f[12];
    if (split_(f, 12) != 11) return HUD_BAD;
    HudGeomMsg m;
    m.mirrorX = atol(f[1]) ? 1 : 0;
    m.mirrorY = atol(f[2]) ? 1 : 0;
    for (int i = 0; i < 4; i++) {
      m.corners.dx[i] = (int16_t)clampPull_(atol(f[3 + i * 2]), HUD_SCR_W);
      m.corners.dy[i] = (int16_t)clampPull_(atol(f[4 + i * 2]), HUD_SCR_H);
    }
    geom = m;
    return HUD_GEOM;
  }

  // Clamp on the way in, before the value is ever stored in an int16_t.
  // Geom::rebuild() clamps as well, but that one runs on a number that has
  // already been through a narrowing cast: atol("99999") would arrive there as
  // -31073 and pass the check by wrapping.
  static long clampPull_(long v, long span) {
    const long lim = (long)(span * GEOM_MAX_PULL) + 1;
    if (v >  lim) return  lim;
    if (v < -lim) return -lim;
    return v;
  }

  char   buf_[HUD_LINE_MAX];
  size_t len_;
  bool   active_;

 public:
  /** Filled by the most recent $GEOM. Only meaningful after HUD_GEOM. */
  HudGeomMsg geom;
};

#endif  // HUD_PROTOCOL_H
