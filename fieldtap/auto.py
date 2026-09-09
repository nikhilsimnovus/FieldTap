"""`fieldtap auto`: plug a phone in, everything else happens.

    watch USB ──► handset appears ──► Worker thread per handset
                                          │ enable diag (root over adb), wait for the port
                                          │ capture: log mask, raw .qmdl, pcapng, live Wireshark
                                          │ side threads: GPS (adb / NMEA), traffic tests (adb)
                                          │ live events on the console
                                          │ phone unplugged / Ctrl-C / time limit
                                          ▼
                                      finish: sidecar, events.csv, kpi.csv, track.csv,
                                              traffic.csv, summary.json, report.html

Several phones can be connected at once; each gets its own worker, session
directory and report. A handset that finished is not captured again until
it has been unplugged. A handset that is not ready (no USB-debugging
authorisation, no root) is reported and retried later.
"""

from __future__ import annotations

import os
import sys
import threading
import time
import traceback
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Callable, Optional

from . import devices, events as events_mod, gps as gps_mod, pipeline, report as report_mod, traffic as traffic_mod
from .decode import Decoder
from .diag import transport as tr
from .output.sinks import GsmtapPcapSink, PcapngSink, WiresharkLiveSink
from .session import Session
from .tshark import find_tshark, find_wireshark


@dataclass
class AutoOptions:
    captures: str = "captures"
    profile: str = "signalling"
    codes: Optional[list] = None
    gsmtap: bool = False
    live: bool = False
    keep_raw: bool = True
    report: bool = True
    open_report: bool = False
    gps: str = "auto"                    # auto | adb | none | nmea:COM7[@baud]
    gps_interval: float = 5.0
    traffic: list = field(default_factory=list)      # subset of ping, download, iperf3
    traffic_interval: float = 60.0
    ping_host: str = traffic_mod.DEFAULT_PING_HOST
    download_url: str = traffic_mod.DEFAULT_DOWNLOAD_URL
    iperf_server: Optional[str] = None
    max_seconds: Optional[float] = None
    poll: float = 2.0
    once: bool = False
    simulate: list = field(default_factory=list)     # .qmdl paths standing in for handsets
    simulate_extras: bool = True                     # synthetic GPS/traffic in simulate mode, so the report shows them
    name: Optional[str] = None
    note: Optional[str] = None
    location: Optional[str] = None
    enable_diag: bool = True
    keep_debug: bool = False
    retry_seconds: float = 30.0


def _stamp() -> str:
    return datetime.now(timezone.utc).strftime("%H:%M:%S")


