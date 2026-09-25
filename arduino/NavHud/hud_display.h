// ---------------------------------------------------------------------------
//  hud_display.h -- what appears on the glass, and when.
//
//  If the screen is showing the wrong thing, this is the file. There are three
//  inputs and they are all booleans:
//
//     phone   a $HUD frame within LINK_TIMEOUT_MS
//     car     the MCP2515 came up AND the key is in (0x130)
//     route   the phone set FLAG_ROUTE, meaning it is following an itinerary
//
//  and the six states they make:
//
//     phone  car  route |  on the glass
//     ------------------+--------------------------------------------------
//       -     -     -   |  NO LINK. On the car the panel is dark anyway --
//                       |  no CAN means no key signal means backlight off.
//       -     y     -   |  car only: speed, tacho, PS, volts
//       y     -     -   |  speed (GPS) + limit. No arrow. Car band empty.
//       y     -     y   |  the above plus arrow, distance, street, ETA
//       y     y     -   |  speed (CAN) + limit + tacho, PS, volts. No arrow.
//       y     y     y   |  all of it
//
//  Two rules make that table true, and they are deliberately in different
//  places:
//
//   1. WHICH SCREEN -- displayWanted() below. One function, no other caller
//      gets a vote.
//   2. WHETHER THERE IS A ROUTE -- displayRouteRule(), applied once, to the
//      frame, as it arrives. Everything downstream then sees a frame that
//      simply has no maneuver in it, so no theme can draw one by accident.
//
//  Rule 2 replaces an inference. The HUD used to work out "there is a route"
//  from "the maneuver is not MAN_NONE", which is a guess dressed as a fact,
//  and it guessed wrong in the one case that mattered: open the app with no
//  destination and a straight-ahead arrow appeared on a parked car. Deriving
//  it from a bit the phone sets explicitly means the failure cannot recur, and
//  a phone too old to send the bit produces no arrow rather than a wrong one.
// ---------------------------------------------------------------------------
#ifndef HUD_DISPLAY_H
#define HUD_DISPLAY_H

enum HudScreen : uint8_t {
  SCREEN_NOTHING = 0,   // nothing has been drawn yet this boot
  SCREEN_BOOT,          // the boot splash, still inside its grace period
  SCREEN_ALIGN,         // the keystone pattern owns the glass
  SCREEN_NOLINK,        // no phone and no car: say so, show nothing stale
  SCREEN_CAR,           // the car half only
  SCREEN_DRIVE          // the phone's layout, with or without a route in it
};

/**
 * How long the boot splash holds before NO LINK is allowed to replace it.
 *
 * On a normal start the board is up a second or two before the phone has
 * opened the port. Without this, every single boot flashes "check the USB
 * cable" at a cable that is perfectly fine and about to work.
 */
#define DISPLAY_BOOT_GRACE_MS 4000UL

/**
 * Is the car half worth showing?
 *
 * The key, not the engine. `displayOn` is the same signal the backlight uses
 * (hud_backlight.h), so the panel being lit and the car band being on screen
 * are the same condition -- there is no state where the key lights the glass
 * and the glass then says NO LINK at a car that is plainly talking to us.
 *
 * At key position 1 with the engine off that means a tacho reading zero and a
 * battery voltage, which is exactly the pair you want at that moment.
 */
static inline bool displayCarLive() {
#ifdef HUD_CAN
  // Built with CAN, so the car screen is what this display IS. It does not
  // wait for the bus to prove itself first.
  //
  // It used to require canOk and a key signal, on the reasoning that a screen
  // full of blanks says nothing. But the alternative it fell back to was "NO
  // LINK -- check the USB cable", which says something WRONG: it blames a cable
  // that is fine, for a bus that is the actual problem, at a driver who only
  // wanted to see their speed. A tacho with no needle and a blank speed is an
  // honest picture of a bus that is not talking; the serial line and 'status'
  // say why, and neither of them is on the windscreen.
  return true;
#else
  return false;
#endif
}

