#!/usr/bin/env bash
# The signed, R8-minified release build of 5gto6G FieldTap, driven on an emulator the way a person uses it. Run by the
# release job of .github/workflows/android.yml.
#
# R8 shrinks, optimises and renames the release build, so a class it removed wrongly, a missing keep rule or a resource
# shrunk away shows only when that build runs. The instrumented end-to-end proof (run_e2e.sh) runs the debug build and
# cannot show it. Nothing is added to the APK under test: "adb shell uiautomator dump" reads the screen, "adb shell input
# tap" taps what shows a given text, and every text comes from the app's own string resources.
#
#   1. adb install -r, then precise location granted (and notifications from Android 13), as a person allows them in
#      Android's dialogs. The installed package must be the release: the version app/build.gradle.kts sets, not
#      debuggable, no debug automation hook.
#   2. First run: the disclosure and "Accept and continue", then Live ("Continue" first if the Permissions screen
#      shows). From API 36, whose emulator modem reports NR, Live must show a serving cell.
#   3. About shows "Version <name> (<code>)".
#   4. A session with ping and download tests: Start session, tick the tests, Start, and "Start anyway" when the
#      pre-start sheet shows. It records RECORD_SECONDS while a GPS walk is fed with "adb emu geo fix" once a second,
#      then Stop is confirmed.
#   5. The session is pulled. python -m fieldtap validate DIR --upload and python -m fieldtap report DIR must pass, and
#      the files must show a working build: GPS fixes, cell measurements joined to them, kpi rows and a serving_cell
#      event from API 36, test rows, stopped_by user and the release's version.
#   6. The app never crashed. Missing classes, members or resources its process logged are listed for review.
#
#   bash android/e2e/release_smoke.sh
#
#   RELEASE_APK      the signed release APK (default: app-release.apk below RELEASE_APK_DIR, which defaults to
#                    android/app/build/outputs/apk/release)
#   RELEASE_OUT      output directory (default ./release-smoke)
#   RECORD_SECONDS   how long the session records (default 90)
#   PYTHON           the Python that runs fieldtap (default python3)
#   ANDROID_SERIAL   device (default emulator-5554)
#
# Output: summary.txt, release.log, screens/ (a screenshot of each step), windows/ (the window dumps), sessions/ (the
# pulled session with report.html), validate/, device/ (install, package facts, the injected track), logcat/ (redacted
# as run_e2e.sh redacts). The exit status is non-zero when any step or check failed; everything is collected first.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$here/../.." && pwd)
OUT=${RELEASE_OUT:-$PWD/release-smoke}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
mkdir -p "$OUT"/{device,logcat,screens,sessions,validate,windows}
: > "$OUT/release.log"
SERIAL=${ANDROID_SERIAL:-emulator-${EMULATOR_PORT:-5554}}
PYTHON=${PYTHON:-python3}
RECORD_SECONDS=${RECORD_SECONDS:-90}
PKG=$(sed -n 's/^fieldtap\.applicationId=//p' "$repo/android/gradle.properties" | tr -d '\r[:space:]')
VERSION_NAME=$(sed -n 's/^ *versionName = "\([^"]*\)".*/\1/p' "$repo/android/app/build.gradle.kts" | head -n 1)
VERSION_CODE=$(sed -n 's/^ *versionCode = \([0-9][0-9]*\).*/\1/p' "$repo/android/app/build.gradle.kts" | head -n 1)
RES="$repo/android/app/src/main/res/values"
FILES="/sdcard/Android/data/$PKG/files"
WINDOW_ON_DEVICE=/sdcard/fieldtap-release-window.xml

adb_bin=$(command -v adb || echo "${ANDROID_HOME:-}/platform-tools/adb")
ADB=("$adb_bin" -s "$SERIAL")
raw=$(mktemp -d)
: > "$raw/pids.txt"
FAILURES=()
STEPS=()
FEEDER_PID=""
LOGCAT_PID=""
API=0
APK=""
SESSION=""
MATCHED=""
TAP_X=""
TAP_Y=""

