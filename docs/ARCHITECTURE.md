# Architecture

## The pipeline as it stands (Nov 2025, verified working)

    OnePlus handset
      -> Qualcomm diag port  (requires root / diag enablement)
      -> QCSuper             (HDLC framing, log mask config, log record extraction)
      -> GSMTAP over UDP     (payload type 13 = LTE RRC, port 4729)
      -> pcap / pcapng
      -> Wireshark           (lte_rrc built-in dissector; NR needed a third-party plugin)

Confirmed from the Nov 2025 capture: `Protocols in frame: ip:udp:gsmtap:lte_rrc`,
GSMTAP v2, UDP 4729 both directions. LTE decode works with stock Wireshark.
NR decode did not — it depended on the now-expired commercial dissector.

## The target pipeline

    Supported handset
      -> diag port
      -> FieldTap capture layer      (Phase 0 decides: QCSuper subprocess, or own client)
      -> FieldTap decode layer       (OURS - Phase 2, the differentiator)
      -> GSMTAP / pcap
      -> Wireshark built-in dissectors (nr-rrc, nas-5gs, lte-rrc)
      -> FieldTap product shell      (sessions, live view, KPIs, call flow)

The load-bearing design principle: **do not write an ASN.1 decoder.** Wireshark already
has correct, maintained, industry-trusted dissectors for `nr-rrc`, `nas-5gs` and `lte-rrc`.
FieldTap's job is to strip the Qualcomm diag wrapper and present the payload with the
right channel type so those dissectors take over. Decode quality then *is* Wireshark's
decode quality — which is precisely the claim the product is sold on, and a claim neither
NSG nor XCAL can make about their proprietary decoders.

## Where the difficulty actually is

Not in the transport. Not in ASN.1. It is in **log record layout variance**.

Qualcomm diag log records carry a `packet_version` field, and the struct layout behind it
changes between modem generations. A parser written against one generation silently
misreads another — wrong offsets, plausible-looking garbage, no error. QCSuper's bundled
`diag_nr_rrc_dissector.lua` reads `packet_version` and handles a limited set; the NR log
code both it and the commercial dissector target is **`0xB821` (NR RRC OTA)**.

Broad, correct coverage across generations requires captures from many physical devices
over a long period. That is the moat. It is also why a 213 KB compiled Lua dissector was
worth charging money for.

## Fields in the NR RRC OTA record

QCSuper's dissector for `0xB821` reads, in order: packet version, RRC release/version
number, radio bearer ID, physical cell ID, frequency, SysFrameNum/SubFrameNum, PDU number,
SIB mask in SI, message length — then hands the remaining bytes to the ASN.1 dissector.
Use this as the reference layout when building the FieldTap decoder, and treat every field
offset as version-dependent until proven otherwise.

## Ports

| Port | Use |
| --- | --- |
| 4729/udp | Standard GSMTAP. What the Nov 2025 capture used. |
| 47928/udp | The port QCSuper's own dissector binds for its diag-wrapper protocol. |

Pick one convention for FieldTap and document it; the split above is a known source of
confusion when a dissector silently does not fire.
