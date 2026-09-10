"""Cell scanning without diag, and without root.

Android's public telephony layer already knows a great deal about the radio
environment: every cell the modem currently reports, its PLMN and operator
name, physical cell id, ARFCN, band, tracking area, cell identity, and the
signal strength measured on it. All of that is readable over adb from an
unrooted phone, and it works with no SIM inserted.

What it cannot give is the broadcast itself. MIB and the SIBs are decoded
inside the modem and never surface as ASN.1 through a public API, so a real
SIB decode still needs the diag path (`fieldtap capture`). What does survive
is the handful of SIB1 fields Android re-exposes as structured data: the
PLMN, any additional PLMNs broadcast by the cell, the tracking area code,
the cell identity and the band list. This module reads those.

    fieldtap scan                 one snapshot of every visible cell
    fieldtap scan --watch 5       keep sampling, log the changes
    fieldtap scan --operators     ask the modem for a full PLMN search
"""

from __future__ import annotations

import csv
import io
import re
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Callable, Optional

from .diag import transport as tr

# Android renders "not available" as Integer.MAX_VALUE throughout telephony.
UNAVAILABLE = 2147483647


@dataclass
class Cell:
    rat: str = ""                       # nr | lte | wcdma | gsm | tdscdma | cdma
    registered: bool = False
    status: str = ""                    # connection status, when reported
    mcc: Optional[str] = None
    mnc: Optional[str] = None
    operator: str = ""
    pci: Optional[int] = None
    arfcn: Optional[int] = None
    bands: str = ""
    tac: Optional[int] = None
    cell_id: Optional[int] = None
    bandwidth_khz: Optional[int] = None
    rsrp: Optional[int] = None
    rsrq: Optional[int] = None
    sinr: Optional[int] = None
    rssi: Optional[int] = None
    level: Optional[int] = None
    additional_plmns: str = ""
    seen_utc: str = ""

    @property
    def plmn(self) -> str:
        if self.mcc and self.mnc:
            return "%s%s" % (self.mcc, self.mnc)
        return ""

    @property
    def key(self) -> tuple:
        return (self.rat, self.plmn, self.pci, self.arfcn, self.cell_id)

    def line(self) -> str:
        return "%-5s %-6s %-16s pci %-5s arfcn %-8s band %-6s tac %-8s cell %-14s rsrp %-6s rsrq %-5s sinr %-5s%s" % (
            self.rat.upper(), self.plmn or "-", (self.operator or "-")[:16], _s(self.pci), _s(self.arfcn),
            self.bands or "-", _s(self.tac), _s(self.cell_id), _s(self.rsrp), _s(self.rsrq), _s(self.sinr),
            "  <- registered" if self.registered else "")


def _s(value) -> str:
    return "-" if value is None else str(value)


COLUMNS = ["seen_utc", "rat", "registered", "plmn", "mcc", "mnc", "operator", "pci", "arfcn", "bands",
           "tac", "cell_id", "bandwidth_khz", "rsrp", "rsrq", "sinr", "rssi", "level", "additional_plmns"]


def to_csv(cells: list) -> str:
    out = io.StringIO()
    w = csv.DictWriter(out, fieldnames=COLUMNS, extrasaction="ignore")
    w.writeheader()
    for c in cells:
        row = {k: getattr(c, k, "") for k in COLUMNS}
        row["registered"] = int(c.registered)
        row["plmn"] = c.plmn
        w.writerow({k: ("" if v is None else v) for k, v in row.items()})
    return out.getvalue()


# --- parsing dumpsys telephony.registry ------------------------------------------------------

_BLOCK_RE = re.compile(r"(CellIdentity|CellSignalStrength|CellConfig)(Nr|Lte|Wcdma|Gsm|Tdscdma|Cdma)\s*:?\s*\{")
_KV_RE = re.compile(r"([A-Za-z][A-Za-z0-9_]*)\s*=\s*")


