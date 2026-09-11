#!/usr/bin/env bash
# FieldTap end-to-end proof on an Android emulator, run by the emulator job of .github/workflows/android.yml.
# It needs a booted emulator, the debug APK and the instrumented-test APK, and Python 3.9+ for fieldtap.
#
#   bash android/e2e/run_e2e.sh
#
# What it proves, in order:
#   1. First run in light, dark and font scale 1.3 (FirstRunScreensTest): the disclosure shows before any
#      permission prompt. Screenshots of the disclosure and of the Permissions screen.
#   2. The walk (EndToEndWalkTest), through the real UI, while this script feeds a walking GPS track with
#      "adb emu geo fix" once a second and cycles "adb emu gsm signal-profile": consent, permissions, a serving
#      cell with its age on Live, ping 10.0.2.2 and a 1 MB download, a session with a marker stopped after
#      WALK_SECONDS, its detail, and its zip exported and shared.
#      The LTE and NR checks (a serving cell on Live, kpi rows, serving_cell events) apply when the modem reports an
#      LTE or NR cell, which API 36 must. A modem reporting only other cells, like the API 31 emulator's single GSM
#      cell, instead gets a session checked to hold no kpi row and Live checked to say so.
#   3. Every screen in each variant of VARIANTS (ScreenTourTest), each at every scroll position.
#   4. Location services switched off mid-session (LocationOffTest), with a privacy zone far from the walk: the files
#      say so with gps_lost, a marker that waits for a fix is dropped when the wait outlasts its limit and the Session
#      detail screen says so, and gps_restored follows once location is back on.
#   5. Process death: a session started through the debug automation hook is killed with "am force-stop" and
#      relaunched with "am start"; another is killed with "kill -9" and relaunched by RecoveryUiTest. Each must be
#      closed as interrupted, with the exit reason Android recorded for the killed process.
#   6. fieldtap validate --upload and fieldtap report on every session, fieldtap validate on the exported zip, and
#      check_e2e.py's assertions on what the files and screenshots hold.
#
# Environment:
#   APK_DIR         where app-debug.apk and app-debug-androidTest.apk are, searched recursively
#   E2E_OUT         output directory (default ./e2e-out)
#   WALK_SECONDS    how long the walk session records (default 180)
#   VARIANTS        the looks the first run and the screen tour are taken in, from light, dark, font130 and landscape
#                   (default "light dark font130"); the walk, the location-off test and the recovery run once, in light
#   PYTHON          the Python that runs fieldtap and check_e2e.py (default python3)
#   ANDROID_SERIAL  device (default emulator-5554)
#
# Output: summary.txt, e2e.log, sessions/ (each with report.html), device/ (screenshots, result JSON, the exported
# zip, failure captures), instrumentation/ (raw "am instrument -r" output and JUnit XML), validate/, checks/,
# recovery/, logcat/ (redacted like the probe's). The exit status is non-zero when any step failed; everything is
# collected first.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$here/../.." && pwd)
OUT=${E2E_OUT:-$PWD/e2e-out}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
APK_DIR=${APK_DIR:-$repo/android/app/build/outputs/apk}
WALK_SECONDS=${WALK_SECONDS:-180}
PYTHON=${PYTHON:-python3}
SERIAL=${ANDROID_SERIAL:-emulator-${EMULATOR_PORT:-5554}}
CHECKER="$here/check_e2e.py"
PKG=$(sed -n 's/^fieldtap\.applicationId=//p' "$repo/android/gradle.properties" | tr -d '\r[:space:]')
TEST_PKG="$PKG.test"
RUNNER="$TEST_PKG/androidx.test.runner.AndroidJUnitRunner"
TESTS=com.fieldtap.e2e
FILES="/sdcard/Android/data/$PKG/files"
# How long a session records before it is killed: several of its 5 s heartbeats.
RECORD_BEFORE_KILL=${RECORD_BEFORE_KILL:-25}
read -r -a VARIANT_LIST <<< "${VARIANTS:-light dark font130}"

