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

Moved here from `~/Downloads` — these files live in this folder on disk but are excluded
from version control.

| File | Device / session | Date | Notes |
| --- | --- | --- | --- |
| `oneplus_latest_all_logs.pcapng` | OnePlus, T-Mobile | 19 Nov 2025 | ~2.5 MB. The raw QCSuper capture. |
| `OnePlus_TMobile.txt` | OnePlus, T-Mobile | 21 Nov 2025 | ~7 MB Wireshark text export. GSMTAP → `lte_rrc` over UDP 4729. Confirms the LTE path decodes with stock Wireshark. |
| `Signalling Message_GS24.txt` | **Samsung Galaxy S24** | 13 Nov 2025 | ~990 KB. UL EPS SM / PDN disconnect. A **second device and modem generation** — directly useful to Phase 2.3. |
| `RealUE_CallFlow_RRC_NAS_Only.txt` | Unidentified | Jul 2024 | ~8.4 MB. NR5G NAS MM5G, log code `0xB80D`. QCAT-style export from an earlier session. **The only NR-layer material in the corpus.** |

## What this inventory tells us

The project has captures from **at least three distinct devices/sessions**, not one. That
matters more than it looks: Roadmap Phase 2.3 — packet-version variance across modem
generations — is the technical moat, and it needs exactly this kind of cross-device
material. The Galaxy S24 file and the Jul 2024 NR5G NAS export are the two most valuable
items here, because they cover ground the OnePlus captures do not.

Two gaps to close:

* **Provenance is incomplete.** The device behind `RealUE_CallFlow_RRC_NAS_Only.txt` is
  not recorded anywhere, and without the device and modem firmware the capture is much
  less useful as a regression fixture. Reconstruct it while it is still reconstructable.
* **There is still no backup.** These files are gitignored, so version control does not
  protect them. They are the only empirical record the project has. Put them somewhere
  durable that is not a single laptop.

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
