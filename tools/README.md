# External dependencies

Dependencies listed here are **pinned, not vendored** — nothing in this directory is
committed. QCSuper was moved out of this arrangement and vendored into `third_party/`;
SCAT remains external.

## QCSuper — now vendored, not external

QCSuper is **no longer a pinned external dependency**. It is vendored, unmodified, at
[`../third_party/qcsuper/`](../third_party/) at commit `f5f1501`. See
[`../third_party/README.md`](../third_party/README.md) for provenance, the one omission
(prebuilt adb binaries), and the licensing consequence.

The local working clone at `~/qcsuper` remains the reference for pulling upstream updates.

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
