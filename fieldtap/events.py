"""Events: what an RF engineer scans a session for.

Fed from the live decode stream (DecodedMessage / CellInfo objects) or rebuilt
from a FieldTap pcapng, the detector turns the RRC/NAS message sequence into
a timeline of procedures and outcomes: connection attempts and setups,
releases, re-establishments (radio link failure), handovers (cell change
while connected), attach / registration / TAU / service requests with their
accept or reject cause, bearer and PDU session events, and serving-cell
changes. It also keeps the counters the report turns into success rates and
setup times. No ASN.1 is decoded here; message names come from the outer
CHOICE peek and NAS causes from fixed octet positions (TS 24.301 / 24.501).
tshark, when present, adds handover targets and RRC causes afterwards.
"""

from __future__ import annotations

import csv
import io
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from types import SimpleNamespace
from typing import Callable, Optional

from .decode.records import CellInfo, DecodedMessage

# --- cause tables (subset of TS 24.301 annex A / TS 24.501 annex A) --------------------------

EMM_CAUSES = {
    2: "IMSI unknown in HSS", 3: "Illegal UE", 5: "IMEI not accepted", 6: "Illegal ME",
    7: "EPS services not allowed", 8: "EPS and non-EPS services not allowed",
    9: "UE identity cannot be derived by the network", 10: "Implicitly detached",
    11: "PLMN not allowed", 12: "Tracking area not allowed", 13: "Roaming not allowed in this tracking area",
    14: "EPS services not allowed in this PLMN", 15: "No suitable cells in tracking area",
    16: "MSC temporarily not reachable", 17: "Network failure", 18: "CS domain not available",
    19: "ESM failure", 20: "MAC failure", 21: "Synch failure", 22: "Congestion",
    23: "UE security capabilities mismatch", 24: "Security mode rejected, unspecified",
    25: "Not authorized for this CSG", 26: "Non-EPS authentication unacceptable",
    31: "Redirection to 5GCN required", 35: "Requested service option not authorized in this PLMN",
    39: "CS service temporarily not available", 40: "No EPS bearer context activated",
    42: "Severe network failure", 78: "PLMN not allowed to operate at the present UE location",
    95: "Semantically incorrect message", 96: "Invalid mandatory information",
    97: "Message type non-existent or not implemented", 98: "Message type not compatible with the protocol state",
    99: "Information element non-existent or not implemented", 100: "Conditional IE error",
    101: "Message not compatible with the protocol state", 111: "Protocol error, unspecified",
}
ESM_CAUSES = {
    8: "Operator determined barring", 26: "Insufficient resources", 27: "Missing or unknown APN",
    28: "Unknown PDN type", 29: "User authentication or authorization failed",
    30: "Request rejected by Serving GW or PDN GW", 31: "Request rejected, unspecified",
    32: "Service option not supported", 33: "Requested service option not subscribed",
    34: "Service option temporarily out of order", 35: "PTI already in use", 36: "Regular deactivation",
    37: "EPS QoS not accepted", 38: "Network failure", 39: "Reactivation requested",
    41: "Semantic error in the TFT operation", 42: "Syntactical error in the TFT operation",
    43: "Invalid EPS bearer identity", 44: "Semantic errors in packet filter(s)",
    45: "Syntactical errors in packet filter(s)", 47: "PTI mismatch", 49: "Last PDN disconnection not allowed",
    50: "PDN type IPv4 only allowed", 51: "PDN type IPv6 only allowed",
    52: "Single address bearers only allowed", 53: "ESM information not received",
    54: "PDN connection does not exist", 55: "Multiple PDN connections for a given APN not allowed",
    56: "Collision with network initiated request", 57: "PDN type IPv4v6 only allowed",
    58: "PDN type non IP only allowed", 59: "Unsupported QCI value", 60: "Bearer handling not supported",
    61: "PDN type Ethernet only allowed", 65: "Maximum number of EPS bearers reached",
    66: "Requested APN not supported in current RAT and PLMN combination", 81: "Invalid PTI value",
    95: "Semantically incorrect message", 96: "Invalid mandatory information",
    97: "Message type non-existent or not implemented", 98: "Message type not compatible with the protocol state",
    99: "Information element non-existent or not implemented", 100: "Conditional IE error",
    101: "Message not compatible with the protocol state", 111: "Protocol error, unspecified",
    112: "APN restriction value incompatible with active EPS bearer context",
    113: "Multiple accesses to a PDN connection not allowed",
}
MM5G_CAUSES = {
    3: "Illegal UE", 5: "PEI not accepted", 6: "Illegal ME", 7: "5GS services not allowed",
    9: "UE identity cannot be derived by the network", 10: "Implicitly de-registered",
    11: "PLMN not allowed", 12: "Tracking area not allowed", 13: "Roaming not allowed in this tracking area",
    15: "No suitable cells in tracking area", 20: "MAC failure", 21: "Synch failure", 22: "Congestion",
    23: "UE security capabilities mismatch", 24: "Security mode rejected, unspecified",
    26: "Non-5G authentication unacceptable", 27: "N1 mode not allowed", 28: "Restricted service area",
    31: "Redirection to EPC required", 43: "LADN not available", 62: "No network slices available",
    65: "Maximum number of PDU sessions reached", 67: "Insufficient resources for specific slice and DNN",
    69: "Insufficient resources for specific slice", 71: "ngKSI already in use",
    72: "Non-3GPP access to 5GCN not allowed", 73: "Serving network not authorized",
    74: "Temporarily not authorized for this SNPN", 75: "Permanently not authorized for this SNPN",
    76: "Not authorized for this CAG or authorized for CAG cells only", 77: "Wireline access area not allowed",
    78: "PLMN not allowed to operate at the present UE location", 79: "UAS services not allowed",
    90: "Payload was not forwarded", 91: "DNN not supported or not subscribed in the slice",
    92: "Insufficient user-plane resources for the PDU session",
    95: "Semantically incorrect message", 96: "Invalid mandatory information",
    97: "Message type non-existent or not implemented", 98: "Message type not compatible with the protocol state",
    99: "Information element non-existent or not implemented", 100: "Conditional IE error",
    101: "Message not compatible with the protocol state", 111: "Protocol error, unspecified",
}
SM5G_CAUSES = {
    8: "Operator determined barring", 26: "Insufficient resources", 27: "Missing or unknown DNN",
    28: "Unknown PDU session type", 29: "User authentication or authorization failed",
    31: "Request rejected, unspecified", 32: "Service option not supported",
    33: "Requested service option not subscribed", 35: "PTI already in use", 36: "Regular deactivation",
    38: "Network failure", 39: "Reactivation requested", 41: "Semantic error in the TFT operation",
    42: "Syntactical error in the TFT operation", 43: "Invalid PDU session identity",
    44: "Semantic errors in packet filter(s)", 45: "Syntactical error in packet filter(s)",
    46: "Out of LADN service area", 47: "PTI mismatch", 50: "PDU session type IPv4 only allowed",
    51: "PDU session type IPv6 only allowed", 54: "PDU session does not exist",
    57: "PDU session type IPv4v6 only allowed", 58: "PDU session type Unstructured only allowed",
    59: "Unsupported 5QI value", 61: "PDU session type Ethernet only allowed",
    67: "Insufficient resources for specific slice and DNN", 68: "Not supported SSC mode",
    69: "Insufficient resources for specific slice", 70: "Missing or unknown DNN in a slice",
    81: "Invalid PTI value", 82: "Maximum data rate per UE for user-plane integrity protection is too low",
    83: "Semantic error in the QoS operation", 84: "Syntactical error in the QoS operation",
    85: "Invalid mapped EPS bearer identity", 95: "Semantically incorrect message",
    96: "Invalid mandatory information", 97: "Message type non-existent or not implemented",
    98: "Message type not compatible with the protocol state",
    99: "Information element non-existent or not implemented", 100: "Conditional IE error",
    101: "Message not compatible with the protocol state", 111: "Protocol error, unspecified",
}
CAUSE_TABLES = {"emm": EMM_CAUSES, "esm": ESM_CAUSES, "5gmm": MM5G_CAUSES, "5gsm": SM5G_CAUSES}

