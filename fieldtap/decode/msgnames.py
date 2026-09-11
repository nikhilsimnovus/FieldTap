"""Message names and directions without an ASN.1 decoder.

Two uses:

* NAS message types are plain octets (TS 24.301 / TS 24.501), so the name and
  the direction come straight from the header. The direction table lets the
  decoder check the log code's claimed direction against the message itself.
* RRC messages are PER encoded, but the outermost CHOICE index is the first
  few bits of the payload, which is enough to name the message for the
  call-flow view. Wireshark remains the authority for everything else.
"""

from __future__ import annotations

from typing import Optional, Tuple

# ---------------------------------------------------------------- NAS (24.301 / 24.501)

# (sublayer, message type) -> (name, direction)
NAS_MESSAGES = {
    # EPS mobility management
    ("emm", 0x41): ("Attach request", "ul"), ("emm", 0x42): ("Attach accept", "dl"),
    ("emm", 0x43): ("Attach complete", "ul"), ("emm", 0x44): ("Attach reject", "dl"),
    ("emm", 0x45): ("Detach request", "both"), ("emm", 0x46): ("Detach accept", "both"),
    ("emm", 0x48): ("Tracking area update request", "ul"), ("emm", 0x49): ("Tracking area update accept", "dl"),
    ("emm", 0x4A): ("Tracking area update complete", "ul"), ("emm", 0x4B): ("Tracking area update reject", "dl"),
    ("emm", 0x4C): ("Extended service request", "ul"), ("emm", 0x4D): ("Control plane service request", "ul"),
    ("emm", 0x4E): ("Service reject", "dl"), ("emm", 0x4F): ("Service accept", "dl"),
    ("emm", 0x50): ("GUTI reallocation command", "dl"), ("emm", 0x51): ("GUTI reallocation complete", "ul"),
    ("emm", 0x52): ("Authentication request", "dl"), ("emm", 0x53): ("Authentication response", "ul"),
    ("emm", 0x54): ("Authentication reject", "dl"), ("emm", 0x55): ("Identity request", "dl"),
    ("emm", 0x56): ("Identity response", "ul"), ("emm", 0x5C): ("Authentication failure", "ul"),
    ("emm", 0x5D): ("Security mode command", "dl"), ("emm", 0x5E): ("Security mode complete", "ul"),
    ("emm", 0x5F): ("Security mode reject", "ul"), ("emm", 0x60): ("EMM status", "both"),
    ("emm", 0x61): ("EMM information", "dl"), ("emm", 0x62): ("Downlink NAS transport", "dl"),
    ("emm", 0x63): ("Uplink NAS transport", "ul"), ("emm", 0x64): ("CS service notification", "dl"),
    ("emm", 0x68): ("Downlink generic NAS transport", "dl"), ("emm", 0x69): ("Uplink generic NAS transport", "ul"),
    # EPS session management
    ("esm", 0xC1): ("Activate default EPS bearer context request", "dl"),
    ("esm", 0xC2): ("Activate default EPS bearer context accept", "ul"),
    ("esm", 0xC3): ("Activate default EPS bearer context reject", "ul"),
    ("esm", 0xC5): ("Activate dedicated EPS bearer context request", "dl"),
    ("esm", 0xC6): ("Activate dedicated EPS bearer context accept", "ul"),
    ("esm", 0xC7): ("Activate dedicated EPS bearer context reject", "ul"),
    ("esm", 0xC9): ("Modify EPS bearer context request", "dl"),
    ("esm", 0xCA): ("Modify EPS bearer context accept", "ul"),
    ("esm", 0xCB): ("Modify EPS bearer context reject", "ul"),
    ("esm", 0xCD): ("Deactivate EPS bearer context request", "dl"),
    ("esm", 0xCE): ("Deactivate EPS bearer context accept", "ul"),
    ("esm", 0xD0): ("PDN connectivity request", "ul"), ("esm", 0xD1): ("PDN connectivity reject", "dl"),
    ("esm", 0xD2): ("PDN disconnect request", "ul"), ("esm", 0xD3): ("PDN disconnect reject", "dl"),
    ("esm", 0xD4): ("Bearer resource allocation request", "ul"),
    ("esm", 0xD5): ("Bearer resource allocation reject", "dl"),
    ("esm", 0xD6): ("Bearer resource modification request", "ul"),
    ("esm", 0xD7): ("Bearer resource modification reject", "dl"),
    ("esm", 0xD9): ("ESM information request", "dl"), ("esm", 0xDA): ("ESM information response", "ul"),
    ("esm", 0xDB): ("ESM notification", "dl"), ("esm", 0xDC): ("ESM dummy message", "both"),
    ("esm", 0xE8): ("ESM status", "both"), ("esm", 0xE9): ("Remote UE report", "ul"),
    ("esm", 0xEA): ("Remote UE report response", "dl"), ("esm", 0xEB): ("ESM data transport", "both"),
    # 5GS mobility management
    ("5gmm", 0x41): ("Registration request", "ul"), ("5gmm", 0x42): ("Registration accept", "dl"),
    ("5gmm", 0x43): ("Registration complete", "ul"), ("5gmm", 0x44): ("Registration reject", "dl"),
    ("5gmm", 0x45): ("Deregistration request (UE originating)", "ul"),
    ("5gmm", 0x46): ("Deregistration accept (UE originating)", "dl"),
    ("5gmm", 0x47): ("Deregistration request (UE terminated)", "dl"),
    ("5gmm", 0x48): ("Deregistration accept (UE terminated)", "ul"),
    ("5gmm", 0x4C): ("Service request", "ul"), ("5gmm", 0x4D): ("Service reject", "dl"),
    ("5gmm", 0x4E): ("Service accept", "dl"), ("5gmm", 0x4F): ("Control plane service request", "ul"),
    ("5gmm", 0x50): ("Network slice-specific authentication command", "dl"),
    ("5gmm", 0x51): ("Network slice-specific authentication complete", "ul"),
    ("5gmm", 0x52): ("Network slice-specific authentication result", "dl"),
    ("5gmm", 0x54): ("Configuration update command", "dl"),
    ("5gmm", 0x55): ("Configuration update complete", "ul"),
    ("5gmm", 0x56): ("Authentication request", "dl"), ("5gmm", 0x57): ("Authentication response", "ul"),
    ("5gmm", 0x58): ("Authentication reject", "dl"), ("5gmm", 0x59): ("Authentication failure", "ul"),
    ("5gmm", 0x5A): ("Authentication result", "dl"), ("5gmm", 0x5B): ("Identity request", "dl"),
    ("5gmm", 0x5C): ("Identity response", "ul"), ("5gmm", 0x5D): ("Security mode command", "dl"),
    ("5gmm", 0x5E): ("Security mode complete", "ul"), ("5gmm", 0x5F): ("Security mode reject", "ul"),
    ("5gmm", 0x64): ("5GMM status", "both"), ("5gmm", 0x65): ("Notification", "dl"),
    ("5gmm", 0x66): ("Notification response", "ul"), ("5gmm", 0x67): ("UL NAS transport", "ul"),
    ("5gmm", 0x68): ("DL NAS transport", "dl"),
    # 5GS session management
    ("5gsm", 0xC1): ("PDU session establishment request", "ul"),
    ("5gsm", 0xC2): ("PDU session establishment accept", "dl"),
    ("5gsm", 0xC3): ("PDU session establishment reject", "dl"),
    ("5gsm", 0xC5): ("PDU session authentication command", "dl"),
    ("5gsm", 0xC6): ("PDU session authentication complete", "ul"),
    ("5gsm", 0xC7): ("PDU session authentication result", "dl"),
    ("5gsm", 0xC9): ("PDU session modification request", "ul"),
    ("5gsm", 0xCA): ("PDU session modification reject", "dl"),
    ("5gsm", 0xCB): ("PDU session modification command", "dl"),
    ("5gsm", 0xCC): ("PDU session modification complete", "ul"),
    ("5gsm", 0xCD): ("PDU session modification command reject", "ul"),
    ("5gsm", 0xD1): ("PDU session release request", "ul"),
    ("5gsm", 0xD2): ("PDU session release reject", "dl"),
    ("5gsm", 0xD3): ("PDU session release command", "dl"),
    ("5gsm", 0xD4): ("PDU session release complete", "ul"),
    ("5gsm", 0xD6): ("5GSM status", "both"),
}