adb_bin=$(command -v adb || echo "${ANDROID_HOME:-}/platform-tools/adb")
ADB=("$adb_bin" -s "$SERIAL")

mkdir -p "$OUT"/{checks,device,instrumentation,logcat,recovery,sessions,validate}
: > "$OUT/e2e.log"
raw=$(mktemp -d)
FAILURES=()
FEEDER_PIDS=()
LOGCAT_PID=""
WALK_DIR=""
AUTOMATION_DIR=""
API=""
EXPECT_LTE_NR=true

log() { printf '[e2e %s] %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "$OUT/e2e.log" >&2; }
fail() { FAILURES+=("$*"); log "FAIL: $*"; }

# dsh ARGS...  "adb shell" with carriage returns removed and no stdin; returns adb's status.
dsh() { timeout "${T:-120}" "${ADB[@]}" shell "$@" < /dev/null | tr -d '\r'; }

host_ms() { date +%s%3N; }

# device_ms  the device's wall clock, Unix milliseconds.
device_ms() {
  local value
  value=$(dsh date +%s%3N 2> /dev/null || true)
  if [[ ! $value =~ ^[0-9]{13}$ ]]; then
    value="$(dsh date +%s 2> /dev/null || true)000"
  fi
  printf '%s' "$value"
}

# json_get FILE KEY  a top-level value of a JSON file, empty when the file or the key is missing.
json_get() {
  "$PYTHON" - "$1" "$2" << 'EOF'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as fh:
        value = json.load(fh).get(sys.argv[2])
except (OSError, ValueError):
    value = None
print("" if value is None else ("true" if value is True else "false" if value is False else value))
EOF
}

# Masks anything shaped like an identifier, as probe.sh does: keyed IMEI/IMSI/ICCID/number/ID values, 14 to 20 digit
# runs, the emulator's 1555521xxxx line numbers and MAC addresses.
redact() {
  sed -E \
    -e 's/([A-Za-z_]*(imei|imsi|iccid|icc_?id|meid|msisdn|line_?1_?number|phone_?number|incoming_?number|subscriber_?id|device_?id|android_?id|advertising_?id|sim_?serial)"?[[:space:]]*[=:][[:space:]]*)[^[:space:],;})]+/\1<redacted>/gI' \
    -e 's/(^|[^[:alnum:].])[0-9]{14,20}($|[^0-9])/\1<redacted>\2/g' \
    -e 's/\+?1?555521[0-9]{4}/<redacted>/g' \
    -e 's/([[:xdigit:]]{2}:){5}[[:xdigit:]]{2}/<mac>/g'
}

wait_for_boot() {
  local i
  timeout 300 "${ADB[@]}" wait-for-device
  for i in $(seq 150); do
    [ "$(dsh getprop sys.boot_completed 2> /dev/null || true)" = 1 ] && break
    sleep 2
  done
  if [ "$(dsh getprop sys.boot_completed 2> /dev/null || true)" != 1 ]; then
    fail "the emulator did not finish booting"
    return 1
  fi
  API=$(dsh getprop ro.build.version.sdk)
  log "device $SERIAL, API $API, $(dsh getprop ro.build.fingerprint)"
}

# set_variant light|dark|font130|landscape  the night mode, font scale and orientation the next app process starts with.
# Rotation follows the setting, not the emulator's sensor, so every variant but landscape is upright.
set_variant() {
  local night=no scale=1.0 rotation=0
  case $1 in
    light) ;;
    dark) night=yes ;;
    font130) scale=1.3 ;;
    landscape) rotation=1 ;;
    *)
      log "unknown variant $1"
      return 1
      ;;
  esac
  dsh cmd uimode night "$night" > /dev/null || log "cmd uimode night $night failed"
  dsh settings put system font_scale "$scale" > /dev/null || log "font_scale $scale failed"
  dsh settings put system accelerometer_rotation 0 > /dev/null || log "accelerometer_rotation 0 failed"
  dsh settings put system user_rotation "$rotation" > /dev/null || log "user_rotation $rotation failed"
  sleep 2
}

