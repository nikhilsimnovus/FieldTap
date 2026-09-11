"""Generate the golden Android app session in tests/fixtures/android_session/.

    python tests/fixtures/make_android_session.py [OUTPUT_ROOT]

Two minutes of walking with an Android phone that has a SIM: an LTE anchor
that changes sector halfway, an NR NSA leg that drops during the change, a
1 Hz GPS track, a 14 s sampling gap while the screen was off, a marker with a
note, one ping and one download. Every value is fictional.

The output is deterministic, and every file that fieldtap has a writer for is
written by that writer, so the bytes are what fieldtap-session/1 means. The
Kotlin :format tests must reproduce these files byte for byte;
tests/test_android_session.py checks that this script still does.
"""

from __future__ import annotations

import csv
import hashlib
import json
import math
import os
import sys
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
if REPO not in sys.path:
    sys.path.insert(0, REPO)

from fieldtap import contract, events, gps, kpi, report, scan, session, traffic  # noqa: E402

START = datetime(2026, 9, 10, 14, 30, 0, tzinfo=timezone.utc)
DURATION_MS = 120000
NAME = "Mall walk (north path)"

PLMN, MCC, MNC, OPERATOR = "311480", "311", "480", "Verizon"
TAC = 18704
ENB = 84532
LTE_EARFCN, LTE_UL_EARFCN, LTE_BAND = 66786, 132322, 66
NR_ARFCN, NR_BAND, NR_PCI = 650000, 77, 393
SECTOR_A = {"pci": 212, "sector": 1}
SECTOR_B = {"pci": 213, "sector": 2}
CHANGE_MS = 64400                        # the anchor moves from sector 1 to sector 2
NR_ABSENT = (62000, 70000)               # the NR leg is released around the change
GAP = (90400, 104400)                    # screen off: no fresh cell info in between
BOOT_MS_AT_START = 25323456              # CellInfo.getTimestampMillis() is milliseconds since boot

START_LAT, START_LON = 38.8895000, -77.0353000

# Values on which the usual Kotlin formatting mistakes (String.format, BigDecimal.valueOf,
# RoundingMode.HALF_UP, Math.round) give other digits than the contract's half-even
# rounding of the exact binary value, so the :format tests catch them. Location's
# getAccuracy() and getSpeed() return a float; each of these is exact in a float.
ROUNDING_FIX_SECOND = 12
ROUNDING_FIX = {"accuracy_m": 4.25, "altitude_m": 18.25, "speed_mps": 1.125}   # written 4.2, 18.2, 1.12
PING_AT_MS, PING_MS = 30000, 4135                        # 4.13 s; String.format writes 4.14
DOWNLOAD_AT_MS, DOWNLOAD_MS, DOWNLOAD_BYTES = 36000, 4000, 10031250   # 20.0625 Mbit/s, written 20.062


def at(ms: int) -> datetime:
    return START + timedelta(milliseconds=ms)


def epoch_text(ms: int) -> str:
    """time_epoch from integer milliseconds: exact, no float rounding."""
    whole, frac = divmod(int(round(START.timestamp() * 1000)) + ms, 1000)
    return "%d.%03d" % (whole, frac)


def wobble(ms: int, a: float = 1.5, b: float = 0.7) -> float:
    t = ms / 1000.0
    return a * math.sin(t / 5.3) + b * math.sin(t / 1.7)


def lte_rsrp(pci: int, ms: int) -> int:
    t = ms / 1000.0
    if pci == SECTOR_A["pci"]:
        value = -84.0 - 17.0 * t / 64.0 if ms < CHANGE_MS else -101.0 - 8.0 * (t - 64.4) / 56.0
    else:
        value = -104.0 + 16.0 * t / 64.0 if ms < CHANGE_MS else -88.0 + 2.5 * math.sin((t - 64.4) / 9.0)
    return int(round(value + wobble(ms)))


def lte_level(rsrp: int) -> int:
    return 4 if rsrp >= -85 else 3 if rsrp >= -95 else 2 if rsrp >= -105 else 1 if rsrp >= -115 else 0


def nr_level(rsrp: int) -> int:
    return 4 if rsrp >= -65 else 3 if rsrp >= -80 else 2 if rsrp >= -90 else 1 if rsrp >= -110 else 0


def clip(value: float, low: int, high: int) -> int:
    return int(max(low, min(high, round(value))))


