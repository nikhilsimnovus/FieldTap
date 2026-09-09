"""Serving-cell identity records. These are not OTA messages; they feed the
session sidecar and the KPI export."""

from __future__ import annotations

import struct
from typing import Optional

from ..diag.protocol import LogRecord
from .records import CellInfo

LTE_BANDWIDTHS = {0: 1.4, 1: 3.0, 2: 5.0, 3: 10.0, 4: 15.0, 5: 20.0}


def _plausible_lte(f: dict) -> bool:
    return 0 <= f["pci"] <= 503 and 1 <= f["band"] <= 256 and (100 <= f["mcc"] <= 999 or f["mcc"] == 0)


def decode_serving_cell(rec: LogRecord, info=None) -> Optional[CellInfo]:
    """0xB0C2 LTE RRC Serving Cell Info."""
    body = rec.body
    if not body:
        return None
    version = body[0]
    names = ("pci", "dl_earfcn", "ul_earfcn", "dl_bw", "ul_bw", "cell_id", "tac",
             "band", "mcc", "mnc_digits", "mnc", "allowed_access")
    fmt = {2: "<HHHBBIHIHBHB", 3: "<HIIBBIHIHBHB"}.get(version)
    if fmt is None:
        # Unknown version: the 32-bit EARFCN layout is what every modern modem uses.
        fmt = "<HIIBBIHIHBHB"
    if len(body) < 1 + struct.calcsize(fmt):
        return None
    f = dict(zip(names, struct.unpack_from(fmt, body, 1)))
    f["version"] = version
    f["dl_bw_mhz"] = LTE_BANDWIDTHS.get(f["dl_bw"])
    f["ul_bw_mhz"] = LTE_BANDWIDTHS.get(f["ul_bw"])
    f["plmn"] = "%03d%0*d" % (f["mcc"], 3 if f["mnc_digits"] == 3 else 2, f["mnc"])
    f["enb_id"] = f["cell_id"] >> 8
    f["sector"] = f["cell_id"] & 0xFF
    f["plausible"] = _plausible_lte(f)
    return CellInfo(rat="lte", kind="serving_cell", timestamp=rec.timestamp, log_code=rec.code,
                    version=version, fields=f)


def decode_mib(rec: LogRecord, info=None) -> Optional[CellInfo]:
    """0xB0C1 LTE RRC MIB Message Log Packet."""
    body = rec.body
    if not body:
        return None
    version = body[0]
    names = ("pci", "earfcn", "sfn", "num_tx_antennas", "dl_bw")
    fmt = {1: "<HHHBB", 2: "<HIHBB"}.get(version, "<HIHBB")
    if len(body) < 1 + struct.calcsize(fmt):
        return None
    f = dict(zip(names, struct.unpack_from(fmt, body, 1)))
    f["version"] = version
    f["dl_bw_mhz"] = LTE_BANDWIDTHS.get(f["dl_bw"])
    f["plausible"] = 0 <= f["pci"] <= 503 and f["sfn"] < 1024
    return CellInfo(rat="lte", kind="mib", timestamp=rec.timestamp, log_code=rec.code,
                    version=version, fields=f)
