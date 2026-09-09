"""The `fieldtap` command."""

from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
from typing import Optional

from . import __version__

DEFAULT_CAPTURES = os.environ.get("FIELDTAP_CAPTURES", "captures")


def _log(text: str) -> None:
    sys.stderr.write("[fieldtap] %s\n" % text)
    sys.stderr.flush()


def _parse_codes(text: Optional[str]) -> Optional[list]:
    if not text:
        return None
    codes = []
    for item in text.split(","):
        item = item.strip()
        if not item:
            continue
        codes.append(int(item, 16) if item.lower().startswith("0x") else int(item, 0))
    return codes


def _hostport(text: str, default_port: int):
    host, _, port = text.rpartition(":")
    if not host:
        return text, default_port
    return host, int(port)


# --- commands ---------------------------------------------------------------------------

def cmd_version(args) -> int:
    print("fieldtap %s" % __version__)
    return 0


def cmd_devices(args) -> int:
    from .diag import transport as tr
    print("serial ports")
    ports = tr.list_serial_ports()
    if not ports:
        print("  (none, or pyserial not installed)")
    for p in ports:
        flag = "  <- looks like a diag port" if p.likely_diag else ""
        vidpid = " [%04X:%04X]" % (p.vid, p.pid) if p.vid is not None else ""
        print("  %-12s %s%s%s" % (p.device, p.description, vidpid, flag))
    print("usb diag interfaces")
    devices = tr.list_usb_diag_devices()
    if not devices:
        print("  (none visible to libusb, or pyusb not installed)")
    for d in devices:
        print("  %04X:%04X bus %s addr %s interface %d (ep in 0x%02X out 0x%02X)"
              % (d["vid"], d["pid"], d["bus"], d["address"], d["interface"], d["ep_in"], d["ep_out"]))
    print("adb devices")
    adb = tr.adb_devices()
    if not adb:
        print("  (none, or adb not on PATH)")
    for d in adb:
        print("  %-24s %-12s %s" % (d["serial"], d["state"], d["info"]))
    return 0


def cmd_enable_diag(args) -> int:
    from .diag import transport as tr
    try:
        state = tr.adb_enable_diag_usb(args.serial)
    except tr.TransportError as exc:
        _log(str(exc))
        return 2
    print("usb state: %s" % state)
    print("now run: fieldtap devices   (then capture with --port or --usb)")
    return 0


def cmd_disable_diag(args) -> int:
    from .diag import transport as tr
    try:
        tr.adb_disable_diag_usb(args.serial, args.config)
    except tr.TransportError as exc:
        _log(str(exc))
        return 2
    print("usb config restored to %s" % args.config)
    return 0


def _build_transport(args):
    from .diag import transport as tr
    if args.port:
        return tr.SerialTransport(args.port, args.baud)
    if args.usb:
        return tr.UsbTransport(_parse_int(args.vid), _parse_int(args.pid), args.interface)
    if args.tcp:
        host, port = _hostport(args.tcp, 2500)
        return tr.TcpTransport(host, port)
    if args.adb:
        return tr.AdbTransport(args.serial, args.helper, args.adb_port, args.remote)
    if args.file:
        return tr.FileTransport(args.file, realtime=args.realtime)
    return None


def _parse_int(text: Optional[str]) -> Optional[int]:
    return int(text, 0) if text else None


