"""Capture sessions: a directory per session with the raw stream, the decoded
pcapng, a cells CSV and a JSON sidecar that records everything the corpus
will need a year from now (roadmap 1.3 / 3.1)."""

from __future__ import annotations

import csv
import json
import os
import re
from collections import Counter
from datetime import datetime, timezone
from typing import Optional

from . import __version__
from .decode.records import CellInfo, DecodedMessage
from .diag.protocol import timestamp_is_plausible

SIDE_CAR = "session.json"
RAW_FILE = "capture.qmdl"
PCAPNG_FILE = "capture.pcapng"
GSMTAP_FILE = "capture_gsmtap.pcap"
CELLS_FILE = "cells.csv"
# cells.csv header, one row per serving cell. schema/columns.json repeats this
# list; the Android app adds its own columns after these (fieldtap.contract).
CELLS_COLUMNS = ["first_seen_utc", "rat", "plmn", "mcc", "mnc", "tac", "cell_id", "enb_id", "sector",
                 "pci", "band", "dl_earfcn", "ul_earfcn", "dl_bw_mhz", "ul_bw_mhz", "version", "plausible"]


def slugify(text: str) -> str:
    text = re.sub(r"[^A-Za-z0-9._-]+", "-", text.strip()).strip("-")
    return text[:48] or "session"


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def iso(when: Optional[datetime]) -> Optional[str]:
    return when.astimezone(timezone.utc).isoformat(timespec="milliseconds") if when else None


class Session:
    def __init__(self, root: str, name: Optional[str] = None, note: Optional[str] = None,
                 location: Optional[str] = None, directory: Optional[str] = None):
        started = utcnow()
        self.name = name or "capture"
        if directory is None:
            directory = os.path.join(root, "%s_%s" % (started.strftime("%Y%m%d-%H%M%S"), slugify(self.name)))
        self.dir = directory
        os.makedirs(self.dir, exist_ok=True)
        self.meta = {
            "fieldtap_version": __version__,
            "name": self.name,
            "note": note,
            "location": location,
            "started_utc": iso(started),
            "stopped_utc": None,
            "transport": {},
            "handset": {},
            "modem": {},
            "log_profile": None,
            "log_mask": {},
            "files": {},
            "summary": {},
        }
        self._cells: list = []
        self._cell_keys: set = set()
        self._pcis = Counter()
        self._arfcns = {"lte": Counter(), "nr": Counter()}
        self._plmns = Counter()
        self._first_ts: Optional[datetime] = None
        self._last_ts: Optional[datetime] = None
        self._messages = Counter()
        self._names = Counter()

    # -- paths --------------------------------------------------------------------

    def path(self, filename: str) -> str:
        return os.path.join(self.dir, filename)

    @property
    def raw_path(self) -> str:
        return self.path(RAW_FILE)

    @property
    def pcapng_path(self) -> str:
        return self.path(PCAPNG_FILE)

    @property
    def gsmtap_path(self) -> str:
        return self.path(GSMTAP_FILE)

    @property
    def sidecar_path(self) -> str:
        return self.path(SIDE_CAR)

    # -- observation ----------------------------------------------------------------

    def observe(self, obj) -> None:
        if isinstance(obj, DecodedMessage):
            self._messages["%s_%s" % (obj.rat, obj.layer)] += 1
            if obj.name:
                self._names["%s %s" % (obj.rat.upper(), obj.name)] += 1
            if "pci" in obj.fields:
                self._pcis["%s:%d" % (obj.rat, obj.fields["pci"])] += 1
            if obj.arfcn is not None:
                self._arfcns[obj.rat][obj.arfcn] += 1
            self._track_time(obj.timestamp)
        elif isinstance(obj, CellInfo):
            f = obj.fields
            if obj.kind == "serving_cell":
                key = (f.get("plmn"), f.get("cell_id"), f.get("pci"), f.get("dl_earfcn"))
                if key not in self._cell_keys:
                    self._cell_keys.add(key)
                    self._cells.append({"first_seen_utc": iso(obj.timestamp), "rat": obj.rat, **f})
                if f.get("plmn"):
                    self._plmns[f["plmn"]] += 1
            self._track_time(obj.timestamp)

    def _track_time(self, when: Optional[datetime]) -> None:
        if not timestamp_is_plausible(when):
            return
        if self._first_ts is None or when < self._first_ts:
            self._first_ts = when
        if self._last_ts is None or when > self._last_ts:
            self._last_ts = when

    # -- finishing ---------------------------------------------------------------------

    def finish(self, transport_info: dict, handset: dict, modem: dict, log_profile: Optional[str],
               log_mask: dict, client_stats: dict, unframer_stats: dict, decoder_report: dict,
               sink_report: dict, files: dict) -> None:
        self.meta["stopped_utc"] = iso(utcnow())
        self.meta["transport"] = transport_info
        self.meta["handset"] = handset
        self.meta["modem"] = modem
        self.meta["log_profile"] = log_profile
        self.meta["log_mask"] = {k: (["0x%04X" % c for c in v] if isinstance(v, list) else v)
                                 for k, v in log_mask.items()}
        self.meta["files"] = {k: os.path.basename(v) for k, v in files.items() if v}
        self.meta["summary"] = {
            "modem_time_first_utc": iso(self._first_ts),
            "modem_time_last_utc": iso(self._last_ts),
            "messages": dict(self._messages),
            "message_names": dict(self._names.most_common(40)),
            "pcis": dict(self._pcis),
            "earfcns": dict(self._arfcns["lte"]),
            "nr_arfcns": dict(self._arfcns["nr"]),
            "plmns": dict(self._plmns),
            "cells": self._cells,
            "client": client_stats,
            "framing": unframer_stats,
            "decoder": decoder_report,
            "sinks": sink_report,
        }
        self.write_sidecar()
        self.write_cells_csv()

    def write_sidecar(self) -> None:
        with open(self.sidecar_path, "w", encoding="utf-8") as fh:
            json.dump(self.meta, fh, indent=2, sort_keys=False)
            fh.write("\n")

    def write_cells_csv(self) -> None:
        if not self._cells:
            return
        write_cells_file(self.path(CELLS_FILE), self._cells)


