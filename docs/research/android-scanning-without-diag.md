# Cellular scanning/measurement on Android 15 over adb, without root and without Qualcomm diag

Target device: OnePlus 10 Pro, Android 15, no `/dev/diag` accessible. Question: precisely what the public Android telephony layer still gives a drive-test tool over plain `adb shell`.

Legend: **[VERIFIED]** = confirmed against AOSP source, official Android docs, or a primary technical source cited inline. **[INFERRED]** = reasoned from verified facts or from consistent secondary sources but not confirmed against a primary source in this session. **[UNVERIFIED]** = could not be pinned down this session; flagged so it gets checked on-device.

---

## 1. Listing nearby cells and operators

### 1.1 `adb shell dumpsys telephony.registry` — exact format

`dumpsys telephony.registry` dumps the `TelephonyRegistry` system-server object, which is a **cache**: it prints whatever `CellInfo`/`ServiceState`/`SignalStrength` objects were last pushed to it by the RIL's unsolicited `UNSOL_CELL_INFO_LIST` / `UNSOL_RESPONSE_SIGNAL_STRENGTH` events (fed to registered `PhoneStateListener`/`TelephonyCallback` listeners), **not** a fresh poll triggered by the dumpsys call itself. `dumpsys` itself only needs the `DUMP` permission (which `adb shell` holds), so it sidesteps the app-facing `ACCESS_FINE_LOCATION`/`READ_PHONE_STATE` checks and the `getAllCellInfo()` throttle described in §1.3 — you get whatever the framework already had cached, for free. **[INFERRED — this is the standard explanation for why dumpsys returns data instantly with no location permission grant; I could not fetch `TelephonyRegistry.java`'s `dump()` body directly this session (404 on the path I tried), so the mechanism is inferred from how `TelephonyRegistry` is known to work plus indirect confirmation that the printed objects are literally `CellInfo` subtype `toString()` output — see below.]**

The per-cell text is literally the `toString()` output of the public `android.telephony.CellInfo*` / `CellIdentity*` / `CellSignalStrength*` classes. I pulled the actual `toString()` implementations from AOSP `master` (frameworks/base) to get the exact field order — this is code, not a guess:

**Base `CellInfo.toString()`** (called via `super.toString()` from every subclass) appends, in order:
```
mRegistered=YES mTimeStamp=<ns>ns mCellConnectionStatus=<int>
```
[VERIFIED] — [CellInfo.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/CellInfo.java)

**`CellInfoNr.toString()`**: `CellInfoNr:{ ` + `super.toString()` + ` ` + `CellIdentityNr` + ` ` + `CellSignalStrengthNr` + ` }`
[VERIFIED] — [CellInfoNr.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/CellInfoNr.java)

**`CellInfoLte.toString()`**: `CellInfoLte:{` + `super.toString()` + ` ` + `CellIdentityLte` + ` ` + `CellSignalStrengthLte` + ` ` + `mCellConfig` + `}`
[VERIFIED] — [CellInfoLte.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/CellInfoLte.java)

**`CellIdentityNr.toString()`** — confirms the sample you already had:
```
CellIdentityNr:{ mPci = <int> mTac = <int> mNrArfcn = <int> mBands = <int[]> mMcc = <str> mMnc = <str> mNci = <long> mAlphaLong = <str> mAlphaShort = <str> mAdditionalPlmns = <set> }
```
[VERIFIED, matches your known-good sample exactly] — [CellIdentityNr.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/CellIdentityNr.java)

**`CellSignalStrengthNr.toString()`**, field order:
```
CellSignalStrengthNr:{ csiRsrp = <int> csiRsrq = <int> csiCqiTableIndex = <int> csiCqiReport = <list> ssRsrp = <int> ssRsrq = <int> ssSinr = <int> level = <int> parametersUseForLevel = <int> timingAdvance = <int> }
```
`UNAVAILABLE` shows as `2147483647`. Sample (Verizon n78, synthetic values for illustration, real field names/order verified):
```
CellSignalStrengthNr:{ csiRsrp = 2147483647 csiRsrq = 2147483647 csiCqiTableIndex = 2147483647 csiCqiReport = [] ssRsrp = -95 ssRsrq = -11 ssSinr = 18 level = 3 parametersUseForLevel = 1 timingAdvance = 2147483647 }
```
[VERIFIED field names/order; sample values illustrative] — [CellSignalStrengthNr.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/CellSignalStrengthNr.java)