prepare_device() {
  # Screen on and unlocked for the whole run: Android's cell-info interval depends on it, and so do the tests.
  dsh svc power stayon true > /dev/null || true
  dsh settings put system screen_off_timeout 1800000 > /dev/null || true
  dsh input keyevent KEYCODE_WAKEUP > /dev/null || true
  dsh wm dismiss-keyguard > /dev/null || true
  dsh cmd location set-location-enabled true > /dev/null 2>&1 || true
  {
    echo "api=$API"
    echo "location_enabled=$(dsh cmd location is-location-enabled 2> /dev/null || echo unknown)"
    echo "gsm.operator.numeric=$(dsh getprop gsm.operator.numeric)"
    # "Physical size: 1080x2400" and "Physical density: 420": the screen every screenshot is checked against.
    echo "screen=$(dsh wm size 2> /dev/null | head -n 1 | sed 's/^[^0-9]*//')"
    echo "density=$(dsh wm density 2> /dev/null | head -n 1 | sed 's/^[^0-9]*//')"
    echo "variants=${VARIANT_LIST[*]}"
  } > "$OUT/checks/device.txt"
  log "screen $(grep '^screen=' "$OUT/checks/device.txt" | cut -d= -f2) px at $(grep '^density=' "$OUT/checks/device.txt" | cut -d= -f2) dpi, variants ${VARIANT_LIST[*]}"
  set_variant light
}

start_logcat() {
  "${ADB[@]}" logcat -c < /dev/null || true
  "${ADB[@]}" logcat -v threadtime -b main,system,crash,events,radio < /dev/null > "$raw/logcat.txt" 2>&1 &
  LOGCAT_PID=$!
}

install_apks() {
  local app test
  app=$(find "$APK_DIR" -name 'app-debug.apk' 2> /dev/null | sort | head -n 1)
  test=$(find "$APK_DIR" -name '*-androidTest.apk' 2> /dev/null | sort | head -n 1)
  if [ -z "$app" ] || [ -z "$test" ]; then
    fail "APKs not found under $APK_DIR (app: ${app:-none}, tests: ${test:-none})"
    return 1
  fi
  "${ADB[@]}" uninstall "$PKG" < /dev/null > /dev/null 2>&1 || true
  "${ADB[@]}" uninstall "$TEST_PKG" < /dev/null > /dev/null 2>&1 || true
  # No -g: the walk must see the app ask for its permissions itself, after the disclosure.
  if ! timeout 300 "${ADB[@]}" install -t "$app" < /dev/null > "$OUT/checks/install-app.txt" 2>&1; then
    fail "installing ${app##*/} failed, see checks/install-app.txt"
    return 1
  fi
  if ! timeout 300 "${ADB[@]}" install -t "$test" < /dev/null > "$OUT/checks/install-tests.txt" 2>&1; then
    fail "installing ${test##*/} failed, see checks/install-tests.txt"
    return 1
  fi
  dsh pm list instrumentation > "$OUT/checks/instrumentation.txt" || true
  if ! grep -qF "$RUNNER" "$OUT/checks/instrumentation.txt"; then
    fail "no instrumentation $RUNNER is installed"
    return 1
  fi
}

measure_clock_offset() {
  local before device after
  before=$(host_ms)
  device=$(device_ms)
  after=$(host_ms)
  if [[ $device =~ ^[0-9]{13}$ ]]; then
    echo $((device - (before + after) / 2)) > "$OUT/checks/clock-offset-ms.txt"
  else
    fail "could not read the device clock"
    echo 0 > "$OUT/checks/clock-offset-ms.txt"
  fi
  log "device clock minus host clock: $(cat "$OUT/checks/clock-offset-ms.txt") ms"
}

