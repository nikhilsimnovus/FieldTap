# FieldTap Roadmap

What has to happen to get from "a workflow assembled from other people's tools" to a
product. Phases are ordered by dependency: each one is genuinely blocked by the one above it.

---

## Phase 0 — Decisions that block code

None of these are engineering tasks. All of them change what gets built, so none of the
later phases should start before they are settled.

| # | Decision | Why it blocks | Owner |
| --- | --- | --- | --- |
| 0.1 | **Licence model** — see [`LICENSING.md`](LICENSING.md) | Determines whether the capture backend can be reused or must be reimplemented. Changes the architecture, not just the LICENSE file. | Founder + counsel |
| 0.2 | **Name clearance** — trademark search on "FieldTap" in test & measurement | Cheap now, expensive after collateral exists. Note `OpenTap` is Keysight's; avoid that neighbourhood. | Founder + counsel |
| 0.3 | **Chipset scope** — Qualcomm only, or Qualcomm + MediaTek? | MediaTek uses a completely different diag mechanism. Committing to both roughly doubles Phase 2. Recommend Qualcomm-only for v1. | Eng |
| 0.4 | **Device access model** — root required, or pursue OEM provisioning? | Root is self-service and free but caps the enterprise market. OEM provisioning is what makes XCAL expensive. Recommend root for v1, matching NSG. | Founder |
| 0.5 | **Form factor** — on-device Android app, or laptop-tethered? | The single biggest product decision. See Phase 3. | Founder + eng |

---

## Phase 1 — A reproducible capture baseline

**Goal:** anyone on the team can go from a supported handset to a valid pcap with one
command, repeatably. Today this is tribal knowledge from one session in Nov 2025.

- [ ] **1.1 Pin the toolchain.** Record the exact QCSuper commit in use (currently
      `f5f1501`). Vendor nothing; pin everything. See [`../tools/README.md`](../tools/README.md).
- [ ] **1.2 Write down the device procedure.** For the OnePlus used in Nov 2025: exact
      model, Android build, modem firmware, root method, and the steps to expose the diag
      port. This is the single highest-value undocumented asset the project has right now —
      it is entirely in one person's head and it will be lost.
- [ ] **1.3 One-command capture.** A wrapper that starts QCSuper against an attached
      device, writes a timestamped pcap into `captures/`, and records a sidecar metadata
      file (device, firmware, network, MCC/MNC, band, location, operator, start/stop time).
      Metadata alongside the capture is what makes a corpus usable a year later.
- [ ] **1.4 Device support matrix.** A table of tested handsets and their status. Start
      with one row and be honest about it. This becomes customer-facing documentation.
- [ ] **1.5 Reproduce the Nov 2025 baseline.** Re-run the OnePlus/T-Mobile capture end to
      end and confirm it still works on current firmware. Until this passes, nothing below
      is on solid ground.

**Exit criterion:** a second engineer, given only this repo and a handset, produces a
valid LTE + NR pcap without asking anyone a question.

---

## Phase 2 — NR decode (this is the product)

**Goal:** own the NR decode path. This is the differentiator identified in the competitive
analysis — it is where the free tools are weakest, and it is currently the one part of the
pipeline that is both third-party and *broken*, because the trial expired.

Everything else in FieldTap is plumbing that someone else already built. This is the part
worth owning.

- [ ] **2.1 Establish the LTE baseline first.** QCSuper's existing path already produces
      correct GSMTAP → `lte_rrc` output (verified in the Nov 2025 capture: GSMTAP payload
      type 13, UDP 4729). Understand it completely before touching NR — it is the working
      reference implementation.
- [ ] **2.2 Map the NR diag log codes.** The known anchor is **`0xB821` — NR RRC OTA**,
      which is what both QCSuper's bundled dissector and the expired commercial one target.
      Enumerate the rest: NR ML1 measurement logs, NR MAC/RLC, and 5G NAS. Build a register
      of log code → record layout → Wireshark target dissector.
- [ ] **2.3 Handle packet-version variance. This is the hard part.** Qualcomm changes the
      log record struct layout between modem generations, and the record carries a
      `packet_version` field to distinguish them. QCSuper's dissector reads that field and
      handles a limited set of versions. Broad, correct version coverage is the real moat:
      it is slow, unglamorous, requires captures from many devices, and it is exactly why
      the commercial dissectors charge money.
