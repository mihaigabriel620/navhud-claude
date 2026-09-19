// ---------------------------------------------------------------------------
//  hud_link.h -- the cable to the phone.
//
//  Everything that crosses the USB serial line, in both directions: the frames
//  the board sends up (hello, gyro, compass, car) and the commands it takes
//  back down (geometry, alignment pattern, compass calibration).
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

// Defined in hud_align.h, which the sketch includes BEFORE this file because
// its body calls sendLine() and diag() below. Forward-declared rather than
// reordered: the two files genuinely call into each other, and the alternative
// is splitting the cable in half so that "where does the board send things
// from" has two answers.
static void probeAll();          // hud_probe.h, included after this one
static void stageGeom();
static void sendGeom();
static void saveGeom();
#ifdef HUD_MAG
static void saveMagCal();
#endif

/**
 * Say out loud what the panel is doing, on the serial line.
 *
 * A white screen tells you nothing: powered but uninitialised looks exactly
 * like bad wiring, a wrong driver, and talking too soon after reset. These six
 * lines separate them. They are plain text rather than $ sentences on purpose
 * -- a human with a serial monitor is the audience, and the phone's parser
 * ignores anything that does not start with a '$' anyway.
 */
static void diag(const char* s) { Serial.print(s); Serial.print("\r\n"); }

static void sendLine(const char* body) {
  char line[80];
  snprintf(line, sizeof line, "$%s*%02X\r\n", body,
           HudParser::checksum(body, strlen(body)));
  Serial.print(line);
}

static void sendHello() {
#ifdef HUD_IMU
  sendLine(imu.present ? "HELLO,NAVHUD,3,IMU" : "HELLO,NAVHUD,3");
#else
  sendLine("HELLO,NAVHUD,3");
#endif
}

#ifdef HUD_IMU
// $IMU,<yaw>,<pitch>,<roll>,<rateZ>  -- the phone reads only the rate. A bare
// gyroscope cannot know absolute yaw and GPS already does, so the first field
// is the drifting integrated value, sent for diagnostics alone.
static void sendImu() {
  char body[64];
  snprintf(body, sizeof body, "IMU,%.1f,%.1f,%.1f,%.2f",
           imu.headingRel, imu.pitchDeg, imu.rollDeg, imu.yawDps);
  sendLine(body);
}
#endif

#ifdef HUD_MAG
// $MAG,<headingDeg>,<calibrating>,<samples>
//
// Magnetic north, not true: declination is a function of where you are, the
// phone knows where that is and this board does not. Absolute, and available
// standing still, which is the one thing neither the gyro nor GPS can give.
//
// Sent only while the compass is actually reporting. A line that says 0.0
// every 200 ms because the chip is not there is worse than silence -- the app
// cannot tell it apart from genuinely pointing north.
static void sendMag() {
  // heading, calibrating, samples, field strength uT, spread as a percentage.
  //
  // The last two are the health check: the field magnitude should be the same
  // at every heading once the hard-iron offset is right, so a spread that will
  // not go away means the calibration is stale or something magnetic has moved
  // in next to the sensor. Sent rather than judged here, because the phone has
  // the screen to say it on.
  // The sixth field is the mounting's state, and it is the one the phone must
  // act on: 0 means the board does not know how it is bolted in, so the
  // heading in field 1 is out by however far the sensor is twisted from the
  // car's nose -- confidently, and with no other sign that anything is wrong.
  // 2 means it knew and gravity has since disagreed, i.e. something moved.
  char body[72];
  snprintf(body, sizeof body, "MAG,%.1f,%u,%u,%.1f,%u,%u",
           magHeadingDeg,
           (unsigned)(mag.calibrating() ? 1 : 0),
           (unsigned)mag.calCount(),
           mag.field(),
           (unsigned)(mag.fieldSpread() * 100.0f + 0.5f),
           (unsigned)(mount.usable() ? 1u : (mount.valid ? 2u : 0u)));
  sendLine(body);
}
#endif

