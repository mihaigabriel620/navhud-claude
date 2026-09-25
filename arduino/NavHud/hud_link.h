// ---------------------------------------------------------------------------
//  hud_link.h -- the cable to the phone.
//
//  Everything that crosses the USB serial line, in both directions: the frames
//  the board sends up (hello, compass, turn rate, car) and the commands it
//  takes back down (geometry, alignment pattern, compass calibration).
//
//  If a message is missing, malformed, or the app and the board disagree about
//  what a field means, it is this file and PROTOCOL.md.
//
//  The wire format itself -- checksums, parsing, the frame grammar -- lives in
//  hud_protocol.h, which is shared with the host tests and knows nothing about
//  Serial.
// ---------------------------------------------------------------------------
#ifndef HUD_LINK_H
#define HUD_LINK_H

/**
 * Say out loud what the panel is doing, on the serial line.
 *
 * Plain text rather than $ sentences on purpose: a human with a serial monitor
 * is the audience, and the phone's parser ignores anything that does not start
 * with a '$' anyway.
 */
static void diag(const char* s) { Serial.print(s); Serial.print("\r\n"); }

static void sendLine(const char* body) {
  char line[80];
  snprintf(line, sizeof line, "$%s*%02X\r\n", body,
           HudParser::checksum(body, strlen(body)));
  Serial.print(line);
}

static void sendHello() {
  // Protocol 3. $IMU needs no capability flag: the MPU is found at boot or on a
  // later retry, so a flag here could not be true, and the app takes $IMU
  // whenever it arrives and does without it otherwise.
  sendLine("HELLO,NAVHUD,3");
}

#ifdef HUD_MAG
/**
 * $MAG,<headingDeg>,<calibrating>,<samples>,<fieldUt>,<spreadPct>,<mount>
 *
 * Magnetic north, not true: declination depends on where you are, the phone
 * knows that and this board does not. Absolute, and available standing still,
 * which is the one thing GPS cannot give.
 *
 * Sent only while the compass is genuinely reporting. A line that says 0.0
 * every 200 ms because the chip is absent is worse than silence -- the app
 * cannot tell it from actually pointing north.
 *
 * The field is six wide and stays six wide, because the app reads it
 * positionally. Field 5 was a rolling spread measure from the old driver; it is
 * now 0, because the QMC5883P renews its own offset on every measurement with
 * set-and-reset enabled and the number had stopped meaning anything.
 *
 * FIELD 6 IS LOAD-BEARING AND IT IS EASY TO GET WRONG. The app reads it as
 * "is the number in field 1 a heading for the CAR, or just for the chip", and
 * anything other than 1 makes it ignore the compass completely and fall back
 * to the phone's own sensors. That is the right rule and it is not changing.
 *
 * The rewrite briefly reported compass.calibrated() here, which meant a board
 * that had never been told `spin` sent 0 for ever and the app silently never
 * used the HUD compass at all. Wrong question: hard-iron calibration is about
 * how ACCURATE the heading is, not whether it is a car heading.
 *
 * The next attempt tied it to the measured field magnitude being plausible,
 * and that was wrong too -- wrong in a way worth writing down, because it
 * looked so reasonable. A scale-factor bug made the field read 13.3 uT instead
 * of 49, the plausibility check correctly said "that is not the Earth's field",
 * and field 6 went to 0 -- so the app threw away headings that were PERFECTLY
 * CORRECT. They were correct because the heading is atan2 of two components
 * scaled by the same factor, and atan2 does not care about scale. A magnitude
 * error cannot move the heading by one degree.
 *
 * So field 6 is now exactly what the app asks it: is this a car heading. The
 * chip is answering, so yes. Whether the magnitude looks like the Earth's is a
 * separate question with its own answer in field 4, which the app already
 * checks for its status line -- and which belongs in a warning, never in a
 * silent switch that disables the only absolute heading in the system.
 *
 * Alignment to the car is the `north` offset, which defaults to zero -- "the
 * chip points forwards" -- and one command fixes it.
 */
