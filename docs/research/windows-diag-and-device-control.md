# Windows-side diag capture and device control — research notes

**Scope.** How to get from "engineer plugs a rooted Qualcomm Android phone into a Windows
11 laptop" to "diag port streaming, GPS and traffic tests scriptable, multiple phones
distinguishable" with no manual clicking. This supports Roadmap Phase 1 (a reproducible
capture baseline) and the laptop-tethered product shape (Phase 3, Option B) described in
`docs/ARCHITECTURE.md` and `docs/ROADMAP.md`. It does not cover the diag wire protocol's
log record layouts (that is `docs/ARCHITECTURE.md` and Phase 2) — only what is needed to
reliably *reach* the diag port, unattended, from Windows.

**Status.** Research only. Values below come from vendor pages, XDA/community threads,
AOSP source, and the project's own vendored QCSuper copy (`third_party/qcsuper/`,
GPLv3, commit `f5f1501`) and its own clean-room `fieldtap` package. Nothing here is
copied from QCSuper's source code — QCSuper's README and docs are prose documentation
and are paraphrased with citation; where its *code* was the only source for a fact
(the DIAG_LOG_CONFIG_F op codes), the fact is stated in words, matching what
`fieldtap/diag/protocol.py` already implements independently. Items with no primary
source, or only a single low-confidence source, are flagged **[UNVERIFIED]**.

---

## 1. Windows side: the Qualcomm USB driver and the diag COM port

### 1.1 What "the Qualcomm driver" actually means — three different drivers

People say "install the Qualcomm driver" and mean one of three unrelated things:

