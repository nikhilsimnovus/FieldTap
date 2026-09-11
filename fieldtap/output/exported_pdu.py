"""Wireshark "exported PDU" encapsulation (LINKTYPE_WIRESHARK_UPPER_PDU = 252).

A frame is a list of TLV options followed by the PDU. The option that
matters is tag 12, the dissector name: it makes stock Wireshark hand the
bytes to "nr-rrc.dl.dcch", "nas-5gs" and friends with no plugin, no
heuristics and no port preferences. Tag 35 records uplink/downlink.
"""

from __future__ import annotations

import struct
from typing import Optional

LINKTYPE_WIRESHARK_UPPER_PDU = 252

TAG_END_OF_OPT = 0
TAG_PROTO_NAME = 12
TAG_COL_PROT_TEXT = 33
TAG_P2P_DIRECTION = 35

P2P_DIR_SENT = 0
P2P_DIR_RECV = 1


def _tlv(tag: int, value: bytes) -> bytes:
    padded = value + b"\x00" * (-len(value) % 4)
    return struct.pack(">HH", tag, len(padded)) + padded


def build(dissector: str, payload: bytes, direction: Optional[str] = None,
          col_proto: Optional[str] = None) -> bytes:
    """direction: "ul" (sent by the UE), "dl" (received by the UE) or None."""
    out = bytearray(_tlv(TAG_PROTO_NAME, dissector.encode("ascii")))
    if direction in ("ul", "dl"):
        out += _tlv(TAG_P2P_DIRECTION, struct.pack(">I", P2P_DIR_SENT if direction == "ul" else P2P_DIR_RECV))
    if col_proto:
        out += _tlv(TAG_COL_PROT_TEXT, col_proto.encode("ascii", "replace"))
    out += struct.pack(">HH", TAG_END_OF_OPT, 0)
    out += payload
    return bytes(out)


def parse(frame: bytes) -> tuple:
    """-> (options dict, payload). Used by tests and by the corpus tooling."""
    options = {}
    offset = 0
    while offset + 4 <= len(frame):
        tag, length = struct.unpack_from(">HH", frame, offset)
        offset += 4
        if tag == TAG_END_OF_OPT:
            break
        value = frame[offset:offset + length]
        offset += length
        if tag == TAG_PROTO_NAME:
            options["dissector"] = value.rstrip(b"\x00").decode("ascii", "replace")
        elif tag == TAG_P2P_DIRECTION:
            options["direction"] = "ul" if struct.unpack(">I", value[:4])[0] == P2P_DIR_SENT else "dl"
        elif tag == TAG_COL_PROT_TEXT:
            options["col_proto"] = value.rstrip(b"\x00").decode("ascii", "replace")
        else:
            options[tag] = value
    return options, frame[offset:]