# NAS messages whose first IE after the type is a cause octet
_CAUSE_MESSAGES = {
    ("emm", 0x44): "Attach reject", ("emm", 0x4B): "Tracking area update reject",
    ("emm", 0x4E): "Service reject",
    ("esm", 0xD1): "PDN connectivity reject", ("esm", 0xD3): "PDN disconnect reject",
    ("esm", 0xD5): "Bearer resource allocation reject", ("esm", 0xD7): "Bearer resource modification reject",
    ("esm", 0xCD): "Deactivate EPS bearer context request",
    ("5gmm", 0x44): "Registration reject", ("5gmm", 0x4D): "Service reject",
    ("5gsm", 0xC3): "PDU session establishment reject", ("5gsm", 0xCA): "PDU session modification reject",
    ("5gsm", 0xD2): "PDU session release reject", ("5gsm", 0xD3): "PDU session release command",
}


def nas_cause(sublayer: str, msg_type: Optional[int], security_header: int, payload: bytes):
    """-> (cause number, text) for reject-type messages, else None."""
    if msg_type is None or (sublayer, msg_type) not in _CAUSE_MESSAGES:
        return None
    p = payload
    if sublayer in ("emm", "esm") and security_header in (1, 2, 3, 4):
        p = p[6:]
    elif sublayer in ("5gmm", "5gsm") and security_header in (1, 2, 3, 4):
        p = p[7:]
    index = {"emm": 2, "esm": 3, "5gmm": 3, "5gsm": 4}[sublayer]
    if len(p) <= index:
        return None
    cause = p[index]
    return cause, CAUSE_TABLES[sublayer].get(cause, "cause #%d" % cause)


