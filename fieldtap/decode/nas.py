"""LTE (0xB0Ex) and NR (0xB80x) NAS OTA log records.

Record body:  version  [release fields]  NAS PDU

The NAS PDU is self-describing (protocol discriminator / extended protocol
discriminator in the first octet), so instead of trusting a fixed header
size per version, the decoder looks for the first offset at which a valid
NAS header starts. The log code says which sublayer and direction the modem
claims; the message type inside the PDU is used to confirm that claim, and a
disagreement is counted rather than hidden.
"""

from __future__ import annotations

import struct
from typing import Optional

from ..diag.protocol import LogRecord
from .channels import Channel
from .msgnames import nas_message
from .records import DecodedMessage

EPD_5GMM = 0x7E
EPD_5GSM = 0x2E
PD_EMM = 0x07
PD_ESM = 0x02

_LTE_OFFSETS = (4, 3, 5, 6, 8)
_NR_OFFSETS = (4, 7, 8, 5, 6, 12, 16)

_ESM_TYPES = range(0xC1, 0xEC)
_5GSM_TYPES = range(0xC1, 0xD7)


def looks_like_nas_eps(p: bytes) -> bool:
    if len(p) < 2:
        return False
    pd, sec = p[0] & 0x0F, p[0] >> 4
    if pd == PD_EMM:
        return sec in (0, 1, 2, 3, 4, 12)
    if pd == PD_ESM:
        return len(p) >= 3 and p[2] in _ESM_TYPES
    return False


def looks_like_nas_5gs(p: bytes) -> bool:
    if len(p) < 3:
        return False
    if p[0] == EPD_5GMM:
        return p[1] in (0, 1, 2, 3, 4)
    if p[0] == EPD_5GSM:
        return len(p) >= 4 and p[3] in _5GSM_TYPES
    return False


def _classify_eps(p: bytes):
    """-> (sublayer, security_header, msg_type or None)"""
    pd, sec = p[0] & 0x0F, p[0] >> 4
    if pd == PD_ESM:
        return "esm", 0, p[2] if len(p) > 2 else None
    if sec == 0:
        return "emm", 0, p[1] if len(p) > 1 else None
    if sec == 12:
        return "emm", 12, None          # Service request: short header, no type octet
    inner = p[6:]
    if sec in (1, 3) and len(inner) >= 2 and inner[0] & 0x0F == PD_EMM and inner[0] >> 4 == 0:
        return "emm", sec, inner[1]
    if sec in (1, 3) and len(inner) >= 3 and inner[0] & 0x0F == PD_ESM:
        return "esm", sec, inner[2]
    return "emm", sec, None             # ciphered: type unreadable


def _classify_5gs(p: bytes):
    if p[0] == EPD_5GSM:
        return "5gsm", 0, p[3] if len(p) > 3 else None
    sec = p[1]
    if sec == 0:
        return "5gmm", 0, p[2] if len(p) > 2 else None
    inner = p[7:]
    if sec in (1, 3) and len(inner) >= 3 and inner[0] == EPD_5GMM and inner[1] == 0:
        return "5gmm", sec, inner[2]
    if sec in (1, 3) and len(inner) >= 4 and inner[0] == EPD_5GSM:
        return "5gsm", sec, inner[3]
    return "5gmm", sec, None


def decode(rec: LogRecord, info) -> Optional[DecodedMessage]:
    body = rec.body
    rat = info.rat
    claimed_layer, claimed_dir, claimed_sec = info.nas or (None, "unknown", "plain")
    if rat == "lte":
        version = body[0] if body else 0
        offsets, looks = _LTE_OFFSETS, looks_like_nas_eps
    else:
        version = struct.unpack_from("<I", body, 0)[0] if len(body) >= 4 else 0
        offsets, looks = _NR_OFFSETS, looks_like_nas_5gs
    located = "table"
    offset = None
    for candidate in offsets:
        if looks(body[candidate:]):
            offset = candidate
            located = "table" if candidate == offsets[0] else "probed"
            break
    if offset is None:
        # Scan the first 24 bytes before giving up on the record.
        for candidate in range(1, min(24, len(body))):
            if looks(body[candidate:]):
                offset, located = candidate, "scanned"
                break
    if offset is None:
        return None
    payload = bytes(body[offset:])
    if rat == "lte":
        layer, sec, msg_type = _classify_eps(payload)
        dissector = "nas-eps"
    else:
        layer, sec, msg_type = _classify_5gs(payload)
        dissector = "nas-5gs"
    if rat == "lte" and sec == 12:
        name, direction = "Service request", "ul"
    else:
        name, direction = (None, None) if msg_type is None else nas_message(layer, msg_type)
    fields = {
        "sublayer": layer, "security_header": sec, "msg_type": msg_type,
        "claimed_layer": claimed_layer, "claimed_direction": claimed_dir,
        "nas_offset": offset, "nas_locate": located,
    }
    if direction is None:
        direction = claimed_dir
    elif claimed_dir not in ("unknown", direction):
        fields["direction_conflict"] = True
    label = {"emm": "EMM", "esm": "ESM", "5gmm": "5GMM", "5gsm": "5GSM"}[layer]
    channel = Channel(label, label, direction, dissector, 0 if sec == 0 else 1)
    return DecodedMessage(
        rat=rat, layer="nas", channel=channel, direction=direction, payload=payload,
        timestamp=rec.timestamp, log_code=rec.code, version=version, fields=fields, name=name,
    )
