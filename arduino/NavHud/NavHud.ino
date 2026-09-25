// ---------------------------------------------------------------------------
//  NavHUD  --  turn-by-turn + speed limit head-up display
//  Board:   ESP8266 (NodeMCU v3 / Wemos D1 mini). Also builds for ESP32.
//  Display: 4.0" SPI TFT, ST7796S, 480x320, landscape
//  Link:    USB serial from an Android phone or head unit, 115200 8N1
//
//  Configure TFT_eSPI first! Copy arduino/config/User_Setup.h over
//  User_Setup.h in the TFT_eSPI library folder. See docs/BUILD.md.
//
//  ---- file map -----------------------------------------------------------
//
//  This file only starts things and runs the loop. Each job is one file:
//
//    hud_config.h     every knob: what is fitted, timings, brightness
//    hud_pins.h       every pin, checked by the compiler
//    hud_state.h      the variables the files share
//    hud_bus.h        the SPI bus the panel and the MCP2515 share
//    hud_protocol.h   the wire format: frame grammar, checksums, parser
//    hud_link.h       the USB cable: frames up, commands down, typed commands
//    hud_can.h        the MCP2515 driver, listen-only
//    hud_car.h        the E60's CAN frames turned into speed, rpm, PS, volts
//    hud_i2c.h        the I2C bus, and freeing it when a chip holds it low
//    hud_compass.h    the QMC5883P: heading and its calibration
//    hud_backlight.h  lit by the key, dimmed by the phone
//    hud_display.h    which screen is up, and switching to it
//    hud_theme.h      picks the theme; primitives both themes use
//    theme_dash.h     the default layout, three bands
//    theme_e60.h      the E60-style layout
//    hud_arrows.h     turn, roundabout and U-turn glyphs
//    hud_aa.h         the anti-aliased fill those glyphs are drawn with
//    hud_canvas.h     every draw call, through the keystone on its way out
//    hud_geom.h       the mirror and keystone maths
//    hud_align.h      panel orientation and the alignment pattern
//    hud_settings.h   what survives a power cycle; the only file writing flash
//    hud_store.h      the byte layout of the saved alignment
//
//  If this breaks -> look here:
//
//    no car data, or the bus misbehaves ...... hud_can.h, then hud_bus.h
//    a car number is wrong ................... hud_car.h
//    a message missing or wrong on the cable . hud_link.h, hud_protocol.h
//    compass not found ....................... hud_i2c.h, hud_compass.h
//    heading wrong ........................... hud_compass.h
//    the wrong screen is up .................. hud_display.h
//    something drawn wrong ................... theme_dash.h / theme_e60.h
//    a wrong or jagged arrow ................. hud_arrows.h, hud_aa.h
//    picture mirrored or trapezoidal ......... hud_align.h, hud_geom.h
//    panel dark, or too bright ............... hud_backlight.h
//    a setting forgotten between boots ....... hud_settings.h
//    garbage on the glass while CAN runs ..... hud_bus.h, hud_pins.h
//
//  ---- wiring -------------------------------------------------------------
//
//  The authoritative map is hud_pins.h, where the compiler checks it. This is
//  the copy you read with a soldering iron in your hand.
//
//    TFT SCK  -> D5  GPIO14   fixed by the HSPI peripheral
//    TFT MOSI -> D7  GPIO13   fixed
//    TFT SDO  -> LEAVE UNCONNECTED.  <-- read hud_pins.h before ignoring this
//    TFT CS   -> D2  GPIO4
//    TFT DC   -> D1  GPIO5
//    TFT RST  -> the board's own RST pin  (so TFT_RST is -1)
//    TFT LED  -> D0  GPIO16   PWM, day/night dimming
//
//    MCP2515 SCK    -> D5, shared with the display
//    MCP2515 header pin marked SI -> D7   (MOSI)
//    MCP2515 header pin marked SO -> D6   (MISO) and NOTHING ELSE on this pin
//                      -- these modules label the header from the HOST's side
//    MCP2515 CS     -> D8  GPIO15
//    MCP2515 VDD    -> 3V3     TJA1050 VCC -> 5V (they are separate rails)
//
//    QMC5883P SDA -> D3  GPIO0
//    QMC5883P SCL -> D4  GPIO2     (NOT D1/D2 -- the display has those)
//
//    The panel draws about 120 mA with the backlight on, which is more than
//    some USB-serial adapters will give. Power the board from the car before
//    blaming the wiring for a panel that resets under load -- and note that
//    the compass shares that same 3V3 rail.
//
//  WHY THESE PINS AND NOT OTHERS: hud_pins.h, with the datasheet citations.
//  The short version is that a D1 mini has exactly six pins spare and this
//  needs exactly six, so there is nothing to move anything to.
// ---------------------------------------------------------------------------

