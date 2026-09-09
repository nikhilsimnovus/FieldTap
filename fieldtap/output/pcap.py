"""Classic pcap writer, used for the GSMTAP-compatible output format."""

from __future__ import annotations

import struct
from datetime import datetime
from typing import Optional

from .pcapng import epoch_micros

PCAP_MAGIC_MICROS = 0xA1B2C3D4


class PcapWriter:
    def __init__(self, fh, linktype: int, snaplen: int = 262144):
        self.fh = fh
        self.packets = 0
        self.fh.write(struct.pack("<IHHiIII", PCAP_MAGIC_MICROS, 2, 4, 0, 0, snaplen, linktype))

    def write_packet(self, data: bytes, when: Optional[datetime] = None, micros: Optional[int] = None) -> None:
        ts = micros if micros is not None else epoch_micros(when)
        self.fh.write(struct.pack("<IIII", ts // 1_000_000, ts % 1_000_000, len(data), len(data)))
        self.fh.write(data)
        self.packets += 1

    def flush(self) -> None:
        self.fh.flush()

    def close(self) -> None:
        try:
            self.fh.flush()
        finally:
            self.fh.close()
