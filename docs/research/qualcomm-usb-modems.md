# A Qualcomm USB modem module as the capture device

Research date: 2026-09-10. Everything below is sourced; where a claim is inference
rather than a report from someone who did it, it says so.

---

## Verdict

**Yes. This works, it is documented working by named people on named hardware, and it
is a better product than the rooted handset.**

The Qualcomm diag (DM) interface is a property of the *baseband*, not of Android. On a
phone it is buried behind an OEM kernel driver that the OEM may or may not have shipped
— which is exactly the wall the team hit. On a standalone modem module the same baseband
exposes the same interface as a plain USB endpoint pair, because the module has no
Android and no OEM to remove it. There is no root, no bootloader unlock, no `/dev/diag`,
and no `adb`.

Four separate modules are confirmed working with QCSuper by third parties, on the record:

| Module | Report | Notes from the reporter |
| --- | --- | --- |
| Sierra Wireless **MC7455** | [QCSuper #107](https://github.com/P1sec/QCSuper/issues/107) | `qcsuper --usb-modem auto --wireshark-live`. "No fuss, no muss." Kali, venv. Only step: stop ModemManager. |
| Quectel **EP06-E** | [QCSuper #113](https://github.com/P1sec/QCSuper/issues/113) | In "a cheap USB-C m.2 Modem case". **"No steps required to activate Diag mode - Just plug & play."** |
| Quectel **RM500Q-GL** (5G) | [QCSuper #124](https://github.com/P1sec/QCSuper/issues/124) | Windows 10. `qcsuper.py --usb-modem COM11 --wireshark-live`, port found in Device Manager as the DM port. |
| Telit **LE910C4-NF** | [QCSuper #65](https://github.com/P1sec/QCSuper/issues/65) | "Works out of the box." Raspberry Pi 3B + Sixfab shield, `/dev/ttyUSB0`. "didn't need to run the AT#QCAB, or require root." |
| Quectel **EG25** in Soracom Onyx dongle | [QCSuper #74](https://github.com/P1sec/QCSuper/issues/74) | `--usb-modem /dev/ttyUSB0 --wireshark-live --decrypt-nas --reassemble-sibs`. A sealed retail USB dongle. |

That last row is the product story in one line: **a $111 sealed USB dongle from DigiKey,
plugged into a laptop, producing live RRC and NAS in Wireshark.**

The honest counterweight: the one *partially* working report is also Quectel — the EG25-G
on `/dev/ttyUSB0` produced mostly paging records and a storm of CRC errors
([#84](https://github.com/P1sec/QCSuper/issues/84)). That was 2023 on QCSuper 1.x, and
QCSuper 2.1.0 (March 2026) explicitly lists *"Adapt the USB code to handle higher
throughputs and loose connection"*. The failure mode is a throughput/serial-buffer
problem, not an access problem — which is a problem FieldTap's libusb path is better
positioned to avoid than QCSuper's `ttyUSB` path was. See "What this means for FieldTap".

---

## 1. Module-by-module

### How to read the "diag by default" column

Three different situations hide behind that phrase:

* **On by default** — the shipping USB composition includes the DIAG function. Plug in,
  it is there. All Quectel modules below, Telit LE910C4, Fibocom FM101-GL.
* **On by default *in the generic firmware*, off in OEM firmware** — Sierra. A generic
  Sierra card ships with DIAG; a Dell/Lenovo/HP-branded one often ships MBIM-only and
  needs three AT commands.
* **Composition-selectable** — the vendor gives you an AT command to pick which functions
  appear. Telit `AT#USBCFG`, SIMCom `AT+CUSBPIDSWITCH`, Sierra `AT!USBCOMP`.

### Quectel

| Model | Chipset | Gen | USB IDs | Interface layout | Diag by default | Indicative price |
| --- | --- | --- | --- | --- | --- | --- |
| **EC25** (mini-PCIe / LCC) | MDM9207 | LTE Cat 4 | `2c7c:0125` | 5 interfaces. **if0 = DM** (class FF / subclass FF / protocol FF, 2 endpoints) → `ttyUSB0`; if1 NMEA; if2/if3 AT + modem; if4 QMI/RMNET | Yes | ~$57 (EC25-EUX, Getic) |
| **EG25-G** (mini-PCIe / LCC) | MDM9207 | LTE Cat 4, global bands | same `2c7c` family | Same as EC25 — `ttyUSB0` is the DM port | Yes | ~$60–70 bare; $111.25 as a Soracom Onyx dongle (DigiKey) |
| **EP06-E** (M.2) | Qualcomm (exact part not verified here) | LTE-A Cat 6 | `2c7c` | — | Yes — "just plug & play" per QCSuper #113 | ~$50–90 used |
| **RM500Q-GL** (M.2 key B) | **SDX55** (Snapdragon X55) | 5G NSA + SA sub-6 | `2c7c:0800` | `ttyUSB0` DIAG / `ttyUSB1` NMEA / `ttyUSB2` AT | Yes | ~€235–240 / ~$250–280 |
| **RM520N-GL** (M.2 key B) | **SDX62** (Snapdragon X62), 3GPP Rel-16 | 5G NSA + SA sub-6 | `2c7c:0801` | `ttyUSB0` **DIAG** (documented as "DIAG Port for output developing message"), `ttyUSB1` NMEA/GNSS, `ttyUSB2` AT | Yes | ~$289–370 |

> ### Procurement trap — read this before ordering an RM520N-GL
>
> The RM520N-GL exists in two part numbers that look nearly identical and are **not**
> interchangeable for this purpose:
>
> * **`RM520NGLAA-M20-SGASA`** — USB interface. This is the one you want.
> * **`RM520NGLAP-M20-SGASA`** — **PCIe. It has no USB interfaces at all.**
>
> A user put an `AP` part into an M.2-to-USB adapter and spent a long time installing
> drivers before a Quectel engineer told them: *"then you have no USB interfaces on the
> modem and there is no point in putting it into a USB adapter"*
> ([Quectel forum](https://forums.quectel.com/t/rm520n-gl-on-m-2-b-key-ngff-to-usb-3-0-adapter-not-recognized-on-windows-10/47163)).
> Both parts are stocked at DigiKey and Mouser under the same marketing name. Check the
> full part number on the purchase order.

Quectel does **not** document or support diag. Asked directly for the diag frame
structure, Quectel support replied *"Sorry that we have no such document or app can
share with you"*
([forum](https://forums.quectel.com/t/diag-structure-of-quectel-modems-ec25/2947)), and
asked how to decode NR-RRC from the DM port, *"Actually, we use QXDM to decode the
information, but it need license"*
([forum](https://forums.quectel.com/t/windows-tools-to-capture-and-device-nr-rrc/38320)).
The port is there and open; there is simply no vendor support behind it. For a product,
that is fine — it is exactly the same situation as on a phone — but it does mean no
vendor escalation path when a firmware revision changes something.

Also note: **diag is USB-only.** Quectel confirmed the module's UART cannot carry diag
logs; the only alternative is a dedicated DBG_TX/DBG_RX debug UART on the module pads
([forum](https://forums.quectel.com/t/receiving-ec25-diag-data-via-serial-port/10868)).
So a serial-only integration is not an option.

### Sierra Wireless

| Model | Chipset | Gen | Form factor | USB IDs | Interface layout |
| --- | --- | --- | --- | --- | --- |
| **EM7455** | Snapdragon X7 (MDM9x35) | LTE Cat 6, 300/50 | M.2, MHF4 antennas | generic `1199:9071`/`9070`; Lenovo `1199:9079`/`9078`; Dell DW5811e `413c:81b6`/`81b5` | `qcserial` SWI layout: **if0 = DM/DIAG**, if2 = NMEA, if3 = modem/AT, if8 = QMI/net |
| **MC7455** | Snapdragon X7 (MDM9x35) | LTE Cat 6, 300/50 | **mini-PCIe**, U.FL antennas | same family | same |

EM7455 and MC7455 are the same silicon in different packages — *"the only difference
being packaging"*, plus MHF4 vs U.FL antenna connectors. Pick by which adapter board you
already have.

Interface numbering is non-sequential and comes straight from the kernel:

```c
case QCSERIAL_SWI:
    case 0:  dev_dbg(dev, "DM/DIAG interface found\n");
    case 2:  dev_dbg(dev, "NMEA GPS interface found\n");
    case 3:  dev_dbg(dev, "Modem port found\n");
```
— `drivers/usb/serial/qcserial.c`. Interface 1 is skipped and 8 is the QMI/net function.

**Enabling DIAG when it is missing.** Generic Sierra cards commonly ship with DIAG in the
composition; OEM-branded ones frequently ship `USBCOMP=9` (MBIM only). The fix:

```
AT!ENTERCND="A710"
AT!USBCOMP=1,1,0000100D     # = DIAG + NMEA + MODEM + MBIM  (equivalent to USBCOMP=8)
AT!RESET
```
(EM7565 uses `AT!USBCOMP=1,3,0000100D`.) Bjørn Mork's `swi_usbcomp.pl` does the same
thing more safely. After this, Device Manager shows an AT port, an NMEA port and a
**Qualcomm diagnostic port**. Sources:
[danielewood/sierra-wireless-modems](https://github.com/danielewood/sierra-wireless-modems),
[zukota.com](https://zukota.com/posts/sierra-wireless-em7455-how-to-enable-com-ports/).
The Windows driver for the diag port is named *"Sierra Wireless EM7455 Qualcomm Snapdragon
X7 LTE-A DM Port"* — useful for confirming both the chipset and that a signed driver exists.

Price: **$28–70**, used, on eBay — the cheapest credible entry into this whole idea, and
the one with a first-hand QCSuper success report (#107, MC7455).

### Telit

| Model | Chipset | Gen | Form factor | Diag |
| --- | --- | --- | --- | --- |
| **LE910C4** (`-NF`, `-EU`, `-WWXD`) | MDM9207 | LTE Cat 4 | mini-PCIe / LGA | **Confirmed working out of the box** on `/dev/ttyUSB0` (QCSuper #65) |
| **LN920** | Snapdragon X12+ | LTE Cat 12 / Cat 6 | M.2 | `AT#USBCFG` composition |
| **FN990A28 / A40** | 5G Rel-16 sub-6 | 5G NSA + SA | M.2 | Diag present; NR RRC decode needed QCSuper ≥ 2.1.3 (see §4) |

Composition is selected with `AT#USBCFG=<n>`, and the DIAG function is in the common
modes — e.g. `0x1070` = DIAG + ADB + RmNet + NMEA + MODEM + MODEM + AUX, `0x1071` swaps
RmNet for MBIM, `0x1073` for ECM. The module resets and applies the new composition on
next boot.

Telit is the vendor whose own documentation is most explicit that DIAG is a normal,
selectable USB function. If procurement wants a vendor who will not blink at the question
"does this expose a diagnostic port", Telit is the easiest conversation.

### SIMCom

| Model | Chipset | Gen | Form factor | Diag |
| --- | --- | --- | --- | --- |
| **SIM7600** series | MDM9x07 | LTE Cat 4 / Cat 1 | mini-PCIe, LCC, HAT | Diagnostics port present among the HS-USB ports; composition switched with `AT+CUSBPIDSWITCH=<pid>,1,1` |
| **SIM8200EA-M2** | **SDX55** (Snapdragon X55) | 5G NSA + SA, R15, to 4 Gbps | M.2 | `ttyUSB0..4` enumerate; Windows shows AT / Audio / **Diagnostics** / NMEA ports. ~€365 |

No first-hand QCSuper issue thread names a SIMCom module. But the practical field report
from Mark Houtz used *"my original one from SIMCOM"* — a SIMCom X55 5G modem — with SCAT,
and got 5G NR frames (NR-ARFCN 126510, PCI 460, PLMN 310/260) out of it
([markhoutz.com](https://markhoutz.com/2023/08/21/5g-scanning-with-scat/)). So SIMCom
diag works; it is just less written-up than Quectel.

### Fibocom

| Model | Chipset | Gen | Form factor | Diag |
| --- | --- | --- | --- | --- |
| **FM101-GL** | Qualcomm **SDX12** | LTE Cat 12 | M.2 key B | **Diag in the default composition.** Kernel `option.c`: `2cb7:01a2` = mbim, tty, tty, **diag**, gnss → diag is **interface 3**. Debug variant `2cb7:01a4` = mbim, **diag**, tty, adb, gnss |
| **FM150-AE** | SDX55 | 5G NSA + SA | M.2 | Expected present; not confirmed by a public capture report |
| **FM160-EAU** | SDX62 | 5G, USB 3.1 / PCIe 4.0 | M.2 | Expected present; not confirmed |

Fibocom is the one vendor here where the diag interface number is **not** 0. That matters
for auto-detection — see §7.

---

## 2. Does QCSuper actually support these?

### The mechanism

`--usb-modem` accepts five argument forms, per the README:

* a device path — `/dev/ttyUSB0`, `/dev/ttyHS2`, or `COM2` on Windows
* `vid:pid[:cfg:intf]` — e.g. `05c6:9091`
* `bus:addr[:cfg:intf]` — e.g. `001:003`
* `auto`

When it has to guess which interface is diag, it uses two rules in priority order.
From `src/qcsuper/inputs/usb_modem_pyusb_devfinder.py`:

```python
DEV_FINDER_RULES_SET = [
    dict(bInterfaceClass=255, bInterfaceSubClass=255, bInterfaceProtocol=48,  bNumEndpoints=2),
    dict(bInterfaceClass=255, bInterfaceSubClass=255, bInterfaceProtocol=255, bNumEndpoints=2),
]
```

`48` is `0x30`. **This is byte-for-byte the same signature FieldTap's `--usb` transport
already matches on** (`DIAG_INTERFACE_PROTOCOLS = (0x30, 0xFF)` in
`fieldtap/diag/transport.py`). Whatever QCSuper can find, FieldTap can find.

QCSuper also maps a matched libusb interface back to its `/dev/ttyUSBn` via sysfs
(`_find_char_dev`), so it can hand off to pyserial when the kernel already owns the port.

### Who is on the record

The `confirmed working` label on the QCSuper issue tracker currently carries 23 issues.
Filtering to standalone modems and dongles:

* **#107 Sierra MC7455** — `--usb-modem auto --wireshark-live`, Kali. Only prerequisite:
  disable ModemManager, re-enable after.
* **#113 Quectel EP06-E** — Linux, in a cheap USB-C M.2 modem case, no activation step.
* **#124 Quectel RM500Q-GL** — Windows 10, `--usb-modem COM11 --wireshark-live`.
* **#65 Telit LE910C4-NF** — RPi 3B + Sixfab mPCIe shield, `/dev/ttyUSB0`, no root.
* **#74 Soracom Onyx** (Quectel EG25 / MDM9207 dongle) — `/dev/ttyUSB0`, live view,
  `--decrypt-nas --reassemble-sibs`.
* **#152 "UFI"** and **#115 "UZ801"** — cheap Chinese LTE dongles. Both work, **but the
  UZ801 route goes through ADB on the dongle's internal Android** (`192.168.100.1/usbdebug.html`
  to turn on the ADB server). That reintroduces exactly the Android dependency the team is
  trying to escape. Interesting as a $15 curiosity; wrong for the product.
* Older 2G/3G sticks: Huawei E1750/E1552/E367, ZTE MF190/MF823/MF667, Option Icon 225,
  Novatel Ovation MC998D.

### Known problems

* **#84 Quectel EG25-G — partially working.** PCAP mostly paging records; hundreds of
  `Warning: Wrong CRC` lines; `Unknown log type received for LOG_LTE_RRC_OTA_MSG_LOG_C
  version 38: 20`. Raspberry Pi 4, Sixfab mini-PCIe HAT, `/dev/ttyUSB0` found via
  ModemManager. Open, unresolved in the thread.
* **#30 Quectel EC25-E** — the module's broadband connection dies as soon as QCSuper
  starts. The maintainer's diagnosis is the useful part:
  > *"the throughput of Diag (DM) logs may be higher than the throughput supported by the
  > device's serial link, especially when listening for a lot of categories of logs"*, and
  > *"Most operating systems don't handle well having multiple devices communicating with
  > the same serial port"* — naming ModemManager as the classic offender.

Both of these are **bandwidth and port-contention problems on the `ttyUSB` path**, not
"the module won't give you diag" problems. They point at concrete mitigations: narrow the
log mask, kill ModemManager, and prefer the raw bulk-endpoint path over the tty.

### SCAT

SCAT's [device wiki](https://github.com/fgsect/scat/wiki/Devices) lists modules directly,
with the baseband it expects:

| Module | Baseband per SCAT wiki |
| --- | --- |
| Quectel BG96 | MDM9206 (NB-IoT signalling) |
| Quectel BG95 | MDM9205 |
| Quectel **EC21 / EC25** | MDM9207 |
| Quectel **RM500Q** | X55 |
| Quectel **RM520N** | X62 |
| Sierra Wireless **EM7455** | X7 (MDM9635) |

All under `-t qc`. SCAT's README gives one piece of advice that matters here:

> *"for discrete cellular modules the serial mode should be used instead of USB"*

and explains why: *"the `qcserial` and `option` kernel module do not have the information
of diagnostic port of all Qualcomm-based smartphones and cellular modules."* Mark Houtz
independently reports the same thing from practice — *"Although, USB connections are
possible with SCAT, I have been unable to get it to work"* — and runs
`sudo scat -t qc -s /dev/ttyUSB0 -F /tmp/scat-capture.pcap` instead.

SCAT is Linux-only ("Only tested in Linux, mostly various versions of Ubuntu").

---

## 3. The practical bench setup

Three shapes, cheapest to most capable.

### A. LTE, cheapest, highest confidence — ~$50–110

| Item | Price |
| --- | --- |
| Sierra **MC7455** (mini-PCIe) or **EM7455** (M.2), used | $28–70 |
| M.2 key-B → USB 3.0 adapter board with nano-SIM slot (or mini-PCIe → USB equivalent) | $10–20 |
| 2× LTE antennas + MHF4 (EM) or U.FL (MC) → SMA pigtails | $10–20 |
| **Total** | **~$50–110** |

This is the configuration behind QCSuper #107. It is LTE Cat 6 only — no 5G, no NR at all.

### B. Sealed dongle, zero assembly — $111.25

**Soracom Onyx LTE USB dongle** (DigiKey / Mouser, $111.25 with or without eSIM).
Quectel EG25-G / MDM9207 inside, LTE Cat 4 global bands, internal antennas, nano-SIM
slot, plain USB-A. Confirmed in QCSuper #74 with live Wireshark.

For a drive-test product this is the demo unit. There is nothing to build, nothing to
explain to a security team, and it looks like what it is: a USB modem.

### C. 5G — ~$350–450

| Item | Price |
| --- | --- |
| Quectel **RM520N-GL-AA** (`RM520NGLAA-M20-…` — *not* `AP`) or **RM500Q-GL** | $250–370 |
| Waveshare **USB TO M.2 B KEY** 5G dongle enclosure — USB 3.1 Type-A, aluminium heatsink, 4× SMA, SIM slot, module not included | ~$36–45 |
| 4× 5G sub-6 SMA antennas | $25–40 |
| **Total** | **~$310–455** |

The Waveshare enclosure is explicitly documented as compatible with SIMCom, Quectel and
Fibocom M.2 key-B modules, so it is a single mechanical platform for testing several
modules against each other. Alternative: The Wireless Haven sells the same class of
enclosure pre-populated with an RM520N-GL.

Watch the power budget. A sub-6 5G module in transmit pulls well over what a bus-powered
USB 2.0 port will supply; use the enclosure's USB 3 port on a machine that actually
delivers 900 mA, or a powered hub. The reported "not recognized" failures on these
adapters split roughly into (a) the PCIe-only `AP` part number, and (b) power.

### Other ready-made options seen in the wild

* **Cradlepoint MC400-1200M** USB modem caddy with a Telit LM960A18 PCIe module inside —
  used successfully with both QCSuper and SCAT on a WLAN Pi (markhoutz.com).
* **"Cheap USB-C m.2 modem case"** + Quectel EP06-E — QCSuper #113, with photos.
* Quectel's own **Mini PCIe EVB kit** — the vendor development board; more expensive and
  bulkier than a $15 adapter, but it is the supported reference and has proper power.

---

## 4. What a module captures that a phone does, and what it does not

### The same

The diag log codes are a property of the Qualcomm baseband firmware. A module running
MDM9207 emits the same log codes as a phone running MDM9207. Confirmed by SCAT's
`diagcmd.py`, which uses one code table for all Qualcomm targets, phone or module:

```python
LOG_LTE_RRC_OTA_MESSAGE                    = 0xc0   # 0xB0C0
LOG_LTE_NAS_ESM_SEC_OTA_INCOMING_MESSAGE   = 0xe0   # 0xB0E0
LOG_LTE_NAS_EMM_PLAIN_OTA_INCOMING_MESSAGE = 0xec   # 0xB0EC
LOG_LTE_ML1_SERVING_CELL_MEAS_AND_EVAL     = 0x17f  # 0xB17F
LOG_LTE_ML1_SERVING_CELL_MEAS_RESPONSE     = 0x193  # 0xB193
LOG_LTE_ML1_SERVING_CELL_INFO              = 0x197  # 0xB197
LOG_5GNR_RRC_OTA_MESSAGE                   = 0x821  # 0xB821
LOG_5GNR_NAS_5GMM_PLAIN_OTA_INCOMING_MESSAGE = 0x80A
```

So yes: **0xB0C0, 0xB0Ex, 0xB821, 0xB80x and the ML1 measurement records are all
available from a module.** FieldTap's `decode/registry.py` already enumerates this exact
set (0xB0C0–0xB0EF, 0xB16B/0xB173/0xB179/0xB17F/0xB180/0xB193/0xB195, 0xB800–0xB814,
0xB821–0xB826). Nothing in the decode layer needs to change for a module source.

SCAT v1.4.0 added *"Qualcomm: add ML1 meas database update support"*, so the neighbour
measurement database records specifically are handled.

### The differences that actually matter

**In a module's favour:**

* **GNSS is already there.** Most of these modules expose an NMEA port (`ttyUSB1` on
  Quectel, interface 2 on Sierra) with GPS/GLONASS/BeiDou/Galileo. That is a cleaner
  position source than scraping Android location, and it is time-aligned with the same
  device that produced the measurements.
* **Deterministic RF.** External antennas with known gain and placement, rather than a
  handset's body-dependent internal antennas. For repeatable drive test this is a
  genuine upgrade, not a compromise.
* **Band locking is easy and documented.** `AT+QCFG="band"` / `AT!BAND` / SIMCom
  equivalents let you pin the scanner to a band under test. Doing this on a handset is
  a fight.
* **Multiple units, no MDM problem.** Ten modules on a powered hub is a normal thing to
  build. Ten rooted phones is a procurement and security conversation.

**Against:**

* **No voice, so no VoLTE/IMS KPIs.** Most M.2 and mini-PCIe data cards are data-only.
  The IMS/SIP/RTP diag items (0x156E SIP message, 0x1830/0x1831 VoLTE session setup/end,
  0x17F2 voice call stats) that SCAT knows how to parse will simply never fire. Some
  EC25 variants do support voice; verify per SKU if VoLTE matters.
* **No application-layer or device context.** No app-level throughput, no handset
  battery/thermal, no screen. If the product's story is "what the user experienced",
  a module measures the radio, not the user.
* **Category ceiling.** An EM7455 is Cat 6 (300/50). It will not show you what a Cat 20
  handset sees on the same cell, and it cannot report CA combinations it cannot form.
  Match the module category to the network being tested.
* **One SIM, one PLMN at a time** — same as a phone.

### 5G specifically

* **NSA and SA are both visible** on X55 (RM500Q-GL, SIM8200) and X62 (RM520N-GL) parts.
* **NR RRC decode has been the sore spot, and it is now largely fixed.** The trouble is
  that Qualcomm changed the NR RRC OTA log packet header layout across versions:
  * QCSuper 2.0.0 (Feb 2024) added *"basic 5G support"*.
  * [#148](https://github.com/P1sec/QCSuper/issues/148): a **Quectel RM500Q** decoded
    NR RRC correctly in Wireshark 4.4.8, while a **Telit FN990A28** did not — newer
    packet version, unsupported header.
  * [#156](https://github.com/P1sec/QCSuper/issues/156): an SDX65 module on 5G SA showed
    NR frames as bare UDP.
  * **QCSuper 2.1.3, released 2026-07-23**, is exactly the fix: *"Fix 5G NR RRC dissection
    for log packet version >= 17"*. PR #171 documents the cause — packet version ≥ 17 uses
    a **31-byte header**, not the 23/24-byte layout the bundled Lua dissector assumed, so
    the message-length field decoded as garbage and the inner PDU was sliced at the wrong
    offset. Symptom on the way to diagnosis: forcing Decode As → GSMTAP yields
    *"Unknown GSMTAP version (17)"* because the packet-version byte lands where GSMTAP
    expects its version.
  * SCAT v2.0.0 (Dec 2025) completed the GSMTAPv3 binary format; **NR RRC and NAS-5GS are
    only available through GSMTAPv3**, needing Wireshark ≥ 4.2.5 and the `scat.lua` plugin.
* **The ARFCN field problem.** GSMTAPv2's ARFCN field is too narrow for CBRS EARFCNs
  (55240–56739) or any NR-ARFCN (e.g. 636667–646666 for 3.55–3.7 GHz), so those captures
  carry **ARFCN = 0**. This affects LTE on high bands too, not just 5G. GSMTAPv3 is the
  fix and is what SCAT now emits.

### The forward risk worth naming: encrypted diag

SCAT v2.0.0 release notes: *"Qualcomm: Identification of DIAG secure log public key and
encrypted log packet. **No support for decryption is planned.**"* The command code is
`DIAG_SECURE_LOG_F = 0x9e` in `diagcmd.py`.

Newer Qualcomm firmware can wrap log payloads in an encrypted envelope keyed to the
vendor. None of the modules recommended above has been reported doing this — they are
X7/X12/MDM9207/X55/X62 parts on relatively old, stable firmware, which is part of why
they work. But it is the mechanism that would end this whole approach on a future
platform, and it argues for standardising on parts that are known-good rather than
chasing the newest module.

---

## 5. AT-command scanning — the independent answer to "list all operators and cells"

This is worth treating as a **second, separate capability**, not a fallback. It answers a
different question than diag does, and on some modules it answers it better.

### Quectel — the strongest AT scanning story

* **`AT+QSCAN=<mode>[,<option>]`** on the 5G modules (RM5xx / RG5xx / RM520N).
  `AT+QSCAN=3,0` scans LTE **and** NR. Output rows look like:

  ```
  +QSCAN: "LTE",311,480,5230,16,-105,-13,23,116
           tech  MCC MNC EARFCN PCI RSRP RSRQ SINR band
  ```

  That is a real PCI scanner: every cell it can hear, with channel, PCI and level — with
  no SIM required for the physical-layer part and with no diag involved at all.
* **`AT+QENG="servingcell"`** — full serving-cell detail (state, tech, band, EARFCN/NR-ARFCN,
  PCI, RSRP/RSRQ/SINR, TAC, CID, MCC/MNC).
* **`AT+QENG="neighbourcell"`** — the neighbours the network told the UE to monitor.
  **Documented limitation: it does not report 5G cells.**
* Also `AT+QENG="3gcomm"`. All of this is Quectel's "QuecCell" engineering-mode command
  set, and it is properly documented in the *QuecCell AT Commands Manual* — a rare case of
  a vendor documenting the thing you want.

### Sierra

* `AT!GSTATUS?` — serving-cell status.
* `AT!LTEINFO?` — serving cell (EARFCN, MCC, MNC, TAC, CID, levels) **plus intra-frequency
  and inter-frequency neighbour cells**.
* `AT!NRINFO?` — NR equivalent on the newer parts.
* **No general band-scan command comparable to `AT+QSCAN`.** Sierra users on the vendor
  forum are explicit that there is no `AT+QENG="neighbourcell"` equivalent. Sierra gives
  you the serving cell and its reported neighbours, not a sweep.

### Every module — `AT+COPS=?`

Returns the PLMN list only: operator long name, short name, numeric MCC/MNC and access
technology. **No PCI, no EARFCN, no level.** And it is expensive:

* Reported response times of **3–4 minutes** on an EC25, with intermittent `CME ERROR`
  instead of a list.
* It takes the modem **out of service** for the duration — you cannot scan and hold a
  session at the same time.
* Results depend on the SIM's provisioned PLMN list; with a non-steered SIM it takes
  longer and behaves less predictably.

**Recommendation:** treat `AT+COPS=?` as the "which operators exist here" answer,
`AT+QSCAN` (Quectel 5G modules only) as the "which cells exist here" answer, and diag as
the "what did the network and the UE actually say to each other" answer. They compose
well: `QSCAN` gives you a target list, band-lock to a target, then capture diag on it.
That is a scanner product, and it needs only Quectel hardware to build.

---

## 6. Driver and OS caveats

### Linux

* **`option`** binds the vendor-specific serial interfaces on Quectel, SIMCom, Telit and
  Fibocom → `/dev/ttyUSB0..n`. **`qcserial`** binds Sierra with the SWI layout above.
  Both are in-tree; no vendor driver needed for the ports themselves.
* **ModemManager is the number-one failure cause.** It opens the same ports and interleaves
  reads, which is exactly the "one received byte in two" corruption the QCSuper maintainer
  describes in #30. Every working report says the same thing — QCSuper #107: *"Just need to
  disable ModemManager and turn it back on after starting."* Either `systemctl stop
  ModemManager`, or set `ID_MM_DEVICE_IGNORE=1` via a udev rule on the diag interface (the
  better answer for a product, since it leaves the modem's data function usable).
* **Serial path** needs root or `dialout` membership. **libusb path** needs a udev rule or
  root, plus detaching the kernel driver from the interface first (`option` will already
  own it).
* SCAT's own guidance is to prefer `-s /dev/ttyUSBn` over USB mode on discrete modules,
  because the kernel modules do not know every module's diag interface. QCSuper's `auto`
  mode sidesteps this by matching descriptors rather than a device table.
* No new kernel is needed for any of the recommended parts; `option`/`qcserial` have had
  these VID/PIDs for years. Newer Fibocom and Telit compositions occasionally need a recent
  kernel — the `option.c` patches adding `2cb7:01a2` etc. landed around 5.15.

### macOS

**This is the easy platform, and it is already documented in `docs/MACOS.md`.**

* The diag interface is vendor class FF/FF, which matches **no Apple driver**. Nothing
  claims it, so **libusb opens it directly with no kext, no driver install and no root**.
  There will be **no `/dev/cu.*` for diag** — an empty `ls /dev/cu.*` is the correct state,
  not a failure.
* **Caveat that bites on modules specifically:** if the module's USB composition includes
  a CDC-ACM function, macOS *will* create a `/dev/cu.usbmodem*` for it. **That is the AT
  port, not diag.** Do not point the tool at it. Sierra's MBIM composition and Quectel's
  ECM composition both change what appears here.
* Getting the module to work as a *network interface* on macOS is a different and much
  harder problem (it needs `AppleWWANSupport` kexts with the vendor ID in their list).
  **This does not matter** — for capture you never need the modem to carry traffic.
* Use `ioreg -p IOUSB -l -w 0` to confirm enumeration; `system_profiler SPUSBDataType`
  was removed in macOS Tahoe 26.
* On Apple Silicon laptops, the USB accessory permission prompt applies (already noted in
  the FieldTap host-platform notes).

### Windows

**Much easier than the handset case, and this is a real product argument.**

* Vendors ship **signed Windows drivers** for these modules that create a named COM port:
  *"Quectel USB DM Port"* (`USB\VID_2C7C&PID_0125&MI_00`), *"Sierra Wireless … DM Port"*.
  `Quectel_Windows_USB_Driver(Q)_NDIS` covers both the EC25 and RM520N series. QCSuper #124
  is literally "find the DM port in Device Manager, pass `--usb-modem COM11`".
* This is the opposite of the phone situation, where the diag COM port depends on an OEM
  driver package that may not exist. Here the module vendor's own driver is the supported,
  downloadable, signed artifact.
* Alternative if you would rather not install a vendor driver: bind **WinUSB** to the diag
  interface with Zadig and use FieldTap's `--usb` path. QCSuper documents the equivalent
  libusb-win32 filter approach.

---

## 7. What this means for FieldTap, concretely

### It plugs into what already exists

`fieldtap/diag/transport.py` already has both halves of what is needed:

* `SerialTransport` → `/dev/ttyUSB0` or `COM11`, which is the path every confirmed working
  report used.
* `UsbTransport` with `DIAG_INTERFACE_PROTOCOLS = (0x30, 0xFF)` and a requirement of class
  FF / subclass FF / exactly 2 bulk endpoints — **identical to QCSuper's rule set**.

And `fieldtap/decode/registry.py` already covers every log code these modules emit. The
module route is a new *source*, not a new *pipeline*.

### Three changes worth making before the first bench test

**1. Prefer protocol `0x30` over `0xFF` instead of taking the first match.**

`_iter_diag_interfaces` walks configurations and interfaces in enumeration order and yields
the first interface matching *either* protocol. On a Quectel EC25 that happens to be
correct — interface 0 is the DM port and it is `FF/FF/FF` with 2 endpoints, while the AT and
modem interfaces have 3 endpoints and are filtered out. But it is luck, not logic. QCSuper
runs its two rules as **separate full passes** (`for ruleset in DEV_FINDER_RULES_SET`), so a
`0x30` interface anywhere on the device beats an `0xFF` interface at a lower index. FieldTap
should do the same.

**2. Add a small VID/PID → interface hint table, and expose the override.**

`UsbTransport.__init__` already takes an `interface` argument; it just is not reachable from
the CLI as far as this research went. Known-good values:

| Vendor | VID | Diag interface |
| --- | --- | --- |
| Quectel | `2c7c` | 0 |
| Sierra Wireless | `1199` | 0 |
| Sierra (Dell-branded) | `413c` | 0 |
| **Fibocom FM101-GL** | `2cb7:01a2` | **3** |
| Fibocom FM101-GL debug | `2cb7:01a4` | 1 |

Fibocom is the counter-example that proves the point: `mbim, tty, tty, diag, gnss` means the
first `FF/FF` 2-endpoint interface is *not* diag. A pure descriptor heuristic will pick wrong
on that part.

**3. Handle ModemManager explicitly on Linux.**

Every single working report mentions it. FieldTap's `setup`/preflight should detect a running
ModemManager holding the target port and say so in plain words, rather than surfacing whatever
garbled HDLC results. A udev rule shipping `ID_MM_DEVICE_IGNORE=1` for the diag interface is
the polite fix; `systemctl stop ModemManager` is the blunt one.

### Prefer the libusb path, and keep the mask tight

The two bad QCSuper reports (#84 CRC storm, #30 broadband collapse) are both `ttyUSB`
throughput problems, and the maintainer's own diagnosis is that diag output can outrun the
serial link when a lot of log categories are enabled. FieldTap's `--usb` transport reads the
bulk endpoints directly and never touches the tty line discipline or its buffers, which should
be strictly better. Worth measuring both on the bench and recording the result — it is a
differentiator if it holds.

Independently: keep the enabled log mask to RRC + NAS + ML1 and leave IP traffic off. #84's
CRC storm correlates with `--include-ip-traffic` being on.

---

## 8. Recommended next step

Buy two things, both cheap, both with a first-hand success report behind them:

1. A **Sierra MC7455 or EM7455 + USB adapter + antennas** (~$60–100). Proves the LTE path,
   proves the FieldTap `--usb` and serial transports against a real module, and is the
   configuration in QCSuper #107.
2. A **Soracom Onyx** ($111.25, DigiKey). Proves the sealed-dongle product story end to end
   with no assembly, and is the configuration in QCSuper #74.

Then, only if LTE proves out, a **Quectel RM520N-GL-AA** in a Waveshare M.2-to-USB enclosure
for 5G — and confirm the part number is `RM520NGLAA`, not `RM520NGLAP`.

Total to answer the question properly: under $200 for the LTE half, under $600 including 5G.

---

## 9. What this research did not establish

* **Nobody has run FieldTap against any modem module.** All evidence here is QCSuper and
  SCAT. The transports and log codes line up on paper; that is not the same as a capture.
* **No public capture report exists for SIMCom SIM7600/SIM8200 or any Fibocom part with
  QCSuper or SCAT.** Their diag ports are documented to exist (vendor docs, kernel
  `option.c` composition strings, Windows port names) and one field report used a SIMCom
  X55 with SCAT successfully, but no one has posted "this exact module, this exact command".
* **The Quectel EP06's exact Qualcomm part number** was not verified.
* **Prices are indicative marketplace observations**, not quotes, and the used Sierra market
  is volatile.
* **5G SA on the newest platforms (SDX65, X70/X75) has open decode issues** and the QCSuper
  2.1.3 fix has not been independently confirmed against a module in this research.
* **Whether any of these modules restricts its diag log mask** relative to a phone was not
  directly tested. The code tables are shared and there is no report of a missing log code,
  but "the EG25 mostly emitted paging records" in #84 is at least consistent with a mask
  problem as well as with a throughput problem, and the two have not been disentangled.

---

## Sources

**Tools**
- QCSuper — https://github.com/P1sec/QCSuper
- QCSuper interface matching — `src/qcsuper/inputs/usb_modem_pyusb_devfinder.py`
- QCSuper releases (2.0.0 basic 5G; 2.1.0 USB throughput; 2.1.3 NR RRC pktver ≥ 17) — https://github.com/P1sec/QCSuper/releases
- QCSuper PR #171 (31-byte NR RRC header) — https://github.com/P1sec/QCSuper/pull/171
- QCSuper device support wiki — https://github.com/P1sec/QCSuper/wiki/Device-Support
- QCSuper "confirmed working" issues — https://github.com/P1sec/QCSuper/issues?q=label%3A%22confirmed+working%22
- SCAT — https://github.com/fgsect/scat and https://github.com/fgsect/scat/wiki/Devices
- SCAT log code table — `src/scat/parsers/qualcomm/diagcmd.py`
- SCAT releases (v1.4.0 ML1 meas db; v2.0.0 GSMTAPv3, secure log identification) — https://github.com/fgsect/scat/releases

**Per-module issue threads**
- #107 Sierra MC7455 — https://github.com/P1sec/QCSuper/issues/107
- #113 Quectel EP06-E — https://github.com/P1sec/QCSuper/issues/113
- #124 Quectel RM500Q-GL — https://github.com/P1sec/QCSuper/issues/124
- #74 Soracom Onyx / Quectel EG25 — https://github.com/P1sec/QCSuper/issues/74
- #65 Telit LE910C4-NF — https://github.com/P1sec/QCSuper/issues/65
- #84 Quectel EG25-G partial — https://github.com/P1sec/QCSuper/issues/84
- #30 Quectel EC25-E throughput — https://github.com/P1sec/QCSuper/issues/30
- #148 RM500Q works / Telit FN990A28 does not — https://github.com/P1sec/QCSuper/issues/148
- #156 SDX65 5G SA as UDP — https://github.com/P1sec/QCSuper/issues/156

**Hardware and vendor**
- Linux `qcserial.c` Sierra interface layout — https://raw.githubusercontent.com/torvalds/linux/master/drivers/usb/serial/qcserial.c
- Osmocom Quectel EC25 wiki (USB descriptors) — https://osmocom.org/projects/quectel-modems/wiki/EC25
- Quectel EC25 DM port hardware ID `USB\VID_2C7C&PID_0125&MI_00` — DeviceHunt / driver databases
- Quectel RM520N-GL port layout and PID 0x0801 — https://www.waveshare.com/wiki/RM520N-GL
- RM520NGLAP has no USB — https://forums.quectel.com/t/rm520n-gl-on-m-2-b-key-ngff-to-usb-3-0-adapter-not-recognized-on-windows-10/47163
- Quectel diag undocumented — https://forums.quectel.com/t/diag-structure-of-quectel-modems-ec25/2947
- Quectel: diag not available over UART — https://forums.quectel.com/t/receiving-ec25-diag-data-via-serial-port/10868
- Quectel: "we use QXDM" for NR-RRC — https://forums.quectel.com/t/windows-tools-to-capture-and-device-nr-rrc/38320
- Quectel Windows USB driver (NDIS V2.8, covers EC25 + RM520N) — https://www.quectel.com/download/quectel_windows_usb_driverq_ndis_v2-8_en/
- Sierra AT!USBCOMP / AT!ENTERCND — https://github.com/danielewood/sierra-wireless-modems
- Sierra EM7455 enabling COM ports, USBCOMP=8/9 — https://zukota.com/posts/sierra-wireless-em7455-how-to-enable-com-ports/
- MC7455 vs EM7455 packaging — https://forum.sierrawireless.com/t/mc7455-vs-em7455/8968
- Sierra AT!LTEINFO / AT!NRINFO / no neighbour scan — https://forum.sierrawireless.com/t/seeing-neighbor-cell-towers-5g-and-lte-using-em9293/34694
- Telit AT#USBCFG compositions — Telit LE910Cx AT Command Reference
- Fibocom FM101-GL `2cb7:01a2` = mbim,tty,tty,diag,gnss — Linux `option.c` patch, https://www.spinics.net/lists/linux-usb/msg219041.html
- SIMCom AT+CUSBPIDSWITCH — SIM7500/SIM7600 AT Command Manual
- Quectel QuecCell AT commands (QENG servingcell/neighbourcell) — Quectel EC2x&EG9x&EM05 QuecCell AT Commands Manual
- AT+COPS=? 3–4 minute scans on EC25 — https://forums.quectel.com/t/ec25e-ec200a-network-scan-using-at-cops/22747

**Field reports**
- 5G scanning with SCAT (SIMCom X55, NR frames, ARFCN width problem) — https://markhoutz.com/2023/08/21/5g-scanning-with-scat/
- CBRS PCI scanning with SCAT (Cradlepoint MC400-1200M / Telit LM960, serial vs USB) — https://markhoutz.com/2022/05/03/cbrs-pci-scanning-with-scat/

**Pricing**
- Soracom Onyx $111.25 — DigiKey
- Sierra EM7455 $28–70 — eBay listings
- Quectel RM520N-GL $289–370; RM500Q-GL ~€235–240 — Techship / retail listings
- Waveshare USB TO M.2 B KEY 5G dongle enclosure ~$36–45 — https://www.waveshare.com/usb-to-m.2-b-key.htm
- SIM8200EA-M2 ~€365 — welectron
- Quectel EC25-EUX from ~$56.92 — Getic
