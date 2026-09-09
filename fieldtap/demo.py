"""A synthetic drive test: a realistic session with no handset.

Why it exists. The synthetic corpus in `fixtures.py` proves the decoders;
this proves the *product* — events, KPI charts, the route map and the report
— by generating a capture that looks like eight minutes of driving: an LTE
attach, measurement reports whose RSRP falls and recovers along a route, a
handover, a radio link failure and re-establishment, an NR leg with its own
measurement reports, and a release.

The RRC payloads are real PER encodings, not filler: every MeasurementReport
here decodes in stock Wireshark to the exact values that went in, which is
what makes the KPI numbers in the demo report meaningful. The bit layouts
were calibrated against Wireshark's own ASN.1 (see tests/test_asn1_encode.py,
which fails if a Wireshark upgrade ever changes them).

Nothing here came off a handset. `fieldtap demo` labels every artifact it
writes as simulated so a demo report is never mistaken for a field capture.
"""

from __future__ import annotations

import math
import os
import struct
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Callable, Optional

from . import fixtures
from .diag import hdlc, protocol

# --- a minimal PER bit writer -------------------------------------------------------------

class Bits:
    """Big-endian bit accumulator. Unaligned PER puts the first field in the
    most significant bits of the first octet, which is what `add` does."""

    def __init__(self) -> None:
        self.bits: list = []

    def add(self, value: int, n: int) -> "Bits":
        for i in range(n - 1, -1, -1):
            self.bits.append((value >> i) & 1)
        return self

    def bytes(self) -> bytes:
        bits = list(self.bits)
        while len(bits) % 8:
            bits.append(0)
        out = bytearray()
        for i in range(0, len(bits), 8):
            byte = 0
            for bit in bits[i:i + 8]:
                byte = (byte << 1) | bit
            out.append(byte)
        return bytes(out)


# Field widths that Wireshark's compiled ASN.1 actually uses. Both were
# established by encoding known values and reading them back through tshark
# rather than by reading the specification, because Wireshark is the decoder
# the product is sold on. tests/test_asn1_encode.py re-checks them.
LTE_MEAS_ID_BITS = 6
NR_MEAS_ID_BITS = 6
NR_SERVMO_LEN_BITS = 5


def lte_measurement_report(meas_id: int = 1, rsrp: int = 60, rsrq: int = 20) -> bytes:
    """UL-DCCH-Message / measurementReport / measResults with measResultPCell.

    rsrp is RSRP-Range (0..97, dBm = value - 140); rsrq is RSRQ-Range
    (0..34, dB = value / 2 - 19.5).
    """
    b = Bits()
    b.add(0, 1).add(1, 4)                 # UL-DCCH-MessageType -> c1 -> measurementReport
    b.add(0, 1).add(0, 2)                 # criticalExtensions -> c1 -> measurementReport-r8
    b.add(0, 1)                           # nonCriticalExtension absent
    b.add(0, 1)                           # MeasResults extension marker
    b.add(0, 1)                           # measResultNeighCells absent
    b.add(max(0, meas_id - 1), LTE_MEAS_ID_BITS)
    b.add(max(0, min(97, rsrp)), 7)
    b.add(max(0, min(34, rsrq)), 6)
    return b.bytes()


def nr_measurement_report(meas_id: int = 1, pci: int = 245, rsrp: int = 90, rsrq: int = 60,
                          sinr: int = 70) -> bytes:
    """UL-DCCH-Message / measurementReport with one serving MO carrying
    resultsSSB-Cell rsrp/rsrq/sinr.

    rsrp is SS-RSRP (0..127, dBm = value - 156); rsrq is SS-RSRQ
    (dB = value / 2 - 43); sinr is SS-SINR (dB = value / 2 - 23).
    """
    b = Bits()
    b.add(0, 1).add(0, 4)                 # UL-DCCH-MessageType -> c1 -> measurementReport
    b.add(0, 1)                           # criticalExtensions -> measurementReport
    b.add(0, 1).add(0, 1)                 # lateNonCriticalExtension, nonCriticalExtension absent
    b.add(0, 1)                           # MeasResults extension marker
    b.add(0, 1)                           # measResultNeighCells absent
    b.add(max(0, meas_id - 1), NR_MEAS_ID_BITS)
    b.add(0, NR_SERVMO_LEN_BITS)          # MeasResultServMOList: one entry
    b.add(0, 1).add(0, 1)                 # MeasResultServMO ext, measResultBestNeighCell absent
    b.add(0, 5)                           # servCellId
    b.add(0, 1).add(1, 1)                 # MeasResultNR ext, physCellId present
    b.add(max(0, min(1007, pci)), 10)
    b.add(0, 1).add(1, 1).add(0, 1)       # measResult ext, resultsSSB-Cell present, resultsCSI-RS-Cell absent
    b.add(0b111, 3)                       # MeasQuantityResults: rsrp, rsrq, sinr all present
    b.add(max(0, min(127, rsrp)), 7)
    b.add(max(0, min(127, rsrq)), 7)
    b.add(max(0, min(127, sinr)), 7)
    return b.bytes()


