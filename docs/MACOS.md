# Testing on a MacBook

A Mac is very likely a better host for this than the Windows laptop, for one
reason: **macOS needs no driver.**

On Windows the Qualcomm diag interface only becomes usable if a vendor driver
turns it into a COM port, or if you bind WinUSB to it with Zadig. That whole
class of failure does not exist on a Mac. Android's own documentation is
explicit that macOS needs no USB driver for adb, and libusb's FAQ says that when
no kernel driver claims a device, "libusb will run out of the box, you do not
even need to have root privilege and there is no need to set up udev rules like
Linux." The Qualcomm diag interface is vendor-specific, so nothing claims it,
which is exactly the easy case.

Sources for everything below are in
[`research/windows-diag-and-device-control.md`](research/windows-diag-and-device-control.md).

> **FieldTap has never been run on macOS.** The code is now free of Windows-only
> assumptions and the platform branches are exercised, but no one has executed
> it on a Mac. Expect to be the first, and expect to correct this page.

---

## One thing to know before you start

**A diag port never appears as a device node on macOS.** Do not go looking for
`/dev/cu.usbmodem*`. macOS only creates those for CDC-ACM devices, and the diag
interface is vendor class `FF/FF/0x30`, which matches no Apple driver. An empty
`ls /dev/cu.*` is the expected, correct state, not a failure.

The phone still enumerates. You will see it in `ioreg`. The diag interface is
reached through libusb, and FieldTap's `--usb` transport is exactly that.

One exception worth knowing: if the USB composition you set happens to include
an `acm` function, a `/dev/cu.usbmodem*` **will** appear. That is the AT command
port, not diag. Do not point FieldTap at it.

---

## Step 0: is the phone detected at all?

Do this before installing anything. On the Windows laptop the phone never
enumerated, so this is the question that actually matters.

```bash
ioreg -p IOUSB -l -w 0 > /tmp/usb-before.txt
```

Plug the phone in, wait five seconds, then:

```bash
ioreg -p IOUSB -l -w 0 > /tmp/usb-after.txt; diff /tmp/usb-before.txt /tmp/usb-after.txt
```

Any difference means the Mac saw the phone. No difference means it did not, and
nothing further will work until that changes.

Note `system_profiler SPUSBDataType` was **removed in macOS Tahoe 26**, so use
`ioreg` rather than the command most guides still recommend. Check with
`sw_vers` if you are unsure which macOS you are on.

To see names and IDs of what is attached:

```bash
ioreg -p IOUSB -l -w 0 | grep -E '"(USB Vendor Name|USB Product Name|idVendor|idProduct)"'
```

`ioreg` prints those IDs in decimal. Qualcomm's vendor ID `0x05c6` shows as
`1478`.

### If nothing appears

In likelihood order:

1. **A charge-only cable.** This is the single most likely cause and it matches
   your symptom exactly: the phone charges and the host sees no attach event at
   all. Cables bundled with power banks, chargers and accessories are very often
   two-wire. There is no reliable way to tell by looking.
   **Do not use the white cable that came with a Mac or iPhone charger** for this
   test. Do not use an active Thunderbolt cable either. Use the phone's own
   cable, or one marked SS.
2. **Accessory approval, on an Apple Silicon MacBook.** See the next section.
   This produces precisely the "charges but invisible" symptom.
3. **A hub, dock or adapter.** Plug directly into the Mac. Try both orientations
   of the USB-C connector and every port.
4. **The phone's own USB policy.** Some hardened Android builds and managed
   devices disable the USB data lines while locked, at the hardware level.

**The decisive test**, which removes every Android setting from the equation:
boot the phone to its bootloader, usually volume-down plus power, and run

```bash
fastboot devices
```

Fastboot enumerates regardless of USB debugging, screen lock, USB mode or ROM
policy. If it appears in fastboot but not in Android, the problem is software or
mode. If it appears in neither, on two known-good cables and two ports, the
phone's USB hardware is the fault and no host will rescue it.

---

## The Apple Silicon gate

On Apple Silicon **laptops** running macOS 13 or later, a newly attached USB
accessory must be approved before it can exchange data. Apple's own wording is
that if you do not allow it, "your Mac won't recognize the accessory or give it
access to the data on your Mac," while "accessories can still charge."

That is the same signature as a dead cable, so rule it out deliberately:

- The prompt reads **"Do you want to connect the USB accessory to this Mac?"**
- The setting is **System Settings, Privacy and Security, Allow accessories to
  connect**. The default is "Ask for new accessories".
- For debugging, set it to **Always allow**, unplug, replug, and re-check
  `ioreg`. Put it back afterwards.