static void sendMag() {
  char body[72];
  snprintf(body, sizeof body, "MAG,%.1f,%u,%u,%.1f,%u,%u",
           heading.deg,
           (unsigned)(heading.calibrating() ? 1 : 0),
           (unsigned)heading.calCount(),
           heading.fieldUt,
           0u,
           (unsigned)(compass.present() ? 1u : 0u));
  sendLine(body);
}

/**
 * $IMU,<yawDeg>,<pitchDeg>,<rollDeg>,<rateDps>
 *
 * The app uses field 5 only: how fast the car is turning, degrees per second,
 * clockwise positive like a bearing, and it adds rate x time-since-the-last-one
 * onto its heading. So the rate is the AVERAGE over that time, not the latest
 * sample -- that makes the sum exactly the heading change -- and it is about
 * the true vertical, so a box mounted at an angle reports the car's turn and
 * not the chip's. Yaw is the compass heading (0 without one), pitch and roll
 * are the box's own, for anyone reading the line.
 */
static void sendImu(float rateDps) {
  char body[64];
  snprintf(body, sizeof body, "IMU,%.1f,%.1f,%.1f,%.2f",
           isnan(heading.deg) ? 0.0f : heading.deg,
           heading.pitchDeg(), heading.rollDeg(), rateDps);
  sendLine(body);
}
#endif

#ifdef HUD_CAN
// $CAR,<kmh>,<rpm>,<ps>,<peak>,<volts>,<ign>[,<voltsRaw>[,<coolantC>]]
//   -- so the phone can log the bus and, more usefully, prefer the car's own
//   speed over its GPS estimate.
static void sendCar() {
  // Fields seven and eight are additions rather than replacements: the app
  // reads the first six positionally, takes field seven if it is there and
  // ignores anything beyond, so a phone on an older build is unaffected and a
  // phone on a newer build talking to an older board is too.
  //
  //   7  voltsRaw   the undivided count, so the /68 scale can be checked
  //                 against a multimeter -- true scale is measured / this.
  //   8  coolantC   degrees C from 0x1D0, or -99 when the frame has not been
  //                 heard. -99 rather than 0, because 0 C is a real winter
  //                 morning and "no data" must not look like one. Nothing in
  //                 the app reads it yet and nothing on the glass draws it --
  //                 it is on the wire so one drive can confirm the decode.
  //
  //   1  kmh        -1 once 0x1A6 is stale (CAR_STALE_MS). car.kmh is never
  //                 cleared, so this used to repeat the last speed for as long
  //                 as the bus stayed up without it -- and the app, which
  //                 prefers the car's speed to GPS whenever a line is fresh,
  //                 showed that frozen number. The app refuses a line with a
  //                 negative speed and falls back to GPS (CarLink.feed).
  char body[80];
  const uint32_t now = millis();
  const int coolant = carCoolantStale(car, now) ? -99 : (int)car.coolantC;
  const int kmh = carStale(car.tSpeed, now) ? -1 : (int)(car.kmh + 0.5f);
  snprintf(body, sizeof body, "CAR,%d,%u,%d,%d,%.1f,%u,%u,%d",
           kmh, (unsigned)car.rpm, (int)car.ps,
           (int)car.peakPs, car.volts, (unsigned)(car.ignitionOn ? 1 : 0),
           (unsigned)car.voltsRaw, coolant);
  sendLine(body);
}

/**
 * Every CAN_REPORT_MS: $CAR up the cable while the phone is there, and a
 * CANDROP line when the receive-overflow count has moved. Called once per
 * loop() pass.
 */