- [ ] **2.4 Hand off cleanly to Wireshark's ASN.1 dissectors.** Do not write an ASN.1
      decoder. Wireshark already ships `nr-rrc` and `nas-5gs`; the job is to strip the
      Qualcomm wrapper and present the payload with the correct channel type so the
      built-in dissector takes over. Get this boundary right and the decode quality is
      Wireshark's, which is the "Wireshark-native" promise the product is sold on.
- [ ] **2.5 Build a regression corpus.** One capture per modem generation per RAT,
      committed with expected-decode fixtures, run in CI. Without this, every fix to a
      packet-version parser silently breaks another device.
- [ ] **2.6 Retire the third-party dissector.** Success here is what removes the
      `makemytechnology.com` dependency permanently.

**Exit criterion:** a NR capture from at least three different modem generations decodes
to correct, complete `nr-rrc` in stock Wireshark with no third-party plugin.

---

## Phase 3 — The product shell

**Goal:** the difference between a script and a thing someone pays for.

Decision 0.5 governs this phase. The two options are genuinely different products:

**Option A — On-device Android app (the NSG model).** Decode and display on the handset,
live, in the field. This is NSG's signature capability and the reason engineers carry it.
Hardest to build: an Android app doing live ASN.1 decode on-device, and you lose the
"Wireshark does the decoding" advantage that Phase 2 is built on.

**Option B — Laptop-tethered (the current QCSuper model, closer to XCAL-Mobile).** Phone
captures, laptop decodes and displays. Far easier, plays directly to the Wireshark-native
strength, but concedes the live-in-the-field use case that is NSG's whole appeal.

**Recommendation:** ship B first — it is a short path from Phase 2 and it is honest about
what the product is. Treat A as the v2 bet, and only after Phase 2 has produced a decode
library that could plausibly be ported.

- [ ] 3.1 Session management: named sessions, start/stop, metadata capture, organised storage
- [ ] 3.2 Live view: streaming decode rather than post-hoc file analysis
- [ ] 3.3 KPI extraction: RSRP/RSRQ/SINR/throughput timeseries alongside the signalling
- [ ] 3.4 Export: pcap as the primary format; consider a CSV/JSON KPI sidecar
- [ ] 3.5 Call-flow view: ladder diagram of RRC/NAS procedures, the thing engineers
      actually look at (there is already a reference artifact for this shape:
      `RealUE_CallFlow_RRC_NAS_Only.txt`)

---

## Phase 4 — Modem control (NSG parity)

**Goal:** close the single largest functional gap against NSG. NSG *writes* to the modem —
band lock, cell lock, RAT force, measurement config. QCSuper only reads. For an engineer
chasing one misbehaving cell, the write path is the job.

- [ ] 4.1 Assess feasibility and risk per command class over diag
- [ ] 4.2 Band / RAT lock
- [ ] 4.3 Cell lock
- [ ] 4.4 Guardrails: this is the feature most capable of bricking a customer handset.
      Treat destructive-command protection as a v1 requirement, not a polish item.

**Sequencing note:** deliberately after Phase 3. It is the highest-risk work in the
project — technically, legally, and in terms of device damage — and it should not be
attempted until there is a stable product to attach it to.

---

## Phase 5 — Commercialisation

Gated entirely by decision 0.1. If Option A (GPL) is chosen, most of this becomes a
support-and-hardware motion rather than a licensing one.

- [ ] 5.1 Licensing / activation mechanism, if the model allows one
- [ ] 5.2 Packaging and installer; consider pre-provisioned handset bundles
- [ ] 5.3 Support, documentation, training materials — this is a real part of the
      "Western vendor" premium, not an afterthought
- [ ] 5.4 Positioning against NSG: clean pcap, real NR decode, vendor you can procure from
- [ ] 5.5 Simnovus portfolio integration — the FieldSim pairing (one simulates a UE, the
      other taps a real one) is free marketing; use it

---

## The risk worth naming

FieldTap's technical moat is Phase 2.3 — broad, correct packet-version coverage across
modem generations. That is not a clever insight anyone can copy; it is grinding work
requiring access to many devices over a long period. It is also the only part of this
project that a competitor cannot trivially replicate.

Every phase before it is table stakes. Every phase after it depends on it. Resource it
accordingly, and resist the pull toward Phase 3 UI work, which will feel more like
progress and will not build any equity.
