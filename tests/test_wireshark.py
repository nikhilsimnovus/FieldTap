"""The claim the product is sold on: FieldTap output decodes in stock
Wireshark. Skipped when tshark is not installed; CI installs it."""

import pytest

from fieldtap import fixtures, pipeline, kpi
from fieldtap import tshark as tshark_mod
from fieldtap.diag.transport import FileTransport
from fieldtap.output.sinks import GsmtapPcapSink, PcapngSink

TSHARK = tshark_mod.find_tshark()
pytestmark = pytest.mark.skipif(TSHARK is None, reason="tshark not installed")


@pytest.fixture(scope="module")
def outputs(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("ws")
    records, expected = fixtures.build_corpus()
    qmdl = tmp / "synthetic.qmdl"
    qmdl.write_bytes(fixtures.build_qmdl(records))
    pcapng = str(tmp / "out.pcapng")
    gsm = str(tmp / "out_gsmtap.pcap")
    pipeline.run(FileTransport(str(qmdl)), [PcapngSink(pcapng), GsmtapPcapSink(gsm)])
    return pcapng, gsm, expected


def test_every_message_decodes_with_expected_dissector_and_info(outputs):
    pcapng, _, expected = outputs
    rows = tshark_mod.fields(pcapng, ["frame.number", "frame.protocols", "_ws.col.Info", "exported_pdu.p2p_dir"], tshark=TSHARK)
    assert len(rows) == len(expected)
    for exp, (number, protocols, info_col, p2p) in zip(expected, rows):
        proto = exp.dissector.split(".")[0].replace("-", "_")
        assert proto in protocols.replace("-", "_"), (number, exp.dissector, protocols)
        if exp.wireshark_info:
            assert exp.wireshark_info in info_col, (number, exp.wireshark_info, info_col)
        assert p2p == ("0" if exp.direction == "ul" else "1")


def test_no_malformed_frames(outputs):
    pcapng, _, _ = outputs
    assert tshark_mod.malformed(pcapng, tshark=TSHARK) == []


def test_protocol_column_names_rrc_channel(outputs):
    pcapng, _, expected = outputs
    rows = tshark_mod.fields(pcapng, ["_ws.col.Protocol"], tshark=TSHARK)
    for exp, (proto,) in zip(expected, rows):
        if exp.layer == "rrc" and exp.dissector != "data":
            assert "RRC" in proto, (exp, proto)


def test_packet_comments_visible_to_wireshark(outputs):
    pcapng, _, _ = outputs
    rows = tshark_mod.fields(pcapng, ["frame.comment"], display_filter='frame.comment contains "PCI 245"', tshark=TSHARK)
    assert len(rows) == 5


def test_gsmtap_pcap_reproduces_nov_2025_baseline(outputs):
    _, gsm, expected = outputs
    summary = tshark_mod.protocol_summary(gsm, tshark=TSHARK)
    assert summary["raw:ip:udp:gsmtap:lte_rrc"] == sum(1 for e in expected if e.rat == "lte" and e.layer == "rrc")
    assert summary["raw:ip:udp:gsmtap:nas-eps"] == sum(1 for e in expected if e.rat == "lte" and e.layer == "nas")
    assert tshark_mod.malformed(gsm, tshark=TSHARK) == []


def test_kpi_fields_exist_in_this_wireshark():
    """The KPI extractor names Wireshark fields; make sure they are real."""
    import subprocess
    out = subprocess.run([TSHARK, "-G", "fields"], capture_output=True, text=True).stdout
    names = set()
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) > 2 and parts[0] == "F":
            names.add(parts[2])
    for field in kpi.FIELDS:
        if field.startswith(("lte-rrc.", "nr-rrc.")):
            assert field in names, field