# start_feeders  a walking GPS track, one "geo fix" a second, and a signal profile change every 20 s, both in the
# background until stop_feeders. Every fix and profile sent is logged with the host's clock.
start_feeders() {
  if ! "$PYTHON" "$CHECKER" plan-walk --seconds 7200 > "$OUT/checks/walk-plan.csv"; then
    fail "could not plan the GPS walk"
    return 1
  fi
  echo "host_ms,lat,lon" > "$OUT/checks/injected-track.csv"
  (
    trap 'exit 0' TERM
    while IFS=, read -r lat lon alt; do
      next=$(($(host_ms) + 1000))
      sent=$(host_ms)
      # geo fix takes longitude first.
      if timeout 5 "${ADB[@]}" emu geo fix "$lon" "$lat" "$alt" 8 < /dev/null > /dev/null 2>&1; then
        echo "$sent,$lat,$lon" >> "$OUT/checks/injected-track.csv"
      fi
      now=$(host_ms)
      if [ "$next" -gt "$now" ]; then
        sleep "$(awk -v ms=$((next - now)) 'BEGIN { printf "%.3f", ms / 1000 }')"
      fi
    done < "$OUT/checks/walk-plan.csv"
  ) &
  FEEDER_PIDS+=($!)
  echo "host_ms,profile" > "$OUT/checks/signal-profiles.csv"
  (
    trap 'exit 0' TERM
    profiles=(4 3 2 1 0 1 2 3)
    n=0
    while true; do
      profile=${profiles[$((n % ${#profiles[@]}))]}
      if timeout 10 "${ADB[@]}" emu gsm signal-profile "$profile" < /dev/null > /dev/null 2>&1; then
        echo "$(host_ms),$profile" >> "$OUT/checks/signal-profiles.csv"
      fi
      n=$((n + 1))
      sleep 20
    done
  ) &
  FEEDER_PIDS+=($!)
  log "feeding a GPS walk and signal profiles"
}

stop_feeders() {
  local pid
  for pid in "${FEEDER_PIDS[@]}"; do kill "$pid" 2> /dev/null || true; done
  for pid in "${FEEDER_PIDS[@]}"; do wait "$pid" 2> /dev/null || true; done
  FEEDER_PIDS=()
}

# pull_device_files  what the tests wrote to e2e/ on the device: screenshots, results, the zip, failure captures.
# Pulled after every run, because "pm clear" deletes it.
pull_device_files() {
  "${ADB[@]}" pull "$FILES/e2e/." "$OUT/device/" < /dev/null > /dev/null 2>&1 || log "nothing to pull from $FILES/e2e"
}

pull_session() {
  rm -rf "${OUT:?}/sessions/$1"
  if ! timeout 300 "${ADB[@]}" pull "$FILES/sessions/$1" "$OUT/sessions/" < /dev/null > /dev/null 2>&1; then
    fail "could not pull session $1"
    return 1
  fi
}

# instrument NAME CLASS [-e KEY VALUE]...  runs one instrumented test class and pulls what it wrote; fails unless
# every test in it passed.
instrument() {
  local name=$1 class=$2
  shift 2
  local output="$OUT/instrumentation/$name.txt"
  log "instrumentation $name: $class $*"
  timeout 1800 "${ADB[@]}" shell am instrument -w -r -e class "$TESTS.$class" "$@" "$RUNNER" < /dev/null |
    tr -d '\r' > "$output" || true
  pull_device_files
  if "$PYTHON" "$CHECKER" instrumentation "$output" --junit "$OUT/instrumentation/$name.xml" --name "$name" \
    > "$OUT/instrumentation/$name.summary" 2>&1; then
    log "instrumentation $name passed"
    return 0
  fi
  cat "$OUT/instrumentation/$name.summary" >> "$OUT/e2e.log"
  fail "instrumentation $name failed, see instrumentation/$name.txt"
  return 1
}

# automation ACTION EXTRAS...  one debug automation action (android/ARCHITECTURE.md section 9). Sets
# AUTOMATION_DIR to the result's dir_name; fails unless the action reports ok.
automation() {
  local action=$1 result="$raw/automation.json" i
  shift
  AUTOMATION_DIR=""
  dsh rm -f "$FILES/automation/last-result.json" > /dev/null 2>&1 || true
  dsh am start -W -n "$PKG/com.fieldtap.debug.AutomationActivity" -a "com.fieldtap.debug.$action" "$@" \
    >> "$OUT/recovery/automation.txt" 2>&1 || true
  for i in $(seq 90); do
    if dsh cat "$FILES/automation/last-result.json" > "$result" 2> /dev/null && [ -s "$result" ]; then
      cat "$result" >> "$OUT/recovery/automation.txt"
      if [ "$(json_get "$result" ok)" = true ]; then
        AUTOMATION_DIR=$(json_get "$result" dir_name)
        return 0
      fi
      fail "automation $action failed: $(cat "$result")"
      return 1
    fi
    sleep 1
  done
  fail "automation $action wrote no result in 90 s"
  return 1
}

# wait_closed DIR  until launch recovery has written stopped_utc into DIR's session.json.
wait_closed() {
  local i
  for i in $(seq 90); do
    if dsh cat "$FILES/sessions/$1/session.json" 2> /dev/null | grep -q '"stopped_utc": "'; then
      return 0
    fi
    sleep 1
  done
  fail "launch recovery did not close $1 within 90 s"
  return 1
}

# root_shell  restarts adbd as root; true once "adb shell" runs as uid 0.
root_shell() {
  timeout 60 "${ADB[@]}" root < /dev/null >> "$OUT/recovery/adb-root.txt" 2>&1 || return 1
  timeout 60 "${ADB[@]}" wait-for-device < /dev/null || return 1
  [ "$(dsh id -u 2> /dev/null)" = 0 ]
}

# unroot_shell  back to the shell user, as the rest of the run expects.
unroot_shell() {
  timeout 60 "${ADB[@]}" unroot < /dev/null >> "$OUT/recovery/adb-root.txt" 2>&1 || log "adb unroot failed"
  timeout 60 "${ADB[@]}" wait-for-device < /dev/null || log "the device did not come back after adb unroot"
  sleep 2
}

# telephony_snapshot NAME  what the modem reports now (cell info, service state, signal), redacted, for diagnosis.
telephony_snapshot() {
  dsh dumpsys telephony.registry 2> /dev/null |
    grep -E '^[[:space:]]*(mServiceState|mSignalStrength|mCellInfo|mTelephonyDisplayInfo|mDataConnectionState)[[:space:]]*=' |
    cut -c1-4000 | redact > "$OUT/checks/telephony-$1.txt" || true
}

# lte_nr_from_snapshot FILE  reads one telephony_snapshot: "true" when it lists an LTE or NR cell; "false" when it lists
# only other cells; with no cell listed (the registry caches cell info only once something asked for it, so the API 31
# emulator shows mCellInfo=null for a while), the service state's data radio technology while in service: LTE or NR
# "true", any other known technology "false"; "unknown" otherwise.
lte_nr_from_snapshot() {
  local rat
  if grep -qE 'CellInfo(Lte|Nr):' "$1"; then
    echo true
    return 0
  fi
  if grep -qE 'mCellInfo=\[CellInfo' "$1"; then
    echo false
    return 0
  fi
  if grep -qE 'm(Voice|Data)RegState=0\(IN_SERVICE\)' "$1"; then
    rat=$(grep -oE 'getRilDataRadioTechnology=[0-9]+\([A-Za-z_0-9]+\)' "$1" | head -n 1 | sed -E 's/.*\((.*)\)/\1/')
    case $rat in
      LTE* | NR*)
        echo true
        return 0
        ;;
      "" | UNKNOWN | IWLAN) ;;
      *)
        echo false
        return 0
        ;;
    esac
  fi
  echo unknown
}

# detect_lte_nr  sets EXPECT_LTE_NR from the telephony registry once five snapshots 2 s apart agree: true when the modem
# reports LTE or NR, false when it reports another technology only (the API 31 emulator: a GSM cell on HSPA). The API 36
# emulator must report LTE or NR.
detect_lte_nr() {
  local i answer last="" streak=0 basis
  EXPECT_LTE_NR=true
  for i in $(seq 60); do
    telephony_snapshot radio
    answer=$(lte_nr_from_snapshot "$OUT/checks/telephony-radio.txt")
    if [ "$answer" != unknown ] && [ "$answer" = "$last" ]; then streak=$((streak + 1)); else streak=1; fi
    last=$answer
    if [ "$answer" != unknown ] && [ "$streak" -ge 5 ]; then break; fi
    sleep 2
  done
  basis="cells: $(grep -oE 'CellInfo[A-Za-z]+' "$OUT/checks/telephony-radio.txt" | sort -u | tr '\n' ' ')data: $(grep -oE 'getRilDataRadioTechnology=[0-9]+\([A-Za-z_0-9]+\)' "$OUT/checks/telephony-radio.txt" | head -n 1)"
  case $last in
    true)
      EXPECT_LTE_NR=true
      log "the modem reports LTE or NR ($basis): the LTE and NR checks apply"
      ;;
    false)
      EXPECT_LTE_NR=false
      log "the modem reports no LTE or NR ($basis): a session without kpi rows is expected"
      if [ "${API:-0}" -ge 36 ]; then
        fail "API $API must report LTE or NR ($basis), see checks/telephony-radio.txt"
        return 1
      fi
      ;;
    *)
      fail "the modem reported neither a cell nor a radio technology in 120 s, see checks/telephony-radio.txt"
      return 1
      ;;
  esac
}