def cmd_capture(args) -> int:
    from . import pipeline
    from .decode import Decoder
    from .diag import transport as tr
    from .output.sinks import GsmtapPcapSink, GsmtapUdpSink, PcapngSink, WiresharkLiveSink
    from .session import Session
    from .tshark import find_wireshark

    transport = _build_transport(args)
    if transport is None:
        _log("choose a source: --port COMx | --usb | --tcp host:port | --adb | --file capture.qmdl")
        return 2
    session = Session(args.captures, args.name, args.note, args.location)
    _log("session %s" % session.dir)
    sinks = []
    files = {}
    if args.format in ("wireshark", "both"):
        sinks.append(PcapngSink(session.pcapng_path, comment="FieldTap session %s" % session.name))
        files["pcapng"] = session.pcapng_path
    if args.format in ("gsmtap", "both"):
        sinks.append(GsmtapPcapSink(session.gsmtap_path))
        files["gsmtap_pcap"] = session.gsmtap_path
    if args.udp:
        host, port = _hostport(args.udp, 4729)
        sinks.append(GsmtapUdpSink(host, port))
        _log("streaming GSMTAP (LTE) to udp://%s:%d" % (host, port))
    if args.live:
        wireshark = find_wireshark()
        if not wireshark:
            _log("--live: Wireshark not found; continuing without the live window")
        else:
            sinks.append(WiresharkLiveSink(wireshark))
            _log("live view: Wireshark started, reading from FieldTap")
    raw = None
    if not args.no_raw and transport.interactive:
        raw = open(session.raw_path, "wb")
        files["raw"] = session.raw_path
    handset = tr.adb_getprops(args.serial) if (args.adb or args.handset_info) and tr.adb_path() else {}
    if handset:
        _log("handset: %s %s (%s)" % (handset.get("manufacturer", ""), handset.get("model", ""), handset.get("baseband", "")))
    options = pipeline.RunOptions(profile=args.profile, codes=_parse_codes(args.codes),
                                  quiet_modem=not args.keep_debug, max_seconds=args.seconds,
                                  max_records=args.records)
    stop_flag = threading.Event()

    def stop() -> bool:
        return stop_flag.is_set()

    if transport.interactive and args.seconds is None and args.records is None:
        _log("capturing; press Ctrl-C to stop")
    decoder = Decoder()
    try:
        result = pipeline.run(transport, sinks, decoder, session, raw_sink=raw, options=options,
                              stop=stop, log=_log)
    except tr.TransportError as exc:
        _log("transport: %s" % exc)
        if raw:
            raw.close()
        return 2
    except Exception as exc:  # DiagError and friends
        _log("capture failed: %s" % exc)
        if raw:
            raw.close()
        return 2
    if raw:
        raw.close()
    session.finish(transport.describe(), handset, result.modem, args.profile if not args.codes else "custom",
                   result.log_mask, result.client_stats, result.framing, result.decoder, result.sinks, files)
    _log("stopped (%s): %d records, %d messages, %d cell-info records in %.1f s"
         % (result.stopped_by, result.records, result.messages, result.cell_info, result.seconds))
    if result.framing.get("crc_errors"):
        _log("framing: %d crc errors" % result.framing["crc_errors"])
    if result.decoder["unknown_codes"]:
        _log("unknown log codes: %s" % ", ".join(result.decoder["unknown_codes"]))
    if result.decoder["layout_sources"].get("probed") or result.decoder["layout_sources"].get("forced"):
        _log("layout note: %s  (new packet versions? keep the .qmdl, see docs/CORPUS.md)"
             % result.decoder["layout_sources"])
    _log("files: %s" % ", ".join(os.path.basename(p) for p in files.values()))
    _log("sidecar: %s" % session.sidecar_path)
    return 0 if result.messages or not transport.interactive else 1


def cmd_decode(args) -> int:
    from . import pipeline
    from .decode import Decoder
    from .output.sinks import GsmtapPcapSink, PcapngSink
    from .session import Session

    if not os.path.isfile(args.input):
        _log("no such file: %s" % args.input)
        return 2
    base = os.path.splitext(args.input)[0]
    session = None
    sinks = []
    outputs = []
    if args.session_dir:
        session = Session(os.path.dirname(args.session_dir) or ".", os.path.basename(args.session_dir),
                          directory=args.session_dir)
        pcapng_path, gsmtap_path = session.pcapng_path, session.gsmtap_path
    else:
        pcapng_path = args.output or base + ".pcapng"
        gsmtap_path = (args.output or base) + "_gsmtap.pcap" if args.output else base + "_gsmtap.pcap"
    if args.format in ("wireshark", "both"):
        sinks.append(PcapngSink(pcapng_path, comment="FieldTap decode of %s" % os.path.basename(args.input)))
        outputs.append(pcapng_path)
    if args.format in ("gsmtap", "both"):
        sinks.append(GsmtapPcapSink(gsmtap_path))
        outputs.append(gsmtap_path)
    decoder = Decoder()
    result = pipeline.replay(args.input, sinks, decoder, session, log=_log)
    if session is not None:
        session.finish({"transport": "file", "path": args.input}, {}, {}, None, {}, result.client_stats,
                       result.framing, result.decoder, result.sinks, {"pcapng": pcapng_path})
    _log("%d records -> %d messages, %d cell-info records" % (result.records, result.messages, result.cell_info))
    if result.framing.get("crc_errors"):
        _log("framing: %d crc errors" % result.framing["crc_errors"])
    for name, count in sorted(result.decoder["unknown_codes"].items()):
        _log("unknown log code %s x%d" % (name, count))
    if result.decoder["errors"]:
        _log("decoder notes: %s" % result.decoder["errors"])
    for path in outputs:
        print(path)
    return 0 if result.messages else 1