static void linkReportCar(uint32_t now, bool up) {
  if (canOk && now - lastCanReportMs >= CAN_REPORT_MS) {
    lastCanReportMs = now;
    // Read and clear the overflow flags whether or not anybody is listening.
    // They latch, so gating this on the phone being connected meant a
    // phone-free drive left them set and the count stopped counting.
    // Read and clear the hardware flags either way -- see above.
    const uint32_t ovf = canOverflows();
    if (up) {
      sendCar();
      // Only when it CHANGED. The counter is cumulative and never resets, so
      // reporting `if (ovf)` meant one unavoidable overflow during the first
      // repaint put a CANDROP line on the wire twice a second for the rest of
      // the drive -- which is the same latch this code exists to have fixed,
      // moved from the hardware into software. "Is it still climbing" is the
      // only question worth answering, and now the line only appears when it is.
      static uint32_t ovfReported = 0;
      if (ovf != ovfReported) {
        ovfReported = ovf;
        char body[48];
        snprintf(body, sizeof body, "CANDROP,%lu,%lu",
                 (unsigned long)ovf, (unsigned long)canFrameCount());
        sendLine(body);
      }
    }
  }
}
#endif  // HUD_CAN

// ---------------------------------------------------------------------------
//  Typed commands.
//
//  The app talks in $SENTENCES with checksums. A person with the Arduino Serial
//  Monitor open talks in words, and the two share one wire, so anything that
//  does not start with a '$' is treated as something a human typed.
//
//  Replies are plain text for the same reason: the audience is somebody reading
//  a scrolling window, and the phone's parser ignores every line that does not
//  begin with '$' anyway.
// ---------------------------------------------------------------------------

/** Case-insensitive prefix match, and how many characters it ate. */
static uint8_t cmdIs(const char* line, const char* word) {
  uint8_t i = 0;
  for (; word[i]; i++) {
    char a = line[i];
    if (a >= 'A' && a <= 'Z') a = (char)(a + 32);
    if (a != word[i]) return 0;
  }
  if (line[i] != '\0' && line[i] != ' ') return 0;
  while (line[i] == ' ') i++;
  return i;
}

/**
 * wipe -- put the screen back together in four stages and watch which one
 * brings the mark with it.
 *
 * A mark that will not go away is nearly impossible to reason about from a
 * finished screen, because everything is already on it. So this takes the
 * screen apart. Each stage adds one layer and holds it for two seconds, and
 * the layers are chosen so that each one rules out a different half of the
 * system:
 *
 *   1  flat fills straight at the driver. No theme, no geometry, no fonts --
 *      just every pixel of the panel written with one colour. A mark here is
 *      the panel or the bus; nothing of ours has been drawn yet.
 *   2  the theme's own full-screen clear, and nothing on top of it. A mark
 *      that appears here is in that one big SPI burst, not in any layout.
 *   3  the car half: the rule, the tacho, volts, PS, peak, speed.
 *   4  the nav half on top: limit, turn, street.
 *
 * Four stages is two bisections. Whichever number it appears on removes three
 * quarters of the places it could be hiding.
 */
static void cmdWipe() {
  diag("");
  diag("wipe: four stages. Watch the panel and note which one the mark arrives on.");
  diag("  1  flat fills -- red, green, blue, black. Nothing of ours is drawn yet.");
  // Each stage is held long enough to look at and no longer. This blocks the
  // loop, which means no bus drain and no phone frames for the duration, so
  // the whole thing is under three seconds rather than the nine it was.
  rawTft.fillScreen(TFT_RED);   delay(350);
  rawTft.fillScreen(TFT_GREEN); delay(350);
  rawTft.fillScreen(TFT_BLUE);  delay(350);
  rawTft.fillScreen(TFT_BLACK); delay(600);

  diag("  2  the theme's own clear, with nothing drawn on it.");
  themeInit();
  delay(700);

  diag("  3  the car half: rule, tacho, volts, PS, peak, speed.");
  themeRenderCarOnly(millis(), true);
  delay(700);

  diag("  4  the nav half on top: limit, turn, street.");
  themeRenderFull(cur);
  delay(700);

  diag("");
  diag("  came up at 1 -> the panel or the bus. No drawing code was involved.");
  diag("  came up at 2 -> the full-screen clear. One corrupt SPI burst, not layout.");
  diag("  came up at 3 -> the car half.");
  diag("  came up at 4 -> the nav half.");
  // Every widget's record of what is already painted is now a lie.
  geomRepaint = true;
  diag("");
}

