"""KPI extraction (roadmap 3.3), the Wireshark-native way.

Measurement results are already in the signalling: MeasurementReport and
the measResult IEs carry RSRP/RSRQ/SINR for the serving cell and every
neighbour the UE reported. tshark pulls them out of the decoded pcapng, so
no bit-packed ML1 record parsing is needed for a first KPI timeline. Serving
cell identity comes from the session sidecar (0xB0C2)."""

from __future__ import annotations

import csv
import io
from typing import Optional

from . import tshark as tshark_mod

# Field names as Wireshark 4.0 registers them (tshark -G fields). The NR
# results sit under MeasQuantityResults, hence the prefix; test_wireshark.py
# checks these names against whichever Wireshark is installed.
NR_RSRP = "nr-rrc.measQuantityResults.rsrp"
NR_RSRQ = "nr-rrc.measQuantityResults.rsrq"
NR_SINR = "nr-rrc.measQuantityResults.sinr"
FIELDS = [
    "frame.number", "frame.time_epoch", "frame.comment",
    "lte-rrc.measId", "lte-rrc.physCellId", "lte-rrc.rsrpResult", "lte-rrc.rsrqResult",
    "nr-rrc.measId", "nr-rrc.physCellId", NR_RSRP, NR_RSRQ, NR_SINR,
]
FILTER = "lte-rrc.rsrpResult || lte-rrc.rsrqResult || %s || %s || %s" % (NR_RSRP, NR_RSRQ, NR_SINR)


def lte_rsrp_dbm(value: int) -> float:
    return value - 140.0            # RSRP-Range, TS 36.133 9.1.4


def lte_rsrq_db(value: int) -> float:
    return value / 2.0 - 19.5       # RSRQ-Range, TS 36.133 9.1.7


def nr_rsrp_dbm(value: int) -> float:
    return value - 156.0            # SS-RSRP, TS 38.133 10.1.6.1


def nr_rsrq_db(value: int) -> float:
    return value / 2.0 - 43.0       # SS-RSRQ, TS 38.133 10.1.11.1


def nr_sinr_db(value: int) -> float:
    return value / 2.0 - 23.0       # SS-SINR, TS 38.133 10.1.16.1


def _convert(raw: str, fn) -> str:
    if not raw:
        return ""
    out = []
    for item in raw.split(","):
        try:
            out.append("%.1f" % fn(int(item)))
        except ValueError:
            out.append(item)
    return ",".join(out)


def extract(path: str, tshark: Optional[str] = None) -> list:
    rows = tshark_mod.fields(path, FIELDS, display_filter=FILTER, tshark=tshark)
    out = []
    for r in rows:
        (number, epoch, comment, lte_meas, lte_pci, lte_rsrp, lte_rsrq,
         nr_meas, nr_pci, nr_rsrp, nr_rsrq, nr_sinr) = r[:12]
        rat = "nr" if (nr_rsrp or nr_rsrq or nr_sinr) else "lte"
        out.append({
            "frame": number,
            "time_epoch": epoch,
            "rat": rat,
            "meas_id": nr_meas if rat == "nr" else lte_meas,
            "pci": nr_pci if rat == "nr" else lte_pci,
            "rsrp_dbm": _convert(nr_rsrp, nr_rsrp_dbm) if rat == "nr" else _convert(lte_rsrp, lte_rsrp_dbm),
            "rsrq_db": _convert(nr_rsrq, nr_rsrq_db) if rat == "nr" else _convert(lte_rsrq, lte_rsrq_db),
            "sinr_db": _convert(nr_sinr, nr_sinr_db) if rat == "nr" else "",
            "comment": comment,
        })
    return out


COLUMNS = ["frame", "time_epoch", "rat", "meas_id", "pci", "rsrp_dbm", "rsrq_db", "sinr_db", "comment"]


def to_csv(rows: list) -> str:
    out = io.StringIO()
    writer = csv.DictWriter(out, fieldnames=COLUMNS)
    writer.writeheader()
    for row in rows:
        writer.writerow(row)
    return out.getvalue()
