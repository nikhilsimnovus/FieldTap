# Restoring Qualcomm DIAG by custom kernel, and which handsets are known-good

**Date:** 2026-09-10
**Scope:** (a) whether a custom kernel can restore `/dev/diag` on the OnePlus 10 Pro (NE2215, SM8450 "taro", Android 15 / OxygenOS 15, bootloader unlocked, Magisk root); (b) which handsets in 2025–2026 actually give decodable DIAG.

**Companion document:** [`oneplus-10-pro-diag.md`](oneplus-10-pro-diag.md) already establishes, and I do not re-derive here, that OnePlus's published kernel source for this device contains no `drivers/char/diag` on any branch from OxygenOS 12.1 to 15, and that on the physical device `/dev/diag` is absent (`ENOENT` as root, `grep -c diagchar /proc/devices` = 0). This document picks up from there and answers the *next* question: can we put it back, and if not, what do we buy.

## Confidence key

- **[PRIMARY]** — I fetched and read the artefact myself in this session (source trees via the GitHub/GitLab REST APIs, symbol lists, defconfigs, project READMEs, maintainer replies in issue threads).
- **[COMMUNITY]** — GitHub issue/PR content or wiki content I read in full.
- **[COMMUNITY — snippet]** — search-result snippets only; xdaforums.com, droidwin.com, community.oneplus.com and web.archive.org all return 403/blocked to automated fetches in this session. Cited but flagged.
- **[REASONED]** — my inference from the above, not a citation.

---

## 1. Can the diag driver be put back? The GKI analysis

### 1.1 The premise "take it from Qualcomm's public SM8450 release" does not hold

**[PRIMARY]** I listed `drivers/char/` in Qualcomm's own public CodeLinaro (CAF successor) kernel trees via the GitLab API. There is **no `diag` subdirectory in any of them**:

| CodeLinaro tree | Ref checked | `drivers/char/diag` present? |
|---|---|---|
| `clo/la/kernel/msm-5.10` (the SM8450 / waipio generation) | `kernel.lnx.5.10.r1-rel` | **No** |
| `clo/la/kernel/msm-5.4` | `kernel.lnx.5.4.r1-rel` | **No** |
| `clo/la/kernel/msm-4.19` | default branch | **No** |

Queries used, reproducible:
`https://git.codelinaro.org/api/v4/projects/clo%2Fla%2Fkernel%2Fmsm-5.10/repository/tree?path=drivers/char&ref=kernel.lnx.5.10.r1-rel&per_page=100`
(returns `agp, hw_random, ipmi, mwave, pcmcia, tpm, xilinx_hwicap, xillybus, Kconfig, Makefile, adi.c, adsprpc.c, … rdbg.c, …` — Qualcomm-specific files such as `adsprpc.c` and `rdbg.c` are there; `diag/` is not.)

So there is **no "kernel.lnx / LA.UM tag" you can lift `drivers/char/diag` from**. Qualcomm does not publish the diag driver on CodeLinaro for this generation. The driver reaches devices through OEM BSP drops under NDA, and OEMs then omit it from their GPL releases.

### 1.2 No same-generation OEM tree has it either

**[PRIMARY]** I checked every SM8450-generation (and neighbouring) vendor tree I could find, by API, for `drivers/char/diag` and for `diagchar.ko` in the module manifests:

| Tree | Kernel | `drivers/char/diag`? | `diagchar.ko` in `modules.list.msm.waipio`? |
|---|---|---|---|
| `OnePlusOSS/android_kernel_msm-5.10_oneplus_sm8450` | 5.10 | No | **No** (129 modules listed, zero match `diag`) |
| `samsung-sm8450-kernel/kernel_msm` (lineage-19.1) | 5.10 | No | **No** |
| `xiaomi-sm8450-kernel/android_kernel_platform_msm-kernel` | 5.10 | No (`drivers/char/Makefile` has no `diag/` line) | n/a |
| `LineageOS/android_kernel_oneplus_sm8450` | 5.10.237 | No | n/a |
| `LineageOS/android_kernel_xiaomi_sm8450` | 5.10 | No | n/a |
| `LineageOS/android_kernel_nothing_sm8475` | 5.10 | No | n/a |
| `LineageOS/android_kernel_oneplus_sm8350` | 5.4 | No | n/a |
| `LineageOS/android_kernel_xiaomi_sm8350` | 5.4 | No | n/a |
| **`LineageOS/android_kernel_xiaomi_sm8250`** | **4.19.325** | **Yes** — `Kconfig, Makefile, diag_dci.c, diag_debugfs.c, …` | n/a |

