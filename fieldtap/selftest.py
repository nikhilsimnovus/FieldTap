"""`fieldtap selftest`: prove the toolchain on this machine end to end.

Builds the synthetic corpus, runs it through the same pipeline a live capture
uses, and asks the installed Wireshark to decode the result. Passing means:
the framing, the parsers, the pcapng writer and the exported-PDU handoff all
work with the Wireshark that is actually installed."""

from __future__ import annotations

import os
import shutil
import sys
import tempfile
from typing import Optional

from . import __version__, fixtures, pipeline
from . import tshark as tshark_mod
from .decode import Decoder
from .diag.transport import FileTransport, adb_path
from .output.sinks import GsmtapPcapSink, PcapngSink


class Report:
    def __init__(self):
        self.lines = []
        self.failures = 0
        self.warnings = 0

    def ok(self, text: str) -> None:
        self.lines.append("  ok    " + text)

    def warn(self, text: str) -> None:
        self.warnings += 1
        self.lines.append("  warn  " + text)

    def fail(self, text: str) -> None:
        self.failures += 1
        self.lines.append("  FAIL  " + text)

    def section(self, text: str) -> None:
        self.lines.append(text)

    @property
    def passed(self) -> bool:
        return self.failures == 0

    def render(self) -> str:
        verdict = "PASS" if self.passed else "FAIL"
        return "\n".join(self.lines + ["", "selftest %s (%d failures, %d warnings)" % (verdict, self.failures, self.warnings)])


def _check_environment(rep: Report) -> Optional[str]:
    rep.section("environment")
    rep.ok("fieldtap %s on Python %s" % (__version__, sys.version.split()[0]))
    tshark = tshark_mod.find_tshark()
    if tshark:
        rep.ok("tshark: %s (%s)" % (tshark_mod.version(tshark), tshark))
    else:
        rep.warn("tshark not found: Wireshark decode checks skipped (install Wireshark or set FIELDTAP_TSHARK)")
    wireshark = tshark_mod.find_wireshark()
    rep.ok("wireshark: %s" % wireshark) if wireshark else rep.warn("wireshark GUI not found: --live will not work")
    for module, extra in (("serial", "serial"), ("usb.core", "usb")):
        try:
            __import__(module)
            rep.ok("python module %s available" % module)
        except ImportError:
            rep.warn("python module %s missing: pip install fieldtap[%s]" % (module, extra))
    rep.ok("adb: %s" % adb_path()) if adb_path() else rep.warn("adb not on PATH: --adb transport and handset metadata unavailable")
    return tshark


def _check_pipeline(rep: Report, workdir: str, tshark: Optional[str]) -> None:
    rep.section("pipeline (synthetic corpus)")
    records, expected = fixtures.build_corpus()
    qmdl = os.path.join(workdir, "selftest.qmdl")
    with open(qmdl, "wb") as fh:
        fh.write(fixtures.build_qmdl(records))
    pcapng = os.path.join(workdir, "selftest.pcapng")
    gsmtap = os.path.join(workdir, "selftest_gsmtap.pcap")
    decoder = Decoder()
    result = pipeline.run(FileTransport(qmdl), [PcapngSink(pcapng), GsmtapPcapSink(gsmtap)], decoder)
    rep.ok("replayed %d log records from %s" % (result.records, os.path.basename(qmdl)))
    if result.framing["crc_errors"] == 1:
        rep.ok("corrupt frame detected and skipped (crc errors = 1)")
    else:
        rep.fail("expected exactly 1 crc error from the noisy fixture, got %s" % result.framing["crc_errors"])
    if result.messages == len(expected):
        rep.ok("%d RRC/NAS messages decoded, as expected" % result.messages)
    else:
        rep.fail("decoded %d messages, expected %d: %s" % (result.messages, len(expected), result.decoder["errors"]))
    if result.cell_info == 2:
        rep.ok("cell-identity records parsed")
    else:
        rep.fail("expected 2 cell-identity records, got %d" % result.cell_info)
    unknown = result.decoder["unknown_codes"]
    rep.ok("unknown log code counted, not fatal: %s" % unknown) if unknown else rep.fail("unknown log code was not counted")
    probed = result.decoder["layout_sources"].get("probed", 0)
    rep.ok("unknown packet versions resolved by the length check (%d probed)" % probed) if probed else rep.fail("layout probing did not trigger")
    if result.decoder["stats"].get("direction_conflicts") == 1:
        rep.ok("NAS direction cross-check caught the mislabelled record")
    else:
        rep.fail("direction cross-check did not fire")
    if not tshark:
        return
    rep.section("wireshark decode")
    try:
        rows = tshark_mod.fields(pcapng, ["frame.number", "frame.protocols", "_ws.col.Info"], tshark=tshark)
    except RuntimeError as exc:
        rep.fail("tshark could not read the pcapng: %s" % exc)
        return
    if len(rows) != len(expected):
        rep.fail("Wireshark saw %d packets, FieldTap wrote %d" % (len(rows), len(expected)))
        return
    bad = 0
    for exp, (number, protocols, info) in zip(expected, rows):
        proto_ok = exp.dissector.split(".")[0].replace("-", "_") in protocols.replace("-", "_") or exp.dissector == "data"
        info_ok = exp.wireshark_info is None or exp.wireshark_info in info
        if not (proto_ok and info_ok):
            bad += 1
            rep.fail("frame %s: expected %s / %r, Wireshark said %s / %r" % (number, exp.dissector, exp.wireshark_info, protocols, info))
    if not bad:
        rep.ok("all %d packets decode with the expected dissector and message" % len(rows))
    malformed = tshark_mod.malformed(pcapng, tshark=tshark)
    rep.ok("no malformed packets") if not malformed else rep.fail("malformed packets: %s" % malformed)
    summary = tshark_mod.protocol_summary(gsmtap, tshark=tshark)
    if any(k.endswith("gsmtap:lte_rrc") for k in summary):
        rep.ok("GSMTAP pcap decodes as ip:udp:gsmtap:lte_rrc (Nov 2025 baseline reproduced)")
    else:
        rep.fail("GSMTAP pcap did not decode as lte_rrc: %s" % dict(summary))


def run(keep_dir: Optional[str] = None) -> Report:
    rep = Report()
    tshark = _check_environment(rep)
    workdir = keep_dir or tempfile.mkdtemp(prefix="fieldtap-selftest-")
    os.makedirs(workdir, exist_ok=True)
    try:
        _check_pipeline(rep, workdir, tshark)
    finally:
        if keep_dir:
            rep.section("files kept in %s" % workdir)
        else:
            shutil.rmtree(workdir, ignore_errors=True)
    return rep
