# External dependencies

These are **pinned, not vendored**. Nothing here is committed — see `.gitignore`.

Vendoring GPL code into this tree would entangle the repository's licence with QCSuper's
and SCAT's before the licence decision in [`../docs/LICENSING.md`](../docs/LICENSING.md)
has been made. Keeping them external keeps that decision open.

## QCSuper — the current capture backend

* Upstream: https://github.com/P1sec/QCSuper
* Licence: **GPLv3**
* Pinned commit: `f5f1501` (merge of PR #119, tcp_connector)
* Local working copy: `~/qcsuper` (clean, no local commits as of Nov 2025)

```bash
git clone https://github.com/P1sec/QCSuper.git tools/qcsuper
cd tools/qcsuper && git checkout f5f1501
```

Ships its own Wireshark dissector at
`src/modules/wireshark_plugin/diag_nr_rrc_dissector.lua`. Note that the copy previously
kept in `~/Downloads` is byte-identical to this file — it is QCSuper's work, not ours,
and it is covered by QCSuper's GPLv3.

## SCAT — alternative capture backend

* Upstream: https://github.com/fgsect/scat
* Licence: **GPL-2.0-or-later**
* Local working copy: `~/scat` (clean, no local commits as of Mar 2025)

Evaluated earlier than QCSuper and not carried forward. Worth re-checking during Phase 2 —
its parser coverage differs, and it may cover log records QCSuper misses.

## Wireshark

The decode target. FieldTap deliberately relies on Wireshark's built-in `nr-rrc`,
`nas-5gs` and `lte-rrc` dissectors rather than writing its own ASN.1 layer. Record the
minimum supported Wireshark version once Phase 2 begins.

## Not a dependency: cots_nr_dissector.lua

Commercial trial software from `makemytechnology.com`, distributed as compiled Lua 5.4
bytecode. **The trial has expired** ("Trial period expired. Contact
support@makemytechnology.com"). It is not redistributable and is not in this repository.
Replacing it is Roadmap Phase 2.
