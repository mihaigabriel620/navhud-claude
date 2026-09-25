# NavHUD — notes for Claude

Android nav app (Kotlin, MapLibre, Mapbox Directions, OSM/Overpass) for Android
head units (UIS7862/Mekede, Android 10+), driving a DIY HUD (ESP8266 + MCP2515
CAN + QMC5883P compass, 480x320 SPI). Firmware in `arduino/`, app in `android/`.
The owner drives in Belgium and across Europe (to Romania); it must behave like
Waze / Google Maps everywhere.

## How the owner wants the work done
- **One repo, one branch.** `mihaigabriel620/navhud-claude`, commit and push
  straight to `main`. No feature branches, no other repos. If subagents use
  worktrees, merge into main and delete the worktrees and their branches. If a
  cloud session is started on its own `claude/...` branch, fast-forward `main`
  to it, push `main`, and delete that branch (local and remote) when done.
- **Where to work:** the owner currently prefers sessions on their PC; cloud
  sessions (which use their cloud credit) are an option when the weekly limit
  is tight. At the start and end of a session, report remaining usage.
- **Every new APK goes to GitHub Releases, automatically.** Bump
  `versionCode`/`versionName` in `android/app/build.gradle.kts`, add a
  `## App X.Y` section at the top of `CHANGELOG.md`, push to `main`. When CI
  passes it publishes "NavHUD X.Y" (tag `vX.Y`) with the signed APK — no tag
  push needed (see `.github/workflows/android.yml`). Cloud sessions cannot push
  tags or delete remote branches: never tell the owner to do those by hand;
  the workflow covers releases, and leftover session branches are cleaned up
  from the owner's PC session.
- **Chat short**: act, don't narrate; only important info, questions, results.
  Full detail only when asked for status. Big logs go to files, not chat.
- **Thorough and correct beats fast.** Research online (docs, OSM wiki, laws)
  instead of guessing; verify API signatures (MapLibre AAR, android.jar).
- **Usage is limited**: at most 2 subagents at once; watch usage and pause
  before the limit.
- **The owner's skills** are copied in `docs/owner-skills/` (a cloud session
  has no other copy) — read and follow all of them. Summary of the
  coding rules (the owner's skills): read before editing; never delete
  features or touch unrelated code; minimum code that works (reuse > stdlib >
  new code); one change at a time, test, revert failed attempts; state a
  hypothesis before fixing a bug; no blocking/delay() in firmware loops.
- Splitting files: one file per job, main file only orchestrates, file map at
  the top of the main file. Only split code that already works, never while
  fixing bugs, one file at a time with a build/test after each move.
  `MapActivity.kt` (~3000 lines) and `HudService.kt` (~2300) are candidates.

## Build and test
- CI (GitHub Actions) runs the unit tests and builds the signed APK on every
  push to `main`; check the Actions tab / `gh run list` after pushing.
- Locally: `cd android && ./gradlew :app:testDebugUnitTest` (~692 tests) and
  `./gradlew :app:assembleRelease`. Needs JDK 17 and Android SDK platform 34.
- `android/local.properties` is committed with `sdk.dir=C:/Android` for the
  owner's Windows PC. Elsewhere, overwrite it locally and run
  `git update-index --skip-worktree android/local.properties` — never commit a
  different sdk.dir. The signing keystores are committed on purpose (private
  repo); release builds sign automatically.
- A cloud session may not reach dl.google.com / jitpack.io; if the SDK or
  dependencies cannot be installed, push and let CI build and test instead.

## HUD firmware (`arduino/NavHud`, v2.9)
- Hardware: Wemos D1 mini (ESP8266) + ST7796 4" 480x320 SPI (landscape,
  mirrored for the windscreen) + MCP2515 CAN (BMW E60 K-CAN, listen-only) +
  QMC5883P compass (I2C). Pins: `hud_pins.h` (CAN CS D8, TFT CS D2, backlight
  D0). Two themes: `theme_dash.h` (default), `theme_e60.h`. Protocol with the
  app: `PROTOCOL.md` ($HUD, $RAB, $CAM, $LANE, $CAR …; flags bit 7 = left-hand
  traffic) — app side in `android/.../HudFrame.kt`, `HudService.kt`.
- The MCP2515 must never transmit: listen-only, re-checked on every drain
  (`canPump`), and the host tests assert no transmit command ever reaches it.
