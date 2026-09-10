#!/bin/sh
# FieldTap on a Mac: set up, check, and report what the phone looks like.
#
#   git clone -b clean-room-implementation https://github.com/nikhilsimnovus/FieldTap.git
#   cd FieldTap && ./mac-start.sh
#
# Installs nothing outside this directory except adb, which lands in ./tools.
# Homebrew packages are reported, never installed silently. Run it with the
# phone plugged in and unlocked; paste the whole output back if you want help
# reading it.
set -u
cd "$(dirname "$0")"
BOLD=$(printf '\033[1m'); DIM=$(printf '\033[2m'); OFF=$(printf '\033[0m')
say() { printf '%s%s%s\n' "$BOLD" "$1" "$OFF"; }
note() { printf '  %s\n' "$1"; }

say "== 1. This Mac =="
sw_vers 2>/dev/null | sed 's/^/  /'
note "architecture: $(uname -m)   $(uname -m | grep -q arm64 && echo '(Apple Silicon: accessory approval applies)' || echo '(Intel)')"

say "== 2. Prerequisites =="
if command -v brew >/dev/null 2>&1; then note "homebrew: $(brew --prefix)"; else note "homebrew: NOT INSTALLED (https://brew.sh)"; fi
for f in tshark wireshark; do
    if command -v "$f" >/dev/null 2>&1; then note "$f: $(command -v $f)"
    elif [ -x "/Applications/Wireshark.app/Contents/MacOS/$f" ]; then note "$f: /Applications/Wireshark.app/Contents/MacOS/$f"
    else note "$f: MISSING  ->  brew install --cask wireshark"; fi
done
if [ -f /opt/homebrew/lib/libusb-1.0.0.dylib ] || [ -f /usr/local/lib/libusb-1.0.0.dylib ]; then
    note "libusb: present"
else
    note "libusb: MISSING  ->  brew install libusb"
fi

say "== 3. Python environment =="
PY="${PYTHON:-python3}"
if ! command -v "$PY" >/dev/null 2>&1; then
    note "python3 NOT FOUND -> brew install python"; exit 1
fi
note "python: $($PY --version 2>&1) at $(command -v $PY)"
if [ ! -x ".venv/bin/python" ]; then
    note "creating .venv ..."
    "$PY" -m venv .venv || { note "venv creation failed"; exit 1; }
fi
.venv/bin/python -m pip install --quiet --upgrade pip >/dev/null 2>&1
note "installing fieldtap ..."
if ! .venv/bin/python -m pip install --quiet -e ".[all]" 2>/tmp/ft-pip.log; then
    note "pip install FAILED; last lines:"; tail -5 /tmp/ft-pip.log | sed 's/^/    /'; exit 1
fi
# libusb-package removes the usual "no backend available" problem on macOS,
# where pyusb cannot find the Homebrew dylib from a non-Homebrew Python.
.venv/bin/python -m pip install --quiet libusb-package >/dev/null 2>&1 || true
note "fieldtap: $(.venv/bin/python -m fieldtap.cli --version 2>&1)"

say "== 4. FieldTap self-check =="
.venv/bin/python -m fieldtap.cli setup --install-adb 2>&1 | sed 's/^/  /'

say "== 5. What is on USB right now =="
if command -v ioreg >/dev/null 2>&1; then
    ioreg -p IOUSB -w 0 2>/dev/null | sed 's/[^o]*o //; s/@.*$//' | grep -v '^Root' | sed 's/^/  /' || note "(none)"
    printf '\n'
    note "vendor/product ids (decimal; Qualcomm 0x05c6 = 1478):"
    ioreg -p IOUSB -l -w 0 2>/dev/null | grep -E '"(USB Vendor Name|USB Product Name|idVendor|idProduct|USB Serial Number)"' | sed 's/^ */    /'
fi
note "serial devices (a diag port does NOT appear here on macOS; empty is normal):"
ls /dev/cu.* 2>/dev/null | sed 's/^/    /' || note "    (none)"

say "== 6. The phone over adb =="
ADB="tools/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb 2>/dev/null || echo '')"
if [ -n "$ADB" ] && [ -x "$ADB" ]; then
    "$ADB" devices -l 2>&1 | sed 's/^/  /'
    SERIAL=$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}')
    if [ -n "${SERIAL:-}" ]; then
        printf '\n'
        note "identity:"
        "$ADB" -s "$SERIAL" shell getprop 2>/dev/null | grep -E \
            'ro.product.(manufacturer|model|device|cpu.abi)\]|ro.build.version.release\]|ro.hardware\]|ro.board.platform\]|ro.soc.(model|manufacturer)\]|gsm.version.baseband\]|gsm.operator.(alpha|numeric)\]|sys.usb.(config|state)\]|ro.boot.verifiedbootstate\]' \
            | sed 's/^/    /'
        printf '\n'
        note "is it Qualcomm, is it rooted, is there a diag node:"
        "$ADB" -s "$SERIAL" shell 'echo "  soc: $(getprop ro.board.platform) / $(getprop ro.hardware)"; if [ -e /dev/diag ]; then echo "  /dev/diag: EXISTS"; else echo "  /dev/diag: not visible to this shell (needs root, or the driver is absent)"; fi; if command -v su >/dev/null 2>&1; then echo "  su binary: present"; else echo "  su binary: NOT present (phone is probably not rooted)"; fi' 2>&1 | sed 's/^/  /'
    else
        note "no phone in 'device' state. unauthorized = unlock the phone and accept the prompt."
    fi
else
    note "adb not found; step 4 should have installed it."
fi

say "== Next =="
note "If a phone showed up above in state 'device':"
note "  .venv/bin/fieldtap demo          # prove the whole pipeline, no phone needed"
note "  .venv/bin/fieldtap auto          # then capture from the phone"
note "Read docs/MACOS.md for the detail, including the Apple Silicon accessory prompt."
