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
- **Cloud sessions are preferred** (the owner has cloud-session credits):
  at the start and end of a session, report remaining usage/credits if visible.
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
- Coding rules (the owner's skills): read before editing; never delete
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
- Locally: `cd android && ./gradlew :app:testDebugUnitTest` (664 tests) and
  `./gradlew :app:assembleRelease`. Needs JDK 17 and Android SDK platform 34.
- `android/local.properties` is committed with `sdk.dir=C:/Android` for the
  owner's Windows PC. Elsewhere, overwrite it locally and run
  `git update-index --skip-worktree android/local.properties` — never commit a
  different sdk.dir. The signing keystores are committed on purpose (private
  repo); release builds sign automatically.
- A cloud session may not reach dl.google.com / jitpack.io; if the SDK or
  dependencies cannot be installed, push and let CI build and test instead.

## Where things are
- `MapActivity.kt` driving screen (camera loop, arrow, route line, gestures);
  `map/` PuckMotion (arrow motion, tunnel coasting), RouteLine (vanishing
  line), HeadingFusion, NavCamera, OfflineRoutes (map tile chunks).
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
