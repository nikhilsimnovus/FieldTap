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

PLATFORM_TOOLS_URL = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
QUD_HINT = ("Qualcomm USB serial driver (QUD) not detected: no 'Qualcomm HS-USB' COM port is visible. "
            "It only appears while a phone is plugged in and in diag mode, so this is expected with no phone attached. "
            "If a phone is attached and the port is missing, install the Qualcomm USB driver (see docs/DEVICE-SETUP.md) "
            "or bind WinUSB to the diag interface with Zadig and use --usb.")


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
    zip_path = os.path.join(dest_root, "platform-tools-latest-windows.zip")
    log("downloading %s" % PLATFORM_TOOLS_URL)
    with urllib.request.urlopen(PLATFORM_TOOLS_URL, timeout=120) as resp, open(zip_path, "wb") as fh:
        total = 0
        while True:
            chunk = resp.read(1 << 16)
            if not chunk:
                break
            fh.write(chunk)
            total += len(chunk)
    log("downloaded %.1f MB" % (total / 1e6))
    with zipfile.ZipFile(zip_path) as zf:
        zf.extractall(dest_root)
    os.remove(zip_path)
    adb = os.path.join(dest_root, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
    if not os.path.isfile(adb):
        raise RuntimeError("platform-tools unpacked but adb not found at %s" % adb)
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
        log("  info  " + QUD_HINT)
    try:
        import usb.core
        try:
            list(usb.core.find(find_all=True))
            log("  ok    libusb backend available (raw USB diag possible after Zadig/WinUSB)")
        except usb.core.NoBackendError:
            log("  info  no libusb backend: raw --usb transport unavailable (the serial driver path does not need it)")
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
        log("ready: run `fieldtap auto` (or FieldTap-Auto.cmd) and plug the phone in.")
    return 1 if problems else 0
