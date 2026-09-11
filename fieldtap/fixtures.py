"""Synthetic diag captures for the test-suite and `fieldtap selftest`.

What they prove: framing, log dispatch, every header layout in the version
tables, the NAS locator, direction checking, and that the emitted files
decode in stock Wireshark. What they cannot prove: that the layout tables
match real modems. Only captures from hardware do that (docs/CORPUS.md).

The record builders here pack headers explicitly and independently of the
decoder's tables, so an accidental edit to one side shows up as a failure.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Optional

from .diag import hdlc, protocol

# Payloads verified against Wireshark 4.0.1 (see tests/test_wireshark.py)
LTE_MIB = bytes.fromhex("A80000")                       # MasterInformationBlock, n100
LTE_RRC_CONN_REQ = bytes.fromhex("512345678908")        # RRCConnectionRequest, randomValue, mo-Data
LTE_RRC_CONN_REL = bytes.fromhex("2802")                # RRCConnectionRelease, cause other
NR_MIB = bytes.fromhex("033284")                        # MIB
NR_RRC_SETUP_REQ = bytes.fromhex("12468ACF1204")        # RRCSetupRequest, randomValue, mo-Data
NR_RRC_RELEASE = bytes.fromhex("1000")                  # RRCRelease
NR_RRC_RECONF_CONTAINER = bytes.fromhex("0000")         # RRCReconfiguration (empty)
NR_RRC_RECONF_COMPLETE = bytes.fromhex("0800")          # RRCReconfigurationComplete, tid 0
EMM_IDENTITY_REQUEST = bytes.fromhex("075501")
EMM_IDENTITY_RESPONSE = bytes.fromhex("0756080910101032547698")   # IMSI 001010123456789
EMM_IDENTITY_REQUEST_INTEGRITY = bytes.fromhex("17AABBCCDD05075501")
ESM_PDN_CONN_REJECT = bytes.fromhex("0201D11B")
ESM_PDN_DISCONNECT_REQ = bytes.fromhex("0205D205")
MM5G_IDENTITY_REQUEST = bytes.fromhex("7E005B01")
MM5G_REGISTRATION_COMPLETE = bytes.fromhex("7E0043")
MM5G_IDENTITY_REQUEST_INTEGRITY = bytes.fromhex("7E01AABBCCDD057E005B01")
SM5G_PDU_SESSION_EST_REJECT = bytes.fromhex("2E0101C31A")
SM5G_PDU_SESSION_REL_REQ = bytes.fromhex("2E0105D1")


@dataclass
class Expected:
    log_code: int
    version: int
    rat: str
    layer: str
    channel: str            # channel key or NAS sublayer label
    direction: str
    dissector: str
    payload: bytes
    name: Optional[str] = None
    layout_source: str = "table"
    wireshark_info: Optional[str] = None   # substring expected in Wireshark's Info column
    conflict: bool = False


# --- record body builders (independent of fieldtap.decode) ----------------------------

def lte_rrc_body(version: int, pdu_num: int, payload: bytes, earfcn_width: int = 2,
                 sib_mask: Optional[int] = None, pci: int = 101, earfcn: int = 1850,
                 sfn: int = 512, subfn: int = 3, rb_id: int = 0) -> bytes:
    hdr = struct.pack("<BBB", version, 15, 3) + struct.pack("<BH", rb_id, pci)
    hdr += struct.pack("<H" if earfcn_width == 2 else "<I", earfcn)
    hdr += struct.pack("<HB", (sfn << 4) | subfn, pdu_num)
    if sib_mask is not None:
        hdr += struct.pack("<I", sib_mask)
    hdr += struct.pack("<H", len(payload))
    return hdr + payload


def nr_rrc_body(version: int, pdu_num: int, payload: bytes, sfn_width: int = 2,
                pci: int = 245, arfcn: int = 636000, sfn: int = 512, subfn: int = 3,
                rb_id: int = 1, sib_mask: int = 0) -> bytes:
    hdr = struct.pack("<IBBBHI", version, 15, 5, rb_id, pci, arfcn)
    hdr += struct.pack("<H" if sfn_width == 2 else "<I", (sfn << 4) | subfn)
    hdr += struct.pack("<BIH", pdu_num, sib_mask, len(payload))
    return hdr + payload


def lte_nas_body(payload: bytes, version: int = 1) -> bytes:
    return struct.pack("<BBBB", version, 15, 3, 0) + payload


def nr_nas_body(payload: bytes, version: int = 1, header: int = 7) -> bytes:
    if header == 4:
        return struct.pack("<I", version) + payload
    return struct.pack("<IBBB", version, 15, 5, 0) + payload


def lte_serving_cell_body(version: int = 3, pci: int = 101, dl_earfcn: int = 1850, ul_earfcn: int = 19850,
                          bw: int = 5, cell_id: int = 0x0012345A, tac: int = 0x1234, band: int = 3,
                          mcc: int = 310, mnc_digits: int = 3, mnc: int = 260) -> bytes:
    fmt = "<BHHHBBIHIHBHB" if version == 2 else "<BHIIBBIHIHBHB"
    return struct.pack(fmt, version, pci, dl_earfcn, ul_earfcn, bw, bw, cell_id, tac, band, mcc, mnc_digits, mnc, 0)


def lte_mib_body(version: int = 2, pci: int = 101, earfcn: int = 1850, sfn: int = 512) -> bytes:
    return struct.pack("<BHIHBB" if version == 2 else "<BHHHBB", version, pci, earfcn, sfn, 2, 5)


# --- the corpus --------------------------------------------------------------------------

BASE_TIME = datetime(2025, 11, 19, 12, 0, 0, tzinfo=timezone.utc)


def build_corpus():
    """-> (records, expected). records = [(code, ts_raw, body)], expected aligned to
    the records that produce a DecodedMessage (cell-info records have no entry)."""
    records = []
    expected = []
    t = [BASE_TIME]

    def ts():
        t[0] += timedelta(milliseconds=20)
        return protocol.qc_timestamp_from_datetime(t[0])

    def add(code, body, exp=None):
        records.append((code, ts(), body))
        if exp is not None:
            expected.append(exp)

    # LTE RRC, one record per header layout / pdu map era
    add(0xB0C0, lte_rrc_body(2, 1, LTE_MIB),
        Expected(0xB0C0, 2, "lte", "rrc", "BCCH_BCH", "dl", "lte-rrc.bcch.bch", LTE_MIB,
                 "masterInformationBlock", wireshark_info="MasterInformationBlock"))
    add(0xB0C0, lte_rrc_body(9, 14, LTE_RRC_CONN_REQ, earfcn_width=4),
        Expected(0xB0C0, 9, "lte", "rrc", "UL_CCCH", "ul", "lte-rrc.ul.ccch", LTE_RRC_CONN_REQ,
                 "rrcConnectionRequest", wireshark_info="RRCConnectionRequest"))
    add(0xB0C0, lte_rrc_body(14, 7, LTE_RRC_CONN_REL, earfcn_width=4, sib_mask=0),
        Expected(0xB0C0, 14, "lte", "rrc", "DL_DCCH", "dl", "lte-rrc.dl.dcch", LTE_RRC_CONN_REL,
                 "rrcConnectionRelease", wireshark_info="RRCConnectionRelease"))
    add(0xB0C0, lte_rrc_body(19, 9, LTE_RRC_CONN_REL, earfcn_width=4, sib_mask=0),
        Expected(0xB0C0, 19, "lte", "rrc", "DL_DCCH", "dl", "lte-rrc.dl.dcch", LTE_RRC_CONN_REL,
                 "rrcConnectionRelease", wireshark_info="RRCConnectionRelease"))
    add(0xB0C0, lte_rrc_body(26, 1, LTE_MIB, earfcn_width=4, sib_mask=0),
        Expected(0xB0C0, 26, "lte", "rrc", "BCCH_BCH", "dl", "lte-rrc.bcch.bch", LTE_MIB,
                 "masterInformationBlock", wireshark_info="MasterInformationBlock"))
    # Unknown LTE version: the length check must find layout C and the D-era map
    add(0xB0C0, lte_rrc_body(30, 10, LTE_RRC_CONN_REQ, earfcn_width=4, sib_mask=0),
        Expected(0xB0C0, 30, "lte", "rrc", "UL_CCCH", "ul", "lte-rrc.ul.ccch", LTE_RRC_CONN_REQ,
                 "rrcConnectionRequest", layout_source="probed", wireshark_info="RRCConnectionRequest"))

    # NR RRC
    add(0xB821, nr_rrc_body(9, 1, NR_MIB),
        Expected(0xB821, 9, "nr", "rrc", "BCCH_BCH", "dl", "nr-rrc.bcch.bch", NR_MIB, "mib",
                 wireshark_info="MIB"))
    add(0xB821, nr_rrc_body(9, 6, NR_RRC_SETUP_REQ),
        Expected(0xB821, 9, "nr", "rrc", "UL_CCCH", "ul", "nr-rrc.ul.ccch", NR_RRC_SETUP_REQ,
                 "rrcSetupRequest", wireshark_info="RRC Setup Request"))
    add(0xB821, nr_rrc_body(15, 4, NR_RRC_RELEASE, sfn_width=4),
        Expected(0xB821, 15, "nr", "rrc", "DL_DCCH", "dl", "nr-rrc.dl.dcch", NR_RRC_RELEASE,
                 "rrcRelease", wireshark_info="RRC Release"))
    add(0xB821, nr_rrc_body(15, 9, NR_RRC_RECONF_CONTAINER, sfn_width=4),
        Expected(0xB821, 15, "nr", "rrc", "RRC_RECONFIGURATION", "dl", "nr-rrc.rrc_reconf",
                 NR_RRC_RECONF_CONTAINER, "rrcReconfiguration", wireshark_info="RRC Reconfiguration"))
    # Unknown NR version with the wide frame field: must be probed, not misread
    add(0xB821, nr_rrc_body(33, 8, NR_RRC_RECONF_COMPLETE, sfn_width=4),
        Expected(0xB821, 33, "nr", "rrc", "UL_DCCH", "ul", "nr-rrc.ul.dcch", NR_RRC_RECONF_COMPLETE,
                 "rrcReconfigurationComplete", layout_source="probed",
                 wireshark_info="RRC Reconfiguration Complete"))

    # LTE NAS
    add(0xB0EC, lte_nas_body(EMM_IDENTITY_REQUEST),
        Expected(0xB0EC, 1, "lte", "nas", "EMM", "dl", "nas-eps", EMM_IDENTITY_REQUEST,
                 "Identity request", wireshark_info="Identity request"))
    add(0xB0ED, lte_nas_body(EMM_IDENTITY_RESPONSE),
        Expected(0xB0ED, 1, "lte", "nas", "EMM", "ul", "nas-eps", EMM_IDENTITY_RESPONSE,
                 "Identity response", wireshark_info="Identity response"))
    add(0xB0EA, lte_nas_body(EMM_IDENTITY_REQUEST_INTEGRITY),
        Expected(0xB0EA, 1, "lte", "nas", "EMM", "dl", "nas-eps", EMM_IDENTITY_REQUEST_INTEGRITY,
                 "Identity request", wireshark_info="Identity request"))
    add(0xB0E2, lte_nas_body(ESM_PDN_CONN_REJECT),
        Expected(0xB0E2, 1, "lte", "nas", "ESM", "dl", "nas-eps", ESM_PDN_CONN_REJECT,
                 "PDN connectivity reject", wireshark_info="PDN connectivity reject"))
    add(0xB0E3, lte_nas_body(ESM_PDN_DISCONNECT_REQ),
        Expected(0xB0E3, 1, "lte", "nas", "ESM", "ul", "nas-eps", ESM_PDN_DISCONNECT_REQ,
                 "PDN disconnect request", wireshark_info="PDN disconnect request"))
    # Direction conflict: logged as incoming, but the message is an uplink one
    add(0xB0EC, lte_nas_body(EMM_IDENTITY_RESPONSE),
        Expected(0xB0EC, 1, "lte", "nas", "EMM", "ul", "nas-eps", EMM_IDENTITY_RESPONSE,
                 "Identity response", conflict=True, wireshark_info="Identity response"))

    # NR NAS, with both header sizes so the locator is exercised
    add(0xB80A, nr_nas_body(MM5G_IDENTITY_REQUEST, header=7),
        Expected(0xB80A, 1, "nr", "nas", "5GMM", "dl", "nas-5gs", MM5G_IDENTITY_REQUEST,
                 "Identity request", layout_source="probed", wireshark_info="Identity request"))
    add(0xB80B, nr_nas_body(MM5G_REGISTRATION_COMPLETE, header=4),
        Expected(0xB80B, 1, "nr", "nas", "5GMM", "ul", "nas-5gs", MM5G_REGISTRATION_COMPLETE,
                 "Registration complete", wireshark_info="Registration complete"))
    add(0xB80C, nr_nas_body(MM5G_IDENTITY_REQUEST_INTEGRITY, header=7),
        Expected(0xB80C, 1, "nr", "nas", "5GMM", "dl", "nas-5gs", MM5G_IDENTITY_REQUEST_INTEGRITY,
                 "Identity request", layout_source="probed", wireshark_info="Identity request"))
    add(0xB800, nr_nas_body(SM5G_PDU_SESSION_EST_REJECT, header=7),
        Expected(0xB800, 1, "nr", "nas", "5GSM", "dl", "nas-5gs", SM5G_PDU_SESSION_EST_REJECT,
                 "PDU session establishment reject", layout_source="probed",
                 wireshark_info="PDU session establishment reject"))
    add(0xB801, nr_nas_body(SM5G_PDU_SESSION_REL_REQ, header=7),
        Expected(0xB801, 1, "nr", "nas", "5GSM", "ul", "nas-5gs", SM5G_PDU_SESSION_REL_REQ,
                 "PDU session release request", layout_source="probed",
                 wireshark_info="PDU session release request"))

    # Cell identity (no DecodedMessage, feeds the sidecar)
    add(0xB0C2, lte_serving_cell_body(3))
    add(0xB0C1, lte_mib_body(2))
    # Listed but not decoded, and completely unknown
    add(0xB193, b"\x01" + bytes(range(40)))
    add(0xB0FF, b"\x00\x01\x02")
    return records, expected


def build_qmdl(records, with_noise: bool = True) -> bytes:
    """HDLC-framed DIAG_LOG_F stream, optionally with a corrupt frame and a stray
    non-log response mixed in, the way a real port behaves."""
    out = bytearray()
    if with_noise:
        out += b"\x7e"   # leading flag some stacks emit
    for i, (code, ts, body) in enumerate(records):
        frame = hdlc.encode(protocol.build_log_packet(code, ts, body))
        if with_noise and i == 2:
            bad = bytearray(frame)
            bad[3] ^= 0xFF
            out += bytes(bad)
        if with_noise and i == 5:
            out += hdlc.encode(bytes([protocol.DIAG_EXT_BUILD_ID_F, 0, 0, 0]) + struct.pack("<II", 1, 2)
                               + b"MPSS.HI.TEST\x00SYNTH\x00")
        out += frame
    return bytes(out)


def build_dlf(records) -> bytes:
    return b"".join(protocol.build_log_entry(code, ts, body) for code, ts, body in records)


def write_fixture_files(directory: str) -> dict:
    import os
    os.makedirs(directory, exist_ok=True)
    records, _ = build_corpus()
    paths = {
        "qmdl": os.path.join(directory, "synthetic_lte_nr.qmdl"),
        "dlf": os.path.join(directory, "synthetic_lte_nr.dlf"),
    }
    with open(paths["qmdl"], "wb") as fh:
        fh.write(build_qmdl(records))
    with open(paths["dlf"], "wb") as fh:
        fh.write(build_dlf(records))
    return paths