log() { printf '[release %s] %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "$OUT/release.log" >&2; }
ok() {
  STEPS+=("ok   $*")
  log "ok: $*"
}
fail() {
  FAILURES+=("$*")
  STEPS+=("FAIL $*")
  log "FAIL: $*"
}

# dsh ARGS...  "adb shell" with carriage returns removed and no stdin; returns adb's status.
dsh() { timeout "${T:-60}" "${ADB[@]}" shell "$@" < /dev/null | tr -d '\r'; }

# Masks anything shaped like an identifier, as run_e2e.sh and probe.sh do: keyed IMEI/IMSI/ICCID/number/ID values, 14 to
# 20 digit runs, the emulator's 1555521xxxx line numbers and MAC addresses.
redact() {
  sed -E \
    -e 's/([A-Za-z_]*(imei|imsi|iccid|icc_?id|meid|msisdn|line_?1_?number|phone_?number|incoming_?number|subscriber_?id|device_?id|android_?id|advertising_?id|sim_?serial)"?[[:space:]]*[=:][[:space:]]*)[^[:space:],;})]+/\1<redacted>/gI' \
    -e 's/(^|[^[:alnum:].])[0-9]{14,20}($|[^0-9])/\1<redacted>\2/g' \
    -e 's/\+?1?555521[0-9]{4}/<redacted>/g' \
    -e 's/([[:xdigit:]]{2}:){5}[[:xdigit:]]{2}/<mac>/g'
}

# app_string NAME [ARG]...  the app's string NAME from res/values, %1$s-style arguments filled in, so every tap follows
# the app's wording instead of a copy of it.
app_string() {
  "$PYTHON" - "$RES" "$@" << 'EOF'
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

values, name, args = Path(sys.argv[1]), sys.argv[2], sys.argv[3:]
for path in sorted(values.glob("strings*.xml")):
    for element in ET.parse(path).getroot().iter("string"):
        if element.get("name") == name:
            text = "".join(element.itertext()).replace("\\'", "'").replace('\\"', '"')
            print(re.sub(r"%(\d+)\$[sd]", lambda match: args[int(match.group(1)) - 1], text))
            sys.exit(0)
sys.exit("no string resource " + name)
EOF
}

load_strings() {
  S_ACCEPT=$(app_string disclosure_accept)
  S_CONTINUE=$(app_string permissions_continue)
  S_START_SESSION=$(app_string live_start)
  S_MORE=$(app_string live_action_more)
  S_ABOUT=$(app_string live_menu_about)
  S_VERSION=$(app_string about_version "$VERSION_NAME" "$VERSION_CODE")
  S_START_TITLE=$(app_string live_start_dialog_title)
  S_TESTS=$(app_string live_tests_title)
  S_START=$(app_string live_start_confirm)
  S_START_ANYWAY=$(app_string prestart_start_anyway)
  S_BLOCKED=$(app_string prestart_title_blocked)
  S_RECORDING=$(app_string live_recording)
  S_STOP_TITLE=$(app_string live_stop_dialog_title)
  S_STOP=$(app_string live_stop_confirm)
  # "PCI 555" on Live's hero tile, without the number.
  S_PCI=$(app_string live_pci 0)
  S_PCI=${S_PCI%0}
}

shot() {
  timeout 60 "${ADB[@]}" exec-out screencap -p < /dev/null > "$OUT/screens/$1.png" 2> /dev/null || log "screencap $1 failed"
}

# dump_window NAME  the active window's hierarchy as windows/NAME.xml. uiautomator waits for the screen to be idle and
# can give up while it keeps changing, so it is retried.
dump_window() {
  local name=$1 attempt output
  for attempt in 1 2 3 4 5; do
    output=$(T=45 dsh uiautomator dump "$WINDOW_ON_DEVICE" 2>&1 || true)
    case $output in
      *"dumped to"*)
        rm -f "$OUT/windows/$name.xml"
        if timeout 60 "${ADB[@]}" pull "$WINDOW_ON_DEVICE" "$OUT/windows/$name.xml" < /dev/null > /dev/null 2>&1 &&
          [ -s "$OUT/windows/$name.xml" ]; then
          return 0
        fi
        ;;
    esac
    printf '%s attempt %s: %s\n' "$name" "$attempt" "$output" >> "$OUT/device/uiautomator-errors.txt"
    sleep 1
  done
  return 1
}

