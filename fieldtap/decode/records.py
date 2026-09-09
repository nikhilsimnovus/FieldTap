"""Decoded-object types and the dispatching Decoder."""

from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional

from ..diag.protocol import LogRecord
from .channels import Channel
from .registry import LOG_CODES

GSMTAP_TYPE_LTE_RRC = 13
GSMTAP_TYPE_LTE_NAS = 18


@dataclass
class DecodedMessage:
    rat: str                       # "lte" | "nr"
    layer: str                     # "rrc" | "nas"
    channel: Channel
    direction: str                 # "ul" | "dl" | "unknown"
    payload: bytes
    timestamp: Optional[datetime]
    log_code: int
    version: int
    fields: dict = field(default_factory=dict)
    name: Optional[str] = None

    @property
    def dissector(self) -> str:
        return self.channel.dissector

    @property
    def gsmtap(self) -> Optional[tuple]:
        """(type, subtype) when a GSMTAP encoding exists for this message."""
        if self.rat != "lte":
            return None
        if self.layer == "rrc" and self.channel.gsmtap_subtype is not None:
            return GSMTAP_TYPE_LTE_RRC, self.channel.gsmtap_subtype
        if self.layer == "nas":
            return GSMTAP_TYPE_LTE_NAS, self.channel.gsmtap_subtype or 0
        return None

    @property
    def arfcn(self) -> Optional[int]:
        return self.fields.get("earfcn", self.fields.get("arfcn"))

    def summary(self) -> str:
        rat = "NR" if self.rat == "nr" else "LTE"
        head = "%s %s %s" % (rat, self.layer.upper(), self.channel.label)
        if self.name:
            head += " %s" % self.name
        return head

    def comment(self) -> str:
        """Packet comment for pcapng: what the diag wrapper knew about this PDU."""
        parts = ["FieldTap %s" % self.summary(), "log 0x%04X v%d" % (self.log_code, self.version)]
        f = self.fields
        if "pci" in f:
            parts.append("PCI %d" % f["pci"])
        if self.arfcn is not None:
            parts.append("%s %d" % ("NR-ARFCN" if self.rat == "nr" else "EARFCN", self.arfcn))
        if "sfn" in f:
            parts.append("SFN %d.%d" % (f["sfn"], f.get("subfn", 0)))
        if "rb_id" in f:
            parts.append("RB %d" % f["rb_id"])
        if "pdu_num" in f:
            parts.append("PDU %d" % f["pdu_num"])
        if "sib_mask" in f and f["sib_mask"]:
            parts.append("SIB mask 0x%X" % f["sib_mask"])
        if "security_header" in f and f["security_header"]:
            parts.append("sec-hdr %d" % f["security_header"])
        src = f.get("layout_source") or f.get("nas_locate")
        if src and src != "table":
            parts.append("layout %s" % src)
        if f.get("direction_conflict"):
            parts.append("direction from message, log code disagreed")
        if self.channel.dissector == "data":
            parts.append("not decoded: %s" % self.channel.label)
        return " | ".join(parts)


@dataclass
class CellInfo:
    rat: str
    kind: str                      # "serving_cell" | "mib"
    timestamp: Optional[datetime]
    log_code: int
    version: int
    fields: dict = field(default_factory=dict)


class Decoder:
    """Route log records to parsers and keep the statistics the reports need."""

    def __init__(self):
        self.stats: Counter = Counter()
        self.by_code: Counter = Counter()
        self.versions = defaultdict(Counter)     # code -> version -> count
        self.unknown_codes: Counter = Counter()
        self.errors: Counter = Counter()
        self.layout_sources: Counter = Counter()

    def decode(self, rec: LogRecord) -> list:
        self.by_code[rec.code] += 1
        info = LOG_CODES.get(rec.code)
        if info is None:
            self.unknown_codes[rec.code] += 1
            self.stats["unknown"] += 1
            return []
        if not info.decoder:
            self.stats["not_decoded"] += 1
            return []
        parser = decoders().get(info.decoder)
        if parser is None:
            self.stats["not_decoded"] += 1
            return []
        try:
            result = parser(rec, info)
        except Exception as exc:  # a bad record must never stop the capture
            self.errors["%s: %s" % (info.decoder, exc.__class__.__name__)] += 1
            self.stats["errors"] += 1
            return []
        if result is None:
            self.stats["unparsed"] += 1
            self.errors["%s: unparsed 0x%04X" % (info.decoder, rec.code)] += 1
            return []
        self.versions[rec.code][result.version] += 1
        if isinstance(result, DecodedMessage):
            self.stats["messages"] += 1
            self.stats["messages_%s_%s" % (result.rat, result.layer)] += 1
            src = result.fields.get("layout_source") or result.fields.get("nas_locate")
            if src:
                self.layout_sources[src] += 1
            if result.fields.get("direction_conflict"):
                self.stats["direction_conflicts"] += 1
            if result.channel.dissector == "data":
                self.stats["unmapped_channel"] += 1
        else:
            self.stats["cell_info"] += 1
        return [result]

    def report(self) -> dict:
        stats = dict(self.stats)
        stats.setdefault("errors", 0)
        return {
            "stats": stats,
            "by_code": {"0x%04X" % c: n for c, n in sorted(self.by_code.items())},
            "versions": {"0x%04X" % c: dict(v) for c, v in sorted(self.versions.items())},
            "unknown_codes": {"0x%04X" % c: n for c, n in sorted(self.unknown_codes.items())},
            "errors": dict(self.errors),
            "layout_sources": dict(self.layout_sources),
        }


_DECODERS: dict = {}


def decoders() -> dict:
    """Parser table, built on first use so the parser modules can import this one."""
    if not _DECODERS:
        from . import cellinfo, lte_rrc, nas, nr_rrc
        _DECODERS.update({
            "lte_rrc": lte_rrc.decode,
            "nr_rrc": nr_rrc.decode,
            "nas": nas.decode,
            "lte_serving_cell": cellinfo.decode_serving_cell,
            "lte_mib": cellinfo.decode_mib,
        })
    return _DECODERS