- The Mac must be **unlocked** to approve. If the prompt appeared while the lid
  was shut or on another desktop and was missed, the phone stays silently
  data-blocked with no second prompt.

This matters again later: switching the phone into diag mode re-enumerates it
with a different USB ID, so the Mac can treat it as a **new** accessory and
prompt again in the middle of a capture. Desktop Macs have no such setting,
which makes a Mac mini a useful control.

---

## Step 1: install FieldTap and prove it works with no phone

```bash
git clone https://github.com/nikhilsimnovus/FieldTap.git
cd FieldTap && git checkout clean-room-implementation
python3 -m venv .venv && .venv/bin/pip install -e ".[all]"
brew install --cask wireshark
brew install libusb
.venv/bin/fieldtap setup --install-adb
```

Then, with no phone attached at all:

```bash
.venv/bin/fieldtap demo
```

That generates a simulated drive test, decodes it through your Wireshark, and
opens a session report. If that works, the entire pipeline is sound on your Mac
and anything that fails later is about the phone, not the software.

`fieldtap setup` tells you what is missing. On macOS it should report tshark,
adb and a libusb backend. If it says **no libusb backend**, that is the common
Python-cannot-find-the-dylib problem rather than a missing library:

```bash
.venv/bin/pip install libusb-package
```

That ships a prebuilt libusb for both Apple Silicon and Intel and removes the
path question entirely.

---

## Step 2: get the phone talking

```bash
.venv/bin/fieldtap devices
```

Enable USB debugging on the phone, plug it in **with the screen unlocked**,
accept the RSA prompt, and confirm:

```bash
adb devices -l
```

Read the state carefully, because it is a diagnostic ladder:

| State | Meaning |
| --- | --- |
| empty list | No adb interface visible. Check `ioreg` to tell "not enumerating" from "debugging off" |
| `unauthorized` | **Good news.** USB, enumeration and the adb daemon all work. Only the key was not accepted. Unlock the phone and look for the dialog |
| `offline` | Enumerated but the handshake did not finish. `adb kill-server && adb start-server` |
| `device` | Working |

FieldTap treats `unauthorized` as "not ready, retry" and will pick the phone up
as soon as you accept.

---

## Step 3: capture

Both routes need root on the phone. Neither needs a driver on the Mac.

### Route A, the simpler one on a Mac: the diag USB interface through libusb

```bash
.venv/bin/fieldtap auto --profile all
```

FieldTap asks the phone to expose diag, waits for the interface to appear, and
captures through libusb. Watch for a second accessory-approval prompt at the
moment the phone re-enumerates.

To do it by hand:

```bash
.venv/bin/fieldtap devices
.venv/bin/fieldtap capture --usb --name mac-test --live
```

### Route B: the on-device helper over adb

This reads `/dev/diag` on the phone and relays it over adb, so it does not
depend on the USB composition at all. It needs `device/fieldtap-diagd.c` built
with the Android NDK first, which has not been done yet:

```bash
ANDROID_NDK=~/Library/Android/sdk/ndk/<version> ./device/build.sh
.venv/bin/fieldtap capture --adb --helper device/fieldtap-diagd-arm64
```

Route A is the one to try first on a Mac.

---

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Nothing in `ioreg` when plugged in | Cable, accessory approval, hub | Step 0 above, in that order |
| Phone charges, never appears | Charge-only cable, or approval denied | Swap cable; set Allow accessories to Always allow |
| `ls /dev/cu.*` shows nothing | **Expected.** No driver claims diag on macOS | Use `--usb`, not a serial port |
| `no backend available` from pyusb | Python cannot find libusb | `pip install libusb-package` |
| `adb devices` says `unauthorized` | RSA key not accepted | Unlock the phone, accept, tick always allow |
| Prompt never appears | Stale key | Revoke USB debugging authorizations on the phone, then `adb kill-server` and remove `~/.android/adbkey*` |
| Device drops when diag is enabled | Re-enumeration triggered a new approval prompt | Approve it; consider Always allow for the session |
| Finder does not show the phone | **Expected.** macOS has no native MTP | Irrelevant to capture. For a quick data-path check, set the phone to PTP and open Image Capture |

Do not use Android File Transfer as evidence of anything. Google discontinued it
around May 2024 and it fails to launch on current macOS regardless of your phone.

---

## What to report back

If Route A works, the useful things to capture for the device support matrix are
the phone model, its Android build and modem firmware, the USB composition that
worked, and whether the accessory prompt interfered. `session.json` in the
capture directory records most of it automatically.