static void cmdHelp() {
  diag("");
  diag("NavHUD commands");
  diag("  status          what is fitted, what answered, what is calibrated");
  diag("  wipe            rebuild the screen in stages -- where does a mark come from?");
#ifdef HUD_MAG
  diag("  spin            start a compass calibration");
  diag("  spin stop       finish it and save, or say why it was refused");
  diag("  north <deg>     the car is pointing this way right now (0-359)");
  diag("  forget          erase the compass calibration");
#endif
  diag("  help            this");
  diag("");
}

/**
 * status -- everything the board knows about itself.
 *
 * The one place to look when something is not working, so it says what is
 * WRONG rather than only what is right, and names the wire where it can.
 */
static void cmdStatus() {
  // 200, not 128. The MISO_HIGH fault text alone is 156 characters and the
  // prefix adds 27, so 128 cut it off at "...or the display's SDO is still" --
  // losing the half of the sentence that names the wire. NavHud.ino builds the
  // same line into b[200]; these two disagreeing was the bug.
  char b[200];
  diag("");
  diag("NavHUD status");

#ifdef HUD_CAN
  if (canOk) {
    snprintf(b, sizeof b, "  can     : ok, listen-only, %s",
             (CAN_XTAL == MCP_8MHZ) ? "8 MHz xtal" : "16 MHz xtal");
    diag(b);
    snprintf(b, sizeof b, "            speed %.0f km/h, rpm %u, %.1f V, key %s",
             car.kmh, (unsigned)car.rpm, car.volts, car.displayOn ? "in" : "out");
    diag(b);
    // Not drawn on the glass -- reported here and on $CAR so one drive can
    // confirm the 0x1D0 decode before anything depends on it.
    {
      const uint32_t now = millis();
      if (carCoolantStale(car, now)) {
        diag("            coolant  : 0x1D0 not heard yet (decode unconfirmed)");
      } else {
        snprintf(b, sizeof b, "            coolant  : %d C  (0x1D0, decode unconfirmed)",
                 (int)car.coolantC);
        diag(b);
      }
      snprintf(b, sizeof b, "            speed scale CAR_SPEED_K = %.4f", (double)CAR_SPEED_K);
      diag(b);
      if (canModeFixes()) {
        snprintf(b, sizeof b, "            found out of listen-only %lu time(s) and put back --",
                 (unsigned long)canModeFixes());
        diag(b);
        diag("            a glitch on the shared SPI bus, or the module browning out.");
      }
      if (canBadDlc()) {
        snprintf(b, sizeof b, "            %lu frames REFUSED for an impossible length --",
                 (unsigned long)canBadDlc());
        diag(b);
        diag("            the controller or its wiring is glitching. Should be 0.");
      }
    }
  } else {
    snprintf(b, sizeof b, "  can     : NOT WORKING -- %s", canFaultText());
    diag(b);
    snprintf(b, sizeof b, "            CANSTAT probe read %02X (want 80). Flash CanTest.",
             canProbeByte);
    diag(b);
  #ifndef CAN_DEAD_MEANS_DARK
    diag("            The backlight is forced ON because of this: no controller");
    diag("            means no key signal, and a dark panel would look like a");
    diag("            dead display. See CAN_DEAD_MEANS_DARK in hud_config.h.");
  #endif
  }
#else
  diag("  can     : not compiled in");
#endif

#ifdef HUD_MAG
  snprintf(b, sizeof b, "  compass : %s", compass.describe());
  diag(b);
  if (compass.present()) {
    snprintf(b, sizeof b, "            heading %.1f deg, field %.1f uT, range +-%u G%s",
             heading.deg, heading.fieldUt, (unsigned)compass.rangeG,
             heading.healthy() ? "" : "  <- NOT the Earth's field");
    diag(b);
    if (!heading.healthy()) {
      diag("            Earth's field is 25-65 uT. A long way off usually means");
      diag("            something magnetic is next to the sensor -- but a wrong");
      diag("            SCALE cannot move the heading: it is worked out from the");
      diag("            field's direction, and every axis is scaled the same.");
      snprintf(b, sizeof b, "            The chip reports +-%u G; if that is not what you",
               (unsigned)compass.rangeG);
      diag(b);
      diag("            expect, the range write is not sticking.");
    }
    snprintf(b, sizeof b, "            offset %+.1f %+.1f %+.1f uT%s",
             heading.offset[0], heading.offset[1], heading.offset[2],
             heading.calibrated() ? "" : "  <- never calibrated: type `spin`");
    diag(b);
    snprintf(b, sizeof b, "            north offset %+.1f deg", heading.northOffsetDeg);
    diag(b);
    if (heading.calibrating()) {
      snprintf(b, sizeof b, "            CALIBRATING: %u samples of %d needed",
               (unsigned)heading.calCount(), (int)COMPASS_CAL_MIN_SAMPLES);
      diag(b);
    }
  }
  snprintf(b, sizeof b, "  mpu     : %s", motion.describe());
  diag(b);
  if (motion.present()) {
    snprintf(b, sizeof b, "            WHO_AM_I 0x%02X, pitch %+.1f deg, roll %+.1f deg%s",
             (unsigned)motion.whoAmI, heading.pitchDeg(), heading.rollDeg(),
             heading.haveUp ? "" : "  <- no gravity reading yet");
    diag(b);
    snprintf(b, sizeof b, "            gyro bias %+.2f %+.2f %+.2f deg/s%s",
             heading.bias[0], heading.bias[1], heading.bias[2],
             heading.biasKnown ? "" : "  <- not learned yet: it needs the car parked");
    diag(b);
  } else {
    diag("            Optional. Without it the heading is only right with the");
    diag("            box level: 1 deg of tilt is about 2 deg of heading here.");
  }
  if (i2cWasStuck) {
    diag("            The I2C bus was being held low at boot and had to be");
    diag("            clocked free. A slave that does that can also stop the");
    diag("            board booting -- SDA is GPIO0, a boot strap.");
  }
#else
  diag("  compass : not compiled in");
#endif

  snprintf(b, sizeof b, "  phone   : %s", linkUp ? "connected" : "no frames");
  diag(b);
  snprintf(b, sizeof b, "  panel   : %dx%d, SPI %ld Hz, screen state %u",
           (int)HUD_SCR_W, (int)HUD_SCR_H, (long)SPI_FREQUENCY, (unsigned)screenNow);
  diag(b);
  snprintf(b, sizeof b, "  backlight: %u/255", (unsigned)backlightNow);
  diag(b);
  diag("");
}

