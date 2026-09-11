"""GSMTAP v2 over UDP/IPv4, the encapsulation the Nov 2025 baseline used
(Wireshark: ip:udp:gsmtap:lte_rrc). Only LTE RRC and LTE NAS have GSMTAP
payload types in Wireshark 4.0; NR goes through exported_pdu instead."""

from __future__ import annotations

import socket
import struct
from typing import Optional

GSMTAP_VERSION = 2
GSMTAP_HDR_WORDS = 4
GSMTAP_UDP_PORT = 4729

GSMTAP_TYPE_LTE_RRC = 0x0D
GSMTAP_TYPE_LTE_NAS = 0x12

GSMTAP_ARFCN_F_UPLINK = 0x4000
GSMTAP_ARFCN_MASK = 0x3FFF

LINKTYPE_RAW = 101          # pcap link type: raw IPv4/IPv6 packet


def build_header(gsmtap_type: int, sub_type: int, arfcn: Optional[int] = None, uplink: bool = False,
                 frame_number: int = 0, sub_slot: int = 0, signal_dbm: int = 0, snr_db: int = 0,
                 antenna: int = 0) -> bytes:
    arfcn_field = 0
    if arfcn is not None and 0 <= arfcn <= GSMTAP_ARFCN_MASK:
        arfcn_field = arfcn
    if uplink:
        arfcn_field |= GSMTAP_ARFCN_F_UPLINK
    return struct.pack(">BBBBHbbIBBBB", GSMTAP_VERSION, GSMTAP_HDR_WORDS, gsmtap_type, 0,
                       arfcn_field, max(-128, min(127, signal_dbm)), max(-128, min(127, snr_db)),
                       frame_number & 0xFFFFFFFF, sub_type, antenna, sub_slot & 0xFF, 0)


def _ipv4_checksum(header: bytes) -> int:
    total = 0
    for i in range(0, len(header), 2):
        total += (header[i] << 8) | header[i + 1]
    while total >> 16:
        total = (total & 0xFFFF) + (total >> 16)
    return (~total) & 0xFFFF


def build_ip_udp(payload: bytes, src: str = "127.0.0.1", dst: str = "127.0.0.1",
                 sport: int = GSMTAP_UDP_PORT, dport: int = GSMTAP_UDP_PORT) -> bytes:
    udp_len = 8 + len(payload)
    udp = struct.pack(">HHHH", sport, dport, udp_len, 0) + payload
    total = 20 + udp_len
    header = struct.pack(">BBHHHBBH4s4s", 0x45, 0, total, 0, 0, 64, 17, 0,
                         socket.inet_aton(src), socket.inet_aton(dst))
    checksum = _ipv4_checksum(header)
    header = header[:10] + struct.pack(">H", checksum) + header[12:]
    return header + udp


def build_frame(gsmtap_type: int, sub_type: int, payload: bytes, arfcn: Optional[int] = None,
                uplink: bool = False, frame_number: int = 0, sub_slot: int = 0) -> bytes:
    """A complete raw-IP frame: IPv4 / UDP 4729 / GSMTAP / payload."""
    gsmtap = build_header(gsmtap_type, sub_type, arfcn, uplink, frame_number, sub_slot) + payload
    return build_ip_udp(gsmtap)


class UdpSender:
    """Stream GSMTAP datagrams to a live Wireshark (udp.port == 4729)."""

    def __init__(self, host: str = "127.0.0.1", port: int = GSMTAP_UDP_PORT):
        self.addr = (host, port)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sent = 0

    def send(self, gsmtap_type: int, sub_type: int, payload: bytes, arfcn: Optional[int] = None,
             uplink: bool = False, frame_number: int = 0, sub_slot: int = 0) -> None:
        self.sock.sendto(build_header(gsmtap_type, sub_type, arfcn, uplink, frame_number, sub_slot) + payload,
                         self.addr)
        self.sent += 1

    def close(self) -> None:
        self.sock.close()