def lte_connection_setup(transaction_id: int = 0) -> bytes:
    """DL-CCCH-Message / rrcConnectionSetup with an empty
    RadioResourceConfigDedicated (every optional field absent)."""
    b = Bits()
    b.add(0, 1).add(3, 2)                 # DL-CCCH-MessageType -> c1 -> rrcConnectionSetup
    b.add(transaction_id & 3, 2)          # rrc-TransactionIdentifier
    b.add(0, 1).add(0, 3)                 # criticalExtensions -> c1 -> rrcConnectionSetup-r8
    b.add(0, 1)                           # nonCriticalExtension absent
    b.add(0, 1)                           # RadioResourceConfigDedicated extension marker
    b.add(0, 6)                           # all six optional fields absent
    return b.bytes()


REESTAB_CAUSES = {"reconfigurationFailure": 0, "handoverFailure": 1, "otherFailure": 2}


def lte_reestablishment_request(pci: int = 101, cause: str = "handoverFailure",
                                c_rnti: int = 0x4A2B, short_mac_i: int = 0x1234) -> bytes:
    """UL-CCCH-Message / rrcConnectionReestablishmentRequest: what a UE sends
    after a radio link failure."""
    b = Bits()
    b.add(0, 1).add(0, 1)                 # UL-CCCH-MessageType -> c1 -> reestablishmentRequest
    b.add(0, 1)                           # criticalExtensions -> r8
    b.add(c_rnti & 0xFFFF, 16)            # ReestabUE-Identity: c-RNTI
    b.add(max(0, min(503, pci)), 9)       # physCellId
    b.add(short_mac_i & 0xFFFF, 16)       # shortMAC-I
    b.add(REESTAB_CAUSES.get(cause, 2), 2)
    b.add(0, 2)                           # spare
    return b.bytes()


# --- unit helpers (TS 36.133 9.1.4 / 9.1.7, TS 38.133 10.1.6.1 / 10.1.11.1 / 10.1.16.1) ------

def lte_rsrp_range(dbm: float) -> int:
    return max(0, min(97, int(round(dbm + 140))))


def lte_rsrq_range(db: float) -> int:
    return max(0, min(34, int(round((db + 19.5) * 2))))


def nr_rsrp_range(dbm: float) -> int:
    return max(0, min(127, int(round(dbm + 156))))


def nr_rsrq_range(db: float) -> int:
    return max(0, min(127, int(round((db + 43) * 2))))


def nr_sinr_range(db: float) -> int:
    return max(0, min(127, int(round((db + 23) * 2))))


# --- the drive ------------------------------------------------------------------------------

@dataclass
class DriveProfile:
    seconds: float = 480.0
    meas_every: float = 5.0
    lte_pci: int = 101
    lte_pci_after_handover: int = 154
    lte_earfcn: int = 1850
    nr_pci: int = 245
    nr_arfcn: int = 636000
    plmn_mcc: int = 310
    plmn_mnc: int = 260
    handover_at: float = 150.0
    rlf_at: float = 255.0
    nr_from: float = 300.0
    release_at: float = 465.0
    start_lat: float = 12.9716
    start_lon: float = 77.5946


def _rsrp_curve(t: float, profile: DriveProfile) -> float:
    """A plausible serving-cell RSRP along a drive: a slow fade away from the
    cell, a dip before the handover, a recovery after it, and a deep notch at
    the radio link failure."""
    base = -78.0 - 22.0 * (1 - math.cos(t / profile.seconds * 2 * math.pi)) / 2
    base -= 12.0 * math.exp(-((t - profile.handover_at) ** 2) / (2 * 25.0 ** 2))
    base -= 26.0 * math.exp(-((t - profile.rlf_at) ** 2) / (2 * 12.0 ** 2))
    if t > profile.handover_at:
        base += 9.0 * min(1.0, (t - profile.handover_at) / 40.0)
    wobble = 2.4 * math.sin(t / 7.0) + 1.3 * math.sin(t / 2.3)
    return max(-125.0, min(-62.0, base + wobble))