#ifdef HUD_MAG
/**
 * Queue a calibration write for the next standstill.
 *
 * It cancels a `forget` still waiting there: the write is newer. Both wait
 * for a standstill, and the forget used to win -- so `forget` while moving,
 * then a calibration or a `north`, and the next stop erased the NEW one, in
 * RAM and in flash, after it had been reported as accepted.
 */
static void magQueueSave() {
  magSavePending = true;
  magForgetPending = false;
}

/**
 * spin -- start or finish a compass calibration.
 *
 * Drive a slow full circle, or pick the board up and turn it round. The result
 * is kept only if both horizontal axes actually swept an arc: a calibration
 * taken while parked would centre the circle on wherever the car happened to be
 * pointing, which is worse than none at all because it looks like it worked.
 */
static void cmdSpin(const char* rest) {
  if (!compass.present()) { diag("no compass on this board."); return; }

  if (cmdIs(rest, "stop")) {
    char b[112];
    // Gates first, flag second. The other way round, an early `spin stop`
    // silently ends the session while printing "keep going".
    if (heading.calFinish()) {
      magQueueSave();
      diag("calibration accepted. Saving at the next standstill.");
      snprintf(b, sizeof b, "  offset %+.1f %+.1f %+.1f uT",
               heading.offset[0], heading.offset[1], heading.offset[2]);
      diag(b);
    } else {
      // Two lines rather than one: the single string overran b[128] and the
      // compiler was right to say so -- it was being cut off mid-sentence.
      snprintf(b, sizeof b, "not enough yet: %u samples of %d, and both",
               (unsigned)heading.calCount(), (int)COMPASS_CAL_MIN_SAMPLES);
      diag(b);
      snprintf(b, sizeof b,
               "horizontal directions must sweep %.0f uT. Keep turning, then `spin stop`.",
               (double)COMPASS_CAL_MIN_SPAN_UT);
      diag(b);
    }
    return;
  }
  heading.calStart();
  diag("calibrating. Drive a slow full circle, or turn the box right round on");
  diag("the dash, then type `spin stop`.");
}

