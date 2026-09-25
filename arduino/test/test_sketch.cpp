// Compiles the actual sketch on the host and runs its main loop against a
// scripted serial stream: link up, frames, link lost, link back.
#define HUD_HOST_TEST 1
#include "tft_stub.h"
#include "SPI.h"
SPIClass SPI;      // the MCP2515 simulator the sketch will talk to
// Ahead of the #ifdef below, because HUD_MAG is a setting in that file rather
// than something only the Makefile passes in -- the compass needs the Wire
// instance, and leaving it undefined is a link error rather than anything
// readable.
#include "../NavHud/hud_config.h"
#ifdef HUD_MAG
#include "Wire.h"
TwoWire Wire;
#endif

const char* g_zone = "sketch";
std::vector<Box> g_boxes;
int g_outOfBounds = 0;
int g_badGlyphs = 0;
bool TFT_eSPI::g_reportOob = true;

#include "../NavHud/NavHud.ino"

static int failures = 0;
#define CHECK(c, m) do { if (!(c)) { printf("  FAIL: %s\n", m); failures++; } } while (0)

static std::string wrap(const std::string& body) {
  char cs[8];
  snprintf(cs, sizeof cs, "%02X", HudParser::checksum(body.c_str(), body.size()));
  return "$" + body + "*" + cs + "\r\n";
}
static void pump(int iterations) { for (int i = 0; i < iterations; i++) loop(); }

