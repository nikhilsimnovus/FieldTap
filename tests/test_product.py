"""The plug-and-go product: device pairing, events, GPS, traffic, report.

The ASN.1 tests are the load-bearing ones. FieldTap's whole claim is that
Wireshark does the decoding, so the demo generator's hand-rolled PER
encodings are checked by asking the installed Wireshark to read them back:
if a Wireshark upgrade ever changes a field width, these fail rather than
quietly producing wrong dBm values in a customer's report.
"""

import os
import threading

import pytest

from fieldtap import demo, devices, events as events_mod, gps, report, traffic
from fieldtap import tshark as tshark_mod
from fieldtap.decode import Decoder
from fieldtap.decode.records import DecodedMessage
from fieldtap.diag import hdlc, protocol
from fieldtap.diag.client import DiagClient
from fieldtap.diag.transport import BufferedTransport, FileTransport, LoopbackTransport, Transport, TransportError
from fieldtap.output import exported_pdu
from fieldtap.output.pcapng import PcapngWriter
from fieldtap.output.sinks import PcapngSink

TSHARK = tshark_mod.find_tshark()


# --- ASN.1 encodings, verified by the installed Wireshark -------------------------------------

def _decode_with_wireshark(tmp_path, packets, fields):
    """packets: [(dissector, payload)] -> tshark rows for `fields`."""
    path = str(tmp_path / "probe.pcapng")
    with open(path, "wb") as fh:
        w = PcapngWriter(fh, "test")
        if_id = w.add_interface(exported_pdu.LINKTYPE_WIRESHARK_UPPER_PDU, "t")
        for dissector, payload in packets:
            w.write_packet(if_id, exported_pdu.build(dissector, payload, "ul"))
        w.flush()
    rows = tshark_mod.fields(path, fields, tshark=TSHARK)
    return rows, tshark_mod.malformed(path, tshark=TSHARK)


@pytest.mark.skipif(TSHARK is None, reason="tshark not installed")
def test_lte_measurement_report_round_trips_through_wireshark(tmp_path):
    cases = [(1, 60, 20), (7, 97, 34), (32, 0, 0), (12, 45, 11)]
    packets = [("lte-rrc.ul.dcch", demo.lte_measurement_report(m, p, q)) for m, p, q in cases]
    rows, bad = _decode_with_wireshark(
        tmp_path, packets, ["frame.number", "_ws.col.Info", "lte-rrc.rsrpResult", "lte-rrc.rsrqResult"])
    assert bad == []
    for (_m, rsrp, rsrq), row in zip(cases, rows):
        assert row[1] == "MeasurementReport"
        assert row[2] == str(rsrp), row
        assert row[3] == str(rsrq), row


@pytest.mark.skipif(TSHARK is None, reason="tshark not installed")
def test_nr_measurement_report_round_trips_through_wireshark(tmp_path):
    cases = [(1, 245, 90, 60, 70), (5, 0, 127, 0, 127), (9, 1007, 40, 100, 12)]
    packets = [("nr-rrc.ul.dcch", demo.nr_measurement_report(*c)) for c in cases]
    rows, bad = _decode_with_wireshark(
        tmp_path, packets, ["frame.number", "nr-rrc.measId", "nr-rrc.physCellId",
                            "nr-rrc.measQuantityResults.rsrp", "nr-rrc.measQuantityResults.rsrq",
                            "nr-rrc.measQuantityResults.sinr"])
    assert bad == []
    for (meas_id, pci, rsrp, rsrq, sinr), row in zip(cases, rows):
        assert row[1:] == [str(meas_id), str(pci), str(rsrp), str(rsrq), str(sinr)], row


@pytest.mark.skipif(TSHARK is None, reason="tshark not installed")
def test_setup_and_reestablishment_encodings(tmp_path):
    packets = [("lte-rrc.dl.ccch", demo.lte_connection_setup(0)),
               ("lte-rrc.ul.ccch", demo.lte_reestablishment_request(101, "handoverFailure"))]
    rows, bad = _decode_with_wireshark(
        tmp_path, packets, ["frame.number", "_ws.col.Info", "lte-rrc.physCellId",
                            "lte-rrc.reestablishmentCause"])
    assert bad == []
    assert "RRCConnectionSetup" in rows[0][1]
    assert "Reestablishment" in rows[1][1]
    assert rows[1][2] == "101"
    assert rows[1][3] == "1"          # handoverFailure