/**
 * north <deg> -- "the car is pointing this way right now".
 *
 * The box's yaw on the dash, by hand, in one command: gravity tells the board
 * how the box is tilted, but nothing on it can tell which way the car's nose
 * is. One stored offset handles any rotation about the vertical. Point the car
 * at something whose bearing you know and type it.
 */
static void cmdNorth(const char* rest) {
  if (!compass.present()) { diag("no compass on this board."); return; }
  while (*rest == ' ') rest++;
  if (*rest < '0' || *rest > '9') {
    diag("usage: north <degrees>, 0-359, where the car's nose is pointing now.");
    return;
  }
  const float want = (float)atof(rest);
  if (want < 0.0f || want >= 360.0f) { diag("0 to 359, please."); return; }
  if (!heading.setNorth(want)) { diag("no reading from the compass yet."); return; }
  char b[80];
  snprintf(b, sizeof b, "north set: offset is now %+.1f deg. Saving at the next standstill.",
           heading.northOffsetDeg);
  diag(b);
  magQueueSave();
}

static void cmdForget() {
  magForgetPending = true;
  diag("compass calibration will be erased at the next standstill.");
}
#endif  // HUD_MAG

/** Act on one typed line. Anything not recognised gets the help. */
static void linkCommand(const char* line) {
  uint8_t n;
  if (!*line) return;
  if ((n = cmdIs(line, "help")) || (n = cmdIs(line, "?")))   { (void)n; cmdHelp(); return; }
  if (cmdIs(line, "status"))                                  { cmdStatus(); return; }
  if (cmdIs(line, "wipe"))                                    { cmdWipe();   return; }
#ifdef HUD_MAG
  if ((n = cmdIs(line, "spin")))   { cmdSpin(line + n);  return; }
  if ((n = cmdIs(line, "north")))  { cmdNorth(line + n); return; }
  if (cmdIs(line, "forget"))       { cmdForget();        return; }
#endif
  diag("");
  diag("Not a command. Type 'help'.");
}

/** One typed line, accumulated a byte at a time alongside the frame parser. */
static char    cmdBuf_[40];
static uint8_t cmdLen_ = 0;
/** Set when a typed line outgrew the buffer, so the truncated head is not run. */
static bool    cmdOver_ = false;

/**
 * Drain the serial port and act on everything in it.
 *
 * Returns true if anything arrived that changes what should be on the glass,
 * which is what the caller uses to decide whether a repaint is worth it.
 *
 * One HudState copy per pass, not one per byte: HudState is about 100 bytes,
 * so copying it twice for every character cost ~50 us of an 87 us byte period
 * on an AVR build -- enough to drop frames continuously against a 64-byte
 * buffer. `tmp` is the scratch the parser fills; `cur` only ever receives a
 * whole, checksum-verified frame.
 */
