# Is an Android app worth building for FieldTap?

Researched 2026-09-10. Companion to `android-scanning-without-diag.md` (what non-root
gets you), `diag-capable-handsets.md` (which phones expose diag), and
`qualcomm-usb-modems.md` (the no-phone capture path).

## Confidence key

- **[VERIFIED]** — primary source, quoted or directly checked this session.
- **[COMMUNITY]** — field reports, issue trackers, vendor forums.
- **[REASONED]** — derived from a mechanism established above, not separately sourced.
- **[UNVERIFIED]** — plausible, not confirmed.

---

## Verdict

**No app now. Never the rooted on-phone capture app. Revisit later, and only in one
specific shape: an Android app as the *UI and host* for external capture hardware.**

The fact that decides it:

> **Neither of the two no-root diag paths the team found gives an on-device app
> anything.** Samsung's `*#0808#` and Xiaomi's `com.longcheertel.midtest` change the
> phone's **USB composition** — they publish DIAG as a USB endpoint pair *for a host on
> the other end of the cable*. They do not create an app-readable node on the phone. A
> phone cannot enumerate itself as a USB host to its own device port. **[REASONED]** —
> and consistent with the fact that every SCAT and QCSuper success report on those
> devices is host-side, never on-device
> ([SCAT device wiki](https://github.com/fgsect/scat/wiki/Devices)).

So the team's best discovery — no-root diag — is a **laptop-side capability by
construction**. An on-phone app that wanted the same data would have to go back to
requiring root, throwing away the exact advantage that was just found. And the target
handset (OnePlus 10 Pro) has no diag driver at all, and rooting a Samsung *breaks* diag
([SCAT #98](https://github.com/fgsect/scat/issues/98), quoted in
`diag-capable-handsets.md` §4.1). The rooted-app path is aimed at a device the team has
already decided not to buy.

The industry has already converged on the answer. Keysight's **Nemo Handy** is "the
world's most widely used handheld drive test tool" and is an Android app — and when
Keysight hit this same wall (no diag on commercial handsets) their answer was the **Nemo
Diagnostic Module**: *"an external hardware unit"* on USB to a non-rooted COTS
smartphone, powered by an external battery, worn on a hip belt, giving *"full measurement
functionality with commercial off-the-shelf smartphones. No special firmware or rooting is
needed"* **[VERIFIED]**
([Keysight NDM datasheet](https://www.keysight.com/us/en/assets/3125-1130/data-sheets/Nemo-Diagnostic-Module.pdf),
[press release](https://www.keysight.com/us/en/about/newsroom/news-releases/2020/1022-nr20126-keysight-s-new-handheld-measurement-software-suppor.html)).

That is the same architecture as "a Sierra EM7455 or Quectel RM520N on USB" — just with a
phone at the other end of the cable instead of a laptop. **If FieldTap ever builds an app,
build that one.** Everything else in this document is the supporting argument.

---

## 1. What an on-phone app can do that a laptop cannot

### 1.1 The honest answer about NSG's live on-handset decode

The user value of NSG decoding live on the handset is **not the decode**. On-phone decode
is strictly *worse* than the laptop view: a 6-inch screen, no Wireshark, no multi-pane
tree, no display filter language, no `tshark` scripting. Nobody reads a 2 kB
`RRCConnectionReconfiguration` on a phone screen by choice.

What the on-phone decode actually buys is **triage and confirmation in the field**:

- 6–10 KPI tiles changing live (RSRP / RSRQ / SINR / PCI / band / CA state / throughput).
- A scrolling **message list** where the engineer spots the one red event — a drop, a
  reject cause, a failed handover — as it happens.
- Confirming *"the handover fired here, at this spot, on this floor"* while still standing
  on the spot, so the next walk can be adjusted immediately rather than tomorrow.

The deep read still happens later, on a laptop, from the exported log. **[REASONED]** This
reframing matters: an on-phone tool's real job is capture-and-triage, and its output is a
log file that goes to a laptop anyway — which is exactly why the "app as host for external
hardware" shape (§2.5) is the only sensible one.

### 1.2 What genuinely requires the phone

| Capability | Why a laptop cannot do it |
|---|---|
| **Walk test / in-building survey** | Stairwells, lifts, retail floors, hospital wards, stadium concourses, narrow streets. One person, one hand, no bag, no cable, no power. This is the entire reason the handheld category exists — Keysight sells it as testing "on foot in areas where vehicle access is limited or impossible" **[VERIFIED]** ([Keysight Handheld Testing](https://www.keysight.com/us/en/products/ue-ran-and-core-emulators/rf-network-drive-test-solutions/handheld-testing.html)). |
| **Discreet measurement** | Keysight's own word is "discreet". Competitive benchmarking inside a rival operator's store, or measuring in a jurisdiction where a rig with antennas draws attention. A phone in a hand is invisible; a laptop with a modem on a strap is not. |
| **The DUT is the tester** | An on-phone app places the VoLTE call, runs the video session, and computes MOS on the *actual audio path of the actual UE*, correlated with L3 from the same modem with zero clock skew and no relay hop. FieldTap's `traffic.py` drives the same tests over adb — same measurements, but of a phone a laptop is puppeting. For QoE (MOS, video stall, app-launch latency) the on-device path is genuinely better instrumented: you can hook the media player and the app's own timings. **[REASONED]** |
| **Live feedback at the antenna** | Rooftop or lift, adjusting tilt/azimuth, watching RSRP/SINR move in your hand as you move it. A laptop is genuinely the wrong tool. |
| **Network control from the device** | NSG's real differentiator is not decode: it is lock-band, lock-PCI, force-RAT, disable-NR, trigger-reselection *from the handset*. That has to execute on the handset. (It also needs root.) |
| **Distribution scale** | An app reaches 500 field techs or 50,000 crowdsourcers. A laptop tool with a driver install does not. CellMapper: **1M+ installs**; NSG: **500k+ installs** **[VERIFIED]**. |

### 1.3 What the laptop keeps — and it is the part FieldTap is built on

- **Wireshark's ASN.1 dissectors as the authority.** FieldTap's core design decision
  (`docs/research/qualcomm-measurement-log-layouts.md`; the PER encodings in `demo.py`
  were calibrated *against* tshark). An on-phone app either re-implements RRC/NAS ASN.1 or
  ships a cut-down dissector — either way it gives up the thing that makes FieldTap
  trustworthy.
- Multi-device aggregation (4–8 UEs in a benchmarking rig).
- Storage, post-processing, scripting, `report.html` generation.
- **The standalone modem modules** — they need a USB host with power.
- **No root.**

### 1.4 Who buys the handheld, and why

1. **Operator RF optimisation teams** doing in-building and small-cell verification. They
   already buy Nemo Handy or TEMS Pocket at multi-thousand-per-seat. FieldTap is not
   displacing Keysight here in the near term.
2. **Subcontractors and system integrators** doing site-acceptance walk tests. Priced out
   of XCAL/Nemo. This is exactly the segment **HiCellTek** targets — an Android app using
   "Native Qualcomm DIAG access", sold as a SaaS subscription against "$20,000–$80,000+"
   incumbents, pitched at "subcontractors and integrators" **[VERIFIED]**
   ([HiCellTek](https://hicelltek.com/en/android-drive-test-alternative/)). This is the
   only segment an app could realistically win — and it is precisely the segment that is
   hardest to serve without root, because they use whatever handsets they already own.
3. **Crowdsourcers and enthusiasts.** Free, funded by a map/premium tier. CellMapper's
   model.

Segment 2 is the app opportunity. Segment 2 is also the one blocked by §2.

---

## 2. What an app would need to access diag

Confirmed: **root, or a system/privileged app, or a vendor-signed APK.** There is no
fourth option on the phone's own modem.

### 2.1 `/dev/diag` is not reachable from an ordinary app

Even where the node exists with permissive DAC bits (reported as `crw-rw-rw-`, group
`qcom_diag` on some builds), **SELinux MAC blocks an `untrusted_app` domain from opening a
diag character device**. Opening it from a JNI shared library returns `Permission denied`,
and setting SELinux permissive does not reliably fix it; the working pattern is a separate
native **executable run as root** **[COMMUNITY]**
([AOSP SELinux device policy](https://source.android.com/docs/security/features/selinux/device-policy)).

That is exactly the architecture of:

- **MobileInsight's `diag_revealer`** — "a proxy daemon between in-device diagnostic port
  (`/dev/diag`) and MobileInsight monitor" **[VERIFIED]**
  ([mobileinsight.net/diag-revealer.html](http://www.mobileinsight.net/diag-revealer.html),
  [source](https://github.com/mobile-insight/mobileinsight-mobile/blob/master/diag_revealer/qcom/jni/diag_revealer.c));
- **FieldTap's own `device/fieldtap-diagd.c`** — 344 lines, already written, opens
  `/dev/diag` as root, `DIAG_IOCTL_SWITCH_LOGGING` into `MEMORY_DEVICE_MODE`, relays HDLC
  frames over a TCP socket via `adb forward`. Its header comment already notes the ioctl
  argument struct "has grown across kernel generations (we try each shape)".

So FieldTap has the hard native piece already. What it does not have is a reason to point
it at an app instead of at adb.

### 2.2 MobileInsight is the cautionary precedent, not the encouraging one

MobileInsight is the canonical open-source on-device diag app: Apache-2.0, UCLA/Purdue,
peer-reviewed at MobiCom '16. Its current state: **incompatible with Android 11+ SELinux
policy, requires per-kernel compilation, and diag collection needs root, so it deploys
only to rooted Android 11 devices** **[COMMUNITY]**
([mobileinsight-mobile](https://github.com/mobile-insight/mobileinsight-mobile)). A funded
academic project with the exact same architecture stalled on exactly this maintenance
surface. That is the shape of the liability, not a hypothetical.

### 2.3 NSG's actual requirements — the market leader cannot escape root either

From NSG's own Play listing **[VERIFIED]**
([Play](https://play.google.com/store/apps/details?id=com.qtrun.QuickTest),
[listing mirror with install count](https://apkcombo.com/network-signal-guru/com.qtrun.QuickTest/)):

- **Qualcomm and MediaTek: root required.**
- **Samsung Exynos: needs a *token from Samsung*** — a vendor-issued credential, not
  something a third party can obtain. (Corroborated by Samsung's own community forum:
  S10/S20/S21/S22 owners need a Samsung-issued token to recover the diag port after root
  **[COMMUNITY]**.)
- **Huawei Kirin: custom ROM preferred.**
- Free on Play; **500,000+ installs**, 3.9★ from 4,227 ratings; on Play since January 2016.
- Qtrun's own site confirms coverage of Qualcomm X50–X70, MediaTek Dimensity, Exynos and
  Kirin, and "full recording and decoding of protocol layers"
  ([qtrun.com/eng/nsg](https://www.qtrun.com/eng/nsg/)).

500k installs and no way around root. If there were a no-root on-device diag trick, NSG
would be using it.

Note also: **NSG being on Play for a decade settles §4's first question** — a
root-requiring diagnostic app *is* publishable.

### 2.4 Vendor-signed / privileged is not obtainable

- **Xiaomi `com.longcheertel.midtest`.** QCSuper's README describes it precisely: *"it may
  also be possible to use an APK file signed by the phone vendor and with System-related
  permissions in order to enable the Diag mode without rooting"* **[VERIFIED]**
  ([QCSuper](https://github.com/P1sec/QCSuper)). You cannot obtain that signature. You can
  only *invoke the vendor's existing app*
  (`adb shell am start -n com.longcheertel.midtest/com.longcheertel.midtest.Diag`)
  **[COMMUNITY]** — and per the Verdict, that only flips the USB composition. It is a
  laptop enabler, not an app capability.
- **`READ_PRIVILEGED_PHONE_STATE` and `MODIFY_PHONE_STATE` are `signature|privileged`**
  — grantable only to platform-signed apps, or apps in a `priv-app` directory on a system
  image with an allowlist entry in `/etc/permissions`. Third-party apps, whether from Play
  or sideloaded, cannot hold them **[VERIFIED]**
  ([privileged permission allowlist](https://source.android.com/docs/core/permissions/perms-allowlist),
  [Android 10 privacy changes](https://developer.android.com/about/versions/10/privacy/changes)).
  `TelephonyManager.requestNetworkScan()` — a real multi-operator PLMN scan — sits behind
  `MODIFY_PHONE_STATE` and is therefore permanently out of reach (already established in
  `android-scanning-without-diag.md` §2.2).

### 2.5 The one no-root on-phone path that *does* exist

**Android's USB Host API.** `UsbManager` → `UsbDevice` → `UsbInterface` →
`claimInterface()` → bulk transfers on a **vendor-specific interface**. Available since
Android 3.1, **no root, no ADK, no special kernel driver**, driver selection by VID/PID,
one per-device user consent dialog **[VERIFIED]**
([USB host overview](https://developer.android.com/develop/connectivity/usb/host)).

The Qualcomm diag interface is USB vendor class `FF/FF/0x30` (see
`fieldtap-host-platforms` memory / `docs/MACOS.md`). That is claimable from a normal
Android app. **An Android phone or tablet with OTG can therefore read a Sierra EM7455,
Quectel EG25-G or RM520N exactly the way the laptop does, with no root anywhere in the
system.**

This is not speculative — it is the Nemo Diagnostic Module architecture, shipping since
2020 (§Verdict). Practical caveats to design around **[REASONED]**:

- **Power.** An M.2/mini-PCIe modem draws well beyond what a phone's OTG port will supply
  during TX bursts. Keysight's NDM uses an external battery for exactly this reason. Plan
  on a powered hub or a self-powered enclosure.
- **Two radios.** The module is the measurement UE; the phone keeps its own SIM for
  backhaul/upload. That is a feature (upload logs live) and a cost (two SIMs).
- **The pcapng still wants a laptop.** On-device you get triage; the real analysis is
  Wireshark on the host, from the exported file.

---

## 3. What a non-root Android app can still do — and is it a product?

`android-scanning-without-diag.md` §4 already establishes the ceiling and that NetMonster
is the open-source proof of it. Specifics worth pinning down here:

### 3.1 The exact public-API surface

| API | What it yields |
|---|---|
| `TelephonyManager.getAllCellInfo()` | `CellInfoNr` / `CellInfoLte` / `CellInfoWcdma` / `CellInfoGsm`, each carrying a `CellIdentity*` (MCC/MNC, TAC, NCI or ECI, PCI, NRARFCN/EARFCN, band list, `additionalPlmns`, operator alpha) and a `CellSignalStrength*` (SS-RSRP/SS-RSRQ/SS-SINR for NR; RSRP/RSRQ/RSSI/RSSNR/CQI/timing-advance for LTE). Serving cell plus whatever neighbours the modem happens to be measuring. |
| `TelephonyCallback` / `PhoneStateListener` | `onSignalStrengthsChanged`, `onCellInfoChanged`, `onServiceStateChanged`, `onDataConnectionStateChanged`, `onDisplayInfoChanged`, and `onPhysicalChannelConfigsChanged` (public from Android 12) — the last is the sanctioned route to carrier-aggregation / bandwidth / NSA-vs-SA detail. |
| `ServiceState` / reflection | What netmonster-core uses for LTE-A and NR-NSA CA detection on Android P+, plus its MOCN post-processor **[VERIFIED]** ([netmonster-core README](https://github.com/mroczis/netmonster-core/blob/master/README.md)). |
| `requestCellInfoUpdate()` | Rate-limited; on Q+ targets it no longer force-refreshes the modem. |

### 3.2 The hard limits

- **Since Android 10, `ACCESS_FINE_LOCATION` *and* location services enabled are required**
  for `getAllCellInfo()` to return anything at all to a non-system app **[VERIFIED]**
  ([Android 10 privacy changes](https://developer.android.com/about/versions/10/privacy/changes)).
- **Throttled.** App-driven refresh is rate-limited; real cadence is set by the RIL.
- **Neighbours are opportunistic**, not a scan.
- **AOSP gaps that netmonster-core documents in its own README**: cannot distinguish
  HSPA+42 from HSPA+; AOSP offers no native way to tell whether CA is active
  **[VERIFIED]**.
- **Never available without diag:** any RRC or NAS message, MIB/SIB content, barring flags,
  `MeasurementReport`, handover events with cause codes, BLER, MCS, rank, layer count, PRB
  allocation, HARQ, transmit power, pathloss, PUSCH/PDSCH scheduling — i.e. **everything in
  FieldTap's `decode/` tree**. A no-root app cannot produce a single row of what FieldTap
  currently produces.

### 3.3 Comparables and their economics

| Product | Root | Model | Numbers **[VERIFIED]** |
|---|---|---|---|
| **NetMonster** (+ open-source `netmonster-core`) | No | Free / freemium, solo dev | The reference implementation of the public-API ceiling. Its existence is the proof there is no secret extra API. |
| **G-NetTrack Pro** | Not required | **$34.99 one-time**, no subscription | **~220 downloads / 30 days**; 700 ratings, 4.44★ ([AppBrain](https://www.appbrain.com/app/g-nettrack-pro/com.gyokovsolutions.gnettrackproplus), [gyokovsolutions](https://gyokovsolutions.com/g-nettrack/)). ≈ **$7.7k/month gross** at that rate — a viable solo business, not a company product line. Notably it already does *"Bluetooth control of multiple phones"* — the companion-app pattern. |
| **CellMapper** | Base no; some band/bandwidth detail on Qualcomm/Sony/Samsung historically needed root, with a newer non-root path | Free; the crowdsourced map *is* the product | **1M+ installs**, 2.3★ from 3.59k reviews ([Play](https://play.google.com/store/apps/details?id=cellmapper.net.cellmapper), [CellMapper app settings docs](https://www.cellmapper.net/Android_App_Settings)) |
| **Network Signal Guru** | **Yes** (Qualcomm/MTK) | Free on Play; money is in operator-grade licences and paid advanced features | **500k+ installs**, 3.9★ / 4,227 ratings, on Play since Jan 2016 |
| **HiCellTek** | Uses "Native Qualcomm DIAG" | **SaaS: trial / monthly / annual** | Positions explicitly as the XCAL/Nemo alternative for subcontractors |

### 3.4 Is a no-root app a worthwhile product on its own?

**No.** It would be a fifth entrant into a category where the incumbents are free or
$35 one-time, where the technical ceiling is fixed by AOSP and identical for everyone, and
where FieldTap's actual differentiator (real L3 through Wireshark's dissectors) is
structurally unreachable. Worse, it would teach the market that FieldTap is a NetMonster
clone — which is the opposite of the positioning the L3 work has earned.

**The one legitimate role for a no-root app inside FieldTap** is as a **companion**, not a
product: GPS/location logging with better fidelity than scraping Android location over adb,
session start/stop, a live "am I still capturing?" tile, field notes and site photos tagged
to the track, and public-API KPIs as a cross-check against the diag stream. Paired to the
laptop or to the module over Bluetooth/USB. G-NetTrack Pro's Bluetooth multi-phone control
shows the pattern is accepted in this market.

---

## 4. Google Play policy

### 4.1 A root-requiring diagnostic app: publishable — empirically yes

NSG has been on Play since January 2016 with 500k+ installs while stating its own root
requirement in the listing. **[VERIFIED]**

The policy that bites is **Device and Network Abuse**, which prohibits apps that
"interfere with, disrupt, damage, or access in an unauthorized manner" a device, and
specifically apps that *"facilitate or provide instructions on how to hack services,
software or hardware, or circumvent security protections"* **[VERIFIED]**
([Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646)).
*Using* root the user already has is not the same as *providing* it.

Practical rules to stay inside the line:
- Ship the app **root-agnostic**: detect `su` at runtime, degrade to public-API mode.
- Never bundle an exploit, a rooting tool, or a Magisk module.
- Never link a rooting guide from inside the app or the listing.
- Expect Play Integrity / SafetyNet-style device exclusion to be *available to you*, not
  imposed on you — Play lets developers exclude uncertified/rooted devices, which is the
  opposite problem.

### 4.2 Continuous background location: the real friction

- `ACCESS_BACKGROUND_LOCATION` requires the **Permissions Declaration Form** plus **a video
  demonstrating the in-app feature that needs it and the steps to reach it**; background
  location "may only be used to provide features beneficial to the user and relevant to the
  core functionality" **[VERIFIED]**
  ([Understanding location in the background](https://support.google.com/googleplay/android-developer/answer/9799150)).
  Inaccurate declarations can suspend the developer account.
- **Android 14+ foreground service types.** You must declare `FOREGROUND_SERVICE_LOCATION`
  in the manifest, hold the matching permission, **and** declare the type in Play Console
  with a justification. Missing the permission throws at service start; missing the Console
  declaration gets the update **rejected**; and **Play rejects apps that declare the
  location FGS type without genuinely using it** **[VERIFIED]**
  ([FGS types required](https://developer.android.com/about/versions/14/changes/fgs-types-required),
  [Play FGS declaration](https://support.google.com/googleplay/android-developer/answer/13392821)).
  (April 2026 update: geofencing was removed as an approved FGS use case — not relevant
  here, but it shows the list is actively pruned.)
- **The cheap way out:** a drive-test session is *started by a human, in the foreground*.
  A location-type foreground service with **while-in-use** location covers a session that
  begins in the app and continues with the screen on or the app visible.
  `ACCESS_BACKGROUND_LOCATION` only becomes necessary for **unattended or scheduled**
  collection. Design for the former and the hardest Play review disappears. **[REASONED]**

### 4.3 `QUERY_ALL_PACKAGES`: do not use it

Permitted only where broad app visibility **is** the core user-facing purpose — Google's
examples are device search, antivirus, file managers, browsers — requires the declaration
form, and is explicitly disallowed *"when the required task can be done with a less broad
app-visibility method"* **[VERIFIED]**
([broad package visibility policy](https://support.google.com/googleplay/android-developer/answer/10158779)).
A drive-test app does not qualify. Everything it would plausibly need (launch a vendor diag
activity, check for a helper) is served by narrow `<queries>` entries in the manifest.

### 4.4 `READ_PRIVILEGED_PHONE_STATE`: unobtainable

`signature|privileged` (§2.4). Not a Play policy question at all — it is a build-time
impossibility for anything not on the system image. Design as if it does not exist.

### 4.5 Distribution channel — sideloading is narrowing, plan for it

Android developer verification is rolling out now **[VERIFIED]**
([Android Developers Blog, March 2026](https://android-developers.googleblog.com/2026/03/android-developer-verification-rolling-out-to-all-developers.html),
[support doc](https://support.google.com/android-developer-console/answer/16561738)):

- Verification opened to **all developers, March 2026**.
- **30 September 2026**: apps must be registered by a verified developer to install or
  update on certified devices in **Brazil, Indonesia, Singapore, Thailand**.
- **2027**: global rollout to all certified Android devices.
- Unverified apps get an "advanced sideloading flow" with a **24-hour wait**.

**Exemptions that matter to a B2B tool:**

| Channel | Status |
|---|---|
| Enterprise / managed devices via an organisation's own store | **Exempt** |
| ADB installs on your own devices | **Exempt** |
| Non-certified devices (no GMS) | **Exempt** |
| "Limited distribution" account | **Exempt from ID verification, up to 20 devices** |

So the realistic channels, in order:

1. **Managed / enterprise distribution** (EMM, managed Google Play private app). Exempt,
   clean, and matches the buyer — an operator or integrator with a device fleet. **This is
   the right channel for a rooted diag app.**
2. **Play, with a root-optional build** — fine for a free or companion tier.
3. **Direct APK** — still works, but budget for verification and the 24-hour friction, and
   complete verification early regardless.

The "limited distribution, 20 devices" account is a genuinely good fit for a pilot with one
customer's field team.

---

## 5. Effort: an app versus extending the Python tool

**FieldTap today:** ~**8,371 lines of Python** across `fieldtap/`, ~**1,171 lines of
tests**, plus **344 lines of C** in `device/fieldtap-diagd.c`. Wireshark does the ASN.1;
`report.py` produces a self-contained `report.html`. 61 tests pass.

### Three honest app scopes

**(a) Companion app — non-root, no diag.**
GPS + public-API KPIs + session control + live status, paired to the laptop or module.
- First version: **4–6 weeks**, one Android developer.
- Maintenance: **~1–2 weeks per quarter** (target-SDK bumps are annual and mandatory to
  stay on Play; permission/FGS declarations move most years).
- Risk: low. Standalone value: low. Useful only as an accessory.

**(b) On-phone rooted diag app — the NSG / MobileInsight shape.**
Port transport + HDLC + decode registry to run on-device; ship a native daemon invoked via
`su`; build a mobile UI (map, KPI tiles, message list, filters); **replace Wireshark**
with your own dissector or an on-device tshark.
- First credible release: **4–6 months**, one to two developers.
- Maintenance: **unbounded** — per-device root quirks, per-kernel diag ioctl struct shapes
  (the daemon already tries multiple), SELinux changes each Android release. MobileInsight
  died here.
- And it targets a device the team cannot buy: the OnePlus 10 Pro has no diag driver, and
  rooting a Samsung breaks diag.
- **Do not build this.**

**(c) App as host for an external module — the NDM shape.**
Android USB Host API + FieldTap's decode logic, talking to an EM7455 / EG25-G / RM520N.
- The catch nobody should gloss over: **the decode logic is Python.** Running it on Android
  means either Chaquopy/Kivy (heavy — this is part of what aged MobileInsight badly) or a
  **Kotlin/Java rewrite** of transport, HDLC framing, log-code registry and the record
  decoders. Realistically **~2.5k–3.5k lines of Kotlin** to reach parity with the current
  pcapng-writing point.
- First release: **3–4 months**.
- You still lose Wireshark on-device, and you still hand the pcapng to a laptop for real
  analysis — so this is a *capture-and-triage front end*, which §1.1 says is the right job
  anyway.
- Maintenance: moderate and *bounded* — USB host API is stable, and the module's diag
  dialect does not change under you the way a phone's kernel does.

### Versus finishing the laptop + module path

The module path is **already most of the way there**: `transport.py` has a libusb path,
the decode tree exists, the report exists, and `qualcomm-usb-modems.md` §7 scopes the work
as **three changes before the first bench test**. That is **days to weeks**, and it is on
the critical path to a demo no matter what happens with an app.

**Ratio: an app is roughly 10–30× the remaining effort of finishing the laptop + module
path, for zero additional decode capability.**

---

## 6. Recommendation

### Build an app: **not now; never in shape (b); later only in shape (c).**

**Now (weeks):**
1. Finish **laptop + EM7455 / EG25-G** per `qualcomm-usb-modems.md` §7–8. It is the only
   path with no root, no OEM dependency, and no phone. Get RRC + NAS from real hardware
   into Wireshark. Nothing else should compete for attention until that exists.
2. Keep non-root public-API collection as a **laptop feature over adb** (already
   documented and scripted in `android-scanning-without-diag.md` §5) — not as an app.
3. Do the **Android developer verification** paperwork anyway. It is free, it takes a
   week of calendar time, and it expires the "we can't ship an APK" excuse before it
   matters (Sept 2026 in four markets, 2027 globally).

**Later — build the app when at least two of these are true:**

- A **paying customer** asks for walk test / in-building specifically, **in writing**, and
  will not accept a laptop in a backpack. (Not "an engineer would like it." A line item.)
- The **module path is proven end-to-end on hardware** — real RRC and NAS from a real
  EM7455/RM520N in Wireshark. Until then an app is a UI for a pipeline that has never run.
- You have someone who **owns Android as a platform**: target-SDK bumps, Play declarations,
  the device matrix, the release train. This is a recurring tax, not a project. Without a
  named owner it rots exactly like MobileInsight did.
- A **second buyer segment** appears at a price point that supports a subscription — the
  subcontractor/integrator slot HiCellTek is currently occupying.

**When you do build it, build shape (c):** an Android app that is the UI, GPS source,
uploader and triage screen for an **external USB module**, using the USB Host API with no
root anywhere. That is Keysight's own answer to this exact problem, it preserves the
no-root advantage the team just discovered, and it reuses the module work rather than
competing with it.

**Never build shape (b)** — the rooted on-phone capture app. It requires root the target
devices cannot give, breaks diag on the one handset family that works, and inherits a
maintenance surface that killed a funded academic project.

**Never triggers:** "our engineer wants NSG but free" is a $0 requirement. If a walk-test
need arrives before the module path is ready, the honest short-term answer is to **buy**:
G-NetTrack Pro is $34.99, NSG is free, and neither threatens FieldTap's differentiation,
which lives on the L3-into-Wireshark side.

### What would change this answer

- **A no-root, on-device, app-readable diag path appearing on a shipping handset.** It
  would invalidate §2 wholesale. Watch NSG's release notes — with 500k installs they will
  find it before anyone else. Currently there is no evidence such a path exists.
- **A customer buying a device fleet.** If the customer supplies the handsets, "rooted
  Samsung" stops being a support nightmare and becomes a spec — though SCAT #98's
  root-breaks-diag report still applies.
- **The Python-on-Android question resolving.** If the decode tree were ported to a
  language that runs natively on both (Rust or Kotlin with a Python binding), shape (c)'s
  cost drops by more than half and the calculus shifts earlier.

---

## Sources

**On-phone tools and their requirements**
- Network Signal Guru — [Play listing](https://play.google.com/store/apps/details?id=com.qtrun.QuickTest), [Qtrun product page](https://www.qtrun.com/eng/nsg/), [listing mirror with install count](https://apkcombo.com/network-signal-guru/com.qtrun.QuickTest/)
- MobileInsight — [mobileinsight-mobile](https://github.com/mobile-insight/mobileinsight-mobile), [diag_revealer code review](http://www.mobileinsight.net/diag-revealer.html), [diag_revealer.c](https://github.com/mobile-insight/mobileinsight-mobile/blob/master/diag_revealer/qcom/jni/diag_revealer.c), [MobiCom '16 paper](https://www.cs.purdue.edu/homes/chunyi/pubs/mobicom16-li.pdf)
- NetMonster Core — [README](https://github.com/mroczis/netmonster-core/blob/master/README.md)
- G-NetTrack Pro — [Play](https://play.google.com/store/apps/details?id=com.gyokovsolutions.gnettrackproplus), [AppBrain metrics](https://www.appbrain.com/app/g-nettrack-pro/com.gyokovsolutions.gnettrackproplus), [Gyokov Solutions](https://gyokovsolutions.com/g-nettrack/)
- CellMapper — [Play](https://play.google.com/store/apps/details?id=cellmapper.net.cellmapper), [Android app settings docs](https://www.cellmapper.net/Android_App_Settings)
- HiCellTek — [Android drive test alternative](https://hicelltek.com/en/android-drive-test-alternative/)

**Commercial handheld precedent**
- Keysight [Handheld Testing](https://www.keysight.com/us/en/products/ue-ran-and-core-emulators/rf-network-drive-test-solutions/handheld-testing.html), [Nemo Handy NTH50047B](https://www.keysight.com/us/en/product/NTH50047B/nemo-handy-handheld-measurement-solution.html)
- [Nemo Diagnostic Module datasheet](https://www.keysight.com/us/en/assets/3125-1130/data-sheets/Nemo-Diagnostic-Module.pdf)
- [Keysight press release, COTS smartphone support](https://www.keysight.com/us/en/about/newsroom/news-releases/2020/1022-nr20126-keysight-s-new-handheld-measurement-software-suppor.html)

**Android platform**
- [USB host overview](https://developer.android.com/develop/connectivity/usb/host)
- [Privileged permission allowlist](https://source.android.com/docs/core/permissions/perms-allowlist)
- [Android 10 privacy changes](https://developer.android.com/about/versions/10/privacy/changes)
- [Write SELinux policy](https://source.android.com/docs/security/features/selinux/device-policy)
- [Foreground service types are required (Android 14)](https://developer.android.com/about/versions/14/changes/fgs-types-required)

**Google Play policy**
- [Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646)
- [Understanding location in the background](https://support.google.com/googleplay/android-developer/answer/9799150)
- [Broad package visibility (QUERY_ALL_PACKAGES)](https://support.google.com/googleplay/android-developer/answer/10158779)
- [Foreground service and full-screen intent requirements](https://support.google.com/googleplay/android-developer/answer/13392821)
- [Permissions and APIs that access sensitive information](https://support.google.com/googleplay/android-developer/answer/16585319)

**Distribution**
- [Android developer verification rollout (March 2026)](https://android-developers.googleblog.com/2026/03/android-developer-verification-rolling-out-to-all-developers.html)
- [Understanding Android developer verification](https://support.google.com/android-developer-console/answer/16561738)
- [Sideloading changes timeline](https://www.androidauthority.com/android-sideloading-changes-timeline-3679204/)

**Diag access on handsets (context from sibling docs)**
- [SCAT tested devices wiki](https://github.com/fgsect/scat/wiki/Devices), [SCAT #98](https://github.com/fgsect/scat/issues/98)
- [QCSuper](https://github.com/P1sec/QCSuper)