**`CellIdentityLte.toString()`**:
```
CellIdentityLte:{ mCi=<int> mPci=<int> mTac=<int> mEarfcn=<int> mBands=<int[]> mBandwidth=<int> mMcc=<str> mMnc=<str> mAlphaLong=<str> mAlphaShort=<str> mAdditionalPlmns=<set> mCsgInfo=<obj>}
```
[VERIFIED] — [CellIdentityLte.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/CellIdentityLte.java)

**`CellSignalStrengthLte.toString()`**:
```
CellSignalStrengthLte: rssi=<int> rsrp=<int> rsrq=<int> rssnr=<int> cqiTableIndex=<int> cqi=<int> ta=<int> level=<int> parametersUseForLevel=<int>
```
(Note: this one omits the wrapping `{ }` braces the others use.) [VERIFIED] — [CellSignalStrengthLte.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/CellSignalStrengthLte.java)

**`CellIdentityGsm.toString()`**:
```
CellIdentityGsm:{ mLac=<int> mCid=<int> mArfcn=<int> mBsic=<hex> mMcc=<str> mMnc=<str> mAlphaLong=<str> mAlphaShort=<str> mAdditionalPlmns=<set>}
```
[VERIFIED] — [CellIdentityGsm.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/CellIdentityGsm.java)

**`CellIdentityWcdma.toString()`**:
```
CellIdentityWcdma:{ mLac=<int> mCid=<int> mPsc=<int> mUarfcn=<int> mMcc=<str> mMnc=<str> mAlphaLong=<str> mAlphaShort=<str> mAdditionalPlmns=<set> mCsgInfo=<obj>}
```
[VERIFIED] — [CellIdentityWcdma.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/CellIdentityWcdma.java)

**`CellSignalStrengthGsm.toString()`**: `CellSignalStrengthGsm: rssi=<int> ber=<int> mTa=<int> mLevel=<int>` [VERIFIED] — [CellSignalStrengthGsm.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/CellSignalStrengthGsm.java)

**`CellSignalStrengthWcdma.toString()`**: `CellSignalStrengthWcdma: ss=<int> ber=<int> rscp=<int> ecno=<int> level=<int>` [VERIFIED] — [CellSignalStrengthWcdma.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/CellSignalStrengthWcdma.java)

Putting it together, a plausible full LTE line (field order verified, exact whitespace may differ by a space or two from live output — worth diffing against a real capture once you have the phone in hand):
```
CellInfoLte:{mRegistered=YES mTimeStamp=242853112000000ns mCellConnectionStatus=2 CellIdentityLte:{ mCi=94739203 mPci=142 mTac=12345 mEarfcn=2000 mBands=[7] mBandwidth=2147483647 mMcc=311 mMnc=480 mAlphaLong=Verizon mAlphaShort=Verizon mAdditionalPlmns={} mCsgInfo=null} CellSignalStrengthLte: rssi=-73 rsrp=-97 rsrq=-11 rssnr=90 cqiTableIndex=2147483647 cqi=2147483647 ta=2147483647 level=3 parametersUseForLevel=1 mCellConfig=...}
```

**Practical note:** don't hand-write a regex against the "expected" spacing above — pull one real capture from the OnePlus 10 Pro first and diff it against these field lists, since AOSP vendors/OEMs occasionally tweak a space or reorder a rarely-used field between AOSP tags. The field **names** and their **presence** are what's stable and worth anchoring a parser to (e.g. `mAlphaLong = `, `ssRsrp = `, `mAdditionalPlmns = `), not exact column offsets.

### 1.2 Is there anything better than `dumpsys telephony.registry`?

