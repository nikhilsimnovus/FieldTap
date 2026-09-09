"""`fieldtap setup`: check this machine and fetch what is missing.

Reports Python modules, Wireshark/tshark, adb, the Qualcomm serial driver
(by looking for its COM ports) and the libusb backend, then prints the
exact next step for anything missing. `--install-adb` downloads Google's
platform-tools zip into <repo>/tools/platform-tools, where FieldTap looks
for adb automatically.
"""

from __future__ import annotations

import os
import sys
import zipfile
from typing import Callable

from . import __version__
from . import tshark as tshark_mod
from .diag import transport as tr

# Google publishes one archive per host OS. Picking the Windows one on a Mac
# downloads happily and then finds no adb, so this is keyed off the platform.
PLATFORM_TOOLS_URLS = {
    "win32": "https://dl.google.com/android/repository/platform-tools-latest-windows.zip",
    "darwin": "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip",
    "linux": "https://dl.google.com/android/repository/platform-tools-latest-linux.zip",
}


def platform_tools_url() -> str:
    for prefix, url in PLATFORM_TOOLS_URLS.items():
        if sys.platform.startswith(prefix):
            return url
    raise RuntimeError("no Android platform-tools build is published for %s; install adb yourself "
                       "and put it on PATH" % sys.platform)


IS_MAC = sys.platform == "darwin"
IS_WINDOWS = os.name == "nt"

# How the diag interface is reached differs by host, and so does the advice.
# Windows needs a vendor driver to turn it into a COM port, or WinUSB via
# Zadig. macOS and Linux ship no driver that claims a vendor-specific
# interface, so libusb is the usual route there and no driver install is
# involved - which is why a Mac is often the easier host.
if IS_WINDOWS:
    NO_PORT_HINT = ("no 'Qualcomm HS-USB' COM port is visible. It only appears while a phone is "
                    "plugged in and in diag mode, so this is expected with no phone attached. If a "
                    "phone is attached and the port is missing, install the Qualcomm USB driver, or "
                    "bind WinUSB to the diag interface with Zadig and capture with --usb. See "
                    "docs/DEVICE-SETUP.md.")
    USB_OK_HINT = "raw USB diag possible after binding WinUSB with Zadig"
    USB_MISSING_HINT = ("no libusb backend: the --usb transport is unavailable. The Qualcomm serial "
                        "driver path does not need it.")
    LAUNCHER = "FieldTap-Auto.cmd"
else:
    NO_PORT_HINT = ("no Qualcomm diag serial device is visible. On %s the diag interface is usually "
                    "not claimed by any driver, so it appears to libusb rather than as a serial "
                    "device: capture with --usb. If a /dev/cu.* or /dev/ttyUSB* device does appear "
                    "for the phone, FieldTap will use it. See docs/DEVICE-SETUP.md."
                    % ("macOS" if IS_MAC else "this system"))
    USB_OK_HINT = "raw USB diag available, which is the normal route on this platform"
    USB_MISSING_HINT = ("no libusb backend: the --usb transport is unavailable, and it is the main "
                        "route on this platform. Install it with %s"
                        % ("`brew install libusb`" if IS_MAC else "`apt install libusb-1.0-0`"))
    LAUNCHER = "./fieldtap-auto.sh"


def tools_dir() -> str:
    override = os.environ.get("FIELDTAP_TOOLS")
    if override:
        return override
    return os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "tools")


