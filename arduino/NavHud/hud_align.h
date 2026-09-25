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

/** What the panel and the driver think they are, on the serial line. */
static void reportPanel() {
  char b[72];
  diag("");
  diag("NavHUD panel report");
#if defined(ST7796_DRIVER)
  diag("  driver  : ST7796 (correct)");
#else
  diag("  driver  : NOT ST7796 -- User_Setup.h is not the file being compiled");
#endif
  snprintf(b, sizeof b, "  pins    : CS %d  DC %d  RST %d  MOSI %d  SCK %d",
           (int)TFT_CS, (int)TFT_DC, (int)TFT_RST, (int)TFT_MOSI, (int)TFT_SCLK);
  diag(b);
  snprintf(b, sizeof b, "  spi     : %ld Hz", (long)SPI_FREQUENCY);
  diag(b);
  snprintf(b, sizeof b, "  size    : %d x %d  (want %d x %d)",
           (int)rawTft.width(), (int)rawTft.height(), HUD_SCR_W, HUD_SCR_H);
  diag(b);
  // The saved mirror is read out of flash further down, so this reports the
  // compiled-in default rather than what will end up on the glass. Saying so
  // beats printing a number that quietly disagrees with the panel.
  snprintf(b, sizeof b, "  mirror  : default x=%d y=%d -> rotation %u (flash may override)",
           HUD_DEFAULT_MIRROR_X, HUD_DEFAULT_MIRROR_Y,
           (unsigned)geomRotation(HUD_DEFAULT_MIRROR_X, HUD_DEFAULT_MIRROR_Y));
  diag(b);
  diag("  If you can read this but the panel is white, the controller is not");
  diag("  listening: wrong driver, a dead control wire, or SPI too fast.");
  diag("");
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
