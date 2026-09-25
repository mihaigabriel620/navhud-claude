# Task: HUD firmware sweep + roundabout redesign (owner request, 2026-09-25)

## 0. Before anything — read these, in this order
1. `CLAUDE.md` — the owner's rules (main only, CI auto-release, short chat,
   usage, ≤ 2 agents) and the **"HUD firmware"** section (hardware, build,
   library versions, the User_Setup.h copy, IRAM 94 %).
2. **`docs/owner-skills/*.md` — the owner's 5 skills. Follow all of them**:
   expert-embedded-systems (zero collateral damage, no delay(), verify APIs,
   output only modified files), smart-simple-coding (KISS/YAGNI, reuse,
   re-read before editing), systematic-debugging (reproduce, hypothesis,
   one change at a time, revert failed attempts), concise-direct-execution
   (act, minimal chat), separate-for-debugging (one file per job — only split
   code that already works, never while fixing bugs).
3. This file, then `CHANGELOG.md` (top: App 1.27–1.31; firmware history in the
   `## 2.x` sections — 2.7 explains `$RAB`), `PROTOCOL.md`, and
   `docs/reference-icons/README.md`.

The owner is not a developer: explain in plain language, keep chat short,
show pictures. Report usage/credits at the start and end.

## 1. What the owner asked (their words)
"Sweep the HUD code the same way you did the app and make it perfect", and
fix the roundabout: "it shows only one exit", looks "ugly … black dots … not
polished". Use the icons they collected "as inspiration and make it good" —
but in the HUD's theme, not white. They chose **path + ALL exit stubs**.

## 2. Known findings (verified)
1. **Roundabout dots — cause confirmed:** `hud_arrows.h` `roundaboutArt()`
   (~line 93) draws the ring as concentric 1-px `drawCircle`s every 0.8 px
   (`for (r = ringR; r > ringR - w; r -= 0.8f)`); integer circles of
   neighbouring radii leave unfilled pixels → the black speckle in the photo
   `docs/reference-icons/owner-picks/00-current-hud-roundabout-photo.jpg`.
   The number sits in a box inside the ring; the exit is one thin arrow.
2. Dash theme: the PS field clears 106 px from x=257 and erases the battery "V".
3. E60 theme: the km/h label's box clips the bottom 2 rows of the speed digits.
4. `$CAR` speed is sent even when the CAN speed frame 0x1A6 is stale
   (`NavHud.ino` ~463, `hud_link.h` ~118); the app works around it.
5. Host tests stale since the 2.x rewrite: `can*`, `mount`, `sketch_*` need
   Arduino.h and removed `hud_mount.h` / `hud_imu.h`; stage 0 needs python3.
   Passing today: `t`, `geom`, `layout_e60`, `bringup_diag`.
6. IRAM at 94 %: no new IRAM_ATTR code. RAM: ~45 % of 80 KB used → roughly
   40 KB heap; a 16-bit sprite of the roundabout zone (~130×170 px ≈ 44 KB) does
   NOT fit — draw directly on black, or use a 1/4/8-bit sprite; check
   `ESP.getFreeHeap()` if you add any buffer.
7. `PROTOCOL.md` (v3) does not document `$RAB` yet (only `HudFrame.kt` comments
   and CHANGELOG 2.7) — document `$RAB` and the new frame.

## 3. Code map for the roundabout
- Firmware: `hud_arrows.h` — `roundaboutArt()` (~93), `rabBearing()` (~86),
  `rabBearingFor()`/`RAB_BEARINGS` fallback table (~74), `rabHasExit()` (~72);
  call site `theme_dash.h` ~486 (`s.rbExit`, `s.rbAngle`, `s.rbAngleExit`);
  also check `theme_e60.h`. State in `hud_state.h`, parser in
  `hud_protocol.h`.
- App: `HudFrame.kt` `rabLine()` (~103, builds `$RAB,<exit>,<bearing>`);
  `nav/MapboxProvider.kt` `bannerExitAngle` (~298, ~406: angle = 180 −
  banner `degrees`, mirrored for `driving_side=left`, fallback to the step
  bearings); `ui/ManeuverView.kt` (~132–143, the app's own roundabout drawing
  — keep it consistent with the HUD).
