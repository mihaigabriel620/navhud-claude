// ---------------------------------------------------------------------------
//  NavHUD  --  turn-by-turn + speed limit head-up display
//  Board:   ESP8266 (NodeMCU v3 / Wemos D1 mini). Also builds for ESP32.
//  Display: 4.0" SPI TFT, ST7796S, 480x320, landscape
//  Link:    USB serial from an Android phone or head unit, 115200 8N1
//
//  Configure TFT_eSPI first! Copy arduino/config/User_Setup_ESP8266.h over
//  User_Setup.h in the TFT_eSPI library folder. See docs/BUILD.md.
//
//  ---- wiring, NodeMCU v3 --------------------------------------------------
//    TFT SCK  -> D5  GPIO14   fixed by the ESP8266's HSPI, not remappable
//    TFT MOSI -> D7  GPIO13   fixed
//    TFT MISO -> not connected (nothing here ever reads the panel)
//    TFT CS   -> D8  GPIO15
//    TFT DC   -> D1  GPIO5
//    TFT RST  -> the board's own RST pin  (so TFT_RST is -1)
//    TFT LED  -> D2  GPIO4    PWM, for the day/night dimming below
//
//    I2C  SDA     -> D3  GPIO0   gyro and compass share these two
//    I2C  SCL     -> D4  GPIO2   (not D1/D2 -- the display has those)
//
//    VCC/GND  -> 3V3 and GND. The panel draws ~120 mA with the backlight on,
//               which is more than some USB-serial adapters will give: power
//               the board from the car, not from a laptop port, before
//               blaming the wiring for a panel that resets under load.
//
//  D5/D7/D8 are forced by the SPI peripheral and by TFT_eSPI's hardware chip
//  select. D1 and D2 are chosen because GPIO0, GPIO2 and GPIO15 are sampled at
//  reset to pick the boot mode: a display that holds GPIO0 or GPIO2 low while
//  the board comes out of reset stops it booting at all, which looks exactly
//  like a dead board. Nothing on the display touches those two.
//
//  Which leaves GPIO0 and GPIO2 for the gyro -- and an I2C sensor is the one
//  thing you actively want there, because a GY-521 carries 2.2k pull-ups to
//  VCC on both lines and holds them high far more firmly than the NodeMCU's
//  own 10k. Note this is NOT the Arduino default I2C pair on an ESP8266: that
//  is GPIO4/GPIO5, which here are the backlight and DC. hud_imu.h refuses to
//  compile if the two ever land on the same pin.
// ---------------------------------------------------------------------------

// Every knob lives in hud_config.h -- theme, CAN on/off, pins, backlight
// levels, bench mode, the optional sensors. This file is the machine; that
// one is the settings. It has to come first: the geometry and CAN headers
// below read HUD_DEFAULT_MIRROR_X, HUD_CAN and friends out of it.
#include "hud_config.h"

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
  #error "TFT_eSPI is not set up for this display. Copy arduino/config/User_Setup_ESP8266.h into the TFT_eSPI library folder and RENAME it to User_Setup.h (back the old one up first), then close and reopen the IDE. See docs/BUILD.md."
#endif

#if defined(TFT_DC) && (TFT_DC != 5)
  #warning "TFT_DC is not GPIO5. That is fine if you moved the wire on purpose; if you did not, the panel will stay white."
#endif

// ---- the car half ----------------------------------------------------------
#include "hud_car.h"
#ifdef HUD_CAN
  #include "hud_can.h"
#endif
#ifdef HUD_IMU
  #include "hud_imu.h"
#endif
#ifdef HUD_MAG
  #include "hud_mag.h"
  #include "hud_mount.h"
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
#include "hud_link.h"
#include "hud_probe.h"