// Every knob lives in hud_config.h -- theme, CAN on/off, pins, backlight
// levels, bench mode, the optional sensors. This file is the machine; that
// one is the settings. It has to come first: the geometry and CAN headers
// below read HUD_DEFAULT_MIRROR_X, HUD_CAN and friends out of it.
#include "hud_config.h"
#include "hud_pins.h"

#include <TFT_eSPI.h>
#include <SPI.h>
#if defined(ARDUINO_ARCH_ESP8266)
  #include <ESP8266WiFi.h>
  #include <EEPROM.h>
#elif defined(ESP32)
  #include <WiFi.h>
  #include <EEPROM.h>
#elif defined(HUD_HOST_TEST)
  #include <EEPROM.h>            // test/EEPROM.h: a RAM-backed stand-in
#endif

#include "hud_protocol.h"
#include "hud_geom.h"
#include "hud_canvas.h"
#include "hud_store.h"
#include "hud_bus.h"

// ---------------------------------------------------------------------------
//  Is TFT_eSPI actually configured for this panel?
//
//  TFT_eSPI is set up by editing a file INSIDE THE LIBRARY, not by the sketch,
//  which catches everybody once. If the step was missed, the library is still
//  built for whatever it shipped with -- a different controller, and DC on a
//  different pin -- so nothing you send ever initialises the panel and you get
//  a white screen with a perfectly working backlight.
//
//  Rather than let that happen at 2 a.m. on a bench, fail here:
// ---------------------------------------------------------------------------
#if !defined(ST7796_DRIVER)
  #error "TFT_eSPI is not set up for this display. Copy arduino/config/User_Setup.h into the TFT_eSPI library folder and RENAME it to User_Setup.h (back the old one up first), then close and reopen the IDE. See docs/BUILD.md."
#endif

// ---- the car half ----------------------------------------------------------
#include "hud_car.h"
#ifdef HUD_CAN
  #include "hud_can.h"
#endif
#ifdef HUD_MAG
  #include "hud_i2c.h"
  #include "hud_compass.h"
#endif

// The shared variables, defined here and declared to everything else. This is
// the one place they exist; HUD_STATE_OWNER is what makes this translation
// unit the owner.
#define HUD_STATE_OWNER 1
#include "hud_state.h"

#include "hud_theme.h"
#include "hud_display.h"
#include "hud_backlight.h"


#include "hud_align.h"
#include "hud_settings.h"
#include "hud_link.h"

