"""Find handsets on this machine and bring each one to a diag port.

Three things can be visible for one phone:

* an adb device (USB debugging on),
* a Qualcomm diag serial port ("Qualcomm HS-USB Diagnostics 9091 (COM7)"),
* a raw USB diag interface (when WinUSB/libusb is bound instead of the serial driver).

`scan()` lists all three. `pair()` groups them into Handset objects using the
USB serial number, which for Android phones is the adb serial, with a
one-to-one fallback when the serial is not exposed. `prepare()` does whatever
is needed to obtain a transport: switch the phone's USB composition to one
that includes diag (root over adb), then wait for the port to enumerate. All
of it is safe to run repeatedly and with several phones plugged in at once;
the orchestrator in auto.py owns the set of ports already in use.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Callable, Iterable, Optional, Set, Tuple

from .diag import transport as tr


class NotReady(Exception):
    """The handset is visible but cannot be captured.

    `permanent` distinguishes "not yet" (waiting on a USB-debugging prompt,
    a port that has not enumerated) from "not ever on this device" (an
    emulator, which has no Qualcomm modem). The orchestrator retries the
    first kind and stops pestering about the second.
    """

    def __init__(self, message: str, permanent: bool = False):
        super().__init__(message)
        self.permanent = permanent


EMULATOR_HARDWARE = ("ranchu", "goldfish", "vbox86", "cutf")


def is_emulator(props: dict) -> bool:
    """An emulated Android has no baseband, no /dev/diag and nothing to tap."""
    if props.get("qemu") == "1":
        return True
    if "emulator" in (props.get("characteristics") or "").lower():
        return True
    hardware = (props.get("hardware") or "").lower()
    return any(hardware.startswith(h) for h in EMULATOR_HARDWARE)


@dataclass
class DiagPort:
    kind: str                       # "serial" | "usb"
    id: str                         # COM7  |  "05C6:9091@bus1-addr4"
    description: str = ""
    vid: Optional[int] = None
    pid: Optional[int] = None
    serial_number: Optional[str] = None
    location: Optional[str] = None
    interface: Optional[int] = None

    def transport(self) -> tr.Transport:
        if self.kind == "serial":
            return tr.SerialTransport(self.id)
        return tr.UsbTransport(self.vid, self.pid, self.interface)

    def describe(self) -> dict:
        return {"kind": self.kind, "id": self.id, "description": self.description,
                "vid": ("0x%04X" % self.vid) if self.vid is not None else None,
                "pid": ("0x%04X" % self.pid) if self.pid is not None else None,
                "serial_number": self.serial_number, "location": self.location}


@dataclass
class AdbDevice:
    serial: str
    state: str                      # device | unauthorized | offline
    info: str = ""


@dataclass
class Handset:
    key: str                        # stable identity for the orchestrator (adb serial when known)
    adb: Optional[AdbDevice] = None
    port: Optional[DiagPort] = None
    props: dict = field(default_factory=dict)

    @property
    def label(self) -> str:
        model = self.props.get("model") or self.props.get("device")
        tail = self.key[-6:] if self.adb else self.key.split(":", 1)[-1]
        if model:
            return "%s-%s" % (model.replace(" ", ""), tail)
        if self.port is not None and self.port.description:
            return "%s-%s" % (self.port.description.split("(")[0].strip().replace(" ", "")[:24], tail)
        return self.key

    def describe(self) -> dict:
        return {"key": self.key, "label": self.label,
                "adb": {"serial": self.adb.serial, "state": self.adb.state, "info": self.adb.info} if self.adb else None,
                "port": self.port.describe() if self.port else None}


def _norm(text: Optional[str]) -> str:
    return (text or "").strip().lower()


# --- discovery --------------------------------------------------------------------------------

def scan(include_adb: bool = True) -> Tuple[list, list]:
    """-> (diag ports, adb devices) visible right now."""
    ports = []
    for p in tr.list_serial_ports():
        if p.likely_diag:
            ports.append(DiagPort("serial", p.device, p.description, p.vid, p.pid,
                                  p.serial_number, p.location))
    for d in tr.list_usb_diag_devices():
        ports.append(DiagPort("usb", "%04X:%04X@bus%s-addr%s" % (d["vid"], d["pid"], d["bus"], d["address"]),
                              "USB diag interface %d" % d["interface"], d["vid"], d["pid"],
                              d.get("serial"), None, d["interface"]))
    adbs = []
    if include_adb and tr.adb_path():
        for d in tr.adb_devices():
            adbs.append(AdbDevice(d["serial"], d["state"], d.get("info", "")))
    return ports, adbs


def pair(ports: Iterable[DiagPort], adbs: Iterable[AdbDevice], busy_ports: Iterable[str] = (),
         exclude_keys: Iterable[str] = ()) -> list:
    """Group ports and adb devices into handsets.

    A port whose USB serial equals an adb serial belongs to that phone. If
    exactly one port and one adb device are left unmatched they are assumed to
    be the same phone (the serial is not always exposed through the serial
    driver). Ports in `busy_ports` are already owned by a capture and are
    ignored; handsets whose key is in `exclude_keys` are dropped.
    """
    busy = set(busy_ports)
    ports = [p for p in ports if p.id not in busy]
    adbs = list(adbs)
    handsets = []
    used_adb: Set[str] = set()
    for port in ports:
        match = None
        if port.serial_number:
            for a in adbs:
                if _norm(a.serial) == _norm(port.serial_number) and a.serial not in used_adb:
                    match = a
                    break
        if match is not None:
            used_adb.add(match.serial)
            handsets.append(Handset(match.serial, match, port))
        else:
            handsets.append(Handset("port:" + port.id, None, port))
    loose_ports = [h for h in handsets if h.adb is None]
    loose_adbs = [a for a in adbs if a.serial not in used_adb]
    if len(loose_ports) == 1 and len(loose_adbs) == 1:
        h = loose_ports[0]
        h.adb = loose_adbs[0]
        h.key = h.adb.serial
        used_adb.add(h.adb.serial)
        loose_adbs = []
    for a in loose_adbs:
        handsets.append(Handset(a.serial, a, None))
    skip = set(exclude_keys)
    return [h for h in handsets if h.key not in skip]


# --- preparation ------------------------------------------------------------------------------

def prepare(handset: Handset, busy_ports: Set[str], log: Callable[[str], None] = lambda s: None,
            timeout: float = 45.0, poll: float = 1.0, enable_diag: bool = True) -> tr.Transport:
    """Return an opened-ready transport for the handset, claiming its port id in
    `busy_ports`. Raises NotReady when the phone needs a human (USB debugging
    prompt, no root) or has not enumerated a diag port yet."""
    if handset.port is not None:
        busy_ports.add(handset.port.id)
        return handset.port.transport()
    adb = handset.adb
    if adb is None:
        raise NotReady("no diag port and no adb device")
    if adb.state == "unauthorized":
        raise NotReady("adb says 'unauthorized': accept the USB debugging prompt on the phone")
    if adb.state != "device":
        raise NotReady("adb device is %s" % adb.state)
    if not handset.props:
        handset.props = tr.adb_getprops(adb.serial)
    if is_emulator(handset.props):
        raise NotReady("this is an Android emulator (%s), not a handset: it has no Qualcomm "
                       "modem and no /dev/diag, so there is nothing to capture"
                       % (handset.props.get("model") or handset.props.get("hardware") or "?"),
                       permanent=True)
    before = {p.id for p in scan(include_adb=False)[0]}
    usb_state = handset.props.get("usb_state", "") or handset.props.get("usb_config", "")
    if enable_diag and "diag" not in usb_state:
        log("USB config is '%s': switching the phone to a diag composition (needs root)" % (usb_state or "?"))
        try:
            state = tr.adb_enable_diag_usb(adb.serial)
        except tr.TransportError as exc:
            raise NotReady("could not enable diag over adb (is the phone rooted?): %s" % exc)
        log("phone reports usb state '%s'; waiting for the diag port to enumerate" % state)
    deadline = time.monotonic() + timeout
    log("waiting up to %.0f s for a diag port to enumerate" % timeout)
    announced = 0.0
    while True:
        waited = timeout - (deadline - time.monotonic())
        if waited - announced >= 10:
            announced = waited
            log("still waiting for a diag port (%.0f s of %.0f s)" % (waited, timeout))
        ports = [p for p in scan(include_adb=False)[0] if p.id not in busy_ports]
        by_serial = [p for p in ports if p.serial_number and _norm(p.serial_number) == _norm(adb.serial)]
        fresh = [p for p in ports if p.id not in before]
        chosen = None
        if by_serial:
            chosen = by_serial[0]
        elif len(fresh) == 1:
            chosen = fresh[0]
        elif not enable_diag and len(ports) == 1:
            chosen = ports[0]
        if chosen is not None:
            handset.port = chosen
            busy_ports.add(chosen.id)
            log("diag port %s (%s)" % (chosen.id, chosen.description))
            return chosen.transport()
        if time.monotonic() >= deadline:
            hint = ("the phone was not asked to expose diag (--no-enable-diag)"
                    if not enable_diag else
                    "the phone accepted the diag USB config but no port appeared: "
                    "Qualcomm USB driver installed?")
            raise NotReady("no diag port after %.0f s. %s Run `fieldtap devices` to see what "
                           "Windows sees, and docs/DEVICE-SETUP.md for the driver" % (timeout, hint))
        time.sleep(poll)


def release(handset: Handset, busy_ports: Set[str]) -> None:
    if handset.port is not None:
        busy_ports.discard(handset.port.id)


def present_keys(ports: Iterable[DiagPort], adbs: Iterable[AdbDevice]) -> Set[str]:
    """Every identity currently visible, so the orchestrator can tell when a
    finished handset has actually been unplugged."""
    keys = {a.serial for a in adbs}
    for p in ports:
        keys.add("port:" + p.id)
        if p.serial_number:
            keys.add(p.serial_number)
    return keys