def cmd_info(args) -> int:
    from . import info
    try:
        report = info.inspect(args.file)
    except (OSError, ValueError) as exc:
        _log(str(exc))
        return 2
    if args.json:
        print(json.dumps(report, indent=2, default=str))
    else:
        print(info.render(report))
    return 0


def cmd_flow(args) -> int:
    from . import flow
    events = flow.load_events(args.file, use_tshark=not args.no_tshark)
    text = flow.render(events, args.format, args.width)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(text + "\n")
        _log("wrote %s (%d messages)" % (args.output, len(events)))
    else:
        print(text)
    return 0


def cmd_kpi(args) -> int:
    from . import kpi
    try:
        rows = kpi.extract(args.file)
    except RuntimeError as exc:
        _log(str(exc))
        return 2
    text = kpi.to_csv(rows)
    if args.output:
        with open(args.output, "w", encoding="utf-8", newline="") as fh:
            fh.write(text)
        _log("wrote %s (%d measurement rows)" % (args.output, len(rows)))
    else:
        sys.stdout.write(text)
    return 0


def cmd_sessions(args) -> int:
    from .session import list_sessions
    sessions = list_sessions(args.captures)
    if not sessions:
        print("no sessions under %s" % args.captures)
        return 0
    print("%-26s %-20s %-8s %-14s %s" % ("started (UTC)", "name", "msgs", "plmn", "handset / note"))
    for s in sessions:
        print("%-26s %-20s %-8d %-14s %s" % ((s["started_utc"] or "")[:19], (s["name"] or "")[:20], s["messages"],
                                              s["plmns"][:14], " ".join(x for x in (s["handset"], s["note"]) if x)))
    return 0


def cmd_logcodes(args) -> int:
    from .decode.registry import LOG_CODES, PROFILES, profile_codes
    codes = profile_codes(args.profile) if args.profile else sorted(LOG_CODES)
    print("%-8s %-4s %-6s %-8s %-10s %s" % ("code", "rat", "cat", "decoder", "confidence", "name"))
    for code in codes:
        i = LOG_CODES[code]
        print("%-8s %-4s %-6s %-8s %-10s %s%s" % ("0x%04X" % code, i.rat, i.category, i.decoder or "-",
                                                i.confidence, i.name, ("  [%s]" % i.note) if i.note else ""))
    print("\nprofiles: %s" % ", ".join("%s (%d)" % (k, len(v)) for k, v in sorted(PROFILES.items())))
    return 0


def cmd_selftest(args) -> int:
    from . import selftest
    report = selftest.run(args.keep)
    print(report.render())
    return 0 if report.passed else 1


def cmd_fixtures(args) -> int:
    from . import fixtures
    paths = fixtures.write_fixture_files(args.directory)
    for kind, path in paths.items():
        print("%s: %s" % (kind, path))
    return 0