| Name | Purpose | PID | When you need it |
| --- | --- | --- | --- |
| **QUD** (Qualcomm USB Driver) / "Qualcomm HS-USB Diagnostics 9091" | Diag + modem/AT/NMEA serial ports over composite USB, normal boot | `9091` (also seen: `901D`, `901F`, `9008`-adjacent numbers on some chipsets) | This is the one FieldTap needs — it is what exposes `/dev/diag` as a Windows COM port. |
| **QDLoader 9008** / "Qualcomm HS-USB QDLoader 9008" | Emergency Download (EDL) mode — raw flash programming, no OS running | `9008` | Not needed for capture. Relevant only if a phone gets bricked during rooting and needs `fastboot`/EDL flashing to recover. |
| Android **ADB/fastboot** driver (Google's) | The `adb`/`fastboot` transport itself | varies by OEM | Always needed alongside QUD; without it `adb devices` won't see the phone at all. |

Sources: [itechguides — Install Qualcomm 9008 EDL Drivers on Windows 11](https://www.itechguides.com/install-edl-drivers-in-windows-11-qualcomm-hs-usb-qdloader-9008/), [Qualcomm EDL mode — Wikipedia](https://en.wikipedia.org/wiki/Qualcomm_EDL_mode), [QCSuper README, Windows installation section](https://github.com/P1sec/QCSuper/blob/master/README.md).

### 1.2 Where to get QUD legitimately

There is no single Qualcomm-hosted consumer download for the generic "9091" driver —
Qualcomm distributes it to OEMs, who bundle it with their own phone's driver package.
In practice:

1. **Phone-vendor driver package** — search "`<phone model>` USB driver" (e.g. "OnePlus
   USB drivers"). This is what QCSuper's own README recommends: *"you may need … to
   download and install your phone's USB drivers from your phone model (this may include
   generic Qualcomm USB drivers). Please search for your phone's model + 'USB driver'"*
   ([QCSuper README](https://github.com/P1sec/QCSuper/blob/master/README.md)).
2. **Microsoft Update Catalog** — for the QDLoader 9008 (EDL) driver specifically, the
   WHQL-signed package is on `catalog.update.microsoft.com`; several driver-download
   sites republish the same signed CAB
   ([itechguides](https://www.itechguides.com/install-edl-drivers-in-windows-11-qualcomm-hs-usb-qdloader-9008/)).
3. **Generic Qualcomm HS-USB driver packages** hosted by driver-archive sites
   (`driverscape.com`, `driveridentifier.com`, `oemdrivers.com`) — these repackage the
   same `qcser.inf`/`qcusbser.inf` INF used across OEMs. Treat as "legitimate but
   unofficial redistribution" — verify the digital signature before installing
   (Properties → Digital Signatures on the downloaded EXE/CAB).
4. A mirror of the INF itself is publicly viewable at
   [DriversStore/QCUSBSer (`qcser.inf`)](https://github.com/DriversStore/QCUSBSer/blob/2.1.1.2-arm/qcser.inf)
   for reference on what device IDs a typical build matches.

**Signing on Windows 11.** Recent QUD/QDLoader packages are WHQL-signed and install
without disabling driver signature enforcement on a standard (Secure-Boot-on) Windows 11
machine. Older/unsigned INFs downloaded from driver archives may still trigger the
signature block; the fallback is `bcdedit /set testsigning on` (persistent) or the
Advanced Startup → "Disable driver signature enforcement" F7 option (one boot only) —
**not available at all when Secure Boot is enabled**, so an unsigned INF simply won't
install on a Secure-Boot machine. For an unattended provisioning script, prefer a
WHQL-signed package specifically so this never comes up.
Sources: [Pureinfotech — disable driver signature enforcement Windows 11](https://pureinfotech.com/disable-driver-signature-enforcement-windows-11/), [droidwin — Install EDL Drivers in Windows 11](https://droidwin.com/install-edl-drivers-in-windows-11-qualcomm-hs-usb-qdloader-9008/).

### 1.3 Hardware IDs and common USB compositions

The diag interface enumerates as a child device node under the composite parent, named
by its Multiple-Interface (`MI_xx`) index. **The MI index for diag is not fixed** — it
depends on which `sys.usb.config` string the phone is set to (see §2) — but the common
patterns observed across vendor driver pages are:

| Device ID pattern | Friendly name | Notes |
| --- | --- | --- |
| `USB\VID_05C6&PID_9091&MI_00` | Qualcomm HS-USB Diagnostics 9091 | The classic pattern: diag is interface 0 when the composition is just `diag,adb` or `diag,modem,nmea,...`. Seen on many OEMs incl. Longcheer-based designs. ([Device KB](https://www.devicekb.com/hardware/usb-vendors/vid_05c6-pid_9091), [drivermax](https://www.drivermax.com/Qualcomm-HS-USB-Diagnostics-9091-Qualcomm-Incorporated-USB-VID-05C6-PID-9091-MI-00-2_1_3_5-2018-12-17-4392457-driver.htm)) |
| `USB\VID_05C6&PID_901D&MI_00` | Qualcomm HS-USB Android DIAG 901D | Alternate PID some Qualcomm reference/AOSP-style builds report when diag mode is switched on ([Device KB / driveridentifier](https://www.driveridentifier.com/scan/qualcomm-hs-usb-android-diag-901d-driver/driver-detail/0B5C07BA9F3F451B80DAFD752E1F660E/4662476/41b8739b8fbe8fbaf2f2be47df315b38/1026892998/USB-VID_05C6&PID_901D&MI_00)) |
| `USB\VID_05C6&PID_9018&MI_01` | Android Composite ADB Interface (Qualcomm USB ID) | Example of ADB sharing a composition with diag at a *different* MI index — confirms the index shifts per-build, not per-vendor ([driveridentifier](https://www.driveridentifier.com/scan/android-composite-adb-interface-qualcomm-usb-id-driver/driver-detail/2BDBEC1DD1634BDEACC1A623832534A1/3326455/7ae6070f7cd9fc06429a47b31286e131/336385849/USB-VID_05C6&PID_9018&MI_01)) |
| `USB\VID_2A70&...` | OnePlus's own vendor ID, normal (non-diag) boot | OnePlus ships as VID `2A70` (OnePlus Technology (Shenzhen) Co., Ltd.) in MTP/adb-only mode; once `sys.usb.config` is switched to include `diag`, the composite device re-enumerates **under Qualcomm's VID `05C6`**, not OnePlus's own VID — this is the "port changed to a totally different device" surprise reported repeatedly in XDA threads. ([DeviceHunt — VID_2A70](https://devicehunt.com/view/type/usb/vendor/2A70), corroborating pattern in multiple droidwin guides) |

**Interface descriptor for the diag function** (confirmed in a community capture of a
`05c6:901d` "DIAG_ADB" USB configuration): `bInterfaceClass = 255` (0xFF, vendor-specific),
`bInterfaceSubClass = 255` (0xFF), `bInterfaceProtocol = 48` (0x30) — i.e. exactly the
"class FF, subclass FF, protocol 30" signature the task description named, and the
signature QCSuper's own device-matching logic looks for.
Source: [android-porting mailing list thread, Google Groups](https://groups.google.com/g/android-porting/c/Qu7DjHt7z_U) (community-reported descriptor dump; **[UNVERIFIED against an official Qualcomm spec]** — Qualcomm's own USB interface guide at `docs.qualcomm.com` does not render its content to fetchers, so this could not be cross-checked against the primary vendor document).

Practical consequence for automated provisioning: **do not hardcode a single PID**. Match
on `VID_05C6` plus interface class/subclass/protocol (`FF/FF/30`), or on the friendly-name
substring "Diagnostics" / "DIAG", not on a fixed PID — the same phone can present `9091`,
`901D`, or something else depending on firmware and which composition string was used.

### 1.4 The WinUSB/libusb alternative (Zadig)

Instead of the class-driver COM port, the diag interface can be bound directly to
**WinUSB** or **libusb-win32** via [Zadig](https://zadig.akeo.ie/), which is what
QCSuper's Windows path actually documents and expects:

> "you should as well manually create `libusb-win32` filters (through the utility
> accessible in the Start Menu after installing it) in the case where your device
> directly needs to connect to the Diag port over pseudo-serial USB… if you mode-switch
> your device, the associated USB PID/VID may change and it may require to redo driver
> associations."
> — [QCSuper README, Windows installation](https://github.com/P1sec/QCSuper/blob/master/README.md)

Two practical notes:

- **libusb-win32 vs. WinUSB.** QCSuper's documented path uses the older `libusb-win32`
  filter driver (pinned versions: 1.2.7.3 for Win11-era systems, 1.2.3.7 for Windows 7),
  installed from a `libusb-win32-devel-filter` EXE, not from Zadig directly. Zadig itself
  defaults to installing **WinUSB** (Microsoft's own driver, no separate signing/filter
  concerns) and can install libusb-win32/libusbK as alternates — WinUSB is the more
  modern and Windows-11-friendly choice per [libwdi's own Zadig documentation](https://github.com/pbatard/libwdi/wiki/Zadig).
  `pyusb`/`libusb` (used by QCSuper, and by whatever Python or C++ transport FieldTap
  writes for direct USB access rather than a COM port) can open a WinUSB-bound interface
  identically to a libusb-win32 one.
- **Re-binding after mode switch.** Since diag mode-switching changes the composite
  device's PID (§1.3), any driver association made via Zadig/libusb-win32 against the
  *pre-switch* PID is invalid after the phone re-enumerates in diag mode — the filter or
  WinUSB binding has to target the *post-switch* PID, and gets invalidated again if the
  phone firmware changes which PID it reports. This is the single biggest source of
  "worked yesterday, doesn't today" driver failures reported in the QCSuper issue tracker
  and XDA threads.

**Choosing between the two for FieldTap:** a native class-driver COM port (§1.1–1.3) is
simplest to enumerate from Windows APIs (`SetupDiGetClassDevs` / WMI / .NET
`SerialPort.GetPortNames()`) and is what `fieldtap devices` already expects
(`fieldtap capture --port COM5`, per the repo's own `README.md`). WinUSB is more robust
against composition changes (binds by VID/PID/interface, not driver-class enumeration
order) but needs either an interactive Zadig step or a custom signed INF. Recommendation
for a "just plug it in" product: ship a signed INF that WinUSB-binds the diag interface
by `Class_FF&SubClass_FF&Prot_30` under `VID_05C6` (not a fixed PID), and read phone
identity through `adb` (§3) rather than USB descriptor strings.

### 1.5 Telling diag apart from the modem/AT and NMEA ports

Within a single composite Android USB device, the class driver installs one COM port per
serial-like interface. When a composition string includes multiple serial functions
(e.g. `diag,serial_cdev,serial_cdev,rmnet,adb`, seen in Xiaomi-family `init.qcom.usb.rc`
configs — [devicekb / xiaomi.eu diag thread](https://xiaomi.eu/community/threads/connecting-to-diag-mode.37793/)),
Device Manager shows several "Qualcomm HS-USB ... COMn" entries that are **not**
distinguishable by friendly name alone — Windows just enumerates them in interface order.
Ways to tell them apart:

- **Behavior probe (most reliable).** Diag is a binary HDLC-framed protocol; sending an
  AT command (`AT\r\n`) to it gets silence or garbage, while the true AT/modem port
  responds `OK`. NMEA ports emit unsolicited `$GPxxx` sentences on their own without being
  queried. This is the same probe QCSuper effectively relies on when a `--usb-modem COMn`
  target is given explicitly.
- **Interface descriptor.** The diag interface is class/subclass/protocol `FF/FF/30`
  (§1.3); AT/modem ports are typically CDC-ACM (`class 0x02`/`0x0A`); the interface order
  in the `sys.usb.config` string usually (but not reliably) maps to ascending MI index —
  e.g. for `diag,serial_cdev,rmnet,adb`, diag tends to land at `MI_00`. Treat this as a
  starting guess, not a guarantee — it is exactly the kind of assumption that broke on the
  OnePlus 6/6T in one QCSuper bug report until the real device state was inspected
  ([QCSuper issue #13](https://github.com/P1sec/QCSuper/issues/13)).
- **Registry/WMI cross-reference.** All ports belonging to the same phone share the same
  parent composite device instance and (usually) the same serial-number suffix in their
  hardware ID (§1.7) — group by that, then behavior-probe within the group.

### 1.6 Known Windows issues

| Issue | Detail | Source |
| --- | --- | --- |
| Driver signing on Win11 | WHQL-signed QUD/QDLoader packages install cleanly; unsigned/legacy INFs need test-signing mode, which is blocked entirely when Secure Boot is on | [Pureinfotech](https://pureinfotech.com/disable-driver-signature-enforcement-windows-11/) |
| Port renumbering after mode-switch | Because the PID changes (§1.3), Windows can (not always does) allocate a **new** COM port number on first mode-switch, even though it will reuse the same number on subsequent identical reconnects, because COM-port assignment is keyed off VID+PID+serial, and the VID/PID pair itself changed | [Microsoft Q&A — static COM port assignment](https://learn.microsoft.com/en-us/answers/questions/452998/how-to-assign-static-com-port-number-to-a-device), [QCSuper README](https://github.com/P1sec/QCSuper/blob/master/README.md) |
| libusb-win32 filter invalidation | A filter bound to the pre-switch PID does not carry over post-switch; must be redone in the libusb-win32 filter utility and/or Device Manager | [QCSuper README](https://github.com/P1sec/QCSuper/blob/master/README.md) |
| ~5s enumeration stall with duplicate identifiers | If two attached devices ever present identical VID+PID+serial (shouldn't happen with distinct phones, but has been reported with cheap USB hubs mangling descriptors), Windows adds a multi-second enumeration delay while it works around the conflict | [proxmark3 issue #1904](https://github.com/RfidResearchGroup/proxmark3/issues/1904) |

### 1.7 Multiple phones at once: COM port naming and serial-number mapping

**Windows assigns COM port numbers per (VID, PID, serial-number) tuple**, and the
assignment is sticky: the same physical phone plugged into the same physical port (or
even a different port, on modern Windows — the port-vs-hub-path binding was relaxed some
releases ago; treat as "usually persistent, verify on target hardware") gets the same
`COMn` on the next connection.
[Microsoft Community Hub — how does the USB stack enumerate a device](https://techcommunity.microsoft.com/blog/microsoftusbblog/how-does-usb-stack-enumerate-a-device/270685),
[Wronex — enumerating USB serial devices](https://www.wronex.com/articles/220211-enumerating-serial-devices/).

**Is the phone's serial exposed on the COM port, so software can map COM port → phone?**
Yes, in the normal case. Android's USB gadget stack writes `ro.serialno` into the gadget
driver's `iSerial` field at init time (historically via
`/sys/class/android_usb/android0/iSerial`, functionally equivalent on the newer
ConfigFS-based gadget), so **the USB device descriptor's serial-number string equals the
phone's `adb`/`ro.serialno` value** for essentially every stock Android build.
[android-kernel Google Group — ADB device serial number and Android USB Composite kernel driver](https://groups.google.com/g/android-kernel/c/dWw484uOn_w).
Concretely, on Windows this means:

- The composite device's own instance ID looks like
  `USB\VID_05C6&PID_9091\<ro.serialno>` — that string is retrievable via WMI/PnP without
  opening the port at all.
- Each child interface (the diag COM port among them) is enumerated under that same
  parent, and its own instance ID (`USB\VID_05C6&PID_9091&MI_00\<serial>&0&0000`-shaped)
  still carries the serial number as a substring — so a PowerShell query such as
  `Get-CimInstance Win32_PnPEntity | Where-Object { $_.Name -like 'Qualcomm HS-USB*' }`
  (or `Win32_SerialPort` cross-referenced against `Win32_PnPEntity.PNPDeviceID`) exposes
  both the `COMn` name and the phone's serial in the same record.
  ([Win32_PnPEntity docs](https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/win32-pnpentity),
  [Displaying USB Devices using WMI — PowerShell Team](https://devblogs.microsoft.com/powershell/displaying-usb-devices-using-wmi/))
- This is exactly how a multi-phone provisioning script should build its
  `serial → COM port` table: enumerate `Win32_PnPEntity` for `VID_05C6`, pull the serial
  substring out of `PNPDeviceID`, cross-reference against `adb devices -l` (§3.3) to get
  the human-facing model/product name for the same serial.
- **Caveat [UNVERIFIED per-OEM]:** a small number of vendors have shipped gadget builds
  with `iSerialNumber` blank or hardcoded, breaking this mapping. Worth a one-time check
  on the team's actual OnePlus unit (`adb shell getprop ro.serialno` vs. the WMI
  `PNPDeviceID` suffix) before relying on it in code.

---

## 2. Phone side: getting a rooted Qualcomm phone into diag mode

### 2.1 The mechanism: `sys.usb.config` / `persist.sys.usb.config`

USB composition on Qualcomm Android builds is controlled by the `sys.usb.config` system
property (read by `init.qcom.usb.rc`/`init.usb.rc` early-boot scripts), with
`persist.sys.usb.config` as the value that survives reboot. The property's value is a
comma-separated list of USB *functions*; `diag` is one of them, and can be combined with
`adb`, `serial_cdev`, `rmnet`, `qdss`, `dpl`, `mass_storage`, `mtp`, etc. The exact
combination required varies by SoC generation and vendor `.rc` file, so **the safe pattern
is to add `diag` in front of whatever functions are already present, keeping `adb` last**,
rather than replacing the whole string:

```
adb shell su -c "setprop sys.usb.config diag,adb"
adb shell getprop sys.usb.config          # should now read back "...diag...adb"
```

Root (`su`) is required because `sys.usb.config` is a protected system property on all
current Android versions — an unprivileged shell can read it but a plain (non-root)
`adb shell setprop` write is rejected. `persist.sys.usb.config` is the same idea but
governs the *boot-time default*, so setting it (still root-only) makes diag mode survive
a reboot instead of reverting to the OEM default composition.
Sources: [hovatek forum — enable/disable diag port](https://www.hovatek.com/forum/thread-22399.html), [Bugzilla 836770 — persist.sys.usb.config exact-match note](https://bugzilla.mozilla.org/show_bug.cgi?id=836770), [commaai/android_device_oneplus_oneplus3 — init.qcom.usb.rc](https://github.com/commaai/android_device_oneplus_oneplus3/blob/master/rootdir/etc/init.qcom.usb.rc).

**Important gotcha found in the `init.qcom.usb.rc` matching logic:** the property is
matched **exactly** against known strings in the init script, not by substring — a
composition the phone's `.rc` file does not explicitly enumerate is silently ignored (the
property changes, but nothing on the USB bus does). This is the most common reason a
`setprop` "does nothing": the requested combination just isn't one of the phone's
pre-baked options. When a `setprop` has no visible effect, cycle through the
OEM-documented compositions in §2.2 rather than inventing a new comma-separated string.

### 2.2 Per-OEM USB configuration values

| OEM / family | Working composition string(s) | Root needed? | Notes |
| --- | --- | --- | --- |
| **OnePlus / OPPO / Realme** (ColorOS/OxygenOS, all Snapdragon) | `diag,adb` (minimal) or the fuller `diag,diag_mdm,qdss,qdss_mdm,serial_cdev,dpl,rmnet,adb` | Yes (root) for the `setprop` route | On Android 12+ OxygenOS builds, some devices additionally require flipping `encrypt_app` / `encrypt_adb` to `false` inside `/mnt/vendor/persist/engineermode/engineermode_config` before diag will actually open, even with the property set correctly. |
| **Xiaomi / Redmi / POCO** (MIUI/HyperOS) | `diag,adb` via `setprop`, or `DIAG,SERIAL_SMD,RMNET_IPA,ADB` via the on-device USB-config popup | `setprop` route: yes. Popup route: **no root** | Non-root popup reached by dialing `*#*#13491#*#*`, which surfaces a native "USB config" picker with `DIAG,...,ADB` and `MTP,ADB` entries — this is the same mechanism as the com.longcheertel.midtest engineering APK some Xiaomi builds ship. |
| **Samsung, Snapdragon SKUs only** | Native USB-mode selector reached by `*#0808#`, offering an `RNDIS + ACM + DM` (and on newer chipsets, longer lists with `RMNET`, `ADPL`, `QDSS`) entry | **No root**, if the code still works on that firmware | Only Snapdragon-SKU Samsung phones expose this at all — European/global units are usually Exynos and never expose Qualcomm diag over USB regardless of code or root. `*#0808#` was blocked on some 2018+ firmware with no known non-root bypass; on those, root + `setprop` is the fallback. USB config resets after OTA updates — re-run the code (or re-run `setprop`) after every update. |
| **Motorola** | `diag,adb`, or `diag,serial_cdev,rmnet,dpl,qdss,adb`; some builds additionally need `resetprop ro.bootmode usbradio` + `resetprop ro.build.type userdebug` before the composition takes | Yes | The `ro.bootmode`/`ro.build.type` trick (via Magisk's `resetprop`, which can flip build-time-only props at runtime) shows up repeatedly across Motorola/Qualcomm generic guides — it exists because some `.rc` matching is gated on `ro.build.type == userdebug` in addition to the composition string. |
| **Sony Xperia** | Model-dependent: Xperia 1 III — `setprop persist.usb.eng 1`; Xperia 1 IV — `setprop sys.usb.ffs.ready 1; setprop vendor.usb.use_ffs_mtp 0` (switch to file-transfer mode first), then `setprop sys.usb.config diag,diag_mdm,adb` | Yes | Sony's own service/DIAG menu is reached by `*#*#7378423#*#*` but is a diagnostics *app*, not itself a USB-composition switch. |

Sources: [droidwin — Boot Qualcomm device to Diag Mode via ADB](https://droidwin.com/how-to-boot-qualcomm-device-to-diag-mode-via-adb-commands/), [droidwin — Enable DIAG Mode in OnePlus when *#801# is not working](https://droidwin.com/how-to-enable-diag-mode-in-oneplus-when-801-is-not-working/), [XDA — open diag port / engineering mode Android 12 OOS root only](https://xdaforums.com/t/how-to-open-diag-port-and-unlock-engineering-mode-for-android-12-oos-root-device-only.4533121/), [XDA — DIAG Mode on OnePlus community thread](https://community.oneplus.com/threads/how-to-enter-hidden-usb-menu.234779/), [xiaomi.eu — Connecting to Diag Mode](https://xiaomi.eu/community/threads/connecting-to-diag-mode.37793/), [AndroidAuthority — hidden Samsung dial codes](https://www.androidauthority.com/secret-dial-codes-samsung-phones-3684761/), [hicelltek — Qualcomm DIAG Mode on Samsung](https://hicelltek.com/en/qualcomm-diag-mode/enable-diag-samsung/), [XDA — Sony Xperia diag mode threads](https://xdaforums.com/t/how-to-enable-diag-mode-in-z3v.3367086/), [gsmwiki/NckTeam — Enable/Disable Diag Mode via ADB](http://www.gsmwiki.com/enable-or-disable-diag-mode-on-qualcomm-device-using-adb).

### 2.3 Dialer / engineering codes reference

| Code | Effect | Root needed | Reliability |
| --- | --- | --- | --- |
| `*#801#` | Historically opened OnePlus diag/engineering menu directly | No | **Blocked on current OxygenOS** — OnePlus patched this out; do not rely on it |
| `*#808#` | Opens a OnePlus/OPPO engineering menu with a USB-composite picker incl. a DIAG-enabled option | No, on firmware where it still works | Inconsistent across models/regions — verify per unit |
| `*#*#717717#*#*` | MediaTek engineering-mode code (listed for completeness — **not applicable to Qualcomm phones**, MediaTek uses META/DA mode, a different mechanism entirely) | N/A | Out of scope per Roadmap 0.3 (Qualcomm-only for v1) |
| `*#9090#` | Vendor-specific service-mode code seen on some Samsung/other builds; **[UNVERIFIED]** — not independently confirmed to expose Qualcomm diag specifically across the searched sources | Varies | Treat as folklore until confirmed on target hardware |
| `*#0808#` | Samsung Snapdragon-SKU USB mode selector (see §2.2) | No | Snapdragon SKUs only; blocked on some post-2018 firmware |
| `*#*#13491#*#*` | Xiaomi USB-config popup (see §2.2) | No | MIUI/HyperOS only |
| `*#*#7378423#*#*` | Sony service/DIAG menu launcher | No (menu access); diag-mode switch itself still needs root on most Xperia models | Opens diagnostics app, not a direct USB switch |

### 2.4 The `diag_mdlog` approach

`diag_mdlog` is Qualcomm's own on-device logging client — a native binary (present on
many stock builds at `/system/bin/diag_mdlog` or shipped by the vendor partition,
originating from `vendor/qcom/proprietary/diag/mdlog/`) that opens `/dev/diag` itself,
applies a **mask configuration file** (`diag.cfg`, generated from QXDM's "Save Log
Mask File" or hand-built from a Qualcomm log-code list), and writes rotated `.qmdl`
files straight to on-device storage — no USB or host tool involved at all. Typical
invocation:

```
diag_mdlog -f /data/local/tmp/diag.cfg -o /sdcard/diag_logs/ -s 50
```

(`-f` config file, `-o` output directory, `-s` per-file size cap in MB — the flag set is
consistent across the several vendor forks inspected, though the authoritative
per-build `--help` output should be treated as the source of truth on the actual unit,
since some OEMs trim or extend it.) It only ever runs one task at a time (`kill -9 <pid>`
to stop it, no clean shutdown flag documented). This is a genuinely different capture
model from the diag-over-USB approach FieldTap currently targets: `diag_mdlog` logs
**to the phone's own storage**, decoupled from the laptop entirely, then the `.qmdl`
files are pulled off afterward with `adb pull`. Useful as a fallback when the USB diag
port can't be reliably held open (e.g. the "port busy" problem in §6.4), at the cost of
losing the live/streaming capture model.
Sources: [Alibaba Cloud dev topic — diag_mdlog usage and diag.cfg](https://topic.alibabacloud.com/a/obtain-logs-through-android-debugging_1_21_32231164.html), [bcyj/android_tools_leeco_msm8996 — diag_mdlog.c, described not quoted](https://github.com/bcyj/android_tools_leeco_msm8996/blob/master/diag/mdlog/diag_mdlog.c), [silklabs/kenzo-blobs — diag_mdlog binary listing](https://github.com/silklabs/kenzo-blobs/blob/master/system/bin/diag_mdlog).

### 2.5 `/dev/diag` permissions, SELinux, and whether Magisk root is enough

Two kernel-generation regimes, per QCSuper's own documented split:

- **Linux kernel ≤ 4.9**: `/dev/diag` is directly accessible as a character device once
  USB mode-switching exposes it; a rooted shell can `open()` it directly (this is what
  `device/fieldtap-diagd.c` in this repo is written against — see its header comment for
  the exact ioctl/read/write framing it follows).
- **Linux kernel ≥ 4.14**: `/dev/diag` access from a host-side tool over USB stops being
  necessary/possible the same way — the diag character device still exists on-device, but
  QCSuper's practical Windows/host path is to mode-switch the USB composition (§2.1) and
  let the class driver/WinUSB interface carry the traffic, rather than reading
  `/dev/diag` node permissions from the host side at all.

Source: [QCSuper README, "Device Categories"](https://github.com/P1sec/QCSuper/blob/master/README.md).

**Magisk root is not automatically sufficient.** A documented real-world failure on
SDM845 OnePlus 6/6T: with `/dev/diag` present and the shell running with Magisk root
(SELinux context `u:r:magisk:s0`), a diag client still got `DIAG_NOT_WRITEABLE=1` —
write access to the node was refused even though read access worked and root was
confirmed. No definitive resolution is recorded in that report.
[QCSuper issue #13 — OnePlus 6/6T /dev/diag not writeable](https://github.com/P1sec/QCSuper/issues/13). Practical
implication: **plan for a device-specific permission/SELinux check as part of bring-up
for each new phone model**, not just "is it rooted." Two angles worth trying if this
recurs: (a) a Magisk `post-fs-data.sh` module that `chcon`/`chmod`s `/dev/diag` at boot;
(b) check whether the failure is SELinux-enforced (`logcat | grep avc.*diag`) versus a
plain DAC bit — the fixes differ (`setenforce 0` for a diagnostic-only test vs. a
permissive `chmod 0666 /dev/diag`).
**[UNVERIFIED — no source documents the actual fix for the OnePlus 6/6T case; flagging
for hands-on bring-up.]**

### 2.6 OnePlus root recipe (team's phone is OnePlus on T-Mobile)

**Which OnePlus models are Snapdragon and rootable, and which T-Mobile units resist it:**

| Generation | Snapdragon | Rootable via unlock+Magisk | T-Mobile-specific status |
| --- | --- | --- | --- |
| OnePlus 6 / 6T | Yes (SD845) | Yes | T-Mobile 6T ships **bootloader-locked until the device is fully paid off** on that carrier's installment plan; unlockable after payoff |
| OnePlus 7 Pro | Yes (SD855) | Yes | T-Mobile unlock guide exists and works (see DroidFeats guide below) |
| OnePlus 7T, 8, 8T, 9, 10T | Yes | Bootloader-locked on **most** T-Mobile units — "most common non-rootable variants" per multiple guides | Avoid these specific models/carrier-SKU combinations for the fleet if root is a hard requirement |
| OnePlus 11, 12, 13, 15 (all Nord too) | Yes | Yes, unlock is immediate (no OnePlus-imposed waiting period, unlike Xiaomi) | Carrier lock status not confirmed T-Mobile-specific in sources found — verify per unit before relying on it |

Sources: [droidwin — Unlock Bootloader on OnePlus T-Mobile](https://droidwin.com/unlock-bootloader-oneplus-t-mobile/), [awesome-android-root — Complete OnePlus Rooting Guide](https://awesome-android-root.org/rooting-guides/how-to-root-oneplus-phone), [DroidFeats — Unlock Bootloader OnePlus 7 Pro T-Mobile](https://droidfeats.com/unlock-bootloader-oneplus-7-pro-t-mobile/), [DroidFeats — OnePlus 6T unlock/root incl. T-Mobile](https://droidfeats.com/oneplus-6t-unlock-bootloader-twrp-root/), [XDA — root for US OnePlus 13](https://xdaforums.com/t/root-for-the-us-version-of-the-oneplus-13.4713243/).

**Root recipe (standard OnePlus pattern, all generations):**

1. Developer Options → enable OEM unlocking + USB debugging.
2. `adb reboot bootloader`, then `fastboot flashing unlock` (or `fastboot oem unlock` on
   older builds) — **this wipes the device**.
3. Pull the matching stock `boot.img` (or `init_boot.img` on newer AVB2.2 devices, e.g.
   OnePlus 13/15 — patching the wrong one bootloops) for the exact installed build, from
   the official OTA/fastboot ROM package.
4. Patch it on-device via the Magisk app ("Install → Select a file"), pull the patched
   image back to the PC.
5. `fastboot flash boot patched_boot.img` (or `flash init_boot ...` on AVB2.2 devices),
   then `fastboot reboot`.
6. Confirm: Magisk app shows "Installed"; `adb shell su -c id` returns `uid=0(root)`.

For OnePlus 13/15, guidance leans toward **KernelSU** over Magisk, and AVB2.2's vbmeta
chain verification will bootloop the device if the patched image's hash doesn't match —
unlock alone isn't sufficient; the patch has to target the right partition.
Source: [XDA — Root for the US version of the OnePlus 13](https://xdaforums.com/t/root-for-the-us-version-of-the-oneplus-13.4713243/), [thecustomdroid — Comprehensive Guide to Unlock OnePlus Bootloader](https://www.thecustomdroid.com/oneplus-bootloader-unlocking-guide/).

**Gap this doc surfaces but cannot close:** Roadmap task 1.2 wants the exact device
procedure recorded, and this research found no specific OnePlus model/build for the
Nov 2025 T-Mobile capture anywhere in the repo (`captures/README.md`, `docs/ROADMAP.md`,
and the capture metadata JSON all just say "OnePlus, T-Mobile"). Pull
`ro.product.model` and `ro.build.fingerprint` off the physical unit and record them —
§2.2's table is generic-OnePlus guidance until that happens.

---

## 3. adb: connecting, authenticating, identifying, surviving re-enumeration

### 3.1 Official download

`https://developer.android.com/tools/releases/platform-tools` is the canonical page;
the actual Windows ZIP is served from Google's CDN at a stable, scriptable URL:

```
https://dl.google.com/android/repository/platform-tools-latest-windows.zip
```

— suitable for an unattended installer/bootstrap script (always resolves to current;
pin a specific `platform-tools_rXX.X.X-win.zip` build instead if reproducibility across
provisioning runs matters more than always-latest).
Source: [Android Debug Bridge — developer.android.com](https://developer.android.com/tools/adb), confirmed direct URL per multiple mirrors of the same `dl.google.com` path.

### 3.2 USB debugging + RSA authorization flow

1. On-device: Settings → About → tap Build Number 7× → Developer Options appears →
   enable "USB debugging".
2. On first connection from a given host, `adbd` on the phone offers the host's RSA
   public key fingerprint and the phone shows an "Allow USB debugging?" dialog with that
   fingerprint. Until approved, `adb devices` lists the phone as `unauthorized`, and every
   other adb operation is refused.
3. The host's key pair lives at `%USERPROFILE%\.android\adbkey` (private) and
   `adbkey.pub` (public) — auto-generated on first `adb` invocation if absent.
4. Checking "Always allow from this computer" on that dialog persists the authorization
   (keyed by the *public key*, not the machine otherwise) — this is the step to script
   around, since it cannot itself be scripted (it's a physical on-screen tap): a
   provisioning workflow needs one manual "allow" per phone per host the very first time,
   then never again for that key/phone pair (unless "Revoke USB debugging authorizations"
   is used or the key file is deleted).
5. `$ADB_VENDOR_KEYS` (env var pointing at a directory of pre-approved key pairs) lets a
   fleet-management setup pre-seed the same trusted key across multiple provisioning
   hosts, if the phones can be authorized once against that key and the key file
   distributed.

Sources: [getandora — adb devices unauthorized fix + $ADB_VENDOR_KEYS](https://getandora.in/blog/adb-unauthorized), [joachimschuster.de — RSA key fingerprint](https://joachimschuster.de/posts/debug-on-device-rsa-fingerprint/).

### 3.3 `adb devices -l` output format

```
<serial>       device   usb:<bus-port-path> product:<name> model:<name> device:<name> transport_id:<n>
```

Example, two phones attached:

```
serial1111   device usb:3-3 product:msm8953_64 model:msm8953_for_arm64 device:msm8953_64 transport_id:1
serial2222   device usb:3-4 product:msm8953_64 model:msm8953_for_arm64 device:msm8953_64 transport_id:2
```

- `serial` is `ro.serialno` for USB-connected devices, and is the same value exposed in
  the Windows USB descriptor (§1.7) — this is the join key between "which COM port" and
  "which adb device."
- `state` can be `device` (ready), `unauthorized` (§3.2 not completed),
  `offline` (adb sees the transport but the daemon hasn't finished the handshake — often
  transient during/after a `setprop sys.usb.config` re-enumeration, §3.6), or absent
  entirely if USB isn't in an adb-carrying composition at all.
- `transport_id` is **assigned by the host**, increments per USB attach event, and is
  unique per physical connection *regardless of the phone's own serial* — use
  `adb -t <transport_id>` instead of `-s <serial>` when multiple attached phones might
  report identical or non-unique serials (a known problem specifically with cheap/rebranded
  hardware, not expected on genuine OnePlus units, but worth defending against in a
  many-phones product).

Sources: [android-testing-skills SKILL.md — adb devices](https://github.com/skydoves/android-testing-skills/blob/main/adb/devices/connecting-to-devices/SKILL.md), [scrcpy issue #1148 — transport_id vs serial](https://github.com/Genymobile/scrcpy/issues/1148), [pbreault/adb-idea issue #100 — duplicate serials](https://github.com/pbreault/adb-idea/issues/100).

### 3.4 `adb root` vs. `su`

- **`adb root`** restarts the on-device `adbd` daemon itself running as root, so every
  subsequent `adb shell`, `adb push`/`pull`, `adb remount`, etc. from that point on is
  already privileged with no per-command `su` wrapping. It requires a **`userdebug` or
  `eng` build** of Android (`ro.debuggable=1`); on standard retail (`user`-build)
  firmware, which is what ships on essentially every consumer phone including a
  Magisk-rooted OnePlus, `adb root` is refused outright by the stock `adbd` — Magisk's
  `su` mechanism does not itself patch `adbd` to allow this.
- **`su`** (Magisk's implementation) is per-command or per-shell-session privilege
  escalation invoked *from inside* an otherwise unprivileged `adb shell` — i.e.
  `adb shell su -c "setprop sys.usb.config diag,adb"`, or `adb shell` then `su` inside the
  interactive session. This is the correct/only route on a Magisk-rooted retail-build
  OnePlus.
- A Magisk module (`evdenis/adb_root`) exists that restores literal `adb root` behavior
  by patching `adbd`, but is explicitly scoped by its author to Android 10 (possibly 9),
  "definitely not Android 11/12" — not usable as a general solution for a current-firmware
  OnePlus.

Sources: [evdenis/adb_root — Magisk module](https://github.com/evdenis/adb_root), [XDA — use Magisk su in adb shell](https://xdaforums.com/t/help-how-to-use-magisk-su-in-adb-shell.4691455/).

### 3.5 `getprop` keys for handset identity

| Property | Value |
| --- | --- |
| `ro.serialno` | Device serial — matches `adb devices` serial and the USB descriptor serial (§1.7) |
| `ro.product.model` | Marketing/model string, e.g. `CPH2581`-style OnePlus model codes |
| `ro.product.manufacturer` | e.g. `OnePlus` |
| `ro.build.version.release` | Android version, e.g. `14` |
| `ro.build.version.sdk` | API level |
| `ro.build.fingerprint` | Full build fingerprint — the single most useful string for "exact firmware" reproducibility, and what Roadmap 1.2 wants recorded |
| `gsm.version.baseband` / `ro.build.expect.baseband` | Modem/baseband firmware version — relevant since diag log record layouts are modem-generation-dependent (`docs/ARCHITECTURE.md`) |
| `sys.usb.config` / `persist.sys.usb.config` | Current / boot-default USB composition (§2.1) |

Sources: [Famoco — ADB commands reference](https://help.famoco.com/developers/dev-env/adb-commands/), [repeato.app — retrieving Android device properties via ADB](https://www.repeato.app/retrieving-android-device-properties-via-adb-commands/).

### 3.6 Effect of `sys.usb.config` changes on the adb connection

Changing `sys.usb.config` forces a **USB re-enumeration** — the composite device
disconnects and reconnects at the USB-electrical level, which is exactly the same event
Windows sees as an unplug/replug. Consequences:

- **The serial persists.** Since it comes from `ro.serialno`, not the USB session, the
  phone reappears under the same `adb` serial and (per §1.7/§1.6) usually the same COM
  port, once the driver/filter binding for the *new* PID (if it changed) is in place.
  `adb devices` briefly shows the entry gone, then `offline`, then `device` — poll
  `adb wait-for-device` (no built-in timeout; wrap it) rather than assume a fixed delay.
- Direct evidence on typical re-enumeration timing is thin; a related (non-diag) USB
  gadget kernel report noted a device disappearing and re-enumerating roughly **6
  seconds** after initial enumeration as a generic gadget-stack figure — **not a
  documented guarantee for this exact scenario**, but consistent with the anecdotal
  "a few seconds" behavior reported around diag mode-switching in §2.2.
  **[UNVERIFIED precise timing — budget several seconds to ~15s and poll, don't sleep a
  fixed duration.]**
- If the PID changed, the *first* mode-switch after a fresh driver install may need the
  WinUSB/class-driver binding redone (§1.4/§1.6) before the diag COM port reappears —
  a one-time-per-phone-per-firmware cost if matching by class/subclass/protocol rather
  than a fixed PID.

Sources: [android-kernel Google Group — re-enumeration discussion](https://groups.google.com/g/android-kernel/c/tTKskpSfLgA), [DWC2 gadget re-enumeration report](https://lkml.rescloud.iu.edu/2504.1/10568.html), [commaai/android_device_oneplus_oneplus3 init.qcom.usb.rc](https://github.com/commaai/android_device_oneplus_oneplus3/blob/master/rootdir/etc/init.qcom.usb.rc).

---

## 4. Phone GPS from the laptop

### 4.1 `adb shell dumpsys location` — the "last known location" line format

Verified directly against AOSP's current `Location.toString()` implementation
(`core/java/android/location/Location.java`, Apache-2.0 — not GPL, quoting the *format*,
not reproducing the implementation): the string is built as
`"Location[" + provider + " " + "%.6f,%.6f".format(lat, lon)`, then conditionally
appends, **in this fixed order, only for fields the Location object actually has**:
` hAcc=<meters>` (if horizontal accuracy present), ` et=<elapsed-time-since-boot,
human-formatted duration>` (always present), ` alt=<meters>` then optionally
` vAcc=<meters>` (if altitude/vertical-accuracy present), ` mslAlt=<meters>` /
` mslAltAcc=<meters>` (mean-sea-level altitude, if present — a newer field, may be absent
on older Android versions' equivalent `toString()`), ` vel=<m/s>` then optionally
` sAcc=<m/s>` (speed / speed accuracy), ` bear=<degrees>` then optionally
` bAcc=<degrees>` (bearing / bearing accuracy), the literal word ` mock` if the location
came from a mock provider, then ` {<extras Bundle>}` if extras are non-empty, closed by
`]`.
[AOSP `Location.java`, `core/java/android/location/`, current `main` branch](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/location/Location.java).

Real examples matching this exact template, independently corroborated:

```
Location[fused 55.852561,37.312428 hAcc=699.999 et=+2d2h41m23s327ms alt=192.40202601485817 vAcc=14.377255 {Bundle[EMPTY_PARCEL]}]
Location[network 31.315315,121.502259 hAcc=2000 et=+1m11s106ms vAcc=???]
```

[LXG Blog — Android Location](https://lixiaogang03.github.io/2021/01/07/Android-Location/).
This format has been stable in AOSP for years and the fetch above confirms it against a
current source tree, so it should hold across Android 10–15 (the field-presence
conditionals mean older objects simply omit newer fields like `mslAlt`, not that the
whole format differs). `dumpsys location`'s surrounding output ("Location Manager State,"
per-provider "Last Location" and "Last Location (Coarse Interval)" sections, listener
records) is not itself formally documented anywhere found in this research — parse by
locating the `Location[...]` substrings rather than depending on surrounding section
headers, which are freer to change between releases (there is a recorded AOSP commit
titled "Clean up 'adb shell dumpsys location' output", confirming the surrounding text
*has* churned even though the `Location[...]` token format itself is stable).

### 4.2 `adb shell cmd location` — verified subcommands

Verified against AOSP's `LocationShellCommand.java`
(`services/core/java/com/android/server/location/`, Apache-2.0). Top-level commands:

| Command | Effect |
| --- | --- |
| `cmd location is-location-enabled [--user <id>]` | Prints `true`/`false` |
| `cmd location set-location-enabled <true\|false> [--user <id>]` | Toggles the location master switch |
| `cmd location is-adas-gnss-location-enabled` / `set-adas-gnss-location-enabled` | ADAS-specific GNSS toggle (automotive) |
| `cmd location providers <subcommand>` | Namespace for the provider-level operations below |

`providers` subcommands: `add-test-provider`, `remove-test-provider`,
`set-test-provider-enabled`, `set-test-provider-location`, `send-extra-command`.
`set-test-provider-location` takes flags including `--location <lat,lon>`,
`--accuracy <meters>`, `--time <millis>` (exact flag set per the source read; treat the
on-device `cmd location providers set-test-provider-location --help` output on the actual
Android version in use as authoritative, since `BasicShellCommandHandler` subclasses can
vary flag sets release to release).

### 4.3 Forcing a fix

- **Documented, reliable route: mock location injection.** `add-test-provider`, then
  `set-test-provider-enabled <provider> true`, then repeated
  `set-test-provider-location <provider> --location <lat,lon> ...` calls — this is a
  first-class, AOSP-shell-documented mechanism (§4.2) and is what most "fake GPS from adb"
  community tools (e.g. [amotzte/android-mock-location-for-development](https://github.com/amotzte/android-mock-location-for-development))
  wrap. Note this **injects** a location rather than forcing the real GNSS radio to
  acquire faster — not useful if the goal is testing real-world fix time/accuracy, only
  useful for feeding a known coordinate into apps/telemetry that read from
  `LocationManager`.
- **Forcing a real cold-start GNSS fix** (the `delete_aiding_data`-style GPS-HAL debug
  command sometimes referenced in older Android GPS-testing folklore) is **[UNVERIFIED —
  not confirmed against any current, citable source in this research]**; it appears to be
  HAL/vendor-specific and not part of the documented `cmd location` surface. For real-fix
  timing tests, the practical approach is polling `dumpsys location` for the `gps`
  provider's `Location[gps ...]` line after moving the phone somewhere with sky view,
  rather than relying on an undocumented force-fix command.

### 4.4 Alternative: laptop-side USB GPS dongle (NMEA)

A separate USB GPS receiver plugged into the laptop is a legitimate alternative/backup to
reading the phone's own location — decouples "where is the engineer" from "what does the
phone under test report," which is actually useful for cross-checking phone GPS accuracy.
Most USB GPS pucks present as a CDC-ACM virtual COM port (driver install identical in
kind to the diag driver discussion in §1, just a different, usually
class-driver-compliant, device) emitting standard NMEA 0183 sentences at a conventional
4800 or 9600 baud: `$GPGGA` (fix data: time, lat/lon, fix quality, satellite count,
HDOP, altitude), `$GPRMC` (recommended minimum: time, status, lat/lon, speed, course,
date), plus typically `$GPGSV`/`$GPGSA`/`$GPGLL`. Any NMEA-parsing library (or a
20-line regex-based reader) on the laptop side can consume this directly from the COM
port without touching the phone at all.
Sources: [eecis.udel.edu — Generic NMEA GPS Receiver (NTP driver docs)](https://www.eecis.udel.edu/~mills/ntp/html/drivers/driver20.html), [Chip Overclock — A Menagerie of GPS Devices with USB](https://coverclock.blogspot.com/2018/04/a-menagerie-of-gps-devices-with-usb.html), [benholcomb.com — NMEA GPS to a Windows 10 sensor](https://www.benholcomb.com/nmea-gps-to-a-windows-10-sensor/).

---

## 5. Traffic tests from the laptop through adb

| Tool | Available? | Detail |
| --- | --- | --- |
| `ping` | **Yes**, Android 10+ | Provided by toybox, which has supplied most of Android's shell utilities since Marshmallow; `adb shell ping -c 4 8.8.8.8` works out of the box, no root. ([toybox in AOSP](https://medium.com/@mmohamedrashik/toybox-in-aosp-create-custom-adb-commands-90d53ac92a08), [Android's shell and utilities, AOSP docs](https://android.googlesource.com/platform/system/core/+/master/shell_and_utilities/README.md)) |
| `curl` | **No**, on any stock Android version | curl has never shipped as part of AOSP or toybox; every guide for "curl on Android" is instructions to manually install a separately-compiled static binary. Do not depend on it being present. ([XDA — Install cURL and OpenSSL on Android](https://xdaforums.com/t/howto-install-curl-and-openssl-on-android.2362386/)) |
| `wget` | **Partially** — toybox has a `wget` implementation (`toys/pending/wget.c` in AOSP's toybox tree) | Present on current Android builds as part of the toybox multicall binary; HTTPS support specifically was **not confirmed** in this research (`[UNVERIFIED]`) — verify with `adb shell wget --help` and a live HTTPS URL on the actual target Android version before depending on it for timed downloads. ([AOSP toybox wget.c](https://android.googlesource.com/platform/external/toybox/+/refs/heads/android11-mainline-extservices-release/toys/pending/wget.c)) |
| Timing an HTTP download without installing an app | Two viable paths | (a) `adb shell wget <url> -O /dev/null` (or a real output path) timed from the host side by wrapping the `adb shell` invocation and measuring wall-clock, if `wget` supports the target scheme/TLS config; (b) push a static `curl` binary once (`adb push curl /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/curl`) and use it exactly like on any Linux box — more control (explicit `--connect-timeout`, `-w '%{time_total}'` machine-readable timing) at the cost of one extra provisioning step per phone. |
| `iperf3` | Not preinstalled; run as a **pushed static binary** | Standard pattern: `adb push iperf3-android-<abi> /data/local/tmp/iperf3 && adb shell chmod 755 /data/local/tmp/iperf3 && adb shell /data/local/tmp/iperf3 -c <server> ...`. Pre-built ARM64 Android binaries are maintained at [davidBar-On/android-iperf3](https://github.com/davidBar-On/android-iperf3) (built against recent NDK, targets API 28+/Android 9+) and the older [KnightWhoSayNi/android-iperf](https://github.com/KnightWhoSayNi/android-iperf). No root required — `/data/local/tmp` is writable and executable by the shell user by default. |
| Trigger a voice call | `adb shell am start -a android.intent.action.CALL -d tel:<number>` | Confirmed this works **without root** and without any extra permission grant: the `adb shell` identity runs as the `com.android.shell` pseudo-package, whose own `AndroidManifest.xml` declares `<uses-permission android:name="android.permission.CALL_PHONE" />` directly — a privileged system package's declared permission is auto-granted, so the shell UID already carries `CALL_PHONE`. ([AOSP `packages/Shell/AndroidManifest.xml`](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/packages/Shell/AndroidManifest.xml)) A `SecurityException` has been reported on some OEM-modified shell packages that strip permissions from the stock manifest — if that happens on the target phone, `ACTION_DIAL` (`-a android.intent.action.DIAL`, no `CALL_PHONE` needed) plus a synthesized tap/`KEYCODE_CALL` is the fallback, at the cost of needing the dialer UI actually on screen. |
| Hang up | `adb shell input keyevent KEYCODE_ENDCALL` (keycode `6`, so `input keyevent 6` also works) | No root needed |
| Detect call state | `adb shell dumpsys telephony.registry \| grep mCallState` | `mCallState` values: `0` = `CALL_STATE_IDLE`, `1` = `CALL_STATE_RINGING`, `2` = `CALL_STATE_OFFHOOK` (talking, in or out) — matches `TelephonyManager`'s own constants. |

Sources for the table beyond inline citations: [droidbyme/appuals curl guides](https://appuals.com/install-curl-openssl-android/), [android-iperf3 README](https://github.com/davidBar-On/android-iperf3/blob/master/README.md), [Medium — ADB command to make a call](https://medium.com/@roshni.b.tiwari/adb-command-to-make-call-768ce24da25e), [42gears tech blog — adb call/reject/SMS](https://techblogs.42gears.com/using-adb-command-to-make-a-call-reject-a-call-and-sending-receiving-a-message/).

---

## 6. Diag protocol operational details for unattended use

This section describes protocol *behavior* in prose; the wire-format constants quoted
(command byte `0x73`, the four op codes) match what this repository's own clean-room
`fieldtap/diag/protocol.py` already implements (`DIAG_LOG_CONFIG_F = 0x73`,
`LOG_CONFIG_DISABLE_OP/RETRIEVE_ID_RANGES_OP/RETRIEVE_VALID_MASK_OP/SET_MASK_OP`), so this
is corroboration of already-implemented code, not new protocol reverse-engineering.
General mask-configuration commands (`DIAG_LOG_CONFIG_F`) are documented in the same
shape by an independent open-source diag parser:
[RUB-SysSec/mobile_sentinel `diagcmd.py`](https://github.com/RUB-SysSec/mobile_sentinel/blob/master/app/src/main/python/parsers/qualcomm/diagcmd.py)
(GPLv3 — described, not quoted) and by the public msm kernel's diag character-driver
header, which independently names the equivalent kernel-side op constants
`DIAG_CMD_OP_SET_LOG_MASK` / `DIAG_CMD_OP_GET_LOG_MASK`
([`drivers/char/diag/diagchar.h`, android-7.1.0 kernel/msm tree](https://android.googlesource.com/kernel/msm/+/android-7.1.0_r0.2/drivers/char/diag/diagchar.h)).

### 6.1 `DIAG_LOG_CONFIG_F` (`0x73`) mechanics

- The command operates against one **equipment ID** at a time — a 4-bit field (the top
  nibble of a 16-bit log code, e.g. log code `0xB821` has equipment ID `0xB`), giving up
  to 16 equipment IDs per processor/subsystem.
- Sub-operations selected by an op code carried in the request: **0 = disable all
  logging**, **1 = retrieve the valid item-ID range per equipment ID** (used to size the
  bitmask before setting one), **2 = retrieve the currently valid mask**, **3 = set the
  mask** (the actual enable operation — supply a bitmask covering item IDs 0..last-item
  for one equipment ID, one bit per log code within that equipment ID), and a fourth,
  **get-current-log-mask**, op used to read back what's active.
- Enabling a specific set of log codes is therefore at minimum a two-request sequence per
  equipment ID actually needed: request the valid ID range (op 1) to size the mask buffer
  correctly, then send the set-mask request (op 3) with the right bits flipped — repeated
  once per equipment ID that has log codes of interest (e.g. separately for LTE RRC's
  equipment ID and NR RRC's, per `docs/ARCHITECTURE.md`'s note that `0xB821` NR RRC OTA is
  the key anchor log code).
- The kernel driver header independently confirms a **centralized mask-management
  structure covering message, log, and event masks together**, each with its own "update
  buffer" used to propagate a configuration change out to whichever peripheral processor
  (modem, in Qualcomm's terms) actually generates that log — i.e. setting a mask from the
  AP/host side is not instantaneous locally, it is relayed to the baseband/modem
  processor, which is the actual log source.

### 6.2 What happens to the log mask on reboot or USB drop

No single authoritative document states this explicitly for the general case, but the
surrounding evidence is consistent and worth acting on defensively. The kernel diag
driver's client-management structures are per-open-file-descriptor / per-client (§6.4),
and nothing in the header or QCSuper's architecture notes suggests the mask persists in
nonvolatile storage across a reboot. **Treat the mask as volatile and re-send the full
log configuration sequence (§6.1) every time a diag session is (re-)established** — after
every reboot and every USB disconnect/reconnect (including re-enumeration from a
`sys.usb.config` change, §3.6) — rather than assume a previous mask survives. This matches
ordinary QXDM practice, where loading the config before capturing is the standard first
step of every session ([Qualcomm 5G Modem Log Guide, via Quectel forums](https://forums.quectel.com/uploads/short-url/tTD6R4aML76kWql3UvRFinOVAMA.pdf)).
**[UNVERIFIED as a hard protocol guarantee]** — no source says the mask is *guaranteed*
to reset, only that nothing suggests it's preserved. The safe posture is the same either
way: always (re-)send the log configuration at session start, never assume it carried
over.

### 6.3 Keep-alive

No dedicated diag keep-alive command was identified as strictly required by the protocol
itself in the sources reviewed — the practical "keep-alive" concern in this project's
context is really the USB/transport layer (does the COM port or WinUSB handle stay open,
does Windows notice the device vanished) rather than an application-level diag heartbeat.
`DIAG_VERNO_F` (`0x00`, version-number request/response — already in
`fieldtap/diag/protocol.py`'s command table) is a natural cheap round-trip to poll
periodically as a liveness check without side effects, since it is a trivial
request/response the modem answers regardless of what logging is configured.
**[Design recommendation, not a sourced fact]**.

### 6.4 The "diag port busy" problem

Directly confirmed from two independent primary sources:

- QCSuper's own architecture documentation states plainly: **"when the source is a real
  device, the device can accept only one Diag client at once."**
  ([QCSuper architecture.md](https://github.com/P1sec/QCSuper/blob/master/docs/QCSuper%20architecture.md))
- The most common real-world trigger is QXDM (or QPST/QUTS) already holding the port —
  the standard Windows-side symptom is QXDM's own error, *"Failed to create diag service.
  Please quit all QXDM.exe and QUTS.exe from task manager and restart QXDM,"* and the
  standard fix is exactly that: ensure no other diag client (QXDM, QPST, another QCSuper
  instance, another instance of whatever FieldTap's own capture process is) is already
  attached before opening the port.
  ([telecomHall forum — QXDM "Failed to create diag service" error](https://www.telecomhall.net/t/qxdm-failed-to-create-diag-service-error/25219))
- On the phone side, `diag_mdlog` (§2.4) is a diag client too — running it and a
  USB-side capture tool simultaneously against the same phone will produce the same
  single-client conflict, on-device rather than on the Windows side.
- For a product where "the engineer only plugs the phone in," the operational
  consequence is: the capture process must be the *only* thing that ever opens the diag
  handle, own its lifecycle exclusively (open once per session, hold it, close cleanly on
  stop), and the provisioning/setup tooling must never leave QXDM, QPST, or a stray
  previous capture process running in the background — worth an explicit
  process-liveness check (e.g. refuse to start a new capture if a previous FieldTap
  capture process for that same COM port/serial is still running) rather than relying on
  the phone or Windows to arbitrate the conflict.

---

## Open items flagged for hands-on verification

1. **§1.3** — the `FF/FF/30` diag interface descriptor signature is corroborated by one
   community source and QCSuper's own device-matching behavior, not independently
   cross-checked against an official Qualcomm interface spec (the vendor doc page did
   not render to the fetch tooling used).
2. **§1.7** — USB descriptor serial = `ro.serialno` is well-supported generally, but
   per-OEM `iSerialNumber` blanking/hardcoding is a known failure mode; verify on the
   team's actual OnePlus unit before building the COM-port↔phone mapping on it.
3. **§2.5** — the OnePlus 6/6T `DIAG_NOT_WRITEABLE=1` failure under Magisk root has no
   recorded resolution; needs hands-on SELinux/DAC triage if it recurs.
4. **§2.6** — the exact OnePlus model/build used for the Nov 2025 T-Mobile capture isn't
   recorded anywhere in this repository (Roadmap 1.2's problem); blocks turning §2.2's
   table into a verified, device-specific procedure.
5. **§3.6** — precise re-enumeration timing after a `sys.usb.config` change isn't
   documented; budget generously and poll (`adb wait-for-device`) rather than sleep.
6. **§4.3** — no citable source for a real GNSS cold-start force-fix command; mock
   location injection (§4.2) is solid, forcing the *real* radio is not.
7. **§5** — toybox `wget`'s HTTPS support wasn't confirmed either way; verify on-device
   before depending on it over a pushed `curl` binary.
8. **§6.2** — log-mask persistence across reboot/USB-drop is inferred from indirect
   evidence, not one explicit primary-source statement. The recommended posture (always
   re-send config at session start) is robust regardless, so this doesn't block
   implementation, but isn't a confirmed fact.
