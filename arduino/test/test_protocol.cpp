// Host-side unit test for the NavHUD parser.
//   g++ -std=c++11 -Wall -Wextra -I../NavHud test_protocol.cpp -o t && ./t
#include "../NavHud/hud_protocol.h"
#include <cstdio>
#include <cstring>
#include <string>

static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { printf("  FAIL: %s\n", msg); failures++; } } while (0)

// Encode exactly the way the Android app does, so the two stay honest.
static std::string frame(const std::string& body) {
  char cs[4];
  snprintf(cs, sizeof cs, "%02X", HudParser::checksum(body.c_str(), body.size()));
  return "$" + body + "*" + cs + "\r\n";
}

static HudParseResult feedAll(HudParser& p, const std::string& s, HudState& out) {
  HudParseResult last = HUD_NOTHING;
  for (char c : s) {
    HudParseResult r = p.feed(c, out);
    if (r != HUD_NOTHING) last = r;
  }
  return last;
}

int main() {
  HudParser p;
  HudState st;
  hudStateInit(st);

  printf("1. well-formed frame\n");
  {
    std::string f = frame("HUD,72,50,2,0,180,845,7300,5,RUE DE LA LOI");
    CHECK(feedAll(p, f, st) == HUD_FRAME, "should parse");
    CHECK(st.speed == 72, "speed");
    CHECK(st.limit == 50, "limit");
    CHECK(st.maneuver == MAN_RIGHT, "maneuver");
    CHECK(st.rbExit == 0, "rbExit");
    CHECK(st.distToMan == 180, "distToMan");
    CHECK(st.etaSeconds == 845, "eta");
    CHECK(st.remaining == 7300, "remaining");
    CHECK(st.flags == (FLAG_OVER_LIMIT | FLAG_GPS_OK), "flags");
    CHECK(strcmp(st.street, "RUE DE LA LOI") == 0, "street");
  }

  printf("2. negative limit (derestricted) and no-fix speed\n");
  {
    std::string f = frame("HUD,-1,-1,8,0,4200,1810,52000,4,A4");
    CHECK(feedAll(p, f, st) == HUD_FRAME, "should parse");
    CHECK(st.speed == -1, "speed -1");
    CHECK(st.limit == -1, "limit -1");
    CHECK(st.maneuver == MAN_STRAIGHT, "straight");
  }

  printf("3. roundabout with exit\n");
  {
    std::string f = frame("HUD,31,30,13,3,90,1204,9100,4,N9");
    CHECK(feedAll(p, f, st) == HUD_FRAME, "should parse");
    CHECK(st.maneuver == MAN_ROUNDABOUT, "roundabout");
    CHECK(st.rbExit == 3, "exit 3");
  }

  printf("4. empty street field\n");
  {
    std::string f = frame("HUD,0,0,0,0,0,0,0,0,");
    CHECK(feedAll(p, f, st) == HUD_FRAME, "should parse");
    CHECK(st.street[0] == '\0', "street empty");
  }

  printf("5. ping\n");
  {
    CHECK(feedAll(p, frame("PING"), st) == HUD_PING, "ping");
  }

  printf("6. bad checksum is rejected\n");
  {
    std::string f = "$HUD,72,50,2,0,180,845,7300,5,RUE DE LA LOI*00\r\n";
    CHECK(feedAll(p, f, st) == HUD_BAD, "bad checksum");
    CHECK(st.speed == 0 || st.speed == 72, "state untouched-ish");
  }

  printf("7. truncated frame then a good one -- resync on '$'\n");
  {
    HudState s2; hudStateInit(s2);
    HudParseResult r = feedAll(p, "$HUD,99,1", s2);
    CHECK(r == HUD_NOTHING, "partial yields nothing");
    r = feedAll(p, frame("HUD,55,70,1,0,300,600,4000,4,MAIN ST"), s2);
    CHECK(r == HUD_FRAME, "resyncs and parses");
    CHECK(s2.speed == 55, "speed after resync");
    CHECK(strcmp(s2.street, "MAIN ST") == 0, "street after resync");
  }

  printf("8. wrong field count rejected\n");
  {
    CHECK(feedAll(p, frame("HUD,1,2,3"), st) == HUD_BAD, "too few fields");
  }

  printf("9. out-of-range maneuver clamped, long street truncated\n");
  {
    std::string f = frame("HUD,10,50,99,0,10,10,10,4,ABCDEFGHIJKLMNOPQRSTUVWXYZ");
    CHECK(feedAll(p, f, st) == HUD_FRAME, "should parse");
    CHECK(st.maneuver == MAN_NONE, "clamped to none");
    CHECK(strlen(st.street) == HUD_STREET_MAX - 1, "street truncated to 20");
    CHECK(strncmp(st.street, "ABCDEFGHIJKLMNOPQRST", 20) == 0, "truncation prefix");
  }

  printf("10. overlong garbage does not overflow\n");
  {
    std::string junk = "$";
    junk.append(400, 'X');
    junk += "\r\n";
    HudParseResult r = feedAll(p, junk, st);
    CHECK(r == HUD_BAD || r == HUD_NOTHING, "no crash, no frame");
    CHECK(feedAll(p, frame("HUD,7,7,7,0,7,7,7,4,OK"), st) == HUD_FRAME,
          "still works afterwards");
    CHECK(st.speed == 7, "recovered");
  }

  printf("10b. protocol v2: $CAM and $LANE\n");
  {
    HudState v; hudStateInit(v);
    CHECK(feedAll(p, frame("CAM,1,300,50"), v) == HUD_CAM, "cam frame");
    CHECK(v.camKind == CAM_FIXED, "cam kind");
    CHECK(v.camDistance == 300, "cam distance");
    CHECK(v.camLimit == 50, "cam limit");

    CHECK(feedAll(p, frame("CAM,4,0,0"), v) == HUD_CAM, "zone frame");
    CHECK(v.camKind == CAM_ZONE, "zone kind");

    // A protocol v2 frame, with no chosen-direction tail. Still accepted, so
    // an older phone paired with newer firmware keeps working.
    CHECK(feedAll(p, frame("LANE,4,12,16,16,80,64"), v) == HUD_LANE, "lane frame");
    CHECK(v.laneCount == 4, "lane count");
    CHECK(v.laneActive == 12, "active mask");
    CHECK(v.lanes[0] == LANE_STRAIGHT, "lane 0 straight");
    CHECK(v.lanes[2] == (LANE_STRAIGHT | LANE_RIGHT), "lane 2 straight+right");
    CHECK(v.lanes[3] == LANE_RIGHT, "lane 3 right");
    CHECK(v.laneChosen[2] == 0, "no chosen movement without the tail");

    // This is the exact string LaneGuidance.encodeBody() produces in the app.
    CHECK(feedAll(p, frame("LANE,4,12,16,16,80,64,0,0,64,64"), v) == HUD_LANE, "v2.1 lane frame");
    CHECK(v.laneCount == 4, "lane count with tail");
    CHECK(v.laneChosen[0] == 0, "lane 0 is not ours");
    CHECK(v.laneChosen[2] == LANE_RIGHT, "shared lane exits");
    CHECK(v.laneChosen[3] == LANE_RIGHT, "exit lane exits");


    // A HUD frame must not wipe the camera and lane state that came separately.
    CHECK(feedAll(p, frame("HUD,50,50,2,0,100,60,500,4,X"), v) == HUD_FRAME, "hud after");
    CHECK(v.camKind == CAM_ZONE, "camera state survived a HUD frame");
    CHECK(v.laneCount == 4, "lane state survived a HUD frame");
    CHECK(v.laneChosen[3] == LANE_RIGHT, "chosen movements survived a HUD frame");

    // A chosen movement the lane does not permit is dropped rather than drawn:
    // an arrow nobody may follow is worse than no arrow.
    CHECK(feedAll(p, frame("LANE,2,3,16,64,64,64"), v) == HUD_LANE, "impossible pick");
    CHECK(v.laneChosen[0] == 0, "impossible pick dropped");
    CHECK(v.laneChosen[1] == LANE_RIGHT, "legal pick kept");

    // Eight lanes with a full tail is the longest lane frame the app can send.
    CHECK(feedAll(p, frame("LANE,8,128,16,16,16,16,16,16,16,64,"
                           "0,0,0,0,0,0,0,64"), v) == HUD_LANE, "widest lane frame");
    CHECK(v.laneCount == 8, "eight lanes");
    CHECK(v.laneChosen[7] == LANE_RIGHT, "eighth lane exits");

    CHECK(feedAll(p, frame("LANE,0,0"), v) == HUD_LANE, "clearing lanes");
    CHECK(v.laneCount == 0, "lanes cleared");

    // A lane frame that claims more lanes than it carries must be rejected.
    CHECK(feedAll(p, frame("LANE,4,3,16"), v) == HUD_BAD, "short lane frame rejected");
    // An unrecognised kind must not *clear* the warning. Clamping it to
    // CAM_NONE meant a single corrupted digit silently took a live camera bar
    // off the glass; a camera we cannot classify is still a camera.
    CHECK(feedAll(p, frame("CAM,9,1,2"), v) == HUD_CAM, "unknown cam kind accepted");
    CHECK(v.camKind == CAM_FIXED, "unknown cam kind falls back to a plain camera");
    CHECK(feedAll(p, frame("CAM,0,0,0"), v) == HUD_CAM, "explicit clear");
    CHECK(v.camKind == CAM_NONE, "kind 0 still clears");
    // The four documented kinds map one to one.
    CHECK(feedAll(p, frame("CAM,1,300,70"), v) == HUD_CAM, "fixed");
    CHECK(v.camKind == CAM_FIXED && v.camDistance == 300 && v.camLimit == 70, "fixed fields");
    CHECK(feedAll(p, frame("CAM,4,0,0"), v) == HUD_CAM, "zone");
    CHECK(v.camKind == CAM_ZONE, "zone kind");
  }

  printf("10c. protocol v3: $GEOM, $GEOMSAVE, $GEOM?\n");
  {
    HudParser g;
    HudState  gs; hudStateInit(gs);

    CHECK(feedAll(g, frame("GEOM,1,0,4,-2,-4,-2,-6,3,6,3"), gs) == HUD_GEOM,
          "a set frame parses");
    CHECK(g.geom.mirrorX == 1 && g.geom.mirrorY == 0, "mirror flags");
    CHECK(g.geom.corners.dx[0] == 4 && g.geom.corners.dy[0] == -2, "top-left");
    CHECK(g.geom.corners.dx[1] == -4 && g.geom.corners.dy[1] == -2, "top-right");
    CHECK(g.geom.corners.dx[2] == -6 && g.geom.corners.dy[2] == 3, "bottom-right");
    CHECK(g.geom.corners.dx[3] == 6 && g.geom.corners.dy[3] == 3, "bottom-left");

    // ...and it drives a Geom that a theme could actually draw through.
    Geom real;
    real.mirrorX = g.geom.mirrorX; real.mirrorY = g.geom.mirrorY;
    real.corners = g.geom.corners;
    CHECK(real.rebuild(HUD_SCR_W, HUD_SCR_H), "the quad it describes is usable");
    CHECK(!real.identity, "and it is not the identity");

    CHECK(feedAll(g, frame("GEOMSAVE"), gs) == HUD_GEOM_SAVE, "save is its own message");
    CHECK(feedAll(g, frame("GEOM?"), gs) == HUD_GEOM_QUERY, "and so is the query");

    // A save must not be mistaken for a set: the two differ by one character
    // at the point where the field list would start.
    CHECK(g.geom.corners.dx[0] == 4, "$GEOMSAVE left the corners alone");

    CHECK(feedAll(g, frame("GEOM,1,0,4,-2,-4,-2,-6,3"), gs) == HUD_BAD,
          "a short frame is rejected, not half-applied");
    CHECK(g.geom.corners.dx[0] == 4, "and left the last good one standing");

    // A number too big for int16_t must be clamped before the cast, not after:
    // (int16_t)99999 is -31073, which would have sailed through a check that
    // only ran inside rebuild().
    CHECK(feedAll(g, frame("GEOM,0,0,99999,0,0,0,0,0,0,0"), gs) == HUD_GEOM,
          "an absurd pull still parses");
    CHECK(g.geom.corners.dx[0] > 0 && g.geom.corners.dx[0] <= (int)(HUD_SCR_W * GEOM_MAX_PULL) + 1,
          "clamped positive, not wrapped negative");
  }

  printf("11. checksums quoted in PROTOCOL.md\n");
  {
    // Asserted, not merely printed: a checksum in the documentation that does
    // not match the code is worse than no documentation, because someone will
    // hand-type it into a terminal to test their wiring and conclude the board
    // is broken.
    struct { const char* body; uint8_t cs; } v[] = {
      { "HUD,72,50,2,0,180,845,7300,5,RUE DE LA LOI", 0x62 },
      { "HUD,118,-1,8,0,4200,1810,52000,4,A4",        0x21 },
      { "HUD,31,30,13,3,90,1204,9100,4,N9",           0x00 },
      { "HUD,-1,0,0,0,0,0,0,0,",                      0x59 },
      { "PING",                                       0x10 },
      { "HELLO,NAVHUD,2",                             0x70 },
      { "HELLO,NAVHUD,2,IMU",                         0x0D },
      { "IMU,182.4,1.2,-0.4,15.30",                   0x73 },
      { "HELLO,NAVHUD,3",                             0x71 },
      { "GEOM,1,0,0,0,0,0,0,0,0,0",                   0x01 },
      { "GEOMSAVE",                                   0x01 },
      { "GEOM?",                                      0x3F },
    };
    for (auto& e : v) {
      const uint8_t got = HudParser::checksum(e.body, strlen(e.body));
      char msg[128];
      snprintf(msg, sizeof msg, "$%s*%02X matches PROTOCOL.md", e.body, got);
      CHECK(got == e.cs, msg);
    }
  }

  printf("12. $RAB: exit and bearing, stamped with the distance it arrived at\n");
  {
    HudParser q; HudState s; hudStateInit(s);
    s.distToMan = 240;
    CHECK(feedAll(q, frame("RAB,2,-95"), s) == HUD_RAB, "parses");
    CHECK(s.rbAngleExit == 2 && s.rbAngle == -95, "exit and bearing");
    CHECK(s.rbAngleDist == 240, "stamped with the distance to the manoeuvre");
    CHECK(feedAll(q, frame("RAB,0,10"), s) == HUD_BAD, "exit 0 refused");
    CHECK(feedAll(q, frame("RAB,2,181"), s) == HUD_BAD, "181 degrees refused");
    CHECK(s.rbAngle == -95, "and a refused frame changes nothing");
  }

  printf("13. $RBX: every exit's angle, the side of the road, and the pairing\n");
  {
    HudParser q; HudState s; hudStateInit(s);
    s.distToMan = 310;
    CHECK(feedAll(q, frame("RBX,3,0,95,30,-80"), s) == HUD_RBX, "parses");
    CHECK(s.rbxExit == 3 && s.rbxLeft == 0 && s.rbxCount == 3, "exit 3, right-hand traffic, 3 angles");
    CHECK(s.rbxAngles[0] == 95 && s.rbxAngles[1] == 30 && s.rbxAngles[2] == -80, "angles in exit order");
    CHECK(s.rbxDist == 310, "stamped with the distance to the manoeuvre");

    CHECK(feedAll(q, frame("RBX,2,1"), s) == HUD_RBX, "no angles: 'this roundabout, nothing known'");
    CHECK(s.rbxExit == 2 && s.rbxLeft == 1 && s.rbxCount == 0, "...clears the old ones");

    CHECK(feedAll(q, frame("RBX,12,0,170,150,120,90,60,30,0,-30,-60,-90,-120,-150"), s) == HUD_RBX,
          "twelve exits fit a line");
    CHECK(s.rbxCount == 12 && s.rbxAngles[11] == -150, "all twelve kept");

    hudStateInit(s);
    CHECK(feedAll(q, frame("RBX,3,0,95,30"), s) == HUD_BAD, "fewer angles than the exit number refused");
    CHECK(feedAll(q, frame("RBX,2,0,95,30,-80"), s) == HUD_BAD, "more angles than the exit number refused");
    CHECK(feedAll(q, frame("RBX,0,0"), s) == HUD_BAD, "exit 0 refused");
    CHECK(feedAll(q, frame("RBX,13,0"), s) == HUD_BAD, "exit 13 refused");
    CHECK(feedAll(q, frame("RBX,2,2,10,20"), s) == HUD_BAD, "side must be 0 or 1");
    CHECK(feedAll(q, frame("RBX,2,0,10,200"), s) == HUD_BAD, "an angle past 180 refused");
    CHECK(feedAll(q, frame("RBX,2"), s) == HUD_BAD, "side missing refused");
    CHECK(s.rbxExit == 0 && s.rbxCount == 0, "and a refused frame changes nothing");
  }

  printf(failures ? "\n%d CHECK(s) FAILED\n" : "\nall checks passed\n", failures);
  return failures ? 1 : 0;
}