def install_platform_tools(log: Callable[[str], None]) -> str:
    """Download and unpack platform-tools; returns the adb path."""
    import urllib.request
    dest_root = tools_dir()
    os.makedirs(dest_root, exist_ok=True)
    url = platform_tools_url()
    zip_path = os.path.join(dest_root, os.path.basename(url))
    log("downloading %s" % url)
    with urllib.request.urlopen(url, timeout=120) as resp, open(zip_path, "wb") as fh:
        total = 0
        while True:
            chunk = resp.read(1 << 16)
            if not chunk:
                break
            fh.write(chunk)
            total += len(chunk)
    log("downloaded %.1f MB" % (total / 1e6))
    with zipfile.ZipFile(zip_path) as zf:
        # extractall() drops the Unix permission bits, which leaves adb
        # non-executable on macOS and Linux. Restore the mode the archive
        # recorded, so the binary can actually be run.
        for info in zf.infolist():
            target = zf.extract(info, dest_root)
            mode = info.external_attr >> 16
            if mode and os.name != "nt":
                os.chmod(target, mode)
    os.remove(zip_path)
    adb = os.path.join(dest_root, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
    if not os.path.isfile(adb):
        raise RuntimeError("platform-tools unpacked but adb not found at %s" % adb)
    if os.name != "nt":
        # Belt and braces: if the archive recorded no mode, the loop above left
        # adb at the umask default and the next subprocess call would fail with
        # PermissionError from a setup run that reported success.
        for name in ("adb", "fastboot"):
            binary = os.path.join(dest_root, "platform-tools", name)
            if os.path.isfile(binary):
                os.chmod(binary, os.stat(binary).st_mode | 0o111)
    log("adb installed at %s" % adb)
    return adb


def run(install_adb: bool = False, log: Callable[[str], None] = print) -> int:
    problems = 0
    log("FieldTap %s on Python %s (%s)" % (__version__, sys.version.split()[0], sys.platform))
    for module, extra in (("serial", "serial"), ("usb.core", "usb")):
        try:
            __import__(module)
            log("  ok    python module %s" % module)
        except ImportError:
            problems += 1
            log("  MISSING python module %s -> pip install fieldtap[%s]" % (module, extra))
    tshark = tshark_mod.find_tshark()
    if tshark:
        log("  ok    tshark %s" % (tshark_mod.version(tshark) or tshark))
    else:
        problems += 1
        log("  MISSING tshark/Wireshark -> install Wireshark from https://www.wireshark.org/download.html (decode, KPIs and the live view need it)")
    wireshark = tshark_mod.find_wireshark()
    log("  ok    Wireshark GUI %s" % wireshark if wireshark else "  warn  Wireshark GUI not found (--live unavailable)")
    adb = tr.adb_path()
    if adb:
        log("  ok    adb %s" % adb)
    elif install_adb:
        try:
            adb = install_platform_tools(log)
            log("  ok    adb %s" % adb)
        except Exception as exc:
            problems += 1
            log("  FAILED to install platform-tools: %s" % exc)
    else:
        problems += 1
        log("  MISSING adb -> run `fieldtap setup --install-adb` (downloads Google platform-tools, ~7 MB), "
            "or install Android platform-tools and put adb on PATH")
    ports = tr.list_serial_ports()
    diag_ports = [p for p in ports if p.likely_diag]
    qc_ports = [p for p in ports if p.vid == tr.QUALCOMM_VID or "qualcomm" in p.description.lower()]
    if diag_ports:
        for p in diag_ports:
            log("  ok    diag port %s %s (serial %s)" % (p.device, p.description, p.serial_number or "?"))
    elif qc_ports:
        log("  warn  Qualcomm ports present but none look like diag: %s" % ", ".join(p.device for p in qc_ports))
    else:
        log("  info  " + NO_PORT_HINT)
    try:
        import usb.core
        try:
            list(usb.core.find(find_all=True))
            log("  ok    libusb backend available (%s)" % USB_OK_HINT)
        except usb.core.NoBackendError:
            if IS_WINDOWS:
                log("  info  " + USB_MISSING_HINT)
            else:
                problems += 1
                log("  MISSING " + USB_MISSING_HINT)
    except ImportError:
        pass
    if adb:
        devs = tr.adb_devices()
        if devs:
            for d in devs:
                log("  ok    adb device %s (%s) %s" % (d["serial"], d["state"], d["info"]))
        else:
            log("  info  no adb devices attached (plug a phone in with USB debugging enabled)")
    log("")
    if problems:
        log("%d thing(s) to fix before `fieldtap auto` can run unattended." % problems)
    else:
        log("ready: run `fieldtap auto` (or %s) and plug the phone in." % LAUNCHER)
    return 1 if problems else 0