# --- the event object ----------------------------------------------------------------------

@dataclass
class Event:
    when: Optional[datetime]
    rat: str                     # lte | nr | -
    kind: str                    # machine key, e.g. rrc_setup, attach_reject, handover
    severity: str                # info | ok | warn | error
    title: str                   # human line
    detail: str = ""
    frame: Optional[int] = None  # pcapng frame number of the triggering message
    fields: dict = field(default_factory=dict)

    @property
    def when_iso(self) -> str:
        return self.when.astimezone(timezone.utc).isoformat(timespec="milliseconds") if self.when else ""


COLUMNS = ["time_utc", "rat", "kind", "severity", "title", "detail", "frame", "pci", "arfcn", "cause", "setup_ms"]


def to_csv(events: list) -> str:
    out = io.StringIO()
    w = csv.writer(out)
    w.writerow(COLUMNS)
    for e in events:
        w.writerow([e.when_iso, e.rat, e.kind, e.severity, e.title, e.detail, e.frame or "",
                    e.fields.get("pci", ""), e.fields.get("arfcn", ""), e.fields.get("cause", ""),
                    e.fields.get("setup_ms", "")])
    return out.getvalue()


def read_csv(path: str) -> list:
    events = []
    with open(path, encoding="utf-8", newline="") as fh:
        for row in csv.DictReader(fh):
            when = datetime.fromisoformat(row["time_utc"]) if row.get("time_utc") else None
            fields = {k: row[k] for k in ("pci", "arfcn", "cause", "setup_ms") if row.get(k)}
            events.append(Event(when, row["rat"], row["kind"], row["severity"], row["title"], row.get("detail", ""),
                                int(row["frame"]) if row.get("frame") else None, fields))
    return events


# --- detection rules ---------------------------------------------------------------------------

