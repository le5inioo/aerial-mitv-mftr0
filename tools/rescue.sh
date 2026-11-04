#!/usr/bin/env bash
# Put a picture back on a TV that boots to a black screen, in one command.
#
# A black screen on this kind of install usually means nothing owns Home: the stock launcher was
# disabled on purpose (tools/install-ts.sh's DISABLE_STOCK_HOME=1, or by hand), and the app that
# replaced it is gone, disabled or crashing. With no Home there is nothing to draw, the remote's
# Home key opens nothing, and the settings pages cannot be reached — ADB is the only way in. That
# is why this is one command: connect, say what is wrong, switch the stock launcher back on, hand
# Home to it, and leave the TV showing something.
#
#   tools/rescue.sh                  # the TV at $TV, or 192.168.2.11
#   tools/rescue.sh 192.168.2.31     # another address
#   tools/rescue.sh --scan           # this subnet: find a TV with ADB open, then rescue it
#   tools/rescue.sh --scan 192.168.1 # ...on another subnet
#   tools/rescue.sh --check          # print the diagnosis only; change nothing
#   RESCUE_TIMEOUT=180 tools/rescue.sh   # wait longer for a TV that is still booting
#
# On Windows, tools/rescue.bat runs this on a double-click.
#
# Exit status: 0 when Home resolves again, 1 when the TV never answered or could not be fixed,
# 2 for a bad command line. Nothing here needs root; every change goes through ADB's shell.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
TV_IP=${TV:-192.168.2.11}
TIMEOUT=${RESCUE_TIMEOUT:-120}
MODE=fix
SCAN=0
SCAN_BASE=

usage() {
  sed -n '2,/^set /p' "$0" | grep '^#' | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --check) MODE=check ;;
    --scan)
      SCAN=1
      if [ $# -ge 2 ] && printf '%s' "$2" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
        SCAN_BASE=$2; shift
      fi ;;
    --timeout)
      [ $# -ge 2 ] || { echo "--timeout 要一个秒数" >&2; exit 2; }
      TIMEOUT=$2; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "不认识的选项：$1" >&2; usage >&2; exit 2 ;;
    *) TV_IP=$1 ;;
  esac
  shift
done

case "$TV_IP" in *:*) SERIAL=$TV_IP ;; *) SERIAL="$TV_IP:5555" ;; esac

# The TV's screen is black, so the log is the only thing to show anyone afterwards. Written next to
# the script; only when a person is watching (a pipe or a test keeps the tree clean).
LOG="$HERE/rescue-$(date +%Y%m%d-%H%M%S).log"
if [ -t 1 ]; then exec > >(tee "$LOG") 2>&1; fi

say() { printf '%s\n' "$*"; }
head_() { printf '\n== %s\n' "$*"; }
indent() { sed 's/^/    /'; }

find_adb() {
  local c
  for c in "${ADB:-}" \
           /c/Android/sdk/platform-tools/adb.exe \
           "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
           "/c/Program Files (x86)/Android/android-sdk/platform-tools/adb.exe" \
           "/c/Program Files/Android/platform-tools/adb.exe"; do
    [ -n "$c" ] || continue
    if command -v "$c" >/dev/null 2>&1; then command -v "$c"; return 0; fi
    if [ -x "$c" ]; then printf '%s\n' "$c"; return 0; fi
  done
  command -v adb 2>/dev/null && return 0
  return 1
}

# Every adb call in this script goes through here: one TV, CRs stripped, errors hidden — the caller
# decides what an empty answer means.
sh_() { "$ADB_BIN" -s "$SERIAL" shell "$@" 2>/dev/null | tr -d '\r'; }

resolve_home() {
  local out
  out=$("$ADB_BIN" -s "$SERIAL" shell cmd package resolve-activity --brief \
        -a android.intent.action.MAIN -c android.intent.category.HOME \
        -c android.intent.category.DEFAULT 2>/dev/null | tr -d '\r' | tail -n 1)
  case "$out" in ''|null|*'No activity'*) printf '' ;; *) printf '%s' "$out" ;; esac
}

has_pkg() { [ -n "$(sh_ pm path "$1" | grep -m1 '^package:')" ]; }
# Asked once and kept: the list is what the diagnosis was based on, and every adb round trip costs
# a second or more on this TV.
DISABLED_LIST=
is_disabled() {
  if [ -z "$DISABLED_LIST" ]; then DISABLED_LIST=$(sh_ pm list packages -d); fi
  printf '%s\n' "$DISABLED_LIST" | grep -qx "package:$1"
}

