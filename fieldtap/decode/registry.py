"""The log-code register (roadmap 2.2): code -> what it is -> how it is decoded.

`confidence` is about the record *layout* FieldTap applies, not the log
code's existence:

  high    layout widely documented and exercised in the field by public tools
  medium  layout documented for the common versions; self-validated at runtime
  low     name known, layout not implemented; captured to .qmdl, not decoded

Anything not listed here is still captured when the mask allows it; it is
just counted as unknown by the decoder.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class LogCodeInfo:
    code: int
    name: str
    rat: str            # "lte" | "nr"
    category: str       # "rrc" | "nas" | "cell" | "meas" | "mac" | "other"
    decoder: str = ""   # key into records.DECODERS; "" = captured, not decoded
    confidence: str = "low"
    # NAS only: (sublayer, direction, security)
    nas: Optional[tuple] = None
    note: str = ""


def _c(code, name, rat, category, decoder="", confidence="low", nas=None, note=""):
    return LogCodeInfo(code, name, rat, category, decoder, confidence, nas, note)


LOG_CODES = {i.code: i for i in (
    # --- LTE RRC ---------------------------------------------------------------
    _c(0xB0C0, "LTE RRC OTA Packet", "lte", "rrc", "lte_rrc", "high"),
    _c(0xB0C1, "LTE RRC MIB Message Log Packet", "lte", "cell", "lte_mib", "medium"),
    _c(0xB0C2, "LTE RRC Serving Cell Info Log Packet", "lte", "cell", "lte_serving_cell", "medium"),
    _c(0xB0C3, "LTE RRC PLMN Search Info", "lte", "other"),
    _c(0xB0C4, "LTE RRC PLMN Search Request", "lte", "other"),
    # --- LTE NAS ---------------------------------------------------------------
    _c(0xB0E0, "LTE NAS ESM Security Protected Incoming Msg", "lte", "nas", "nas", "medium", ("esm", "dl", "sec")),
    _c(0xB0E1, "LTE NAS ESM Security Protected Outgoing Msg", "lte", "nas", "nas", "medium", ("esm", "ul", "sec")),
    _c(0xB0E2, "LTE NAS ESM Plain OTA Incoming Msg", "lte", "nas", "nas", "high", ("esm", "dl", "plain")),
    _c(0xB0E3, "LTE NAS ESM Plain OTA Outgoing Msg", "lte", "nas", "nas", "high", ("esm", "ul", "plain")),
    _c(0xB0E4, "LTE NAS ESM Bearer Context State", "lte", "other"),
    _c(0xB0E5, "LTE NAS ESM Bearer Context Info", "lte", "other"),
    _c(0xB0EA, "LTE NAS EMM Security Protected Incoming Msg", "lte", "nas", "nas", "medium", ("emm", "dl", "sec")),
    _c(0xB0EB, "LTE NAS EMM Security Protected Outgoing Msg", "lte", "nas", "nas", "medium", ("emm", "ul", "sec")),
    _c(0xB0EC, "LTE NAS EMM Plain OTA Incoming Msg", "lte", "nas", "nas", "high", ("emm", "dl", "plain")),
    _c(0xB0ED, "LTE NAS EMM Plain OTA Outgoing Msg", "lte", "nas", "nas", "high", ("emm", "ul", "plain")),
    _c(0xB0EE, "LTE NAS EMM State", "lte", "other"),
    _c(0xB0EF, "LTE NAS EMM USIM Card Mode", "lte", "other"),
    # --- LTE MAC / PHY: captured for the corpus, not decoded -------------------------------
    _c(0xB061, "LTE MAC UL Transport Block", "lte", "mac"),
    _c(0xB063, "LTE MAC DL Transport Block", "lte", "mac"),
    _c(0xB16B, "LTE PHY PDCCH-PHICH Indication Report", "lte", "meas"),
    _c(0xB173, "LTE PDSCH Stat Indication", "lte", "meas"),
    _c(0xB179, "LTE ML1 Connected Mode LTE Intra-Freq Meas Results", "lte", "meas",
       note="RSRP/RSRQ per neighbour; layout is version dependent and bit packed"),
    _c(0xB17F, "LTE ML1 Serving Cell Meas and Eval", "lte", "meas"),
    _c(0xB180, "LTE ML1 Idle Neighbor Meas Results", "lte", "meas"),
    _c(0xB193, "LTE ML1 Serving Cell Measurement Result", "lte", "meas",
       note="The classic RSRP/RSRQ/RSSI source; layout is version dependent and bit packed"),
    _c(0xB195, "LTE ML1 Neighbor Measurements", "lte", "meas"),
    # --- NR RRC ---------------------------------------------------------------------
    _c(0xB821, "NR RRC OTA Packet", "nr", "rrc", "nr_rrc", "medium",
       note="Header layout self-validated against the record length field"),
    _c(0xB822, "NR RRC MIB Info", "nr", "cell"),
    _c(0xB823, "NR RRC Serving Cell Info", "nr", "cell"),
    _c(0xB825, "NR RRC Configuration Info", "nr", "other"),
    _c(0xB826, "NR RRC PLMN Search Info", "nr", "other"),
    # --- NR NAS ---------------------------------------------------------------------
    _c(0xB800, "NR NAS SM5G Plain OTA Incoming Msg", "nr", "nas", "nas", "medium", ("5gsm", "dl", "plain")),
    _c(0xB801, "NR NAS SM5G Plain OTA Outgoing Msg", "nr", "nas", "nas", "medium", ("5gsm", "ul", "plain")),
    _c(0xB808, "NR NAS SM5G Security Protected Incoming Msg", "nr", "nas", "nas", "low", ("5gsm", "dl", "sec"),
       note="code/direction pairing not confirmed on hardware"),
    _c(0xB809, "NR NAS SM5G Security Protected Outgoing Msg", "nr", "nas", "nas", "low", ("5gsm", "ul", "sec"),
       note="code/direction pairing not confirmed on hardware"),
    _c(0xB80A, "NR NAS MM5G Plain OTA Incoming Msg", "nr", "nas", "nas", "medium", ("5gmm", "dl", "plain")),
    _c(0xB80B, "NR NAS MM5G Plain OTA Outgoing Msg", "nr", "nas", "nas", "medium", ("5gmm", "ul", "plain")),
    _c(0xB80C, "NR NAS MM5G Security Protected Incoming Msg", "nr", "nas", "nas", "low", ("5gmm", "dl", "sec"),
       note="code/direction pairing not confirmed on hardware"),
    _c(0xB80D, "NR NAS MM5G Security Protected Outgoing Msg", "nr", "nas", "nas", "low", ("5gmm", "ul", "sec"),
       note="seen in the Jul 2024 QCAT export as an MM5G log; direction unconfirmed"),
    _c(0xB80E, "NR NAS MM5G State", "nr", "other"),
    _c(0xB80F, "NR NAS MM5G Service Request", "nr", "other"),
    _c(0xB814, "NR NAS SM5G State", "nr", "other"),
    # --- NR ML1: captured for the corpus, not decoded ----------------------------------------
    _c(0xB975, "NR ML1 Serving Cell Beam Management", "nr", "meas"),
    _c(0xB97F, "NR ML1 Searcher Measurement Database Update Ext", "nr", "meas",
       note="SS-RSRP/RSRQ/SINR per beam; layout is version dependent"),
    _c(0xB887, "NR MAC PDSCH Info", "nr", "mac"),
    _c(0xB88A, "NR MAC UL Physical Channel Schedule Report", "nr", "mac"),
)}


PROFILES = {
    # The product promise: every RRC and NAS message, plus cell identity.
    "signalling": [c for c, i in LOG_CODES.items() if i.category in ("rrc", "nas", "cell")],
    "lte": [c for c, i in LOG_CODES.items() if i.rat == "lte" and i.category in ("rrc", "nas", "cell")],
    "nr": [c for c, i in LOG_CODES.items() if i.rat == "nr" and i.category in ("rrc", "nas", "cell")],
    # Everything in the register, decoded or not: what the regression corpus needs.
    "corpus": sorted(LOG_CODES),
}


def profile_codes(name: str) -> list:
    if name not in PROFILES:
        raise KeyError("unknown log profile %r (choose from %s)" % (name, ", ".join(sorted(PROFILES))))
    return sorted(PROFILES[name])


def describe(code: int) -> str:
    info = LOG_CODES.get(code)
    return info.name if info else "unknown log 0x%04X" % code
