// ---------------------------------------------------------------------------
//  hud_backlight.h -- is the panel lit, and how brightly.
//
//  Two questions, always in that order, and they have different owners:
//
//    lit at all?  -> the key, off CAN 0x130. Nothing else votes.
//    how bright?  -> the phone's night flag, when there is a phone.
//
//  If the panel is dark when it should not be, or lit when it should not be,
//  this file is the whole story.
// ---------------------------------------------------------------------------
#ifndef HUD_BACKLIGHT_H
#define HUD_BACKLIGHT_H

/** Drive the pin. Skips a write that matches what is already there, because
    analogWrite() on a pin that is already at that duty still costs a register
    write and this is called on every pass of the loop. */
static void applyBacklight(uint8_t v) {
#if BACKLIGHT_PIN >= 0
  if (v == backlightNow) return;
  backlightNow = v;
  analogWrite(BACKLIGHT_PIN, v);
#else
  (void)v;
#endif
}

/**
 * Should the panel be lit at all?
 *
 * The key is the only input. 0x130 byte 0 above 0x40 is a key in the barrel
 * (see carIgnition_ in hud_car.h for why that threshold and not another), so
 * the panel comes up when the key goes in and goes dark when it comes out.
 *
 * Note there is no staleness test, which is the whole point and worth
 * spelling out because the obvious code has one. K-CAN keeps chattering for
 * about five minutes after the car is locked and then sleeps. An earlier
 * version treated that silence as "no data" and fell back to LIT, reasoning
 * about a CAN wire falling off -- so a parked car came back on roughly 800 ms
 * after the last frame and sat there glowing. The realistic silence is not a
 * broken wire, it is the bus asleep, and by then the last 0x130 we saw already
 * said the key was out. Latching that last known state is therefore both
 * simpler and correct.
 *
 * Board power comes off the head unit's USB, so the radio drops to standby a
 * few minutes after the car locks and takes the board with it. There is no
 * battery drain to protect against here; this is only about the glass not
 * glowing at an empty car park in the meantime.
 *
 * The consequence to know about: with HUD_CAN built in and the MCP2515
 * unplugged or dead, displayOn never becomes true and the panel stays black.
 * HUD_BENCH is the way out of that, and it is the same switch that already
 * un-mirrors the screen for desk work.
 */
static inline bool backlightLit() {
#if defined(HUD_BENCH)
  return true;                    // desk: lit, nothing else consulted
#elif defined(HUD_CAN)
  return car.displayOn;           // last known key state, latched
#else
  return true;                    // no CAN: nothing here knows about the key
#endif
}

/**
 * What the pin should read the moment setup() has configured it.
 *
 * Dark on a car build: no 0x130 has arrived yet, so the key state is not
 * "out", it is unknown -- and unknown is treated as out. The splash, the font
 * check and the $CAN lines are all still drawn underneath, just not lit; the
 * first frame with a key in the barrel brings the panel up.
 *
 * Bench and no-CAN builds boot lit, because neither can ever learn where the
 * key is and a panel that stays dark for ever is not a HUD.
 */
static inline uint8_t backlightAtBoot() {
#if defined(HUD_BENCH) || !defined(HUD_CAN)
  return BACKLIGHT_DAY;
#else
  return 0;
#endif
}

/** Called once from setup(), after pinMode(). Assigns backlightNow directly
    rather than going through applyBacklight(), which early-returns when the
    value already matches and would leave the pin at whatever pinMode() left
    it while claiming otherwise. */
static void backlightBegin() {
#if BACKLIGHT_PIN >= 0
  #if defined(ARDUINO_ARCH_ESP8266)
    // Pin the PWM range. The ESP8266 core defaulted to 0..1023 before 3.0 and
    // 0..255 after; without this the backlight constants mean different
    // brightnesses on different core versions.
    analogWriteRange(255);
  #endif
  pinMode(BACKLIGHT_PIN, OUTPUT);
  backlightNow = backlightAtBoot();
  analogWrite(BACKLIGHT_PIN, backlightNow);
#endif
}

/**
 * One pass. `phoneUp` and `gotFrame` come from the link; they only decide
 * brightness, never whether the panel is lit.
 */
static void backlightUpdate(bool phoneUp, bool gotFrame) {
  if (!backlightLit()) {
    applyBacklight(0);
  } else if (phoneUp && gotFrame) {
    applyBacklight((cur.flags & FLAG_NIGHT) ? BACKLIGHT_NIGHT : BACKLIGHT_DAY);
  } else if (backlightNow == 0) {
    // Coming back from dark with no phone frame to tell us day or night:
    // daylight, because too bright is a squint and too dim is unreadable.
    applyBacklight(BACKLIGHT_DAY);
  }
}

#endif  // HUD_BACKLIGHT_H
