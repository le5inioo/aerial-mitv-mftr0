#!/usr/bin/env bash
# Puts developer mode back on a TV, from a computer over ADB.
#
# Xiaomi's firmware sometimes drops developer mode at a start, and the ADB switch lives inside it:
# with it gone, the settings page has no way to turn ADB back on. This script writes the same three
# settings the app writes at boot when "Developer Mode at Boot" is on (Personalize > TV system), for
# the times there is no link to the app and the TV has to be reached over ADB first.
#
#   tools/dev-mode.sh                    # the TV in $TV, or 192.168.2.11:5555
#   tools/dev-mode.sh 192.168.2.31:5555  # another one
#
# No special permission is needed: `settings put` over ADB goes through the shell's own rights.
set -eu

ADB=${ADB:-adb}
SERIAL=${1:-${TV:-192.168.2.11:5555}}

case "$SERIAL" in
  *:*) "$ADB" connect "$SERIAL" >/dev/null 2>&1 || true ;;
esac

# One line: adb.exe on Windows loses output (and sometimes the rest) over a multi-line argument.
"$ADB" -s "$SERIAL" shell "settings put global development_settings_enabled 1; settings put global developer_status 1; settings put global adb_enabled 1; echo development_settings_enabled=\$(settings get global development_settings_enabled) developer_status=\$(settings get global developer_status) adb_enabled=\$(settings get global adb_enabled)"