def nas_message(sublayer: str, msg_type: int) -> Tuple[Optional[str], Optional[str]]:
    entry = NAS_MESSAGES.get((sublayer, msg_type))
    if entry is None:
        return None, None
    name, direction = entry
    return name, (None if direction == "both" else direction)


# ---------------------------------------------------------------- RRC outer CHOICE peek

class _Bits:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def read(self, n: int) -> int:
        value = 0
        for _ in range(n):
            byte = self.pos >> 3
            if byte >= len(self.data):
                raise ValueError("short")
            bit = (self.data[byte] >> (7 - (self.pos & 7))) & 1
            value = (value << 1) | bit
            self.pos += 1
        return value


LTE_DL_DCCH = ["csfbParametersResponseCDMA2000", "dlInformationTransfer",
               "handoverFromEUTRAPreparationRequest", "mobilityFromEUTRACommand",
               "rrcConnectionReconfiguration", "rrcConnectionRelease", "securityModeCommand",
               "ueCapabilityEnquiry", "counterCheck", "ueInformationRequest",
               "loggedMeasurementConfiguration", "rnReconfiguration", "rrcConnectionResume",
               "spare3", "spare2", "spare1"]
LTE_UL_DCCH = ["csfbParametersRequestCDMA2000", "measurementReport",
               "rrcConnectionReconfigurationComplete", "rrcConnectionReestablishmentComplete",
               "rrcConnectionSetupComplete", "securityModeComplete", "securityModeFailure",
               "ueCapabilityInformation", "ulHandoverPreparationTransfer", "ulInformationTransfer",
               "counterCheckResponse", "ueInformationResponse", "proximityIndication",
               "rnReconfigurationComplete", "mbmsCountingResponse", "interFreqRSTDMeasurementIndication"]
