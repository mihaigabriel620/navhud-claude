# NavHUD 1.26 — what was restored, and how

The workspace holding the source was reclaimed between sessions. This tree is
the **older source zip** (which builds, and has the tests) with every fix from
the lost newer build ported forward, using a **decompiled copy of the shipped
APK** as the reference for what each change had to become.

Nothing here was reconstructed from memory. Every constant was read out of the
decompiled reference; where the decompiler garbled a method, the reconstruction
is noted in the code.

## Verified state

    514 tests, 47 classes, 0 failures        (was 451 / 43 before the port)
    compiles clean, signed with navhud-release.jks

## Ported forward

| | file |
|---|---|
| compass/GPS handover 70 km/h → 5 km/h | `map/HeadingFusion.kt` |
| the shadowed clock that froze the arrow | `map/HeadingFusion.kt` |
| map stutter, 30 ms → 8 ms + frame self-limiter | `MapActivity.kt` |
| travelled route drawn dimmer behind you | `MapActivity.kt` |
| tunnel dead reckoning + eased reacquisition | `MapActivity.kt` |
| 512 MB ambient tile cache | `MapActivity.kt` |
| voice: three stages → two, plus both suppression rules | `VoiceGuide.kt` |
| U-turn-averse reroute | `RouteChoice.kt` (recovered) |
| offline Overpass cache | `nav/AreaCache.kt` (recovered) |
| cache → network → stale cache | `nav/AreaRoads.kt` |
| derived speed limits shared by both trackers | `nav/SpeedDefaults.kt`, `FreeTracker.kt` |
| wheel speed preferred over GPS | `HudService.kt` |
| roundabout arrow points at the real exit | `nav/MapboxProvider.kt`, `HudFrame.kt`, `ui/ManeuverView.kt` |

## Still missing from the shipped build

`audio/Chime.kt` and `SpeedingRule.kt` were in the APK and are not here. They
are not on the bug list and nothing references them, so they appear to be a
later refactor rather than a fix. The decompiled Java for both is recoverable
from the APK if they turn out to matter.

## Build

    cd android
    # put MAPBOX_TOKEN=pk.… in local.properties, or type it into the app
    gradle :app:assembleRelease

Signed with `navhud-release.jks`, so it installs over the top — no uninstall.

## Keep this zip somewhere that is not a chat.
