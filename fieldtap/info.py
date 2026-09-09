"""`fieldtap info`: what is in a capture file, without opening Wireshark."""

from __future__ import annotations

import os
from collections import Counter
from datetime import datetime
from typing import Optional

from .decode import Decoder
from .decode.registry import LOG_CODES
from .diag import hdlc, protocol
from .output import exported_pdu
from .output.pcapng import read_packets


def _time_range(first: Optional[datetime], last: Optional[datetime]) -> dict:
    return {"first_utc": first.isoformat(timespec="milliseconds") if first else None,
            "last_utc": last.isoformat(timespec="milliseconds") if last else None,
            "seconds": (last - first).total_seconds() if first and last else None}


def inspect_raw(path: str) -> dict:
    """.qmdl (HDLC stream) or .dlf (bare log entries)."""
    ext = os.path.splitext(path)[1].lower()
    decoder = Decoder()
    codes: Counter = Counter()
    other_frames: Counter = Counter()
    first = last = None
    size = os.path.getsize(path)
    unframer = hdlc.Unframer()

    def note(rec: protocol.LogRecord) -> None:
        nonlocal first, last
        codes[rec.code] += 1
        decoder.decode(rec)
        ts = rec.timestamp
        if protocol.timestamp_is_plausible(ts):
            first = ts if first is None or ts < first else first
            last = ts if last is None or ts > last else last

    if ext == ".dlf":
        with open(path, "rb") as fh:
            for rec in protocol.iter_log_entries(fh.read()):
                note(rec)
        framing = {}
    else:
        with open(path, "rb") as fh:
            while True:
                chunk = fh.read(1 << 16)
                if not chunk:
                    break
                for frame in unframer.feed(chunk):
                    if not frame:
                        continue
                    if frame[0] == protocol.DIAG_LOG_F:
                        try:
                            note(protocol.parse_log_packet(frame))
                        except ValueError:
                            other_frames["bad log packet"] += 1
                    else:
                        other_frames["0x%02X" % frame[0]] += 1
        framing = {"frames": unframer.frames, "crc_errors": unframer.crc_errors,
                   "short_frames": unframer.short_frames, "resyncs": unframer.resyncs}
    return {
        "file": path, "kind": "dlf" if ext == ".dlf" else "qmdl", "bytes": size,
        "framing": framing, "other_frames": dict(other_frames),
        "log_records": sum(codes.values()),
        "codes": [{"code": "0x%04X" % c, "name": LOG_CODES[c].name if c in LOG_CODES else "(unknown)",
                   "count": n, "versions": dict(decoder.versions.get(c, {}))}
                  for c, n in sorted(codes.items())],
        "decoder": decoder.report(),
        "time": _time_range(first, last),
    }


def inspect_pcapng(path: str) -> dict:
    packets = 0
    dissectors: Counter = Counter()
    kinds: Counter = Counter()
    first = last = None
    for pkt in read_packets(path):
        packets += 1
        when = pkt.when
        first = when if first is None or when < first else first
        last = when if last is None or when > last else last
        if pkt.linktype == exported_pdu.LINKTYPE_WIRESHARK_UPPER_PDU:
            options, _ = exported_pdu.parse(pkt.data)
            dissectors[options.get("dissector", "?")] += 1
        if pkt.comment and pkt.comment.startswith("FieldTap "):
            kinds[" ".join(pkt.comment.split(" ")[1:4])] += 1
    return {"file": path, "kind": "pcapng", "bytes": os.path.getsize(path), "packets": packets,
            "dissectors": dict(dissectors), "messages": dict(kinds), "time": _time_range(first, last)}


def inspect(path: str) -> dict:
    ext = os.path.splitext(path)[1].lower()
    if ext in (".pcapng", ".pcap"):
        if ext == ".pcap":
            raise ValueError("classic .pcap is not inspected; open it in Wireshark")
        return inspect_pcapng(path)
    return inspect_raw(path)


def render(report: dict) -> str:
    lines = ["%s (%s, %d bytes)" % (report["file"], report["kind"], report["bytes"])]
    t = report.get("time", {})
    if t.get("first_utc"):
        lines.append("modem time  %s .. %s (%.1f s)" % (t["first_utc"], t["last_utc"], t["seconds"] or 0))
    if report["kind"] in ("qmdl", "dlf"):
        fr = report.get("framing") or {}
        if fr:
            lines.append("framing     %d frames, %d crc errors, %d short, %d resyncs"
                         % (fr["frames"], fr["crc_errors"], fr["short_frames"], fr["resyncs"]))
        if report["other_frames"]:
            lines.append("non-log     " + ", ".join("%s x%d" % kv for kv in sorted(report["other_frames"].items())))
        lines.append("log records %d" % report["log_records"])
        lines.append("")
        lines.append("  %-8s %-6s %-52s %s" % ("code", "count", "name", "versions"))
        for entry in report["codes"]:
            versions = ", ".join("v%s x%d" % kv for kv in sorted(entry["versions"].items()))
            lines.append("  %-8s %-6d %-52s %s" % (entry["code"], entry["count"], entry["name"][:52], versions))
        d = report["decoder"]
        lines.append("")
        lines.append("decoded     " + ", ".join("%s=%d" % kv for kv in sorted(d["stats"].items())))
        if d["layout_sources"]:
            lines.append("layouts     " + ", ".join("%s=%d" % kv for kv in sorted(d["layout_sources"].items())))
        if d["errors"]:
            lines.append("errors      " + ", ".join("%s x%d" % kv for kv in sorted(d["errors"].items())))
    else:
        lines.append("packets     %d" % report["packets"])
        for name, count in sorted(report["dissectors"].items()):
            lines.append("  %-32s %d" % (name, count))
        if report["messages"]:
            lines.append("messages")
            for name, count in sorted(report["messages"].items()):
                lines.append("  %-32s %d" % (name, count))
    return "\n".join(lines)