# (rat, layer, name) -> (kind, severity, title). Names are the outer-CHOICE / NAS names from msgnames.py.
_RULES = {
    # LTE RRC
    ("lte", "rrc", "rrcConnectionRequest"): ("rrc_attempt", "info", "RRC connection request"),
    ("lte", "rrc", "rrcConnectionSetup"): ("rrc_setup", "ok", "RRC connection setup"),
    ("lte", "rrc", "rrcConnectionSetupComplete"): ("rrc_connected", "ok", "RRC connected"),
    ("lte", "rrc", "rrcConnectionReject"): ("rrc_reject", "error", "RRC connection rejected"),
    ("lte", "rrc", "rrcConnectionRelease"): ("rrc_release", "info", "RRC connection released"),
    ("lte", "rrc", "rrcConnectionReestablishmentRequest"): ("reestablishment_attempt", "error", "Radio link failure: re-establishment request"),
    ("lte", "rrc", "rrcConnectionReestablishment"): ("reestablishment", "warn", "RRC re-establishment"),
    ("lte", "rrc", "rrcConnectionReestablishmentReject"): ("reestablishment_reject", "error", "RRC re-establishment rejected"),
    ("lte", "rrc", "rrcConnectionReconfiguration"): ("reconfiguration", "info", "RRC connection reconfiguration"),
    ("lte", "rrc", "rrcConnectionReconfigurationComplete"): ("reconfiguration_complete", "info", "RRC reconfiguration complete"),
    ("lte", "rrc", "securityModeCommand"): ("security_mode", "info", "AS security mode command"),
    ("lte", "rrc", "measurementReport"): ("meas_report", "info", "Measurement report"),
    ("lte", "rrc", "ueCapabilityEnquiry"): ("capability", "info", "UE capability enquiry"),
    ("lte", "rrc", "mobilityFromEUTRACommand"): ("irat_mobility", "warn", "Mobility from E-UTRA command (inter-RAT)"),
    ("lte", "rrc", "systemInformationBlockType1"): ("sib1", "info", "SIB1"),
    ("lte", "rrc", "paging"): ("paging", "info", "Paging"),
    # NR RRC
    ("nr", "rrc", "rrcSetupRequest"): ("rrc_attempt", "info", "RRC setup request"),
    ("nr", "rrc", "rrcSetup"): ("rrc_setup", "ok", "RRC setup"),
    ("nr", "rrc", "rrcSetupComplete"): ("rrc_connected", "ok", "RRC connected"),
    ("nr", "rrc", "rrcReject"): ("rrc_reject", "error", "RRC rejected"),
    ("nr", "rrc", "rrcRelease"): ("rrc_release", "info", "RRC release"),
    ("nr", "rrc", "rrcResumeRequest"): ("rrc_resume_attempt", "info", "RRC resume request"),
    ("nr", "rrc", "rrcResume"): ("rrc_resume", "ok", "RRC resume"),
    ("nr", "rrc", "rrcReestablishmentRequest"): ("reestablishment_attempt", "error", "Radio link failure: re-establishment request"),
    ("nr", "rrc", "rrcReestablishment"): ("reestablishment", "warn", "RRC re-establishment"),
    ("nr", "rrc", "rrcReconfiguration"): ("reconfiguration", "info", "RRC reconfiguration"),
    ("nr", "rrc", "rrcReconfigurationComplete"): ("reconfiguration_complete", "info", "RRC reconfiguration complete"),
    ("nr", "rrc", "securityModeCommand"): ("security_mode", "info", "AS security mode command"),
    ("nr", "rrc", "measurementReport"): ("meas_report", "info", "Measurement report"),
    ("nr", "rrc", "ueCapabilityEnquiry"): ("capability", "info", "UE capability enquiry"),
    ("nr", "rrc", "mobilityFromNRCommand"): ("irat_mobility", "warn", "Mobility from NR command (inter-RAT)"),
    ("nr", "rrc", "scgFailureInformation"): ("scg_failure", "error", "SCG failure information"),
    ("nr", "rrc", "scgFailureInformationEUTRA"): ("scg_failure", "error", "SCG failure information (EUTRA)"),
    ("nr", "rrc", "failureInformation"): ("failure_info", "error", "Failure information"),
    ("nr", "rrc", "systemInformationBlockType1"): ("sib1", "info", "SIB1"),
    ("nr", "rrc", "paging"): ("paging", "info", "Paging"),
    # LTE NAS
    ("lte", "nas", "Attach request"): ("attach_attempt", "info", "Attach request"),
    ("lte", "nas", "Attach accept"): ("attach_accept", "ok", "Attach accept"),
    ("lte", "nas", "Attach complete"): ("attach_complete", "ok", "Attach complete"),
    ("lte", "nas", "Attach reject"): ("attach_reject", "error", "Attach reject"),
    ("lte", "nas", "Detach request"): ("detach", "warn", "Detach request"),
    ("lte", "nas", "Tracking area update request"): ("tau_attempt", "info", "Tracking area update request"),
    ("lte", "nas", "Tracking area update accept"): ("tau_accept", "ok", "Tracking area update accept"),
    ("lte", "nas", "Tracking area update reject"): ("tau_reject", "error", "Tracking area update reject"),
    ("lte", "nas", "Service request"): ("service_attempt", "info", "Service request"),
    ("lte", "nas", "Extended service request"): ("service_attempt", "info", "Extended service request"),
    ("lte", "nas", "Service accept"): ("service_accept", "ok", "Service accept"),
    ("lte", "nas", "Service reject"): ("service_reject", "error", "Service reject"),
    ("lte", "nas", "Authentication request"): ("auth", "info", "Authentication request"),
    ("lte", "nas", "Authentication failure"): ("auth_failure", "error", "Authentication failure"),
    ("lte", "nas", "Authentication reject"): ("auth_reject", "error", "Authentication reject"),
    ("lte", "nas", "Security mode command"): ("nas_security", "info", "NAS security mode command"),
    ("lte", "nas", "Security mode reject"): ("nas_security_reject", "error", "NAS security mode reject"),
    ("lte", "nas", "Identity request"): ("identity", "info", "Identity request"),
    ("lte", "nas", "GUTI reallocation command"): ("guti", "info", "GUTI reallocation"),
    ("lte", "nas", "EMM information"): ("emm_info", "info", "EMM information"),
    ("lte", "nas", "PDN connectivity request"): ("pdn_attempt", "info", "PDN connectivity request"),
    ("lte", "nas", "PDN connectivity reject"): ("pdn_reject", "error", "PDN connectivity reject"),
    ("lte", "nas", "Activate default EPS bearer context request"): ("bearer_setup", "ok", "Default EPS bearer activated"),
    ("lte", "nas", "Activate dedicated EPS bearer context request"): ("bearer_dedicated", "ok", "Dedicated EPS bearer activated"),
    ("lte", "nas", "Deactivate EPS bearer context request"): ("bearer_release", "warn", "EPS bearer deactivated"),
    ("lte", "nas", "PDN disconnect request"): ("pdn_disconnect", "info", "PDN disconnect request"),
    ("lte", "nas", "ESM status"): ("esm_status", "warn", "ESM status"),
    ("lte", "nas", "EMM status"): ("emm_status", "warn", "EMM status"),
    # NR NAS
    ("nr", "nas", "Registration request"): ("registration_attempt", "info", "Registration request"),
    ("nr", "nas", "Registration accept"): ("registration_accept", "ok", "Registration accept"),
    ("nr", "nas", "Registration complete"): ("registration_complete", "ok", "Registration complete"),
    ("nr", "nas", "Registration reject"): ("registration_reject", "error", "Registration reject"),
    ("nr", "nas", "Deregistration request (UE originating)"): ("deregistration", "warn", "Deregistration request (UE)"),
    ("nr", "nas", "Deregistration request (UE terminated)"): ("deregistration", "warn", "Deregistration request (network)"),
    ("nr", "nas", "Service request"): ("service_attempt", "info", "Service request"),
    ("nr", "nas", "Service accept"): ("service_accept", "ok", "Service accept"),
    ("nr", "nas", "Service reject"): ("service_reject", "error", "Service reject"),
    ("nr", "nas", "Authentication request"): ("auth", "info", "Authentication request"),
    ("nr", "nas", "Authentication failure"): ("auth_failure", "error", "Authentication failure"),
    ("nr", "nas", "Authentication reject"): ("auth_reject", "error", "Authentication reject"),
    ("nr", "nas", "Security mode command"): ("nas_security", "info", "NAS security mode command"),
    ("nr", "nas", "Security mode reject"): ("nas_security_reject", "error", "NAS security mode reject"),
    ("nr", "nas", "Identity request"): ("identity", "info", "Identity request"),
    ("nr", "nas", "Configuration update command"): ("config_update", "info", "Configuration update command"),
    ("nr", "nas", "PDU session establishment request"): ("pdu_attempt", "info", "PDU session establishment request"),
    ("nr", "nas", "PDU session establishment accept"): ("pdu_accept", "ok", "PDU session established"),
    ("nr", "nas", "PDU session establishment reject"): ("pdu_reject", "error", "PDU session establishment reject"),
    ("nr", "nas", "PDU session release request"): ("pdu_release", "info", "PDU session release request"),
    ("nr", "nas", "PDU session release command"): ("pdu_release", "warn", "PDU session release command"),
    ("nr", "nas", "PDU session modification reject"): ("pdu_modify_reject", "error", "PDU session modification reject"),
    ("nr", "nas", "5GMM status"): ("mm_status", "warn", "5GMM status"),
    ("nr", "nas", "5GSM status"): ("sm_status", "warn", "5GSM status"),
}