- **`adb shell cmd phone <subcommand>`** — I pulled the actual dispatch table from `TelephonyShellCommand.java` (the class that implements `cmd phone` in `packages/services/Telephony`). Subcommands include `ims`, `uce`, `numverify`, `emergency-callback-mode`, `emergency-number-test-mode`, `end-block-suppression`, `cc` (carrier config), `data`, `gba`, `d2d`, `barring`, `src`, `restart-modem`, `callcomposer`, `unattended-reboot`, `has-carrier-privileges`, `thermal-mitigation`, `disable/enable-physical-subscription`, `get/set-allowed-network-types-for-users`, `get-imei`, `get-sim-slots-mapping`, `radio`, `carrier_restriction_status_test`, `domainselection`, satellite-related commands, IMSI-key deletion, attach-restriction commands. **There is no `cellinfo`/`servicestate`/`signalstrength`/`network-scan`/`sib`/`mib` subcommand anywhere in this class.** [VERIFIED] — [TelephonyShellCommand.java](https://android.googlesource.com/platform/packages/services/Telephony/+/master/src/com/android/phone/TelephonyShellCommand.java). So `cmd phone` is a dead end for cell listing; `dumpsys telephony.registry` remains the best shell-native source.
- **`dumpsys telephony.registry --all`** — I could not find documentation confirming an `--all` flag changes the output (the default dump already walks all `phoneId`/subId slots on multi-SIM devices). **[UNVERIFIED]** — worth a 5-second on-device check (`adb shell dumpsys telephony.registry --all` vs without) but don't rely on it existing.
- **`dumpsys connectivity`** — this is `ConnectivityService`, which dumps IP-layer network state (which `NetworkAgent`/transport is default, DNS, routes). It does **not** carry cell-radio measurement data (no RSRP/PCI/etc). Not useful for this task. **[INFERRED from what ConnectivityService is responsible for; not exhaustively read this session.]**
- **`adb shell service call phone <code>`** — `phone` is the registered binder name for `ITelephony`. You can invoke individual AIDL methods by transaction number (`service call phone <N> ...`), the way `service call phone 1 s16 "123"` dials a number via `TRANSACTION_dial`. The AIDL confirms the methods exist as raw binder calls: `getAllCellInfo(String callingPkg, String callingFeatureId)`, `requestCellInfoUpdate(int subId, ICellInfoCallback cb, ...)`, `getCellLocation(...)`, `getNeighboringCellInfo(...)`, `requestNetworkScan(int subId, boolean renounceFineLocationAccess, NetworkScanRequest request, Messenger, IBinder, String callingPackage, String callingFeatureId)` [VERIFIED, method signatures pulled straight from AOSP — [ITelephony.aidl](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/com/android/internal/telephony/ITelephony.aidl)]. In practice this is painful: `service call` needs raw Parcel marshalling of `List<CellInfo>`/callback binders which is not realistically doable from the shell one-liner form (unlike primitive-arg calls like `dial`), and the transaction numbers are **not stable across AOSP/OEM versions** (they're assigned by declaration order in the `.aidl`, which changes release to release, and OnePlus/OxygenOS may reorder further) — the classic blog reference for this technique is [ktnr74's "Calling Android services from ADB shell"](http://ktnr74.blogspot.com/2014/09/calling-android-services-from-adb-shell.html), which only demonstrates it for the trivial `dial`/`call`/`isRadioOn` style methods. **Verdict: theoretically present, not practically usable for `getAllCellInfo`/`requestNetworkScan` without also having the corresponding permission (see §2), so this doesn't buy you anything `dumpsys` doesn't already give you for free.**
- **`am start -a android.settings.NETWORK_OPERATOR_SETTINGS`** and **`settings` command** — see §2 below (these open UI / read settings values, they don't surface CellInfo).

### 1.3 Neighbour cells, and throttling

- **Neighbour cells**: `getAllCellInfo()`/the `TelephonyRegistry` cache is documented to include "the camped/registered, serving, and neighboring cells" [VERIFIED — this phrase is the official Android reference wording for `getAllCellInfo()`, corroborated across multiple citations of the javadoc; see [TelephonyManager reference](https://developer.android.com/reference/android/telephony/TelephonyManager)]. In practice, whether neighbours actually show up is modem/RIL-dependent: many modems (including Qualcomm ones in non-diag mode) only report neighbours they're actively measuring for handover, which for NR SA/NSA is often nothing beyond the serving cell unless the UE is near a cell edge and doing measurement reports. **Expect: reliable serving-cell data; neighbour-cell presence is opportunistic, not guaranteed.** [INFERRED from common field experience reported across drive-test tool communities (NetMonster/CellMapper forums), not independently re-verified this session.]
- **Throttling on `getAllCellInfo()`/`requestCellInfoUpdate()`**: official Android behavior, confirmed consistently across multiple citations of the javadoc: *"Apps targeting Android Q or higher will no longer trigger a refresh of the cached CellInfo by invoking `getAllCellInfo()`. Instead, those apps will receive the latest cached results, which may not be current. Apps targeting Android Q or higher that wish to request updated CellInfo should call `requestCellInfoUpdate()`; however, in all cases, updates will be rate-limited and are not guaranteed."* Callers should check `CellInfo#getTimeStamp()` to know how stale the data is; a cache-change is also pushed via `onCellInfoChanged()` callback. [VERIFIED — official phrasing, see [TelephonyManager#getAllCellInfo()](https://developer.android.com/reference/android/telephony/TelephonyManager#getAllCellInfo()) and corroborating discussion on the original P-preview thread, [issuetracker.google.com/issues/37086426](https://issuetracker.google.com/issues/37086426)]. The exact numeric rate-limit interval (I've seen "~2 seconds" quoted informally in various forum threads) is **[UNVERIFIED]** — I could not pin a specific millisecond constant to a primary source this session; treat any specific number as folklore until confirmed against `RIL.java`/`ServiceStateTracker` source or measured on-device.
- Practical implication for `dumpsys telephony.registry`: because it reads the **push-based cache** (populated whenever the RIL sends an unsolicited cell-info update, which is *not* subject to the app-facing `getAllCellInfo()` throttle), polling `dumpsys` every 1–2 seconds in a loop is the most reliable non-rooted way to get frequent updates. **[INFERRED]**

---

## 2. A real PLMN search (all operators in the area)

### 2.1 `am start -a android.settings.NETWORK_OPERATOR_SETTINGS`

`Settings.ACTION_NETWORK_OPERATOR_SETTINGS` = `"android.settings.NETWORK_OPERATOR_SETTINGS"` is a real, documented intent action (API level 1+) that opens the Settings UI screen for operator selection. [VERIFIED — this is a long-standing public `Settings` constant.] Command:
```
adb shell am start -a android.settings.NETWORK_OPERATOR_SETTINGS
```
Manually disabling "Select automatically" in that screen does trigger a genuine over-the-air PLMN scan — the Settings app calls into `TelephonyManager`/`NetworkScanHelper` (`packages/services/Telephony`, [NetworkScanHelper.java](https://android.googlesource.com/platform/packages/services/Telephony/+/492769b55cd09f2ad14549370da19f5073a9180b/src/com/android/phone/NetworkScanHelper.java)) which on modern Android issues a `TelephonyManager.requestNetworkScan()`/`TelephonyScanManager` request; on many modems this still resolves down to the classic RIL request `RIL_REQUEST_QUERY_AVAILABLE_NETWORKS` (or `RIL_REQUEST_START_NETWORK_SCAN` on modems that support the newer async scan API) at the vendor RIL layer. **[VERIFIED that Settings uses `requestNetworkScan`/`NetworkScanHelper`; INFERRED which specific RIL request a given OnePlus modem issues — that's vendor-specific.]**

**Is the result machine-readable?** Not through any content provider or dumpsys sink — the scan results are delivered back to the Settings UI via a callback and rendered as a list on screen; they are not cached into `TelephonyRegistry` (that cache is for the *serving* cell's `CellInfo`, not a PLMN-scan operator list) and there's no public API to read "the last manual PLMN scan result" after the fact. The only place the list surfaces outside the screen itself is **logcat**, specifically the radio log buffer:
```
adb logcat -b radio -v time | grep -Ei "RILJ|QUERY_AVAILABLE_NETWORKS|START_NETWORK_SCAN|GsmServiceStateTracker|NetworkScan"
```
Reported real-world RILJ lines from this path include entries like:
```
D RILJ  : [4548]> RIL_REQUEST_START_NETWORK_SCAN [SUB0]
D RILJ  : [4548]< RIL_REQUEST_START_NETWORK_SCAN
```
and, for the older query-based path, per-operator lines carrying operator long/short name, numeric PLMN, and status (`AVAILABLE`/`CURRENT`/`FORBIDDEN`). [VERIFIED presence of these RILJ tags via multiple independent threads — [android-platform group thread on RIL scan/neighbour cell for privileged apps](https://groups.google.com/g/android-platform/c/a8L_bO-q5GQ), [PixelExperience manual-network-selection issue](https://github.com/PixelExperience/android-issues/issues/2731); exact line formatting is vendor/AOSP-version dependent and I did not capture a byte-exact sample this session — **[UNVERIFIED exact formatting, VERIFIED that the tag and approach work]**.] Note: on Android 15 **user builds**, some of this radio-buffer logging may be stripped or rate-limited compared to `userdebug`/`eng` builds — confirm on the actual OnePlus 10 Pro build.

### 2.2 `TelephonyManager.requestNetworkScan()` / `NetworkScanRequest` from a sideloaded app

- **Signature/permission** (confirmed straight from an AOSP commit diff that added the requirement): `requestNetworkScan` carries `@RequiresPermission(allOf = { android.Manifest.permission.MODIFY_PHONE_STATE, android.Manifest.permission.ACCESS_FINE_LOCATION })`. [VERIFIED — [AOSP commit diff](https://android.googlesource.com/platform/frameworks/base/+/ee313737e970e95c77cdc229c315dd2c0e8551ce%5E!/telephony/java/android/telephony/TelephonyManager.java) adding `ACCESS_FINE_LOCATION` on top of the pre-existing `MODIFY_PHONE_STATE`.]
- **`MODIFY_PHONE_STATE`** is declared `protectionLevel="signature|privileged"` in the platform manifest — i.e. it can only be held by apps signed with the platform key, or apps in the privileged-app allowlist on the system partition (`privapp-permissions*.xml`). [VERIFIED as a general Android permission-model fact — [privileged permission allowlist doc](https://source.android.com/docs/core/permissions/perms-allowlist).] A normal sideloaded (`adb install`) app **cannot** hold it, and `adb shell pm grant <pkg> android.permission.MODIFY_PHONE_STATE` fails at runtime (the standard error pattern for any signature-level permission via `pm grant` is *"Security exception: Permission ... is not a changeable permission type"* — I confirmed this exact failure pattern occurs for other signature permissions such as `BATTERY_STATS`; I did not independently re-run it for `MODIFY_PHONE_STATE` specifically this session, but it is the same protection level so the same rejection applies). **[VERIFIED mechanism/protection level; INFERRED — by strong analogy, not independently executed — that `pm grant` specifically for `MODIFY_PHONE_STATE` is refused the same way.]**
- I checked whether ADB's own shell identity (UID 2000) gets an exemption in `TelephonyPermissions.java` (the class that gates every telephony API call). The only unconditional bypass I found in the version I fetched is for `Process.SYSTEM_UID`/phone-process UID on the **carrier-privilege** check path (`getCarrierPrivilegeStatus`); I did not find a blanket shell/root bypass applied to the read-phone-state or modify-phone-state checks used by `getAllCellInfo`/`requestNetworkScan` in the code I could read. [VERIFIED for the code paths inspected — [TelephonyPermissions.java](https://android.googlesource.com/platform/frameworks/base/+/master/telephony/common/com/android/internal/telephony/TelephonyPermissions.java) — but the file is large and I did not exhaustively review every check method, so treat "no shell bypass" as **[INFERRED, moderately confident]** rather than exhaustively proven.]
- **Bottom line: `requestNetworkScan` is effectively privileged/system-app-only.** An ordinary sideloaded app — even one launched via `adb shell am start`/`monkey`, even with every *grantable* runtime permission approved — cannot call it. This matches what you'd expect: it's the same reason third-party non-root apps (§4) don't offer a "real" PLMN scan button, only the Settings-UI workaround in §2.1.

### 2.3 Does any of this work with **no SIM inserted**?

- `getAllCellInfo()`/the `TelephonyRegistry` cache is scoped to whatever the modem is doing at the radio layer, and Android explicitly supports "camped for emergency service" with `SIM_STATE_ABSENT` — the modem still does cell search/camping without a SIM for E911 purposes, exactly as you're seeing on the Verizon n78 cell. Some patent/spec-literature language documents this general behavior ("cell search is normally performed for transmitting an emergency call ... even if the SIM card is not inserted"), but I did not find an Android-framework-specific citation confirming `getAllCellInfo()` continues to return non-empty results in this exact state (`SIM_STATE_ABSENT` + emergency camping) — this is the single most important thing to verify on your actual phone, since the answer directly gates whether `dumpsys telephony.registry` is useful for you at all right now. **[UNVERIFIED for Android specifically — verify with `adb shell dumpsys telephony.registry` on the OnePlus 10 Pro in its current SIM-less state; if `mCellInfo=null` or empty, that's your answer.]**
- **Manual network selection (§2.1) is widely reported to require a SIM**: consumer troubleshooting threads consistently report "Select Network" is greyed out with no SIM inserted, because PLMN selection is fundamentally about choosing which network to *register* on, and registration state machinery expects a USIM/SIM context. [INFERRED from consistent user-support-forum reports, not an AOSP source citation — treat as reasonably confident folklore, and confirm on-device.] If true, this means: with no SIM, you likely get the serving/camped cell's `CellInfo` (if the emergency-camping case above does populate it) but **not** a manual multi-operator PLMN scan, since the Settings screen path needs a SIM to even offer the option.

---

## 3. MIB and SIB decode without diag

**Confirmed: raw MIB/SIB1/SIB2… ASN.1 is NOT obtainable from any public Android API.** There is no public class, broadcast, or logcat channel on a stock non-rooted build that hands you undecoded RRC system-information PDUs. What you get instead is exactly the handful of **already-decoded fields** the modem's RIL implementation chooses to surface up through `CellIdentity`/`ServiceState`, specifically:

- From SIB1 (NR) / MIB+SIB1 (LTE) / SI (GSM/WCDMA): **MCC, MNC** (`mMcc`/`mMnc`), **TAC/LAC** (`mTac`/`mLac`), **Cell Identity** (`mNci` for NR — the 36-bit NR Cell Identity — or `mCi` for LTE, `mCid` for GSM/WCDMA), **PCI/PSC** (physical/primary scrambling code — this is actually PSS/SSS-derived, not strictly SIB content, but reported alongside), **ARFCN/UARFCN/EARFCN/NR-ARFCN**, **band list** (`mBands`), and **`mAdditionalPlmns`** — the equivalent-PLMN list a cell also serves, which genuinely does come from SIB1's PLMN-IdentityInfoList. **[VERIFIED — these are literal fields on the public `CellIdentity*` classes, confirmed against AOSP source in §1.1.]**
- What is **not** exposed: SIB2/SIB3+ scheduling and reselection parameters, barring info, `q-RxLevMin`, full neighbour-cell relation lists as broadcast (as opposed to what the modem chooses to report as "neighbour" `CellInfo`), MIB's raw `systemFrameNumber`/bandwidth-indicator bits, any of the RRC container itself. **[INFERRED, by absence — no public Android class exposes these; consistent with the general architectural fact that "Android telephony framework provides APIs for the phone application; [lower-level RIL] APIs cannot be entered from any other applications that are not part of the Android system."]**
- **Logcat on a stock non-rooted build**: the `radio` logcat buffer (`adb logcat -b radio`) carries RILJ request/response tracing (hex-encoded `OEM_HOOK_RAW` payloads and similar) but on **user** builds this is typically far less verbose than on `userdebug`/`eng` builds, and even at its most verbose it's RIL *request/response* framing, not decoded-or-raw SIB content — it will not hand you ASN.1. [VERIFIED that `logcat -b radio` exists and carries RILJ tracing — [example gist](https://gist.github.com/nicklaslof/3206907), [telecomhall forum example](https://www.telecomhall.net/t/android-watching-data-sessions-in-real-time/37043); **INFERRED** that it never carries SIB content on a stock build — I did not find a documented counterexample, and this is consistent with how DIAG-based tools like Qualcomm's own capture stack are the documented way to get raw SIBs (see §4 — QCSuper).]
- The clean confirmation that raw-SIB capture is a *diag-layer* problem, not a telephony-API problem: tools that actually decode raw SIBs (e.g. [QCSuper](https://github.com/P1sec/QCSuper), which "reassembl[es] SIBs... in separate GSMTAP frames") work by talking Qualcomm's **DIAG protocol** to the baseband, which is exactly the `/dev/diag` path this phone doesn't expose. There is no alternate, non-diag Android API route to the same data. [VERIFIED — QCSuper's own description confirms the DIAG dependency.]

**What IS derivable, summarized:** MCC/MNC, TAC/LAC, (N)CI/Cell-Id, PCI/PSC, (N)ARFCN, band, bandwidth (LTE), `additionalPlmns` (the multi-PLMN list), operator alpha-long/alpha-short, plus signal quality (RSRP/RSRQ/RSSI/SINR/RSSNR/CQI/ECNO/BER as applicable per RAT) and a `ServiceState`-level registration/roaming status. That is the complete non-diag surface.

---

## 4. Third-party apps — what non-root actually buys you

| App | Root/diag requirement | Notes |
|---|---|---|
| **Network Signal Guru** | **Requires root** (Qualcomm chipset assumed; needs a vendor-specific unlock/token on Samsung Exynos; custom ROM path for Huawei Kirin) | Confirmed by the app's own distribution pages: *"This app requires ROOT permission and is based on QUALCOMM chipset... NSG needs cell phone super user access."* [VERIFIED] — [Play Store listing](https://play.google.com/store/apps/details?id=com.qtrun.QuickTest), [uptodown listing](https://network-signal-guru.en.uptodown.com/android). It's a diag-tier tool, same category problem as your phone's missing `/dev/diag`. |
| **NetMonster** (+ open-source **netmonster-core**) | **No root required** for its core functionality | Confirmed via the library's own README: it is built purely on public `TelephonyManager` methods — `getAllCellInfo()` (primary/modern), `getCellLocation()` (deprecated fallback), `getNeighbouringCellInfo()` (removed in modern AOSP but still probed on old OEM builds) — plus **reflection into a few hidden-but-still-userspace APIs** (`ServiceState`/`PhysicalChannelConfig`) for LTE-Advanced/NR-NSA carrier-aggregation detection on Android P+, and its own **post-processing "MOCN postprocessor"** that infers/attaches additional-PLMN data onto non-serving cells from the `mAdditionalPlmns` field and known-MOCN-sharing-operator tables. [VERIFIED via README review, [mroczis/netmonster-core](https://github.com/mroczis/netmonster-core/blob/master/README.md); MOCN postprocessing behavior corroborated by an open issue discussing its false-positive edge cases, [netmonster-core#44](https://github.com/mroczis/netmonster-core/issues/44).] **No mention anywhere in the docs of raw MIB/SIB access or a root-mode path** — it does not attempt diag capture at all; it is a pure public-API tool, which is exactly the ceiling this document is describing. This is the closest open-source proof that "public API only" is a real, working product category, not just a degraded fallback. |
| **Cellular-Z** | Not clearly stated to require root; positions itself as a `READ_PHONE_STATE` + location app | Could not confirm definitively either way this session — **[UNVERIFIED]**, but nothing found suggests it needs root, and its feature set (signal quality + WiFi channel info) is consistent with the public-API ceiling above. |
| **G-NetTrack Pro/Lite** | Not stated as root-required in its own Play Store description; markets full 2G–5G serving+neighbour cell measurement and logfile export | **[UNVERIFIED]** whether it silently benefits from root when present (common pattern: use root/diag if available, degrade gracefully to public API otherwise) — worth testing directly since it's a serious drive-test-oriented tool, but I found no explicit root requirement stated. [Play Store listing](https://play.google.com/store/apps/details?id=com.gyokovsolutions.gnettrackproplus) |
| **CellMapper** | **Mixed**: base app works without root; some *frequency/bandwidth detail* features on certain chipsets (Qualcomm, Sony, Samsung) explicitly call out a root requirement, with a newer non-root code path for some of that same data | [VERIFIED via CellMapper's own Android app settings documentation] — [cellmapper.net/Android_App_Settings](https://www.cellmapper.net/Android_App_Settings). This is a useful data point: even a public-API-only base gets you serving-cell mapping; band/bandwidth-precision is the specific thing that historically needed root, and CellMapper's own docs describe them chipping away at that non-root gap over time — worth revisiting for what "newer version" covers now. |

**None of these get SIBs without root/diag.** NetMonster is the strongest comparison point because it's open source and confirms in its own code/docs that its non-root ceiling is the same public `CellIdentity`/`CellSignalStrength`/`ServiceState` surface described in §1 and §3 — there is no secret extra API.

---

## 5. Practical recipe

Best non-rooted, no-diag "what's around me" report on Android 15, copy-pasteable:

```bash
# 1) One-shot snapshot of everything the framework currently has cached
#    (serving cell + whatever neighbours the modem chose to report,
#    across every CellInfo* type: NR/LTE/WCDMA/GSM, all SIM slots)
adb shell dumpsys telephony.registry > cellinfo_snapshot.txt

# 2) Poll it in a loop for a moving picture while you walk/drive
#    (this rides the RIL's own unsolicited-update cadence, not the
#     app-facing getAllCellInfo() throttle, so it's the fastest legal path)
while true; do
  { date +%s; adb shell dumpsys telephony.registry | grep -E "CellInfo|CellIdentity|CellSignalStrength|mServiceState"; } >> cellinfo_log.txt
  sleep 1
done

# 3) Trigger + capture a manual PLMN scan (needs a SIM per §2.3;
#    this opens UI on-device, so pair it with a screen recording or
#    have someone tap "Search Networks" / disable "auto-select" on screen)
adb shell am start -a android.settings.NETWORK_OPERATOR_SETTINGS
adb logcat -b radio -v time -c   # clear old radio buffer first
adb logcat -b radio -v time | grep -Ei "RILJ|QUERY_AVAILABLE_NETWORKS|START_NETWORK_SCAN|GsmServiceStateTracker|NetworkScan" | tee plmn_scan.log

# 4) Grab registration/roaming/operator context alongside the raw cell dump
adb shell dumpsys telephony.registry | grep -E "mServiceState|mOperator|mDataRegState|mVoiceRegState|isManualNetworkSelection"
```

**Limitations to design the drive-test product around:**

1. **Throttle**: any *app*-driven `getAllCellInfo()`/`requestCellInfoUpdate()` call is rate-limited and, on Android Q+ targets, no longer force-refreshes — real freshness depends on the RIL pushing updates on its own schedule. `dumpsys telephony.registry` polling sidesteps the app-level throttle but is still bounded by how often the modem itself reports changes.
2. **Neighbours are opportunistic**: expect reliable serving-cell data; neighbour-cell entries appear only when the modem is actively measuring them (typically near handover boundaries), not a full live neighbour list on demand.
3. **No real PLMN-scan API for a sideloaded app**: `requestNetworkScan()` needs `MODIFY_PHONE_STATE` (signature|privileged) — unreachable without a system-signed/privileged app or root. The only non-privileged path to a genuine over-the-air multi-operator scan is driving the Settings UI (§2.1) and scraping `logcat -b radio`, which is fragile (UI automation + log scraping) and, per field reports, **requires a SIM to even be offered**.
4. **SIM-less behavior is the open question for your exact scenario**: whether `dumpsys telephony.registry` returns a populated `CellInfo` while `SIM_STATE_ABSENT` and emergency-camped is plausible by general cellular-emergency-camping behavior but **not confirmed against Android-specific documentation this session** — verify first, since it determines whether steps 1–2 above produce anything at all on this phone right now.
5. **No SIBs, ever, without diag**: everything you get is post-decode fragments (MCC/MNC/TAC/CellId/PCI/ARFCN/band/additionalPlmns + signal quality). If the product needs raw SIB1/SIB2 content, barring flags, or full neighbour-relation lists as broadcast, that is a hard wall on this phone without `/dev/diag` — no public-API trick closes that gap, and NetMonster (open source, no-root) is proof that nobody else has found one either.

---

## Sources (all cited inline above; consolidated list)

- AOSP `frameworks/base` telephony classes (CellInfo, CellInfoNr, CellInfoLte, CellIdentityNr, CellIdentityLte, CellIdentityGsm, CellIdentityWcdma, CellSignalStrengthNr, CellSignalStrengthLte, CellSignalStrengthGsm, CellSignalStrengthWcdma, ITelephony.aidl, TelephonyPermissions.java) — https://android.googlesource.com/platform/frameworks/base/
- `TelephonyShellCommand.java` (packages/services/Telephony) — https://android.googlesource.com/platform/packages/services/Telephony/+/master/src/com/android/phone/TelephonyShellCommand.java
- `NetworkScanHelper.java` — https://android.googlesource.com/platform/packages/services/Telephony/+/492769b55cd09f2ad14549370da19f5073a9180b/src/com/android/phone/NetworkScanHelper.java
- AOSP commit adding `ACCESS_FINE_LOCATION` to `requestNetworkScan` — https://android.googlesource.com/platform/frameworks/base/+/ee313737e970e95c77cdc229c315dd2c0e8551ce%5E!/
- Android privileged permission allowlist — https://source.android.com/docs/core/permissions/perms-allowlist
- `TelephonyManager` reference (getAllCellInfo/requestCellInfoUpdate behavior) — https://developer.android.com/reference/android/telephony/TelephonyManager
- `NetworkScanRequest` / `NetworkScan` reference — https://developer.android.com/reference/android/telephony/NetworkScanRequest , https://developer.android.com/reference/android/telephony/NetworkScan
- Google Issue Tracker, getAllCellInfo Q-preview behavior discussion — https://issuetracker.google.com/issues/37086426 , https://issuetracker.google.com/issues/36980074
- RIL scan / neighbour-cell privileged-app discussion — https://groups.google.com/g/android-platform/c/a8L_bO-q5GQ
- PixelExperience manual network selection issue (RILJ log evidence) — https://github.com/PixelExperience/android-issues/issues/2731
- `logcat -b radio` usage examples — https://gist.github.com/nicklaslof/3206907 , https://www.telecomhall.net/t/android-watching-data-sessions-in-real-time/37043
- `service call phone` / ITelephony transaction technique — http://ktnr74.blogspot.com/2014/09/calling-android-services-from-adb-shell.html
- NetMonster Core (open source) — https://github.com/mroczis/netmonster-core , README: https://github.com/mroczis/netmonster-core/blob/master/README.md , MOCN postprocessor issue: https://github.com/mroczis/netmonster-core/issues/44 , docs: https://github.com/mroczis/NetMonster-docs
- Network Signal Guru root requirement — https://play.google.com/store/apps/details?id=com.qtrun.QuickTest , https://network-signal-guru.en.uptodown.com/android
- G-NetTrack Pro — https://play.google.com/store/apps/details?id=com.gyokovsolutions.gnettrackproplus
- CellMapper Android app settings (root vs non-root feature split) — https://www.cellmapper.net/Android_App_Settings
- QCSuper (DIAG-based raw SIB capture, confirms the diag dependency) — https://github.com/P1sec/QCSuper