def measurements() -> list:
    """Fresh modem measurements: every 2 s while the screen is on, none in the gap."""
    out = []
    ms = 400
    while ms < DURATION_MS:
        if not (GAP[0] < ms < GAP[1]):
            out.append(ms)
        ms += 2000
    return out


def serving(ms: int) -> dict:
    return SECTOR_A if ms < CHANGE_MS else SECTOR_B


def neighbour(ms: int) -> dict:
    return SECTOR_B if ms < CHANGE_MS else SECTOR_A


def nr_present(ms: int) -> bool:
    return ms >= 8000 and not (NR_ABSENT[0] <= ms < NR_ABSENT[1])


def radio(ms: int) -> dict:
    """Everything Android reports for the measurement taken at ms."""
    cell = serving(ms)
    rsrp = lte_rsrp(cell["pci"], ms)
    other = neighbour(ms)
    n_rsrp = lte_rsrp(other["pci"], ms)
    out = {
        "lte": {"pci": cell["pci"], "sector": cell["sector"], "rsrp": rsrp,
                "rsrq": clip(-9 + (rsrp + 90) * 0.15, -19, -3), "rssnr": clip(12 + (rsrp + 90) * 0.5, -5, 30),
                "rssi": clip(rsrp + 27, -113, -51), "level": lte_level(rsrp),
                "cqi": clip(10 + (rsrp + 90) * 0.2, 0, 15), "ta": 3},
        "neighbour": {"pci": other["pci"], "rsrp": n_rsrp, "rsrq": clip(-12 + (n_rsrp + 95) * 0.15, -19, -3),
                      "level": lte_level(n_rsrp)},
        "nr": None,
    }
    if nr_present(ms):
        ss_rsrp = int(round(rsrp - 6 + wobble(ms + 777, 1.2, 0.6)))
        out["nr"] = {"pci": NR_PCI, "rsrp": ss_rsrp, "rsrq": clip(-10 + (ss_rsrp + 96) * 0.1, -20, -3),
                     "sinr": clip(9 + (ss_rsrp + 96) * 0.45, -10, 30), "level": nr_level(ss_rsrp)}
    return out