# kinds that are noise in a timeline unless asked for
LOW_PRIORITY = {"meas_report", "sib1", "paging", "reconfiguration_complete", "emm_info", "identity", "guti",
                "capability", "auth", "security_mode", "nas_security", "config_update"}

# attempt kind -> (success kinds, failure kinds) for rates and setup times
PROCEDURES = {
    "rrc": ("rrc_attempt", {"rrc_setup"}, {"rrc_reject"}),
    "attach": ("attach_attempt", {"attach_accept"}, {"attach_reject"}),
    "registration": ("registration_attempt", {"registration_accept"}, {"registration_reject"}),
    "tau": ("tau_attempt", {"tau_accept"}, {"tau_reject"}),
    "service": ("service_attempt", {"service_accept", "rrc_release", "bearer_setup"}, {"service_reject"}),
    "pdn": ("pdn_attempt", {"bearer_setup"}, {"pdn_reject"}),
    "pdu": ("pdu_attempt", {"pdu_accept"}, {"pdu_reject"}),
    "reestablishment": ("reestablishment_attempt", {"reestablishment"}, {"reestablishment_reject"}),
}


class EventDetector:
    """Stateful observer. Call observe() with every decoded object, in order."""

    def __init__(self, on_event: Optional[Callable[[Event], None]] = None):
        self.on_event = on_event
        self.events: list = []
        self.frame = 0                             # DecodedMessage count == pcapng frame number
        self.counts: dict = {}
        self._pending: dict = {}                   # procedure -> (attempt Event, attempt time)
        self._setup_times: dict = {}               # procedure -> [ms]
        self._connected_pci = {"lte": None, "nr": None}
        self._reconf_pci = {"lte": None, "nr": None}
        self._serving_key = None
        self.handovers = 0
        self.cell_changes = 0

    # -- input ---------------------------------------------------------------------

    def observe(self, obj) -> None:
        if isinstance(obj, DecodedMessage) or (hasattr(obj, "layer") and hasattr(obj, "payload")):
            self.frame += 1
            self._message(obj)
        elif isinstance(obj, CellInfo):
            self._cell(obj)

    def _emit(self, ev: Event) -> None:
        self.events.append(ev)
        self.counts[ev.kind] = self.counts.get(ev.kind, 0) + 1
        if self.on_event is not None:
            self.on_event(ev)

    def _message(self, m) -> None:
        name = m.name
        rat = m.rat
        pci = m.fields.get("pci")
        arfcn = m.fields.get("earfcn", m.fields.get("arfcn"))
        base_fields = {}
        if pci is not None:
            base_fields["pci"] = pci
        if arfcn is not None:
            base_fields["arfcn"] = arfcn
        # cell change while connected: the PCI on DCCH traffic moved
        if m.layer == "rrc" and pci is not None and m.channel.key in ("DL_DCCH", "UL_DCCH"):
            last = self._connected_pci[rat]
            if last is not None and last != pci:
                self.handovers += 1
                self._emit(Event(m.timestamp, rat, "handover", "ok",
                                 "Handover PCI %s -> %s" % (last, pci), "cell change while RRC connected",
                                 self.frame, {"pci": pci, "from_pci": last, "arfcn": arfcn}))
            self._connected_pci[rat] = pci
        if m.layer == "rrc" and name in ("rrcConnectionRelease", "rrcRelease"):
            self._connected_pci[rat] = None
        rule = _RULES.get((rat, m.layer, name))
        if rule is None:
            return
        kind, severity, title = rule
        detail = ""
        fields = dict(base_fields)
        if m.layer == "nas":
            cause = nas_cause(m.fields.get("sublayer", ""), m.fields.get("msg_type"),
                              m.fields.get("security_header", 0), m.payload)
            if cause is not None:
                fields["cause"] = cause[0]
                fields["cause_text"] = cause[1]
                detail = "cause #%d %s" % cause
        ev = Event(m.timestamp, rat, kind, severity, title, detail, self.frame, fields)
        self._track_procedure(ev)
        self._emit(ev)

    def _track_procedure(self, ev: Event) -> None:
        for proc, (attempt, successes, failures) in PROCEDURES.items():
            if ev.kind == attempt:
                self._pending[proc] = ev
            elif proc in self._pending and (ev.kind in successes or ev.kind in failures):
                start = self._pending.pop(proc)
                if ev.kind in successes and start.when and ev.when:
                    ms = (ev.when - start.when).total_seconds() * 1000.0
                    if 0 <= ms < 120000:
                        self._setup_times.setdefault(proc, []).append(ms)
                        ev.fields["setup_ms"] = round(ms, 1)
                        ev.detail = (ev.detail + "; " if ev.detail else "") + "%.0f ms after %s" % (ms, start.title.lower())

    def _cell(self, c: CellInfo) -> None:
        if c.kind != "serving_cell":
            return
        f = c.fields
        key = (f.get("plmn"), f.get("cell_id"), f.get("pci"), f.get("dl_earfcn"))
        if key == self._serving_key:
            return
        first = self._serving_key is None
        self._serving_key = key
        self.cell_changes += 0 if first else 1
        self._emit(Event(c.timestamp, c.rat, "serving_cell", "info",
                         "%s cell PLMN %s TAC %s eNB %s sector %s PCI %s EARFCN %s band %s" % (
                             "Serving" if first else "Serving cell changed:", f.get("plmn"), f.get("tac"),
                             f.get("enb_id"), f.get("sector"), f.get("pci"), f.get("dl_earfcn"), f.get("band")),
                         "", None, {"pci": f.get("pci"), "arfcn": f.get("dl_earfcn"), "plmn": f.get("plmn"),
                                    "cell_id": f.get("cell_id"), "tac": f.get("tac"), "band": f.get("band")}))

    # -- output --------------------------------------------------------------------

    def summary(self) -> dict:
        procs = {}
        for proc, (attempt, successes, failures) in PROCEDURES.items():
            a = self.counts.get(attempt, 0)
            s = sum(self.counts.get(k, 0) for k in successes if k != "rrc_release" and k != "bearer_setup" or proc in ("pdn",))
            if proc == "service":
                s = self.counts.get("service_accept", 0)
            f = sum(self.counts.get(k, 0) for k in failures)
            times = self._setup_times.get(proc, [])
            procs[proc] = {
                "attempts": a, "successes": s, "failures": f,
                "success_rate": (round(100.0 * s / a, 1) if a else None),
                "setup_ms_avg": (round(sum(times) / len(times), 1) if times else None),
                "setup_ms_max": (round(max(times), 1) if times else None),
            }
        return {
            "events": len(self.events),
            "by_kind": dict(sorted(self.counts.items())),
            "errors": sum(1 for e in self.events if e.severity == "error"),
            "warnings": sum(1 for e in self.events if e.severity == "warn"),
            "handovers": self.handovers,
            "cell_changes": self.cell_changes,
            "procedures": procs,
        }


