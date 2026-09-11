#!/usr/bin/env bash
# The minified release APK on the emulator. R8 shrinks and obfuscates the release build, so a class it removed wrongly,
# a keep rule missing or a resource shrunk away shows only when that build runs. This installs the release APK the build
# job signed for the test, launches it, and requires it to be running with no crash 15 s later, with a screenshot of its
# first screen. It then uninstalls it, so the debug build installed next has no signature to clash with.
#
#   bash android/e2e/release_smoke.sh
#
#   RELEASE_APK_DIR  where the signed release APK is (the newest *release*.apk below it)
#   RELEASE_OUT      output directory (default ./release-smoke)
#   ANDROID_SERIAL   device (default emulator-5554)
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$here/../.." && pwd)
OUT=${RELEASE_OUT:-$PWD/release-smoke}
mkdir -p "$OUT"
SERIAL=${ANDROID_SERIAL:-emulator-${EMULATOR_PORT:-5554}}
PKG=$(sed -n 's/^fieldtap\.applicationId=//p' "$repo/android/gradle.properties" | tr -d '\r[:space:]')
adb_bin=$(command -v adb || echo "${ANDROID_HOME:-}/platform-tools/adb")
ADB=("$adb_bin" -s "$SERIAL")
FAILURES=()

log() { printf '[release %s] %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "$OUT/release.log" >&2; }
fail() { FAILURES+=("$*"); log "FAIL: $*"; }
dsh() { timeout "${T:-60}" "${ADB[@]}" shell "$@" < /dev/null | tr -d '\r'; }

apk=$(find "${RELEASE_APK_DIR:?RELEASE_APK_DIR is not set}" -name '*release*.apk' -type f | sort | tail -n 1)
if [ -z "$apk" ]; then
  log "FAIL: no release APK below $RELEASE_APK_DIR"
  exit 1
fi
log "release APK ${apk##*/}: $(stat -c %s "$apk") bytes, package $PKG"

timeout 300 "${ADB[@]}" wait-for-device
for _ in $(seq 150); do
  [ "$(dsh getprop sys.boot_completed)" = 1 ] && break
  sleep 2
done

# The end-to-end proof and the probe installed the debug build, signed with another key.
timeout 120 "${ADB[@]}" uninstall "$PKG" > "$OUT/uninstall-debug.txt" 2>&1 < /dev/null || true
timeout 60 "${ADB[@]}" logcat -c < /dev/null || true
if ! timeout 300 "${ADB[@]}" install -r "$apk" > "$OUT/install.txt" 2>&1 < /dev/null; then
  fail "adb install of the release APK failed, see install.txt"
else
  dsh input keyevent KEYCODE_WAKEUP > /dev/null || true
  dsh wm dismiss-keyguard > /dev/null || true
  component=$(dsh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tail -n 1)
  case $component in
    */*) dsh am start -W -n "$component" > "$OUT/launch.txt" || true ;;
    *) fail "the release APK has no launcher activity: $component" ;;
  esac
  sleep 15
  timeout 60 "${ADB[@]}" exec-out screencap -p > "$OUT/first-screen.png" < /dev/null || log "screencap failed"
  pid=$(dsh pidof "$PKG" || true)
  [ -n "$pid" ] || fail "the release build is not running 15 s after launch"
  dsh dumpsys activity activities | grep -E 'ResumedActivity' > "$OUT/resumed.txt" || true
  timeout 60 "${ADB[@]}" logcat -d -v threadtime -b crash < /dev/null > "$OUT/crash.txt" 2> /dev/null || true
  timeout 60 "${ADB[@]}" logcat -d -v threadtime < /dev/null 2> /dev/null | grep -E "AndroidRuntime|$PKG|FieldTap" > "$OUT/logcat.txt" || true
  if grep -qF "Process: $PKG," "$OUT/crash.txt"; then
    fail "the release build crashed, see crash.txt"
  fi
fi
timeout 120 "${ADB[@]}" uninstall "$PKG" > "$OUT/uninstall.txt" 2>&1 < /dev/null || true

if [ ${#FAILURES[@]} -eq 0 ]; then
  log "PASS: the minified release build installs, launches and runs with no crash"
  exit 0
fi
printf 'FAIL: %s\n' "${FAILURES[@]}" | tee -a "$OUT/release.log"
exit 1
