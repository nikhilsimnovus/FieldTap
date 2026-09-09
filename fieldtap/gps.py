"""Location for drive-test tagging.

Two sources, both optional and both polled in a background thread into a
thread-safe Track:

* the phone's own location through adb (`dumpsys location`, last known fix
  per provider; no app needed, works on a rooted or unrooted phone with USB
  debugging), and
* an NMEA GPS receiver on a serial port of the laptop, shared by every
  handset in a multi-phone campaign.

Fixes are time-stamped with host UTC when they are observed. The join to
measurements is by nearest time (`Track.nearest`).
"""

from __future__ import annotations

import csv
import io
import re
import subprocess
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Callable, Optional

from .diag import transport as tr


@dataclass
class Fix:
    when: datetime
    lat: float
    lon: float
    accuracy_m: Optional[float] = None
    altitude_m: Optional[float] = None
    speed_mps: Optional[float] = None
    provider: str = ""
    source: str = ""           # adb | nmea

    @property
    def when_iso(self) -> str:
        return self.when.astimezone(timezone.utc).isoformat(timespec="milliseconds")


COLUMNS = ["time_utc", "lat", "lon", "accuracy_m", "altitude_m", "speed_mps", "provider", "source"]


class Track:
    def __init__(self):
        self._fixes: list = []
        self._lock = threading.Lock()

    def add(self, fix: Fix) -> None:
        with self._lock:
            self._fixes.append(fix)

    def fixes(self) -> list:
        with self._lock:
            return list(self._fixes)

    def __len__(self) -> int:
        with self._lock:
            return len(self._fixes)

    def last(self) -> Optional[Fix]:
        with self._lock:
            return self._fixes[-1] if self._fixes else None

    def between(self, start: Optional[datetime], end: Optional[datetime]) -> list:
        return [f for f in self.fixes() if (start is None or f.when >= start) and (end is None or f.when <= end)]

    def nearest(self, when: datetime, max_gap_s: float = 30.0) -> Optional[Fix]:
        best = None
        best_gap = None
        for f in self.fixes():
            gap = abs((f.when - when).total_seconds())
            if best_gap is None or gap < best_gap:
                best, best_gap = f, gap
        return best if best is not None and best_gap <= max_gap_s else None

    def to_csv(self) -> str:
        out = io.StringIO()
        w = csv.writer(out)
        w.writerow(COLUMNS)
        for f in self.fixes():
            w.writerow([f.when_iso, "%.7f" % f.lat, "%.7f" % f.lon,
                        "" if f.accuracy_m is None else "%.1f" % f.accuracy_m,
                        "" if f.altitude_m is None else "%.1f" % f.altitude_m,
                        "" if f.speed_mps is None else "%.2f" % f.speed_mps, f.provider, f.source])
        return out.getvalue()

    def write_csv(self, path: str) -> None:
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(self.to_csv())

    @classmethod
    def read_csv(cls, path: str) -> "Track":
        track = cls()
        with open(path, encoding="utf-8", newline="") as fh:
            for row in csv.DictReader(fh):
                def num(key):
                    return float(row[key]) if row.get(key) else None
                track.add(Fix(datetime.fromisoformat(row["time_utc"]), float(row["lat"]), float(row["lon"]),
                              num("accuracy_m"), num("altitude_m"), num("speed_mps"),
                              row.get("provider", ""), row.get("source", "")))
        return track

    def bounds(self):
        fixes = self.fixes()
        if not fixes:
            return None
        lats = [f.lat for f in fixes]
        lons = [f.lon for f in fixes]
        return min(lats), min(lons), max(lats), max(lons)


# --- adb: dumpsys location -------------------------------------------------------------------

# Android prints last-known fixes as, e.g.
#   Location[fused 12.9715987,77.5945627 hAcc=12.0 et=+3d2h11m5s123ms alt=920.3 vel=0.4 bear=90.0 vAcc=... {Bundle[...]}]
# The provider name and the lat,lon pair are stable across Android 8..15; the rest varies.
_LOCATION_RE = re.compile(r"Location\[(?P<prov>[A-Za-z_]+)\s+(?P<lat>-?\d+(?:\.\d+)?),\s*(?P<lon>-?\d+(?:\.\d+)?)(?P<rest>[^\]]*)\]")
_KV_RE = re.compile(r"(\w+)=([-+]?[\d.]+)")
_PROVIDER_RANK = {"gps": 0, "fused": 1, "network": 2, "passive": 3}


def parse_dumpsys_location(text: str) -> list:
    """Every Location[...] in a dumpsys dump, best provider first."""
    fixes = []
    for m in _LOCATION_RE.finditer(text):
        rest = dict(_KV_RE.findall(m.group("rest")))
        try:
            lat, lon = float(m.group("lat")), float(m.group("lon"))
        except ValueError:
            continue
        if not (-90 <= lat <= 90 and -180 <= lon <= 180) or (lat == 0 and lon == 0):
            continue
        fixes.append(Fix(datetime.now(timezone.utc), lat, lon,
                         float(rest["hAcc"]) if "hAcc" in rest else None,
                         float(rest["alt"]) if "alt" in rest else None,
                         float(rest["vel"]) if "vel" in rest else None,
                         m.group("prov"), "adb"))
    fixes.sort(key=lambda f: _PROVIDER_RANK.get(f.provider, 9))
    return fixes