void setup() {
#ifdef HUD_CAN
  // Before anything else touches SPI: an undriven chip select is an asserted
  // one, and the display's init would otherwise be fed to the CAN controller.
  canParkCs();
#endif
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
  Serial.begin(LINK_BAUD);
  delay(400);

  // Pin, PWM range and the starting value. See hud_backlight.h -- on a car
  // build that starting value is dark, because no 0x130 has arrived yet and an
  // unknown key is treated as an absent one.
  backlightBegin();


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

  // ---- boot self-test ---------------------------------------------------
  //
  // Three flat fills straight at the driver, touching none of the theme, the
  // geometry layer or the fonts. It is the same thing the bring-up sketch
  // does, and it exists because "white screen" covers two completely
  // different faults with one symptom:
  //
  //   colours appear -> the panel and the bus are fine, and whatever is wrong
  //                     is in this project's own drawing code
  //   still white    -> the controller is not listening, and nothing above
  //                     this line matters
  //
  // Half a second at boot to know which half of the problem you are in.
  // Comment out HUD_BOOT_SELFTEST once the display is working.
#ifdef HUD_BOOT_SELFTEST
  diag("  self-test: watch the panel against these lines.");
  diag("    red");   rawTft.fillScreen(TFT_RED);   delay(700);
  diag("    green"); rawTft.fillScreen(TFT_GREEN); delay(700);
  diag("    blue");  rawTft.fillScreen(TFT_BLUE);  delay(700);
  diag("    black"); rawTft.fillScreen(TFT_BLACK);
  // ...and a string, straight at the driver, so a failure here separates
  // "cannot draw at all" from "cannot draw text".
  rawTft.setTextDatum(MC_DATUM);
  rawTft.setTextColor(TFT_WHITE, TFT_BLACK);
  rawTft.setTextSize(2);
  rawTft.drawString("SELF TEST", HUD_SCR_W / 2, HUD_SCR_H / 2, 4);
  rawTft.setTextSize(1);
  delay(700);
#endif

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
  Serial.begin(LINK_BAUD);
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
  canOk = canBegin(CAR_IDS, CAR_ID_COUNT);
  if (canOk) diag("  can     : ok");
  else {
    // Which STAGE failed, not just that something did. See CanStage in
    // hud_can.h: the SPI path and the crystal fail at different points and
    // want you looking at different wires.
    char b[160];
    snprintf(b, sizeof b, "  can     : %s", canStageText(canStage()));
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

#if defined(HUD_IMU) || defined(HUD_MAG)
  // The I2C bus, once, here, before anything on it is touched. It used to be
  // opened inside HudImu::begin(), which made every device on the bus depend
  // on the MPU being fitted and answering -- and the compass is not on the MPU,
  // it is a separate board on the same two wires.
  #if defined(ARDUINO_ARCH_ESP8266) || defined(ESP32)
    Wire.begin(HUD_IMU_SDA, HUD_IMU_SCL);
  #else
    Wire.begin();
  #endif
  Wire.setClock(400000);        // both the MPU and the QMC5883 are rated for it
#endif

#ifdef HUD_MAG
  // The compass FIRST, and independently. Nothing about it needs the MPU.
  {
    mag.begin();
    char b[96];
    snprintf(b, sizeof b, "  compass : %s", mag.describe());
    diag(b);
  }
#endif

#ifdef HUD_IMU
  // Do this before the splash goes away: calibration wants the car standing
  // still, and the driver has not put it in gear yet.
  imu.begin();
  {
    // Say what actually answered. whoAmI is the difference between "the sensor
    // is not wired up" and "the sensor is not the one on the label": 0x68 is
    // an MPU-6050, 0x71 an MPU-9250, 0x70 an MPU-6500 sold as one.
    char b[80];
    snprintf(b, sizeof b, "  imu     : %s, WHO_AM_I 0x%02X",
             imu.present ? "present" : "NOT FOUND", (unsigned)imu.whoAmI);
    diag(b);
  }
#ifdef HUD_MAG
  if (mag.present) loadMagCal();
  loadMount();
  // Never calibrated, or the flash was blank or damaged: start working it out
  // immediately rather than waiting to be asked. The whole point is that it
  // does not matter how the sensor ended up bolted in.
  if (!mount.valid) mount.learnStart();
#endif
#endif

  // If anything is missing, say which wire rather than which feature. This
  // runs last so it probes what is actually left on the pins after every
  // driver has had its turn.
  {
    bool missing = false;
#ifdef HUD_CAN
    if (!canOk) missing = true;
#endif
#ifdef HUD_MAG
    if (!mag.present) missing = true;
#endif
#ifdef HUD_IMU
    if (!imu.present) missing = true;
#endif
    if (missing) probeAll();
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
  // are about 250 us deep at 500 kbit/s.
  if (canOk) canPump(car, now);
#endif

  // Leave the alignment pattern if the phone has gone quiet: a driver should
  // never end up doing 120 km/h behind a test grid because an app crashed.
  if (geomTest && (now - geomLastMsgMs) > GEOM_TEST_IDLE_MS) {
    geomTest = false;
    geomDirty = true;
  }

  if (geomDirty && (now - geomLastPaintMs) >= GEOM_REPAINT_MS) applyGeom();

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

  // ---- what should be on the glass ---------------------------------------
  //
  // hud_display.h decides WHICH screen; this decides how to get there from
  // whatever is drawn now. The split is deliberate: the rule is a thing you
  // read, the transition is a thing you debug.
  //
  // SCREEN_ALIGN and SCREEN_BOOT are both "leave the glass alone" -- the
  // alignment pattern is drawn by applyGeom() above, and the boot splash was
  // drawn by setup() and is inside its grace period. Frames still arrive and
  // still update `cur` underneath either of them, so dismissing the pattern
  // brings the drive display straight back, and the backlight and the IMU
  // below still run, because the car is still a car while somebody is aiming
  // the screen at the windscreen.
  const HudScreen want = displayWanted(up, now);
  if (want != SCREEN_ALIGN && want != SCREEN_BOOT) {
    const bool switching = (want != (HudScreen)screenNow) || geomRepaint;
    geomRepaint = false;

    if (want == SCREEN_DRIVE) {
      if (switching)  { themeRenderFull(cur); shown = cur; }
      else if (got)   { themeRenderDelta(cur, shown); shown = cur; }
      themeTick(cur, now);                   // blinking, if the theme wants it
    } else if (want == SCREEN_CAR) {
      // The panel is not blank just because the phone is: the car half runs on
      // every drive, phone or no phone.
      themeRenderCarOnly(now, switching);
      if (switching) displayForgetPhone();
    } else {                                 // SCREEN_NOLINK
      // Never leave a stale speed limit on the glass. Blank and say so.
      //
      // Always, even when the panel is already dark. Skipping the paint to
      // save the 123 ms looks like a free win and is not: the old drive
      // display would still be sitting in the panel's own RAM, and the next
      // time the key lit the backlight it would light that -- a speed limit
      // from the last trip, on the glass, with nothing to take it off again.
      // Darkening first (above) is what makes the paint free instead.
      if (switching) {
        // Say which thing is missing. "Check the USB cable" is right when the
        // phone is the only source the board has -- and actively misleading
        // when the cable is fine and the CAN module is the one that did not
        // come up, which is exactly the state a bench build with a half-wired
        // MCP2515 sits in.
#ifdef HUD_CAN
        const char* why = !canOk ? "no phone, and no CAN either"
                                 : "check the USB cable";
#else
        const char* why = "check the USB cable";
#endif
        themeSplash("NO LINK", why, true);
        displayForgetPhone();
      }
    }

    screenNow = (uint8_t)want;
    everDrew  = true;
    linkUp    = (want == SCREEN_DRIVE);
  }


#ifdef HUD_IMU
  imu.update();
  // Re-zero whenever the car is provably still: that is the only moment the
  // gyro's true output is known, and its bias wanders by as much as its
  // initial tolerance as the part warms up (InvenSense quote +-20 deg/s for
  // both), so once at power-on is nowhere near enough.
  //
  // The bus is the better witness than the phone: it says zero in a tunnel,
  // in a car park and with the phone unplugged.
#ifdef HUD_CAN
  if (canOk) imu.noteStopped(carStopped(car, now));
  else
#endif
       imu.noteStopped(up && cur.speed >= 0 && cur.speed <= 2);
  if (imu.present && now - lastImuSendMs >= IMU_SEND_INTERVAL_MS) {
    lastImuSendMs = now;
    sendImu();
  }
#ifdef HUD_MAG
  // Read every pass, report every fifth or so. Reading is cheap (seven bytes
  // on a 100 kHz bus, about 700 us) and the samples feed the calibration,
  // which wants everything it can get during that one slow circle.
  // The two I2C bursts either side of this each hold the CPU for the best part
  // of a millisecond, and the MCP2515's two receive buffers are about 500 us
  // deep on a busy 500 kbit/s bus. Draining between them costs ten
  // microseconds when there is nothing waiting.
#ifdef HUD_CAN
  canPump(car, millis());
#endif
  {
    // m/s^2, not g. The mounting calibration compares horizontal acceleration
    // against d(speed)/dt off the CAN bus, and the bus is in km/h -- so the two
    // have to be in the same units or the thresholds are out by a factor of ten
    // and every gate either never fires or always does.
    const float acc[3] = { imu.accelX * 9.80665f,
                           imu.accelY * 9.80665f,
                           imu.accelZ * 9.80665f };

    // Speed derivative, from the bus. Two things about how it is measured.
    //
    // It compares the MEAN speed across a window against the mean across the
    // previous one, rather than the speed at the two instants. car.kmh is not
    // a number the bus sends: it is a distance counter differentiated over
    // dt = "when the loop last got round to polling the MCP2515", so both ends
    // carry the loop's own jitter. A few milliseconds against a 100 ms frame
    // interval is a few percent, and a few percent of 100 km/h differenced
    // twice is a couple of m/s^2 of pure phantom acceleration -- several times
    // the threshold below, on a car holding a steady cruise. A full repaint
    // takes about 123 ms and makes it far worse. Averaging is what makes the
    // motorway usable for this at all.
    //
    // And the window is a second rather than half of one, because the noise
    // scales with speed and raising the threshold instead would throw away the
    // gentle acceleration this is trying to measure.
    static uint32_t dvAtMs = 0;
    static float    dvSum = 0;
    static uint16_t dvN = 0;
    static float    dvPrevMean = 0;
    static bool     dvHavePrev = false;
    float dvdt = 0.0f;
    bool  haveDvdt = false;
    bool  stopped = false;
#ifdef HUD_CAN
    stopped = canOk && carStopped(car, now);
    if (canOk && !carStale(car.tSpeed, now)) {
      if (dvAtMs == 0) { dvAtMs = now; dvSum = 0; dvN = 0; }
      dvSum += car.kmh; dvN++;
      if (now - dvAtMs >= 1000 && dvN > 0) {
        const float mean = dvSum / (float)dvN;
        if (dvHavePrev) {
          dvdt = (mean - dvPrevMean) / 3.6f / ((now - dvAtMs) / 1000.0f);
          haveDvdt = true;
        }
        dvPrevMean = mean; dvHavePrev = true;
        dvAtMs = now; dvSum = 0; dvN = 0;
      }
    } else {
      // The bus went quiet. Start again rather than differencing across the
      // gap, which would read a whole outage as one enormous acceleration.
      dvAtMs = 0; dvHavePrev = false;
    }
#endif

    if (mount.learning()) {
      if (stopped)        mount.feedStill(acc, now);
      else if (haveDvdt)  mount.feedMoving(acc, imu.yawDps, dvdt);
      // Automatic. Nothing is typed, no procedure is followed, and the driver
      // is not told to do anything special -- the board watches ordinary
      // driving and adopts the answer the moment there is enough of it and it
      // agrees with the speed on the bus.
      //
      // learnFinish() leaves the session running when it refuses, so calling
      // it on every qualifying pass is free and simply means the calibration
      // completes at the earliest moment it is trustworthy rather than the
      // next time somebody remembers to ask.
      if (mount.fwdSamples() >= MOUNT_FWD_SAMPLES && mount.learnFinish()) {
        pendingSave |= 2;
        diag("mount: worked out by itself. Saving at the next standstill.");
      }
    } else if (stopped && mount.valid && !mount.suspect &&
               !mount.matchesGravity(acc)) {
      // The check u-blox run instead of persisting their alignment at all. Only
      // while standing still, because gravity is the only thing being compared
      // and it is not measurable through a moving car.
      mount.suspect = true;
      // Plain text, no leading '$'. Every other line the board writes is either
      // a checksummed sentence the phone parses or a human line the phone
      // ignores, and this was neither: a '$' with no '*CS', which the phone's
      // parser reads as a corrupt frame. The phone learns about this through
      // the flag on $MAG instead.
      char b[112];
      snprintf(b, sizeof b,
               "mount: gravity is %.1f deg off the saved mounting -- something "
               "moved. Working it out again.", mount.gravityErrorDeg(acc));
      diag(b);
      // Straight back to learning. Noticing that the mounting is wrong and
      // then waiting to be asked to fix it is half a feature: the sensor is
      // still bolted to the car, the bus is still reporting, and everything
      // needed to re-learn it is already arriving.
      mount.learnStart();
    }

    // The deferred flash writes. Here rather than where they were asked for:
    // see pendingSave in hud_state.h. `!canOk` covers the bench, where there is
    // no bus to report a standstill and the board is sitting on a desk anyway.
    if (pendingSave && (stopped || !canOk)) {
      if (pendingSave & 4) { forgetCalibration(); diag("calibrations erased."); }
      else {
        if (pendingSave & 1) saveMagCal();
        if (pendingSave & 2) saveMount();
        diag("written to flash.");
      }
      pendingSave = 0;
    }

    if (mag.present && mag.read()) {
      mag.calFeed();
      mag.healthFeed(now);
      const float m[3] = { mag.x, mag.y, mag.z };
      magHeadingDeg = mount.headingDeg(m, acc);
      if (now - lastMagSendMs >= MAG_SEND_INTERVAL_MS) {
        lastMagSendMs = now;
        sendMag();
      }
    }
  }
#endif
#endif

#ifdef HUD_CAN
  if (canOk && now - lastCanReportMs >= CAN_REPORT_MS) {
    lastCanReportMs = now;
    // Read and clear the overflow flags whether or not anybody is listening.
    // They latch, so gating this on the phone being connected meant a
    // phone-free drive left them set and the count stopped counting.
    const uint32_t ovf = canOverflows();
    if (!up) { /* nothing to report to, but the flags are cleared */ }
    else {
    sendCar();
    // Frames the hardware threw away because we were painting. If this climbs
    // on the road the loop is too slow, not the wiring.
    if (ovf) {
      char body[48];
      snprintf(body, sizeof body, "CANDROP,%lu,%lu",
               (unsigned long)ovf, (unsigned long)canFrameCount());
      sendLine(body);
    }
    }
  }
#endif

  // 5 ms is a long time when a 500 kbit/s bus can fill both RX buffers in
  // 250 us, so the bus gets drained again rather than slept through.
#ifdef HUD_CAN
  for (uint8_t i = 0; i < 5; i++) { canPump(car, millis()); delay(1); }
#else
  delay(5);
#endif
}
