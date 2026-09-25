// ---------------------------------------------------------------------------
//  hud_settings.h -- what survives a power cycle, and the only file that
//  writes flash.
//
//  The screen alignment (mirror and keystone corners) and the compass
//  calibration, in one 4 KB EEPROM sector: saving both is one erase. The byte
//  layout of the alignment is hud_store.h, which stays free of Arduino so the
//  host tests can check it; this file is the part that talks to the flash.
//
//  If a setting forgets itself between boots, or saving it stalls the panel,
//  it is this file.
// ---------------------------------------------------------------------------
#ifndef HUD_SETTINGS_H
#define HUD_SETTINGS_H

// Defined in hud_link.h, which is included after this file.
static void sendLine(const char* body);
static void diag(const char* s);

#if defined(ARDUINO_ARCH_ESP8266) || defined(ESP32) || defined(HUD_HOST_TEST)
  #define HUD_HAVE_EEPROM 1
#endif

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
  if (heading.unpack(b)) diag("  compass : calibration restored from flash");
}

/** Write it. Shares the sector with the geometry, so this is one erase. */
static void saveCompassCal() {
  eepromOpen();
  uint8_t b[HUD_MAG_STORE_BYTES];
  heading.pack(b);
  for (int i = 0; i < HUD_MAG_STORE_BYTES; i++) EEPROM.write(HUD_MAG_STORE_OFF + i, b[i]);
  EEPROM.commit();
}

/** Throw it away, in RAM and in flash. */
static void forgetCompassCal() {
  heading.forget();
  saveCompassCal();
}

/**
 * The calibration writes that were asked for while moving, done now that the
 * car is stopped (or there is no bus to ask, which is a desk).
 *
 * Done here rather than where they were asked for. EEPROM.commit() erases a
 * 4 KB sector with interrupts off -- tens of milliseconds, up to 400 by the
 * datasheet -- and nothing fills the UART buffer in that window, so a $CAM or
 * $LANE clearing frame arriving during it is lost for good. At a standstill
 * nobody minds.
 */
static void settingsSaveDeferred(bool stopped) {
  if (!stopped) return;
  if (magForgetPending) {
    forgetCompassCal();
    magForgetPending = false; magSavePending = false;
    diag("compass calibration erased.");
  } else if (magSavePending) {
    saveCompassCal();
    magSavePending = false;
    diag("written to flash.");
  }
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

#endif  // HUD_SETTINGS_H