# find_node FILE MODE PATTERN [MODE PATTERN]...  prints "INDEX X Y", the centre of the first enabled node with non-empty
# bounds that matches an alternative; alternatives are tried in order and INDEX counts them from 0. MODE is text (the
# whole visible text), desc (part of the content description), has (part of either), res (the resource id) or checked
# (a ticked box; PATTERN is ignored). The exit status is 1 when nothing matches.
find_node() {
  "$PYTHON" - "$@" << 'EOF'
import re
import sys
import xml.etree.ElementTree as ET

path, pairs = sys.argv[1], sys.argv[2:]
try:
    nodes = list(ET.parse(path).getroot().iter("node"))
except (OSError, ET.ParseError):
    sys.exit(1)
for index in range(0, len(pairs) - 1, 2):
    mode, pattern = pairs[index], pairs[index + 1]
    for node in nodes:
        text = " ".join((node.get("text") or "").split())
        desc = node.get("content-desc") or ""
        if mode == "text":
            hit = text == pattern
        elif mode == "desc":
            hit = pattern in desc
        elif mode == "has":
            hit = pattern in text or pattern in desc
        elif mode == "res":
            hit = node.get("resource-id") == pattern
        elif mode == "checked":
            hit = node.get("checked") == "true"
        else:
            sys.exit("unknown mode " + mode)
        if not hit or node.get("enabled") == "false":
            continue
        bounds = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds") or "")
        if not bounds:
            continue
        x1, y1, x2, y2 = (int(value) for value in bounds.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        print(index // 2, (x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
EOF
}

# wait_for NAME SECONDS MODE PATTERN [MODE PATTERN]...  dumps the window as windows/NAME.xml until an alternative
# matches; sets MATCHED to its index and TAP_X and TAP_Y to its centre. Taps "Wait" on an "isn't responding" dialog, which
# a slow emulator can show over any app. Returns 1 after SECONDS.
wait_for() {
  local name=$1 seconds=$2 deadline found
  shift 2
  deadline=$(($(date +%s) + seconds))
  while :; do
    if dump_window "$name"; then
      if found=$(find_node "$OUT/windows/$name.xml" "$@"); then
        read -r MATCHED TAP_X TAP_Y <<< "$found"
        return 0
      fi
      if found=$(find_node "$OUT/windows/$name.xml" res android:id/aerr_wait); then
        read -r _ TAP_X TAP_Y <<< "$found"
        log "$name: a not-responding dialog shows; tapping Wait"
        dsh input tap "$TAP_X" "$TAP_Y" > /dev/null || true
      fi
    fi
    [ "$(date +%s)" -lt "$deadline" ] || return 1
    sleep 2
  done
}

# tap_matched NAME  taps what wait_for found.
tap_matched() {
  dsh input tap "$TAP_X" "$TAP_Y" > /dev/null || true
  log "$1: tapped alternative $MATCHED at $TAP_X,$TAP_Y"
  sleep 2
}

# tap_on NAME SECONDS MODE PATTERN [MODE PATTERN]...  wait_for, then a tap on what matched.
tap_on() {
  local name=$1 seconds=$2
  if ! wait_for "$@"; then
    shot "$name"
    fail "$name: nothing on screen matched [${*:3}] within $seconds s, see windows/$name.xml and screens/$name.png"
    return 1
  fi
  tap_matched "$name"
}

remember_pid() { dsh pidof "$PKG" >> "$raw/pids.txt" 2> /dev/null || true; }

wait_for_boot() {
  timeout 300 "${ADB[@]}" wait-for-device < /dev/null
  for _ in $(seq 150); do
    [ "$(dsh getprop sys.boot_completed 2> /dev/null || true)" = 1 ] && break
    sleep 2
  done
  if [ "$(dsh getprop sys.boot_completed 2> /dev/null || true)" != 1 ]; then
    fail "the emulator did not finish booting"
    return 1
  fi
  API=$(dsh getprop ro.build.version.sdk)
  API=${API:-0}
  log "device $SERIAL, API $API, $(dsh getprop ro.build.fingerprint)"
}

prepare_device() {
  # Screen on and unlocked, location on, light theme at the default font size.
  dsh svc power stayon true > /dev/null || true
  dsh settings put system screen_off_timeout 1800000 > /dev/null || true
  dsh input keyevent KEYCODE_WAKEUP > /dev/null || true
  dsh wm dismiss-keyguard > /dev/null || true
  dsh cmd location set-location-enabled true > /dev/null 2>&1 || true
  dsh cmd uimode night no > /dev/null || true
  dsh settings put system font_scale 1.0 > /dev/null || true
}

start_logcat() {
  timeout 60 "${ADB[@]}" logcat -c < /dev/null || true
  "${ADB[@]}" logcat -v threadtime -b main,system,crash < /dev/null > "$raw/logcat.txt" 2>&1 &
  LOGCAT_PID=$!
}

install_release() {
  local perm package flags
  timeout 120 "${ADB[@]}" uninstall "$PKG" < /dev/null > /dev/null 2>&1 || true
  if ! timeout 300 "${ADB[@]}" install -r "$APK" < /dev/null > "$OUT/device/install.txt" 2>&1; then
    fail "adb install -r ${APK##*/} failed, see device/install.txt"
    return 1
  fi
  ok "adb install -r ${APK##*/}"
  # What a person allows in Android's dialogs. Not the Phone permission: the app asks for it only from Settings.
  for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
    dsh pm grant "$PKG" "android.permission.$perm" > /dev/null || fail "pm grant $perm failed"
  done
  if [ "$API" -ge 33 ]; then
    dsh pm grant "$PKG" android.permission.POST_NOTIFICATIONS > /dev/null || fail "pm grant POST_NOTIFICATIONS failed"
  fi
  package=$(dsh dumpsys package "$PKG" || true)
  grep -E '^ *(versionCode|versionName|pkgFlags|flags|installerPackageName)=|^ *android\.permission\.[A-Z_]+: granted=' \
    <<< "$package" > "$OUT/device/package.txt" || true
  if grep -qE "^ *versionName=${VERSION_NAME//./\\.}\$" <<< "$package"; then
    ok "installed versionName $VERSION_NAME"
  else
    fail "the installed package is not versionName $VERSION_NAME, see device/package.txt"
  fi
  if grep -qE "^ *versionCode=$VERSION_CODE( |\$)" <<< "$package"; then
    ok "installed versionCode $VERSION_CODE"
  else
    fail "the installed package is not versionCode $VERSION_CODE, see device/package.txt"
  fi
  flags=$(grep -E '^ *(pkgFlags|flags)=' <<< "$package" || true)
  case $flags in
    "") fail "dumpsys package shows no flags for $PKG, so debuggable cannot be checked" ;;
    *DEBUGGABLE*) fail "the installed package is debuggable" ;;
    *) ok "the installed package is not debuggable" ;;
  esac
  if grep -q 'com\.fieldtap\.debug' <<< "$package"; then
    fail "the installed package has the debug automation hook"
  else
    ok "the installed package has no debug automation hook"
  fi
}