/** Which screen should be up. The only place this is decided. */
static inline HudScreen displayWanted(bool phoneUp, uint32_t now) {
  if (geomTest) return SCREEN_ALIGN;      // somebody is aiming the panel
  if (phoneUp)  return SCREEN_DRIVE;      // the phone wins whenever it is there

  // The car screen outranks the boot grace, and the ordering of these two
  // lines is the whole question.
  //
  // The grace exists for one reason: not to flash "NO LINK -- check the USB
  // cable" at a cable that is fine and about to work, in the second or two
  // before the phone opens the port. That is an argument for waiting when
  // there is NOTHING ELSE TO SHOW. It is not an argument for sitting on a
  // splash while the bus is right there saying 47 km/h.
  //
  // It was the other way round, on the reasoning that showing the car screen
  // first costs an extra full repaint inside the window where the phone is
  // connecting -- about 123 ms of blocking SPI at 20 MHz. True, but it buys
  // that repaint back only when the phone connects inside four seconds, and it
  // pays for it every single time the phone is not there at all: the driver
  // gets four seconds of splash and then a complaint about USB, with a
  // perfectly good CAN bus behind it.
  if (displayCarLive()) return SCREEN_CAR;
  if (!everDrew && now < DISPLAY_BOOT_GRACE_MS) return SCREEN_BOOT;
  return SCREEN_NOLINK;
}

/**
 * Forget everything the phone told us, because it is no longer there.
 *
 * Two separate jobs. `shown` is reset so that whatever is drawn next is drawn
 * in full rather than as a delta against a screen that has since been painted
 * over -- speed is forced to an impossible value for exactly that reason.
 *
 * The camera and lane fields are cleared out of `cur`, not `shown`, and that
 * is the subtle half. Both are edge-triggered: the phone only sends CAM,0,0,0
 * or LANE,0,0 at the moment they go away. Lose the cable while a camera bar is
 * up, drive past the camera during the outage, and the clearing frame goes
 * into a dead wire -- so when the link came back, the full repaint put a
 * warning for a camera already behind you back on the glass, and nothing would
 * ever have taken it off again.
 */
static inline void displayForgetPhone() {
  hudStateInit(shown);
  shown.speed = -999;
  // All of `cur`, not only the camera and lanes. The link comes back on ANY
  // message -- a heartbeat, the Keystone screen's $GEOM? -- and that repaints
  // the drive layout from `cur` straight away, before a $HUD frame has said
  // anything. Keeping the rest put the last trip's speed limit, turn and
  // street back on the glass for up to a frame. The phone resends $HUD, $CAM
  // and $RAB every tick, and (app 1.32) the lanes while they apply.
  hudStateInit(cur);
}

/**
 * Strip the route half out of a frame that has no route behind it.
 *
 * Applied to the frame, once, at the moment it is accepted -- not at each
 * place that draws. Two owners of one rule is how the alignment-pattern
 * repaint bug happened, and a rule about what may appear on a windscreen is
 * not one to hold in two minds.
 *
 * The speed limit, the speed and the camera and lane warnings all survive:
 * none of them needs an itinerary to be true. A speed limit is a property of
 * the road you are on, which is why the free-drive tracker sends one.
 */
static inline void displayRouteRule(HudState& s) {
  if (s.flags & FLAG_ROUTE) return;
  s.maneuver   = MAN_NONE;
  s.rbExit     = 0;
  s.distToMan  = 0;
  s.etaSeconds = 0;
  s.remaining  = 0;
  s.street[0]  = '\0';
  // OFF_ROUTE and ARRIVED are statements about a route. Without one they are
  // left over from the last one, and a stale "arrived" is a lit marker on the
  // glass for a trip that finished yesterday.
  s.flags &= (uint8_t)~(FLAG_OFF_ROUTE | FLAG_ARRIVED);
}

/**
 * Get the glass from whatever is drawn now to the screen displayWanted() asks
 * for. The split is deliberate: the rule is a thing you read, the transition
 * is a thing you debug.
 *
 * SCREEN_ALIGN and SCREEN_BOOT are both "leave the glass alone" -- the
 * alignment pattern is drawn by applyGeom(), and the boot splash was drawn by
 * setup() and is inside its grace period. Frames still arrive and still update
 * `cur` underneath either of them, so dismissing the pattern brings the drive
 * display straight back, and the backlight and the compass still run, because
 * the car is still a car while somebody is aiming the screen at the
 * windscreen.
 *
 * Called after backlightUpdate(), and that order is load-bearing: see the
 * note at the call in loop().
 */
static void displayUpdate(bool up, bool got, uint32_t now) {
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
      // Darkening first (backlightUpdate) is what makes the paint free instead.
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
}

#endif  // HUD_DISPLAY_H