def write_cells_file(path: str, cells: list, columns: Optional[list] = None) -> None:
    """Write cell dicts as cells.csv. Keys outside `columns` are ignored and
    missing keys are written blank."""
    with open(path, "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=columns or CELLS_COLUMNS, extrasaction="ignore")
        writer.writeheader()
        for cell in cells:
            writer.writerow(cell)


def _object(value) -> dict:
    return value if isinstance(value, dict) else {}


def list_sessions(root: str) -> list:
    """Sidecar summaries for every session directory under root, newest first."""
    sessions = []
    if not os.path.isdir(root):
        return sessions
    for entry in sorted(os.listdir(root), reverse=True):
        sidecar = os.path.join(root, entry, SIDE_CAR)
        if not os.path.isfile(sidecar):
            continue
        try:
            with open(sidecar, encoding="utf-8") as fh:
                meta = json.load(fh)
        except (OSError, ValueError, RecursionError):
            continue
        if not isinstance(meta, dict):
            continue
        # One damaged sidecar must not break the list of every other session.
        summary = _object(meta.get("summary"))
        messages = _object(summary.get("messages"))
        sessions.append({
            "dir": os.path.join(root, entry),
            "name": meta.get("name"),
            "started_utc": meta.get("started_utc"),
            "stopped_utc": meta.get("stopped_utc"),
            "handset": _object(meta.get("handset")).get("model") or _object(meta.get("modem")).get("build_id") or "",
            "messages": sum(v for v in messages.values() if isinstance(v, int) and not isinstance(v, bool)),
            "plmns": ",".join(str(key) for key in _object(summary.get("plmns"))),
            "note": meta.get("note") or "",
        })
    return sessions
