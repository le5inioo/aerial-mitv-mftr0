#!/usr/bin/env bash
# Merged installer: Aerial (pengchujin/aerial-tv) and the Home-key stand-in
# (fandejin/homekey-standin) as ONE APK, package com.ts.launcher.
#
# Why one package: Xiaomi's firmware only hands Home to a package name on its own list, and
# com.ts.launcher is the one this TV accepts (aerial-tv issue #901). Aerial's "standard" build
# declares the HOME filter, so it needs no separate forwarder: the stand-in's job — getting Home
# to Aerial with no frame of the stock launcher in between — is done by Aerial itself, under the
# name the firmware trusts. This script is the merge of tools/install.sh (Aerial) and
# homekey-standin/build.sh's install steps.
#
#   TV=192.168.2.11 tools/install-ts.sh                # install the built APK, make it Home
#   TV=192.168.2.11 APK_FILE=/path/to.apk tools/install-ts.sh
#   TV=192.168.2.11 BUILD=1 tools/install-ts.sh        # build first (JDK 17 + Android SDK)
#   TV=192.168.2.11 KEEP_ADB=1 tools/install-ts.sh     # switch ADB back on whenever the TV turns it off
#   TV=192.168.2.11 DISABLE_STOCK_HOME=1 tools/install-ts.sh  # also disable com.mitv.tvhome
#                                                   # (loses the Xiaomi Kids tile in Aerial)
#
# Nothing is uninstalled except an older com.ts.launcher that cannot be updated (homekey-standin,
# signed with another key, holds no data). The stock launcher is never touched unless asked.
set -euo pipefail
cd "$(dirname "$0")/.."

TV="${TV:-192.168.2.11}"
PKG=com.ts.launcher
HOME_ACT="$PKG/app.orchard.tv.MainActivity"
GUARD="$PKG/app.orchard.tv.guard.HomeGuardService"
LISTENER="$PKG/app.orchard.tv.guard.RevivalListener"
CONFIG="app.orchard.tv.CONFIG"
APK="${APK_FILE:-app/build/outputs/apk/standard/release/app-standard-release.apk}"

: "${TV:?set TV to the TV IP address, e.g. TV=192.168.2.11 $0}"
ADB="${ADB:-adb}"
command -v "$ADB" >/dev/null 2>&1 || { echo "adb not found: put it on PATH or set ADB=/path/to/adb" >&2; exit 1; }
export ANDROID_SERIAL="$TV:5555"

case "$TV" in *:*) export ANDROID_SERIAL="$TV" ;; *) export ANDROID_SERIAL="$TV:5555" ;; esac

if [ "${BUILD:-0}" = 1 ]; then
  ./gradlew -q --console=plain assembleStandardRelease
fi
[ -f "$APK" ] || { echo "No APK at $APK. Run with BUILD=1 or set APK_FILE." >&2; exit 1; }

echo "== connecting $ANDROID_SERIAL"
"$ADB" connect "$ANDROID_SERIAL" >/dev/null || true
STATE=$("$ADB" get-state 2>&1 || true)
if [ "$STATE" != device ]; then
  echo "Could not connect: $STATE" >&2
  echo "Turn ADB debugging on (设置 → 账号与安全 → ADB 调试) and run again." >&2
  exit 1
fi
SDK=$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')
echo "== Android SDK $SDK, $( "$ADB" shell getprop ro.product.model | tr -d '\r')"

echo "== installing $APK"
OUT=$("$ADB" install -r "$APK" 2>&1 || true)
if ! printf '%s' "$OUT" | grep -q Success; then
  case "$OUT" in
    *INCONSISTENT_CERTIFICATES*|*UPDATE_INCOMPATIBLE*)
      # homekey-standin (the old forwarder) is signed with another key and has nothing worth keeping.
      echo "== an older $PKG cannot be updated in place; removing it first"
      "$ADB" uninstall "$PKG" >/dev/null 2>&1 || true
      OUT=$("$ADB" install -r "$APK" 2>&1 || true) ;;
  esac
fi
printf '%s\n' "$OUT" | tail -n 2
printf '%s' "$OUT" | grep -q Success || { echo "Install failed; nothing else was changed." >&2; exit 1; }

echo "== grants"
grant() { "$ADB" shell "$@" >/dev/null 2>&1 || true; }
grant pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
grant pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
grant appops set "$PKG" SYSTEM_ALERT_WINDOW allow
grant appops set "$PKG" WRITE_SETTINGS allow
grant appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
grant appops set "$PKG" GET_USAGE_STATS allow
grant appops set "$PKG" android:get_usage_stats allow
grant appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow
for op in APP_AUTO_START APP_ASSOC_START AUTO_START ASSOC_START; do grant appops set "$PKG" "$op" allow; done
grant cmd notification allow_listener "$LISTENER"

if [ "${DISABLE_STOCK_HOME:-0}" = 1 ]; then
  echo "== disabling com.mitv.tvhome (Aerial's Kids tile goes with it)"
  "$ADB" shell pm disable-user --user 0 com.mitv.tvhome >/dev/null 2>&1 || true
fi

echo "== making it Home"
# The HOME role / preferred activity first; Xiaomi honours it once its own launcher is out of the way.
"$ADB" shell cmd package set-home-activity --user 0 "$HOME_ACT" >/dev/null 2>&1 || true
"$ADB" shell cmd role add-role-holder --user 0 android.app.role.HOME "$PKG" >/dev/null 2>&1 || true

# Home Guard: appended to whatever accessibility services the owner already had, never replaced.
CURRENT=$("$ADB" shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$CURRENT" in
  *"$GUARD"*) ;;
  ""|null) "$ADB" shell settings put secure enabled_accessibility_services "$GUARD" ;;
  *) "$ADB" shell settings put secure enabled_accessibility_services "$CURRENT:$GUARD" ;;
esac
"$ADB" shell settings put secure accessibility_enabled 1 >/dev/null 2>&1 || true

if [ "${KEEP_ADB:-1}" = 1 ]; then
  echo "== keeping ADB on"
  # The receiver's class is in the code namespace (app.orchard.tv), not under the applicationId.
  # "-n $PKG/.guard.ConfigReceiver" resolves to com.ts.launcher.guard.ConfigReceiver, which does
  # not exist: the broadcast was silently dropped and ADB stayed off after standby. Spell the
  # class out, like GUARD and LISTENER do.
  "$ADB" shell am broadcast -a "$CONFIG" -n "$PKG/app.orchard.tv.guard.ConfigReceiver" --ez keep_adb true >/dev/null 2>&1 || true
fi

"$ADB" shell am start -W -n "$HOME_ACT" >/dev/null 2>&1 || true
sleep 5
# Ahead-of-time compilation after the first start (Xiaomi treats "speed" as "speed-profile",
# which needs the profile Aerial writes as it runs).
"$ADB" shell cmd package compile -m speed -f "$PKG" >/dev/null 2>&1 || true

echo
echo "== verification"
echo -n "resolve-activity HOME : "; "$ADB" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1 | tr -d '\r'
echo -n "accessibility services: "; "$ADB" shell settings get secure enabled_accessibility_services | tr -d '\r'
echo -n "front window          : "; "$ADB" shell "dumpsys window | grep mCurrentFocus" | tr -d '\r'
echo
echo "Done. Press Home on the remote: it should land on Aerial with no stock-launcher frame."
echo "If Home ever goes back to the TV's launcher, run this script again."
