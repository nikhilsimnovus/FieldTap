"""Minimal pcapng writer: one section, any number of interfaces (each with
its own link type), enhanced packet blocks with microsecond timestamps and
an optional per-packet comment. Little-endian, as Wireshark itself writes."""

from __future__ import annotations

import struct
from datetime import datetime, timezone
from typing import Optional

BLOCK_SHB = 0x0A0D0D0A
BLOCK_IDB = 0x00000001
BLOCK_EPB = 0x00000006

OPT_END = 0
OPT_COMMENT = 1
OPT_SHB_HARDWARE = 2
OPT_SHB_OS = 3
OPT_SHB_USERAPPL = 4
OPT_IF_NAME = 2
OPT_IF_DESCRIPTION = 3
OPT_IF_TSRESOL = 9


def _opt(code: int, value: bytes) -> bytes:
    return struct.pack("<HH", code, len(value)) + value + b"\x00" * (-len(value) % 4)


def _block(block_type: int, body: bytes) -> bytes:
    total = 12 + len(body)
    return struct.pack("<II", block_type, total) + body + struct.pack("<I", total)


def epoch_micros(when: Optional[datetime]) -> int:
    if when is None:
        when = datetime.now(timezone.utc)
    elif when.tzinfo is None:
        when = when.replace(tzinfo=timezone.utc)
    return int(when.timestamp() * 1_000_000)


class PcapngWriter:
    def __init__(self, fh, application: str = "FieldTap", comment: Optional[str] = None,
                 hardware: Optional[str] = None):
        self.fh = fh
        self.packets = 0
        self._interfaces = 0
        options = _opt(OPT_SHB_USERAPPL, application.encode("utf-8"))
        if hardware:
            options += _opt(OPT_SHB_HARDWARE, hardware.encode("utf-8"))
        if comment:
            options += _opt(OPT_COMMENT, comment.encode("utf-8"))
        options += struct.pack("<HH", OPT_END, 0)
        body = struct.pack("<IHHq", 0x1A2B3C4D, 1, 0, -1) + options
        self.fh.write(_block(BLOCK_SHB, body))

    def add_interface(self, linktype: int, name: str, description: Optional[str] = None,
                      snaplen: int = 0) -> int:
        options = _opt(OPT_IF_NAME, name.encode("utf-8"))
        if description:
            options += _opt(OPT_IF_DESCRIPTION, description.encode("utf-8"))
        options += _opt(OPT_IF_TSRESOL, b"\x06")
        options += struct.pack("<HH", OPT_END, 0)
        body = struct.pack("<HHI", linktype, 0, snaplen) + options
        self.fh.write(_block(BLOCK_IDB, body))
        if_id = self._interfaces
        self._interfaces += 1
        return if_id

    def write_packet(self, if_id: int, data: bytes, when: Optional[datetime] = None,
                     micros: Optional[int] = None, comment: Optional[str] = None) -> None:
        ts = micros if micros is not None else epoch_micros(when)
        body = struct.pack("<IIIII", if_id, (ts >> 32) & 0xFFFFFFFF, ts & 0xFFFFFFFF, len(data), len(data))
        body += data + b"\x00" * (-len(data) % 4)
        if comment:
            body += _opt(OPT_COMMENT, comment.encode("utf-8")) + struct.pack("<HH", OPT_END, 0)
        self.fh.write(_block(BLOCK_EPB, body))
        self.packets += 1

    def flush(self) -> None:
        self.fh.flush()

    def close(self) -> None:
        try:
            self.fh.flush()
        finally:
            self.fh.close()


class Packet:
    __slots__ = ("if_id", "linktype", "micros", "data", "comment")

    def __init__(self, if_id, linktype, micros, data, comment):
        self.if_id, self.linktype, self.micros, self.data, self.comment = if_id, linktype, micros, data, comment

    @property
    def when(self) -> datetime:
        return datetime.fromtimestamp(self.micros / 1_000_000, tz=timezone.utc)


def _parse_options(blob: bytes) -> dict:
    options = {}
    offset = 0
    while offset + 4 <= len(blob):
        code, length = struct.unpack_from("<HH", blob, offset)
        offset += 4
        if code == OPT_END:
            break
        options.setdefault(code, []).append(blob[offset:offset + length])
        offset += length + (-length % 4)
    return options


def read_packets(path: str):
    """Iterate packets from a pcapng written by FieldTap (or any single-section
    little-endian pcapng). Enough for the flow view and the corpus tools."""
    with open(path, "rb") as fh:
        data = fh.read()
    offset = 0
    linktypes = []
    tsresol = []
    while offset + 12 <= len(data):
        block_type, total = struct.unpack_from("<II", data, offset)
        if total < 12 or offset + total > len(data):
            break
        body = data[offset + 8: offset + total - 4]
        if block_type == BLOCK_SHB:
            magic = struct.unpack_from("<I", body, 0)[0]
            if magic != 0x1A2B3C4D:
                raise ValueError("big-endian pcapng is not supported")
            linktypes, tsresol = [], []
        elif block_type == BLOCK_IDB:
            linktype = struct.unpack_from("<H", body, 0)[0]
            options = _parse_options(body[8:])
            res = options.get(OPT_IF_TSRESOL, [b"\x06"])[0][0]
            linktypes.append(linktype)
            tsresol.append(10 ** res if res < 128 else 2 ** (res & 0x7F))
        elif block_type == BLOCK_EPB:
            if_id, ts_high, ts_low, captured, _orig = struct.unpack_from("<IIIII", body, 0)
            packet = body[20:20 + captured]
            options = _parse_options(body[20 + captured + (-captured % 4):])
            comment = options.get(OPT_COMMENT, [b""])[0].decode("utf-8", "replace") or None
            ticks = (ts_high << 32) | ts_low
            divisor = tsresol[if_id] if if_id < len(tsresol) else 1_000_000
            micros = ticks * 1_000_000 // divisor
            yield Packet(if_id, linktypes[if_id] if if_id < len(linktypes) else None, micros, bytes(packet), comment)
        offset += total
