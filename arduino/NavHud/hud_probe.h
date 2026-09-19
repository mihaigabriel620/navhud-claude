// ---------------------------------------------------------------------------
//  hud_probe.h -- what is actually on the wires.
//
//  Written because two separate buses went silent at once and every piece of
//  software involved was, by then, known to be correct: the MCP2515 driver had
//  been audited line by line against the datasheet, and a completely
//  independent library failed at the identical stage with its own error
//  message. When that happens, more code inspection is wasted time. The board
//  has to say which wire.
//
//  Each test below answers ONE question and is designed so its failure mode is
//  distinguishable from its neighbours'. "Not found" is not a diagnosis.
// ---------------------------------------------------------------------------
#ifndef HUD_PROBE_H
#define HUD_PROBE_H

/**
 * Can GPIO16 actually be driven?
 *
 * GPIO16 is not an ordinary pin. It is XPD_DCDC, and it lives in the RTC
 * domain rather than on the GPIO peripheral -- a different register block,
 * reached by different code in the core. Everything else on this board is a
 * normal pin, so it is the one that deserves to be proved rather than assumed.
 *
 * Drive it both ways and read it back. A pin that cannot be driven cannot
 * select a chip, and every downstream symptom would then be "nothing answers".
 */
static void probeCsPin() {
  char b[112];
  pinMode(CAN_CS_PIN, OUTPUT);
  digitalWrite(CAN_CS_PIN, HIGH); delayMicroseconds(50);
  const int hi = digitalRead(CAN_CS_PIN);
  digitalWrite(CAN_CS_PIN, LOW);  delayMicroseconds(50);
  const int lo = digitalRead(CAN_CS_PIN);
  digitalWrite(CAN_CS_PIN, HIGH);
  snprintf(b, sizeof b, "  cs pin  : GPIO%d reads %d when driven high, %d when driven low%s",
           (int)CAN_CS_PIN, hi, lo,
           (hi == 1 && lo == 0) ? "  -- ok" : "  <-- THE PIN IS NOT SWITCHING");
  diag(b);
  if (!(hi == 1 && lo == 0)) {
    diag("            Something is holding it. On a D1 mini, check nothing else");
    diag("            is on D0, and that the RST-to-D0 deep-sleep link is NOT");
    diag("            fitted -- that ties this pin to the reset line.");
  }
}

/**
 * What comes back on MISO when the controller is asked a question.
 *
 * The CANSTAT register after a reset reads 0x80, and only 0x80. So the raw
 * byte is a diagnosis in itself, and the three wrong answers mean three
 * different things:
 *
 *   0x00 every time  MISO is low and staying low. Not connected, or the module
 *                    has no power, or its ground is not shared with the ESP.
 *   0xFF every time  MISO is floating high. The wire is on the wrong pin, or
 *                    the chip is not driving it because CS never reached it.
 *   anything else    something IS driving the line but the bytes are wrong:
 *                    SPI too fast, mode wrong, or a bit is being shifted.
 *
 * Reading it four times separates a stuck line from an intermittent one, which
 * a single read cannot do.
 */
static void probeCanSpi() {
  char b[128];
  SPI.begin();
  SPI.beginTransaction(SPISettings(1000000, MSBFIRST, SPI_MODE0));   // slow on purpose
  uint8_t seen[4];
  for (uint8_t i = 0; i < 4; i++) {
    digitalWrite(CAN_CS_PIN, LOW);
    SPI.transfer(0x03);           // READ
    SPI.transfer(0x0E);           // CANSTAT
    seen[i] = SPI.transfer(0x00);
    digitalWrite(CAN_CS_PIN, HIGH);
    delay(2);
  }
  SPI.endTransaction();

  snprintf(b, sizeof b, "  can spi : CANSTAT reads %02X %02X %02X %02X at 1 MHz (want 80)",
           seen[0], seen[1], seen[2], seen[3]);
  diag(b);

  bool allSame = true;
  for (uint8_t i = 1; i < 4; i++) if (seen[i] != seen[0]) allSame = false;
  if (allSame && seen[0] == 0x00) {
    diag("            Stuck LOW. MISO is not reaching D6/GPIO12, or the module");
    diag("            has no 3V3, or its GND is not shared with the ESP.");
    diag("            Measure 3V3 at the module's own pin, not at the board.");
  } else if (allSame && seen[0] == 0xFF) {
    diag("            Floating HIGH. Nothing is driving MISO: the wire is on the");
    diag("            wrong pin, or CS is not reaching the module, so the chip");
    diag("            never answers. Check CS continuity from D0 to the module.");
  } else if (allSame && seen[0] == 0x80) {
    diag("            That is the right answer. If bring-up still failed, the");
    diag("            crystal is the remaining suspect -- SPI is clearly fine.");
  } else {
    diag("            Something is driving the line but the bytes are wrong.");
    diag("            Wiring is close: suspect SPI speed, or MOSI and MISO");
    diag("            swapped, or a long unshielded jumper on SCK.");
  }
}

/**
 * Every address on the I2C bus.
 *
 * The whole sweep rather than the two we expect, because "the QMC is not at
 * 0x2C or 0x0D" and "nothing at all is on this bus" are completely different
 * faults with completely different fixes, and a targeted probe cannot tell
 * them apart. A board silkscreened HMC5883L answering at 0x1E, or a VCM5883L
 * at 0x0C, would also show up here rather than as a mystery.
 */
static void probeI2c() {
  char b[112];
  uint8_t found = 0;
  char list[80]; list[0] = '\0';
  for (uint8_t a = 1; a < 0x78; a++) {
    Wire.beginTransmission(a);
    if (Wire.endTransmission() == 0) {
      found++;
      char one[8];
      snprintf(one, sizeof one, " %02X", a);
      if (strlen(list) < sizeof list - 5) strcat(list, one);
    }
    delay(1);
  }
  if (found) {
    snprintf(b, sizeof b, "  i2c scan: %u device(s) at%s", (unsigned)found, list);
    diag(b);
    diag("            0x68/0x69 an MPU, 0x2C a QMC5883P, 0x0D a QMC5883L,");
    diag("            0x1E an HMC5883L, 0x0C a VCM5883L.");
  } else {
    snprintf(b, sizeof b, "  i2c scan: NOTHING on the bus (SDA GPIO%d, SCL GPIO%d)",
             (int)HUD_IMU_SDA, (int)HUD_IMU_SCL);
    diag(b);
    diag("            Not one address acknowledged, so this is the bus itself,");
    diag("            not the sensors. In order of likelihood: no pull-ups on");
    diag("            SDA and SCL (4.7k to 3V3 -- most breakout boards have");
    diag("            them, a bare chip does not), 3V3 or GND not reaching the");
    diag("            module, or SDA and SCL swapped.");
    diag("            NOTE on a D1 mini: D1/GPIO5 and D2/GPIO4 are the usual I2C");
    diag("            pins but the DISPLAY has them (DC and backlight), so this");
    diag("            build moves I2C to D3/GPIO0 and D4/GPIO2. Wire to D3 and");
    diag("            D4, not to D1 and D2.");
  }
}

/** All of it, with a heading. Printed at boot when something is missing. */
static void probeAll() {
  diag("");
  diag("Hardware probe");
  probeCsPin();
  probeCanSpi();
  probeI2c();
  diag("");
}

#endif  // HUD_PROBE_H