def _balanced(text: str, open_at: int) -> str:
    """The contents of the {...} that starts at open_at."""
    depth = 0
    for i in range(open_at, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[open_at + 1:i]
    return text[open_at + 1:]


def _fields(blob: str) -> dict:
    """`a = 1 b = Verizon Wireless c = [2]` -> {'a': '1', ...}.

    Values are taken up to the next `key =`, so operator names with spaces
    survive, which a naive split on whitespace would not manage.
    """
    out = {}
    matches = list(_KV_RE.finditer(blob))
    for i, m in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(blob)
        value = blob[m.end():end].strip().strip(",").strip()
        while value.endswith("}") and value.count("}") > value.count("{"):
            value = value[:-1].strip()
        out[m.group(1)] = value
    return out


def _int(fields: dict, *names) -> Optional[int]:
    for name in names:
        raw = fields.get(name)
        if raw is None or raw == "":
            continue
        try:
            value = int(raw)
        except ValueError:
            continue
        if value == UNAVAILABLE or value == -UNAVAILABLE - 1:
            continue
        return value
    return None


def _text(fields: dict, *names) -> str:
    for name in names:
        raw = fields.get(name)
        if raw and raw not in ("null", "none", "{}", "[]"):
            return raw
    return ""


def _plmn_digits(fields: dict, key: str) -> Optional[str]:
    raw = fields.get(key)
    if raw is None or raw in ("", "null", str(UNAVAILABLE)):
        return None
    raw = raw.strip()
    return raw if raw.isdigit() else None


def parse_cells(text: str) -> list:
    """Pull every cell out of `dumpsys telephony.registry` output.

    Identity and signal-strength blocks are matched up in order: Android
    prints them adjacently inside each CellInfo, so an identity followed by a
    signal-strength block of the same RAT belongs together.
    """
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    cells: list = []
    pending: Optional[Cell] = None
    for m in _BLOCK_RE.finditer(text):
        kind, rat = m.group(1), m.group(2).lower()
        blob = _balanced(text, m.end() - 1)
        fields = _fields(blob)
        if kind == "CellIdentity":
            if pending is not None:
                cells.append(pending)
            cell = Cell(rat=rat, seen_utc=now)
            cell.mcc = _plmn_digits(fields, "mMcc")
            cell.mnc = _plmn_digits(fields, "mMnc")
            cell.operator = _text(fields, "mAlphaLong", "mAlphaShort")
            cell.pci = _int(fields, "mPci")
            cell.arfcn = _int(fields, "mNrArfcn", "mEarfcn", "mUarfcn", "mArfcn", "mChannelNumber")
            cell.bands = _text(fields, "mBands").strip("[]")
            cell.tac = _int(fields, "mTac", "mLac")
            cell.cell_id = _int(fields, "mNci", "mCi", "mCid", "mBasestationId")
            cell.bandwidth_khz = _int(fields, "mBandwidth")
            cell.additional_plmns = _text(fields, "mAdditionalPlmns").strip("{}")
            pending = cell
            # "mRegistered=YES" sits just before the identity block
            head = text[max(0, m.start() - 220):m.start()]
            if re.search(r"mRegistered\s*=\s*YES", head):
                cell.registered = True
            status = re.search(r"mCellConnectionStatus\s*=\s*(\d+)", head)
            if status:
                cell.status = status.group(1)
        elif kind == "CellSignalStrength" and pending is not None and pending.rat == rat:
            pending.rsrp = _int(fields, "ssRsrp", "rsrp", "csiRsrp")
            pending.rsrq = _int(fields, "ssRsrq", "rsrq", "csiRsrq")
            pending.sinr = _int(fields, "ssSinr", "rssnr", "csiSinr", "mSnr")
            pending.rssi = _int(fields, "rssi", "mRssi")
            pending.level = _int(fields, "level", "mLevel")
    if pending is not None:
        cells.append(pending)
    # A cell with no identity at all is noise from an empty registration slot.
    cells = [c for c in cells if c.plmn or c.pci is not None or c.cell_id is not None]
    return _merge(cells)


def _merge(cells: list) -> list:
    """The same cell is printed more than once - the registration block names
    it without measurements, the cell-info list repeats it with them. Collapse
    them so a scan does not report one cell as two, keeping whichever copy
    carries the most detail."""
    merged: dict = {}
    order: list = []
    for cell in cells:
        existing = merged.get(cell.key)
        if existing is None:
            merged[cell.key] = cell
            order.append(cell.key)
            continue
        for name in ("rsrp", "rsrq", "sinr", "rssi", "level", "tac", "cell_id", "bandwidth_khz"):
            if getattr(existing, name) is None:
                setattr(existing, name, getattr(cell, name))
        for name in ("operator", "bands", "additional_plmns", "status"):
            # Keep the fuller string: the registration block abbreviates the
            # operator ("Verizon") where the cell-info list spells it out
            # ("Verizon Wireless"), and lists more of the extra PLMNs.
            if len(getattr(cell, name) or "") > len(getattr(existing, name) or ""):
                setattr(existing, name, getattr(cell, name))
        existing.registered = existing.registered or cell.registered
    return [merged[k] for k in order]


_SS_RE = re.compile(r"mVoiceRegState\s*=\s*\d+\((?P<voice>\w+)\).*?mDataRegState\s*=\s*\d+\((?P<data>\w+)\)", re.S)


def parse_service_state(text: str) -> dict:
    m = _SS_RE.search(text)
    out = {"voice": m.group("voice") if m else "", "data": m.group("data") if m else ""}
    rplmn = re.search(r"rRplmn\s*=\s*(\d+)", text)
    out["registered_plmn"] = rplmn.group(1) if rplmn else ""
    emergency = re.search(r"mIsEmergencyOnly\s*=\s*(\w+)", text)
    out["emergency_only"] = emergency.group(1) if emergency else ""
    return out


# --- driving the phone ----------------------------------------------------------------------

def dump(serial: Optional[str] = None, timeout: float = 30.0) -> str:
    return tr.adb(["shell", "dumpsys", "telephony.registry"], serial=serial, timeout=timeout, check=False)


def snapshot(serial: Optional[str] = None) -> tuple:
    """-> (cells, service state). One reading of everything the phone reports."""
    text = dump(serial)
    return parse_cells(text), parse_service_state(text)


def sim_state(serial: Optional[str] = None) -> str:
    try:
        return tr.adb(["shell", "getprop", "gsm.sim.state"], serial=serial, timeout=10,
                      check=False).strip() or "UNKNOWN"
    except Exception:
        return "UNKNOWN"


def request_operator_search(serial: Optional[str] = None) -> None:
    """Open the manual network-selection screen, which makes the modem run a
    real PLMN search and list every operator it can hear. The result is shown
    on the phone; Android exposes no machine-readable form of it."""
    tr.adb(["shell", "am", "start", "-a", "android.settings.NETWORK_OPERATOR_SETTINGS"],
           serial=serial, timeout=20, check=False)


def watch(serial: Optional[str] = None, interval: float = 5.0, seconds: Optional[float] = None,
          log: Callable[[str], None] = print, stop=None) -> list:
    """Sample repeatedly, reporting cells as they appear, change or vanish.

    Android throttles how often it will refresh cell information, so a very
    short interval simply returns the same reading; a few seconds is the
    useful floor.
    """
    seen: dict = {}
    history: list = []
    started = time.monotonic()
    while True:
        if stop is not None and stop():
            break
        if seconds is not None and time.monotonic() - started >= seconds:
            break
        try:
            cells, _state = snapshot(serial)
        except Exception as exc:
            log("scan failed: %s" % exc)
            time.sleep(interval)
            continue
        history.extend(cells)
        current = {c.key: c for c in cells}
        for key, cell in current.items():
            previous = seen.get(key)
            if previous is None:
                log("+ %s" % cell.line())
            elif (previous.rsrp, previous.registered) != (cell.rsrp, cell.registered):
                log("  %s" % cell.line())
        for key in list(seen):
            if key not in current:
                log("- lost %s" % seen[key].line())
        seen = current
        time.sleep(interval)
    return history


def render(cells: list, state: dict, sim: str = "") -> str:
    lines = []
    if sim:
        lines.append("SIM: %s" % sim)
    if state.get("voice") or state.get("data"):
        lines.append("registration: voice %s, data %s%s%s" % (
            state.get("voice") or "?", state.get("data") or "?",
            ", PLMN %s" % state["registered_plmn"] if state.get("registered_plmn") else "",
            ", emergency only" if state.get("emergency_only") == "true" else ""))
    if not cells:
        lines.append("")
        lines.append("No cells reported. With no SIM some builds report nothing until the")
        lines.append("modem is asked to search: try `fieldtap scan --operators`.")
        return "\n".join(lines)
    lines.append("")
    lines.append("%d cell(s) visible to the modem:" % len(cells))
    for cell in sorted(cells, key=lambda c: (not c.registered, -(c.rsrp or -999))):
        lines.append("  " + cell.line())
    plmns = sorted({c.plmn for c in cells if c.plmn})
    extra = sorted({p for c in cells for p in c.additional_plmns.replace(" ", "").split(",") if p})
    if plmns:
        lines.append("")
        lines.append("operators heard: %s" % ", ".join(
            "%s%s" % (p, " (%s)" % next((c.operator for c in cells if c.plmn == p and c.operator), "")
                      if any(c.operator for c in cells if c.plmn == p) else "") for p in plmns))
    if extra:
        lines.append("additional PLMNs broadcast by those cells (from SIB1): %s" % ", ".join(extra))
    lines.append("")
    lines.append("This is what the modem reports through Android. Raw MIB/SIB decode needs")
    lines.append("the diag path: see `fieldtap capture`.")
    return "\n".join(lines)
