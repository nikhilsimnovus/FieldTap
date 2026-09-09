# Vendored third-party source

Code here is **not ours**. It is redistributed under its own licence, unmodified.
Do not edit it in place — see "If you need to change it" below.

## qcsuper/

| | |
| --- | --- |
| Upstream | https://github.com/P1sec/QCSuper |
| Licence | **GPLv3** (`qcsuper/LICENSE`, preserved verbatim) |
| Vendored commit | `f5f1501c7ce09f6c167ae623233f674be09cdf87` |
| Upstream date | 2024-07-25 — "Merge pull request #119 from 24alpha/tcp_connector" |
| Modifications | **None.** Byte-identical to upstream at that commit, with one omission below. |

### The one omission

`src/inputs/external/adb/` is **not** included. Upstream ships prebuilt `adb` binaries
for macOS, Linux and Windows — about 28 MB — which would bloat every clone of this
repository permanently and which git handles poorly.

They are not source, and upstream provides its own fetch script. To restore them:

```bash
cd third_party/qcsuper/src/inputs/external && ./update_tools.sh
```

A system `adb` on `PATH` also works for most use cases. Everything else, including
`docs/sample_pcaps/`, is present exactly as upstream published it.

### If you need to change it

Do not patch files in place. A local modification that is invisible in git history is
how a vendored dependency silently diverges from upstream and becomes unmaintainable.

Either keep changes as patch files applied at build time, or fork
`P1sec/QCSuper` under the `nikhilsimnovus` account and vendor the fork — which also
keeps upstream history and attribution intact, and makes pulling upstream fixes tractable.

## What this changed about the repository's licence

**This repository now contains GPLv3 source.** That is lawful — the GPL exists to permit
exactly this — but it is not free of consequence, and `docs/LICENSING.md` should be read
as partially overtaken by it:

* Merely holding this code, unmodified, in a repository triggers no obligation. Copyleft
  attaches on **distribution**.
* If FieldTap is ever distributed as a combined work with QCSuper — bundled, installed
  together, or shipped as one product — GPLv3 applies to the whole, with source made
  available to recipients. That is incompatible with a per-seat proprietary licence.
* The escape routes described in `docs/LICENSING.md` (arm's-length separation, clean-room
  reimplementation) remain open, but each is harder to argue now that the code sits in
  the same tree as future FieldTap source. Keeping FieldTap's own code in a clearly
  separate directory, talking to QCSuper only as a subprocess, preserves the strongest
  version of that argument.

Roadmap task 0.1 is still open, and is now more urgent rather than less.
