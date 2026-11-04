"""Offline tests for the black-screen rescue; every adb call is a stub, no TV or network."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(os.environ.get('RESCUE_SCRIPT', Path(__file__).with_name('rescue.sh'))).resolve()

STUB = '''#!/usr/bin/env bash
if [ "${1:-}" = "-s" ]; then shift 2; fi
printf '%s\\n' "$*" >> "$CALL_LOG"
case "$*" in
  connect*) echo "connected to $2" ;;
  'shell getprop sys.boot_completed') printf '%s\\n' "$BOOTED" ;;
  'shell getprop ro.product.model') echo MiTV-MFTR0 ;;
  'shell getprop ro.build.version.release') echo 11 ;;
  'shell getprop ro.build.version.sdk') echo 30 ;;
  'shell cmd package resolve-activity '*)
    if [ -s "$HOME_FILE" ]; then cat "$HOME_FILE"; else printf '%s\\n' "$HOME_RESOLVE"; fi ;;
  'shell pm path com.mitv.tvhome')
    if [ "$PATCHWALL" = 1 ]; then echo package:/data/app/com.mitv.tvhome/base.apk; else exit 1; fi ;;
  'shell pm path com.ts.launcher') echo package:/data/app/com.ts.launcher/base.apk ;;
  'shell pm path app.orchard.tv'|'shell pm path app.orchard.tv.lite') exit 1 ;;
  'shell pm list packages -d') printf '%s\\n' "$DISABLED" ;;
  'shell settings get secure enabled_accessibility_services') echo null ;;
  'shell settings get secure enabled_notification_listeners') echo null ;;
  'shell settings get secure screensaver_components') echo null ;;
  'shell settings get global aerial_vt_home_pkg'|'shell settings get global aerial_boot_app_componentname'|\\
  'shell settings get system boot_app_componentname') echo null ;;
  'shell getprop persist.vt.use_home_pkg') ;;
  'shell dumpsys power') echo '  mWakefulness=Asleep' ;;
  'shell pm enable --user 0 '*) ;;
  'shell cmd package set-home-activity --user 0 '*)
    for a in "$@"; do last=$a; done
    printf '%s' "$last" > "$HOME_FILE" ;;
  'shell cmd role remove-role-holder '*) ;;
  'shell cmd notification disallow_listener '*) exit 127 ;;
  'shell get-state') echo "$STATE" ; exit 1 ;;
  'shell am broadcast '*|'shell pm revoke '*|'shell appops set '*|'shell am force-stop '*|\\
  'shell settings delete '*|'shell settings put '*|'shell input keyevent '*) ;;
  *) echo "unexpected adb: $*" >&2; exit 99 ;;
esac
'''


class RescueTest(unittest.TestCase):
    def run_rescue(self, *args, home='', patchwall=1, disabled='', booted='1', state='error: device not found', timeout='2'):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            log = root / 'calls'
            adb = root / 'adb'
            adb.write_text(STUB)
            adb.chmod(0o755)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                       TV='example.invalid', CALL_LOG=str(log), HOME_RESOLVE=home,
                       PATCHWALL=str(patchwall), DISABLED=disabled, BOOTED=booted, STATE=state,
                       RESCUE_TIMEOUT=timeout, ADB=str(adb), HOME_FILE=str(root / 'home'))
            for name in ('SHELLOPTS', 'BASHOPTS', 'BASH_ENV', 'POSIXLY_CORRECT'):
                env.pop(name, None)
            result = subprocess.run(['bash', str(SCRIPT), *args], env=env,
                                    capture_output=True, text=True, timeout=180)
            calls = log.read_text().splitlines() if log.exists() else []
            return result, calls

    def test_black_screen_with_no_home_gets_patchwall_back(self):
        """PatchWall disabled, Home resolving to nothing: enable it, then choose it."""
        result, calls = self.run_rescue(disabled='package:com.mitv.tvhome')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        enable = 'shell pm enable --user 0 com.mitv.tvhome'
        set_home = 'shell cmd package set-home-activity --user 0 com.mitv.tvhome/.MainActivityUserMode'
        self.assertIn('没有应用接管 Home', result.stdout)
        self.assertIn('已安装，但被禁用了', result.stdout)
        self.assertGreaterEqual(calls.count(enable), 1)
        self.assertIn(set_home, calls)
        self.assertLess(calls.index(enable), calls.index(set_home))
        self.assertIn('已指定', result.stdout)
        self.assertIn('Home 现在', result.stdout)
        self.assertNotIn('unexpected adb:', result.stderr)

    def test_check_only_reports(self):
        result, calls = self.run_rescue('--check', home='com.ts.launcher/app.orchard.tv.MainActivity')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('只看不改', result.stdout)
        self.assertFalse(any(c.startswith('shell pm enable') for c in calls))
        self.assertFalse(any(c.startswith('shell cmd package set-home-activity') for c in calls))
        self.assertFalse(any(c.startswith('shell am broadcast') for c in calls))

    def test_a_tv_that_never_answers_says_what_to_try(self):
        result, calls = self.run_rescue(booted='', state="error: device 'example.invalid:5555' not found")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('没有响应', result.stdout)
        self.assertIn('--scan', result.stdout)
        self.assertIn('tools/rescue.sh --scan', result.stdout)

    def test_disabled_aerial_on_a_tv_without_patchwall_is_frozen_back_on(self):
        """Not a Xiaomi: there is no stock launcher to hand Home to, so Aerial has to come back."""
        result, calls = self.run_rescue(patchwall=0, disabled='package:com.ts.launcher')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('shell pm enable --user 0 com.ts.launcher', calls)
        self.assertIn('shell cmd package set-home-activity --user 0 com.ts.launcher/app.orchard.tv.MainActivity', calls)
        self.assertIn('不是小米电视', result.stdout)
        self.assertNotIn('unexpected adb:', result.stderr)

    def test_a_home_that_still_works_is_left_alone(self):
        """Home resolves to some other launcher that is installed: rescue must not take it away."""
        result, calls = self.run_rescue(home='org.example/.Home')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn('shell cmd package set-home-activity --user 0 com.mitv.tvhome/.MainActivityUserMode', calls)
        self.assertIn('Home 现在   org.example/.Home', result.stdout)


if __name__ == '__main__':
    unittest.main()