static bool linkPump(uint32_t now) {
  bool got = false;
  HudState tmp = cur;
  while (Serial.available()) {
    const char ch = (char)Serial.read();

    // Every byte goes to both readers. The app's frames start with '$' and are
    // ignored by the line buffer below; anything else is somebody typing.
    if (ch == '\r' || ch == '\n') {
      if (cmdLen_ > 0 && !cmdOver_) {
        cmdBuf_[cmdLen_] = '\0';
        if (cmdBuf_[0] != '$') linkCommand(cmdBuf_);
      }
      cmdLen_ = 0;
      cmdOver_ = false;
    } else if (cmdLen_ < (uint8_t)(sizeof cmdBuf_ - 1)) {
      cmdBuf_[cmdLen_++] = ch;
    } else {
      // Overlong line: drop the WHOLE line, not just the tail. Stopping at the
      // buffer end and running the head means "mount stop, actually no, I
      // changed my mind about all of this" truncates to something that still
      // starts with "mount stop" and does it. A command nobody finished typing
      // should do nothing at all.
      cmdOver_ = true;
    }

    HudParseResult r = parser.feed(ch, tmp);
    if (r == HUD_FRAME) {
      cur = tmp;
      // Once, here, before anything downstream can look at it. See
      // displayRouteRule in hud_display.h: without FLAG_ROUTE the maneuver,
      // distance, street and ETA are not stale, they are meaningless, and a
      // frame that carries them anyway is how a parked car ends up with an
      // arrow on the glass.
      displayRouteRule(cur);
      got = true; lastFrameMs = now;
    }
    else if (r == HUD_PING)  { lastFrameMs = now; }
    else if (r == HUD_GEOM) {
      // Live preview while a slider moves. Nothing is written to flash here;
      // $GEOMSAVE does that, once, when the driver is happy. And nothing is
      // repainted here either -- the display block coalesces.
      stageGeom(now);
      lastFrameMs = now;
    }
    else if (r == HUD_GEOM_TEST_ON) {
      geomTest = true; geomDirty = true; geomLastMsgMs = now;
      lastFrameMs = now;
    }
    else if (r == HUD_GEOM_TEST_OFF) {
      geomTest = false; geomDirty = true; geomLastMsgMs = now;
      lastFrameMs = now;
    }
    else if (r == HUD_GEOM_SAVE)  { saveGeom(); geomLastMsgMs = now; lastFrameMs = now; }
    else if (r == HUD_GEOM_QUERY) { sendGeom(); geomLastMsgMs = now; lastFrameMs = now; }
#ifdef HUD_MAG
    else if (r == HUD_MAG_CAL_ON) {
      lastFrameMs = now;
      if (compass.present()) { heading.calStart(); sendLine("MAGCAL,1,0"); }
      else                     sendLine("MAGCAL,0,0");
    }
    else if (r == HUD_MAG_CAL_OFF) {
      lastFrameMs = now;
      // The result is only kept if the drive actually swept a circle -- see
      // HudHeading::calFinish. A calibration taken while parked would centre
      // the offset on wherever the car happened to be pointing, which is worse
      // than none at all because it looks like it worked.
      // calFinish() deliberately leaves the session running when it refuses,
      // so that a typed `spin stop` can say "keep going". This path has no way
      // to say that -- the app has already been told calibration stopped -- so
      // it must actually stop, or calOn_ stays set for the rest of the drive
      // and every later `$MAG` keeps claiming to be calibrating.
      const bool ok = heading.calFinish();
      if (ok) magQueueSave();
      else    heading.calAbort();
      char b[24];
      snprintf(b, sizeof b, "MAGCAL,0,%u", (unsigned)(ok ? 1 : 0));
      sendLine(b);
    }
#endif
    else if (r == HUD_CAM || r == HUD_LANE) {
      cur.camKind = tmp.camKind; cur.camDistance = tmp.camDistance;
      cur.camLimit = tmp.camLimit;
      cur.laneCount = tmp.laneCount; cur.laneActive = tmp.laneActive;
      memcpy(cur.lanes, tmp.lanes, HUD_MAX_LANES);
      memcpy(cur.laneChosen, tmp.laneChosen, HUD_MAX_LANES);
      got = true; lastFrameMs = now;
    }
    else if (r == HUD_RAB) {
      // Copied like $CAM and $LANE. Without this branch the bearing only ever
      // reached `tmp`, the resync below threw it away, and every roundabout
      // was drawn from the fallback table as if no phone had sent one.
      cur.rbAngle = tmp.rbAngle; cur.rbAngleExit = tmp.rbAngleExit;
      cur.rbAngleDist = tmp.rbAngleDist;
      got = true; lastFrameMs = now;
    }
    if (r != HUD_NOTHING) tmp = cur;    // resync the scratch copy after each frame
  }
  return got;
}

#endif  // HUD_LINK_H