def build_records(profile: Optional[DriveProfile] = None, start: Optional[datetime] = None) -> list:
    """-> [(log_code, timestamp_raw, body)] for a whole simulated drive."""
    p = profile or DriveProfile()
    start = start or datetime(2026, 9, 9, 9, 0, 0, tzinfo=timezone.utc)
    records: list = []

    def at(t: float, code: int, body: bytes) -> None:
        records.append((code, protocol.qc_timestamp_from_datetime(start + timedelta(seconds=t)), body))

    def lte_rrc(t: float, pdu_num: int, payload: bytes, pci: int) -> None:
        at(t, 0xB0C0, fixtures.lte_rrc_body(19, pdu_num, payload, earfcn_width=4, sib_mask=0,
                                            pci=pci, earfcn=p.lte_earfcn,
                                            sfn=int(t * 100) % 1024, subfn=int(t * 10) % 10))

    def nr_rrc(t: float, pdu_num: int, payload: bytes) -> None:
        at(t, 0xB821, fixtures.nr_rrc_body(15, pdu_num, payload, sfn_width=4, pci=p.nr_pci,
                                           arfcn=p.nr_arfcn, sfn=int(t * 100) % 1024, subfn=int(t * 10) % 10))

    def lte_nas(t: float, code: int, payload: bytes) -> None:
        at(t, code, fixtures.lte_nas_body(payload))

    def nr_nas(t: float, code: int, payload: bytes) -> None:
        at(t, code, fixtures.nr_nas_body(payload, header=4))

    def serving_cell(t: float, pci: int) -> None:
        at(t, 0xB0C2, fixtures.lte_serving_cell_body(3, pci=pci, dl_earfcn=p.lte_earfcn,
                                                     ul_earfcn=p.lte_earfcn + 18000, cell_id=0x0012345A,
                                                     tac=0x1234, band=3, mcc=p.plmn_mcc, mnc=p.plmn_mnc))

    # PDU-map D numbering (packet version 19): 1 BCCH-BCH, 8 DL-CCCH, 9 DL-DCCH, 10 UL-CCCH, 11 UL-DCCH
    BCCH_BCH, DL_CCCH, DL_DCCH, UL_CCCH, UL_DCCH = 1, 8, 9, 10, 11
    # NR PDU map: 1 BCCH-BCH, 6 UL-CCCH, 8 UL-DCCH, 4 DL-DCCH
    NR_BCCH_BCH, NR_UL_CCCH, NR_UL_DCCH, NR_DL_DCCH = 1, 6, 8, 4

    # --- camp and attach ---------------------------------------------------------------
    serving_cell(0.2, p.lte_pci)
    at(0.4, 0xB0C1, fixtures.lte_mib_body(2, pci=p.lte_pci, earfcn=p.lte_earfcn))
    lte_rrc(0.6, BCCH_BCH, fixtures.LTE_MIB, p.lte_pci)
    lte_rrc(1.0, UL_CCCH, fixtures.LTE_RRC_CONN_REQ, p.lte_pci)
    lte_rrc(1.08, DL_CCCH, lte_connection_setup(0), p.lte_pci)
    lte_nas(1.6, 0xB0EC, fixtures.EMM_IDENTITY_REQUEST)
    lte_nas(1.8, 0xB0ED, fixtures.EMM_IDENTITY_RESPONSE)
    lte_nas(2.4, 0xB0E2, fixtures.ESM_PDN_CONN_REJECT)          # a rejected first try, then success
    lte_nas(3.0, 0xB0E3, fixtures.ESM_PDN_DISCONNECT_REQ)

    # --- the drive: measurement reports the whole way --------------------------------------
    t = 6.0
    meas_id = 1
    while t < p.release_at:
        pci = p.lte_pci if t < p.handover_at else p.lte_pci_after_handover
        rsrp_dbm = _rsrp_curve(t, p)
        rsrq_db = max(-19.0, min(-3.0, -6.0 - (abs(rsrp_dbm) - 80) * 0.18))
        lte_rrc(t, UL_DCCH, lte_measurement_report(meas_id, lte_rsrp_range(rsrp_dbm), lte_rsrq_range(rsrq_db)), pci)
        meas_id = 1 + (meas_id % 8)
        # NR leg runs alongside once EN-DC is up
        if t >= p.nr_from:
            nr_rsrp = rsrp_dbm + 4.5 + 2.0 * math.sin(t / 11.0)
            nr_rrc(t + 0.4, NR_UL_DCCH, nr_measurement_report(meas_id, p.nr_pci, nr_rsrp_range(nr_rsrp),
                                                              nr_rsrq_range(max(-19.0, rsrq_db + 1.5)),
                                                              nr_sinr_range(max(-5.0, min(28.0, 24.0 + rsrp_dbm * 0.22)))))
        t += p.meas_every

    # --- handover: the serving PCI moves while the connection stays up -------------------------
    serving_cell(p.handover_at + 1.0, p.lte_pci_after_handover)
    lte_rrc(p.handover_at + 1.4, UL_DCCH,
            lte_measurement_report(2, lte_rsrp_range(_rsrp_curve(p.handover_at + 1.4, p)), 18),
            p.lte_pci_after_handover)

    # --- radio link failure, then a fresh establishment ---------------------------------------
    lte_rrc(p.rlf_at, UL_CCCH, lte_reestablishment_request(p.lte_pci_after_handover, "handoverFailure"),
            p.lte_pci_after_handover)
    lte_rrc(p.rlf_at + 2.0, UL_CCCH, fixtures.LTE_RRC_CONN_REQ, p.lte_pci_after_handover)
    lte_rrc(p.rlf_at + 2.08, DL_CCCH, lte_connection_setup(1), p.lte_pci_after_handover)
    lte_nas(p.rlf_at + 2.6, 0xB0EA, fixtures.EMM_IDENTITY_REQUEST_INTEGRITY)
    lte_nas(p.rlf_at + 3.2, 0xB0ED, fixtures.EMM_IDENTITY_RESPONSE)

    # --- NR leg: setup, registration, PDU session -----------------------------------------------
    nr_rrc(p.nr_from - 1.0, NR_BCCH_BCH, fixtures.NR_MIB)
    nr_rrc(p.nr_from - 0.6, NR_UL_CCCH, fixtures.NR_RRC_SETUP_REQ)
    nr_nas(p.nr_from + 0.4, 0xB80B, fixtures.MM5G_REGISTRATION_COMPLETE)
    nr_nas(p.nr_from + 1.0, 0xB800, fixtures.SM5G_PDU_SESSION_EST_REJECT)
    nr_nas(p.nr_from + 1.6, 0xB801, fixtures.SM5G_PDU_SESSION_REL_REQ)
    nr_rrc(p.nr_from + 2.2, NR_DL_DCCH, fixtures.NR_RRC_RELEASE)

    # --- release ------------------------------------------------------------------------------
    lte_rrc(p.release_at, DL_DCCH, fixtures.LTE_RRC_CONN_REL, p.lte_pci_after_handover)
    at(p.release_at + 0.5, 0xB193, b"\x01" + bytes(range(40)))   # a listed-but-undecoded record

    records.sort(key=lambda r: r[1])
    return records


