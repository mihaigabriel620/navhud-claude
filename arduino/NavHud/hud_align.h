// ---------------------------------------------------------------------------
//  hud_align.h -- the panel's orientation.
//
//  Mirror, rotation, keystone, and the alignment pattern the app draws them
//  with. Saving them is hud_settings.h, the one file that writes flash.
//
//  If the picture is the wrong way round or trapezoidal, it is this file; if
//  it forgets itself between boots, it is hud_settings.h.
// ---------------------------------------------------------------------------
#ifndef HUD_ALIGN_H
#define HUD_ALIGN_H

// Defined in hud_link.h, which is included after this one because its command
// dispatch calls sendGeom() below. Two forward declarations break the cycle;
// the alternative is splitting one of the two files in half, and "where does
// the board send things from" having two answers is worse.
static void sendLine(const char* body);
static void diag(const char* s);

// ---- screen geometry -------------------------------------------------------

/**
 * The first lines on the serial line: a name, then what the driver thinks the
 * panel is. One line when it is right. A white panel with a working backlight
 * is PanelDiag's job, and docs/BUILD.md's; the driver being the right one is
 * already guaranteed by the #error in NavHud.ino.
 */
static void reportPanel() {
  char b[80];
  diag("");
  diag("NavHUD");
  const bool sizeOk = rawTft.width() == HUD_SCR_W && rawTft.height() == HUD_SCR_H;
  snprintf(b, sizeof b, "  panel   : ST7796 %dx%d, SPI %ld Hz%s",
           (int)rawTft.width(), (int)rawTft.height(), (long)SPI_FREQUENCY,
           sizeOk ? "" : " -- want 480x320");
  diag(b);
}

/**
 * setRotation, and then check it actually did what it says.
 *
 * The mirrored landscape orientations are rotations 5 and 7, which exist
 * because ST7796_Rotation.h takes `m % 8` rather than `m % 4`. That is true of
 * the library today; it has not always been, and an older copy sitting in
 * somebody's libraries folder would take a 7, do something else with it, and
 * leave the driver's width and height disagreeing with the panel. Every
 * subsequent draw would then be clipped away and the screen would stay at
 * whatever it powered up as -- white, with a working backlight, and no clue.
 *
 * So: ask, then measure. If a mirrored rotation does not come back as 480x320,
 * fall back to the plain landscape that has the same handedness and say so up
 * the cable. A display the right way round beats no display at all.
 */
static void setPanelRotation() {
  const uint8_t want = geomRotation(tft.geom.mirrorX, tft.geom.mirrorY);
  rawTft.setRotation(want);
  if (rawTft.width() == HUD_SCR_W && rawTft.height() == HUD_SCR_H) return;

  const uint8_t fallback = (want == 7) ? 1 : (want == 5 ? 3 : 1);
  rawTft.setRotation(fallback);
  char body[48];
  snprintf(body, sizeof body, "ROTERR,%u,%u", want, fallback);
  sendLine(body);
}

static void applyGeom() {
  if (!tft.geom.rebuild(HUD_SCR_W, HUD_SCR_H)) {
    // Corners that describe an impossible quad. rebuild() has already fallen
    // back to identity; say so rather than leaving the driver wondering why
    // the slider did nothing.
    sendLine("GEOMERR,quad");
  }
  setPanelRotation();
  geomLastPaintMs = millis();
  geomDirty = false;

  if (geomTest) { themeAlignPattern(); return; }

  // Everything that was on the glass is now in the wrong place, so clear it --
  // and then let the loop decide what goes back on, using a link state it has
  // just computed rather than one this function inherited.
  themeInit();
  geomRepaint = true;
}

/**
 * Take the corners from a $GEOM without repainting yet.
 *
 * Stamped with the loop pass's `now`, like every other message. A fresh
 * millis() can be a tick later than the `now` loop() then compares it with,
 * and `now - geomLastMsgMs` underflowed to four billion: the idle check
 * dismissed the pattern in the same pass, mid-drag.
 */
static void stageGeom(uint32_t now) {
  tft.geom.mirrorX = parser.geom.mirrorX != 0;
  tft.geom.mirrorY = parser.geom.mirrorY != 0;
  tft.geom.corners = parser.geom.corners;
  geomDirty = true;
  geomLastMsgMs = now;
}

/**
 * Once per loop() pass: drop the alignment pattern when the phone has gone
 * quiet, and paint staged corners at most every GEOM_REPAINT_MS, so a moving
 * slider costs one repaint per interval rather than one per message.
 */
static void alignUpdate(uint32_t now) {
  // Leave the alignment pattern if the phone has gone quiet: a driver should
  // never end up doing 120 km/h behind a test grid because an app crashed.
  if (geomTest && (now - geomLastMsgMs) > GEOM_TEST_IDLE_MS) {
    geomTest = false;
    geomDirty = true;
  }

  if (geomDirty && (now - geomLastPaintMs) >= GEOM_REPAINT_MS) applyGeom();
}

/** $GEOM? -- hand back what is on the panel right now. */
static void sendGeom() {
  const Geom& g = tft.geom;
  char body[80];
  snprintf(body, sizeof body, "GEOM,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d",
           g.mirrorX ? 1 : 0, g.mirrorY ? 1 : 0,
           g.corners.dx[0], g.corners.dy[0], g.corners.dx[1], g.corners.dy[1],
           g.corners.dx[2], g.corners.dy[2], g.corners.dx[3], g.corners.dy[3]);
  sendLine(body);
}

#endif  // HUD_ALIGN_H
