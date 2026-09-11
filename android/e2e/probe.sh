#!/usr/bin/env bash
# FieldTap emulator probe. Records what an Android emulator really provides (cell info,
# signal strength, location, networks, reachability), so the end-to-end test and the app's
# emulator behaviour are written against facts. It fails only if the APK does not install,
# the app is not running 10 s after launch, or the app crashes. Every other probe command
# may fail; its output and exit code are kept.
#
#   bash android/e2e/probe.sh [APK]
#
#   APK_DIR         where to look for the debug APK when none is given
#   PROBE_OUT       output directory (default ./emulator-probe)
#   PACKAGE         application ID (default: read from the APK, then android/gradle.properties)
#   ANDROID_SERIAL  device (default emulator-${EMULATOR_PORT:-5554})
#
# Subscriber and hardware identifiers are never asked for, and all saved text goes through
# redact() in case a dump or log line carries one anyway.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$here/../.." && pwd)
OUT=${PROBE_OUT:-$PWD/emulator-probe}
SERIAL=${ANDROID_SERIAL:-emulator-${EMULATOR_PORT:-5554}}
CMD_TIMEOUT=${CMD_TIMEOUT:-90}

mkdir -p "$OUT"/{device,app,telephony,signal,location,connectivity,reachability,logcat}
: > "$OUT/commands.log"
raw=$(mktemp -d)
trap 'rm -rf "$raw"' EXIT

adb_bin=$(command -v adb || echo "${ANDROID_HOME:-}/platform-tools/adb")
ADB=("$adb_bin" -s "$SERIAL")
FAILURES=()
RC=0
APK=""
PKG=""
APP_PID=""

