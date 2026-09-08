# Competitive landscape

Directional as of the analysis date. Pricing and chipset-support claims move — confirm
against live quotes before quoting any of it externally.

## The two products that matter

### XCAL-Mobile (Accuver)

    supported handset -> diag/DM port -> on-device collection + GPS tagging
      -> proprietary log -> XCAP on a workstation

The phone is a *collection endpoint*, not the analysis surface. Intelligence lives
downstream in XCAP. Supports Qualcomm DIAG and Samsung Exynos DM; MediaTek in some builds.

Buys: automated test sequencing (unattended drive routes), multi-device campaigns, central
server and fleet management. Access model is enterprise — a supported device list with
diag provisioned via vendor cooperation, which is exactly why it clears operator IT and
procurement. Roughly five figures per seat.

### NSG — Network Signal Guru (QTRUN)

    rooted phone -> /dev/diag -> ASN.1 decode ON THE HANDSET -> rendered live on screen

Collapses the workstation step entirely. Read a full RRC Connection Reconfiguration,
expanded IE by IE, standing at the cell site. That is its signature capability.

The bigger gap: **NSG writes to the modem.** Band lock, cell lock, RAT force, measurement
config, NV access on supported devices. XCAL controls the *phone* for test sequencing;
NSG reaches into the *modem's* radio selection. For an engineer chasing one bad cell, that
is the entire job.

Costs: requires root, Qualcomm-centric, dense UI, essentially no campaign tooling or
post-processing suite. Hundreds of USD, tiered.

### Contrast

| | XCAL-Mobile | NSG |
| --- | --- | --- |
| Decode happens | Downstream, in XCAP | On-device, live |
| Modem access | Read + phone-level test control | **Read and write** |
| Device access | Supported list, vendor-provisioned | Root, self-service |
| Built for | Campaigns, benchmarking, fleets | One engineer, one phone, one cell |
| Analysis suite | Deep | Thin |
| Cost | Five figures/seat | Hundreds, tiered |
| Procurement | Clears cleanly | Chinese vendor + rooted device blocks some Western operators |

## The rest of the field

**Commercial phone-as-probe:** R&S QualiPoc Android (closest analogue, market leader in
this form factor), Keysight Nemo Handy, Infovista TEMS Pocket.

**Open source:** QCSuper (P1sec) — the current backend; SCAT (fgsect) — the alternative;
MobileInsight — most productized of the open tools; SnoopSnitch (SRLabs) — same plumbing,
security framing; G-NetTrack Pro — Android-API-level only, so no RRC/NAS, different tier
but competes for the same budget line.

**Reference tool:** Qualcomm QXDM/QCAT/APEX — licence-gated, Windows, and what customers'
RF engineers already trust. Matching its decode fidelity is table stakes.

**Boxes this undercuts:** Amarisoft UE Simbox, Keysight UXM, Anritsu MT8000A, R&S CMX500 —
and Simnovus's own UESIM. Worth being deliberate about that last one.

## Where FieldTap fits

Structurally on NSG's side of the line — root, diag, decode — but currently missing three
of its four legs: no on-device UI, no write path, offline decode instead of live.

The defensible slot is **not** out-building XCAP, which is a decade of engineering. It is
that NSG's export and analysis story is weak and its procurement story is worse. A
rooted-phone capture tool with clean pcap output, real NR decode, and a Western vendor
behind it has a real niche — and it is a narrower, more honest claim than competing with
XCAL on features.

## Naming note

The opaque-acronym lane (XCAL, TEMS, NSG) is closed to a newcomer — those names carry
meaning only because their owners spent decades teaching the market. Self-explaining names
win from a standing start. "Tap" additionally names the wedge: a network tap is passive,
honest observation, and GSMTAP is literally the encapsulation emitted.

Avoid `OpenTap` — that is Keysight's open-source test automation framework. Avoid anything
containing "Diag": it is Qualcomm's word, it advertises the root dependency, and it reads
gray-area to the procurement team whose trust is the whole advantage over NSG.