LTE_DL_CCCH = ["rrcConnectionReestablishment", "rrcConnectionReestablishmentReject",
               "rrcConnectionReject", "rrcConnectionSetup"]
LTE_UL_CCCH = ["rrcConnectionReestablishmentRequest", "rrcConnectionRequest"]
LTE_BCCH_DL_SCH = ["systemInformation", "systemInformationBlockType1"]

NR_DL_DCCH = ["rrcReconfiguration", "rrcResume", "rrcRelease", "rrcReestablishment",
              "securityModeCommand", "dlInformationTransfer", "ueCapabilityEnquiry", "counterCheck",
              "mobilityFromNRCommand", "dlDedicatedMessageSegment", "ueInformationRequest",
              "dlInformationTransferMRDC", "loggedMeasurementConfiguration", "spare3", "spare2", "spare1"]
NR_UL_DCCH = ["measurementReport", "rrcReconfigurationComplete", "rrcSetupComplete",
              "rrcReestablishmentComplete", "rrcResumeComplete", "securityModeComplete",
              "securityModeFailure", "ulInformationTransfer", "locationMeasurementIndication",
              "ueCapabilityInformation", "counterCheckResponse", "ueAssistanceInformation",
              "failureInformation", "ulInformationTransferMRDC", "scgFailureInformation",
              "scgFailureInformationEUTRA"]
NR_DL_CCCH = ["rrcReject", "rrcSetup", "spare2", "spare1"]
NR_UL_CCCH = ["rrcSetupRequest", "rrcResumeRequest", "rrcReestablishmentRequest", "rrcSystemInfoRequest"]
NR_UL_CCCH1 = ["rrcResumeRequest1", "spare3", "spare2", "spare1"]
NR_BCCH_DL_SCH = ["systemInformation", "systemInformationBlockType1"]
NR_PCCH = ["paging", "spare1"]

# channel key -> (choice bits after the c1 bit, name table) ; None bits = fixed name
_RRC_TABLES = {
    ("lte", "DL_DCCH"): (4, LTE_DL_DCCH), ("lte", "UL_DCCH"): (4, LTE_UL_DCCH),
    ("lte", "DL_CCCH"): (2, LTE_DL_CCCH), ("lte", "UL_CCCH"): (1, LTE_UL_CCCH),
    ("lte", "BCCH_DL_SCH"): (1, LTE_BCCH_DL_SCH), ("lte", "PCCH"): (0, ["paging"]),
    ("lte", "BCCH_BCH"): (None, ["masterInformationBlock"]),
    ("nr", "DL_DCCH"): (4, NR_DL_DCCH), ("nr", "UL_DCCH"): (4, NR_UL_DCCH),
    ("nr", "DL_CCCH"): (2, NR_DL_CCCH), ("nr", "UL_CCCH"): (2, NR_UL_CCCH),
    ("nr", "UL_CCCH1"): (2, NR_UL_CCCH1), ("nr", "BCCH_DL_SCH"): (1, NR_BCCH_DL_SCH),
    ("nr", "PCCH"): (1, NR_PCCH), ("nr", "BCCH_BCH"): (1, ["mib", "messageClassExtension"]),
    ("nr", "RRC_RECONFIGURATION"): (None, ["rrcReconfiguration"]),
    ("nr", "RRC_RECONFIGURATION_COMPLETE"): (None, ["rrcReconfigurationComplete"]),
    ("nr", "UE_MRDC_CAPABILITY"): (None, ["ue-MRDC-Capability"]),
    ("nr", "UE_NR_CAPABILITY"): (None, ["ue-NR-Capability"]),
    ("nr", "UE_RADIO_ACCESS_CAP_INFO"): (None, ["ueRadioAccessCapabilityInformation"]),
    ("nr", "UE_RADIO_PAGING_INFO"): (None, ["ueRadioPagingInformation"]),
}


def rrc_message_name(rat: str, channel_key: str, payload: bytes) -> Optional[str]:
    """Best-effort name from the outer CHOICE. None when it cannot be read."""
    table = _RRC_TABLES.get((rat, channel_key))
    if table is None or not payload:
        return None
    bits, names = table
    if bits is None:
        return names[0]
    try:
        reader = _Bits(payload)
        if (rat, channel_key) == ("nr", "BCCH_BCH"):
            return names[reader.read(1)]
        if reader.read(1) != 0:          # not c1 -> messageClassExtension
            return "messageClassExtension"
        if bits == 0:
            return names[0]
        index = reader.read(bits)
        return names[index] if index < len(names) else None
    except (ValueError, IndexError):
        return None
