# Captures

**Capture files are not committed to this repository.** The `.gitignore` enforces it, and
that is verified — `git check-ignore` confirms every file below is invisible to git.

## Why not committed

These captures contain live commercial network signalling: real subscriber and network
identifiers (IMSI/TMSI/GUTI), cell identities, tracking area codes, and by extension
approximate location and movement over the capture window.

That is personal data belonging to whoever held the SIM, plus operator network topology
that the operator has not agreed to publish. Committing it — even to a private repository —
makes it a permanent artifact in git history that is disproportionately hard to remove
later, and puts it on every machine that clones the repo.

Sharing a capture should be a deliberate decision, and it should be anonymised or taken on
a test SIM against a lab network.

## Current contents

Moved here from `~/Downloads`. These files live in this folder on disk but are excluded
from version control. Each has a tracked `*.meta.json` sidecar — those **are** committed
and form the corpus index.

| File | Source | Date | RAT | Notes |
| --- | --- | --- | --- | --- |
| `oneplus_latest_all_logs.pcapng` | OnePlus, T-Mobile | 19 Nov 2025 | LTE | ~2.5 MB raw QCSuper capture |
| `OnePlus_TMobile.txt` | OnePlus, T-Mobile | 21 Nov 2025 | LTE | ~7 MB Wireshark export, 223 frames over ~281 s |
| `Signalling Message_GS24.txt` | **Uncertain — see below** | 13 Nov 2025 | LTE | ~990 KB, 89 messages, EPS SM/MM, NAS v950 |
| `RealUE_CallFlow_RRC_NAS_Only.txt` | Unidentified device, **AT&T** | 3 Jul 2024 | **NR5G** | ~8.4 MB, 146 NAS messages, QCAT-style |

Full detail — including capture host, decode breakdown and confidence ratings — is in the
sidecars.

## What the recovered provenance changed

Reading the files rather than their filenames corrected three assumptions:

**The corpus spans two operators, not one.** The Jul 2024 capture decodes to **MCC 311 /
MNC 180 — AT&T**, read from the NAS `Req PLMN Identity = { 0x13, 0x01, 0x81 }` and
confirmed against the explicit MCC/MNC fields. The Nov 2025 work was T-Mobile.

**The capture host is Linux, not macOS.** The pcapng section header records
`Linux 6.8.0-85-generic`, an i9-13900H, and `Dumpcap (Wireshark) 4.4.9` reading from
standard input — i.e. QCSuper's stdout piped into dumpcap on a Linux laptop. Anyone
reproducing the Nov 2025 session needs that host, not this Mac. This is exactly the kind
of detail Roadmap 1.2 exists to capture, and it was one file-header away from being lost.

**35% of the OnePlus capture does not decode.** Of 223 frames, 136 resolve to
`lte_rrc`, but **77 resolve only to `ip:udp:data`** — GSMTAP payloads Wireshark could not
attribute to any dissector. That is the gap Phase 2 exists to close, now measured rather
than assumed, and it is consistent with NR frames that depended on the expired
third-party dissector.

## Correction: the Galaxy S24 file is not confirmed to be a handset capture

An earlier assessment recorded `Signalling Message_GS24.txt` as a second device and modem
generation, and therefore directly useful to Phase 2.3. **That is not established.** The
device attribution rests entirely on the `GS24` token in the filename, and the file's
format — `PC Timestamp` fields, directional arrows, decoded-field blocks — reads more like
a network-side or test-system log than an on-device Qualcomm diag capture. Downloads from
the same date included Amarisoft UE Simbox documentation, which makes a test-system origin
plausible.

If it is a network-side log, its value to Phase 2.3 is much lower than assumed, because
Phase 2.3 is specifically about per-modem-generation diag struct variance. Resolve this
before counting it as corpus coverage. Note also that the Galaxy S24 shipped in both
Exynos and Snapdragon variants, so even if it is a handset capture, which variant matters.

## Corpus gaps

* **NR coverage rests on a single file** whose device is unknown. Phase 2 is entirely
  about NR decode, and the only NR material here cannot currently serve as a regression
  fixture because there is no device or modem generation attached to it. It is ~16 months
  old; whoever ran it may still remember the handset.
* **No device or firmware is recorded for any capture.** Every `device` block in the
  sidecars is largely `UNKNOWN`.
* **Still no backup.** These files are gitignored, so version control does not protect
  them. Moving them out of Downloads improved the filing, not the durability.

## Reproducing a capture

To be written as Roadmap task 1.3. Every capture should get a sidecar metadata file
recording device model, Android build, modem firmware, operator, MCC/MNC, band(s),
location, and start/stop time. The table above had to be reconstructed by reading file
contents — that is the cost of not having done this, and it will only get more expensive.

## Test SIM

Phase 2 needs captures across many modem generations. Doing that against a live commercial
network with a personal SIM does not scale, and recreates the privacy problem above every
time. Provision test SIMs against a lab network — Simnovus already owns the hardware to be
its own network here, which is a real structural advantage over a competitor who has to
drive around to collect the same material.