class Worker(threading.Thread):
    """One handset, start to report."""

    def __init__(self, handset: devices.Handset, options: AutoOptions, stop_event: threading.Event,
                 busy_ports: set, log: Callable[[str], None], shared_track: Optional[gps_mod.Track] = None,
                 transport: Optional[tr.Transport] = None):
        super().__init__(name="fieldtap-%s" % handset.label, daemon=True)
        self.handset = handset
        self.options = options
        self.stop_event = stop_event
        self.busy_ports = busy_ports
        self.shared_track = shared_track
        self.transport = transport
        self._log = log
        self.session: Optional[Session] = None
        self.result: Optional[pipeline.RunResult] = None
        self.report_paths: dict = {}
        self.error: Optional[str] = None
        self.state = "new"               # new | preparing | capturing | finishing | done | not-ready | failed
        self.events: Optional[events_mod.EventDetector] = None
        self.track = shared_track or gps_mod.Track()
        self.traffic: Optional[traffic_mod.TrafficRunner] = None

    def log(self, text: str) -> None:
        self._log("[%s] %s" % (self.handset.label, text))

    def run(self) -> None:
        opts = self.options
        handset = self.handset
        try:
            self.state = "preparing"
            transport = self.transport
            if transport is None:
                try:
                    transport = devices.prepare(handset, self.busy_ports, self.log, enable_diag=opts.enable_diag)
                except devices.NotReady as exc:
                    self.error = str(exc)
                    self.state = "unsuitable" if getattr(exc, "permanent", False) else "not-ready"
                    self.log("%s: %s" % ("skipping" if exc.permanent else "not ready", exc))
                    return
            if handset.adb and not handset.props and tr.adb_path():
                handset.props = tr.adb_getprops(handset.adb.serial)
            if transport.interactive:
                transport = tr.BufferedTransport(transport)
            session = Session(opts.captures, opts.name or handset.label, opts.note, opts.location)
            self.session = session
            session.meta["device"] = handset.describe()
            self.log("session %s" % session.dir)

            sinks = [PcapngSink(session.pcapng_path, comment="FieldTap session %s (%s)" % (session.name, handset.label))]
            files = {"pcapng": session.pcapng_path}
            if opts.gsmtap:
                sinks.append(GsmtapPcapSink(session.gsmtap_path))
                files["gsmtap_pcap"] = session.gsmtap_path
            if opts.live:
                wireshark = find_wireshark()
                if wireshark:
                    sinks.append(WiresharkLiveSink(wireshark))
                    self.log("live Wireshark window opened")
                else:
                    self.log("--live: Wireshark not found; continuing without it")
            raw = None
            if opts.keep_raw and transport.interactive:
                raw = open(session.raw_path, "wb")
                files["raw"] = session.raw_path

            self.events = events_mod.EventDetector(on_event=self._on_event)
            side_stop = threading.Event()
            threads = []
            if handset.adb and opts.gps in ("auto", "adb") and self.shared_track is None and tr.adb_path():
                poller = gps_mod.AdbLocationPoller(handset.adb.serial, self.track, opts.gps_interval, self.log, side_stop)
                threads.append(poller)
            if handset.adb and opts.traffic and tr.adb_path():
                self.traffic = traffic_mod.TrafficRunner(handset.adb.serial, opts.traffic, opts.traffic_interval,
                                                         opts.ping_host, opts.download_url, opts.iperf_server,
                                                         log=self.log, stop=side_stop)
                threads.append(self.traffic)
            for t in threads:
                t.start()

            self.state = "capturing"
            run_opts = pipeline.RunOptions(profile=opts.profile, codes=opts.codes, quiet_modem=not opts.keep_debug,
                                           max_seconds=opts.max_seconds)
            try:
                result = pipeline.run(transport, sinks, Decoder(), session, raw_sink=raw, options=run_opts,
                                      stop=self.stop_event.is_set, log=self.log, observer=self.events.observe)
            finally:
                side_stop.set()
                if raw:
                    raw.close()
            self.result = result
            self.state = "finishing"
            for t in threads:
                t.join(timeout=5)

            if opts.simulate and opts.simulate_extras and transport.interactive is False:
                self._synthesize_extras(session)

            profile = opts.profile if not opts.codes else "custom"
            session.meta["summary_extra"] = {"stopped_by": result.stopped_by, "error": result.error}
            session.finish(transport.describe(), handset.props, result.modem, profile, result.log_mask,
                           result.client_stats, result.framing, result.decoder, result.sinks, files)
            session.meta["summary"]["stopped_by"] = result.stopped_by
            if result.error:
                session.meta["summary"]["error"] = result.error
            self._write_side_files(session)
            session.write_sidecar()
            self.log("stopped (%s): %d records, %d messages, %d events (%d errors) in %.0f s"
                     % (result.stopped_by, result.records, result.messages, len(self.events.events),
                        sum(1 for e in self.events.events if e.severity == "error"), result.seconds))
            if opts.report:
                try:
                    self.report_paths = report_mod.build(session.dir, find_tshark(), self.log, open_after=opts.open_report)
                except Exception as exc:
                    self.log("report failed: %s" % exc)
            self.state = "done"
        except Exception as exc:
            self.error = "%s: %s" % (exc.__class__.__name__, exc)
            self.state = "failed"
            self.log("failed: %s" % self.error)
            for line in traceback.format_exc().splitlines()[-4:]:
                self.log("  " + line)
        finally:
            devices.release(handset, self.busy_ports)

    # -- helpers --------------------------------------------------------------------

    def _on_event(self, ev: events_mod.Event) -> None:
        if ev.kind in events_mod.LOW_PRIORITY:
            return
        marker = {"error": "!!", "warn": " !", "ok": "ok", "info": ".."}.get(ev.severity, "..")
        self.log("%s %s%s" % (marker, ev.title, (" (%s)" % ev.detail) if ev.detail else ""))

    def _write_side_files(self, session: Session) -> None:
        events = self.events.events if self.events else []
        if events:
            try:
                if find_tshark() and os.path.isfile(session.pcapng_path):
                    events_mod.enrich_with_tshark(events, session.pcapng_path)
            except Exception:
                pass
            with open(session.path(report_mod.EVENTS_FILE), "w", encoding="utf-8", newline="") as fh:
                fh.write(events_mod.to_csv(events))
            session.meta["files"]["events"] = report_mod.EVENTS_FILE
        if len(self.track):
            fixes = self.track
            if self.shared_track is not None:
                started = datetime.fromisoformat(session.meta["started_utc"])
                fixes = gps_mod.Track()
                for f in self.shared_track.between(started, None):
                    fixes.add(f)
            if len(fixes):
                fixes.write_csv(session.path(report_mod.TRACK_FILE))
                session.meta["files"]["track"] = report_mod.TRACK_FILE
        if self.traffic and self.traffic.results:
            with open(session.path(report_mod.TRAFFIC_FILE), "w", encoding="utf-8", newline="") as fh:
                fh.write(traffic_mod.to_csv(self.traffic.results))
            session.meta["files"]["traffic"] = report_mod.TRAFFIC_FILE
        session.meta["events"] = self.events.summary() if self.events else {}

    def _synthesize_extras(self, session: Session) -> None:
        """Simulation only: a GPS route and traffic results that follow the
        same radio conditions as the replayed capture, so a demo report
        exercises every section. The report header marks the session
        SIMULATED, and every fix carries source "simulated"."""
        from . import demo as demo_mod
        start = datetime.fromisoformat(session.meta["started_utc"])
        first = session._first_ts or start
        last = session._last_ts or first
        span = max((last - first).total_seconds(), 1.0)
        profile = demo_mod.DriveProfile(seconds=span)
        for fix in demo_mod.route(profile, first):
            self.track.add(fix)
        results = demo_mod.traffic_results(profile, first)
        if results:
            self.traffic = traffic_mod.TrafficRunner(None, [], shell=lambda a, t: "")
            self.traffic.results.extend(results)