# wait_dead PID  until the app's process PID is gone.
wait_dead() {
  local i
  for i in $(seq 40); do
    case " $(dsh pidof "$PKG" 2> /dev/null || true) " in
      *" $1 "*) sleep 0.5 ;;
      *) return 0 ;;
    esac
  done
  return 1
}

first_runs() {
  local variant
  for variant in "${VARIANT_LIST[@]}"; do
    dsh pm clear "$PKG" > /dev/null || fail "pm clear $PKG failed"
    set_variant "$variant"
    instrument "first-run-$variant" FirstRunScreensTest -e variant "$variant" || true
  done
}

walk() {
  dsh pm clear "$PKG" > /dev/null || fail "pm clear $PKG failed"
  set_variant light
  telephony_snapshot before-walk
  instrument walk EndToEndWalkTest -e walk_seconds "$WALK_SECONDS" -e expect_lte_nr "$EXPECT_LTE_NR" || true
  telephony_snapshot after-walk
  WALK_DIR=$(json_get "$OUT/device/walk-result.json" dir_name)
  if [ -z "$WALK_DIR" ]; then
    fail "the walk recorded no session"
    return 1
  fi
  pull_session "$WALK_DIR"
}

tour() {
  local variant
  if [ -z "$WALK_DIR" ]; then
    fail "no screen tour: the walk recorded no session"
    return 1
  fi
  for variant in "${VARIANT_LIST[@]}"; do
    set_variant "$variant"
    instrument "tour-$variant" ScreenTourTest -e variant "$variant" -e dir_name "$WALK_DIR" -e expect_lte_nr "$EXPECT_LTE_NR" || true
  done
  set_variant light
}

