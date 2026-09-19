// Feeds stdin through the real Arduino parser and reports what it saw.
// Used to prove the Python simulator and the sketch agree byte for byte:
//   python3 ../../tools/hud_sim.py --stdout --frames 400 | ./parse_stdin
#include "../NavHud/hud_protocol.h"
#include <cstdio>
#include <cstring>

int main() {
  HudParser p;
  HudState st;
  hudStateInit(st);

  long frames = 0, pings = 0, bad = 0;
  long overLimit = 0, lowConf = 0, roundabouts = 0, arrived = 0;
  int  maneuversSeen[MAN_COUNT] = {0};
  long maxDist = 0;
  int  minSpeed = 9999, maxSpeed = -9999;

  int c;
  while ((c = getchar()) != EOF) {
    HudState tmp;
    HudParseResult r = p.feed((char)c, tmp);
    if (r == HUD_FRAME) {
      frames++;
      st = tmp;
      if (st.maneuver < MAN_COUNT) maneuversSeen[st.maneuver]++;
      if (st.flags & FLAG_OVER_LIMIT) overLimit++;
      if (st.flags & FLAG_LOW_CONF)   lowConf++;
      if (st.flags & FLAG_ARRIVED)    arrived++;
      if (st.maneuver == MAN_ROUNDABOUT) roundabouts++;
      if (st.distToMan > maxDist) maxDist = st.distToMan;
      if (st.speed < minSpeed) minSpeed = st.speed;
      if (st.speed > maxSpeed) maxSpeed = st.speed;
    } else if (r == HUD_PING) pings++;
    else if (r == HUD_BAD)    bad++;
  }

  printf("frames parsed : %ld\n", frames);
  printf("pings         : %ld\n", pings);
  printf("rejected      : %ld\n", bad);
  printf("speed range   : %d..%d km/h\n", minSpeed, maxSpeed);
  printf("max dist      : %ld m\n", maxDist);
  printf("over limit    : %ld frames\n", overLimit);
  printf("low confidence: %ld frames\n", lowConf);
  printf("roundabout    : %ld frames\n", roundabouts);
  printf("arrived       : %ld frames\n", arrived);
  printf("maneuvers hit :");
  for (int i = 0; i < MAN_COUNT; i++) if (maneuversSeen[i]) printf(" %d(x%d)", i, maneuversSeen[i]);
  printf("\nlast street   : \"%s\"\n", st.street);

  // The simulator must never emit a frame the sketch cannot read.
  if (bad != 0)     { printf("\nFAIL: %ld malformed frames\n", bad); return 1; }
  if (frames == 0)  { printf("\nFAIL: nothing parsed\n"); return 1; }
  printf("\nOK: every byte the simulator produced round-tripped\n");
  return 0;
}
