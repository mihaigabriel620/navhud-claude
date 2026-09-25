// ---------------------------------------------------------------------------
//  hud_sensors.h -- the compass and the MPU, run from setup() and loop().
//
//  Everything on the I2C bus from the sketch's side: bringing both chips up,
//  reading each in turn, looking again for one that stopped answering, and
//  sending $MAG and $IMU. The chips are hud_compass.h and hud_motion.h; what
//  their numbers mean is hud_heading.h.
//
//  If the heading or the turn rate is missing from the cable, or a chip that
//  is plainly there is reported absent, it is this file.
//
//  ONE CHIP PER PASS. A compass read is about 1.2 ms of bus, an MPU read about
//  1.7, and the MCP2515's two receive buffers hold about 2.2 ms of a busy
//  K-CAN. Both in one pass would lose CAN frames; one per pass, with the loop
//  draining the controller in between, does not.
// ---------------------------------------------------------------------------
#ifndef HUD_SENSORS_H
#define HUD_SENSORS_H

/** Bring the bus and both chips up, and say what answered. setup() only. */
static void sensorsBegin() {
  i2cBegin();
  if (i2cWasStuck) diag("  i2c     : bus was held low at boot; clocked free");

  // Three attempts at each, not one. The first transaction of a board's life
  // happens while the 3V3 rail is still settling, and a chip that NAKs once at
  // that moment used to be written off for the rest of the run.
  // One line per chip when it is fine; the reason when it is not. `status`
  // has the rest.
  char b[120];
  bool ok = false;
  for (uint8_t i = 0; i < 3 && !(ok = compass.begin(true)); i++) delay(30);
  if (!ok) {
    // Say what the BUS says before the driver gets a vote. "no compass found"
    // covers a dead bus, a chip at the wrong address and a chip that answers
    // with the wrong ID, and those are three different faults.
    uint8_t id = 0;
    const bool acked = compass.probe(id);
    snprintf(b, sizeof b, "  compass : %s -- 0x2C %s, chip id 0x%02X (want 80)%s",
             compass.describe(), acked ? "ACKed" : "did NOT ack", (unsigned)id,
             acked ? "" : ": check SDA D3, SCL D4, 3V3");
    diag(b);
  } else {
    // A configured chip that never finishes a measurement is the most
    // confusing failure there is, so boot waits for one real sample.
    float m[3];
    bool seen = false;
    for (uint8_t i = 0; i < 40 && !(seen = compass.read(m)); i++) delay(10);
    if (seen) heading.mag(m);
    snprintf(b, sizeof b, "  compass : %s, +-%u G%s", compass.describe(),
             (unsigned)compass.rangeG, seen ? "" : " -- NO DATA: configured but silent");
    diag(b);
  }
  // Whether or not the compass answered: a compass found later on a retry uses
  // it straight away.
  loadCompassCal();

  ok = false;
  for (uint8_t i = 0; i < 3 && !(ok = motion.begin()); i++) delay(30);
  snprintf(b, sizeof b, "  mpu     : %s%s", motion.describe(),
           ok ? "" : " (optional: the heading then assumes the box is level)");
  diag(b);
}

/**
 * One loop pass: at most one chip is read. `speedMps` is the car's speed from
 * the bus, or negative when there is no bus to ask.
 */
static void sensorsUpdate(uint32_t now, float speedMps) {
  static uint32_t retryAt = 0, mpuAt = 0, mpuOkAt = 0, magAt = 0, magSentAt = 0, imuDueAt = 0;

  // Not found is not a verdict. A chip that browned out -- it comes back asleep
  // or in suspend, and read() notices -- or one plugged in afterwards starts
  // working again without a reboot. A missing chip costs one address probe.
  if ((!compass.present() || !motion.present()) && now - retryAt >= MAG_RETRY_MS) {
    retryAt = now;
    char b[96];
    if (!compass.present() && compass.begin(false)) {
      snprintf(b, sizeof b, "  compass : %s (found on a retry)", compass.describe());
      diag(b);
    }
    if (!motion.present() && motion.begin()) {
      snprintf(b, sizeof b, "  mpu     : %s (found on a retry)", motion.describe());
      diag(b);
    }
    return;
  }

  // Both due -- after a long repaint, say -- and the one read longest ago goes
  // first, so a slow loop shares itself out instead of starving the compass.
  const bool mpuDue = motion.present() && now - mpuAt >= SENSOR_READ_MS;
  const bool magDue = compass.present() && now - magAt >= SENSOR_READ_MS;

  if (mpuDue && (!magDue || (int32_t)(mpuAt - magAt) <= 0)) {
    mpuAt = now;
    float a[3], g[3];
    if (!motion.read(a, g)) return;
    heading.motion(a, g, (float)(now - mpuOkAt) / 1000.0f, speedMps);
    mpuOkAt = now;
    // On a fixed schedule rather than "50 ms since the last one": reads come
    // every 20-25 ms, so the latter would send every third, 15 Hz. This way
    // the intervals alternate and average IMU_SEND_INTERVAL_MS. Each line
    // carries the average rate over its own interval, so they need not be
    // equal.
    float rate;
    if ((int32_t)(now - imuDueAt) >= 0 && heading.takeRate(rate)) {
      imuDueAt += IMU_SEND_INTERVAL_MS;
      if ((int32_t)(now - imuDueAt) >= 0) imuDueAt = now + IMU_SEND_INTERVAL_MS;  // fell behind
      sendImu(rate);
    }
    return;
  }

  if (magDue) {
    magAt = now;
    float m[3];
    // Sent only while the compass is genuinely reporting: a line that says 0.0
    // every 200 ms because the chip is absent is worse than silence -- the app
    // cannot tell it from actually pointing north.
    if (compass.read(m) && heading.mag(m) && now - magSentAt >= MAG_SEND_INTERVAL_MS) {
      magSentAt = now;
      sendMag();
    }
  }
}

#endif  // HUD_SENSORS_H