- Roundabout glyph: `hud_arrows.h` `roundaboutArt()` (dim ring, bright driven
  path, arrow at the real `$RAB` angle, no exit stubs — the owner's choice),
  drawn with TFT_eSPI's smooth `drawArc`/`drawWideLine`; the phone's
  `ui/ManeuverView.kt` uses the same numbers.
- Everything is anti-aliased (the owner wants it as polished as 480x320
  allows): rings with smooth `drawArc`, never stacked `drawCircle`s (pinholes);
  arrows, U-turn and lanes as one union per glyph with `hud_aa.h` `aaFill`
  (round-ended strokes + triangles, per pixel, no seams). `make render` fails
  on any pinhole, on an arrow without smooth edges, or on a seam inside one.
- Build: `arduino-cli compile --fqbn esp8266:esp8266:d1_mini arduino/NavHud`
  with core esp8266:esp8266 3.1.2, libraries TFT_eSPI 2.5.43 and mcp_can 1.5.1;
  **copy `arduino/config/User_Setup.h` into the TFT_eSPI library folder** or it
  compiles fine and drives the wrong pins. 2.9: flash 56 %, RAM 45 %, IRAM 94 %
  (IRAM is the tight one — no new IRAM_ATTR code). CI does this compile.
- CI: `.github/workflows/firmware.yml` on every push to `arduino/`/`tools/`:
  `make check`, `make render` (PNGs as artifact `hud-screens`), and the real
  compile with the size report in the log.
- Host tests and screen images: `cd arduino/test && make check` (all suites
  green since 2.8: protocol, geometry, the three sketch builds, CAN through an
  MCP2515 register simulator and an mcp_can stand-in, layout, PanelDiag, and
  the python reference models — needs python3) and `make render` (PNGs of every
  screen state, both themes, into `arduino/test/out/`, readable orientation;
  also runs pixel checks — a field drawn alone must survive the full screen).
  `make docs-images` rebuilds README's pictures in `docs/img/` from those
  renders (needs Pillow); rerun it whenever what the HUD draws changes.
  On the owner's PC use `mingw32-make` from WinLibs GCC (not on PATH in older
  shells:
  `%LOCALAPPDATA%\Microsoft\WinGet\Packages\BrechtSanders.WinLibs.POSIX.UCRT_*\mingw64\bin`).
  `tools/check_layout.py` is not run: its bench page `hud-editor.html` was lost
  in the 1.26 restore.
- Only the owner flashes the board (USB to the D1 mini, on their PC). Show them
  the rendered screens before anything is flashed.
- Design references for graphics: `docs/reference-icons/` (Waze/Google Maps,
  inspiration only).
- Firmware releases are recorded as `## X.Y — …` sections in CHANGELOG.md
  (app entries are `## App X.Y`).

## Where things are
- `MapActivity.kt` driving screen (camera loop, arrow, route line, gestures);
  `map/` PuckMotion (arrow motion, tunnel coasting), RouteLine (vanishing
  line), HeadingFusion (GPS above 5 km/h, the HUD compass below it, plus
  `hudCorrection` learned from GPS so tilt on hills and the mounting do not
  matter -- see docs/HEADING.md), NavCamera, OfflineRoutes (map tile chunks).
- Free-drive road data (limits, cameras) comes from Overpass: `nav/AreaRoads`
  asks overpass-api.de, then overpass.private.coffee, per-server fair use,
  with `AreaCache` on disk shown at once. With the app swiped away the voice
  keeps only the over-limit and camera warnings (`VoiceGuide.warningsOnly`).
- `HudService.kt` foreground service: GPS, HUD link, route tracking, reroute,
  speed source (CAN vs GPS), cameras, country rules, Overpass prefetch.
- `RouteTracker.kt`, `RerouteRule.kt`, `RouteChoice.kt`, `FreeTracker.kt`:
  navigation logic. `nav/`: Mapbox provider, AreaRoads/AreaCache (Overpass +
  disk cache), SpeedDefaults (per-country limits).
- `VoiceGuide.kt` + `voice/`: TTS queue, earcons, ducking focus, phrases.
  `SpeedingRule.kt`: over-limit warning rule. `alerts/`: cameras, country
  camera policy, zones, road-ahead features.
- `CHANGELOG.md` explains the why behind past fixes; read it before changing
  behaviour. `docs/API-OPTIONS.md`: free data sources evaluated.