# Sets FOUND_SERIAL rather than printing it: the progress lines go to the terminal too, and a
# command substitution would swallow them into the address.
FOUND_SERIAL=
scan_lan() {
  local base=$1 candidates ip
  say "  扫描 ${base}.1-254 的 5555 端口（并发，约半分钟）…"
  seq 1 254 | xargs -P 24 -I{} bash -c \
    '"$2" connect "$1.{}:5555" >/dev/null 2>&1 || true' _ "$base" "$ADB_BIN" >/dev/null 2>&1
  candidates=$("$ADB_BIN" devices 2>/dev/null |
               awk -v p="$base." 'NR>1 && $2=="device" && index($1,p)==1 && $1 ~ /:5555$/ {print $1}')
  for ip in $candidates; do
    # "connected" from adb connect is not proof of anything; only a booted device answers this.
    if [ "$("$ADB_BIN" -s "$ip" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n ')" = 1 ]; then
      say "  $ip 有响应"
      FOUND_SERIAL=$ip
      return 0
    fi
  done
  return 1
}

wait_for_device() {
  local started=$SECONDS attempt=0 hinted=0 state
  while [ $((SECONDS - started)) -lt "$TIMEOUT" ]; do
    attempt=$((attempt + 1))
    case "$SERIAL" in *:*) "$ADB_BIN" connect "$SERIAL" >/dev/null 2>&1 || true ;; esac
    if [ "$(sh_ getprop sys.boot_completed | tr -d ' \n')" = 1 ]; then
      say "  连上了（第 ${attempt} 次尝试）"
      return 0
    fi
    if [ "$attempt" = 1 ]; then
      say "  等电视响应…（开机后 ADB 要 15~30 秒才可用，最多 ${TIMEOUT} 秒）"
    elif [ "$hinted" = 0 ] && [ $((SECONDS - started)) -ge 30 ]; then
      say "  还没响应。电视若已开着，多半是 ADB 调试关着或 IP 变了；继续等到 ${TIMEOUT} 秒。"
      hinted=1
    fi
    sleep 3
  done
  state=$("$ADB_BIN" -s "$SERIAL" get-state 2>&1 | tr -d '\r' | tail -n 1)
  case "$state" in
    *unauthorized*) say "  ${SERIAL} 连上了但没授权：电视上会弹「允许 USB 调试」，黑屏时看不到它。" ;;
    *offline*) say "  ${SERIAL} 是 offline：先 adb kill-server，再跑一次。" ;;
    *) say "  ${SERIAL} 没有响应。" ;;
  esac
  return 1
}

retry_hints() {
  say ""
  say "  再试这些："
  say "   · 电视确实开着，且和电脑在同一个路由器（访客网络不行）"
  say "   · 电视换了 IP：tools/rescue.sh --scan"
  say "   · ADB 调试是开过的才有这条路；黑屏下改不了设置，只能靠它"
  say "   · 断电 5 分钟再上电；仍不行只能恢复出厂或报修"
}

# ---------------------------------------------------------------- 1. adb

head_ "找 adb"
if ! ADB_BIN=$(find_adb); then
  say "  找不到 adb。把 platform-tools 放进 PATH，或 ADB=/path/to/adb 再跑。"
  exit 1
fi
say "  $ADB_BIN"

# ---------------------------------------------------------------- 2. connect

head_ "连接"
if [ "$SCAN" = 1 ]; then
  if [ -z "$SCAN_BASE" ]; then SCAN_BASE=$(printf '%s' "$TV_IP" | awk -F. '{print $1"."$2"."$3}'); fi
  if ! scan_lan "$SCAN_BASE"; then
    say "  ${SCAN_BASE}.0-255 里没有开着 ADB 的电视。"
    retry_hints
    exit 1
  fi
  SERIAL=$FOUND_SERIAL
else
  say "  $SERIAL"
fi
if ! wait_for_device; then
  retry_hints
  exit 1
fi

# ---------------------------------------------------------------- 3. diagnosis

head_ "诊断"
MODEL=$(sh_ getprop ro.product.model)
RELEASE=$(sh_ getprop ro.build.version.release)
SDK=$(sh_ getprop ro.build.version.sdk)
say "  型号        ${MODEL:-?} / Android ${RELEASE:-?} (sdk ${SDK:-?})"

HOME_NOW=$(resolve_home)
if [ -z "$HOME_NOW" ]; then
  say "  Home        （空 —— 没有应用接管 Home，这就是黑屏）"
else
  say "  Home        $HOME_NOW"
fi

PW=com.mitv.tvhome
PW_PRESENT=0
PW_OFF=0
has_pkg "$PW" && PW_PRESENT=1
if [ "$PW_PRESENT" = 1 ] && is_disabled "$PW"; then PW_OFF=1; fi
if [ "$PW_PRESENT" = 0 ]; then
  say "  PatchWall   没装（不是小米，或者被卸了）"
elif [ "$PW_OFF" = 1 ]; then
  say "  PatchWall   已安装，但被禁用了 ⚠  Home 交不回去"
else
  say "  PatchWall   已安装，已启用"
