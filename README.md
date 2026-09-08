# FieldTap

Turn a commercial Android handset into a real-UE network probe: pull RRC/NAS off the
Qualcomm diag port, decode it, and land it in Wireshark as clean GSMTAP.

FieldTap is the real-network counterpart to the Simnovus simulator line. UESIM, ORUSIM
and RuSIM simulate a UE in the lab. FieldTap taps a real one in the field.

> **Status: pre-alpha. No shippable code yet.**
> This repository currently contains planning and architecture documentation only.
> Read [`docs/ROADMAP.md`](docs/ROADMAP.md) for what has to happen next, and
> [`docs/LICENSING.md`](docs/LICENSING.md) for the decision that gates everything else.

## Where the project actually stands

As of the last field session (Nov 2025, OnePlus on T-Mobile), the working pipeline was:

    OnePlus handset -> Qualcomm diag port -> QCSuper -> pcap -> Wireshark + Lua dissector

Every component in that chain is third-party. Specifically:

| Component | Origin | Consequence |
| --- | --- | --- |
| QCSuper | [P1sec/QCSuper](https://github.com/P1sec/QCSuper), **GPLv3** | Copyleft. Gates the product's license model — see `docs/LICENSING.md`. |
| SCAT | [fgsect/scat](https://github.com/fgsect/scat), **GPL-2.0-or-later** | Alternative capture backend, same copyleft question. |
| `diag_nr_rrc_dissector.lua` | Ships **inside QCSuper** | Not original work. Covered by QCSuper's GPLv3. |
| `cots_nr_dissector.lua` | Commercial trial, `makemytechnology.com` | **Expired, compiled bytecode, not redistributable.** Currently the only NR decode path — and it no longer runs. |

The honest summary: FieldTap today is a workflow assembled from other people's tools,
one of which has stopped working because its trial ran out. Turning it into a product
means replacing the parts that are not ours. That is what the roadmap is about.

## What is deliberately not in this repository

* **Capture files.** The Nov 2025 pcaps contain live commercial network signalling with
  real subscriber and cell identifiers. See [`captures/README.md`](captures/README.md).
* **The expired commercial dissector.** Redistributing licensed third-party bytecode is a
  violation regardless of repo visibility.
* **Vendored QCSuper / SCAT.** These are pinned external dependencies, not part of this
  tree. See [`tools/README.md`](tools/README.md).

## Documentation

| Document | What it covers |
| --- | --- |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | Phased plan from here to a shippable product |
| [`docs/LICENSING.md`](docs/LICENSING.md) | The GPL problem and the four ways out |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Target design and the decode pipeline |
| [`docs/COMPETITIVE-LANDSCAPE.md`](docs/COMPETITIVE-LANDSCAPE.md) | XCAL-Mobile, NSG, QualiPoc and where FieldTap fits |
| [`captures/README.md`](captures/README.md) | Capture handling policy and how to reproduce |
| [`tools/README.md`](tools/README.md) | External dependency setup |
