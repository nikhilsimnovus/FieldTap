#!/bin/sh
# FieldTap plug-and-go launcher for macOS and Linux.
#
#   ./fieldtap-auto.sh                       capture every handset that appears
#   ./fieldtap-auto.sh --profile all         every log code the modem reports
#   ./fieldtap-auto.sh --traffic ping,download --live
#
# Creates the Python environment on first run, checks the toolchain, then
# watches USB. Ctrl-C stops. Extra arguments are passed to `fieldtap auto`.
set -e
cd "$(dirname "$0")"

PY="${PYTHON:-python3}"
if ! command -v "$PY" >/dev/null 2>&1; then
    echo "[FieldTap] python3 is required."
    echo "           macOS:  brew install python   (or install from python.org)"
    echo "           Linux:  apt install python3 python3-venv"
    exit 1
fi

if [ ! -x ".venv/bin/python" ]; then
    echo "[FieldTap] creating the Python environment..."
    "$PY" -m venv .venv
    .venv/bin/python -m pip install --quiet --upgrade pip
    .venv/bin/python -m pip install --quiet -e ".[all]"
fi

.venv/bin/python -m fieldtap.cli setup
echo

# On macOS and Linux the diag interface is usually reached through libusb
# rather than a serial port, so --usb is the sensible default there. `auto`
# still prefers a serial diag port when one exists.
.venv/bin/python -m fieldtap.cli auto --open-report --gps auto "$@"
