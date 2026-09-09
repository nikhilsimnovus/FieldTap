# Getting a handset ready

The goal is that an engineer plugs a phone into the laptop and `fieldtap auto`
does the rest. That only works once the phone exposes its Qualcomm diag port
and Windows has a driver for it. This page is the one-time preparation per
handset model.

The detail behind every claim here, with sources, is in
[`research/windows-diag-and-device-control.md`](research/windows-diag-and-device-control.md).
Where that research could not confirm something it says so, and so does this page.

---

## 1. The laptop

Run the checker first. It reports what is missing and can fetch adb for you:

```bash
fieldtap setup --install-adb
```

| Needed | Why | Where |
| --- | --- | --- |
| Wireshark 4.0+ | It is the decoder. Without it there are no KPIs and no live view. | https://www.wireshark.org/download.html |
| adb (platform-tools) | Handset identity, diag enablement, GPS, traffic tests. | `fieldtap setup --install-adb` |
| Qualcomm USB serial driver | Makes the diag interface appear as a COM port. | Ships inside most OEM driver packages; see below |
| Python 3.9+ with `pyserial` | Reads the COM port. | `pip install -e ".[all]"` |

**The diag COM port** appears as *Qualcomm HS-USB Diagnostics 9091* (hardware ID
`USB\VID_05C6&PID_9091&MI_00`, sometimes PID `901D`). Do not match on the PID:
it changes between firmware builds and USB compositions. The reliable signature
is the USB interface descriptor **class `FF`, subclass `FF`, protocol `0x30`**,
which is what FieldTap matches on.

If no driver is available, bind **WinUSB** to that interface with
[Zadig](https://zadig.akeo.ie/) and capture with `--usb` instead of a COM port.

> **Several phones at once.** Windows gives each phone its own COM port, and the
> port carries the phone's USB serial number, which equals `ro.serialno` and the
> adb serial. FieldTap uses that to tie each COM port to the right handset, so a
> multi-phone campaign does not mix sessions up. When a phone does not expose a
> serial and more than one is attached, FieldTap refuses to guess and treats
> them as separate unnamed handsets rather than pairing them wrongly.

---

## 2. The phone

### What is required

* **A Qualcomm (Snapdragon) modem.** Exynos and MediaTek phones do not speak
  this protocol. A Galaxy S24 may be either, depending on region.
* **Root**, in almost all cases, to switch the USB composition and to read
  `/dev/diag`.
* **USB debugging** on, and this computer authorised (accept the RSA prompt).

### Enabling diag

FieldTap tries this for you at the start of a capture. It runs, as root:

```bash
setprop sys.usb.config diag,diag_mdm,qdss,qdss_mdm,serial_cdev,dpl,rmnet,adb
```

falling back to shorter compositions. The long form above is what current
OnePlus, OPPO and Realme builds expect; a shorter string is silently ignored on
some of them. Use `persist.sys.usb.config` instead to make it survive a reboot.

Changing the composition re-enumerates USB, so adb disconnects and returns after
a few seconds. FieldTap waits for the new port rather than failing.

`fieldtap disable-diag` puts the phone back to `mtp,adb` when you are done.

### Non-root paths, where they exist

| Vendor | Code | Notes |
| --- | --- | --- |
| Samsung (Snapdragon SKUs) | `*#0808#` | USB settings menu; choose a DM+ADB mode |
| Xiaomi | `*#*#13491#*#*` | Not present on every build |
| OnePlus | `*#801#` | **Dead on current OxygenOS.** Historical only |

Treat these as best-effort. The research found the OnePlus dialer code no longer
works on recent builds, so root is the dependable route.

### Root on a OnePlus, in outline

1. Unlock the bootloader (wipes the phone).
2. Patch that build's `boot.img` with Magisk, flash it, verify with `su`.
3. Confirm `/dev/diag` is readable as root.

**Carrier caveat.** Several T-Mobile OnePlus models (7T, 8, 8T, 9, 10T) resist
bootloader unlock. A 6T can be unlocked after the carrier unlock is complete.
Newer unlocked OnePlus models unlock immediately. Confirm your exact model
before promising a customer it will work.

**Magisk root is not automatically enough.** On some OnePlus 6/6T builds
`/dev/diag` stays unwritable even as root. If diag enablement succeeds but no
data flows, that is the failure you are looking at.

---

## 3. When it does not work

| Symptom | Cause | Fix |
| --- | --- | --- |
| `adb says 'unauthorized'` | RSA prompt not accepted | Unlock the phone, tick "always allow" |
| No diag port after enablement | Driver missing, or the composition was ignored | Check Device Manager; try WinUSB + `--usb` |
| Port opens, nothing arrives | Another process owns `/dev/diag` | Only one diag client is allowed at a time. Stop the OEM logger or `diag_mdlog` |
| `modem did not answer the version query` | Not the diag port (it is the modem or NMEA port) | Pick the port whose interface protocol is `0x30` |
| Capture stops when the phone is touched | Cable or re-enumeration | FieldTap ends the session tidily and still writes the report; re-plug to start a new one |

---

## 4. Recording what worked

Every capture writes `session.json` with the handset model, Android build,
baseband, modem build and the transport used. That file is how a support matrix
gets built: after a successful session on a new model, add a row to the device
table with what the sidecar recorded.

Nothing in this repository has been run against a handset yet. The first person
to do so should expect to correct this page, and should.