#ifdef HUD_CAN
// $CAR,<kmh>,<rpm>,<ps>,<peak>,<volts>,<ign>  -- so the phone can log the bus
// and, more usefully, prefer the car's own speed over its GPS estimate.
static void sendCar() {
  // The raw voltage count is a seventh field rather than a replacement: the
  // app reads the first six positionally and ignores anything beyond them, so
  // this is backward compatible with a phone running the older build. It is
  // there so the /68 scale can be checked against a multimeter -- true scale
  // is measured volts / this number.
  char body[80];
  snprintf(body, sizeof body, "CAR,%d,%u,%d,%d,%.1f,%u,%u",
           (int)(car.kmh + 0.5f), (unsigned)car.rpm, (int)car.ps,
           (int)car.peakPs, car.volts, (unsigned)(car.ignitionOn ? 1 : 0),
           (unsigned)car.voltsRaw);
  sendLine(body);
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

static void cmdHelp() {
  diag("");
  diag("NavHUD commands (type one and press Enter):");
  diag("  help            this list");
  diag("  status          everything the board currently knows");
  diag("  scan            probe the actual wires: chip select, SPI, I2C");
#ifdef HUD_MAG
  diag("  spin            start the compass calibration -- then pick the");
  diag("                  board up and turn it slowly in every direction");
  diag("  spin save       keep the result and write it to flash");
  diag("  spin stop       throw it away");
  diag("  mount           start learning how the sensor is bolted in. Park,");
  diag("                  wait, then drive with some ordinary accelerating");
  diag("                  and braking in a straight line");
  diag("  mount save      keep it and write it to flash");
  diag("  mount stop      throw it away");
  diag("  forget          erase both calibrations");
#endif
  diag("");
}

static void cmdStatus() {
  char b[96];
  diag("");
  diag("NavHUD status");
  snprintf(b, sizeof b, "  link    : %s, last frame %lu ms ago",
           linkUp ? "up" : "down", (unsigned long)(millis() - lastFrameMs));
  diag(b);
#ifdef HUD_CAN
  if (!canOk) diag("  can     : controller did not start");
  else {
    snprintf(b, sizeof b, "  can     : %u kbit/s, %lu frames, %lu dropped",
             (unsigned)CAN_BITRATE_KBPS, (unsigned long)canFrameCount(),
             (unsigned long)canOverflows());
    diag(b);
    snprintf(b, sizeof b, "  car     : %.0f km/h, %u rpm, %.2f V (raw %u), key %s",
             car.kmh, (unsigned)car.rpm, car.volts, (unsigned)car.voltsRaw,
             car.displayOn ? "in" : "out");
    diag(b);
  }
#else
  diag("  can     : not built in");
#endif
#ifdef HUD_IMU
  snprintf(b, sizeof b, "  imu     : %s, WHO_AM_I 0x%02X, yaw %.1f deg/s",
           imu.present ? "present" : "NOT FOUND", (unsigned)imu.whoAmI, imu.yawDps);
  diag(b);
#else
  diag("  imu     : not built in");
#endif
#ifdef HUD_MAG
  snprintf(b, sizeof b, "  compass : %s", mag.describe());
  diag(b);
  if (mag.present) {
    float hi[3]; mag.hardIron(hi);
    snprintf(b, sizeof b, "            field %.1f uT (earth is 25-65), spread %.0f%%",
             mag.field(), mag.fieldSpread() * 100.0f);
    diag(b);
    snprintf(b, sizeof b, "            offsets %.1f %.1f %.1f uT%s",
             hi[0], hi[1], hi[2], mag.calibrated() ? "" : "  <- never calibrated");
    diag(b);
    if (mag.calibrating()) {
      snprintf(b, sizeof b, "            SPINNING: %u samples, spans %.0f %.0f %.0f uT",
               (unsigned)mag.calCount(), mag.calSpan(0),
               mag.calSpan(1), mag.calSpan(2));
      diag(b);
    }
  }
  if (!mount.valid) {
    diag("  mount   : not calibrated -- the compass cannot know which way the");
    diag("            car points until it knows how the sensor is bolted in");
  } else {
    snprintf(b, sizeof b, "  mount   : calibrated%s", mount.suspect ? " (SUSPECT)" : "");
    diag(b);
    snprintf(b, sizeof b, "            nose  %+.3f %+.3f %+.3f", mount.fwd[0], mount.fwd[1], mount.fwd[2]);
    diag(b);
    snprintf(b, sizeof b, "            up    %+.3f %+.3f %+.3f", mount.up[0], mount.up[1], mount.up[2]);
    diag(b);
  }
  if (mount.learning()) {
    char lb[160];
    snprintf(lb, sizeof lb,
             "            LEARNING: gravity %s, %u straight-line samples of %d,"
             " agreement %.2f (needs %.2f)",
             mount.haveUp() ? "settled" : "not settled yet",
             (unsigned)mount.fwdSamples(), (int)MOUNT_FWD_SAMPLES,
             mount.correlation(), (double)MOUNT_CORR_MIN);
    diag(lb);
  }
  snprintf(b, sizeof b, "  heading : %.1f deg magnetic", magHeadingDeg);
  diag(b);
#ifdef HUD_IMU
  if (mag.present && imu.present) {
    const float acc[3] = { imu.accelX, imu.accelY, imu.accelZ };
    const float dip = mag.dipDeg(acc);
    snprintf(b, sizeof b, "  dip     : %.1f deg", dip);
    diag(b);
    diag("            The angle the Earth's field makes with the horizontal. It");
    diag("            depends on WHERE you are, not which way you point -- about");
    diag("            65 in central Europe. Turn the car in a circle: if this");
    diag("            swings by tens of degrees, MAG_AXIS_ORDER/SIGN are wrong.");
  }
#endif
#endif
  diag("");
}

#ifdef HUD_MAG
static void cmdSpin(const char* rest) {
  if (!mag.present) { diag("no compass on this board."); return; }
  if (cmdIs(rest, "save")) {
    const bool ok = mag.calFinish();
    if (!ok) {
      diag("not enough movement to trust that -- nothing saved.");
      diag("turn the board through a full circle on all three axes and try again.");
      return;
    }
    pendingSave |= 1;
    diag("compass calibration accepted. It will be written to flash at the next");
    diag("standstill and will survive a reboot.");
    cmdStatus();
    return;
  }
  if (cmdIs(rest, "stop")) { mag.calStop(); diag("thrown away."); return; }
  mag.calStart();
  diag("");
  diag("Compass calibration running.");
  diag("Pick the board up and turn it slowly in every direction -- a full turn");
  diag("about each of the three axes, like rolling a dice through every face.");
  diag("The chip's own zero-point error can be six times the whole Earth field,");
  diag("so this is not optional and it is why a compass reads the same in every");
  diag("direction until it is done.");
  diag("Type 'spin save' when you have covered everything, or 'status' to look.");
  diag("");
}

static void cmdMount(const char* rest) {
  if (cmdIs(rest, "save")) {
    if (!mount.learnFinish()) {
      char b[96];
      snprintf(b, sizeof b,
               "not enough yet: gravity %s, %u/%d samples, agreement %.2f (needs %.2f)",
               mount.haveUp() ? "ok" : "not settled", (unsigned)mount.fwdSamples(),
               (int)MOUNT_FWD_SAMPLES, mount.correlation(), (double)MOUNT_CORR_MIN);
      diag(b);
      diag("nothing saved. Keep driving and try again.");
      return;
    }
    pendingSave |= 2;
    diag("mounting accepted. It will be written to flash at the next standstill");
    diag("and will survive a reboot.");
    cmdStatus();
    return;
  }
  if (cmdIs(rest, "stop")) { mount.learnStop(); diag("thrown away."); return; }
#ifdef HUD_CAN
  // Both halves of this need the bus: standstill is what the gravity average
  // is gated on, and the speed derivative is what resolves which end of the
  // car the nose is. Without it the session would sit at "0 samples" for ever
  // and never say why.
  if (!canOk) {
    diag("no CAN bus, so there is no speed to learn the forward direction from.");
    diag("check the MCP2515 first -- type 'status'.");
    return;
  }
#else
  diag("this build has no CAN support, so the mounting cannot be learned.");
  return;
#endif
  mount.learnStart();
  diag("");
  diag("Mounting calibration restarted from scratch.");
  diag("");
  diag("You do not normally need to type this. The board works the mounting out");
  diag("by itself whenever it does not have one, and saves it the moment it is");
  diag("sure -- this is only here for when you move the sensor and want it");
  diag("forgotten now rather than at the next standstill.");
  diag("");
  diag("What it is waiting for, either way:");
  diag("  1. A stop. Ten seconds of standing still gives it gravity, which is");
  diag("     which way up the sensor is.");
  diag("  2. Ordinary driving. A few straight pulls away from junctions and a");
  diag("     few normal brakes. No manoeuvre, nothing to aim at -- it reads the");
  diag("     speed off the CAN bus and watches which way the car pushes the");
  diag("     sensor. Just do not spend the whole time cornering.");
  diag("");
  diag("'status' shows it filling up. It saves itself.");
  diag("");
}
#endif

/** Act on one typed line. Anything not recognised gets the help. */
static void linkCommand(const char* line) {
  uint8_t n;
  if (!*line) return;
  if ((n = cmdIs(line, "help")) || (n = cmdIs(line, "?")))   { (void)n; cmdHelp(); return; }
  if (cmdIs(line, "status"))                                  { cmdStatus(); return; }
  if (cmdIs(line, "scan"))                                    { probeAll();  return; }
#ifdef HUD_MAG
  if ((n = cmdIs(line, "spin")))   { cmdSpin(line + n);  return; }
  if ((n = cmdIs(line, "mount")))  { cmdMount(line + n); return; }
  if (cmdIs(line, "forget")) {
    pendingSave |= 4;
    diag("both calibrations will be erased at the next standstill.");
    return;
  }
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
      stageGeom();
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
      if (mag.present) { mag.calStart(); sendLine("MAGCAL,1,0"); }
      else                 sendLine("MAGCAL,0,0");
    }
    else if (r == HUD_MAG_CAL_OFF) {
      lastFrameMs = now;
      // The result is only kept if the drive actually swept a circle -- see
      // HudMag::calFinish. A calibration taken while parked would centre the
      // offset on wherever the car happened to be pointing, which is worse
      // than none at all because it looks like it worked.
      const bool ok = mag.calFinish();
      if (ok) saveMagCal();
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
    if (r != HUD_NOTHING) tmp = cur;    // resync the scratch copy after each frame
  }
  return got;
}

#endif  // HUD_LINK_H