void setup() {
  // Before anything else touches SPI. An undriven chip select floats, and a
  // floating select is an asserted one -- the display's init sequence would be
  // clocked into the CAN controller as commands. Both selects, both high,
  // first thing, which is exactly what TFT_eSPI's own shared-bus example does.
  busParkChipSelects();
  // ---- from here to init(), this is the bring-up sketch's boot path -----
  //
  // Byte for byte. PanelDiag works on this hardware and this did not, so
  // every difference between the two before the panel is spoken to has been
  // removed rather than reasoned about: no RX buffer resize, no radio calls,
  // no flash read. Those all happen further down, once there is a picture.
  //
  // The RX buffer in particular had been there since the beginning and was
  // never once suspected, because "it only allocates a kilobyte" is the sort
  // of thing you conclude instead of test.
  Serial.begin(HUD_BAUD);
  delay(400);

  // The backlight is brought up LAST, after the compass -- see the note down
  // there. Its pin is parked as an output here so it is not left floating
  // through the panel's own power-on garbage.
  pinMode(PIN_BACKLIGHT, OUTPUT);
  digitalWrite(PIN_BACKLIGHT, LOW);


  // ---- the panel, before anything else gets a chance to interfere -------
  //
  // This used to run after the radio was shut down and the settings read out
  // of flash, which put three things that touch timing, power and the flash
  // bus between the board waking up and the display being told it exists. If
  // any of them upsets the panel you get a white screen and no way to tell
  // which. Now the screen is alive first and everything else happens behind a
  // splash you can see.
  //
  // The delay is not padding. RESET is tied to the board's own reset line
  // rather than driven by the library, so the ST7796S comes out of reset with
  // the ESP -- and the datasheet wants 120 ms after that before it will accept
  // a command. The ESP is ready long before. It is paid once, at boot.
  delay(400);

  rawTft.init();
  delay(50);
  // Rotation 1 to start: the plain landscape orientation every version of the
  // library has always implemented. The mirror is applied further down, once
  // there is something on the glass to notice it going wrong.
  rawTft.setRotation(1);

  reportPanel();

  // A flat-fill self-test used to live here, compiled out behind a symbol
  // that was never defined anywhere -- so it was dead code carrying a comment
  // telling you to switch off something that was already off. The `wipe`
  // command does the same job on demand, without costing two seconds of every
  // boot.


  themeInit();
  themeSplash("NavHUD", "starting...", false);

  // ---- now the rest, with a picture already on the glass ----------------
#if defined(ARDUINO_ARCH_ESP8266)
  // A full repaint at 20 MHz blocks this loop for about a tenth of a second,
  // and 115200 baud delivers 1.4 KB in that time, so the stock 256-byte RX
  // buffer overflows inside a reconnect repaint. Resizing it means ending and
  // restarting the port, which is cheap and, crucially, now happens where it
  // cannot affect the panel coming up.
  Serial.end();
  Serial.setRxBufferSize(1024);
  Serial.begin(HUD_BAUD);
#endif
#if defined(ARDUINO_ARCH_ESP8266) || defined(ESP32)
  // Nothing here talks to a network, and the radio costs ~70 mA and fires
  // interrupts that show up as jitter in the middle of an SPI burst.
  WiFi.persistent(false);
  WiFi.mode(WIFI_OFF);
#endif
#if defined(ARDUINO_ARCH_ESP8266)
  WiFi.forceSleepBegin();
  delay(1);
#endif

  // The saved mirror decides the rotation, so this has to come before the
  // splash that stays up.
#ifdef HUD_CAN
  // After the display: the two share the SPI bus, and bringing the MCP2515 up
  // first would leave the bus at 8 MHz for TFT_eSPI's un-transactioned init.
  canOk = canBegin();
  if (canOk) {
    diag("  can     : ok, listen-only");
  } else {
    // WHICH failure, not just that there was one. canBegin() asks the chip for
    // CANSTAT itself before handing over to the library, precisely so this line
    // can name a wire instead of repeating "not found".
    char b[200];
    snprintf(b, sizeof b, "  can     : NOT WORKING -- %s", canFaultText());
    diag(b);
  }
#endif

  loadGeom();
  setPanelRotation();

  hudStateInit(cur);
  hudStateInit(shown);
  shown.speed = -999;              // force a full first paint

  themeInit();
  themeSplash("NavHUD", "waiting for the phone...", false);

#ifdef HUD_MAG
  // The two-wire bus, once, here, before anything on it is touched.
  //
  i2cBegin();
  if (i2cWasStuck) diag("  i2c     : bus was held low at boot; clocked free");

  {
    // Three attempts, not one. The first transaction of a board's life happens
    // while the 3V3 rail is still settling and the backlight may have just come
    // on, and a compass that NAKs once at that moment used to be written off
    // for the rest of the run.
    // Say what the BUS says before the driver gets a vote. "no compass found"
    // covers a dead bus, a chip at the wrong address and a chip that answers
    // with the wrong ID, and those are three different faults.
    char b[120];
    const bool acked = i2cPresent(QMCP_ADDR);
    uint8_t id = 0;
    if (acked) i2cRead(QMCP_ADDR, 0x00, &id, 1);

    for (uint8_t i = 0; i < 3 && !compass.begin(); i++) delay(30);
    snprintf(b, sizeof b, "  compass : %s", compass.describe());
    diag(b);
    if (!compass.present()) {
      snprintf(b, sizeof b,
               "            0x2C %s, chip id 0x%02X (want 80). Check SDA/SCL and 3V3.",
               acked ? "ACKed" : "did NOT ack", (unsigned)id);
      diag(b);
    } else {
      snprintf(b, sizeof b, "            CTRL1 0x%02X  CTRL2 0x%02X  range +-%u G  %s",
               (unsigned)compass.ctrl1, (unsigned)compass.ctrl2,
               (unsigned)compass.rangeG,
               compass.dataSeen ? "producing data" : "NO DATA -- configured but silent");
      diag(b);
    }
    if (compass.present()) loadCompassCal();
  }
#endif

  {
    bool missing = false;
#ifdef HUD_CAN
    if (!canOk) missing = true;
#endif
#ifdef HUD_MAG
    if (!compass.present()) missing = true;
#endif
    if (missing) diag("  (something is missing -- type `status`. For the MCP2515 on its own, flash CanTest.)");
  }

  // ---- the backlight, last ------------------------------------------------
  //
  // Deliberately after the compass, and the reason is worth writing down.
  //
  // The panel's LED pin is driven straight from a GPIO on this board, and at
  // full brightness that is a steady load on the same 3V3 rail the compass
  // sits on. Mihai noticed a long time ago that the compass stops being found
  // when bench mode is on -- and bench mode is the one thing that forces the
  // backlight on regardless of the key. That is not a coincidence and it is
  // not a software fault: a sagging rail during the I2C probe reads exactly
  // like a chip that is not there.
  //
  // Bringing the light up after the probe costs nothing -- a dark panel for
  // the half second the rest of boot takes -- and it means the one moment that
  // has to be electrically quiet is quiet. The real fix is a transistor on the
  // LED pin so the rail never sees that current at all.
  //
  // It also fixes an older bug for free: backlightAtBoot() asks whether the
  // key is on, and when this ran first the answer was always "no" because
  // canBegin() had not run yet.
  backlightBegin();

  {
#if defined(HUD_CAN) && !defined(HUD_BENCH) && !defined(CAN_DEAD_MEANS_DARK)
    // Not a bug. Say it in words rather than leaving it to be discovered.
    if (!canOk) diag("  backlight: ON and staying on -- no CAN controller, so no key"
                     " signal. This is deliberate; see CAN_DEAD_MEANS_DARK.");
#endif
  }

  sendHello();
}