# location_off  LocationOffTest: a session with a privacy zone 10 km from the walk, location services switched off and
# on again while it records, then its Session detail; the session is pulled for check_e2e.py.
location_off() {
  local dir perm
  log "== location services switched off mid-session"
  set_variant light
  for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do
    dsh pm grant "$PKG" "android.permission.$perm" > /dev/null 2>&1 || true
  done
  instrument location-off LocationOffTest || true
  # Whatever the test got to, everything after it needs location on.
  dsh cmd location set-location-enabled true > /dev/null 2>&1 || log "could not switch location back on"
  dir=$(json_get "$OUT/device/location-off-result.json" dir_name)
  if [ -z "$dir" ]; then
    fail "the location-off test recorded no session"
    return 1
  fi
  pull_session "$dir"
}

# recovery force_stop|kill_9  a session through the automation hook, killed, relaunched, and pulled once launch
# recovery has closed it.
recovery() {
  local scenario=$1 dir pid kill_ms cause perm
  log "== process death: $scenario"
  set_variant light
  # The walk granted these through the UI; granting again keeps this test independent of the walk's outcome.
  for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do
    dsh pm grant "$PKG" "android.permission.$perm" > /dev/null 2>&1 || true
  done
  automation START_SESSION --es name "e2e-$scenario" --ez accept_consent true --ez mark_ready true || return 1
  dir=$AUTOMATION_DIR
  if [ -z "$dir" ]; then
    fail "the automation hook reported no session for $scenario"
    return 1
  fi
  sleep "$RECORD_BEFORE_KILL"
  pid=$(dsh pidof "$PKG" | awk '{ print $1 }')
  if [ -z "$pid" ]; then
    fail "$PKG is not running before the $scenario kill"
    return 1
  fi
  case $scenario in
    force_stop)
      kill_ms=$(device_ms)
      dsh am force-stop "$PKG" > "$OUT/recovery/$scenario-kill.txt" 2>&1 || true
      ;;
    kill_9)
      # The shell user may not signal an app's process. adbd runs as root on userdebug images; where it cannot,
      # run-as is the fallback, which SELinux allows on API 36 but denies on API 31 (runas_app to untrusted_app).
      if root_shell; then
        kill_ms=$(device_ms)
        dsh kill -9 "$pid" > "$OUT/recovery/$scenario-kill.txt" 2>&1 || true
        unroot_shell
      else
        kill_ms=$(device_ms)
        dsh run-as "$PKG" kill -9 "$pid" > "$OUT/recovery/$scenario-kill.txt" 2>&1 || true
      fi
      ;;
  esac
  if ! wait_dead "$pid"; then
    fail "$PKG pid $pid survived $scenario: $(tr '\n' ' ' < "$OUT/recovery/$scenario-kill.txt")"
    return 1
  fi
  sleep 2
  dsh dumpsys activity exit-info "$PKG" > "$OUT/checks/exit-info-$scenario.txt" 2>&1 || true
  printf '%s %s %s %s\n' "$scenario" "$dir" "$pid" "$kill_ms" >> "$OUT/checks/recovered.txt"
  if ! cause=$("$PYTHON" "$CHECKER" exit-reason "$OUT/checks/exit-info-$scenario.txt" --pid "$pid"); then
    fail "Android recorded no exit for pid $pid ($scenario), see checks/exit-info-$scenario.txt"
    cause=""
  fi
  log "$scenario: session $dir, pid $pid, Android's exit reason ${cause:-none}"
  case $scenario in
    force_stop)
      dsh am start -W -n "$PKG/.MainActivity" > "$OUT/recovery/$scenario-relaunch.txt" 2>&1 || true
      wait_closed "$dir" || return 1
      sleep 5
      timeout 60 "${ADB[@]}" exec-out screencap -p < /dev/null > "$OUT/recovery/$scenario-relaunch.png" || true
      dsh uiautomator dump /sdcard/e2e-window.xml > "$OUT/recovery/$scenario-uiautomator.txt" 2>&1 || true
      "${ADB[@]}" pull /sdcard/e2e-window.xml "$OUT/recovery/$scenario-relaunch.xml" < /dev/null > /dev/null 2>&1 ||
        fail "no window dump of the relaunched app after $scenario"
      ;;
    kill_9)
      if [ -n "$cause" ]; then
        instrument "recovery-$scenario" RecoveryUiTest -e recovered_dir "$dir" -e scenario "$scenario" -e expected_cause "$cause" || true
      else
        instrument "recovery-$scenario" RecoveryUiTest -e recovered_dir "$dir" -e scenario "$scenario" || true
      fi
      wait_closed "$dir" || return 1
      ;;
  esac
  pull_session "$dir"
}

