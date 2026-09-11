"""Shared machinery for version-dependent record headers.

A header layout is a struct format plus field names. Every RRC OTA layout
ends in a 16-bit message length, and that length must equal the number of
bytes that follow the header. That single invariant turns an unknown packet
version from a silent misparse into a detectable one: try the layout the
version table says, verify it, and if it does not fit try the alternatives
before giving up.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass
from typing import Optional, Sequence, Tuple


@dataclass(frozen=True)
class Layout:
    name: str
    fmt: str
    fields: Tuple[str, ...]

    @property
    def size(self) -> int:
        return struct.calcsize(self.fmt)

    def unpack(self, data: bytes, offset: int) -> Optional[dict]:
        if len(data) < offset + self.size:
            return None
        values = struct.unpack_from(self.fmt, data, offset)
        return dict(zip(self.fields, values))


@dataclass
class HeaderMatch:
    layout: Layout
    fields: dict
    header_end: int
    source: str          # "table" | "probed" | "forced"


def resolve_header(data: bytes, offset: int, preferred: Optional[Layout],
                   candidates: Sequence[Layout]) -> Optional[HeaderMatch]:
    """Pick the layout whose length field matches the bytes that follow it."""
    order = []
    if preferred is not None:
        order.append(("table", preferred))
    order += [("probed", c) for c in candidates if c is not preferred]
    first_fit: Optional[HeaderMatch] = None
    for source, layout in order:
        fields = layout.unpack(data, offset)
        if fields is None:
            continue
        end = offset + layout.size
        if fields.get("length") == len(data) - end:
            return HeaderMatch(layout, fields, end, source)
        if first_fit is None:
            first_fit = HeaderMatch(layout, fields, end, "forced")
    return first_fit


def sfn_subfn_u16(value: int) -> Tuple[int, int]:
    """Qualcomm packs (SFN << 4) | subframe into 16 bits."""
    return value >> 4, value & 0xF