int main() {
  printf("theme: %s\n\n",
#if defined(HUD_THEME_E60_CLASSIC)
         "e60"
#else
         "dash"
#endif
  );

  printf("1. setup() runs and announces itself\n");
  g_millis = 0;
  setup();
#if BACKLIGHT_PIN >= 0
  // Grabbed here rather than in group 17, because by then sixteen groups have
  // run and the panel is long past its power-on state.
  const uint8_t bootBacklight = backlightNow;
#endif

#if defined(HUD_CAN) && !defined(HUD_BENCH)
  // Same reasoning: the boot grace can only be observed while everDrew is
  // still false, which is true for exactly as long as nothing has been drawn.
  printf("\n1b. the car screen outranks the boot splash\n");
  {
    uint8_t ign[1] = { 0x45 };                 // engine running, bus talking
    SPI.deliver(false, CAR_ID_IGNITION, ign, 1);
    canPump(car, 100);                         // without running a loop pass
    CHECK(car.displayOn, "the simulated key reached the decoder");
    // This assertion is the reverse of what it was, deliberately, and the
    // reasoning is worth leaving here so nobody puts it back.
    //
    // The grace exists to avoid flashing "check the USB cable" at a cable that
    // is fine, in the second or two before the phone opens the port. That is an
    // argument for waiting when there is nothing else to show -- not for
    // sitting on a splash while the bus is right there reporting a speed. It
    // was the other way round to save one full repaint inside the connecting
    // window, and that trade only pays when the phone turns up within four
    // seconds, while costing four seconds of splash plus a wrong complaint
    // every time it does not turn up at all.
    CHECK(displayWanted(false, 1200) == SCREEN_CAR,
          "a talking bus beats the grace: there is something real to show");
    CHECK(displayWanted(false, DISPLAY_BOOT_GRACE_MS + 1) == SCREEN_CAR,
          "and it stays up afterwards");
    CHECK(displayWanted(true, 1200) == SCREEN_DRIVE,
          "a phone frame still beats everything");

    // 0x41 is key in position 1, engine off. displayOn is true there and
    // ignitionOn is not, and the car screen deliberately follows displayOn --
    // so the panel being lit by the key and the car band being on it are the
    // same condition, with no state where the key lights the glass and the
    // glass then says NO LINK at a car that is plainly talking to us.
    ign[0] = 0x41;
    SPI.deliver(false, CAR_ID_IGNITION, ign, 1);
    canPump(car, 200);
    CHECK(!car.ignitionOn && car.displayOn, "0x41 is display-on, ignition-off");
    CHECK(displayWanted(false, DISPLAY_BOOT_GRACE_MS + 1) == SCREEN_CAR,
          "key at position 1 still shows the car half, engine or no engine");

    ign[0] = 0x00;
    SPI.deliver(false, CAR_ID_IGNITION, ign, 1);
    canPump(car, 300);
    // Key out, no phone -- and STILL the car screen, on a build that has CAN
    // compiled in. This is the assertion that changed, and deliberately.
    //
    // It used to be SCREEN_NOLINK, i.e. "NO LINK -- check the USB cable", which
    // is a wrong answer said confidently: the cable is fine, the bus is what is
    // quiet, and the driver only wanted to see their speed. A tacho with no
    // needle is an honest picture of a silent bus. Why it is silent belongs on
    // the serial line and in 'status', not on a windscreen.
    CHECK(displayWanted(false, DISPLAY_BOOT_GRACE_MS + 1) == SCREEN_CAR,
          "a CAN build shows the car screen even with nothing on the bus");
    CHECK(displayWanted(false, 1200) == SCREEN_CAR,
          "and it does not wait out the boot grace to do it");
  }
  printf("\n");
#endif
  // Protocol 2: the board can now talk back up the cable ($IMU), so it says so.
  // The hello line grows a capability when the gyroscope is compiled in, so
  // check the checksum is right for whatever was actually sent rather than
  // pinning one build's string -- otherwise the optional build fails a test
  // that is really about the checksum routine.
  {
    const size_t at = Serial.out_.find("$HELLO,");
    CHECK(at != std::string::npos, "sends $HELLO");
    if (at != std::string::npos) {
      const size_t star = Serial.out_.find('*', at);
      CHECK(star != std::string::npos, "$HELLO carries a checksum");
      if (star != std::string::npos) {
        uint8_t ck = 0;
        for (size_t i = at + 1; i < star; i++) ck ^= (uint8_t)Serial.out_[i];
        char want[8]; snprintf(want, sizeof want, "*%02X", ck);
        CHECK(Serial.out_.compare(star, 3, want) == 0,
              "sends $HELLO with the right checksum");
      }
    }
  }
  CHECK(g_outOfBounds == 0, "splash stays on-panel");
  printf("    sent: %s", Serial.out_.c_str());

  // Deliberately under the limit: an over-limit frame makes the E60 roundel
  // blink, which is a repaint every 450 ms by design and would mask the thing
  // these two checks are actually about.
  printf("2. a frame arrives -> full repaint, then deltas\n");
  Serial.feed(wrap("HUD,72,90,2,0,180,845,7300,4,RUE DE LA LOI"));
  g_millis = 1000;
  g_boxes.clear();
  pump(1);
  size_t first = g_boxes.size();
  CHECK(first > 10, "first frame paints the whole screen");
  Serial.feed(wrap("HUD,73,90,2,0,175,840,7280,4,RUE DE LA LOI"));
  g_millis = 1250;
  g_boxes.clear();
  pump(1);
  size_t second = g_boxes.size();
  CHECK(second > 0 && second < first, "the next frame repaints less than a full screen");
  printf("    full: %zu primitives, delta: %zu\n", first, second);

  printf("3. an identical frame repaints nothing\n");
  Serial.feed(wrap("HUD,73,90,2,0,175,840,7280,4,RUE DE LA LOI"));
  g_millis = 1500;
  g_boxes.clear();
  pump(1);
  printf("    %zu primitives\n", g_boxes.size());
  CHECK(g_boxes.size() == 0, "no repaint for an unchanged frame");

#if !defined(HUD_THEME_MODERN)
  // Blinking is an E60-theme behaviour; the modern theme keeps the roundel
  // steady and signals over-limit with the speed colour instead.
  printf("3b. an over-limit frame is shown as over the limit\n");
  Serial.feed(wrap("HUD,95,50,2,0,170,835,7260,5,RUE DE LA LOI"));
  g_millis = 1750;
  g_boxes.clear();
  pump(1);                         // the frame itself: does the theme react?
  size_t overLimitRepaint = g_boxes.size();
  g_millis = 2100;                 // 2100 %% 900 = 300 -> phase on
  g_boxes.clear();
  pump(1);
  size_t blinkA = g_boxes.size();
  g_millis = 2400;                 // 2400 %% 900 = 600 -> phase off
  g_boxes.clear();
  pump(1);
  size_t blinkB = g_boxes.size();
  // Themes signal over-limit differently -- the E60 blinks the roundel, the
  // dash theme reddens the speed. What every theme must do is SOMETHING: a
  // theme that silently ignores FLAG_OVER_LIMIT is the bug worth catching.
  CHECK(blinkA + blinkB > 0 || overLimitRepaint > 0,
        "the theme must show over-limit somehow");
  printf("    phase on: %zu, phase off: %zu, on arrival: %zu primitives\n",
         blinkA, blinkB, overLimitRepaint);
#endif

  printf("4. link lost -> NO LINK, and no stale limit left on screen\n");
  g_millis += LINK_TIMEOUT_MS + 200;   // relative, so inserting tests above cannot break it
  g_boxes.clear();
  pump(1);
  CHECK(g_boxes.size() > 0, "the timeout repaints");
  CHECK(!linkUp, "link marked down");
  printf("    %zu primitives on the NO LINK screen\n", g_boxes.size());

  printf("5. link returns -> full repaint again\n");
  Serial.feed(wrap("HUD,90,70,1,0,300,600,4000,4,MAIN ST"));
  g_millis += 100;
  g_boxes.clear();
  pump(1);
  CHECK(linkUp, "link back up");
  CHECK(g_boxes.size() > 10, "full repaint after reconnect");
  CHECK(cur.speed == 90 && cur.limit == 70, "state updated");
  printf("    %zu primitives, speed=%d limit=%d\n", g_boxes.size(), cur.speed, cur.limit);

  printf("5b. a link that comes back with something other than $HUD shows nothing stale\n");
  {
    // Drop the link with a limit and a turn on the glass...
    Serial.feed(wrap("HUD,90,70,1,0,300,600,4000,68,MAIN ST"));
    g_millis += 100; pump(1);
    CHECK(cur.limit == 70 && cur.maneuver == MAN_LEFT, "a limit and a turn are up");
    g_millis += LINK_TIMEOUT_MS + 200; pump(1);
    CHECK(!linkUp, "link down");
    // ...and bring it back with a heartbeat, or the Keystone screen's $GEOM?.
    // The drive layout comes back at once, and until a $HUD frame says
    // otherwise there is no limit and no turn: they were the last trip's.
    Serial.feed(wrap("PING"));
    g_millis += 100; pump(1);
    CHECK(linkUp, "a heartbeat brings the drive layout back");
    CHECK(cur.limit == 0 && cur.maneuver == MAN_NONE && cur.street[0] == '\0',
          "with nothing left over from before the drop");
    Serial.feed(wrap("HUD,90,70,1,0,300,600,4000,68,MAIN ST"));
    g_millis += 100; pump(1);
    CHECK(cur.limit == 70, "and the next $HUD frame fills it in");
  }

  printf("6. a corrupt frame is ignored, the last good one stands\n");
  Serial.feed("$HUD,1,2,3,4,5,6,7,8,BAD*00\r\n");
  g_millis += 250;
  pump(1);
  CHECK(cur.speed == 90, "state untouched by the bad frame");
  printf("    speed still %d\n", cur.speed);

  printf("7. a whole simulated drive stays on-panel\n");
  g_outOfBounds = 0;
  for (int i = 0; i < 400; i++) {
    char body[128];
    int spd = 30 + (i % 100), lim = (i % 7) * 20;
    snprintf(body, sizeof body, "HUD,%d,%d,%d,%d,%d,%d,%d,%d,STREET %d",
             spd, lim, i % MAN_COUNT, i % 8, i * 13, 900 - i, 9000 - i * 20,
             FLAG_GPS_OK | ((spd > lim && lim > 0) ? FLAG_OVER_LIMIT : 0), i);
    Serial.feed(wrap(body));
    g_millis += 250;
    pump(1);
  }
  CHECK(g_outOfBounds == 0, "400 frames, nothing off-panel");
  printf("    400 frames, %d out-of-bounds\n", g_outOfBounds);
  // Every string drawn was in a font that carries its characters. With smooth
  // fonts this is not cosmetic: the font-number argument is ignored while one
  // is loaded, so an element pointed at the wrong face draws hollow boxes and
  // nothing else goes wrong. The unit faces carry twenty-one glyphs.
  CHECK(g_badGlyphs == 0, "400 frames, every glyph exists in its font");
  printf("    %d glyphs missing from their font\n", g_badGlyphs);

#ifdef HUD_BENCH
  // HUD_BENCH deliberately ignores flash, forces the mirror off and refuses to
  // save, so the geometry-persistence groups below would be asserting the
  // opposite of what this build is for. Check what bench mode promises instead.
  printf("8-11. bench mode: unmirrored, flash ignored, Save refused\n");
  {
    loadGeom();
    CHECK(!tft.geom.mirrorX && !tft.geom.mirrorY, "no mirror");
    CHECK(tft.geom.identity, "no keystone");
    const int before = EEPROM.sectorErases;
    Serial.out_.clear();
    saveGeom();
    CHECK(EEPROM.sectorErases == before, "Save writes nothing");
    CHECK(Serial.out_.find("GEOMERR,bench") != std::string::npos, "and says why");

    // The point of refusing: a real alignment in flash must survive a session
    // spent on the bench.
    //
    // Note bench mode returns from loadGeom() before EEPROM.begin(), so the
    // store is never even opened -- the test has to open it itself, which is
    // its own small proof that bench mode does not touch flash at all.
    EEPROM.begin(HUD_STORE_EEPROM);
    uint8_t b[HUD_STORE_BYTES];
    Geom real;
    real.mirrorX = true; real.corners.dx[0] = 40;
    hudStorePack(real, b);
    for (int i = 0; i < HUD_STORE_BYTES; i++) EEPROM.write(i, b[i]);
    EEPROM.commit();
    loadGeom();
    CHECK(!tft.geom.mirrorX, "bench still ignores it");
    Geom back;
    uint8_t r[HUD_STORE_BYTES];
    for (int i = 0; i < HUD_STORE_BYTES; i++) r[i] = EEPROM.read(i);
    CHECK(hudStoreUnpack(r, back) && back.mirrorX && back.corners.dx[0] == 40,
          "and the saved alignment is untouched in flash");
  }
#else
  printf("8. keystone: live preview costs no flash, and coalesces repaints\n");
  {
    Serial.out_.clear();
    const int before = EEPROM.sectorErases;

    // Test mode first -- which is what the app does when the keystone screen
    // opens, and what makes a drag affordable at all.
    Serial.feed(wrap("GEOMTEST,1"));
    g_millis += 200; pump(1);
    CHECK(geomTest, "the board is showing the alignment pattern");

    // Two hundred slider positions twenty milliseconds apart: one unhurried
    // adjustment, and four seconds of wall clock.
    int repaints = 0;
    for (int i = 0; i < 200; i++) {
      char body[96];
      snprintf(body, sizeof body, "GEOM,1,0,%d,%d,%d,%d,-4,-2,4,-2",
               i / 4, i / 8, -(i / 4), i / 8);
      Serial.feed(wrap(body));
      g_millis += 20;
      g_boxes.clear();
      pump(1);
      if (!g_boxes.empty()) repaints++;
    }
    CHECK(EEPROM.sectorErases == before, "dragging a slider never writes flash");
    CHECK(tft.geom.corners.dx[0] == 199 / 4, "the last position reached the panel");
    // 200 messages across 4000 ms, repainting no faster than every 120 ms.
    CHECK(repaints <= 40, "repaints are coalesced, not one per message");
    CHECK(repaints >= 20, "...but the preview is still live");
    printf("    200 previews -> %d repaints, %d flash writes\n",
           repaints, EEPROM.sectorErases - before);

    Serial.feed(wrap("GEOMSAVE"));
    g_millis += 20; pump(1);
    CHECK(EEPROM.sectorErases == before + 1, "Save writes once");
    CHECK(Serial.out_.find("GEOMOK,1") != std::string::npos, "and says so");

    // Saving the same thing twice must not cost a second erase.
    Serial.out_.clear();
    Serial.feed(wrap("GEOMSAVE"));
    g_millis += 20; pump(1);
    CHECK(EEPROM.sectorErases == before + 1, "an unchanged Save writes nothing");
    CHECK(Serial.out_.find("GEOMOK,0") != std::string::npos, "and reports 0");

    Serial.feed(wrap("GEOMTEST,0"));
    g_millis += 200; pump(1);
    CHECK(!geomTest, "and the drive display comes back");
  }

  printf("9. the saved geometry survives a power cycle\n");
  {
    const Geom want = tft.geom;
    EEPROM.powerCycle();
    tft.geom = Geom();               // as if the board had just come up
    loadGeom();
    CHECK(geomSame(tft.geom, want), "mirror and all four corners came back");
    rawTft.setRotation(geomRotation(tft.geom.mirrorX, tft.geom.mirrorY));
    CHECK(rawTft.rotation == geomRotation(want.mirrorX, want.mirrorY),
          "and the rotation follows the mirror");
    printf("    mirrorX=%d corners dx0=%d dy0=%d, rotation %d\n",
           tft.geom.mirrorX, tft.geom.corners.dx[0], tft.geom.corners.dy[0],
           rawTft.rotation);
  }

  printf("10. a blank or damaged block falls back to the defaults\n");
  {
    EEPROM.wipe();
    tft.geom = Geom();
    loadGeom();
    CHECK(tft.geom.identity, "blank flash -> no keystone");
    CHECK(tft.geom.mirrorX == (bool)HUD_DEFAULT_MIRROR_X, "and the built-in mirror");

    // Save something, then corrupt one byte of it.
    tft.geom.corners.dx[1] = -30;
    saveGeom();
    EEPROM.write(7, EEPROM.read(7) ^ 0x5A);
    EEPROM.commit();
    tft.geom = Geom();
    loadGeom();
    CHECK(tft.geom.corners.dx[1] == 0, "a bad checksum is not half-applied");
  }

  printf("11. $GEOM? hands back what is on the panel\n");
  {
    Serial.out_.clear();
    Serial.feed(wrap("GEOM,0,1,7,3,-7,3,-5,-2,5,-2"));
    Serial.feed(wrap("GEOM?"));
    g_millis += 200; pump(1);
    CHECK(Serial.out_.find("$GEOM,0,1,7,3,-7,3,-5,-2,5,-2*") != std::string::npos,
          "the reply echoes the live settings");
    CHECK(rawTft.rotation == 5, "mirrorY alone is rotation 5");
    printf("    %s", Serial.out_.c_str());
  }

#endif  // HUD_BENCH

  printf("12. the alignment pattern cannot outlive the app that asked for it\n");
  {
    Serial.feed(wrap("GEOMTEST,1"));
    g_millis += 200; pump(1);
    CHECK(geomTest, "pattern on");

    // The phone keeps sending nav frames; that alone must not dismiss it,
    // or the pattern would vanish the moment the background service ticked.
    Serial.feed(wrap("HUD,50,50,8,0,300,600,4000,4,TEST"));
    g_millis += 300; pump(1);
    CHECK(geomTest, "a nav frame does not dismiss it");

    // A $GEOM read a millisecond after the pass began is stamped with the
    // pass's clock. Stamped with a later millis(), `now - geomLastMsgMs`
    // underflowed in the same pass and dismissed the pattern at once -- the
    // likelier the more a slider was being dragged.
    {
      const uint32_t passStart = g_millis + 300;
      g_millis = passStart + 1;                 // the clock ticked mid-pass
      Serial.feed(wrap("GEOM,1,0,0,0,0,0,0,0,0,0"));
      linkPump(passStart);
      CHECK(geomLastMsgMs == passStart, "a $GEOM is stamped with the pass's clock");
      g_millis = passStart;
      pump(1);
      CHECK(geomTest, "and the pattern is still up after it");
    }

    // A minute spent looking at the windscreen is not the app going away:
    // the Keystone screen sends nothing while no slider moves.
    g_millis += 60000;
    pump(1);
    CHECK(geomTest, "a minute without touching a slider keeps the pattern up");

    // Two minutes of silence from the keystone screen does.
    g_millis += GEOM_TEST_IDLE_MS + 1000;
    pump(1);
    CHECK(!geomTest, "but two minutes without a $GEOM does");

    // ...and the drive display is back, with the state that arrived meanwhile.
    g_boxes.clear();
    Serial.feed(wrap("HUD,64,70,1,0,220,540,3300,4,BACK"));
    g_millis += 200; pump(1);
    CHECK(cur.speed == 64, "frames received during the pattern still counted");
    CHECK(!g_boxes.empty(), "and the screen is being drawn again");
  }

  printf("13. a whole drive with a keystone on stays on the panel\n");
  {
    // The realistic correction: dash tilted back, top corners pulled in.
    Serial.feed(wrap("GEOM,1,0,44,16,-44,16,-8,-4,8,-4"));
    g_millis += 300; pump(1);
    CHECK(!tft.geom.identity, "keystone is live");
    g_outOfBounds = 0;
    for (int i = 0; i < 200; i++) {
      char body[128];
      int spd = 30 + (i % 100), lim = (i % 7) * 20;
      snprintf(body, sizeof body, "HUD,%d,%d,%d,%d,%d,%d,%d,%d,STREET %d",
               spd, lim, i % MAN_COUNT, i % 8, i * 13, 900 - i, 9000 - i * 20,
               FLAG_GPS_OK | ((spd > lim && lim > 0) ? FLAG_OVER_LIMIT : 0), i);
      Serial.feed(wrap(body));
      g_millis += 250;
      pump(1);
    }
    CHECK(g_outOfBounds == 0, "200 keystoned frames, nothing off-panel");
    printf("    200 frames, %d out-of-bounds\n", g_outOfBounds);
  }

  printf("14. dismissing the pattern never flashes a stale drive display\n");
  {
    // The scenario: the driver opens the keystone screen, the cable falls out,
    // and two minutes later the board dismisses the pattern by itself. What it
    // must NOT do is repaint the speed and the speed limit that were live
    // before the pattern went up -- those are now two minutes old.
    Serial.feed(wrap("HUD,110,70,8,0,400,600,4000,4,STALE"));
    g_millis += 300; pump(1);
    CHECK(linkUp, "link is up with a frame on the glass");

    Serial.feed(wrap("GEOMTEST,1"));
    g_millis += 300; pump(1);
    CHECK(geomTest, "pattern up");

    // Cable out. Nothing arrives at all from here on.
    g_millis += GEOM_TEST_IDLE_MS + 5000;
    g_boxes.clear();
    pump(1);
    CHECK(!geomTest, "the pattern timed out");
    CHECK(!linkUp, "and the link is marked down");
    // The bug this guards against painted TWO screens on this one pass: a full
    // drive display rebuilt from state that was live before the pattern went
    // up, and then the fallback screen over the top of it. A tenth of a second
    // of a stale speed limit on the glass is exactly what that fallback exists
    // to prevent.
    //
    // So the test is "one screen, not two", and it has to measure that rather
    // than assume a number. It used to assert fewer than 10 primitives, which
    // only worked while the fallback was the 2-primitive NO LINK splash; the
    // fallback is the car screen now and a full one is ~190 in the dash theme
    // and ~35 in the E60. Measuring a known single repaint first makes the
    // threshold follow whichever theme is compiled.
    const size_t painted = g_boxes.size();
    screenNow = SCREEN_NOTHING;          // force the next pass to paint in full
    g_boxes.clear();
    g_millis += 300; pump(1);
    const size_t oneFullPaint = g_boxes.size();
    printf("    %zu primitives on the dismissal pass, one full repaint is %zu\n",
           painted, oneFullPaint);
    CHECK(oneFullPaint > 0, "the reference repaint drew nothing, so this proves nothing");
    CHECK(painted <= oneFullPaint + 20, "two screens were painted on one pass");

    // And the display is not left blank: something is on it.
    CHECK(g_boxes.size() > 0, "something was drawn");
  }

  printf("15. a geometry change with the link up repaints in full, once\n");
  {
    Serial.feed(wrap("HUD,64,50,1,0,200,500,3000,4,BACK"));
    g_millis += 300; pump(1);
    CHECK(linkUp, "link back up");
    g_boxes.clear();
    Serial.feed(wrap("GEOM,1,0,20,8,-20,8,-4,-2,4,-2"));
    g_millis += 300; pump(1);
    const size_t full = g_boxes.size();
    CHECK(full > 10, "the new geometry forced a full repaint");
    // ...and the next unchanged frame goes back to drawing nothing, which is
    // what proves `shown` was left consistent with the glass.
    g_boxes.clear();
    Serial.feed(wrap("HUD,64,50,1,0,200,500,3000,4,BACK"));
    g_millis += 300; pump(1);
    printf("    repaint %zu primitives, then %zu\n", full, g_boxes.size());
    CHECK(g_boxes.empty(), "an unchanged frame after it draws nothing");
  }

#ifndef HUD_BENCH
  printf("16. a rotation the library will not honour falls back, loudly\n");
  {
    // The failure this guards against: an older TFT_eSPI that does not
    // implement the mirrored rotations leaves width/height wrong, every draw
    // gets clipped away, and the panel stays at its power-on white with a
    // perfectly good backlight and nothing to explain it.
    rawTft.rotationCap = 4;          // pretend 4..7 are not implemented
    Serial.out_.clear();
    tft.geom.mirrorX = true; tft.geom.mirrorY = false;
    setPanelRotation();
    CHECK(rawTft.width() == HUD_SCR_W && rawTft.height() == HUD_SCR_H,
          "fell back to an orientation that is still 480x320");
    CHECK(Serial.out_.find("ROTERR,7,1") != std::string::npos,
          "and reported which rotation it wanted");
    printf("    wanted 7, using %d (%dx%d), said: %s", rawTft.rotation,
           rawTft.width(), rawTft.height(),
           Serial.out_.empty() ? "(nothing)\n" : Serial.out_.c_str());

    rawTft.rotationCap = 8;          // a current library
    Serial.out_.clear();
    setPanelRotation();
    CHECK(rawTft.rotation == 7, "a library that supports it keeps rotation 7");
    CHECK(Serial.out_.empty(), "and says nothing");
  }
#endif  // HUD_BENCH

#if BACKLIGHT_PIN >= 0
  printf("17. the backlight follows the key, and only the key\n");
  {
    uint8_t ign[1];
 #if defined(HUD_BENCH)
    // A desk has no bus, no key and no frames. Bench mode lights the panel
    // anyway, or the board looks dead on the workbench -- and it is also the
    // escape hatch if the MCP2515 ever fails and takes the key signal with it.
    CHECK(bootBacklight > 0, "bench mode boots lit");
    ign[0] = 0x00;                                  // key out
    SPI.deliver(false, CAR_ID_IGNITION, ign, 1);
    g_millis += 50; pump(2);
    CHECK(backlightNow > 0, "and stays lit even when the bus says key out");
    printf("    bench: boot %u, key-out %u\n", bootBacklight, backlightNow);
 #elif defined(HUD_CAN)
    // No 0x130 has arrived yet, so the key state is not "out", it is unknown
    // -- and unknown is treated as out. The splash and the $CAN lines are
    // still drawn underneath, just not lit.
    CHECK(bootBacklight == 0, "boots dark with no key information");

    ign[0] = 0x41;                                  // key in, position 1
    SPI.deliver(false, CAR_ID_IGNITION, ign, 1);
    g_millis += 50; pump(2);
    CHECK(backlightNow > 0, "key in position 1 lights the panel");
    const uint8_t litWith = backlightNow;

    ign[0] = 0x00;                                  // key out
    SPI.deliver(false, CAR_ID_IGNITION, ign, 1);
    g_millis += 50; pump(2);
    CHECK(backlightNow == 0, "key out darkens it");

    // The regression this group exists for. K-CAN keeps chattering for about
    // five minutes after the car is locked and then sleeps. An earlier version
    // read that silence as "no data" and fell back to LIT -- so a parked car
    // came back on roughly CAR_STALE_MS after the last frame and glowed at an
    // empty car park until the head unit dropped the USB. Silence must change
    // nothing at all.
    g_millis += CAR_STALE_MS * 10;
    pump(20);
    CHECK(backlightNow == 0,
          "a sleeping bus leaves it dark: no fallback to lit");

    // And it comes back when the key does, which is the other half -- a rule
    // that only ever darkens would pass every check above.
    ign[0] = 0x45;                                  // engine running
    SPI.deliver(false, CAR_ID_IGNITION, ign, 1);
    g_millis += 50; pump(2);
    CHECK(backlightNow > 0, "the key coming back lights it again");
    printf("    car: boot %u, key-in %u, key-out 0, slept 0, key-in %u\n",
           bootBacklight, litWith, backlightNow);
 #endif
  }

#endif  // BACKLIGHT_PIN >= 0

  printf("18. no route means no maneuver, whatever the frame says\n");
  {
    // A frame carrying a right turn but NOT carrying FLAG_ROUTE. This is what
    // the app sends in free drive, and what a phone too old to know about the
    // flag sends always. The maneuver must not survive contact with the HUD.
    g_millis += 500;
    Serial.feed(wrap("HUD,50,50,2,0,120,600,4000,4,ELM ST"));
    pump(1);
    CHECK(cur.maneuver == MAN_NONE, "the turn is stripped out");
    CHECK(cur.distToMan == 0, "and so is its distance");
    CHECK(cur.etaSeconds == 0 && cur.remaining == 0, "and the ETA with it");
    CHECK(cur.street[0] == '\0', "and the street name");
    CHECK(cur.limit == 50, "but the speed limit survives -- it is a property "
                           "of the road, not of the route");
    CHECK(cur.speed == 50, "and so does the speed");

    // The same frame with the bit set is left completely alone.
    g_millis += 250;
    Serial.feed(wrap("HUD,50,50,2,0,120,600,4000,68,ELM ST"));
    pump(1);
    CHECK(cur.maneuver == MAN_RIGHT, "with FLAG_ROUTE the turn is kept");
    CHECK(cur.distToMan == 120, "and its distance");
    CHECK(strcmp(cur.street, "ELM ST") == 0, "and the street");
    printf("    free drive: man=%u dist=%ld street=\"%s\"\n",
           (unsigned)MAN_NONE, 0L, "");
  }

  printf("18b. $RAB reaches the state the theme draws from\n");
  {
    // A roundabout, exit 2, then the phone's measured bearing for exit 2.
    // Parsed but never copied into `cur`, the angle only ever lived in the
    // parser's scratch copy and the fallback table drew every roundabout.
    g_millis += 250;
    Serial.feed(wrap("HUD,50,50,13,2,300,600,4000,68,RING"));
    Serial.feed(wrap("RAB,2,-95"));
    pump(1);
    CHECK(cur.maneuver == MAN_ROUNDABOUT && cur.rbExit == 2, "the roundabout frame landed");
    CHECK(cur.rbAngleExit == 2 && cur.rbAngle == -95, "the $RAB bearing is kept in cur");
    CHECK(shown.rbAngle == -95, "and a repaint was triggered for it");
    // The next $HUD frame must not wipe it: the phone sends $RAB once per
    // change, not with every frame.
    g_millis += 250;
    Serial.feed(wrap("HUD,50,50,13,2,280,590,3990,68,RING"));
    pump(1);
    CHECK(cur.rbAngleExit == 2 && cur.rbAngle == -95, "a later $HUD frame keeps it");
    printf("    exit %u at %d deg\n", (unsigned)cur.rbAngleExit, (int)cur.rbAngle);

  }

#if defined(HUD_CAN)
  printf("18c. $CAR never sends a speed the bus has stopped giving\n");
  {
    // The app prefers the car's speed to GPS whenever a $CAR line is fresh,
    // so a frozen number here is a frozen speed on both displays.
    const uint32_t now = g_millis;
    car.kmh = 87.0f; car.tSpeed = now;
    Serial.out_.clear();
    sendCar();
    CHECK(Serial.out_.rfind("$CAR,87,", 0) == 0, "a fresh speed is sent");
    g_millis = now + CAR_STALE_MS + 100;           // 0x1A6 has gone quiet
    Serial.out_.clear();
    sendCar();
    CHECK(Serial.out_.rfind("$CAR,-1,", 0) == 0,
          "a stale speed is sent as -1, which the app refuses, then falls back to GPS");
    printf("    %s", Serial.out_.c_str());
    g_millis = now;
  }
#endif

  printf("19. the six display states\n");
  {
    uint8_t ign[1];
    (void)ign;                                  // unused in a bench build
    // ---- phone up -> the drive layout, whatever the car is doing ----------
    g_millis += 250;
    Serial.feed(wrap("HUD,60,50,0,0,0,0,0,4,"));
    pump(1);
    CHECK(screenNow == SCREEN_DRIVE, "a phone frame puts the drive layout up");
    CHECK(linkUp, "and the link reads as up");

 #if defined(HUD_CAN) && !defined(HUD_BENCH)
    // ---- phone gone, key in -> the car half owns the screen ---------------
    ign[0] = 0x45;                              // engine running
    SPI.deliver(false, CAR_ID_IGNITION, ign, 1);
    g_millis += LINK_TIMEOUT_MS + 200;
    pump(2);
    CHECK(screenNow == SCREEN_CAR,
          "phone gone but the key is in: the car half, not a complaint");
    CHECK(!linkUp, "and the link reads as down");

    // ---- phone gone, key out -> nothing to say ----------------------------
    ign[0] = 0x00;                              // key removed
    SPI.deliver(false, CAR_ID_IGNITION, ign, 1);
    g_millis += 500;
    pump(2);
    // The panel goes DARK here -- backlightLit() follows the key, and that has
    // not changed. What is drawn behind a dark panel is the car screen, so that
    // the key coming back lights something honest rather than a complaint.
    CHECK(screenNow == SCREEN_CAR, "key out: dark, but still the car screen");
    CHECK(!backlightLit(), "and the key is what turns the light off");

    // ---- and the phone wins again the moment it comes back ----------------
    g_millis += 250;
    Serial.feed(wrap("HUD,60,50,0,0,0,0,0,4,"));
    pump(1);
    CHECK(screenNow == SCREEN_DRIVE, "the phone takes the screen back");
 #endif
    printf("    screen now %u\n", (unsigned)screenNow);
  }

  printf(failures ? "\n%d CHECK(s) FAILED\n" : "\nall checks passed\n", failures);
  return failures ? 1 : 0;
}