**[PRIMARY]** A GitHub code search for `CONFIG_DIAG_CHAR path:arch/arm64/configs` returns **zero** hits on any sm8350/sm8450/sm8550 tree; the newest platforms that appear are `sm8250` (Snapdragon 865, kernel 4.19) and `sm8150`. A search for `CONFIG_DIAG_CHAR sm8450` returns **zero results**.

**[REASONED]** The newest public diag source is therefore the **SM8250 / kernel-4.19 generation**. Any restoration on SM8450 is a *forward port across two kernel generations* (4.19 → 5.10) and across the pre-GKI → GKI-2.0 architectural boundary — not a copy-paste.

### 1.3 Loadable module against GKI: the symbol wall

Android 12+ on SM8450 uses GKI 2.0: a Google-built generic `boot.img` kernel, plus vendor modules in `vendor_boot`/`vendor_dlkm`. So "build `diagchar.ko` out-of-tree against the GKI" is the right shape of question. It fails on symbols.

**[PRIMARY]** The Android kernel trees carry per-partner KMI symbol lists (`android/abi_gki_aarch64*`), and `build.config.gki.aarch64` in the OnePlus SM8450 tree sets `KMI_SYMBOL_LIST=android/abi_gki_aarch64` plus `ADDITIONAL_KMI_SYMBOL_LISTS` including `..._qcom`, `..._oplus`, `..._galaxy`, `..._xiaomi`, etc. Symbols outside the union are trimmed and are simply not exported by the shipped kernel.

I recovered the exact symbol set `diagchar.ko` needs from a tree of the era when it *was* a GKI vendor module — `kerneltoast/android_kernel_google_redbull` (Pixel 5, sm7250), `android/abi_gki_aarch64_qcom`, which literally contains the stanza:

```
# required by diagchar.ko
  cdev_alloc
  crc_ccitt
  crc_ccitt_table
  kernel_getsockname
  kernel_recvmsg
  kernel_restart
  kernel_sendmsg
  kernel_setsockopt
  mempool_alloc
  mempool_create
  mempool_destroy
  mempool_free
  mempool_kfree
  mempool_kmalloc
  send_sig_info
  time64_to_tm

# required by usb_f_diag.ko
  refcount_dec_and_lock
```

Sixteen symbols for the char driver, one more for the USB gadget function. I then checked those against the **union** of all KMI symbol lists in `LineageOS/android_kernel_oneplus_sm8450` (`abi_gki_aarch64`, `_core`, `_generic`, `_galaxy`, `_oplus`, `_xiaomi` — 10,973 lines):

| Symbol | In OnePlus SM8450 KMI union? |
|---|---|
| `cdev_alloc`, `kernel_getsockname`, `kernel_recvmsg`, `kernel_restart`, `kernel_sendmsg`, `mempool_*`, `send_sig_info`, `time64_to_tm`, `refcount_dec_and_lock` | Yes |
| **`crc_ccitt`** | **No** |
| **`crc_ccitt_table`** | **No** |
| **`kernel_setsockopt`** | **No** |

Three blockers, and they are qualitatively different:

1. **`kernel_setsockopt` does not exist in Linux 5.10 at all.** It was deleted upstream in the 5.9 merge window (Christoph Hellwig, "net: remove kernel_setsockopt — No users left", May 2020, patch 33/33 of the `remove kernel_setsockopt and kernel_getsockopt` series). The diag driver's socket transport (`diagfwd_socket.c`, used for the AP↔modem diag channel) must be rewritten against the 5.10 in-kernel socket-option helpers. That is real driver surgery, not a build fix.
2. **`crc_ccitt` / `crc_ccitt_table`** come from `CONFIG_CRC_CCITT`, which is **not enabled in the OnePlus SM8450 `gki_defconfig`** (the only CRC entry there is `CONFIG_CRC8=y`). Diag uses CRC-CCITT for HDLC framing. You either statically link a private copy into the module, or you rebuild GKI with `CONFIG_CRC_CCITT` and add the symbols to the list — the latter means rebuilding the core kernel, which defeats "just ship a module".
3. **`CONFIG_MODVERSIONS=y`** in that same `gki_defconfig` means the module's symbol CRCs must match the running kernel's exactly. A module built against a tree that differs from the shipped GKI by even a struct layout will refuse to load.

**[PRIMARY]** Two things that are *not* blockers, contrary to the usual assumption:

