"""HDLC-style framing used on the Qualcomm diag port.

A frame is:   escape(payload || crc16_le(payload)) || 0x7E

* 0x7E terminates a frame. There is no leading flag; consecutive frames are
  simply concatenated.
* 0x7E and 0x7D inside the escaped region are sent as 0x7D followed by the
  byte XOR 0x20.
* The checksum is CRC-16/X-25 (reflected 0x1021, init 0xFFFF, xorout 0xFFFF),
  appended little-endian before escaping.
"""

from __future__ import annotations

FLAG = 0x7E
ESCAPE = 0x7D
ESCAPE_XOR = 0x20

# Upper bound on a single frame. Diag responses are at most a few KB; anything
# larger means we lost sync and should resynchronise on the next flag byte.
MAX_FRAME = 65536


def _build_table() -> list[int]:
    table = []
    for byte in range(256):
        crc = byte
        for _ in range(8):
            if crc & 1:
                crc = (crc >> 1) ^ 0x8408
            else:
                crc >>= 1
        table.append(crc)
    return table


_CRC_TABLE = _build_table()


def crc16(data: bytes) -> int:
    """CRC-16/X-25 as used by diag. crc16(b"123456789") == 0x906E."""
    crc = 0xFFFF
    for byte in data:
        crc = (crc >> 8) ^ _CRC_TABLE[(crc ^ byte) & 0xFF]
    return crc ^ 0xFFFF


def escape(data: bytes) -> bytes:
    out = bytearray()
    for byte in data:
        if byte in (FLAG, ESCAPE):
            out.append(ESCAPE)
            out.append(byte ^ ESCAPE_XOR)
        else:
            out.append(byte)
    return bytes(out)


def unescape(data: bytes) -> bytes:
    out = bytearray()
    pending = False
    for byte in data:
        if pending:
            out.append(byte ^ ESCAPE_XOR)
            pending = False
        elif byte == ESCAPE:
            pending = True
        else:
            out.append(byte)
    if pending:
        raise HdlcError("frame ends in the middle of an escape sequence")
    return bytes(out)


class HdlcError(ValueError):
    pass


def encode(payload: bytes) -> bytes:
    """Frame a diag request/response for the wire."""
    crc = crc16(payload)
    body = payload + bytes((crc & 0xFF, crc >> 8))
    return escape(body) + bytes((FLAG,))


def decode(frame: bytes) -> bytes:
    """Unframe one frame. `frame` may or may not include the trailing flag."""
    if frame.endswith(bytes((FLAG,))):
        frame = frame[:-1]
    body = unescape(frame)
    if len(body) < 3:
        raise HdlcError("frame too short (%d bytes)" % len(body))
    payload, crc_bytes = body[:-2], body[-2:]
    expected = crc_bytes[0] | (crc_bytes[1] << 8)
    actual = crc16(payload)
    if expected != actual:
        raise HdlcError("crc mismatch: frame says 0x%04X, computed 0x%04X" % (expected, actual))
    return payload


class Unframer:
    """Incremental unframer for a byte stream.

    Feed it whatever the transport returns; it yields complete, CRC-verified
    payloads and keeps counters for anything it had to throw away.
    """

    def __init__(self) -> None:
        self._buf = bytearray()
        self.frames = 0
        self.crc_errors = 0
        self.short_frames = 0
        self.resyncs = 0

    def feed(self, data: bytes) -> list[bytes]:
        out: list[bytes] = []
        self._buf.extend(data)
        while True:
            idx = self._buf.find(FLAG)
            if idx < 0:
                if len(self._buf) > MAX_FRAME:
                    # No flag in a huge buffer: we are not looking at diag.
                    self._buf.clear()
                    self.resyncs += 1
                return out
            raw = bytes(self._buf[:idx])
            del self._buf[: idx + 1]
            if not raw:
                # Two flags in a row (some stacks emit a leading flag). Harmless.
                continue
            try:
                out.append(decode(raw))
                self.frames += 1
            except HdlcError as exc:
                if "short" in str(exc):
                    self.short_frames += 1
                else:
                    self.crc_errors += 1

    @property
    def pending(self) -> int:
        return len(self._buf)