# --- parser ---------------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="fieldtap",
                                     description="Qualcomm handset -> diag -> RRC/NAS -> Wireshark.")
    parser.add_argument("--version", action="version", version="fieldtap %s" % __version__)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("devices", help="list serial ports, USB diag interfaces and adb devices")
    p.set_defaults(func=cmd_devices)

    p = sub.add_parser("enable-diag", help="switch a rooted handset's USB config to expose diag (via adb)")
    p.add_argument("--serial", help="adb device serial")
    p.set_defaults(func=cmd_enable_diag)

    p = sub.add_parser("disable-diag", help="restore the handset's normal USB config (via adb)")
    p.add_argument("--serial")
    p.add_argument("--config", default="mtp,adb")
    p.set_defaults(func=cmd_disable_diag)

    p = sub.add_parser("capture", help="capture from a handset into a session directory")
    src = p.add_argument_group("source")
    src.add_argument("--port", help="serial port of the Qualcomm diag interface (COM5, /dev/ttyUSB0)")
    src.add_argument("--baud", type=int, default=115200)
    src.add_argument("--usb", action="store_true", help="raw USB diag interface via libusb")
    src.add_argument("--vid", help="USB vendor id filter, e.g. 0x2A70")
    src.add_argument("--pid", help="USB product id filter")
    src.add_argument("--interface", type=int, help="USB interface number")
    src.add_argument("--tcp", help="diag relay host:port")
    src.add_argument("--adb", action="store_true", help="rooted handset via adb + fieldtap-diagd helper")
    src.add_argument("--serial", help="adb device serial")
    src.add_argument("--helper", help="local fieldtap-diagd binary to push to the handset")
    src.add_argument("--adb-port", type=int, default=45299)
    src.add_argument("--remote", help="helper --remote value for modems on a remote processor (mdm)")
    src.add_argument("--file", help="replay a .qmdl instead of a handset")
    src.add_argument("--realtime", action="store_true", help="pace file replay")
    out = p.add_argument_group("output")
    out.add_argument("--captures", default=DEFAULT_CAPTURES, help="sessions root (default: %(default)s)")
    out.add_argument("--name", help="session name")
    out.add_argument("--note", help="free text for the sidecar")
    out.add_argument("--location", help="where the capture was taken")
    out.add_argument("--format", choices=("wireshark", "gsmtap", "both"), default="wireshark",
                     help="pcapng exported-PDU (all RATs) and/or classic GSMTAP pcap (LTE only)")
    out.add_argument("--live", action="store_true", help="open Wireshark and stream into it")
    out.add_argument("--udp", help="also send GSMTAP datagrams to host:port (LTE only)")
    out.add_argument("--no-raw", action="store_true", help="do not keep the raw .qmdl")
    out.add_argument("--handset-info", action="store_true", help="query adb getprop for the sidecar")
    logs = p.add_argument_group("logs")
    logs.add_argument("--profile", default="signalling", help="log profile: signalling, lte, nr, corpus")
    logs.add_argument("--codes", help="explicit log codes, e.g. 0xB821,0xB0C0")
    logs.add_argument("--keep-debug", action="store_true", help="do not silence modem debug messages")
    logs.add_argument("--seconds", type=float, help="stop after N seconds")
    logs.add_argument("--records", type=int, help="stop after N log records")
    p.set_defaults(func=cmd_capture)

    p = sub.add_parser("decode", help="decode a recorded .qmdl / .dlf into pcapng")
    p.add_argument("input")
    p.add_argument("-o", "--output", help="pcapng path (default: next to the input)")
    p.add_argument("--format", choices=("wireshark", "gsmtap", "both"), default="wireshark")
    p.add_argument("--session-dir", help="also write a session sidecar into this directory")
    p.set_defaults(func=cmd_decode)

    p = sub.add_parser("info", help="summarise a .qmdl, .dlf or .pcapng")
    p.add_argument("file")
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_info)

    p = sub.add_parser("flow", help="call-flow ladder from a FieldTap pcapng")
    p.add_argument("file")
    p.add_argument("--format", choices=("text", "mermaid", "csv"), default="text")
    p.add_argument("--width", type=int, default=100)
    p.add_argument("--no-tshark", action="store_true", help="do not ask tshark for Info text")
    p.add_argument("-o", "--output")
    p.set_defaults(func=cmd_flow)

    p = sub.add_parser("kpi", help="RSRP/RSRQ/SINR timeline from the decoded signalling (needs tshark)")
    p.add_argument("file")
    p.add_argument("-o", "--output")
    p.set_defaults(func=cmd_kpi)

    p = sub.add_parser("sessions", help="list capture sessions")
    p.add_argument("--captures", default=DEFAULT_CAPTURES)
    p.set_defaults(func=cmd_sessions)

    p = sub.add_parser("logcodes", help="show the log-code register")
    p.add_argument("--profile")
    p.set_defaults(func=cmd_logcodes)

    p = sub.add_parser("selftest", help="verify the toolchain with a synthetic capture and Wireshark")
    p.add_argument("--keep", help="keep the generated files in this directory")
    p.set_defaults(func=cmd_selftest)

    p = sub.add_parser("fixtures", help="write the synthetic corpus files")
    p.add_argument("directory")
    p.set_defaults(func=cmd_fixtures)

    p = sub.add_parser("version")
    p.set_defaults(func=cmd_version)
    return parser


def main(argv: Optional[list] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