fi

AERIAL=
for p in com.ts.launcher app.orchard.tv app.orchard.tv.lite; do
  if has_pkg "$p"; then AERIAL=$p; break; fi
done
ACC=$(sh_ settings get secure enabled_accessibility_services)
if [ -z "$AERIAL" ]; then
  say "  Aerial      没装"
else
  say "  Aerial      $AERIAL 已安装"
  case "$ACC" in
    *"$AERIAL/app.orchard.tv.guard.HomeGuardService"*)
      say "              无障碍接着（会一直把 Home 抢回自己）" ;;
  esac
  if is_disabled "$AERIAL"; then say "              但被禁用了 ⚠"; fi
fi

WAKE=$(sh_ dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*' | sed 's/^mWakefulness=//')
say "  屏幕        ${WAKE:-?}"

if [ "$MODE" = check ]; then
  say ""
  say "  --check：只看不改。去掉 --check 再跑一次就开始修。"
  exit 0
fi

# ---------------------------------------------------------------- 4. fix

head_ "修复"

# PatchWall first: a disabled package cannot be given Home at all. Enabled unconditionally other
# than by `if`, because a TV that boots to black is past the point of respecting that setting.
if [ "$PW_PRESENT" = 1 ] && [ "$PW_OFF" = 1 ]; then
  say "  启用小米桌面  $PW"
  if sh_ pm enable --user 0 "$PW" >/dev/null 2>&1; then say "                已启用"
  else say "                启用失败（继续）"; fi
fi

# A disabled Aerial is the other way to a black screen: Home still resolves to it, the package is
# frozen, so nothing draws. Switching it back on is the smallest fix there is.
if [ -n "$AERIAL" ] && is_disabled "$AERIAL"; then
  say "  启用 Aerial   $AERIAL"
  if sh_ pm enable --user 0 "$AERIAL" >/dev/null 2>&1; then say "                已启用"
  else say "                启用失败（继续）"; fi
fi

# Aerial's own Home filter is what has to let go: restore.sh takes away the accessibility service,
# the notification listener and the permissions the guard uses to put itself back. It also knows the
# TCL/Hisense boot settings, so it stays the thing that hands Home back — but only on the TV it was
# written for, where PatchWall exists.
if [ "$PW_PRESENT" = 1 ] && [ -f "$HERE/restore.sh" ]; then
  # restore.sh knows Aerial's component names, not Lite's: hand it a package it can take apart.
  RESTORE_PKG=app.orchard.tv
  case "$AERIAL" in com.ts.launcher|app.orchard.tv) RESTORE_PKG=$AERIAL ;; esac
  say "  交还 Home     tools/restore.sh"
  if ( cd "$HERE/.." && TV="$SERIAL" PKG="$RESTORE_PKG" \
       PATH="$(dirname "$ADB_BIN"):$PATH" bash "$HERE/restore.sh" ) 2>&1 | indent; then
    :
  else
    say "                restore.sh 中途出错（继续最小修复）"
  fi
else
  say "  交还 Home     不是小米电视：跳过 restore.sh，只把 Home 指回还在的那个"
fi

# Home can still be empty when it never pointed at Aerial — the case #901's reporter hit. A disabled
# launcher that is now on still needs someone to choose it.
HOME_NOW=$(resolve_home)
if [ -z "$HOME_NOW" ]; then
  TARGET_ACT=
  if [ "$PW_PRESENT" = 1 ]; then TARGET_ACT="$PW/.MainActivityUserMode"
  elif [ -n "$AERIAL" ]; then TARGET_ACT="$AERIAL/app.orchard.tv.MainActivity"; fi
  if [ -n "$TARGET_ACT" ]; then
    say "  指定桌面      $TARGET_ACT"
    if sh_ cmd package set-home-activity --user 0 "$TARGET_ACT" >/dev/null 2>&1; then
      say "                已指定"
    else
      say "                没成功（Android 6/7 没有 cmd）：按遥控器 Home 选桌面"
    fi
  fi
fi

# Wake the panel: a black screen is sometimes a sleeping TV with nothing to wake it to.
"$ADB_BIN" -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB_BIN" -s "$SERIAL" shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true

# ---------------------------------------------------------------- 5. result

head_ "结果"
HOME_NOW=$(resolve_home)
if [ -n "$HOME_NOW" ]; then
  say "  Home 现在   $HOME_NOW"
  say ""
  say "  画面应该回来了。没回来就重启电视一次（遥控器长按电源），小米要重启才认新的 Home。"
  if [ -n "$AERIAL" ]; then
    say "  想继续用 Aerial：TV=${SERIAL%%:*} tools/install-ts.sh"
  fi
  exit 0
fi

say "  Home 仍然是空的 —— 电视上大概还是没有画面。"
retry_hints
exit 1
