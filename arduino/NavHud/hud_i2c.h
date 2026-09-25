// ---------------------------------------------------------------------------
//  hud_i2c.h -- the two-wire bus: bring it up, and get it back when it hangs.
//
//  The compass and the MPU share it, each through its own library (GY521 and
//  Adafruit QMC5883P). This file only owns the bus itself.
//
//  I2C sits on GPIO0 and GPIO2 here, which are both boot-strap pins. That is
//  safe in normal running -- the Arduino core's Wire is a software bit-bang
//  that emulates open drain honestly, releasing the line and letting the
//  pull-up raise it rather than ever driving it high -- so a quiet bus is a
//  high bus, which is exactly what the straps want.
//
//  It stops being safe when the bus is NOT quiet at the moment of a reset:
//
//    GPIO0 (SDA) low at reset  -> the ROM drops into the serial bootloader and
//                                 your sketch never runs [esptool boot modes].
//    GPIO2 (SCL) low at reset  -> no valid boot mode exists at all.
//
//  A slave that lost sync mid-byte and is holding SDA down therefore does not
//  just break the compass; it stops the board from booting, every time, until
//  the slave is power-cycled. That is why i2cRecover() below exists and why it
//  runs BEFORE Wire.begin() rather than after something has already failed.
//
//  Speed is 100 kHz, not 400. The bus is bit-banged, its timing comes from
//  software delay loops, and analogWrite() on this chip drives its waveform
//  from a NON-MASKABLE interrupt -- noInterrupts() cannot hold it off. An NMI
//  landing mid-bit stretches the clock, which I2C tolerates by design, but the
//  margin is four times wider at 100 kHz. That is a trade worth taking in a
//  car. Adafruit's library calls Wire.begin() again, with no pins; on this
//  core that reuses the pins and the clock set here (Wire.cpp, core 3.1.2).
// ---------------------------------------------------------------------------
#ifndef HUD_I2C_H
#define HUD_I2C_H

#include <Arduino.h>
#include <Wire.h>
#include "hud_pins.h"

#ifndef HUD_I2C_HZ
  #define HUD_I2C_HZ  100000
#endif

/**
 * Free a bus that a slave is holding down.
 *
 * The standard procedure, and it is in the I2C specification rather than
 * invented here: with SDA held low by a slave that is mid-transfer, clock SCL
 * up to nine times. Nine because a byte is eight bits plus an ACK, so nine
 * edges is enough to walk any slave off the end of whatever it thought it was
 * sending. Then issue a STOP -- SDA rising while SCL is high -- to put it back
 * in its idle state.
 *
 * Both lines are driven the way Wire drives them: LOW means enable the output
 * driver, HIGH means let go and let the pull-up do it. Never drive high. On
 * these two pins in particular, driving high would fight the board's own 12k
 * pull-ups and, worse, would be a push-pull output on a strap pin.
 *
 * Returns true if the bus ended up idle with both lines high.
 */
static bool i2cRecover() {
  pinMode(PIN_I2C_SCL, INPUT_PULLUP);
  pinMode(PIN_I2C_SDA, INPUT_PULLUP);
  delayMicroseconds(10);

  if (digitalRead(PIN_I2C_SDA) == HIGH && digitalRead(PIN_I2C_SCL) == HIGH) {
    return true;                                  // already idle, nothing to do
  }

  // Nine clocks. Open-drain throughout: OUTPUT+LOW to pull down, INPUT_PULLUP
  // to release.
  for (uint8_t i = 0; i < 9; i++) {
    pinMode(PIN_I2C_SCL, OUTPUT);
    digitalWrite(PIN_I2C_SCL, LOW);
    delayMicroseconds(5);
    pinMode(PIN_I2C_SCL, INPUT_PULLUP);
    delayMicroseconds(5);
    if (digitalRead(PIN_I2C_SDA) == HIGH) break;  // the slave let go
  }

  // STOP: SDA low, SCL high, then SDA released.
  pinMode(PIN_I2C_SDA, OUTPUT);
  digitalWrite(PIN_I2C_SDA, LOW);
  delayMicroseconds(5);
  pinMode(PIN_I2C_SCL, INPUT_PULLUP);
  delayMicroseconds(5);
  pinMode(PIN_I2C_SDA, INPUT_PULLUP);
  delayMicroseconds(5);

  return digitalRead(PIN_I2C_SDA) == HIGH && digitalRead(PIN_I2C_SCL) == HIGH;
}

/** What recover() found, so setup() and `status` can report it. */
static bool i2cWasStuck = false;

/** Bring the bus up. Call once, before anything on it is touched. */
static void i2cBegin() {
  i2cWasStuck = !i2cRecover();
#if defined(ARDUINO_ARCH_ESP8266) || defined(ESP32)
  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL);
#else
  Wire.begin();
#endif
  Wire.setClock(HUD_I2C_HZ);
}

#endif  // HUD_I2C_H