def test_measurement_ranges_map_to_3gpp_dbm():
    # TS 36.133 9.1.4: RSRP-Range 0..97 maps to -140..-43 dBm
    assert demo.lte_rsrp_range(-140.0) == 0 and demo.lte_rsrp_range(-80.0) == 60
    assert demo.lte_rsrq_range(-19.5) == 0 and demo.lte_rsrq_range(-9.5) == 20
    # TS 38.133 10.1.6.1: SS-RSRP 0..127 maps to -156..-29 dBm
    assert demo.nr_rsrp_range(-156.0) == 0 and demo.nr_rsrp_range(-70.0) == 86
    assert demo.nr_sinr_range(-23.0) == 0 and demo.nr_sinr_range(12.0) == 70
    for out_of_range in (-500.0, 500.0):
        assert 0 <= demo.lte_rsrp_range(out_of_range) <= 97
        assert 0 <= demo.nr_rsrp_range(out_of_range) <= 127


# --- the simulated drive -------------------------------------------------------------------------

@pytest.fixture(scope="module")
def drive(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("drive")
    qmdl = str(tmp / "drive.qmdl")
    demo.write_qmdl(qmdl)
    return qmdl, tmp


def test_demo_drive_decodes_and_tells_a_story(drive, tmp_path):
    qmdl, _ = drive
    from fieldtap import pipeline
    out = str(tmp_path / "drive.pcapng")
    det = events_mod.EventDetector()
    result = pipeline.replay(qmdl, [PcapngSink(out)], Decoder(), observer=det.observe)
    assert result.messages > 100
    assert result.decoder["stats"]["errors"] == 0
    kinds = det.counts
    assert kinds.get("handover") == 1, kinds
    assert kinds.get("reestablishment_attempt") == 1, kinds
    assert kinds.get("rrc_setup", 0) >= 2, kinds
    assert kinds.get("meas_report", 0) > 50, kinds
    summary = det.summary()
    assert summary["procedures"]["rrc"]["attempts"] >= 2
    assert summary["procedures"]["rrc"]["setup_ms_avg"] is not None
    # the PDN and PDU rejects carry their 3GPP cause text
    causes = [e.fields.get("cause_text") for e in det.events if "cause_text" in e.fields]
    assert "Missing or unknown APN" in causes
    assert "Insufficient resources" in causes


@pytest.mark.skipif(TSHARK is None, reason="tshark not installed")
def test_demo_drive_has_no_malformed_frames_and_real_kpis(drive, tmp_path):
    qmdl, _ = drive
    from fieldtap import kpi, pipeline
    out = str(tmp_path / "drive.pcapng")
    pipeline.replay(qmdl, [PcapngSink(out)], Decoder())
    assert tshark_mod.malformed(out, tshark=TSHARK) == []
    rows = kpi.extract(out, TSHARK)
    assert len(rows) > 50
    lte = [float(r["rsrp_dbm"].split(",")[0]) for r in rows if r["rat"] == "lte" and r["rsrp_dbm"]]
    nr = [float(r["rsrp_dbm"].split(",")[0]) for r in rows if r["rat"] == "nr" and r["rsrp_dbm"]]
    assert lte and nr
    assert all(-140 <= v <= -43 for v in lte), (min(lte), max(lte))
    assert all(-156 <= v <= -29 for v in nr), (min(nr), max(nr))
    assert min(lte) < -100 < max(lte)          # the drive really does fade and recover


def test_events_rebuilt_from_pcapng_match_the_live_stream(drive, tmp_path):
    qmdl, _ = drive
    from fieldtap import pipeline
    out = str(tmp_path / "drive.pcapng")
    live = events_mod.EventDetector()
    pipeline.replay(qmdl, [PcapngSink(out)], Decoder(), observer=live.observe)
    rebuilt = events_mod.from_pcapng(out)
    live_kinds = {k: v for k, v in live.counts.items() if k != "serving_cell"}
    rebuilt_kinds = {k: v for k, v in rebuilt.counts.items() if k != "serving_cell"}
    assert rebuilt_kinds == live_kinds


def test_report_builds_from_a_session(drive, tmp_path):
    from fieldtap import auto
    captures = str(tmp_path / "captures")
    qmdl, _ = drive
    workers = auto.run(auto.AutoOptions(captures=captures, simulate=[qmdl], report=True,
                                        open_report=False, name="unit"), log=lambda s: None)
    assert len(workers) == 1 and workers[0].state == "done", workers[0].error
    session_dir = workers[0].session.dir
    for name in ("report.html", "summary.json", "events.csv", "track.csv", "capture.pcapng"):
        assert os.path.isfile(os.path.join(session_dir, name)), name
    page = open(os.path.join(session_dir, "report.html"), encoding="utf-8").read()
    assert "SIMULATED" in page                      # never mistakable for a real capture
    assert "<svg" in page and "Call flow" in page
    assert os.path.isfile(os.path.join(captures, "index.html"))


# --- device discovery and pairing ------------------------------------------------------------------

def _port(dev, serial=None, kind="serial"):
    return devices.DiagPort(kind, dev, "Qualcomm HS-USB Diagnostics 9091", 0x05C6, 0x9091, serial)


def test_pairing_matches_ports_to_phones_by_usb_serial():
    ports = [_port("COM7", "1a2b3c4d"), _port("COM9", "9f8e7d6c")]
    adbs = [devices.AdbDevice("9f8e7d6c", "device"), devices.AdbDevice("1a2b3c4d", "device")]
    handsets = devices.pair(ports, adbs)
    assert len(handsets) == 2
    by_key = {h.key: h for h in handsets}
    assert by_key["1a2b3c4d"].port.id == "COM7"
    assert by_key["9f8e7d6c"].port.id == "COM9"


def test_pairing_falls_back_when_the_serial_is_hidden():
    handsets = devices.pair([_port("COM7")], [devices.AdbDevice("abc123", "device")])
    assert len(handsets) == 1 and handsets[0].key == "abc123" and handsets[0].port.id == "COM7"


def test_pairing_keeps_phones_apart_when_neither_exposes_a_serial():
    """Two anonymous ports and two phones must not be guessed at."""
    handsets = devices.pair([_port("COM7"), _port("COM8")],
                            [devices.AdbDevice("a", "device"), devices.AdbDevice("b", "device")])
    keys = sorted(h.key for h in handsets)
    assert keys == ["a", "b", "port:COM7", "port:COM8"]


def test_busy_ports_and_finished_handsets_are_skipped():
    ports = [_port("COM7", "s1"), _port("COM8", "s2")]
    adbs = [devices.AdbDevice("s1", "device"), devices.AdbDevice("s2", "device")]
    assert [h.key for h in devices.pair(ports, adbs, busy_ports={"COM7"})] == ["s2", "s1"]
    assert [h.key for h in devices.pair(ports, adbs, exclude_keys={"s1"})] == ["s2"]


def test_unauthorized_phone_is_reported_not_captured():
    handset = devices.Handset("x", devices.AdbDevice("x", "unauthorized"), None)
    with pytest.raises(devices.NotReady, match="unauthorized"):
        devices.prepare(handset, set(), enable_diag=False)


def test_handset_label_is_readable():
    h = devices.Handset("1a2b3c4d5e", devices.AdbDevice("1a2b3c4d5e", "device"), None, {"model": "GM1913"})
    assert h.label == "GM1913-3c4d5e"
    assert devices.Handset("port:COM7", None, _port("COM7")).label.endswith("COM7")


# --- GPS ------------------------------------------------------------------------------------------

DUMPSYS = """
Location Manager State:
  last location[gps]: Location[gps 12.9715987,77.5945627 hAcc=12.0 et=+3d2h11m5s123ms alt=920.3 vel=0.4]
  last location[network]: Location[network 12.9000000,77.5000000 hAcc=780.0 et=+3d2h10m0s]
  last location[fused]: Location[fused 12.9715000,77.5945000 hAcc=14.0 et=+3d2h11m5s000ms]
"""


def test_dumpsys_location_parsing_prefers_gps():
    fixes = gps.parse_dumpsys_location(DUMPSYS)
    assert len(fixes) == 3
    assert fixes[0].provider == "gps"
    assert abs(fixes[0].lat - 12.9715987) < 1e-7 and abs(fixes[0].lon - 77.5945627) < 1e-7
    assert fixes[0].accuracy_m == 12.0 and fixes[0].altitude_m == 920.3 and fixes[0].speed_mps == 0.4


def test_dumpsys_location_rejects_null_island_and_junk():
    assert gps.parse_dumpsys_location("Location[gps 0.0,0.0 hAcc=1.0]") == []
    assert gps.parse_dumpsys_location("Location[gps 999.0,12.0]") == []
    assert gps.parse_dumpsys_location("no locations here") == []


def test_nmea_parsing():
    gga = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
    assert gps.nmea_checksum_ok(gga)
    fix = gps.parse_nmea(gga)
    assert abs(fix.lat - 48.1173) < 1e-4 and abs(fix.lon - 11.5166667) < 1e-4
    assert fix.altitude_m == 545.4
    rmc = "$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"
    fix = gps.parse_nmea(rmc)
    assert abs(fix.speed_mps - 22.4 * 0.514444) < 0.01
    assert gps.parse_nmea("$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*00") is None
    assert gps.parse_nmea("$GPRMC,123519,V,,,,,,,230394,,*4F") is None   # no fix


def test_track_nearest_and_csv_round_trip(tmp_path):
    from datetime import datetime, timedelta, timezone
    t0 = datetime(2026, 9, 9, tzinfo=timezone.utc)
    track = gps.Track()
    for i in range(5):
        track.add(gps.Fix(t0 + timedelta(seconds=i * 10), 12.0 + i * 0.001, 77.0, 5.0, source="test"))
    assert track.nearest(t0 + timedelta(seconds=21)).lat == pytest.approx(12.002)
    assert track.nearest(t0 + timedelta(seconds=5000), max_gap_s=30) is None
    path = str(tmp_path / "track.csv")
    track.write_csv(path)
    back = gps.Track.read_csv(path)
    assert len(back) == 5 and back.fixes()[2].lat == pytest.approx(12.002)
    rows = [{"time_epoch": str((t0 + timedelta(seconds=21)).timestamp())}, {"time_epoch": ""}]
    assert gps.tag_rows(rows, track) == 1
    assert rows[0]["lat"].startswith("12.002") and rows[1]["lat"] == ""


# --- traffic tests ------------------------------------------------------------------------------------

PING_OUT = """PING 8.8.8.8 (8.8.8.8) 56(84) bytes of data.
64 bytes from 8.8.8.8: icmp_seq=1 ttl=113 time=23.4 ms
--- 8.8.8.8 ping statistics ---
5 packets transmitted, 4 received, 20% packet loss, time 4005ms
rtt min/avg/max/mdev = 21.101/23.412/28.900/2.100 ms
"""


def test_ping_parsing():
    m = traffic.parse_ping(PING_OUT)
    assert m["sent"] == 5 and m["received"] == 4 and m["loss_pct"] == 20.0
    assert m["rtt_avg_ms"] == 23.412 and m["rtt_max_ms"] == 28.9
    assert traffic.parse_ping("network unreachable") == {}


def test_curl_and_iperf_parsing():
    for raw in ("6553600 25000000 3.81 200", "'6553600 25000000 3.81 200'"):
        m = traffic.parse_curl_write_out(raw)
        assert round(m["mbps"], 2) == 52.43 and m["bytes"] == 25000000 and m["http_code"] == "200"
    assert traffic.parse_curl_write_out("") == {}
    assert traffic.parse_curl_write_out("curl: (6) Could not resolve host") == {}
    iperf = "[  5]   0.00-10.00  sec   112 MBytes  94.1 Mbits/sec    0             sender\n" \
            "[  5]   0.00-10.00  sec   111 MBytes  93.2 Mbits/sec                  receiver\n"
    assert traffic.parse_iperf3(iperf)["mbps"] == pytest.approx(93.2)


def test_traffic_runner_records_results_without_a_phone():
    calls = []

    def fake_shell(args, timeout):
        calls.append(args)
        return PING_OUT if args[0] == "ping" else ""

    runner = traffic.TrafficRunner(None, ["ping"], shell=fake_shell)
    results = runner.run_once()
    assert len(results) == 1 and results[0].ok and results[0].metrics["loss_pct"] == 20.0
    assert "ping" in results[0].line()
    assert traffic.summary(results)["ping"]["rtt_avg_ms"] == 23.4


def test_traffic_csv_round_trip(tmp_path):
    from datetime import datetime, timezone
    r = traffic.TestResult(datetime(2026, 9, 9, tzinfo=timezone.utc), "download", "url", True,
                           {"mbps": 51.5, "bytes": 25000000, "http_code": "200"}, "", 4.0)
    path = str(tmp_path / "traffic.csv")
    open(path, "w", encoding="utf-8", newline="").write(traffic.to_csv([r]))
    back = traffic.read_csv(path)
    assert len(back) == 1 and back[0].metrics["mbps"] == 51.5 and back[0].ok


# --- transport and log-mask behaviour ------------------------------------------------------------------

def test_buffered_transport_keeps_every_byte_and_reports_the_error():
    class Chunky(Transport):
        def __init__(self):
            self.sent = [b"abc", b"defg", b"", b"hi"]
            self.opened = False

        def open(self):
            self.opened = True

        def read(self, max_bytes=65536, timeout=0.5):
            if self.sent:
                return self.sent.pop(0)
            raise EOFError("done")

        def write(self, data):
            pass

    inner = Chunky()
    buffered = BufferedTransport(inner, chunk_timeout=0.01)
    buffered.open()
    got = bytearray()
    for _ in range(60):
        try:
            got += buffered.read(timeout=0.05)
        except EOFError:
            break
    buffered.close()
    assert bytes(got) == b"abcdefghi"


def test_unplugging_mid_capture_still_finishes_the_session(tmp_path):
    """A transport that dies is not an exception the user has to see; the
    pcapng is closed and the run is marked 'unplugged'."""
    from fieldtap import fixtures, pipeline

    records, _ = fixtures.build_corpus()
    stream = fixtures.build_qmdl(records[:6], with_noise=False)

    class Yanked(Transport):
        def __init__(self):
            self.data = stream
            self.reads = 0

        def open(self):
            pass

        def read(self, max_bytes=65536, timeout=0.5):
            self.reads += 1
            if self.reads == 1:
                return self.data
            raise TransportError("serial port COM7 lost: device disconnected")

        def write(self, data):
            pass

    out = str(tmp_path / "yanked.pcapng")
    sink = PcapngSink(out)
    result = pipeline.run(Yanked(), [sink], Decoder(), options=pipeline.RunOptions(profile="signalling"))
    assert result.stopped_by == "unplugged"
    assert "lost" in (result.error or "")
    assert result.messages > 0
    assert os.path.getsize(out) > 0


def test_configure_all_logs_enables_every_reported_item():
    """The 'capture everything' mode asks the modem for its ranges and turns
    on every item in them, rather than a curated list."""
    import struct

    seen = []

    def modem(frame_bytes):
        req = hdlc.decode(frame_bytes)
        seen.append(req)
        if req[0] != protocol.DIAG_LOG_CONFIG_F:
            return hdlc.encode(bytes([protocol.DIAG_BAD_CMD_F]) + req)
        op = struct.unpack_from("<I", req, 4)[0]
        if op == protocol.LOG_CONFIG_DISABLE_OP:
            return hdlc.encode(struct.pack("<BBBBII", 0x73, 0, 0, 0, 0, 0))
        if op == protocol.LOG_CONFIG_RETRIEVE_ID_RANGES_OP:
            ranges = [0] * 16
            ranges[0xB] = 0x20
            ranges[0x1] = 0x10
            return hdlc.encode(protocol.build_log_config_ranges_response(ranges))
        equip, last = struct.unpack_from("<II", req, 8)
        return hdlc.encode(protocol.build_log_config_set_mask_response(equip, last, req[16:]))

    transport = LoopbackTransport(modem)
    transport.open()
    client = DiagClient(transport)
    mask = client.configure_all_logs()
    assert len(mask.enabled) == (0x20 + 1) + (0x10 + 1)
    assert 0xB000 in mask.enabled and 0xB020 in mask.enabled and 0x1010 in mask.enabled
    assert not mask.failed
    assert mask.ranges[0xB] == 0x20


def test_all_profile_is_resolved_by_the_modem_not_a_static_list():
    from fieldtap.decode.registry import ALL_PROFILE, profile_codes
    assert profile_codes(ALL_PROFILE) == []
    assert len(profile_codes("signalling")) > 10
    with pytest.raises(KeyError):
        profile_codes("nonsense")