def build_qmdl(profile: Optional[DriveProfile] = None, start: Optional[datetime] = None) -> bytes:
    return b"".join(hdlc.encode(protocol.build_log_packet(code, ts, body))
                    for code, ts, body in build_records(profile, start))


def write_qmdl(path: str, profile: Optional[DriveProfile] = None,
               start: Optional[datetime] = None) -> str:
    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(build_qmdl(profile, start))
    return path


def route(profile: Optional[DriveProfile] = None, start: Optional[datetime] = None, points: int = 96):
    """A GPS track matching the drive: a loop whose far side is where the
    signal fades, so the map and the RSRP chart tell the same story."""
    from .gps import Fix
    p = profile or DriveProfile()
    start = start or datetime(2026, 9, 9, 9, 0, 0, tzinfo=timezone.utc)
    fixes = []
    for i in range(points):
        frac = i / max(points - 1, 1)
        t = frac * p.seconds
        angle = frac * 2 * math.pi
        lat = p.start_lat + 0.010 * math.sin(angle)
        lon = p.start_lon + 0.013 * (1 - math.cos(angle)) / 2
        speed = 11.0 + 3.0 * math.sin(angle * 2)
        fixes.append(Fix(start + timedelta(seconds=t), lat, lon, 6.0, 915.0, speed, "simulated", "simulated"))
    return fixes


def traffic_results(profile: Optional[DriveProfile] = None, start: Optional[datetime] = None) -> list:
    """Ping and download results that track the radio conditions: throughput
    collapses where RSRP does."""
    from .traffic import TestResult
    p = profile or DriveProfile()
    start = start or datetime(2026, 9, 9, 9, 0, 0, tzinfo=timezone.utc)
    out = []
    t = 20.0
    while t < p.release_at:
        rsrp = _rsrp_curve(t, p)
        quality = max(0.02, min(1.0, (rsrp + 118.0) / 40.0))
        when = start + timedelta(seconds=t)
        rtt = 22.0 + 90.0 * (1 - quality)
        loss = 0.0 if quality > 0.35 else round(min(80.0, (0.35 - quality) * 220), 1)
        out.append(TestResult(when, "ping", "8.8.8.8", loss < 100,
                              {"sent": 5, "received": int(5 * (1 - loss / 100)), "loss_pct": loss,
                               "rtt_min_ms": round(rtt * 0.8, 1), "rtt_avg_ms": round(rtt, 1),
                               "rtt_max_ms": round(rtt * 1.6, 1)}, "", 5.0))
        mbps = round(max(0.4, 145.0 * quality ** 1.7), 2)
        out.append(TestResult(when + timedelta(seconds=6), "download", "simulated 25 MB", True,
                              {"mbps": mbps, "bytes": 25000000, "seconds": round(200 / max(mbps, 0.4), 1),
                               "http_code": "200"}, "", 8.0))
        t += 60.0
    return out
