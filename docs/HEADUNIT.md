# Running on an Android head unit

Researched August 2026 against AOSP 10 (`android-10.0.0_r47`) and the current
Android docs, because the answer for a head unit is different from the answer
for a phone — and in most respects better.

## Android 10 is the easy target

Every restriction that makes a long-running foreground service awkward on a
modern phone arrived after API 29, and each needs **both** a matching
`targetSdk` and a device running that platform. The enforcement code is not in
the Android 10 system image at all.

| Restriction | Arrived | Present on API 29 |
|---|---|---|
| Cannot start a foreground service from the background | API 31 | **No** |
| `foregroundServiceType` mandatory | API 34 | **No** |
| `FOREGROUND_SERVICE_*` permissions enforced | API 34 | **No** — the constants don't exist |
| `dataSync` / `mediaProcessing` 6-hour timeout | API 35 | **No** (and `location` has no timeout on any version) |

So on the head unit the boot receiver may start the service directly, with no
exemption and no ceremony.

**Background location needs no extra permission either.** From
[Privacy changes in Android 10](https://developer.android.com/about/versions/10/privacy/changes):

> An app is considered to be accessing location in the background unless one of
> the following conditions is satisfied: An activity belonging to the app is
> visible. **The app is running a foreground service that has declared a
> foreground service type of `location`.**

The app declares exactly that, so `ACCESS_FINE_LOCATION` is sufficient and
`ACCESS_BACKGROUND_LOCATION` is not needed. Worth knowing what the failure looks
like if the type is ever dropped: the location op resolves to `MODE_IGNORED` and
`LocationManager` **silently stops delivering updates** — no exception, no
callback, nothing in the log.

## The one thing that is *worse* on a head unit

Doze is supposed to stay away while a device is charging. The check is not
"is power connected" — it is, in `DeviceIdleController`:

```java
boolean present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
boolean plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
updateChargingLocked(present && plugged);
```

`EXTRA_PRESENT` is whether a **battery** is present. Many head units have none
and report `present=false`, so `mCharging` is false however the unit is wired,
and Doze arms itself the moment the screen sleeps. In Doze, wake locks are
ignored — which stops the 4 Hz frame loop dead, silently.

Check it on the unit:

```
dumpsys deviceidle get charging     # 0 means Doze WILL engage
dumpsys battery                     # look at "present:"
```

If that reads `0`, the Doze whitelist below is not insurance, it is the fix.

## One-time setup

`tools/headunit-setup.sh` does all of this with a pass/fail line for each step.
**Almost none of it needs root** — the shell user already holds `DEVICE_POWER`
and `MANAGE_APP_OPS_MODES`.

```
adb push tools/headunit-setup.sh /data/local/tmp/
adb shell sh /data/local/tmp/headunit-setup.sh
```

What it does, and why:

| Step | Why |
|---|---|
| `cmd deviceidle whitelist +com.mihai.navhud` | Wake locks keep working in Doze, and App Standby buckets stop applying. Persists in `/data/system/deviceidle.xml` across reboots — **but is dropped if you uninstall and reinstall.** |
| `cmd appops set … RUN_ANY_IN_BACKGROUND / START_FOREGROUND / WAKE_LOCK allow` | All three already default to allow. They are pinned because a vendor battery manager is exactly what flips them, and each fails **silently**: `START_FOREGROUND` on `ignore` quietly degrades `startForegroundService()` to a background start that then dies. |
| `cmd appops write-settings` | Flush now. A head unit loses power abruptly and app-op writes are batched. |
| `am set-standby-bucket … active` | Diagnostic only — it is not sticky. The whitelist is the durable answer. |

Deliberately **not** done automatically, because they are device-wide rather
than per-app. Try the first if the display still stalls after a long standstill
— it is the heuristic that auto-restricts an app for *"holding a wakelock for a
long time"*, which is precisely what this app does on purpose:

```
settings put global app_auto_restriction_enabled 0
settings put global adaptive_battery_management_enabled 0
settings put global forced_app_standby_enabled 0
```

## What root adds

One thing, and it is a good one.

The manifest already sets `android:persistent="true"`. On a normally-installed
APK that does very little — the framework's mask is `FLAG_SYSTEM` **and**
`FLAG_PERSISTENT` together, so a side-loaded app gets its process started at
boot and nothing else. Install the same APK into `/system/app` and both halves
switch on:

- started by `AMS.startPersistentApps()` **before** `BOOT_COMPLETED` is
  broadcast — no receiver involved, so a vendor "auto launch" manager cannot
  block it;
- `oom_adj = -800`, where the low-memory killer will not reclaim it;
- **restarted by the framework itself if the process ever dies** — a watchdog
  nobody had to write.

```sh
APK=$(pm path com.mihai.navhud | sed 's/package://')
mkdir -p /system/app/NavHUD
cp "$APK" /system/app/NavHUD/NavHUD.apk
chmod 755 /system/app/NavHUD
chmod 644 /system/app/NavHUD/NavHUD.apk
restorecon -R /system/app/NavHUD
reboot
```

No re-signing needed — the platform key is only required for `signature`-level
permissions, and this app asks for none.

**Use `/system/app`, never `/system/priv-app`.** From the
[AOSP privileged permission allowlist](https://source.android.com/docs/core/permissions/perms-allowlist):

> Android 9 and higher, violations of privileged permissions **prevent the
> device from starting**.

There is nothing in `priv-app` this app needs, and the downside is a bootloop.

Prefer a **Magisk module** to remounting `/system` — it puts the same file in
the same place through an overlay, so nothing is written to the real partition
and removing the module undoes it completely.

## Risks

Everything in the setup script is reversible and touches nothing outside this
package:

```
cmd deviceidle whitelist -com.mihai.navhud
cmd appops reset com.mihai.navhud
```

The `/system/app` copy is reversible by deleting the directory (or removing the
Magisk module). The only genuinely dangerous move on this list is
`/system/priv-app`, which is why the script prints the `/system/app` form and
not that one.

## After a reinstall

Two things do not survive installing the APK from scratch:

1. The Doze whitelist entry — `DeviceIdleController` removes it on package
   removal. Re-run the script.
2. Nothing else. App ops and preferences persist across an update
   (`pm install -r`); a full uninstall clears both.

And one thing to remember on any device: a package Android considers
**stopped** receives no broadcasts at all, so `BOOT_COMPLETED` never arrives
until the app has been opened once after installing.
