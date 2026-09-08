# Licensing: the decision that gates everything else

**Not legal advice.** This is an engineering summary written to frame the question for
counsel. Get a real opinion before committing to a business model.

## The problem in one paragraph

The capture backend FieldTap depends on, QCSuper, is **GPLv3**. SCAT, the alternative,
is **GPL-2.0-or-later**. Both are strong copyleft. If FieldTap is a *derivative work* of
either and FieldTap is *distributed*, then FieldTap must itself be released under the
same license, with source made available to every recipient. That is flatly incompatible
with the business model FieldTap is aiming at — a per-seat licensed commercial tool with
an activation key, sold against NSG and XCAL.

This is not a footnote to be resolved late. It determines the architecture, so it has to
be settled first.

## What triggers copyleft

Copyleft obligations attach on **distribution**, not on use. Internal use inside one
organisation, with no binaries or source leaving it, does not trigger them. The moment a
customer receives FieldTap, it does.

Whether FieldTap is a "derivative work" depends on how tightly it couples to QCSuper:

| Coupling | Risk |
| --- | --- |
| Import QCSuper as a Python module, call its classes, modify its source | **Derivative. Unambiguous.** Combined work must be GPLv3. |
| Fork QCSuper and build on the fork | **Derivative. Unambiguous.** |
| Invoke unmodified QCSuper as a separate process; exchange data via pcap files or a socket | **Contested but defensible.** See Option B. |
| Reimplement the diag client independently, no QCSuper code | **Not derivative.** See Option C. |

## The four ways out

### Option A — Release FieldTap under GPLv3

Accept copyleft. FieldTap becomes open source.

* **Pro:** zero legal risk, immediate credibility with the security and research community
  that already uses QCSuper and SCAT, no clean-room cost, fastest path to a working tool.
* **Con:** kills per-seat licence revenue. Monetisation shifts to support contracts,
  hardware bundles (pre-provisioned handsets), hosted analysis, and integration with the
  existing Simnovus product line.
* **Verdict:** genuinely viable, and it should not be dismissed reflexively. A pre-rooted
  handset with a supported FieldTap build and a support SLA is a real product, and it is
  the model that best matches Simnovus's existing hardware business. It also completely
  removes the "Chinese vendor" objection that is the wedge against NSG.

### Option B — Arm's-length separation

FieldTap ships proprietary. It invokes **unmodified, separately-obtained** QCSuper as a
subprocess and consumes its pcap output. No linking, no imports, no modifications.

* **Pro:** preserves a proprietary licence model while reusing a working capture backend.
* **Con:** the "separate program vs. derivative work" line is genuinely contested under
  the GPL, and the FSF reads it more narrowly than most vendors would like. The pcap-file
  boundary is a strong fact in your favour; a shared in-process data structure would not be.
  You would also be shipping a product whose core dependency you do not control and cannot
  patch, and the user must install QCSuper themselves — which damages the turnkey story.
* **Verdict:** the tempting middle path. Do not take it without a written legal opinion.

### Option C — Clean-room reimplementation

Write an independent diag client. The Qualcomm diag **protocol** is not copyrightable;
QCSuper's **implementation** of it is. Reimplementing from protocol knowledge, public
documentation, and observed behaviour is lawful, provided the people writing it are not
copying QCSuper source.

* **Pro:** total freedom of licence and architecture. This is the only option that yields
  a defensible, ownable asset.
* **Con:** by far the most expensive. Realistically several engineer-months for parity on
  transport, HDLC framing, log-mask configuration, and the log record parsers — and the
  per-modem-generation struct variance (see `ARCHITECTURE.md`) is the part that never
  really finishes.
* **Verdict:** the right answer if FieldTap is a serious multi-year product line. Note it
  can be staged: ship on Option A or B, reimplement underneath, swap the backend later.

### Option D — Licence a commercial diag stack

Buy the capability from an existing vendor rather than building or copylefting it.

* **Pro:** fastest route to a proprietary product with support behind it.
* **Con:** recurring cost per seat compresses the margin that made the NSG slot attractive
  in the first place; and it makes the product dependent on a supplier who may compete.
* **Verdict:** worth a market check, but it likely prices FieldTap out of its own niche.

## The separate third-party problem

`cots_nr_dissector.lua` — the compiled Lua bytecode used for NR decode in the Nov 2025
session — is commercial trial software from `makemytechnology.com`, and **the trial has
expired**. It is not ours, it is not redistributable, and it is not in this repository.

There are exactly three paths: negotiate a redistribution licence with the vendor, build an
equivalent dissector (see Roadmap Phase 2, which is where the actual product differentiation
lives anyway), or drop NR decode. The second is the one that builds equity.

## Recommendation

Settle this before writing product code. If forced to choose today: **Option A**, with a
staged move toward Option C for the decode layer, which is the part worth owning. The
GPL is far less costly to a company that already sells hardware and support than it is to
a pure-software vendor — and Simnovus is the former.
