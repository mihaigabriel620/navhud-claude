#!/system/bin/sh
# ---------------------------------------------------------------------------
#  NavHUD — one-time setup for an Android head unit
#
#  Run it once. It tells the system to leave NavHUD alone, so the display keeps
#  showing your speed, the limit and camera warnings whether or not the app is
#  on screen.
#
#      adb push headunit-setup.sh /data/local/tmp/
#      adb shell sh /data/local/tmp/headunit-setup.sh
#
#  or on the unit itself, in a terminal:  su -c 'sh /sdcard/headunit-setup.sh'
#
#  Most of it does NOT need root — the shell user already holds the permissions
#  involved. Root only adds the last, optional step. Everything here is
#  reversible; nothing touches the boot image or SELinux policy.
# ---------------------------------------------------------------------------

PKG=com.mihai.navhud
ok=0; bad=0

say()  { echo ""; echo "== $*"; }
pass() { echo "   OK    $*"; ok=$((ok+1)); }
fail() { echo "   FAIL  $*"; bad=$((bad+1)); }

echo "NavHUD head-unit setup"
echo "package: $PKG"

# ---------------------------------------------------------------------------
say "Is it installed?"
if pm path $PKG >/dev/null 2>&1; then pass "found"; else
  fail "not installed — install the APK first, open it once, then re-run this"
  exit 1
fi

# The stopped state is real and silent: a package Android considers stopped
# receives no broadcasts at all, so the boot receiver never fires. Opening the
# app once clears it.
if dumpsys package $PKG 2>/dev/null | grep -q "stopped=true"; then
  fail "package is in the stopped state — open NavHUD once, then re-run this"
else
  pass "not in the stopped state, so it can receive BOOT_COMPLETED"
fi

# ---------------------------------------------------------------------------
say "Doze exemption — the important one"
# On a phone this is belt and braces. On a head unit it is load-bearing:
# DeviceIdleController decides it is "charging" from EXTRA_PRESENT && EXTRA_PLUGGED,
# and EXTRA_PRESENT is whether a *battery* is present. Plenty of head units have
# no battery and report present=false, so the unit is wired to the ignition and
# Doze arms itself anyway. In Doze, wake locks are ignored — which stops the
# 4 Hz frame loop dead, with no error anywhere.
cmd deviceidle whitelist +$PKG >/dev/null 2>&1 || \
  dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1
if [ "$(cmd deviceidle whitelist =$PKG 2>/dev/null)" = "true" ]; then
  pass "whitelisted (survives reboot; lost if you reinstall from scratch)"
else
  fail "could not whitelist — try again with su"
fi

echo "   note: this device reports charging=$(dumpsys deviceidle get charging 2>/dev/null), screen=$(dumpsys deviceidle get screen 2>/dev/null)"
echo "         charging=0 means Doze will engage even though the unit is wired in."

# ---------------------------------------------------------------------------
say "App ops — defence against a vendor battery manager"
# All four already default to allow on a clean system. They are pinned here
# because a head-unit ROM's own power manager is exactly the thing that flips
# them, and each one fails silently when it does.
for op in RUN_ANY_IN_BACKGROUND START_FOREGROUND WAKE_LOCK; do
  cmd appops set $PKG $op allow >/dev/null 2>&1
  got=$(cmd appops get $PKG $op 2>/dev/null | head -1)
  case "$got" in
    *allow*) pass "$op allow" ;;
    "")      pass "$op left at its default (allow)" ;;
    *)       fail "$op is $got" ;;
  esac
done
cmd appops write-settings >/dev/null 2>&1   # flush now; the unit loses power abruptly

# ---------------------------------------------------------------------------
say "Standby bucket"
am set-standby-bucket $PKG active >/dev/null 2>&1
b=$(am get-standby-bucket $PKG 2>/dev/null)
[ -n "$b" ] && echo "   bucket now $b (10 = active). Not sticky — the Doze"
echo "         whitelist above is what actually keeps it there."

# ---------------------------------------------------------------------------
say "Location"
if dumpsys package $PKG 2>/dev/null | grep -q "ACCESS_FINE_LOCATION: granted=true"; then
  pass "fine location granted"
else
  pm grant $PKG android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1
  if dumpsys package $PKG 2>/dev/null | grep -q "ACCESS_FINE_LOCATION: granted=true"; then
    pass "fine location granted"
  else
    fail "no location permission — grant it in the app"
  fi
fi
echo "   note: on Android 10 a foreground service typed 'location' counts as"
echo "         foreground for location, so ACCESS_BACKGROUND_LOCATION is not needed."

# ---------------------------------------------------------------------------
say "System-wide battery heuristics (optional, affects every app)"
echo "   Skipping by default. If the display still stalls after a long standstill,"
echo "   the one worth trying is app_auto_restriction_enabled: it is the heuristic"
echo "   that auto-restricts an app for 'holding a wakelock for a long time',"
echo "   which is precisely what this app does on purpose."
echo ""
echo "     settings put global app_auto_restriction_enabled 0"
echo "     settings put global adaptive_battery_management_enabled 0"
echo "     settings put global forced_app_standby_enabled 0"

# ---------------------------------------------------------------------------
say "Root extras (optional)"
if [ "$(id -u)" = "0" ]; then
  echo "   Running as root. The one thing worth doing with it:"
  echo ""
  echo "   Copy the APK to /system/app so the framework treats NavHUD as a"
  echo "   system app. Combined with android:persistent, which the manifest"
  echo "   already sets, that means the process is started before BOOT_COMPLETED"
  echo "   is broadcast, sits where the low-memory killer cannot reclaim it, and"
  echo "   is restarted by the framework itself if it ever dies."
  echo ""
  echo "     APK=\$(pm path $PKG | sed 's/package://')"
  echo "     mkdir -p /system/app/NavHUD && cp \"\$APK\" /system/app/NavHUD/NavHUD.apk"
  echo "     chmod 755 /system/app/NavHUD && chmod 644 /system/app/NavHUD/NavHUD.apk"
  echo "     restorecon -R /system/app/NavHUD && reboot"
  echo ""
  echo "   Use /system/app, never /system/priv-app: since Android 9 a privileged"
  echo "   app whose permissions are not in the allowlist stops the device"
  echo "   booting. There is nothing in /system/priv-app this app needs."
  echo "   Prefer a Magisk module over remounting /system — see docs/HEADUNIT.md."
else
  echo "   Not root, and nothing above needed it. Root only adds the /system/app"
  echo "   install, which is optional. Re-run with su to see those instructions."
fi

# ---------------------------------------------------------------------------
echo ""
echo "---------------------------------------------------------------"
echo " $ok checks passed, $bad failed"
[ $bad -gt 0 ] && echo " Re-run with: su -c 'sh \$0'"
echo " Then open NavHUD once so the boot receiver is armed."
echo "---------------------------------------------------------------"