void loop() {
  // Time first: linkPump() stamps lastFrameMs with it, and reading millis()
  // once per pass rather than at every frame keeps every timestamp in a pass
  // consistent with every other.
  const uint32_t now = millis();
  const bool got = linkPump(now);

  const bool up = (now - lastFrameMs) < LINK_TIMEOUT_MS && lastFrameMs != 0;

#ifdef HUD_CAN
  // Before anything is drawn: the bus does not wait, and the two RX buffers
  // hold about 2.2 ms of a 100 kbit/s bus.
  if (canOk) canPump(car, now);
  else       canRecover(now);            // lost mid-drive: look again now and then
#endif

  // ---- the alignment pattern and staged corners (hud_align.h) ------------
  alignUpdate(now);

  // ---- lit, and how brightly ---------------------------------------------
  //
  // Before the drawing, not after, and that ordering is load-bearing. Take the
  // key out with no phone connected and this pass both darkens the panel and
  // repaints it to NO LINK. Darkening first means the repaint happens behind a
  // panel that is already black, so the driver getting out of the car does not
  // get a red "check the USB cable" flashed at them for a tenth of a second on
  // their way past. Nothing is wrong at that moment; the trip is over.
  //
  // It also sits outside the screen block below on purpose: the night flag
  // keeps arriving while the alignment pattern is up, and a test grid at full
  // daytime brightness is the last thing anybody wants at night. The rule
  // itself is in hud_backlight.h -- lit follows the key, brightness follows
  // the phone.
  backlightUpdate(up, got);

  // ---- what should be on the glass (hud_display.h) -----------------------
  displayUpdate(up, got, now);


#ifdef HUD_MAG
  // The compass, read every pass, reported every fifth.
  //
  {
    // Not found at boot is not a verdict. Two address probes every three
    // seconds costs nothing and means a compass that browned out during
    // bring-up -- or was plugged in afterwards -- starts working without a
    // reboot.
    if (!compass.present()) {
      static uint32_t retryAt = 0;
      if (now - retryAt >= MAG_RETRY_MS) {
        retryAt = now;
        if (compass.begin()) {
          char b[96];
          snprintf(b, sizeof b, "  compass : %s (found on a retry)", compass.describe());
          diag(b);
          loadCompassCal();
        }
      }
    }

    // Reading costs about 700 us at 100 kHz, and the MCP2515's two receive
    // buffers hold roughly 2.2 ms of a busy 100 kbit/s bus. Draining either
    // side of the I2C burst costs ten microseconds when nothing is waiting and
    // saves a frame when something is.
#ifdef HUD_CAN
    if (canOk) canPump(car, millis());
#endif

    if (compass.present() && compass.update()) {
      if (now - lastMagSendMs >= MAG_SEND_INTERVAL_MS) {
        lastMagSendMs = now;
        sendMag();
      }
    }

    // Deferred flash writes, done here rather than where they were asked for.
    // EEPROM.commit() erases a 4 KB sector with interrupts off -- tens of
    // milliseconds, up to 400 by the datasheet -- and nothing fills the UART
    // buffer in that window, so a $CAM or $LANE clearing frame arriving during
    // it is lost for good. At a standstill nobody minds.
    {
      bool stopped = false;
#ifdef HUD_CAN
      stopped = canOk && carStopped(car, now);
      if (!canOk) stopped = true;        // no bus to ask; this is a desk
#else
      stopped = true;
#endif
      if (stopped) {
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
    }
  }
#endif  // HUD_MAG

#ifdef HUD_CAN
  // ---- the car, up the cable (hud_link.h) --------------------------------
  linkReportCar(now, up);
#endif

  // 5 ms is more than the two RX buffers hold (about 2.2 ms of this bus), so
  // the bus gets drained again rather than slept through.
#ifdef HUD_CAN
  for (uint8_t i = 0; i < 5; i++) { canPump(car, millis()); delay(1); }
#else
  delay(5);
#endif
}