class AdbLocationPoller(threading.Thread):
    """Poll the phone's last known location every `interval` seconds."""

    def __init__(self, serial: Optional[str], track: Track, interval: float = 5.0,
                 log: Callable[[str], None] = lambda s: None, stop: Optional[threading.Event] = None):
        super().__init__(name="fieldtap-gps-adb", daemon=True)
        self.serial = serial
        self.track = track
        self.interval = interval
        self.log = log
        self.stop_event = stop or threading.Event()
        self.polls = 0
        self.errors = 0
        self._last_key = None

    def poll_once(self) -> Optional[Fix]:
        self.polls += 1
        try:
            out = tr.adb(["shell", "dumpsys", "location"], serial=self.serial, timeout=15, check=False)
        except Exception:
            self.errors += 1
            return None
        fixes = parse_dumpsys_location(out)
        if not fixes:
            return None
        fix = fixes[0]
        key = (fix.provider, round(fix.lat, 7), round(fix.lon, 7), fix.altitude_m)
        if key == self._last_key:
            return None          # the phone has not produced a new fix
        self._last_key = key
        self.track.add(fix)
        return fix

    def run(self) -> None:
        while not self.stop_event.is_set():
            fix = self.poll_once()
            if fix is not None and len(self.track) == 1:
                self.log("gps: first fix %.6f,%.6f (%s)" % (fix.lat, fix.lon, fix.provider))
            self.stop_event.wait(self.interval)


# --- NMEA on a serial port -------------------------------------------------------------------------

def nmea_checksum_ok(line: str) -> bool:
    line = line.strip()
    if not line.startswith("$") or "*" not in line:
        return False
    body, _, given = line[1:].partition("*")
    calc = 0
    for ch in body:
        calc ^= ord(ch)
    try:
        return calc == int(given[:2], 16)
    except ValueError:
        return False


def _dm_to_deg(value: str, hemi: str) -> Optional[float]:
    if not value:
        return None
    try:
        v = float(value)
    except ValueError:
        return None
    deg = int(v // 100)
    minutes = v - deg * 100
    out = deg + minutes / 60.0
    return -out if hemi in ("S", "W") else out


def parse_nmea(line: str, now: Optional[datetime] = None) -> Optional[Fix]:
    """$GPRMC/$GNRMC -> Fix with speed; $GPGGA/$GNGGA -> Fix with altitude and
    accuracy proxy (HDOP * 5 m). Returns None for other sentences or no fix."""
    if not nmea_checksum_ok(line):
        return None
    parts = line.strip()[1:].split("*")[0].split(",")
    tag = parts[0][2:] if len(parts[0]) >= 5 else parts[0]
    now = now or datetime.now(timezone.utc)
    if tag == "RMC" and len(parts) >= 8:
        if parts[2] != "A":
            return None
        lat, lon = _dm_to_deg(parts[3], parts[4]), _dm_to_deg(parts[5], parts[6])
        if lat is None or lon is None:
            return None
        speed = float(parts[7]) * 0.514444 if parts[7] else None
        return Fix(now, lat, lon, None, None, speed, "nmea-rmc", "nmea")
    if tag == "GGA" and len(parts) >= 10:
        if parts[6] in ("", "0"):
            return None
        lat, lon = _dm_to_deg(parts[2], parts[3]), _dm_to_deg(parts[4], parts[5])
        if lat is None or lon is None:
            return None
        hdop = float(parts[8]) if parts[8] else None
        alt = float(parts[9]) if parts[9] else None
        return Fix(now, lat, lon, hdop * 5.0 if hdop else None, alt, None, "nmea-gga", "nmea")
    return None


class NmeaPoller(threading.Thread):
    """Read an NMEA receiver on a COM port; keep one fix per `interval` seconds."""

    def __init__(self, port: str, track: Track, baud: int = 9600, interval: float = 1.0,
                 log: Callable[[str], None] = lambda s: None, stop: Optional[threading.Event] = None):
        super().__init__(name="fieldtap-gps-nmea", daemon=True)
        self.port, self.baud, self.track, self.interval, self.log = port, baud, track, interval, log
        self.stop_event = stop or threading.Event()
        self.errors = 0

    def run(self) -> None:
        try:
            import serial
        except ImportError:
            self.log("gps: pyserial not installed, NMEA receiver ignored")
            return
        try:
            ser = serial.Serial(self.port, self.baud, timeout=1)
        except Exception as exc:
            self.log("gps: cannot open %s: %s" % (self.port, exc))
            self.errors += 1
            return
        last = 0.0
        with ser:
            while not self.stop_event.is_set():
                try:
                    line = ser.readline().decode("ascii", "replace")
                except Exception:
                    self.errors += 1
                    time.sleep(1)
                    continue
                fix = parse_nmea(line)
                if fix is not None and time.monotonic() - last >= self.interval:
                    last = time.monotonic()
                    self.track.add(fix)
                    if len(self.track) == 1:
                        self.log("gps: first NMEA fix %.6f,%.6f" % (fix.lat, fix.lon))


# --- joining ----------------------------------------------------------------------------------------

def tag_rows(rows: list, track: Track, time_key: str = "time_epoch", max_gap_s: float = 30.0) -> int:
    """Add lat/lon to dict rows that carry an epoch-seconds time. Returns rows tagged."""
    tagged = 0
    for row in rows:
        raw = row.get(time_key)
        if not raw:
            row.setdefault("lat", "")
            row.setdefault("lon", "")
            continue
        try:
            when = datetime.fromtimestamp(float(raw), tz=timezone.utc)
        except (TypeError, ValueError):
            continue
        fix = track.nearest(when, max_gap_s)
        if fix is None:
            row["lat"], row["lon"] = "", ""
        else:
            row["lat"], row["lon"] = "%.7f" % fix.lat, "%.7f" % fix.lon
            tagged += 1
    return tagged