log() { printf '[probe %s] %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "$OUT/probe.log" >&2; }
fail() { FAILURES+=("$*"); log "FAIL: $*"; }
utc() { date -u +%Y-%m-%dT%H:%M:%S.%3N+00:00; }
keep() { grep -E "$1" || true; }

# Masks anything shaped like an identifier: keyed IMEI/IMSI/ICCID/number/ID values, 14 to
# 20 digit runs, the emulator's 1555521xxxx line numbers, and MAC addresses.
redact() {
  sed -E \
    -e 's/([A-Za-z_]*(imei|imsi|iccid|icc_?id|meid|msisdn|line_?1_?number|phone_?number|incoming_?number|subscriber_?id|device_?id|android_?id|advertising_?id|sim_?serial)"?[[:space:]]*[=:][[:space:]]*)[^[:space:],;})]+/\1<redacted>/gI' \
    -e 's/(^|[^[:alnum:].])[0-9]{14,20}($|[^0-9])/\1<redacted>\2/g' \
    -e 's/\+?1?555521[0-9]{4}/<redacted>/g' \
    -e 's/([[:xdigit:]]{2}:){5}[[:xdigit:]]{2}/<mac>/g'
}

# cap CMD...  Prints CMD's stdout and stderr (CR removed, redacted) and never fails.
# The exit code goes to RC (unless called in a subshell) and to commands.log.
cap() {
  local rc=0 cmd
  timeout "${T:-$CMD_TIMEOUT}" "$@" > "$raw/out" 2>&1 || rc=$?
  tr -d '\r' < "$raw/out" | redact
  cmd="$*"
  printf '%s rc=%s %s\n' "$(date -u +%H:%M:%S)" "$rc" "${cmd#"${ADB[*]} "}" >> "$OUT/commands.log"
  RC=$rc
}
dsh() { cap "${ADB[@]}" shell "$@"; }
emu() { T=30 cap "${ADB[@]}" emu "$@"; }
uptime_s() { timeout 15 "${ADB[@]}" shell cat /proc/uptime 2>/dev/null | tr -d '\r' | cut -d' ' -f1 || true; }

# step NAME FUNCTION  Runs one probe section; a failing command inside does not stop it.
step() {
  local name=$1
  shift
  log "== $name"
  "$@" || log "$name: last command returned $?"
}

package_of() {
  local aapt2
  aapt2=$(ls "${ANDROID_HOME:-/nonexistent}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1 || true)
  [ -n "$aapt2" ] || return 0
  "$aapt2" dump packagename "$1" 2>/dev/null | tr -d '\r' | head -n 1 || true
}

probe_device() {
  dsh getprop | keep '^\[(gsm\.|ro\.kernel\.qemu\]|ro\.boot\.qemu\]|ro\.build\.version\.(sdk|release|security_patch)\]|ro\.build\.fingerprint\]|ro\.product\.(model|device)\]|ro\.telephony\.|ro\.radio\.)' \
    > "$OUT/device/getprop.txt"
  {
    echo "## adb emu avd name"; emu avd name
    echo "## adb emu gsm status"; emu gsm status
    echo "## adb emu help"; emu help
    echo "## adb emu help gsm"; emu help gsm
    echo "## adb emu help network"; emu help network
  } > "$OUT/device/console.txt"
  # Screen on and unlocked: Android's cell-info interval depends on it, and so does the screenshot.
  dsh svc power stayon true > /dev/null
  dsh input keyevent KEYCODE_WAKEUP > /dev/null
  dsh wm dismiss-keyguard > /dev/null
}

probe_app() {
  if [ -z "$APK" ]; then
    fail "no APK found (APK_DIR=${APK_DIR:-unset})"
    return 0
  fi
  log "installing ${APK##*/} as $PKG"
  cap "${ADB[@]}" install -r -t "$APK" > "$OUT/app/install.txt"
  if [ "$RC" -ne 0 ]; then
    fail "adb install failed (rc=$RC), see app/install.txt"
    return 0
  fi
  local perm component
  for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do
    { echo "## pm grant $PKG android.permission.$perm"; dsh pm grant "$PKG" "android.permission.$perm"; echo "rc=$RC"; } >> "$OUT/app/grants.txt"
  done
  dsh dumpsys package "$PKG" | keep 'versionCode=|versionName=|targetSdk=|minSdk=|permission|granted=' > "$OUT/app/package.txt"

  dsh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" > "$OUT/app/launcher.txt"
  component=$(tail -n 1 "$OUT/app/launcher.txt")
  case $component in */*) ;; *) component="" ;; esac
  if [ -n "$component" ]; then
    dsh am start -W -n "$component" > "$OUT/app/launch.txt"
  else
    dsh monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > "$OUT/app/launch.txt"
  fi
  sleep 10
  timeout 60 "${ADB[@]}" exec-out screencap -p > "$OUT/app/screenshot.png" || log "screencap failed"
  dsh pidof "$PKG" > "$OUT/app/pid.txt"
  APP_PID=$(grep -oE '[0-9]+' "$OUT/app/pid.txt" | head -n 1 || true)
  [ -n "$APP_PID" ] || fail "$PKG is not running 10 s after launch"
  dsh dumpsys activity activities | keep 'ResumedActivity' > "$OUT/app/resumed.txt"
}

REG_KEYS='^[[:space:]]*(Phone Id|mServiceState|mSignalStrength|mCellIdentity|mCellInfo|mTelephonyDisplayInfo|mDataConnectionState|mDataConnectionNetworkType|mDataActivity|mUserMobileDataState|mIsDataEnabled|mDataEnabledReason|mPhysicalChannelConfigs|mBarringInfo|mAllowedNetworkTypeValue)[[:space:]]*='

# Six registry dumps 5 s apart: do the cell-info modem timestamps advance?
probe_telephony() {
  local i up
  for i in 1 2 3 4 5 6; do
    up=$(uptime_s)
    dsh dumpsys telephony.registry > "$OUT/telephony/registry-t$i.txt"
    { echo "## t$i uptime_s=$up utc=$(utc)"; keep "$REG_KEYS" < "$OUT/telephony/registry-t$i.txt"; echo; } >> "$OUT/telephony/samples.txt"
    [ "$i" -eq 6 ] || sleep 5
  done
  keep 'callingPackage=' < "$OUT/telephony/registry-t6.txt" > "$OUT/telephony/listeners.txt"
  # Only these lines of dumpsys phone are kept; the full dump lists SIM details.
  dsh dumpsys phone | keep 'mLastCellInfoList|mLastCellInfoReqTime|mCellIdentity=|mSS=|mNewSS=|mLastPhysicalChannelConfigList|NetworkTypeController|mOverrideNetworkType|mIsNrAdvanced|mNrState|mIsPhysicalChannelConfigOn|mPrimaryCellChangedWhileIdle' \
    > "$OUT/telephony/phone-filtered.txt"
}

SIG_KEYS='^[[:space:]]*(mSignalStrength|mCellInfo|mServiceState)[[:space:]]*='
signal_sample() {
  { echo "## $1 uptime_s=$(uptime_s) utc=$(utc)"; dsh dumpsys telephony.registry | keep "$SIG_KEYS"; echo; } >> "$OUT/signal/samples.txt"
}

# Does "gsm signal-profile" 0..4 change the reported signal? The console applies it over ~15 s.
probe_signal() {
  local p
  signal_sample "baseline"
  for p in 0 1 2 3 4; do
    { echo "## gsm signal-profile $p utc=$(utc)"; emu gsm signal-profile "$p"; echo "## gsm status"; emu gsm status; } >> "$OUT/signal/console.txt"
    sleep 6
    signal_sample "profile=$p +6s"
    sleep 10
    signal_sample "profile=$p +16s"
  done
}

# Does "geo fix" reach the gps provider? Three fixes about 100 m apart.
probe_location() {
  local n=0 pt
  { echo "## cmd location is-location-enabled"; dsh cmd location is-location-enabled; } > "$OUT/location/settings.txt"
  if ! grep -q true "$OUT/location/settings.txt"; then
    { echo "## cmd location set-location-enabled true"; dsh cmd location set-location-enabled true; echo "rc=$RC"; } >> "$OUT/location/settings.txt"
  fi
  dsh dumpsys location > "$OUT/location/dumpsys-0-before.txt"
  # longitude latitude altitude_m satellites
  for pt in "77.594600 12.971600 920 8" "77.594600 12.972500 921 9" "77.595500 12.972500 922 10"; do
    n=$((n + 1))
    # shellcheck disable=SC2086 # $pt splits into the four geo fix arguments
    { echo "## geo fix $pt utc=$(utc)"; emu geo fix $pt; } >> "$OUT/location/geo-fix.txt"
    sleep 5
    dsh dumpsys location > "$OUT/location/dumpsys-$n-after-fix.txt"
  done
}

# Which networks exist (cellular with INTERNET? Wi-Fi?), and what "cmd phone data enable" does.
probe_connectivity() {
  dsh dumpsys connectivity > "$OUT/connectivity/dumpsys-connectivity-before.txt"
  keep 'NetworkAgentInfo\{' < "$OUT/connectivity/dumpsys-connectivity-before.txt" > "$OUT/connectivity/networks-before.txt"
  {
    echo "## settings get global mobile_data"; dsh settings get global mobile_data
    echo "## settings get global mobile_data1"; dsh settings get global mobile_data1
    echo "## cmd phone data enable"; dsh cmd phone data enable; echo "rc=$RC"
    sleep 10
    echo "## settings get global mobile_data (10 s later)"; dsh settings get global mobile_data
  } > "$OUT/connectivity/mobile-data.txt"
  dsh dumpsys connectivity > "$OUT/connectivity/dumpsys-connectivity-after.txt"
  keep 'NetworkAgentInfo\{' < "$OUT/connectivity/dumpsys-connectivity-after.txt" > "$OUT/connectivity/networks-after.txt"
  dsh ip -4 -o addr show > "$OUT/connectivity/ip-addr.txt"
  dsh dumpsys network_stack > "$OUT/connectivity/dumpsys-network_stack.txt"
  keep 'PROBE_|[Vv]alidat' < "$OUT/connectivity/dumpsys-network_stack.txt" > "$OUT/connectivity/validation.txt"
}

# ICMP and HTTPS from the device shell. The shell cannot bind to one network; the app will.
probe_reachability() {
  local target
  for target in 10.0.2.2 8.8.8.8 www.google.com; do
    { echo "## ping -c 3 -W 5 $target utc=$(utc)"; T=40 dsh ping -c 3 -W 5 "$target"; echo "rc=$RC"; echo; } >> "$OUT/reachability/ping.txt"
  done
  { echo "## HTTP clients on the device"; dsh 'for t in curl wget nc; do command -v "$t" || echo "$t: not found"; done'; } > "$OUT/reachability/tools.txt"
  if grep -qE '/curl$' "$OUT/reachability/tools.txt"; then
    { echo "## curl https://www.google.com/generate_204"; T=40 dsh "curl -sS -o /dev/null -w 'http_code=%{http_code}\n' --max-time 30 https://www.google.com/generate_204"; echo "rc=$RC"; } > "$OUT/reachability/https.txt"
  else
    echo "No curl on the device; the system's own HTTPS validation probe is in connectivity/validation.txt" > "$OUT/reachability/https.txt"
  fi
}

probe_end() {
  dsh pidof "$PKG" > "$OUT/app/pid-end.txt"
  timeout 60 "${ADB[@]}" exec-out screencap -p > "$OUT/app/screenshot-end.png" || log "screencap failed"
  T=120 cap "${ADB[@]}" logcat -d -v threadtime -b main,system,crash,radio,events > "$OUT/logcat/logcat.txt"
  cap "${ADB[@]}" logcat -d -v threadtime -b crash > "$OUT/logcat/crash.txt"
  [ -z "$APP_PID" ] || keep " $APP_PID " < "$OUT/logcat/logcat.txt" > "$OUT/logcat/app.txt"
  if [ -n "$PKG" ] && grep -qF "Process: $PKG," "$OUT/logcat/crash.txt"; then
    fail "$PKG crashed, see logcat/crash.txt"
  fi
}

summarise() {
  local f line
  echo "FieldTap emulator probe, $(utc)"
  echo "package ${PKG:-unknown}, apk ${APK##*/}"
  echo
  echo "== Result"
  if [ ${#FAILURES[@]} -eq 0 ]; then echo "PASS: installed, running 10 s after launch, no crash"; else printf 'FAIL: %s\n' "${FAILURES[@]}"; fi

  echo; echo "== getprop"
  cat "$OUT/device/getprop.txt"

  echo; echo "== Cell info per sample: type, registered, modem timestamp (ns since boot)"
  while IFS= read -r line; do
    case $line in
      '## '*) printf '%s\n' "$line" ;;
      *mCellInfo=*) grep -oE 'CellInfo[A-Za-z]+:\{mRegistered=[A-Z]+ mTimeStamp=[0-9]+ns( mCellConnectionStatus=[0-9A-Za-z_]+)?' <<<"$line" | sed 's/^/   /' ;;
    esac
  done < "$OUT/telephony/samples.txt"

  echo; echo "== Last registry sample"
  keep '^[[:space:]]*(mServiceState|mSignalStrength|mCellInfo|mTelephonyDisplayInfo|mDataConnectionState|mUserMobileDataState)[[:space:]]*=' < "$OUT/telephony/registry-t6.txt" | cut -c1-2500

  echo; echo "== Signal per gsm signal-profile"
  while IFS= read -r line; do
    case $line in
      '## '*) printf '%s\n' "$line" ;;
      *mSignalStrength=*) printf '   %s\n' "$(sed -E 's/^[[:space:]]*//' <<<"$line" | cut -c1-400)" ;;
      *mCellInfo=*) printf '   cellinfo: %s\n' "$(grep -oE '(rssi|rsrp|rsrq|rssnr|ssRsrp|ssRsrq|ssSinr|level)=-?[0-9]+' <<<"$line" | tr '\n' ' ')" ;;
    esac
  done < "$OUT/signal/samples.txt"

  echo; echo "== Location providers before and after geo fix"
  cat "$OUT/location/settings.txt"
  for f in "$OUT"/location/dumpsys-*.txt; do
    echo "-- ${f##*/}"
    awk '/^[[:space:]]*[a-z]+ provider/ { show = ($1 == "gps" || $1 == "fused" || $1 == "network") }
         show && /provider|last location|last coarse location|request=|enabled=/' "$f" | cut -c1-300
  done
  cat "$OUT/location/geo-fix.txt"

  echo; echo "== Networks, before and after cmd phone data enable"
  for f in "$OUT/connectivity/networks-before.txt" "$OUT/connectivity/networks-after.txt"; do
    echo "-- ${f##*/}"
    while IFS= read -r line; do
      printf '   %s\n' "$(grep -oE 'network\{[0-9]+\}|ni\{[^}]*\}|Transports: [A-Z|]+|Capabilities: [A-Za-z_&]+|everValidated|lastValidated' <<<"$line" | tr '\n' ' ')"
    done < "$f"
  done
  cat "$OUT/connectivity/mobile-data.txt"
  echo "-- validation probes (last 15 lines)"
  tail -n 15 "$OUT/connectivity/validation.txt"

  echo; echo "== Reachability from the device shell"
  keep '^## |packets transmitted|rtt |^rc=|unknown host|unreachable' < "$OUT/reachability/ping.txt"
  cat "$OUT/reachability/tools.txt" "$OUT/reachability/https.txt"
}

log "device $SERIAL, output $OUT"
T=180 cap "${ADB[@]}" wait-for-device > /dev/null
for _ in $(seq 90); do
  [ "$(timeout 10 "${ADB[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" = 1 ] && break
  sleep 2
done

APK=${1:-}
[ -n "$APK" ] || APK=$(find "${APK_DIR:-$repo/android/app/build/outputs/apk}" -name '*.apk' ! -name '*androidTest*' 2>/dev/null | sort | head -n 1 || true)
PKG=${PACKAGE:-}
[ -n "$PKG" ] || [ -z "$APK" ] || PKG=$(package_of "$APK")
[ -n "$PKG" ] || PKG=$(sed -n 's/^fieldtap\.applicationId=//p' "$repo/android/gradle.properties" | tr -d '\r[:space:]' || true)

step device probe_device
step app probe_app
step telephony probe_telephony
step signal probe_signal
step location probe_location
step connectivity probe_connectivity
step reachability probe_reachability
step end probe_end

summarise > "$OUT/summary.txt" 2>&1 || true
cat "$OUT/summary.txt"
[ ${#FAILURES[@]} -eq 0 ]
