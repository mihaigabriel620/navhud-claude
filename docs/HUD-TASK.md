# Task: HUD firmware sweep + roundabout redesign (owner request, 2026-09-25)

Owner's words: sweep the HUD code "the same way you did the app and make it
perfect", and fix the roundabout: today it shows one small ring, the exit
number and a single thin arrow stub (see `make render` → the roundabout
images). Use the icons in `docs/reference-icons/` (Waze / Google Maps) as
inspiration and "make it good". Owner chose **path + ALL exit stubs**.
Explain everything plainly (the owner is not a developer). Read `CLAUDE.md`
first (rules, the "HUD firmware" section, main-only git, CI auto-release).
Report usage/credits at the start and end. Delete this file when done.

## Known findings
1. Dash theme: the PS field clears 106 px from x=257 and erases the "V" of the
   battery voltage.
2. E60 theme: the km/h label's box clips the bottom 2 rows of the speed digits.
3. `$CAR` speed is sent even when the CAN speed frame 0x1A6 is stale
   (`NavHud.ino` ~463, `hud_link.h` ~118); the app works around it.
4. Host tests stale since the 2.x rewrite: `can*`, `mount`, `sketch_*` need
   Arduino.h and the removed `hud_mount.h` / `hud_imu.h`; stage 0 needs python3.
5. IRAM at 94 %: add no new IRAM_ATTR code.

## Plan
A. **Audit** (read-only, ≤ 2 agents), like the app sweep: protocol parser and
   every frame, CAN decode and staleness, repaint/delta logic and flicker,
   zone overlaps, compass, backlight/key state, NO LINK timeout, memory and
   stack, no delay()/blocking, watchdog, millis() overflow — and that what the
   HUD shows matches what the app sends (`$RAB` uses Mapbox banner `degrees`
   since app 1.31).
B. **Tests + CI**: make `make check` fully green; add
   `.github/workflows/firmware.yml` running `make check` + `make render`
   (upload the PNGs as an artifact) + an arduino-cli ESP8266 compile (core
   esp8266:esp8266 3.1.2, TFT_eSPI 2.5.43 with `arduino/config/User_Setup.h`
   copied into the library, mcp_can 1.5.1). A cloud machine may be unable to
   download the core; CI can.
C. **Roundabout — the owner's exact picks are in `docs/reference-icons/owner-picks/`.**
   `00-current-hud-roundabout-photo.jpg` is a photo of the real HUD today:
   the ring looks **dotted/speckled with black dots**, a boxed "2" in the
   middle and a thin arrow — "ugly, not polished". `01…16-liked.png` are the
   styles the owner likes (Google Maps): a smooth thick ring where the part
   of the ring you drive is bold and the rest faded, a big clean exit arrow,
   and "progress ring" variants where the exit's sector of the ring is
   highlighted. Their white/grey colours do not fit: convert to the HUD
   theme — driven path and arrow in the theme's bright amber, the rest of the
   ring in a dim amber, on black (and the E60 theme's own palette). Draw it
   smooth: TFT_eSPI 2.5.x has anti-aliased primitives (drawSmoothArc,
   drawArc, drawWideLine/drawWedgeLine, fillSmoothCircle — verify in 2.5.43)
   instead of whatever produces the dots today (find and explain the cause);
   the renderer stub must support whatever you use so the PNGs are faithful.
   Mind the ESP8266: no full-screen sprite (RAM), no new IRAM code.
   Google-style details: thick ring; the driven path bold
   (entry → around the ring → exit), the rest faded; a big exit arrow at the
   real angle; the exit number; **stubs for every other exit**; mirrored for
   left-hand traffic; both themes; stays inside its zone (layout tests).
   The app must send all exit angles: derive them from the Mapbox route's
   intersections around the roundabout (bearings + in/out indices — verify the
   semantics in the Mapbox Directions docs) and send a new frame, e.g.
   `$RBX,<exit>,<angle1>,<angle2>,…` relative to the entry road, paired with the
   exit number like `$RAB` so a stale frame is never drawn on the next
   roundabout. Keep older frames backward compatible; update `PROTOCOL.md`.
   App side: `MapboxProvider`, `HudFrame`, `HudService`, with unit tests on JSON
   shaped like the documented response; the app's `ManeuverView` should match.
D. **Fix** every real finding, one at a time, each with a test. Render
   before/after images and show the owner the key ones (roundabouts with
   3/4/5/7 exits; left/right/straight/U-turn; left-hand traffic; the fixed
   dash/e60 screens) and get their OK on the look.
E. **Release**: app 1.32 (bump versionName, `## App 1.32` in CHANGELOG; CI
   publishes it) and a firmware `## 2.8` section in CHANGELOG. The owner
   flashes the board later on their PC — never claim it was flashed. If the
   session runs on its own branch, fast-forward `main` to it and push `main`.