# --- the orchestrator ------------------------------------------------------------------------------

def run(options: AutoOptions, log: Callable[[str], None] = lambda s: None,
        stop_event: Optional[threading.Event] = None) -> list:
    """Run until stopped (Ctrl-C / stop_event) or, with --once / --simulate,
    until the first batch of handsets has finished. Returns the workers."""
    stop_event = stop_event or threading.Event()
    busy_ports: set = set()
    os.makedirs(options.captures, exist_ok=True)
    workers: list = []

    shared_track = None
    shared_threads = []
    if options.gps.startswith("nmea:"):
        spec = options.gps[5:]
        port, _, baud = spec.partition("@")
        shared_track = gps_mod.Track()
        poller = gps_mod.NmeaPoller(port, shared_track, int(baud or 9600), 1.0, log, stop_event)
        poller.start()
        shared_threads.append(poller)
        log("gps: reading NMEA from %s" % port)

    if options.simulate:
        for path in options.simulate:
            base = os.path.splitext(os.path.basename(path))[0]
            handset = devices.Handset("sim:" + base, None, None, {"model": base, "device": base})
            w = Worker(handset, options, stop_event, busy_ports, log, shared_track, transport=tr.FileTransport(path))
            workers.append(w)
            w.start()
            log("[%s] simulated handset from %s" % (handset.label, path))
        for w in workers:
            w.join()
        _finish_shared(shared_threads, stop_event)
        if options.report:
            report_mod.build_index(options.captures, log)
        return workers

    if not tr.adb_path():
        log("adb is not installed: phones must already expose a diag serial port (COM7 on Windows, "
            "/dev/ttyUSB0 on Linux) or a raw USB diag interface; run `fieldtap setup` for help")
    log("watching for handsets under %s (profile %s); press Ctrl-C to stop" % (os.path.abspath(options.captures), options.profile))
    active: dict = {}
    finished: dict = {}
    retry_after: dict = {}
    announced_waiting = False
    try:
        while not stop_event.is_set():
            now = time.monotonic()
            ports, adbs = devices.scan()
            present = devices.present_keys(ports, adbs)
            for key in list(finished):
                w = finished[key]
                port_id = w.handset.port.id if w.handset.port else None
                if key not in present and ("port:" + port_id) not in present if port_id else key not in present:
                    del finished[key]
                    log("[%s] unplugged; will capture again when reconnected" % w.handset.label)
            skip = set(active) | set(finished) | {k for k, t in retry_after.items() if t > now}
            for handset in devices.pair(ports, adbs, busy_ports, exclude_keys=skip):
                w = Worker(handset, options, stop_event, busy_ports, log, shared_track)
                active[handset.key] = w
                workers.append(w)
                announced_waiting = False
                log("[%s] handset detected (%s); starting" % (handset.label, "adb " + handset.adb.state if handset.adb else "diag port"))
                w.start()
            for key, w in list(active.items()):
                if w.is_alive():
                    continue
                del active[key]
                if w.state == "not-ready":
                    retry_after[key] = now + options.retry_seconds
                    log("[%s] retrying in %.0f s" % (w.handset.label, options.retry_seconds))
                else:
                    # "unsuitable" lands here too: parked until it is unplugged,
                    # so an emulator or a non-Qualcomm phone is reported once
                    # rather than every retry_seconds forever.
                    finished[key] = w
                    if options.once and w.state == "done":
                        stop_event.set()
            if not active and not announced_waiting:
                log("waiting for a handset (USB debugging on, or a Qualcomm diag port)...")
                announced_waiting = True
            stop_event.wait(options.poll)
    except KeyboardInterrupt:
        log("stopping...")
        stop_event.set()
    for w in list(active.values()):
        w.join(timeout=120)
    _finish_shared(shared_threads, stop_event)
    if options.report and workers:
        report_mod.build_index(options.captures, log)
    return workers


def _finish_shared(threads: list, stop_event: threading.Event) -> None:
    stop_event.set()
    for t in threads:
        t.join(timeout=3)


def summarize(workers: list) -> list:
    """One line per worker for the console."""
    lines = []
    for w in workers:
        if w.state == "done" and w.result is not None:
            lines.append("%-28s done   %6d msgs  %s" % (w.handset.label, w.result.messages,
                                                        w.report_paths.get("report") or (w.session.dir if w.session else "")))
        else:
            lines.append("%-28s %-10s %s" % (w.handset.label, w.state, w.error or ""))
    return lines
