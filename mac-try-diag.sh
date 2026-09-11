#!/bin/sh
# Try to bring the Qualcomm diag interface up on a rooted phone, and report
# honestly whether it worked.
#
#   ./mac-try-diag.sh
#
# On many Qualcomm builds /dev/diag only exists while the `diag` USB gadget
# function is bound, so a missing node with the phone in mtp/ptp mode does not
# by itself prove the driver is gone. This walks the known USB compositions,
# and after each one checks three independent things:
#
#   * /dev/diag on the phone            (the on-device route)
#   * diagchar in /proc/devices         (is the driver registered at all)
#   * a USB interface FF/FF/0x30 on the Mac  (the libusb route)
#
# It restores the phone's original USB configuration before exiting, whether
# it succeeded, failed, or was interrupted. persist.sys.usb.config is never
# touched, so a reboot also puts things back.
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

sh_root() { "$ADB" -s "$SERIAL" shell "su -c '$1'" 2>&1; }

ORIGINAL=$("$ADB" -s "$SERIAL" shell getprop sys.usb.config 2>/dev/null | tr -d '\r')
[ -n "$ORIGINAL" ] || ORIGINAL="adb"
RESTORED=0
restore() {
    [ "$RESTORED" = "1" ] && return
    RESTORED=1
    printf '\n'
    note "restoring sys.usb.config to '$ORIGINAL' ..."
    "$ADB" -s "$SERIAL" shell "su -c 'setprop sys.usb.config $ORIGINAL'" >/dev/null 2>&1
    "$ADB" wait-for-device >/dev/null 2>&1
    note "restored: $("$ADB" -s "$SERIAL" shell getprop sys.usb.config 2>/dev/null | tr -d '\r')"
}
trap 'restore; exit 130' INT TERM

note "phone $SERIAL, current sys.usb.config = $ORIGINAL"

say "== Baseline: is diagchar in the kernel at all? =="
sh_root 'grep diagchar /proc/devices || echo "  (diagchar NOT in /proc/devices)"' | sed 's/^/  /'
sh_root 'ls -l /dev/diag 2>&1' | sed 's/^/  /'

usb_diag_interface() {
    # A diag interface is vendor class FF, subclass FF, protocol 0x30 (48).
    ioreg -c IOUSBHostInterface -r -l -w 0 2>/dev/null | awk '
        /"bInterfaceClass"/ {c=$NF}
        /"bInterfaceSubClass"/ {s=$NF}
        /"bInterfaceProtocol"/ {p=$NF; if (c==255 && s==255 && (p==48 || p==255)) print "    FF/FF/" p " <- diag-shaped interface"}
    '
}

FOUND=""
for CONFIG in \
    "diag,adb" \
    "diag,serial_cdev,rmnet,adb" \
    "diag,diag_mdm,adb" \
    "diag,diag_mdm,qdss,qdss_mdm,serial_cdev,dpl,rmnet,adb"
do
    say "== Trying sys.usb.config = $CONFIG =="
    "$ADB" -s "$SERIAL" shell "su -c 'setprop sys.usb.config $CONFIG'" >/dev/null 2>&1
    sleep 4
    "$ADB" wait-for-device >/dev/null 2>&1
    sleep 2
    APPLIED=$("$ADB" -s "$SERIAL" shell getprop sys.usb.state 2>/dev/null | tr -d '\r')
    note "phone reports sys.usb.state = ${APPLIED:-<no answer>}"
    case "$APPLIED" in
        *diag*) note "the phone accepted a diag composition" ;;
        *)      note "the phone did NOT switch to diag (composition ignored)" ; continue ;;
    esac
    NODE=$(sh_root 'ls -l /dev/diag 2>&1')
    note "/dev/diag: $(printf '%s' "$NODE" | tr -d '\r' | head -1)"
    DRIVER=$(sh_root 'grep diagchar /proc/devices 2>/dev/null' | tr -d '\r')
    note "diagchar in /proc/devices: ${DRIVER:-no}"
    IFACE=$(usb_diag_interface)
    if [ -n "$IFACE" ]; then
        note "USB interfaces seen by the Mac:"; printf '%s\n' "$IFACE"
    else
        note "no FF/FF/0x30 interface visible to the Mac"
    fi
    case "$NODE" in *"crw"*) FOUND="$CONFIG (on-device /dev/diag)" ; break ;; esac
    [ -n "$IFACE" ] && { FOUND="$CONFIG (USB diag interface)"; break; }
done

say "== Verdict =="
if [ -n "$FOUND" ]; then
    note "DIAG IS AVAILABLE via: $FOUND"
    note "Leaving the phone in that mode is what capture needs. Run:"
    note "    .venv/bin/fieldtap auto --profile all"
    note "and put it back afterwards with: .venv/bin/fieldtap disable-diag"
    RESTORED=1     # deliberately leave the working composition in place
    printf '\n'
    note "note: sys.usb.config left as the working diag composition, not restored."
else
    note "No diag access on this build."
    note "Every composition was tried; either the phone ignored them or the"
    note "diag node and the USB interface both stayed absent. That points to a"
    note "kernel without diagchar rather than a configuration mistake."
    restore
fi
