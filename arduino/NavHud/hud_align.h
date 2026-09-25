// ---------------------------------------------------------------------------
//  hud_align.h -- the panel's orientation, and the flash it is kept in.
//
//  Mirror, rotation, keystone, the alignment pattern the app draws them with,
//  and the EEPROM sector all of it is saved to. The compass calibration lives
//  in the same sector, which is why it is loaded and saved from here too.
//
//  If the picture is the wrong way round, trapezoidal, or forgets itself
//  between boots, it is this file.
// ---------------------------------------------------------------------------
#ifndef HUD_ALIGN_H
#define HUD_ALIGN_H

// Defined in hud_link.h, which is included after this one because its command
// dispatch calls saveGeom() and sendGeom() below. Two forward declarations
// break the cycle; the alternative is splitting one of the two files in half,
// and "where does the board send things from" having two answers is worse.
static void sendLine(const char* body);
static void diag(const char* s);

// ---- screen geometry -------------------------------------------------------

#if defined(ARDUINO_ARCH_ESP8266) || defined(ESP32) || defined(HUD_HOST_TEST)
  #define HUD_HAVE_EEPROM 1
#endif

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

/**
 * EEPROM.begin() exactly once.
 *
 * Shared by the geometry and the compass calibration, which live in the same
 * sector: calling begin() a second time re-reads flash over anything staged
 * and, on some cores, hands back a second buffer. File scope rather than a
 * function-local static because there are now four callers, and the host test
 * calls loadGeom() repeatedly to model power cycles.
 */
static bool eepromReady = false;
static void eepromOpen() {
  if (!eepromReady) { EEPROM.begin(HUD_STORE_EEPROM); eepromReady = true; }
}

static void loadGeom() {
  tft.geom.mirrorX = HUD_DEFAULT_MIRROR_X;
  tft.geom.mirrorY = HUD_DEFAULT_MIRROR_Y;
#ifdef HUD_BENCH
  // Straight through, ignoring flash. Nothing is erased -- the stored
  // alignment is still there the moment this is commented out again.
  tft.geom.mirrorX = false;
  tft.geom.mirrorY = false;
  tft.geom.corners = GeomCorners{{0, 0, 0, 0}, {0, 0, 0, 0}};
  tft.geom.rebuild(HUD_SCR_W, HUD_SCR_H);
  savedGeom = tft.geom;
  diag("  HUD_BENCH: unmirrored, saved alignment ignored, Save disabled,");
  diag("             backlight forced on regardless of the key.");
  return;
#endif
#ifdef HUD_HAVE_EEPROM
  // Once. begin() allocates the RAM shadow of the flash sector, and calling it
  // again mid-run would at best re-read flash over anything staged and at worst
  // hand back a second buffer. setup() is the only caller in the firmware; the
  // host test calls loadGeom() repeatedly to model power cycles, which is
  // exactly the case this guard has to survive.
  eepromOpen();
  uint8_t b[HUD_STORE_BYTES];
  for (int i = 0; i < HUD_STORE_BYTES; i++) b[i] = EEPROM.read(i);
  hudStoreUnpack(b, tft.geom);   // leaves the defaults alone if it is blank
#endif
  tft.geom.rebuild(HUD_SCR_W, HUD_SCR_H);
  savedGeom = tft.geom;
}

#ifdef HUD_MAG
/** Read the stored compass calibration, if there is a believable one. */
static void loadCompassCal() {
  eepromOpen();
  uint8_t b[HUD_MAG_STORE_BYTES];
  for (int i = 0; i < HUD_MAG_STORE_BYTES; i++) b[i] = EEPROM.read(HUD_MAG_STORE_OFF + i);
  if (compass.unpack(b)) diag("  compass : calibration restored from flash");
}

/** Write it. Shares the sector with the geometry, so this is one erase. */
static void saveCompassCal() {
  eepromOpen();
  uint8_t b[HUD_MAG_STORE_BYTES];
  compass.pack(b);
  for (int i = 0; i < HUD_MAG_STORE_BYTES; i++) EEPROM.write(HUD_MAG_STORE_OFF + i, b[i]);
  EEPROM.commit();
}

/** Throw it away, in RAM and in flash. */
static void forgetCompassCal() {
  compass.forget();
  saveCompassCal();
}
#endif

static void saveGeom() {
#ifdef HUD_BENCH
  // Refusing beats silently overwriting a real alignment with bench defaults.
  sendLine("GEOMERR,bench");
  return;
#endif
#ifdef HUD_HAVE_EEPROM
  if (geomSame(tft.geom, savedGeom)) { sendLine("GEOMOK,0"); return; }
  uint8_t b[HUD_STORE_BYTES];
  hudStorePack(tft.geom, b);
  for (int i = 0; i < HUD_STORE_BYTES; i++) EEPROM.write(i, b[i]);
  const bool ok = EEPROM.commit();
  if (ok) savedGeom = tft.geom;
  sendLine(ok ? "GEOMOK,1" : "GEOMERR,flash");
#else
  sendLine("GEOMERR,noflash");
#endif
}

#endif  // HUD_ALIGN_H
