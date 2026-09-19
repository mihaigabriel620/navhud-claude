// ---------------------------------------------------------------------------
//  hud_state.h -- everything the HUD knows, in one place.
//
//  These are the variables the modules share. They live here rather than in
//  the sketch so that the modules can be included in any sensible order and
//  still see them, and so that "who else touches this?" is a search in one
//  file rather than a thousand-line scroll.
//
//  Include order matters and is not negotiable: this has to come after the
//  data types it instantiates (HudCanvas, CarState, HudCompass) and before
//  every module that reads them -- the themes read `car` and `tft` directly.
// ---------------------------------------------------------------------------
#ifndef HUD_STATE_H
#define HUD_STATE_H

// ---- the panel -------------------------------------------------------------
extern TFT_eSPI  rawTft;         // the driver
extern HudCanvas tft;            // ...and the mirror/keystone layer over it

// ---- the car ---------------------------------------------------------------
extern CarState car;             // the theme reads this directly
#ifdef HUD_CAN
extern bool     canOk;           // the MCP2515 answered at boot
extern uint32_t lastCanReportMs;
#endif

// ---- the phone -------------------------------------------------------------
extern HudParser parser;
extern HudState  cur;            // what the phone last told us
extern HudState  shown;          // what is actually on the glass
extern uint32_t  lastFrameMs;    // when the phone last said anything
extern bool      linkUp;
extern bool      everDrew;
extern uint8_t   screenNow;      // the HudScreen currently drawn

// ---- the backlight ---------------------------------------------------------
extern uint8_t   backlightNow;

// ---- screen geometry -------------------------------------------------------
extern Geom      savedGeom;      // what is currently in flash, for comparison
extern bool      geomTest;
extern bool      geomDirty;
// Set when the geometry changed under whatever is on the glass, so the block
// that owns "what should be on the screen" repaints it. applyGeom() used to
// decide that itself, which meant two owners and a stale frame: dismiss the
// alignment pattern after the cable has been out for two minutes and it would
// repaint a full drive display -- speed, limit and all -- from the state that
// was live before the pattern went up, then correct itself to NO LINK on the
// same pass. A tenth of a second of a stale speed limit on the glass is
// exactly what the NO LINK screen exists to prevent.
extern bool      geomRepaint;
extern uint32_t  geomLastPaintMs;
extern uint32_t  geomLastMsgMs;

// ---- the compass -----------------------------------------------------------
#ifdef HUD_MAG
extern HudCompass compass;
extern uint32_t   lastMagSendMs;
/**
 * A calibration waiting to be written.
 *
 * Deferred rather than written where it is asked for. Typed commands are
 * handled from inside the serial read loop, and EEPROM.commit() on an ESP8266
 * erases and rewrites a whole 4 KB flash sector with interrupts off -- 30 to
 * 45 ms typically and up to 400 ms by the datasheet. Nothing fills the UART
 * receive buffer in that window, so everything the phone sent during it is
 * gone, and a $CAM or $LANE clearing frame lost there is lost for good because
 * both are edge-triggered. So the flag is set there and the write happens from
 * loop(), at a standstill.
 */
extern bool       magSavePending;
extern bool       magForgetPending;
#endif

// ---------------------------------------------------------------------------
//  The definitions. HUD_STATE_OWNER is defined by exactly one translation
//  unit -- the sketch -- immediately before including this. Everything else
//  gets the externs above.
//
//  A sketch is a single translation unit, so plain definitions here would work
//  today. The guard is here because the host test compiles the sketch as one
//  of several .cpp files, and a second one picking this up would be a
//  duplicate-symbol link error nobody would enjoy diagnosing.
// ---------------------------------------------------------------------------
#ifdef HUD_STATE_OWNER
TFT_eSPI  rawTft = TFT_eSPI();
HudCanvas tft(rawTft);

CarState  car;
#ifdef HUD_CAN
bool      canOk = false;
uint32_t  lastCanReportMs = 0;
#endif

HudParser parser;
HudState  cur, shown;
uint32_t  lastFrameMs = 0;
bool      linkUp = false, everDrew = false;
uint8_t   screenNow = 0;         // SCREEN_NOTHING

// 255, not 0: applyBacklight() skips a write that matches what it believes is
// already on the pin, and setup() writes the real starting value explicitly.
uint8_t   backlightNow = 255;

Geom      savedGeom;
bool      geomTest = false;
bool      geomDirty = false;
bool      geomRepaint = false;
uint32_t  geomLastPaintMs = 0, geomLastMsgMs = 0;

#ifdef HUD_MAG
HudCompass compass;
uint32_t   lastMagSendMs = 0;
bool       magSavePending = false;
bool       magForgetPending = false;
#endif
#endif  // HUD_STATE_OWNER

#endif  // HUD_STATE_H