# --- rebuilding from a pcapng --------------------------------------------------------------------

_COMMENT_RE = re.compile(r"^FieldTap (?P<rat>NR|LTE) (?P<layer>RRC|NAS) (?P<channel>\S+)(?: (?P<name>[^|]*?))?(?: \| |$)")
_PCI_RE = re.compile(r"\| PCI (\d+)")
_ARFCN_RE = re.compile(r"\| (?:EARFCN|NR-ARFCN) (\d+)")
_SEC_RE = re.compile(r"\| sec-hdr (\d+)")
_CHANNEL_KEYS = {"DL-DCCH": "DL_DCCH", "UL-DCCH": "UL_DCCH", "DL-CCCH": "DL_CCCH", "UL-CCCH": "UL_CCCH",
                 "BCCH-BCH": "BCCH_BCH", "BCCH-DL-SCH": "BCCH_DL_SCH", "PCCH": "PCCH"}


def _message_from_packet(pkt):
    """A DecodedMessage-shaped object from a FieldTap pcapng packet."""
    from .decode import nas as nas_mod
    from .output import exported_pdu
    m = _COMMENT_RE.match(pkt.comment or "")
    if m is None:
        return None
    rat, layer = m.group("rat").lower(), m.group("layer").lower()
    channel_label = m.group("channel")
    name = (m.group("name") or "").strip() or None
    options, payload = exported_pdu.parse(pkt.data)
    fields = {}
    pci = _PCI_RE.search(pkt.comment or "")
    if pci:
        fields["pci"] = int(pci.group(1))
    arfcn = _ARFCN_RE.search(pkt.comment or "")
    if arfcn:
        fields["earfcn" if rat == "lte" else "arfcn"] = int(arfcn.group(1))
    sec = _SEC_RE.search(pkt.comment or "")
    fields["security_header"] = int(sec.group(1)) if sec else 0
    if layer == "nas":
        sub = channel_label.lower()
        fields["sublayer"] = sub
        classify = nas_mod._classify_eps if rat == "lte" else nas_mod._classify_5gs
        try:
            _layer, _sec, msg_type = classify(payload)
        except Exception:
            msg_type = None
        fields["msg_type"] = msg_type
    channel = SimpleNamespace(key=_CHANNEL_KEYS.get(channel_label, channel_label.replace("-", "_")),
                              label=channel_label)
    return SimpleNamespace(rat=rat, layer=layer, name=name, fields=fields, payload=payload,
                           timestamp=pkt.when, channel=channel, direction=options.get("direction", "?"))