# checks  fieldtap validate --upload, then fieldtap report, on every session; fieldtap validate on the zip; then
# check_e2e.py.
checks() {
  local dir name status zip
  for dir in "$OUT"/sessions/*/; do
    [ -f "$dir/session.json" ] || continue
    name=$(basename "$dir")
    if (cd "$repo" && "$PYTHON" -m fieldtap validate "$dir" --upload) > "$OUT/validate/$name.txt" 2>&1; then status=0; else status=$?; fi
    echo "$status" > "$OUT/validate/$name.exit"
    if [ "$status" -ne 0 ]; then
      fail "fieldtap validate --upload $name exited $status: $(tail -n 5 "$OUT/validate/$name.txt" | tr '\n' ' ')"
    fi
    if ! (cd "$repo" && "$PYTHON" -m fieldtap report "$dir") > "$OUT/validate/$name.report.txt" 2>&1; then
      fail "fieldtap report $name failed: $(tail -n 5 "$OUT/validate/$name.report.txt" | tr '\n' ' ')"
    fi
  done
  for zip in "$OUT"/device/*.zip; do
    [ -f "$zip" ] || continue
    name=$(basename "$zip")
    if (cd "$repo" && "$PYTHON" -m fieldtap validate "$zip") > "$OUT/validate/$name.txt" 2>&1; then status=0; else status=$?; fi
    echo "$status" > "$OUT/validate/$name.exit"
    [ "$status" -eq 0 ] || fail "fieldtap validate $name exited $status"
  done
  if ! "$PYTHON" "$CHECKER" check --out "$OUT" --repo "$repo" --walk-seconds "$WALK_SECONDS" --expect-lte-nr "$EXPECT_LTE_NR" \
    --variants "${VARIANT_LIST[*]}" > "$OUT/checks/check.txt" 2>&1; then
    fail "check_e2e.py found problems, see checks/check.txt"
  fi
  cat "$OUT/checks/check.txt" >> "$OUT/e2e.log"
}

summarise() {
  local f
  {
    echo "FieldTap end-to-end proof, API ${API:-unknown}, $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    if [ ${#FAILURES[@]} -eq 0 ]; then echo "PASS"; else printf 'FAIL: %s\n' "${FAILURES[@]}"; fi
    echo
    echo "== Instrumentation"
    for f in "$OUT"/instrumentation/*.summary; do [ -f "$f" ] && cat "$f"; done
    echo
    echo "== fieldtap validate (exit status)"
    for f in "$OUT"/validate/*.exit; do [ -f "$f" ] && echo "$(basename "$f" .exit): $(cat "$f")"; done
    echo
    echo "== Checks"
    [ -f "$OUT/checks/check.txt" ] && cat "$OUT/checks/check.txt"
  } > "$OUT/summary.txt" 2>&1
  cat "$OUT/summary.txt"
}

finish() {
  local code=$?
  set +e
  stop_feeders
  set_variant light > /dev/null 2>&1
  dsh cmd location set-location-enabled true > /dev/null 2>&1
  if [ -n "$LOGCAT_PID" ]; then
    kill "$LOGCAT_PID" 2> /dev/null
    wait "$LOGCAT_PID" 2> /dev/null
  fi
  tr -d '\r' < "$raw/logcat.txt" | redact > "$OUT/logcat/logcat.txt" 2> /dev/null
  timeout 60 "${ADB[@]}" logcat -d -v threadtime -b crash < /dev/null 2> /dev/null | tr -d '\r' | redact > "$OUT/logcat/crash.txt"
  grep -E ' (FieldTap[A-Za-z]*|TestRunner|AndroidJUnitRunner) *:' "$OUT/logcat/logcat.txt" > "$OUT/logcat/fieldtap.txt"
  if grep -qF "Process: $PKG," "$OUT/logcat/crash.txt"; then
    fail "$PKG crashed, see logcat/crash.txt"
  fi
  summarise
  rm -rf "$raw"
  if [ "$code" -ne 0 ] || [ ${#FAILURES[@]} -gt 0 ]; then exit 1; fi
  exit 0
}

main() {
  log "output $OUT, device $SERIAL, package $PKG, walk ${WALK_SECONDS} s"
  wait_for_boot || exit 1
  prepare_device
  start_logcat
  install_apks || exit 1
  measure_clock_offset
  start_feeders || exit 1
  detect_lte_nr || true
  echo "expect_lte_nr=$EXPECT_LTE_NR" >> "$OUT/checks/device.txt"
  first_runs
  instrument recording-strip RecordingStripTest || true
  walk || true
  tour || true
  location_off || true
  recovery force_stop || true
  recovery kill_9 || true
  stop_feeders
  checks
}

trap finish EXIT
main "$@"
