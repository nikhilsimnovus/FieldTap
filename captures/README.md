# Captures

**Capture files are not committed to this repository.** The `.gitignore` enforces it.

## Why

The Nov 2025 OnePlus/T-Mobile captures contain live commercial network signalling. That
means real subscriber and network identifiers — IMSI/TMSI/GUTI, cell identities, tracking
area codes, and by extension approximate location and movement over the capture window.

That is personal data belonging to whoever held the SIM, plus operator network topology
that T-Mobile has not agreed to publish. Committing it — even to a private repository —
makes it a permanent artifact in git history that is disproportionately hard to remove
later, and it will end up on every machine that clones the repo.

If a capture must be shared, it should be a deliberate decision, and it should be
anonymised or a purpose-built capture taken on a test SIM against a lab network.

## Reference captures held outside the repo

As of Nov 2025 the working captures live in `~/Downloads`:

| File | Description |
| --- | --- |
| `oneplus_latest_all_logs.pcapng` | OnePlus capture, ~2.5 MB, 19 Nov 2025 |
| `OnePlus_TMobile.txt` | Wireshark text export, ~7 MB, 21 Nov 2025. GSMTAP → LTE RRC over UDP 4729, live T-Mobile. |

**These are currently in a Downloads folder with no backup.** Move them somewhere durable.
They are the only empirical record the project has, and Roadmap Phase 2.5 depends on
building a regression corpus that starts with them.

## Reproducing a capture

To be written as Roadmap task 1.3. It should produce, for every capture, a sidecar
metadata file recording: device model, Android build, modem firmware, operator, MCC/MNC,
band(s), location, and start/stop time. A capture without that metadata is close to
useless a year later.

## Test SIM

Phase 2 needs captures across many modem generations. Doing that against a live commercial
network with a personal SIM does not scale and creates the privacy problem above every
time. Provision test SIMs against a lab network — Simnovus already has the hardware to
be its own network here, which is a genuine structural advantage over a competitor who has
to drive around.
