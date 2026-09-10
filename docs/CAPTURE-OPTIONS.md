# Ways to capture real radio frames

The OnePlus cannot do diag: the vendor ships no diag driver in any kernel branch
for it. These are the routes that do work, cheapest and fastest first. Sources
are in [`research/qualcomm-usb-modems.md`](research/qualcomm-usb-modems.md),
[`research/diag-capable-handsets.md`](research/diag-capable-handsets.md) and
[`research/sdr-broadcast-capture.md`](research/sdr-broadcast-capture.md).

## 1. A Qualcomm modem module. Best value, and no phone at all.

The diag interface belongs to the **baseband**, not to Android. A standalone
modem module has no OEM to remove the driver, so its diag port is just a
vendor-specific USB endpoint pair: no root, no bootloader unlock, no Android.

It presents interface class `FF`, subclass `FF`, protocol `0x30`, which is
exactly what FieldTap already matches on, and it emits the same log codes the
register already covers. **This is a new source, not a new pipeline.**

| Buy | Cost | Notes |
| --- | --- | --- |
| Soracom Onyx USB dongle (Quectel EG25-G) | ~$111 | Sealed, no assembly. Best demo unit. LTE |
| Sierra EM7455/MC7455 + M.2-to-USB adapter + antennas | ~$60-100 | Cheapest. A screwdriver and a few AT commands if OEM-branded. LTE |
| Quectel RM520N-GL-**AA** + Waveshare USB enclosure | ~$310-455 | 5G. Order the **AA** part, not AP, which is PCIe-only |

Four modules are on record working with QCSuper, reported by named third
parties. Nobody has run **FieldTap** against one yet.

**The trap:** on Linux, ModemManager opens the same port and interleaves its
reads, which shows up as corrupt frames rather than an error. `fieldtap setup`
now warns when it is running.

## 2. A Samsung Galaxy S23, Snapdragon. Best handset, and no root.

`*#0808#` exposes diag with **no root and no bootloader unlock**. Confirmed by
the SCAT project's own tested-device table, and by a user capturing 5G NR on an
unrooted S24 Ultra.

This matters commercially more than it looks. A rooted handset is hard to put
past an operator's security team; a stock one is not. It removes the biggest
objection to the whole product category.

- **Buy the S23** (SM-S911B or S911U1, ~$300-450 used). Snapdragon in *every*
  region, so no chipset lottery, unlike the S22 and S24.
- **Buy carrier-unlocked.** Verizon and AT&T units have the secret codes
  disabled and need a third-party tool to re-enable them.
- **Do not root it.** A rooted S928B lost diag permanently and reflashing stock
  did not bring it back.
- `*#0808#` resets on OTAs that carry a carrier config change, so make
  re-arming it part of the setup flow.

About 30 minutes to first frames. Gives the full diag stream: LTE RRC with MIB
and the SIBs, NAS, NR RRC, NAS-5GS, and the measurement records.

## 3. An SDR. The only route that needs no phone and no SIM.

`srsue` from srsRAN_4G writes a MAC-LTE pcap that **already contains the MIB and
every SIB**, and stock Wireshark dissects it end to end. That is a genuine
substitute for diag *for broadcast channels*, and it answers "decode MIB and
SIBs" directly.

| Radio | Cost | What it gets |
| --- | --- | --- |
| RTL-SDR v4 | ~$30 | Cell search and MIB only. Not enough bandwidth for SIB1, and its 1.77 GHz ceiling excludes n78 |
| bladeRF 2.0 xA4 | ~$540 | The value pick. Same chip as a B210 |
| USRP B210 | ~$2,400 | What every tool targets |

**Hard limits.** Downlink only in practice. Everything after RRC security
activation on a live network is ciphered and unreachable. And for 5G, srsue
decodes MIB and SIB1 but its pcap writes are disabled, so NR broadcast lands in
the log and not in a capture file.

## 4. A custom kernel for the OnePlus. Do not fund this.

The obvious plan, lifting the diag driver from Qualcomm's own SM8450 release,
does not work: **CodeLinaro's SM8450 kernels do not contain it either.** The
newest public diag source is two kernel generations older and predates the GKI
boundary. Nobody has done this port. Weeks to months of work with a real chance
of failing at the modem transport step, for a phone that then fails every OTA.

## Recommendation

Buy a **Soracom Onyx** and a **Samsung Galaxy S23**, together under $600. The
Onyx proves the pipeline on hardware this week with no Android in the way; the
S23 is the device a customer would actually carry, and needs no root. Treat the
SDR as a later addition for operator-independent broadcast scanning.
