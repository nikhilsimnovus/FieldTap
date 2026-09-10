#!/bin/sh
# Read-only checks that decide whether this phone can actually be captured.
# Changes nothing on the phone. Run after mac-start.sh has found it.
#
#   ./mac-diag-check.sh
set -u
cd "$(dirname "$0")"
BOLD=$(printf '\033[1m'); OFF=$(printf '\033[0m')
say() { printf '\n%s%s%s\n' "$BOLD" "$1" "$OFF"; }
note() { printf '  %s\n' "$1"; }

ADB="tools/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb 2>/dev/null || echo '')"
if [ -z "$ADB" ] || [ ! -x "$ADB" ]; then note "adb not found; run ./mac-start.sh first"; exit 1; fi
SERIAL=$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}')
if [ -z "${SERIAL:-}" ]; then note "no phone in 'device' state"; "$ADB" devices -l; exit 1; fi
note "phone: $SERIAL  $("$ADB" -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"

say "== Can we become root? =="
"$ADB" -s "$SERIAL" shell 'su -c id' 2>&1 | sed 's/^/  /'

say "== Is the diag driver present? =="
# /dev/diag is root-only, so a normal shell seeing nothing proves nothing.
# This is the check that matters.
"$ADB" -s "$SERIAL" shell 'su -c "ls -lZ /dev/diag 2>&1; echo ---; ls /sys/class/diag* 2>/dev/null || echo no-sys-class-diag; echo ---; grep -c diagchar /proc/devices 2>/dev/null || echo 0"' 2>&1 | sed 's/^/  /'

say "== SELinux =="
"$ADB" -s "$SERIAL" shell 'getenforce' 2>&1 | sed 's/^/  /'
note "Enforcing can block /dev/diag even as root on some builds."

say "== USB composition =="
"$ADB" -s "$SERIAL" shell 'echo "  sys.usb.config     : $(getprop sys.usb.config)"; echo "  persist.sys.usb.cfg: $(getprop persist.sys.usb.config)"; echo "  sys.usb.state      : $(getprop sys.usb.state)"' 2>&1
note "diag is exposed on USB only if 'diag' appears in sys.usb.config."

say "== Is there a network to capture? =="
# Without a registered SIM there is almost no RRC/NAS traffic: the capture
# will run and produce an almost empty report, which looks like a bug.
"$ADB" -s "$SERIAL" shell 'echo "  sim state    : $(getprop gsm.sim.state)"; echo "  operator     : $(getprop gsm.operator.alpha) $(getprop gsm.operator.numeric)"; echo "  network type : $(getprop gsm.network.type)"; echo "  airplane     : $(settings get global airplane_mode_on 2>/dev/null)"' 2>&1
printf '\n'
"$ADB" -s "$SERIAL" shell 'dumpsys telephony.registry 2>/dev/null | grep -m 3 -E "mServiceState=|mVoiceRegState=|mDataRegState="' 2>&1 | sed 's/^/  /'

say "== Verdict =="
cat <<'EOT'
  Read the two blocks above:

  * "ls -lZ /dev/diag" printing a crw------- line  ->  the driver is there and
    root can reach it. Capture is possible.
  * "No such file or directory"  ->  the diag node does not exist on this
    build. Enabling the diag USB composition may create it; if not, this
    kernel has no diagchar and the phone cannot be tapped this way.
  * operator/sim empty  ->  no network. Capture will work but there will be
    almost nothing to see. Insert a SIM and let it register first.

  If the diag node is reachable, the next command is:

      .venv/bin/fieldtap auto --profile all

  It asks the phone to expose diag, which re-enumerates USB. On an Apple
  Silicon MacBook approve the accessory prompt if it reappears.

  To undo the USB change afterwards:

      .venv/bin/fieldtap disable-diag
EOT