# start_feeder  a walking GPS track, one "geo fix" a second, in the background until finish. Every fix sent is logged
# with the host's clock in device/injected-track.csv.
start_feeder() {
  if ! "$PYTHON" "$here/check_e2e.py" plan-walk --seconds 3600 > "$raw/walk-plan.csv"; then
    fail "could not plan the GPS walk"
    return 1
  fi
  echo "host_ms,lat,lon" > "$OUT/device/injected-track.csv"
  (
    trap 'exit 0' TERM
    while IFS=, read -r lat lon alt; do
      next=$(($(date +%s%3N) + 1000))
      # geo fix takes longitude first.
      if timeout 5 "${ADB[@]}" emu geo fix "$lon" "$lat" "$alt" 8 < /dev/null > /dev/null 2>&1; then
        echo "$(date +%s%3N),$lat,$lon" >> "$OUT/device/injected-track.csv"
      fi
      now=$(date +%s%3N)
      if [ "$next" -gt "$now" ]; then
        sleep "$(awk -v ms=$((next - now)) 'BEGIN { printf "%.3f", ms / 1000 }')"
      fi
    done < "$raw/walk-plan.csv"
  ) &
  FEEDER_PID=$!
  log "feeding a GPS walk"
}

first_run() {
  local component
  component=$(dsh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tail -n 1)
  case $component in
    */*) ;;
    *)
      fail "the release APK has no launcher activity: $component"
      return 1
      ;;
  esac
  dsh am start -W -n "$component" > "$OUT/device/launch.txt" 2>&1 || true
  if ! wait_for 01-disclosure 90 text "$S_ACCEPT"; then
    shot 01-disclosure
    fail "the disclosure did not show \"$S_ACCEPT\" within 90 s, see windows/01-disclosure.xml"
    return 1
  fi
  remember_pid
  shot 01-disclosure
  ok "the first run opens on the disclosure"
  tap_matched 01-disclosure
  # Precise location is granted, so the app goes on to Live; if the Permissions screen shows, Continue leads there.
  if ! wait_for 02-onboarded 60 text "$S_START_SESSION" text "$S_CONTINUE"; then
    shot 02-onboarded
    fail "neither Live nor the Permissions screen showed after the disclosure"
    return 1
  fi
  if [ "$MATCHED" = 1 ]; then
    shot 02-permissions
    tap_matched 02-permissions
    if ! wait_for 03-live 60 text "$S_START_SESSION"; then
      shot 03-live
      fail "Live did not show after Continue"
      return 1
    fi
  fi
  ok "Live shows after the disclosure"
  if [ "$API" -ge 36 ]; then
    if wait_for 03-live-cell 180 has "$S_PCI"; then
      ok "Live shows a serving cell"
    else
      fail "Live showed no serving cell (\"$S_PCI...\") within 180 s, see windows/03-live-cell.xml"
    fi
  fi
  shot 03-live
}

about() {
  tap_on 04-menu 30 desc "$S_MORE" || return 1
  tap_on 04-menu-open 20 text "$S_ABOUT" || return 1
  if wait_for 04-about 30 text "$S_VERSION"; then
    ok "About shows \"$S_VERSION\""
  else
    fail "About does not show \"$S_VERSION\", see windows/04-about.xml"
  fi
  shot 04-about
  dsh input keyevent KEYCODE_BACK > /dev/null || true
  sleep 2
  if ! wait_for 04-back 30 text "$S_START_SESSION"; then
    shot 04-back
    fail "Back from About did not return to Live"
    return 1
  fi
}

record() {
  local started elapsed midway=false
  tap_on 05-live 30 text "$S_START_SESSION" || return 1
  if ! wait_for 05-start-dialog 30 text "$S_START_TITLE"; then
    shot 05-start-dialog
    fail "the Start dialog did not show"
    return 1
  fi
  # Ping and download are off by default (decision 6). With them on, the session runs every part of the minified app.
  if ! find_node "$OUT/windows/05-start-dialog.xml" checked - > /dev/null; then
    tap_on 05-start-dialog 10 text "$S_TESTS" || return 1
  fi
  if wait_for 05-tests 10 checked -; then
    ok "ping and download tests ticked in the Start dialog"
  else
    fail "the tests box in the Start dialog did not tick, see windows/05-tests.xml"
  fi
  shot 05-start-dialog
  tap_on 05-start 10 text "$S_START" || return 1
  if ! wait_for 06-starting 90 text "$S_START_ANYWAY" desc "$S_RECORDING" text "$S_BLOCKED"; then
    shot 06-starting
    fail "neither the pre-start sheet nor a recording session showed after Start"
    return 1
  fi
  case $MATCHED in
    0)
      shot 06-prestart-sheet
      tap_matched 06-prestart-sheet
      if ! wait_for 07-recording 60 desc "$S_RECORDING"; then
        shot 07-recording
        fail "no recording session after Start anyway"
        return 1
      fi
      ;;
    2)
      shot 06-prestart-blocked
      fail "the pre-start sheet blocks the start, see windows/06-starting.xml"
      return 1
      ;;
  esac
  started=$(date +%s)
  remember_pid
  ok "the session is recording"
  while :; do
    elapsed=$(($(date +%s) - started))
    [ "$elapsed" -lt "$RECORD_SECONDS" ] || break
    if [ "$midway" = false ] && [ "$elapsed" -ge $((RECORD_SECONDS / 2)) ]; then
      shot 07-recording
      midway=true
    fi
    if [ -z "$(dsh pidof "$PKG" || true)" ]; then
      fail "the app's process died after $elapsed s of recording"
      return 1
    fi
    sleep 5
  done
  remember_pid
  tap_on 08-stop 30 desc "$S_RECORDING" || return 1
  if ! wait_for 08-stop-dialog 30 text "$S_STOP_TITLE"; then
    shot 08-stop-dialog
    fail "the Stop dialog did not show"
    return 1
  fi
  shot 08-stop-dialog
  tap_on 08-stop-confirm 10 text "$S_STOP" || return 1
  if ! wait_for 09-stopped 90 text "$S_START_SESSION"; then
    shot 09-stopped
    fail "Live did not return to \"$S_START_SESSION\" after Stop"
    return 1
  fi
  shot 09-stopped
  ok "the session stopped after $(($(date +%s) - started)) s"
}

pull_session() {
  local dirs count json
  dirs=$(dsh ls -1 "$FILES/sessions" 2> /dev/null || true)
  count=$(grep -c . <<< "$dirs" || true)
  if [ "$count" != 1 ]; then
    fail "expected one session on the device, found ${count:-0}: $(tr '\n' ' ' <<< "$dirs")"
    return 1
  fi
  SESSION=$(head -n 1 <<< "$dirs")
  for _ in $(seq 30); do
    json=$(dsh cat "$FILES/sessions/$SESSION/session.json" 2> /dev/null || true)
    case $json in *'"stopped_utc": "'*) break ;; esac
    sleep 1
  done
  rm -rf "${OUT:?}/sessions/$SESSION"
  if ! timeout 300 "${ADB[@]}" pull "$FILES/sessions/$SESSION" "$OUT/sessions/" < /dev/null > /dev/null 2>&1; then
    fail "could not pull session $SESSION"
    return 1
  fi
  ok "pulled session $SESSION"
}

check_session() {
  local dir="$OUT/sessions/$SESSION" status
  if (cd "$repo" && "$PYTHON" -m fieldtap validate "$dir" --upload) > "$OUT/validate/validate.txt" 2>&1; then status=0; else status=$?; fi
  echo "$status" > "$OUT/validate/validate.exit"
  if [ "$status" -eq 0 ]; then
    ok "fieldtap validate --upload"
  else
    fail "fieldtap validate --upload exited $status: $(tail -n 5 "$OUT/validate/validate.txt" | tr '\n' ' ')"
  fi
  if (cd "$repo" && "$PYTHON" -m fieldtap report "$dir") > "$OUT/validate/report.txt" 2>&1 && [ -s "$dir/report.html" ]; then
    ok "fieldtap report wrote report.html"
  else
    fail "fieldtap report failed: $(tail -n 5 "$OUT/validate/report.txt" | tr '\n' ' ')"
  fi
  if "$PYTHON" - "$dir" "$VERSION_NAME" "$VERSION_CODE" "$API" "$RECORD_SECONDS" > "$OUT/validate/files.txt" 2>&1 << 'EOF'
import csv
import json
import sys
from pathlib import Path

session = Path(sys.argv[1])
version_name, version_code, api, record_seconds = sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
failed = 0


def check(name, passed, detail):
    global failed
    print("%s %s: %s" % ("ok  " if passed else "FAIL", name, detail))
    failed += 0 if passed else 1


def rows(name):
    with open(session / name, newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


meta = json.loads((session / "session.json").read_text(encoding="utf-8"))
kpi, cellinfo, track, events, traffic = (rows(n) for n in ("kpi.csv", "cellinfo.csv", "track.csv", "events.csv", "traffic.csv"))
transport = meta.get("transport") or {}
summary = meta.get("summary") or {}
check("the release's version in session.json",
      transport.get("app_version") == version_name and transport.get("version_code") == version_code,
      "%s (%s)" % (transport.get("app_version"), transport.get("version_code")))
check("stopped by the user", summary.get("stopped_by") == "user", summary.get("stopped_by"))
check("no layer-3 capability", (meta.get("capabilities") or {}).get("layer3") is False, meta.get("capabilities"))
check("GPS fixes from the fed walk", len(track) >= record_seconds // 3, "%d track rows in %d s" % (len(track), record_seconds))
check("cell measurements", len(cellinfo) > 0, "%d cellinfo rows" % len(cellinfo))
positioned = sum(1 for row in cellinfo if row.get("lat") and row.get("lon"))
check("measurements joined to a GPS fix", positioned > 0, "%d of %d cellinfo rows positioned" % (positioned, len(cellinfo)))
kinds = sorted({row.get("kind") or "" for row in events})
if api >= 36:
    kpi_positioned = sum(1 for row in kpi if row.get("lat") and row.get("lon"))
    check("kpi rows from the modem's cell, positioned", len(kpi) > 0 and kpi_positioned > 0,
          "%d kpi rows, %d positioned" % (len(kpi), kpi_positioned))
    check("a serving_cell event", "serving_cell" in kinds, "event kinds %s" % ", ".join(kinds))
tests = sorted({row.get("test") or "" for row in traffic})
passed_tests = sum(1 for row in traffic if row.get("ok") in ("1", "True", "true"))
check("ping and download rows", len(traffic) > 0,
      "%d traffic rows (%s), %d succeeded" % (len(traffic), ", ".join(tests), passed_tests))
print("plmns %s, collection %s" % (summary.get("plmns"), json.dumps(meta.get("collection"), sort_keys=True)[:400]))
sys.exit(1 if failed else 0)
EOF
  then
    ok "the session files hold what a working build writes"
  else
    fail "the session files miss what a working build writes, see validate/files.txt"
  fi
}

summarise() {
  {
    echo "5gto6G FieldTap release build on API $API, $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "APK ${APK##*/}, $([ -f "$APK" ] && stat -c %s "$APK") bytes, package $PKG, version $VERSION_NAME ($VERSION_CODE)"
    if [ ${#FAILURES[@]} -eq 0 ]; then echo "PASS"; else printf 'FAIL: %s\n' "${FAILURES[@]}"; fi
    echo
    echo "== Steps"
    [ ${#STEPS[@]} -eq 0 ] || printf '%s\n' "${STEPS[@]}"
    echo
    echo "== Session ${SESSION:-none}"
    [ ! -f "$OUT/validate/files.txt" ] || cat "$OUT/validate/files.txt"
    [ ! -f "$OUT/validate/validate.txt" ] || tail -n 3 "$OUT/validate/validate.txt"
    echo
    echo "== Missing classes, members or resources logged by the app's process (for review, not failures)"
    if [ -s "$OUT/logcat/r8-suspects.txt" ]; then cut -c1-300 "$OUT/logcat/r8-suspects.txt" | head -n 20; else echo "none"; fi
  } > "$OUT/summary.txt" 2>&1
  cat "$OUT/summary.txt"
}

finish() {
  local code=$? pids
  set +e
  if [ -n "$FEEDER_PID" ]; then
    kill "$FEEDER_PID" 2> /dev/null
    wait "$FEEDER_PID" 2> /dev/null
  fi
  remember_pid
  if [ -n "$LOGCAT_PID" ]; then
    kill "$LOGCAT_PID" 2> /dev/null
    wait "$LOGCAT_PID" 2> /dev/null
  fi
  tr -d '\r' < "$raw/logcat.txt" 2> /dev/null | redact > "$OUT/logcat/logcat.txt"
  timeout 60 "${ADB[@]}" logcat -d -v threadtime -b crash < /dev/null 2> /dev/null | tr -d '\r' | redact > "$OUT/logcat/crash.txt"
  if grep -qF "Process: $PKG," "$OUT/logcat/crash.txt"; then
    fail "$PKG crashed, see logcat/crash.txt"
  elif [ -n "$APK" ]; then
    ok "no crash of $PKG in the crash buffer"
  fi
  # What R8 removed wrongly shows up as one of these, even where the app catches it.
  pids=" $(tr -s ' \n' '  ' < "$raw/pids.txt" 2> /dev/null) "
  awk -v pids="$pids" 'index(pids, " " $3 " ") > 0 && /(ClassNotFoundException|NoClassDefFoundError|NoSuchMethodError|NoSuchMethodException|NoSuchFieldError|NoSuchFieldException|Resources\$NotFoundException)/' \
    "$OUT/logcat/logcat.txt" > "$OUT/logcat/r8-suspects.txt" 2> /dev/null
  summarise
  rm -rf "$raw"
  if [ "$code" -ne 0 ] || [ ${#FAILURES[@]} -gt 0 ]; then exit 1; fi
  exit 0
}

main() {
  APK=${RELEASE_APK:-}
  if [ -z "$APK" ]; then
    APK=$(find "${RELEASE_APK_DIR:-$repo/android/app/build/outputs/apk/release}" -name 'app-release.apk' -type f 2> /dev/null | sort | tail -n 1)
  fi
  if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    APK=""
    fail "no signed release APK: set RELEASE_APK"
    exit 1
  fi
  if [ -z "$PKG" ] || [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
    fail "could not read the application ID or the version from android/"
    exit 1
  fi
  load_strings
  log "release APK ${APK##*/}: $(stat -c %s "$APK") bytes, package $PKG, version $VERSION_NAME ($VERSION_CODE), output $OUT"
  wait_for_boot || exit 1
  prepare_device
  start_logcat
  install_release || exit 1
  start_feeder || exit 1
  first_run || exit 1
  about || true
  if ! record; then
    pull_session || true
    exit 1
  fi
  pull_session || exit 1
  check_session
}

trap finish EXIT
main "$@"