def from_pcapng(path: str, on_event: Optional[Callable[[Event], None]] = None) -> EventDetector:
    from .output.pcapng import read_packets
    det = EventDetector(on_event)
    for pkt in read_packets(path):
        msg = _message_from_packet(pkt)
        if msg is None:
            det.frame += 1
            continue
        det.observe(msg)
    return det


# --- tshark enrichment ---------------------------------------------------------------------------

_ENRICH_FIELDS = ["frame.number", "lte-rrc.targetPhysCellId", "lte-rrc.releaseCause", "lte-rrc.establishmentCause",
                  "lte-rrc.reestablishmentCause", "nr-rrc.establishmentCause", "nr-rrc.reestablishmentCause",
                  "nr-rrc.physCellId", "nr-rrc.reconfigurationWithSync_element", "lte-rrc.mobilityControlInfo_element"]


def enrich_with_tshark(events: list, pcapng: str, tshark: Optional[str] = None) -> int:
    """Add Wireshark-derived detail (handover targets, RRC causes) to events
    that have a frame number. Returns the number of events touched; 0 when
    tshark is unavailable."""
    from . import tshark as tshark_mod
    exe = tshark or tshark_mod.find_tshark()
    if not exe:
        return 0
    try:
        rows = tshark_mod.fields(pcapng, _ENRICH_FIELDS, tshark=exe)
    except RuntimeError:
        return 0
    by_frame = {}
    for r in rows:
        if r[0]:
            by_frame[int(r[0])] = r
    touched = 0
    for ev in events:
        if not ev.frame or ev.frame not in by_frame:
            continue
        r = by_frame[ev.frame]
        (_n, lte_target, lte_rel, lte_est, lte_reest, nr_est, nr_reest, nr_pci, nr_sync, lte_mci) = (r + [""] * 10)[:10]
        extra = []
        if ev.kind == "reconfiguration" and (lte_mci or nr_sync):
            target = lte_target or nr_pci
            ev.kind = "handover_command"
            ev.severity = "ok"
            ev.title = "Handover command" + (" to PCI %s" % target.split(",")[0] if target else "")
            ev.fields["target_pci"] = target
            touched += 1
        if ev.kind == "rrc_attempt" and (lte_est or nr_est):
            extra.append("cause %s" % (lte_est or nr_est))
        if ev.kind == "rrc_release" and lte_rel:
            extra.append("release cause %s" % lte_rel)
        if ev.kind == "reestablishment_attempt" and (lte_reest or nr_reest):
            extra.append("cause %s" % (lte_reest or nr_reest))
        if extra:
            ev.detail = (ev.detail + "; " if ev.detail else "") + ", ".join(extra)
            touched += 1
    return touched