- Renderer: `cd arduino/test && make render` → `arduino/test/out/*.png`
  (readable orientation; the real panel is mirrored with keystone, see
  `hud_align.h`). It must support every primitive you use (e.g. smooth arcs)
  so the PNGs are faithful.

## 4. Plan
A. **Audit** (read-only, ≤ 2 agents), like the app sweep: protocol parser and
   every frame; CAN decode and staleness; repaint/delta logic and flicker;
   zone overlaps; compass; backlight/key; NO LINK timeout; memory/stack;
   no delay()/blocking; watchdog; millis() overflow; and that what the HUD
   shows matches what the app sends. Verify every claim in the code before
   fixing it (auditors can be wrong).
B. **Tests + CI**: make `make check` fully green; add
   `.github/workflows/firmware.yml`: `make check` + `make render` (upload the
   PNGs as artifact `hud-screens`) + an arduino-cli compile for
   `esp8266:esp8266:d1_mini` (core 3.1.2, TFT_eSPI 2.5.43 with
   `arduino/config/User_Setup.h` copied into the library, mcp_can 1.5.1).
   A cloud machine may be unable to download the core; CI can.
C. **Roundabout redesign** from `docs/reference-icons/owner-picks/01…16`:
   smooth thick ring; the part you drive (entry → around the ring → exit)
   bold in the theme's bright amber, the rest of the ring dim amber, on black
   (E60 theme: its own palette); a big clean exit arrow at the real angle; the
   exit number (legible, no ugly box); **stubs for every other exit**;
   mirrored for left-hand traffic; stays inside its zone (layout tests).
   Draw anti-aliased with TFT_eSPI's smooth primitives (drawSmoothArc /
   drawArc / drawWideLine / drawWedgeLine / fillSmoothCircle — verify they
   exist in 2.5.43 and their cost on the ESP8266).
   **Data:** the app must send all exit angles: derive them from the Mapbox
   route's intersections around the roundabout (bearings + in/out indices —
   verify the semantics in the Mapbox Directions docs) and send a new frame,
   e.g. `$RBX,<exit>,<angle1>,<angle2>,…` (angles relative to the entry road,
   same sign convention as `$RAB`), paired with the exit number like `$RAB`
   so a stale frame is never drawn on the next roundabout. **Compatibility
   both ways:** the owner flashes later, so the new app must still work with
   the old firmware (keep `$RAB`), and the new firmware must still draw
   path + arrow when an older app sends no `$RBX`. App side: `MapboxProvider`,
   `HudFrame`, `HudService` + unit tests on JSON shaped like the documented
   response; update `ManeuverView` to match.
D. **Fix** every real finding, one at a time, each with a test that fails
   without the fix. Render before/after and show the owner the key images
   (roundabouts with 3/4/5/7 exits; left/right/straight/U-turn exits;
   left-hand traffic; no-angle fallback; the fixed dash/e60 screens) and get
   their OK on the look before calling it done.
E. **Release**: app 1.32 (bump versionCode/versionName, `## App 1.32` in
   CHANGELOG — CI publishes it) and a firmware `## 2.8 — …` CHANGELOG section
   (bump the firmware version string if there is one). Update the "HUD
   firmware" section of CLAUDE.md (test status). The owner flashes the board
   later on their PC — never claim it was flashed. Delete this file when done.

## 5. Mistakes made before — do not repeat
- Two agents editing the same files at once conflict; worktree agents start
  from the default branch, not your current one.
- Tests with "perfect" data hid real bugs — use realistic inputs.
- Separately written parts broke at their seams — review the combined result.
- Cloud sessions cannot push tags or delete remote branches: releases are
  automatic (versionName bump on `main`); if you run on a session branch,
  fast-forward `main` to it and push `main`; the owner's PC session deletes
  the leftover branch.
- Commit and push after each finished fix, so a pause loses nothing.