- **Module signing is not the obstacle.** AOSP states plainly: "Module-signing is not supported for GKI vendor modules"; authenticity comes from dm-verity on the partition, not from a signature on the `.ko`. (https://source.android.com/docs/core/architecture/kernel/loadable-kernel-modules)
- **Adding symbols to a frozen KMI is legal.** The GKI FAQ says changes that don't affect the existing KMI — "new exported functions and symbol list entries" — may be added to frozen kernels. It just means *you* are now building and shipping the core kernel.

### 1.4 What actually breaks, itemised

| Concern | Verdict |
|---|---|
| Symbol availability | **Blocking.** `kernel_setsockopt` gone from 5.10; `crc_ccitt*` not built. Requires source changes plus a rebuilt GKI `boot.img`. |
| GKI module signing | Not an issue — unsupported/unused for vendor modules. |
| ABI/KMI stability | `CONFIG_MODVERSIONS=y`; module must match the exact kernel you boot. If you build your own GKI you control this, but then all ~129 existing vendor modules must still load against your kernel — any struct-affecting config change bootloops the device. |
| `vendor_boot` / `vendor_dlkm` | `vendor_dlkm` is dm-verity protected. Either repack + AVB re-sign it (possible with an unlocked bootloader) or, far simpler, `insmod` the module from a Magisk `post-fs-data` script. |
| SELinux | The node has to be created and labelled. OnePlus's vendor sepolicy has no `diag_device` type at all — the driver never existed on this codebase — so you must inject types and allow rules (`magiskpolicy`) or run permissive. Adds work but is not the hard part. |
| Modem-side plumbing | The 4.19 diag driver's SMD/GLINK/QMI hooks into the modem changed across generations. This is where a forward port most plausibly fails silently — the char device appears, the modem never answers. |

---

## 2. Has anyone actually done it?

**No. I found no evidence anyone has restored `/dev/diag` on SM8450, on any OnePlus/OPPO/Realme device of that generation, or on any GKI-2.0 (5.10+) Qualcomm phone.** Specifically:

- **[PRIMARY]** GitHub code search: `CONFIG_DIAG_CHAR sm8450` → zero results. `CONFIG_DIAG_CHAR path:arch/arm64/configs` → 50 results, newest platform sm8250/sm8150, all pre-GKI.
- **[PRIMARY]** No SM8450-era kernel tree from OnePlus, Samsung, Xiaomi, Nothing, OPPO or LineageOS contains the driver (table in §1.2), so there is not even a same-generation reference implementation to diff against.
- **[COMMUNITY — snippet]** XDA has an active SM8450 kernel-compile thread and OnePlus-kernel build write-ups, none of which mention diag. A 2026 write-up on building OnePlus kernels notes OnePlus's GPL drop is itself incomplete — "entire directories are missing (`drivers/oneplus/` and its subdirectories), header files are absent, and the build is riddled with undefined references" — and recommends using the LineageOS tree instead (https://pwner.gg/blog/2026-04-03-android-custom-kernel).
- **[COMMUNITY]** QCSuper carries an open, unresolved issue "dev/diag not exist on oneplus 12" (SM8650), i.e. the same wall one and two generations later, with no fix posted.
- **[COMMUNITY]** The closest anyone gets on a modern OnePlus is QCSuper issue #150, "One Plus 9 (Rooted) - Works" — but read it carefully: the reporter says "*Had to use the `--adb`. For whatever reason I couldn't expose diag directly. When I try to activate via `*#8011#`, it says I need to toggle ADB debugging…*". That is a **SM8350 / kernel-5.4 OnePlus 9** where the driver was still present; it is not evidence for the 10 Pro, and it is not a kernel port.
- **[COMMUNITY]** The one genuinely modern Qualcomm phone with a documented community diag path is the **Fairphone 5** (QCM6490, rooted, LineageOS) — QCSuper issue #159 plus a full guide at https://github.com/Doct2O/fairphone5/tree/main/radio/cellular/qualcomm-diagnostic-mode. But that works because Fairphone's kernel *still ships the driver*; the guide is `diag-on-usb.sh` / `diag-on-loc.sh` wrappers around a vendor `diag-router` binary. Again: not a port.

**Conclusion:** the team would be first. There is no prior art to copy, no patch series to rebase, and no reference device of the same generation whose tree you could diff.

---

## 3. Realistic effort and risk

**Toolchain.** AOSP prebuilt Clang + `build/build.sh` (or Kleaf/Bazel for the newer branches), an `android12-5.10`/`android13-5.10` GKI checkout, plus OnePlus's `android_kernel_msm-5.10_oneplus_sm8450` and `android_kernel_modules_and_devicetree_oneplus_sm8450`. Device kernel is 5.10.x — community reports show it moving (a12 5.10.136 → 5.10.168 across the OOS 13.1 → 14 beta jump), and a kernel built for the wrong point release bootloops.

**Work items, in order of nastiness:**

1. Forward-port `drivers/char/diag/*` from a 4.19 tree (`LineageOS/android_kernel_xiaomi_sm8250` is the cleanest public source) to 5.10. Rewrite the `kernel_setsockopt` call sites. Bring CRC-CCITT in. Re-wire the modem transport (`diagfwd_socket`/`diagfwd_glink`) onto the 5.10 GLINK/RPMSG APIs.
2. Rebuild GKI with the added symbol-list entries and flash your own `boot.img`; verify all ~129 stock vendor modules still load.
3. Restore the `f_diag` USB gadget function so `sys.usb.config diag,…` has something to bind to (`usb_f_diag.ko` needs `refcount_dec_and_lock`, which *is* in the KMI list — that half is easy).
4. sepolicy: create and allow a `diag_device` type, or run permissive.
5. Prove the modem actually answers, not just that the node exists.

**Effort:** weeks, not days, for someone who has done Qualcomm BSP work; realistically a month-plus for someone who has not, with a material chance of never getting past step 5 because the modem-side interface is the part with no public documentation.

**Risk:**
- **Boot loops:** high probability during bring-up; recovery is `fastboot flash boot` of the stock Magisk-patched image, so it is recoverable but iterative. Custom-kernel flashing on OnePlus typically also needs `--disable-verity --disable-verification`.
- **Cellular instability:** diag hooks the same modem transport that carries live RIL/data. A hand-ported driver that half-works is worse for a drive-test tool than none.
- **OTA:** every OTA overwrites `boot.img`. A custom kernel must be re-flashed after each update, and each OOS release may move the kernel point-version out from under your build. There is no scenario where this survives OTA unattended.
- **Commercial:** the deliverable is a phone that is bootloader-unlocked, rooted, running an unofficial kernel, with a permissive-ish sepolicy. That is a hard thing to hand an operator.

**Recommendation: do not do this.** It is a research project with a plausible failure mode at the end, to reach a capability that a €400 stock Samsung already has with no root at all.

---

## 4. Known-good handsets, 2025–2026

### 4.1 Samsung Snapdragon via `*#0808#` — CONFIRMED, and this is the important one

**The claim is substantially true: on Snapdragon Galaxy models, `*#0808#` opens a USB-mode selector that exposes the Qualcomm DIAG interface with no root and no bootloader unlock.** Caveats below matter, but the headline holds.

Evidence, strongest first:

- **[COMMUNITY — PRIMARY-grade]** SCAT's tested-devices wiki lists **Samsung Galaxy S23, Snapdragon 8 Gen 2, `-t qc`, Root Required: No**, alongside the setup instruction "*Enter `*#0808#` in dialer and select USB mode with 'DM'*". (https://github.com/fgsect/scat/wiki/Devices)
- **[COMMUNITY]** SCAT PR #96 (merged, released in v1.3.0) was contributed by a user capturing **5G NR RRC OTA on their own S23 Ultra and S24 Ultra**, complete with hexdumps of `UECapabilityEnquiry` and a screenshot of the payload dissected by Wireshark's NR RRC dissector. Title: "Add new NR RRC OTA packet version 0x13, 0x17 + SCell version 0x30003 + MM State version 0x30000 to support S23 Ultra and S24 Ultra".
- **[COMMUNITY]** SCAT issue #98: a user captures LTE MAC and NR from an **S24 Ultra**, and when asked "I assume this is on an unrooted S24 Ultra?" replies "**Yes, it's unrooted.**"
- **[COMMUNITY]** SCAT issue #87 ("Is it possible to make this work on Samsung S23 SM8550"), maintainer reply: "**Should be working, as long as USB port setting includes DIAG/QDSS.**"
- **[COMMUNITY]** SCAT issue #98, another user giving the recipe verbatim: "*Enable USB debugging; Enable USB menu/diag mode in phone by dialling `*#0808#` for Samsung and `*#8011#` for Oneplus; Select option **RMNET+DM+MODEM+ADPL+ADB***".

**USB modes the menu offers.** Reported option strings across sources: `DM + MODEM + ADB`, `DM + ACM + ADB`, `RMNET + DM + MODEM + ADPL + ADB`, `RNDIS + ACM + DM`, and the plain `MTP + ADB` you set it back to. Any entry containing **DM** is the one you want.

**Persistence.** Survives normal reboots. **Does not reliably survive an OTA** — several sources note the USB composition, and the related band settings, reset on "CSC updates or software updates that include a CSC update", and advise re-running `*#0808#` after each major update. Build the re-arm step into the product's device-setup flow.

**US carrier variants — the real caveat.** On US carrier-branded units (Verizon in particular) the secret codes are disabled by the CSC, and the community procedure is to re-enable them with a third-party tool first (SamFW FRP Tool ≥ 4.1, MTP tab, "Enable Secret Code for Verizon"), after which `*#0808#` appears. The widely-mirrored S23 band-unlock guide lists confirmed models **S918U / S918U1 / S918W** (S23 Ultra) and **S908U / S908U1 / S908W** (S22 Ultra), notes Canadian `W` units must be on XAA (US) firmware first, and states plainly "**No bootloader unlock or root required**". [COMMUNITY — snippet, via addrom mirror of the XDA thread; xdaforums itself 403s to automated fetch]

**Practical consequence: buy the *unlocked* SKU (`U1`, or an international `B` model of an all-Snapdragon generation), not a carrier-branded one.** That sidesteps the secret-code unlock entirely.

**Do not root a Samsung to get diag.** SCAT issue #98 contains a direct counter-example: a user with a **rooted SM-S928B** (S24 Ultra) reports "*rooting the device broke diag functionality altogether … Even unrooting the device / flashing stock did not fix it*", and only recovered it by flashing a zip that disables encryption. Unlocking the bootloader on a Samsung also trips Knox irreversibly. Stock and unrooted is not just easier to sell — on Samsung it is *more* reliable.

**Which Galaxy models are actually Snapdragon** (this is the whole ballgame; Exynos units expose no Qualcomm DIAG):

| Generation | Snapdragon where? |
|---|---|
| S22 / S22+ / S22 Ultra | US, China, Korea = Snapdragon 8 Gen 1. **Europe = Exynos 2200 — avoid.** |
| **S23 / S23+ / S23 Ultra** | **Snapdragon 8 Gen 2 in every region.** No Exynos variant exists. |
| S24 / S24+ | Snapdragon 8 Gen 3 in US, Canada, China, Korea, Australia; **Exynos 2400 in Europe, UK, India — avoid.** |
| S24 Ultra | Snapdragon 8 Gen 3 **everywhere**. |
| **S25 / S25+ / S25 Ultra** | **Snapdragon 8 Elite in every region**, Samsung-confirmed. |

Always verify on the unit: Settings → About phone → Processor.

**Residual risk to validate on first purchase:** SCAT's README warns "*On certain Qualcomm devices, you will see 'Secure log' messages. This means that the baseband is encrypting the DIAG log packet, which SCAT can't decrypt without a proper RSA key*", and SCAT v2.0.0 (Dec 2025) added detection of the secure-log public key with "no support for decryption planned". The S23U/S24U evidence above shows plaintext RRC OTA on those units, so they are not affected today — but this is the one thing that could quietly kill a future Samsung SKU, and it is worth checking on day one with each new model.

### 4.2 Xiaomi / Redmi / POCO (Snapdragon)

- **[COMMUNITY]** SCAT's tested table lists POCO F1 (SDM845), Mi Mix 3 5G (SM8150), Mi 10T 5G (SM8250) — all **`-t qc`, Root Required: Yes**.
- **[PRIMARY]** QCSuper's README documents a **non-root** path for Xiaomi specifically: "*it may also be possible to use an APK file signed by the phone vendor and with System-related permissions in order to enable the Diag mode without rooting (search about the `com.longcheertel.midtest` APK for Xiaomi-based devices for example)*".
- **[COMMUNITY — snippet, weak]** A dialer code `*#*#73694364#*#*` is widely claimed to enable diag without root on HyperOS 2.0 Qualcomm devices. Sources are low-quality SEO/FRP-tool sites; treat as unverified until tested on a real unit.
- **[REASONED]** Xiaomi is a viable second source but every documented-reliable path needs root or a vendor-signed test APK, and Xiaomi's bootloader-unlock policy has tightened (waiting periods, account binding, per-account quotas). Worse commercial story than Samsung.

### 4.3 Google Pixel — out of scope, confirmed

**[COMMUNITY]** SCAT's tested table lists Pixel 6 (GS101), Pixel 7 (GS201), Pixel 9 (GS401) with baseband "Exynos Modem 5123 / 5300 / 5400" and parser **`-t sec`** (Samsung/Exynos), **root required**. SCAT's device wiki also states the Nexus `setprop sys.usb.config diag,adb` trick "**does not work for Pixel devices**", and marks Pixel 2 as needing system-partition modification. Tensor Pixels have no Qualcomm baseband at all, so Qualcomm DIAG is structurally impossible. The last Qualcomm-modem Pixels are the Pixel 5/5a (sm7250) — aging out, and per §1.3 that is exactly the generation where diag was still a GKI vendor module.

**Verdict: the entire Pixel line is a dead end for a Qualcomm-DIAG product.** (SCAT does support the Exynos/Samsung diag dialect on them via `-t sec` + root, which is a different protocol and a different decoder path — worth knowing, not worth building on.)

### 4.4 Motorola

- **[COMMUNITY]** QCSuper issue #143: "Motorola 30 Edge Ultra Operation Review — I tested my 30 Edge Ultra and it seems to be correctly working". Issue #102: "old Motorola worked".
- **[COMMUNITY — snippet]** The documented recipe on Motorola Edge 40 Pro (XT-2301-4) is root + `setprop sys.usb.config diag,serial_cdev,rmnet,dpl,qdss,adb`.
- **[COMMUNITY]** Qtrun (Network Signal Guru, the incumbent commercial app in this space) states it "supports Moto devices very well with Qualcomm chipsets".
- **[REASONED]** Motorola is the best *rooted* Qualcomm option: bootloader unlock is officially supported on most models, and the diag driver is evidently still present. Root is still required, so it is second choice behind stock Samsung.

### 4.5 Sony

- **[COMMUNITY]** SCAT wiki: Sony requires **rooting**, then `setprop persist.usb.eng 1` from a root shell. Tested entry: Xperia X (MSM8956), root required. QCSuper's tested list includes Xperia Z ("works out of the box after rooting").
- **[COMMUNITY]** Qtrun: "supports Sony devices very well with Qualcomm basebands".
- **[REASONED]** Sony's Open Devices programme makes unlocking legitimate, but unlocking wipes DRM keys and degrades the camera stack, and current Xperia availability outside Japan/EU is thin. Viable, not recommended.

### 4.6 LG, Nokia, others in the project test lists

- **[COMMUNITY]** LG: `277634#*#` in the dialer, no root on several models (SCAT lists LG G Flex 2, root **not** required), but some need udev tweaks because the diag port is not exposed on Linux. LG exited the phone business in 2021 — not a purchasable option.
- **[COMMUNITY]** Nokia 8110 4G (SD205) — SCAT-tested, root required. QCSuper #111: Nokia 6 (TA-1021) working.
- **[COMMUNITY]** Fairphone 5 (QCM6490) — QCSuper #159, rooted + LineageOS, with a full public guide. The only current, actively-documented, *modern* Qualcomm phone where the community has a maintained diag recipe. Requires root; interesting as a Plan B / EU-friendly option.
- **[COMMUNITY]** QCSuper's own "supported devices" list is heavily weighted to USB modems (ZTE MF823/MF667/MF110, Quectel EP06/RM500Q, Sierra MC7455, Telit LE910) — worth remembering that a Qualcomm **M.2/USB modem** is a completely root-free DIAG source (`AT$QCDMG` on the AT port opens the diag port) if the product ever needs a fixed reference probe rather than a handset.

### 4.7 OnePlus / OPPO / Realme — the negative result

SCAT's tested-device wiki lists **zero** OnePlus/OPPO devices. QCSuper lists OnePlus One, 3, Nord and Nord CE 2 Lite (all old), plus the OnePlus 9 report in §2 which needed `--adb`. The OnePlus 12 has an open "no /dev/diag" issue. Combined with §1.2 (no OnePlus tree of any generation since sm8250 ships the driver), treat the whole current OnePlus/OPPO/Realme line as unavailable.

---

## 5. Wireshark path — unchanged and good on the recommended route

This matters because the product is built on Wireshark decode, and the Samsung route does not compromise it at all.

- **[PRIMARY]** SCAT writes **PCAP with GSMTAP encapsulation** — GSMTAPv2 for 2G/3G/4G, **GSMTAPv3 for 5G NR**; control plane to UDP **4729**, user plane to UDP **47290**. Wireshark 2.6.0+ handles 2G–4G; **3.0.0+ is required for the 5G GSMTAPv3 packets, 4.2.5+ recommended.**
- **[PRIMARY]** SCAT decodes RRC, NAS, MIB/SIB, MAC and (partially) PDCP for LTE, and NR RRC + NAS-5GS for 5G. It can capture live from a USB diag serial port (`-u -a <bus>:<dev>` or `/dev/ttyUSB*`) or parse saved **QMDL** (Qualcomm), SDM (Samsung) and LPD (HiSilicon) dumps offline.
- **[PRIMARY]** QCSuper likewise emits PCAP with GSMTAP and can pipe straight into a live Wireshark (`--wireshark-live`), reading from `--usb-modem` (a pseudo-serial DIAG port over USB, no root needed once the phone is mode-switched) or `--adb` (root).
- **[REASONED]** On a `*#0808#`-enabled Samsung, the host sees a standard Qualcomm HS-USB Diagnostics interface (class `FF`, subclass `FF`, protocol `0x30` — the signature FieldTap already matches on per `DEVICE-SETUP.md`), so the existing Windows/macOS transport work applies unchanged. Nothing about the recommended handset route requires new decode plumbing.

**Caveat on completeness:** SCAT's NR MAC support was still "planned" as of v1.3.0/v2.0.0 and LTE MAC decode is explicitly early-phase; RRC/NAS/MIB/SIB — the layers this product needs — are the mature part.

---

## 6. Buy-this recommendation

### First choice — **Samsung Galaxy S23 (SM-S911B or SM-S911U1), unlocked, ~$300–450 used / refurb**

- Snapdragon 8 Gen 2 **in every region**, so there is no chipset lottery.
- **No root, no bootloader unlock, no Knox trip.** Dial `*#0808#`, pick a mode containing **DM**, plug in.
- Explicitly listed in SCAT's tested table as `-t qc`, **root not required**, and the SCAT maintainer confirms the SM8550 S23 works "as long as USB port setting includes DIAG/QDSS".
- The S23 Ultra is the exact device whose NR RRC OTA hexdumps drove SCAT PR #96, so 5G NR decode is proven on this silicon.
- Buy the **unlocked** variant. A Verizon/AT&T-branded unit needs a third-party secret-code unlock first.
- Requires: USB debugging on; re-run `*#0808#` after major OTAs; keep it off Samsung's "auto-update" if you want a stable capture baseline.

### Second choice — **Samsung Galaxy S25 (SM-S931B), ~$600–750**

- Snapdragon 8 Elite in **all** regions, so same no-lottery property, and it is the current generation with years of security support left — the right pick if the demo device has to look current in front of an operator.
- Same `*#0808#` procedure, last independently verified on One UI 6/7; **One UI 8/8.5 behaviour is not confirmed in this research** — validate on the actual unit before quoting it to a customer.
- If budget allows only one device and it must impress, buy this; if budget allows two, buy an S23 as the known-good control and an S25 as the forward-looking one.

### Explicitly do not buy for this purpose

- Any **Exynos** Galaxy (European S22, European/UK/India S24 and S24+, all A-series Exynos) — no Qualcomm DIAG.
- Any **Pixel** — Tensor, no Qualcomm baseband.
- Any **OnePlus/OPPO/Realme**, including the 10 Pro already on the bench.

### Third choice, only if a rooted device is acceptable — **Motorola Edge (40 Pro / 30 Ultra class)**

Officially unlockable bootloader, diag driver still present, confirmed working with QCSuper, `setprop sys.usb.config diag,serial_cdev,rmnet,dpl,qdss,adb` after root. Cheaper than a Galaxy flagship, but it is a rooted phone — a worse artefact to hand an operator.

---

## 7. Open questions worth closing before committing

1. **One UI 8 / 8.5:** is `*#0808#` still present on a 2026-shipping S25? Last solid confirmation is One UI 6/7.
2. **Secure log:** confirm plaintext RRC OTA on whichever exact SKU is purchased, on the first day. SCAT will tell you immediately ("Secure log" messages instead of decoded frames).
3. **US carrier SKUs:** if a customer's fleet is carrier-branded, the secret-code re-enable step is a third-party Windows tool. Decide now whether that is acceptable in the product's setup instructions, or whether the answer is "buy unlocked handsets".
4. **`diag_mdlog` on OnePlus:** the cheap `find /vendor /system -iname '*diag*' -o -iname '*mdlog*'` check flagged in `oneplus-10-pro-diag.md` is still worth running — not because it will resurrect DIAG, but to close the file on that device.

---

## Sources

**Kernel / GKI**
- CodeLinaro `clo/la/kernel/msm-5.10`, `drivers/char` tree listing: https://git.codelinaro.org/api/v4/projects/clo%2Fla%2Fkernel%2Fmsm-5.10/repository/tree?path=drivers/char&ref=kernel.lnx.5.10.r1-rel&per_page=100 (and the msm-5.4 / msm-4.19 equivalents)
- OnePlus SM8450 GKI kernel: https://github.com/OnePlusOSS/android_kernel_msm-5.10_oneplus_sm8450 — `modules.list.msm.waipio` (129 modules, no `diagchar.ko`)
- Samsung SM8450 kernel `modules.list.msm.waipio`: https://github.com/samsung-sm8450-kernel/kernel_msm/blob/lineage-19.1/modules.list.msm.waipio
- Xiaomi SM8450 `drivers/char/Makefile`: https://github.com/xiaomi-sm8450-kernel/android_kernel_platform_msm-kernel
- Last public diag source (kernel 4.19.325): https://github.com/LineageOS/android_kernel_xiaomi_sm8250/tree/lineage-23.2/drivers/char/diag
- `# required by diagchar.ko` symbol stanza: https://github.com/kerneltoast/android_kernel_google_redbull/blob/11.0.0-sultan/android/abi_gki_aarch64_qcom
- OnePlus SM8450 KMI lists + `gki_defconfig` (`CONFIG_MODVERSIONS=y`, `CONFIG_CRC8=y`, no `CRC_CCITT`): https://github.com/LineageOS/android_kernel_oneplus_sm8450
- `kernel_setsockopt` removal (Linux 5.9): https://patchwork.kernel.org/patch/11561221/ and https://lore.kernel.org/lkml/3123534.1589375587@warthog.procyon.org.uk/t/
- GKI project: https://source.android.com/docs/core/architecture/kernel/generic-kernel-image
- Loadable kernel modules ("Module-signing is not supported for GKI vendor modules"): https://source.android.com/docs/core/architecture/kernel/loadable-kernel-modules
- GKI FAQ (adding symbol-list entries to a frozen KMI): https://source.android.com/docs/core/architecture/kernel/gki-faq
- OnePlus custom-kernel build write-up (incomplete GPL drop, verity flags, bootloop recovery): https://pwner.gg/blog/2026-04-03-android-custom-kernel

**Tools and device evidence**
- SCAT: https://github.com/fgsect/scat — README (GSMTAP v2/v3, UDP 4729/47290, Wireshark versions, "Secure log" warning), releases (v1.3.0, v1.4.0, v2.0.0)
- SCAT tested devices wiki (S23 = `-t qc`, root not required; Pixel 6/7/9 = `-t sec`; Sony/Nexus/LG instructions): https://github.com/fgsect/scat/wiki/Devices
- SCAT PR #96, S23 Ultra / S24 Ultra NR RRC OTA: https://github.com/fgsect/scat/pull/96
- SCAT issue #98, unrooted S24 Ultra confirmed; rooted SM-S928B broke diag; `RMNET+DM+MODEM+ADPL+ADB` recipe: https://github.com/fgsect/scat/issues/98
- SCAT issue #87, maintainer on S23/SM8550: https://github.com/fgsect/scat/issues/87
- QCSuper: https://github.com/P1sec/QCSuper — README (kernel 4.14+ `/dev/diag` disappearance, `--usb-modem`, `com.longcheertel.midtest`, `AT$QCDMG`, supported devices)
- QCSuper confirmed-working issues: #150 OnePlus 9 rooted, #159 Fairphone 5, #143 Motorola Edge 30 Ultra, #123 OnePlus Nord CE 2 Lite: https://github.com/P1sec/QCSuper/issues?q=label:%22confirmed+working%22
- Fairphone 5 diag guide: https://github.com/Doct2O/fairphone5/tree/main/radio/cellular/qualcomm-diagnostic-mode
- Samsung `*#0808#` on the developer forum: https://forum.developer.samsung.com/t/secret-code-0808/12599
- S23 US/CA service-menu guide (mirror of the XDA thread; SamFW secret-code enable, model list, "no bootloader unlock or root required", CSC-update reset): https://addrom.com/how-to-enable-all-bands-through-service-menu-on-us-ca-samsung-galaxy-s23-series/ — original thread https://xdaforums.com/t/how-to-enable-all-bands-through-service-menu-on-us-ca-s23-series-including-sub-6-and-mmwave.4554611/ (403 to automated fetch)
- Qtrun Network Signal Guru device/chipset support: https://www.qtrun.com/eng/nsg/
- Galaxy S25 Snapdragon 8 Elite globally: https://9to5google.com/2025/01/22/samsung-galaxy-s25-snapdragon-8-elite-satellite/ , https://www.sammobile.com/news/galaxy-s25-snapdragon-8-elite-chip-exclusive-globally/
- Galaxy S24 Exynos/Snapdragon regional split: https://www.androidauthority.com/samsung-galaxy-s24-snapdragon-vs-exynos-countries-3402659/
