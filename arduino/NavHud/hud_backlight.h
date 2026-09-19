// ---------------------------------------------------------------------------
//  hud_backlight.h -- the panel is lit by the key, and by nothing else.
//
//  THE RULE
//
//  0x130 byte 0 above 0x40 means the key is in the barrel. The panel comes up
//  when the key goes in and goes dark when it comes out. Not on activity, not
//  on a timer, not on the phone -- the key.
//
//  WHY THERE IS NO STALENESS TEST, WHICH IS THE PART THAT LOOKS LIKE A BUG
//
//  The obvious code says "if we have not heard from the bus for a while,
//  something is wrong, so light the panel". That is exactly backwards here.
//  K-CAN keeps chattering for about five minutes after the car is locked and
//  then sleeps, and the USB that powers this board is on the radio, which
//  stays up in standby. So silence is the NORMAL end state of every parked
//  car, and a fallback to lit means the HUD glows at an empty car park all
//  night. Silence must change nothing at all: the last known key state stands.
//
//  WHY A DEAD CONTROLLER IS DIFFERENT FROM A KEY THAT IS OUT
//
//  If the MCP2515 never came up, 0x130 never arrives, so the key state is not
//  "out" -- it is unknown, for ever. Treating that as "out" produced a black
//  screen on a board whose display, compass and serial link all worked
//  perfectly, and a fault that disguises itself as a dead display is the worst
//  thing a diagnostic surface can do. So no controller means LIT, and setup()
//  says so in words on the serial line so it does not look like a bug.
//  CAN_DEAD_MEANS_DARK takes the other trade.
//
//  Note the asymmetry is safe: canOk is only false when bring-up FAILED. A
//  working controller on a bus that has gone to sleep has canOk true and
//  displayOn false, and still goes dark -- which is the whole point above.
// ---------------------------------------------------------------------------
#ifndef HUD_BACKLIGHT_H
#define HUD_BACKLIGHT_H

#include <Arduino.h>
#include "hud_pins.h"
#include "hud_config.h"

/**
 * Write the pin, and only when the value actually changes.
 *
 * analogWrite() at 0 or at full scale does not start a waveform -- the core
 * checks for those two cases and calls digitalWrite() instead -- so the common
 * states cost no timer interrupts at all. That matters because the PWM
 * generator on this chip runs from a NON-MASKABLE interrupt, which no critical
 * section can hold off, and the I2C bus is bit-banged in software.
 */
static inline void applyBacklight(uint8_t v) {
#if PIN_BACKLIGHT >= 0
  if (v == backlightNow) return;
  backlightNow = v;
  analogWrite(PIN_BACKLIGHT, v);
#else
  (void)v;
#endif
}

/** Should the panel be lit at all? */
static inline bool backlightLit() {
#if defined(HUD_BENCH)
  return true;                       // a desk has no key; see hud_config.h
#elif defined(HUD_CAN)
  if (!canOk) {
  #ifdef CAN_DEAD_MEANS_DARK
    return false;
  #else
    return true;
  #endif
  }
  return car.displayOn;              // last known key state, latched
#else
  return true;                       // no CAN compiled in: nothing to follow
#endif
}

/** What it should be at power-on, before any frame has arrived. */
static inline uint8_t backlightAtBoot() {
#if defined(HUD_BENCH) || !defined(HUD_CAN)
  return BACKLIGHT_DAY;
#else
  // DARK, and not backlightLit(), even though the two look interchangeable.
  //
  // backlightBegin() runs long before canBegin() -- the panel has to be alive
  // early so there is something to look at while everything else starts -- so
  // at this moment canOk is false simply because nothing has tried yet. Asking
  // backlightLit() here reads that "not tried yet" as "the controller is dead"
  // and boots the panel lit on every car, which is the parked-car problem
  // coming back in through a side door.
  //
  // Dark is right: no 0x130 has arrived, so the key is unknown. The first pass
  // of loop() calls backlightUpdate(), which by then knows whether the
  // controller actually came up, and lights the panel if it did not.
  return 0;
#endif
}

/**
 * Set the pin up.
 *
 * analogWrite() is called directly rather than through applyBacklight(), which
 * early-returns when the value already matches what it believes is on the pin
 * -- and at boot it believes wrongly, because backlightNow starts at 255 so
 * that the first real write is never skipped.
 */
static void backlightBegin() {
#if PIN_BACKLIGHT >= 0
  #if defined(ARDUINO_ARCH_ESP8266)
    // Pin the range. The core defaulted to 0..1023 before 3.0 and 0..255 after,
    // so without this the constants above mean different brightnesses on
    // different core versions.
    analogWriteRange(255);
  #endif
  pinMode(PIN_BACKLIGHT, OUTPUT);
  backlightNow = backlightAtBoot();
  analogWrite(PIN_BACKLIGHT, backlightNow);
#endif
}

/**
 * One pass. phoneUp and gotFrame only choose brightness, never whether the
 * panel is lit -- that is the key's job alone.
 */
static void backlightUpdate(bool phoneUp, bool gotFrame) {
  if (!backlightLit()) {
    applyBacklight(0);
  } else if (phoneUp && gotFrame) {
    applyBacklight((cur.flags & FLAG_NIGHT) ? BACKLIGHT_NIGHT : BACKLIGHT_DAY);
  } else if (backlightNow == 0) {
    // Coming back from dark with no phone frame to say day or night: daylight,
    // because too bright is a squint and too dim is unreadable.
    applyBacklight(BACKLIGHT_DAY);
  }
}

#endif  // HUD_BACKLIGHT_H