def track() -> gps.Track:
    fixes = gps.Track()
    lon_per_m = 1.0 / (111320.0 * math.cos(math.radians(START_LAT)))
    for second in range(DURATION_MS // 1000):
        t = float(second)
        walked = 1.4 * t
        lat = START_LAT + (0.9 * math.sin(t / 23.0) + 0.02 * t) / 111320.0
        lon = START_LON - walked * lon_per_m
        accuracy = 3.8 + 1.1 * (1 + math.sin(t / 13.0)) + (2.5 if GAP[0] <= second * 1000 < GAP[1] else 0.0)
        altitude = 18.0 + 0.4 * math.sin(t / 31.0)
        speed = 1.4 + 0.15 * math.sin(t / 7.0)
        if second == ROUNDING_FIX_SECOND:
            accuracy, altitude, speed = ROUNDING_FIX["accuracy_m"], ROUNDING_FIX["altitude_m"], ROUNDING_FIX["speed_mps"]
        fixes.add(gps.Fix(at(second * 1000), lat, lon, accuracy, altitude, speed, "gps", "android"))
    return fixes


def rounding_probes() -> list:
    """(file, time_utc of the row, column, value, decimals) for each rounding value
    above. tests/test_android_session.py checks that the fixture holds them and
    that every usual mistake gets at least one of them wrong."""
    fix = session.iso(at(ROUNDING_FIX_SECOND * 1000))
    download_seconds = DOWNLOAD_MS / 1000.0
    return [
        (contract.TRACK_CSV, fix, "accuracy_m", ROUNDING_FIX["accuracy_m"], 1),
        (contract.TRACK_CSV, fix, "altitude_m", ROUNDING_FIX["altitude_m"], 1),
        (contract.TRACK_CSV, fix, "speed_mps", ROUNDING_FIX["speed_mps"], 2),
        (contract.TRAFFIC_CSV, session.iso(at(PING_AT_MS)), "seconds", PING_MS / 1000.0, 2),
        (contract.TRAFFIC_CSV, session.iso(at(DOWNLOAD_AT_MS)), "seconds", download_seconds, 2),
        (contract.TRAFFIC_CSV, session.iso(at(DOWNLOAD_AT_MS)), "mbps", DOWNLOAD_BYTES * 8 / download_seconds / 1e6, 3),
    ]


def serving_detail(cell: dict) -> str:
    return "PLMN %s TAC %d eNB %d sector %d PCI %d EARFCN %d band %d" % (
        PLMN, TAC, ENB, cell["sector"], cell["pci"], LTE_EARFCN, LTE_BAND)


def build(root: str) -> dict:
    """Write the session under root. Returns its directory and the numbers a
    report built from it must show."""
    directory = os.path.join(root, "%s_%s" % (START.strftime("%Y%m%d-%H%M%S"), session.slugify(NAME)))
    os.makedirs(directory, exist_ok=True)
    fresh = measurements()
    fixes = track()

    # kpi.csv: one lte row per fresh sample, and an nr row with the same time while the leg is up
    kpi_rows = []
    for ms in fresh:
        r = radio(ms)
        kpi_rows.append({"frame": "", "time_epoch": epoch_text(ms), "rat": "lte", "meas_id": "",
                         "pci": r["lte"]["pci"], "rsrp_dbm": "%.1f" % r["lte"]["rsrp"],
                         "rsrq_db": "%.1f" % r["lte"]["rsrq"], "sinr_db": "%.1f" % r["lte"]["rssnr"],
                         "comment": "android-api age_ms=500 src=request"})
        if r["nr"] is not None:
            kpi_rows.append({"frame": "", "time_epoch": epoch_text(ms), "rat": "nr", "meas_id": "",
                             "pci": r["nr"]["pci"], "rsrp_dbm": "%.1f" % r["nr"]["rsrp"],
                             "rsrq_db": "%.1f" % r["nr"]["rsrq"], "sinr_db": "%.1f" % r["nr"]["sinr"],
                             "comment": "android-api age_ms=500 src=request"})
    gps.tag_rows(kpi_rows, fixes, max_gap_s=contract.GPS_MATCH_SECONDS)
    report._write_csv(os.path.join(directory, contract.KPI_CSV), kpi_rows, kpi.SESSION_COLUMNS)

    fixes.write_csv(os.path.join(directory, contract.TRACK_CSV))

    # events.csv
    evs = [
        events.Event(at(400), "lte", "serving_cell", "info", "Serving cell", serving_detail(SECTOR_A), None,
                     {"pci": SECTOR_A["pci"], "arfcn": LTE_EARFCN}),
        events.Event(at(8900), "nr", "nr_display", "info", "5G icon on", "override NR_NSA, network LTE"),
        events.Event(at(45200), "-", "marker", "info", "Marker", "North entrance – badge reader, door 3"),
        events.Event(at(62900), "nr", "nr_display", "info", "5G icon off", "override NONE, network LTE"),
        events.Event(at(CHANGE_MS), "lte", "serving_cell", "info", "Serving cell changed", serving_detail(SECTOR_B),
                     None, {"pci": SECTOR_B["pci"], "arfcn": LTE_EARFCN}),
        events.Event(at(70900), "nr", "nr_display", "info", "5G icon on", "override NR_NSA, network LTE"),
        events.Event(at(GAP[1] + 500), "-", "sampling_gap", "warn", "Sampling gap",
                     "no fresh cell info for %.1f s" % ((GAP[1] - GAP[0]) / 1000.0), None, {"cause": "screen_off"}),
    ]
    with open(os.path.join(directory, contract.EVENTS_CSV), "w", encoding="utf-8", newline="") as fh:
        fh.write(events.to_csv(evs))

    # traffic.csv
    download_seconds = DOWNLOAD_MS / 1000.0
    download_mbps = DOWNLOAD_BYTES * 8 / download_seconds / 1e6
    results = [
        traffic.TestResult(at(PING_AT_MS), "ping", "8.8.8.8", True,
                           {"loss_pct": 0.0, "rtt_min_ms": 38.2, "rtt_avg_ms": 44.7, "rtt_max_ms": 58.9}, "",
                           PING_MS / 1000.0),
        traffic.TestResult(at(DOWNLOAD_AT_MS), "download", "https://probe.5gto6g.com/10MB.bin", True,
                           {"mbps": download_mbps, "bytes": DOWNLOAD_BYTES, "http_code": "200"}, "", download_seconds),
    ]
    with open(os.path.join(directory, contract.TRAFFIC_CSV), "w", encoding="utf-8", newline="") as fh:
        fh.write(traffic.to_csv(results))

    # cells.csv: one row per serving cell, in the order first seen
    def rsrps(rat, pci):
        return [float(r["rsrp_dbm"]) for r in kpi_rows if r["rat"] == rat and r["pci"] == pci]

    cells = []
    first = {}
    for ms in fresh:
        r = radio(ms)
        first.setdefault(("lte", r["lte"]["pci"]), ms)
        if r["nr"] is not None:
            first.setdefault(("nr", r["nr"]["pci"]), ms)
    for (rat, pci), ms in sorted(first.items(), key=lambda item: item[1]):
        values = rsrps(rat, pci)
        row = {"first_seen_utc": session.iso(at(ms)), "rat": rat, "pci": pci, "version": "android",
               "plausible": True, "samples": len(values),
               "rsrp_min": "%.1f" % min(values), "rsrp_max": "%.1f" % max(values)}
        if rat == "lte":
            sector = SECTOR_A if pci == SECTOR_A["pci"] else SECTOR_B
            cell_id = ENB << 8 | sector["sector"]
            row.update(plmn=PLMN, mcc=MCC, mnc=MNC, tac=TAC, cell_id=cell_id, enb_id=cell_id >> 8,
                       sector=cell_id & 0xFF, band=LTE_BAND, dl_earfcn=LTE_EARFCN, ul_earfcn=LTE_UL_EARFCN,
                       dl_bw_mhz=20.0, ul_bw_mhz=20.0, operator=OPERATOR, additional_plmns="")
        else:
            # Android reports the NSA leg with no identity beyond PCI, ARFCN and band.
            row.update(band=NR_BAND, dl_earfcn=NR_ARFCN)
        cells.append(row)
    session.write_cells_file(os.path.join(directory, contract.CELLS_CSV), cells,
                             session.CELLS_COLUMNS + contract.CELLS_APP_COLUMNS)

    # cellinfo.csv: every cell of every request, once a second, fresh and stale
    info_rows = []
    returned = set()
    requests = 0
    repeats = 0
    screen_on_requests = 0
    for request_ms in range(900, DURATION_MS, 1000):
        # the newest measurement the modem had 200 ms before the answer came back;
        # asking again inside Android's refresh interval returns it again, unchanged
        latest = max(ms for ms in fresh if ms <= request_ms - 200)
        stale = latest in returned
        returned.add(latest)
        requests += 1
        repeats += 1 if stale else 0
        screen_on = not (GAP[0] < request_ms < GAP[1])
        screen_on_requests += 1 if screen_on else 0
        r = radio(latest)
        seen = session.iso(at(request_ms))
        cells_now = [
            (scan.Cell(rat="lte", registered=True, mcc=MCC, mnc=MNC, operator=OPERATOR, pci=r["lte"]["pci"],
                       arfcn=LTE_EARFCN, bands=str(LTE_BAND), tac=TAC, cell_id=ENB << 8 | r["lte"]["sector"],
                       bandwidth_khz=20000, rsrp=r["lte"]["rsrp"], rsrq=r["lte"]["rsrq"], sinr=r["lte"]["rssnr"],
                       rssi=r["lte"]["rssi"], level=r["lte"]["level"], seen_utc=seen),
             {"connection_status": 1, "cqi": r["lte"]["cqi"], "timing_advance": r["lte"]["ta"]}),
        ]
        if r["nr"] is not None:
            cells_now.append((scan.Cell(rat="nr", registered=False, pci=r["nr"]["pci"], arfcn=NR_ARFCN,
                                        bands=str(NR_BAND), rsrp=r["nr"]["rsrp"], rsrq=r["nr"]["rsrq"],
                                        sinr=r["nr"]["sinr"], level=r["nr"]["level"], seen_utc=seen),
                              {"connection_status": 2}))
        cells_now.append((scan.Cell(rat="lte", registered=False, pci=r["neighbour"]["pci"], arfcn=LTE_EARFCN,
                                    rsrp=r["neighbour"]["rsrp"], rsrq=r["neighbour"]["rsrq"],
                                    level=r["neighbour"]["level"], seen_utc=seen),
                          {"connection_status": 0}))
        for cell, extra in cells_now:
            row = scan.csv_row(cell)
            row.update({"time_epoch": epoch_text(latest), "timestamp_ms": BOOT_MS_AT_START + latest,
                        "age_ms": request_ms - latest, "stale": int(stale), "source": "request",
                        "cqi": "", "timing_advance": "", "csi_rsrp": "", "csi_rsrq": "", "csi_sinr": "",
                        "screen_on": int(screen_on), "charging": 0, "wifi_connected": 0, "sub_id": 1})
            row.update(extra)
            info_rows.append(row)
    gps.tag_rows(info_rows, fixes, max_gap_s=contract.GPS_MATCH_SECONDS)
    with open(os.path.join(directory, contract.CELLINFO_CSV), "w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=scan.COLUMNS + contract.CELLINFO_APP_COLUMNS, extrasaction="ignore")
        writer.writeheader()
        for row in info_rows:
            writer.writerow(row)

    # session.json, written last as the app does when a session closes
    lte_samples = sum(1 for r in kpi_rows if r["rat"] == "lte")
    intervals = sorted(b - a for a, b in zip(fresh, fresh[1:]))
    gap_seconds = (GAP[1] - GAP[0]) / 1000.0
    meta = {
        "format": contract.FORMAT,
        "session_id": "3f6c1a2e-8b7d-4e21-9c55-2a1f0b9d7e44",
        "group_id": None,
        "name": NAME,
        "note": "Walk-mode check of the north path, screen on, Wi-Fi off.",
        "location": "National Mall, Washington DC",
        "started_utc": session.iso(START),
        "stopped_utc": session.iso(at(DURATION_MS)),
        "transport": {"transport": contract.APP_TRANSPORT, "app": "5gto6G FieldTap", "app_version": "0.1.0",
                      "version_code": 1},
        "handset": {"manufacturer": "samsung", "model": "SM-S921U", "device": "e1q", "android_version": "15",
                    "android_build": "AP3A.240905.015.A2", "security_patch": "2026-08-01",
                    "baseband": "S921USQU4BXH2", "soc": "SM8650", "platform": "pineapple", "hardware": "qcom",
                    "operator_mccmnc": PLMN, "operator_name": OPERATOR, "sim_mccmnc": PLMN,
                    "sim_operator_name": OPERATOR, "network_type": "LTE"},
        "device": {"key": "app:7d0e5b8c-1f2a-4c3d-9e6f-0a1b2c3d4e5f", "label": "SM-S921U"},
        "modem": {},
        "log_mask": {},
        "files": {"kpi": contract.KPI_CSV, "track": contract.TRACK_CSV, "events": contract.EVENTS_CSV,
                  "traffic": contract.TRAFFIC_CSV, "cells": contract.CELLS_CSV, "cellinfo": contract.CELLINFO_CSV},
        "summary": {"stopped_by": "user", "plmns": {PLMN: lte_samples}},
        "capabilities": {"layer3": False},
        "collection": {
            "median_fresh_interval_ms": intervals[len(intervals) // 2],
            "short_interval_pct": round(100.0 * (DURATION_MS - (GAP[1] - GAP[0])) / DURATION_MS, 1),
            "screen_on_pct": round(100.0 * screen_on_requests / requests, 1),
            "wifi_connected_pct": 0.0,
            "charging_pct": 0.0,
            "fresh_samples": len(fresh),
            "repeats_dropped": repeats,
            "gaps": [{"start_utc": session.iso(at(GAP[0])), "stop_utc": session.iso(at(GAP[1])),
                      "seconds": gap_seconds, "reason": "screen_off"}],
        },
        "privacy": {"data_class": "kpi", "location_precision": "full", "zone_pauses": 0,
                    "consent_version": "2026-09-01",
                    "consent_sha256": hashlib.sha256(b"fieldtap fixture consent text, version 2026-09-01").hexdigest()},
    }
    with open(os.path.join(directory, contract.SESSION_JSON), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(json.dumps(meta, indent=2, ensure_ascii=False) + "\n")

    def average(values):
        return round(sum(values) / len(values), 1)

    return {
        "dir": directory,
        "lte_rsrp_avg": average([float(r["rsrp_dbm"]) for r in kpi_rows if r["rat"] == "lte"]),
        "nr_rsrp_avg": average([float(r["rsrp_dbm"]) for r in kpi_rows if r["rat"] == "nr"]),
        "lte_samples": lte_samples,
        "nr_samples": sum(1 for r in kpi_rows if r["rat"] == "nr"),
        "gps_fixes": len(fixes),
        "events": len(evs),
        "cell_changes": sum(1 for e in evs if e.kind == "serving_cell") - 1,
        "ping_rtt_avg_ms": 44.7,
        "download_mbps_avg": round(float("%.3f" % download_mbps), 2),
        "cells": len(cells),
        "stale_rows": sum(1 for row in info_rows if row["stale"] == 1),
    }


def main(argv: list) -> int:
    root = argv[1] if len(argv) > 1 else os.path.join(HERE, "android_session")
    result = build(root)
    print(result["dir"])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
