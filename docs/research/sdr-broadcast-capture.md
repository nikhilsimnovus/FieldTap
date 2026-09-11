# Capturing and decoding cellular broadcast + signalling over the air with an SDR

Research note for FieldTap. Written 2026-09-10.

**Why this exists:** the OnePlus 10 Pro is rooted but the vendor ships no Qualcomm
diag driver, so `/dev/diag` is unavailable and the normal QCSuper/SCAT path is
closed on that handset. This note asks whether an SDR can substitute — i.e.
produce real MIB / SIB / RRC frames that land in Wireshark through a pcap.

**Short answer:** yes for LTE, and it is better than expected — `srsue` from
srsRAN_4G writes a **MAC-LTE pcap that already contains the MIB and every SIB**,
and stock Wireshark chains MAC-LTE → LTE-RRC → NAS-EPS with no custom dissector.
For 5G NR it is much weaker: srsue decodes NR MIB/SIB1 but deliberately does
**not** write them to pcap, and the only tool that gets NR broadcast + RRC + NAS
into Wireshark today is a 2025 USENIX Security research artifact (Sni5Gect).

---

## 1. LTE downlink broadcast decode from an SDR

### 1.1 Summary table

| Tool | MIB | SIB1 | SIB2+ | Works on RTL-SDR (~$30)? | Writes pcap? | Maintained (2025–26)? |
|---|---|---|---|---|---|---|
| **srsRAN_4G `srsue`** | yes | yes | yes | **no** (needs TX-capable, full-rate SDR) | **yes — MAC-LTE + NAS-EPS** | yes, release 25.10 |
| srsRAN_4G `cell_search` example | yes | no | no | **yes** | no (stdout) | yes (same repo) |
| srsRAN_4G `pdsch_ue` example | yes | yes (default RNTI = SI-RNTI) | yes | **no** — errors "RTL-SDR does not support this sample rate" | no (stdout / TCP) | yes (same repo) |
| **LTE-Cell-Scanner** (JiaoXianjun) | yes | **no on RTL-SDR** (see below) | no | yes for MIB | no | last push Jan 2024; not dead, not active |
| **openLTE `LTE_fdd_dl_scan`** | yes | yes | SIB2/3/4/8 | listed as supported | no | dormant (SourceForge; `osh/openlte` mirror) |
| **FALCON** | internal only | **no** | **no** | no | no — CSV of DCI + raw `.bin` IQ | last push Oct 2023 |
| **OWL / imdeaOWL** (IMDEA) | no | no | no | no | no | effectively dead (pinned to srsLTE 1.3/2.0, 2016–17) |
| **LTESniffer** (KAIST) | yes | yes | yes | no (2×RX USRP/bladeRF + i7 8-core) | **yes — mac-lte pcap** | last push Oct 2024 |
| MATLAB LTE Toolbox "Cell Search, MIB and SIB1 Recovery" | yes | yes | partial | works with RTL-SDR support package for MIB | no | yes (commercial) |

### 1.2 srsRAN_4G — the primary recommendation

`srsRAN_4G` is **not** deprecated. Latest changelog entry is **25.10**
("various compilation fixes for newer compiler versions, smaller improvements in
various layers"), repo not archived, ~4.0k stars, last push Jan 2026. SRS's newer
`srsRAN_Project` is gNB-only and does **not** replace srsue.

Three separate things live in that repo, and they have very different hardware needs:

1. **`lib/examples/cell_search`** — RX-only PSS/SSS/PBCH scanner. Decodes MIB
   (PCI, number of PRBs, PHICH config, number of Tx ports, SFN). Verified working
   with an RTL-SDR through SoapySDR in srsRAN issue #1453, which found five cells
   between 1815.2–1843.8 MHz. Usage (verbatim from `usage()`):

   ```
   Usage: cell_search [agsendtvb] -b band
       -a RF args          -d RF devicename
       -g RF gain [70.00]  -s earfcn_start   -e earfcn_end
       -n nof_frames_total [100]             -v verbose
   ```

2. **`lib/examples/pdsch_ue`** — full PDSCH receiver. Its default RNTI is
   `SRSRAN_SIRNTI` (0xFFFF), so out of the box it decodes **system information**.
   Same issue #1453 shows it failing on RTL-SDR: `"RTL-SDR does not support this
   sample rate"` when srsRAN asks for 15.36 MHz, plus `Invalid number of PRB 125`.
   Flags of interest: `-f rx_frequency`, `-g gain`, `-a RF args`, `-r RNTI in hex`,
   `-n nof_subframes`, `-i input_file` (offline IQ replay), `-A nof RX antennas`.

3. **`srsue`** — the full UE. Does cell search → MIB → SIB1 → SIB2 → PRACH →
   RRC connection. **This is the one that writes pcap.** It needs a full-duplex,
   full-rate, TX-capable SDR because it goes on to transmit PRACH; an RX-only
   dongle cannot drive it.

### 1.3 LTE-Cell-Scanner — honest limits

Its own `TODO` file settles the RTL-SDR question, verbatim:

> "SIB decoding when hackrf is present because hackrf seems has enough BW.
> (rtl-sdr's BW is not enough for decoding SIB)"

So: `CellSearch` and `LTE-Tracker` give you PCI / N_id_1 / N_id_2 / duplex mode /
Tx antenna count / frequency offset / MIB on an RTL-SDR. The advertised
"full stack ... to SIB ASN1 messages decoded in PDSCH" is the MATLAB
`LTE_DL_receiver.m` path operating on wideband captures, not the C++ RTL-SDR path.
Supported radios: RTL-SDR, HackRF, bladeRF (USRP only via MATLAB/Octave).

### 1.4 FALCON and OWL — wrong tool for this job

Both are **PDCCH/DCI** analyzers, not system-information decoders.

- **FALCON** decodes DCI formats 0/1A, 1, 1B, 1C, 2, 2A, 2B to reveal active RNTIs
  and per-UE resource allocations. Its README states it does not decode SIBs or the
  MIB as an output product (it does an internal srsLTE cell search to lock).
  FDD only, up to 20 MHz, tested on USRP B210/B205mini and LimeSDR Mini, needs
  ≥4 physical cores. Output is **CSV** (timestamp, RNTI, MCS, PRBs, TBS, raw DCI hex)
  plus raw `.bin` IQ. Pinned to a patched srsLTE 18.09. Last push Oct 2023.
- **OWL / imdeaOWL** is the ancestor of FALCON, blind DCI decoding, AGPLv3,
  built on srsLTE v1.3 (2016) later v2.0 (2017), UHD + bladeRF. Nothing has moved
  in years. Skip it.

If FieldTap ever wants "how many UEs are on this cell and what is the cell's PRB
utilisation", FALCON is the right tool. For MIB/SIB it is not.

### 1.5 LTESniffer — the closest thing to a product-grade LTE sniffer

KAIST SysSec, AGPL-3.0, 2.2k stars, last push Oct 2024, built on top of FALCON +
srsRAN. It is the only open LTE tool other than srsue that **emits a MAC-LTE pcap**:
`sniffer_dl_mode.pcap`, `sniffer_ul_mode.pcap`, `api_collector.pcap`.

- Decodes PDCCH, PDSCH, PUSCH → DCI, RNTIs, plaintext broadcast, and in LTE the
  identity leakage (RNTI↔TMSI mapping, IMSI from Identity Response, UE capabilities).
- Explicitly **cannot decrypt** ciphered messages.
- FDD only, max 20 MHz.
- Hardware: downlink-only works with most srsRAN-compatible SDRs with **2 RX
  antennas over USB 3.0**; uplink needs a USRP X310 on 10 GbE or two B-series with
  GPSDO. Host: Intel i7, ≥8 physical cores, 16 GB RAM, Ubuntu 18.04/20.04/22.04.
- Example: `sudo ./src/LTESniffer -A 2 -W 4 -f 1840e6 -C -m 0`

Filter tip from their README: `mac-lte.direction == 0` isolates uplink.

---

## 2. Does srsRAN write pcap? (the critical question)

**Yes.** And the encapsulation is exactly the kind stock Wireshark already handles.

### 2.1 The DLTs, from the source

`lib/include/srsran/common/pcap.h` (srsRAN_4G master):

```c
#define MAC_LTE_DLT   147
#define NAS_LTE_DLT   148
#define UDP_DLT       149
#define S1AP_LTE_DLT  150
#define NAS_5G_DLT    151
#define NGAP_5G_DLT   152
```

Per tcpdump's link-layer type registry, **147–162 are LINKTYPE_USER0–LINKTYPE_USER15,
"Reserved for private use"**. They are not registered LTE link types — Wireshark
resolves them through the `DLT_USER` dissector table, which is why every srsRAN
pcap needs a one-time Wireshark preference edit.

There is **no `mac-lte-framed` link type registered with tcpdump**; `mac-lte-framed`
is a Wireshark *dissector name* you point a DLT_USER entry at.

### 2.2 Which framing srsRAN actually writes

`lib/src/common/pcap.c` has two MAC writers:

- `LTE_PCAP_MAC_WritePDU()` — writes `pcap record header → MAC_Context_Info_t → PDU`.
  No magic string, no fake UDP. This is the form the `mac-lte-framed` dissector eats
  (DLT_USER 147).
- `LTE_PCAP_MAC_UDP_WritePDU()` — writes `pcap record header → dummy UDP header
  (dst port 0xdead, src port 0xbeef) → "mac-lte" magic string → MAC_Context_Info_t → PDU`.

**srsRAN's shipped config and docs use the UDP-framed variant on DLT 149.** The
comment block in `srsue/ue.conf.example` says so directly:

```
# MAC-layer packets are captured to file a the compact format decoded
# by the Wireshark. For decoding, use the UDP dissector and the UDP
# heuristic dissection. Edit the preferences (Edit > Preferences >
# Protocols > DLT_USER) for DLT_USER to add an entry for DLT=149 with
# Protocol=udp. Further, enable the heuristic dissection in UDP under:
# Analyze > Enabled Protocols > MAC-LTE > mac_lte_udp and MAC-NR > mac_nr_udp
# For more information see: https://wiki.wireshark.org/MAC-LTE
# Using the same filename for mac_filename and mac_nr_filename writes both
# MAC-LTE and MAC-NR to the same file allowing a better analysis.
# NAS-layer packets are dissected with DLT=148, and Protocol = nas-eps.
```

`LTE_PCAP_NAS_WritePDU()` writes the NAS PDU raw with no context struct — hence
DLT 148 → `nas-eps` straight through. `nas_pcap.cc` picks `NAS_5G_DLT` (151) for NR
and `NAS_LTE_DLT` (148) for LTE.

### 2.3 MIB and SIBs really are in the LTE MAC pcap

This is the part that matters for FieldTap. `mac_pcap_base` exposes
`write_dl_bch()`, `write_dl_sirnti()`, `write_dl_pch()`, `write_dl_mch()`,
`write_dl_crnti()`, `write_ul_crnti()`, `write_dl_ranti()`, and the call sites exist:

- `srsue/src/stack/mac/mac.cc :: bch_decoded_ok()`
  ```c
  if (pcap) {
    pcap->write_dl_bch(payload, len, true, phy_h->get_current_tti(), cc_idx);
  }
  ```
  → MAC-LTE BCH PDU → Wireshark calls the **`lte-rrc.bcch.bch`** dissector →
  **BCCH-BCH-Message = the MIB**, fully expanded (dl-Bandwidth, phich-Config,
  systemFrameNumber).

- `srsue/src/stack/mac/dl_harq.cc :: dl_tb_process::tb_decoded()`
  ```c
  // when is_bcch && ack:
  harq_entity->pcap->write_dl_sirnti(payload_buffer_ptr, cur_grant.tb[tid].tbs,
                                     ack, cur_grant.tti, harq_entity->cc_idx);
  // otherwise:
  harq_entity->pcap->write_dl_crnti(payload_buffer_ptr, cur_grant.tb[tid].tbs,
                                    cur_grant.rnti, ack, cur_grant.tti,
                                    harq_entity->cc_idx);
  ```
  → `rntiType = SI_RNTI` → Wireshark calls **`lte-rrc.bcch.dl.sch`** →
  **BCCH-DL-SCH-Message = SIB1 and every SI message (SIB2/3/4/5/…)**.

Wireshark's `packet-mac-lte.c` holds `lte_rrc_bcch_bch_handle`,
`lte_rrc_bcch_dl_sch_handle` (and an NB-IoT variant), and its rntiType enum is
`NO_RNTI, P_RNTI, RA_RNTI, C_RNTI, SI_RNTI, SPS_RNTI, M_RNTI, SL_BCH_RNTI,
SL_RNTI, SC_RNTI, G_RNTI`. The MAC-LTE preference **"Attempt to decode BCH, PCH
and CCCH data using LTE RRC dissector"** defaults to TRUE, so BCCH decode works
with no extra configuration beyond the DLT_USER entry.

Independent confirmation from practice: Nick vs Networking ran srsue with the PCAP
option against a live cell without authenticating and read the SIBs (including
neighbour-cell reselection parameters) straight out of Wireshark. Daniel Estévez's
2024 write-up notes the same file contains "packets with the MIB, SIB1, and SIB2
and SIB3".

### 2.4 There is no separate RRC pcap writer

Worth being blunt about, because the brief asked specifically:

- **srsRAN_4G has no RRC pcap file.** The only `write_ul_rrc_pdu()` call sites in
  the whole repo are in `lib/test/asn1/srsran_asn1_rrc_ul_dcch_test.cc` — a unit
  test. RRC reaches Wireshark **inside** the MAC pcap, via MAC-LTE → LTE-RRC.
- **srsRAN_Project has no `rrc_enable` pcap key either.** Its pcap config reference
  lists only mac, rlc, ngap, n3, e1ap, f1ap, f1u, e2ap.

For dedicated signalling you additionally need the MAC-LTE preference
**"Attempt to dissect LCID 1&2 as SRB 1&2"**, which makes Wireshark route
SRB0/1/2 through `lte-rrc.dl.ccch` / `lte-rrc.ul.ccch` / `lte-rrc.dl.dcch` /
`lte-rrc.ul.dcch` and then into `nas-eps`.

### 2.5 Config options that turn pcap on

**srsue (`~/.config/srsran_4g/ue.conf`)**

```ini
[pcap]
enable          = mac,mac_nr,nas    ; comma list of: mac | mac_nr | nas | none
mac_filename    = /tmp/ue_mac.pcap
mac_nr_filename = /tmp/ue_mac_nr.pcap
nas_filename    = /tmp/ue_nas.pcap
```

Equivalent CLI overrides:
```bash
sudo srsue ~/.config/srsran_4g/ue.conf \
     --pcap.enable mac,nas \
     --pcap.mac_filename /tmp/ue_mac.pcap \
     --pcap.nas_filename /tmp/ue_nas.pcap \
     --rf.dl_earfcn 1850
```

**srsenb (`enb.conf`)** — `[pcap] enable = true`, `filename = /tmp/enb.pcap` (MAC-LTE).
**srsepc (`epc.conf`)** — `pcap.enable = true` → S1AP, DLT 150 → `s1ap`.

**srsRAN_Project gNB (`gnb.yaml`)**

```yaml
pcap:
  mac_enable:    true
  mac_filename:  /tmp/gnb_mac.pcap
  mac_type:      udp          # "udp" -> DLT 149, or "dlt" -> DLT 157 mac-nr-framed
  rlc_enable:    false
  rlc_filename:  /tmp/gnb_rlc.pcap
  rlc_rb_type:   all
  ngap_enable:   true
  ngap_filename: /tmp/gnb_ngap.pcap
  n3_enable:     false
  f1ap_enable:   false
  e1ap_enable:   false
  e2ap_enable:   false
```

### 2.6 Wireshark setup, exact

Edit → Preferences → Protocols → DLT_USER → "Encapsulations Table" → Edit:

| DLT | Payload protocol | For |
|---|---|---|
| User 2 (DLT=149) | `udp` | MAC-LTE and MAC-NR (srsRAN's UDP-framed default), also RLC-NR |
| User 0 (DLT=147) | `mac-lte-framed` | only if you write the non-UDP compact form |
| User 1 (DLT=148) | `nas-eps` | srsue NAS pcap (LTE) |
| User 3 (DLT=150) | `s1ap` | srsepc |
| User 4 (DLT=151) | `nas-5gs` | srsue NAS pcap (5G) |
| User 5 (DLT=152) | `ngap` | srsRAN_Project gNB |
| User 10 (DLT=157) | `mac-nr-framed` | srsRAN_Project with `mac_type: dlt` |
| User 6 (153) `e1ap` · User 7 (154) `f1ap` · User 8 (155) `e2ap` · User 9 (156) `gtp` | | srsRAN_Project |

Then:
- Analyze → Enabled Protocols → **MAC-LTE → `mac_lte_udp`** and **MAC-NR → `mac_nr_udp`**
- Preferences → Protocols → **MAC-LTE**: enable "Attempt to decode BCH, PCH and CCCH
  data using LTE RRC dissector" (default on) and "Attempt to dissect LCID 1&2 as SRB 1&2"
- Preferences → Protocols → **MAC-NR**: enable both "Attempt to…" boxes; set
  LCID→DRB mapping to "From configuration protocol"
- RLC-NR over UDP needs **Wireshark ≥ 4.3.x**

Display filters that work immediately: `lte_rrc.BCCH_BCH_Message`,
`lte_rrc.BCCH_DL_SCH_Message`, `mac-lte.rnti-type == 4` (SI-RNTI), `nas_eps`.

### 2.7 Two gotchas that matter for a drive-test product

1. **srsue decodes MIB/SIBs once and stops.** It assumes system information never
   changes, so after cell selection it stops monitoring BCCH — the MIB and SIBs
   appear only in the first few frames of the pcap. For drive test you want
   continuous re-acquisition per cell. Practical workarounds: restart srsue per
   cell / per scan tick, or patch the SI-acquisition procedure to re-arm.
2. **Ciphering.** Everything after `SecurityModeCommand` is encrypted. Wireshark
   *can* decrypt if you paste the keys — but srsRAN logs 256-bit keys and Wireshark
   wants the **last 16 bytes only**, and PDCP COUNT gets confused when a UE
   reattaches and sequence numbers reset. That path is only usable against your own
   test network.

### 2.8 Bridging to FieldTap's existing exported-PDU pipeline

Two options, and the first is probably better:

**(a) Consume the MAC-LTE pcap as-is.** Wireshark already chains
MAC-LTE → LTE-RRC → NAS-EPS. No conversion, no custom dissector, and you get MAC
scheduling context (SFN/SF, RNTI type, CRC status, carrier index, retx count) for free.

**(b) Re-wrap into `LINKTYPE_WIRESHARK_UPPER_PDU` (252)** to match what FieldTap
already emits. The link-layer header is a sequence of TLVs: 2-byte big-endian type,
2-byte big-endian length, value; string values are NUL-padded and the length
includes padding; terminate with `EXP_PDU_TAG_END_OF_OPT` (type 0, length 0). Set
`EXP_PDU_TAG_DISSECTOR_NAME` to the registered dissector name:

| Content | Dissector name |
|---|---|
| LTE MIB | `lte-rrc.bcch.bch` |
| LTE SIB1 / SI messages | `lte-rrc.bcch.dl.sch` |
| LTE dedicated DL / UL | `lte-rrc.dl.dcch` / `lte-rrc.ul.dcch` |
| LTE CCCH | `lte-rrc.dl.ccch` / `lte-rrc.ul.ccch` |
| LTE paging | `lte-rrc.pcch` |
| EPS NAS | `nas-eps` |
| NR MIB | `nr-rrc.bcch.bch` |
| NR SIB1 / SI | `nr-rrc.bcch.dl.sch` |
| NR dedicated | `nr-rrc.dl.dcch` / `nr-rrc.ul.dcch` |
| 5G NAS | `nas-5gs` |

---

## 3. 5G NR broadcast decode from an SDR

Set expectations low. SSB detection and MIB are solid; SIB1 is real but confined to
research artifacts and specific tested bands/bandwidths; nothing open is
production-grade.

### 3.1 srsRAN_4G `srsue` in 5G SA mode — decodes SIB1 but will not pcap it

5G-SA support landed in **22.04**; later releases extended it to 5/10/15/20 MHz.
srsue does SSB search → PBCH/MIB → `Starting SIB1 acquisition` → RRC setup. All of
that is visible in the **log**, including ASN.1 dumps at debug level.

**But the NR broadcast pcap path is disabled on the UE side.** In
`srsue/src/stack/mac_nr/mac_nr.cc`:

```c
// pcap->write_dl_bch(payload, len, true, tti);     // <-- commented out
```

and a code search across the repo shows `write_dl_si_rnti_nr()` is only ever called
from **`srsgnb/src/stack/mac/mac_nr.cc`** — the transmit side. srsue's `write_pcap()`
only emits `write_dl_ra_rnti_nr`, `write_dl_pch_nr`, `write_dl_crnti_nr`, plus
`write_ul_crnti_nr`. So:

> **srsue 5G SA gives you NR MIB/SIB1 in the console log, not in the pcap.**
> Getting them into pcap is a small patch (uncomment the BCH line, add a
> `write_dl_si_rnti_nr()` call in the SIB1 path) — a genuinely tractable upstream
> contribution and probably the cheapest route to NR broadcast pcap for FieldTap.

### 3.2 srsRAN Project

gNB/CU/DU only. There is no UE and no sniffer. Its MAC-NR pcap is transmit-side —
useful for validating your own dissection, useless for over-the-air capture of
someone else's cell.

### 3.3 NR-Scope (Princeton) — the best open NR SIB decoder

- ACM CoNEXT 2024, AGPL-3.0, ~553 commits, built on srsRAN_4G libs + UHD + srsGUI
  + yaml-cpp + liquid-dsp.
- Decodes **SSB/MIB, SIB1 and all other SIBs**, DCI, RACH, and **RRCSetup (Msg4)**
  for continuous UE-attach detection. Claims 1.4% DCI miss rate on a 100 MHz
  T-Mobile TDD cell, up to 64 concurrent UEs, and multi-USRP for multiple gNBs.
- Hardware: USRP **X300/X310** with CBX or TwinRX daughterboards. Not a B210 tool.
- **No pcap output.** Telemetry/CSV. You would have to write the exported-PDU
  wrapper yourself — but since it already produces decoded SIB octets, that is the
  easy half.

**Maturity: research-grade but the most functionally complete.** If FieldTap wants
NR SIBs and can afford an X310, this is the one to try, plus ~200 lines of
exported-PDU writer.

### 3.4 5GSniffer (SPRITE Lab) — PDCCH only, do not expect SIB1

- IEEE S&P 2023 ("From 5G Sniffing to Harvesting Leakages of Privacy-Preserving
  Messengers"). C++, uses srsRAN libs for polar decoding.
- Decodes **MIB from SSB and blind-decodes PDCCH DCI**. It does **not** decode SIB1
  content (it uses CORESET#0 knowledge as an input, not an output).
- **FDD only.** Requires substantial priors: CORESET config, RNTI ranges,
  `pdcch-DMRS-ScramblingID`, frequency alignment — several of which the real network
  hands the UE inside an *encrypted* message.
- README recommends running from **recorded files**, not live SDR, for the current
  release. USRP B210/X310/bladeRF.
- No pcap. Fork maintained at `oran-testing/5g-sniffer`.

**Maturity: proof-of-concept.** Not a path to SIB1.

### 3.5 Sni5Gect (ASSET Group, NTU) — the only NR tool that reaches Wireshark

This is the most product-relevant NR find in this whole review.

- **USENIX Security 2025** (34th), artifacts badged Available + Functional +
  Reproduced. AGPL-3.0. **v4.0 released April 2026** — actively developed, not
  abandoned research code.
- Decodes the whole stack: **SSB/PBCH → PDCCH/DCI → PDSCH/PUSCH → MAC-NR → RRC
  (Setup, Reconfiguration, Release) → NAS-5GS** (Registration Request/Accept,
  Authentication Request, Identity Request/Response).
- **Writes pcap and is explicitly Wireshark-integrated** via the wDissector
  framework; `pcap_folder: logs/` in the YAML config.
- Hardware: **USRP B210 or X310**. Host: ≥12-core CPU, 16 GB RAM (their rig is an
  AMD 5950X / 32 GB). Docker image provided.
- Envelope: bands **n78, n41 (TDD), n3 (FDD)**; tested at 3427.5, 2550.15,
  1865.0 MHz; SCS 15 and 30 kHz; **20–50 MHz**; **SISO only**; range **0–20 m** with
  an amplifier. FDD needs a dual-channel SDR and a GPSDO is recommended.
- Offline replay from a published Zenodo recording, which makes evaluation cheap —
  you can validate the Wireshark chain before buying a radio.

```bash
docker compose build sni5gect && docker compose up -d sni5gect
docker exec -it sni5gect bash
./build/shadower/shadower configs/srsran-n78-20MHz-b210.yaml
```

Caveat: it is a *sniffing and injection* framework. The downlink-injection half is
an attack tool. For a test-equipment company, use the sniffer path only, and be
deliberate about that boundary.

### 3.6 Commercial

- **Keysight SJ001A WaveJudge** — the former **Sanjole** WaveJudge, acquired by
  Keysight in Feb 2021 and still shipping. Over-the-air capture plus real-time
  protocol decode and PHY analysis for 5G NR (through Rel-18/NTN/RedCap), LTE and
  Wi-Fi. This is the reference answer for guaranteed cross-band SIB/RRC decode.
- **R&S TSME6/TSMA6** and **PCTEL IBflex/Gflex** scanning receivers are what the
  drive-test industry actually uses; they decode MIB/SIB natively and are the
  competitive frame FieldTap sits in.

**Honest verdict on NR:** SSB + MIB from an SDR is routine. SIB1 works today only in
NR-Scope (X310, no pcap) and Sni5Gect (B210/X310, pcap, narrow tested band set).
There is no maintained, general-purpose, any-band open NR SIB1-to-pcap tool.

---

## 4. Hardware: what each radio can and cannot do

### 4.1 Sample-rate and bandwidth arithmetic (the thing that decides everything)

| Signal | Occupied BW | Minimum practical sample rate |
|---|---|---|
| LTE central 6 RB (PSS/SSS/PBCH → **MIB**) | 1.08 MHz | **1.92 MS/s** |
| LTE 1.4 / 3 / 5 MHz | 1.4 / 3 / 5 MHz | 1.92 / 3.84 / 7.68 MS/s |
| **LTE 10 MHz** (PDSCH → SIB1) | 9 MHz | **15.36 MS/s** |
| LTE 15 MHz | 13.5 MHz | 23.04 MS/s |
| **LTE 20 MHz** (PDSCH → SIB1) | 18 MHz | **30.72 MS/s** |
| NR **SSB** @ 15 kHz SCS (20 RB) | 3.6 MHz | ~7.68 MS/s |
| NR **SSB** @ 30 kHz SCS (20 RB) | 7.2 MHz | ~11.52–15.36 MS/s |
| NR **SIB1** via CORESET#0 24/48/96 RB @ 30 kHz | 8.6 / 17.3 / **34.6 MHz** | up to **46–61.44 MS/s** |

Clock: RTL-SDR Blog v3/v4 ship a 1 ppm TCXO — at 2.6 GHz that is a ~2.6 kHz offset,
which srsRAN's CFO tracking absorbs fine for MIB. For TDD n78 at 3.5 GHz and for
anything touching uplink timing, use a GPSDO or a 10 MHz reference.

### 4.2 The radios

| Radio | Price (2026) | Range | Max rate / BW | ADC | Duplex | Verdict |
|---|---|---|---|---|---|---|
| **RTL-SDR Blog V4** | **$29.95** dongle, **$39.95** w/ antenna kit | ~500 kHz – 1.766 GHz | ~2.4 MS/s reliable (3.2 lossy) | 8-bit | RX only | **LTE MIB / PCI only.** Cannot reach 15.36 MS/s so no SIB1. Cannot span an NR SSB at any SCS. 1.77 GHz ceiling excludes n78, n41, B7. Buy one anyway — it is the $30 way to prove the toolchain. |
| **HackRF One** | ~$320 / £229 — **discontinued**, superseded by Pro | 1 MHz – 6 GHz | 20 MS/s | 8-bit | half-duplex | Enough for LTE ≤10 MHz SIB attempts (LTE-Cell-Scanner's own TODO points at HackRF for SIB). Half-duplex means **srsue cannot attach**. 8 bits hurts next to a strong adjacent carrier. |
| **HackRF Pro** | current GSG product | 100 kHz – 6 GHz (tunes 0–7.1 GHz) | 20 MS/s @ 8-bit, 40 MS/s @ 4-bit, 16-bit at low rates | 8/16/4-bit | half-duplex | Better clock (built-in TCXO), no DC spike, USB-C. Still half-duplex and still 8-bit — same structural limits as One. |
| **USRP B200 / B200mini / B205mini-i** | ~$1.4k–1.8k class | 70 MHz – 6 GHz | 56 MHz BW, 61.44 MS/s | 12-bit | full | 1×RX. Fine for LTE downlink to 20 MHz and for srsue. Not enough RX chains for LTESniffer. |
| **USRP B210** | **$2,387** (Ettus list); GPSDO **$1,502** | 70 MHz – 6 GHz | 56 MHz BW, 61.44 MS/s | 12-bit AD9361 | full | **The reference platform.** srsRAN, FALCON, LTESniffer and Sni5Gect all target it. 2×RX satisfies LTESniffer's downlink requirement. Sustained >40 MS/s over USB3 will overflow on a weak host. |
| **bladeRF 2.0 micro xA4** | **$540** | 47 MHz – 6 GHz | 61.44 MS/s (122.88 with recent gateware), 56 MHz | 12-bit AD9361 | full | **Best value.** Same AD9361 as B210, 2×2, VCTCXO with DAC trim + 10 MHz ref input. Supported by srsRAN, OWL, 5GSniffer. |
| bladeRF 2.0 micro xA9 | ~$1.2k | same | same, bigger FPGA | 12-bit | full | Only if you want FPGA-side processing. |
| **LimeSDR Mini 2.0** | **$399** | 10 MHz – 3.5 GHz | up to 30.72 MS/s | 12-bit LMS7002M | full | 1×RX. FALCON is tested on it. The **3.5 GHz ceiling clips n78** (3.3–3.8 GHz) — a real problem for FieldTap's n78 work. |
| **AntSDR E200** | ~$400–500 street | 70 MHz – 6 GHz | AD9361-class | 12-bit | full | Zynq + AD9361 over GbE, marketed as a B210-compatible drop-in with a switchable B210 mode, documented running srsRAN. The sensible budget B210 substitute. |
| USRP X310 (+ CBX / TwinRX) | $8k+ | DC – 6 GHz | 160 MHz BW over 10 GbE | 14-bit | full | Required by NR-Scope and by LTESniffer's uplink mode. |

**Buying advice, ranked:** (1) B210 if budget allows — everything targets it;
(2) bladeRF 2.0 micro xA4 at $540 as the value pick; (3) AntSDR E200 as the budget
B210 clone; (4) an RTL-SDR v4 regardless, as a $30 smoke test.

---

## 5. What an SDR fundamentally cannot do

### 5.1 Uplink
Receiving the UE's PUSCH requires being physically close to the handset — LTESniffer
states plainly that uplink range is severely limited by UE transmit power — and
requires either a USRP X310 on 10 GbE or **two** B-series radios GPSDO-locked, plus
you must already be decoding downlink DCI to know where the uplink grants are.
For NR, Sni5Gect's uplink half needs a dual-channel SDR and works at 15 kHz SCS only.
Realistically: uplink is a bench capability, not a drive-test capability.

### 5.2 Everything after AS security activation
Once `SecurityModeCommand` completes, SRB1/SRB2 RRC PDUs and NAS are ciphered and
integrity-protected with keys derived from K_ASME (LTE) / K_AUSF (5G), which come
from the USIM's K/OPc. Without those you cannot decrypt. That means **measurement
reports, handover commands, real RRCReconfiguration content, and post-security NAS
are all opaque**. LTESniffer says it outright: it cannot decrypt.

This is the single biggest gap versus a diag-based tool. QCSuper/SCAT read the
modem's *internal* view and therefore see plaintext on both sides of ciphering; an
SDR sees only what is on the air.

### 5.3 Dedicated signalling for a UE you don't control
You learn a UE's C-RNTI from the RAR, so you must have been listening at the moment
it attached. Blind PDCCH decoding (FALCON, 5GSniffer) recovers RNTIs and allocation
sizes but not identity, and in 5G the SUCI mechanism conceals the IMSI by default —
the LTE identity leakage LTESniffer exploits is largely closed in NR.

### 5.4 What you *do* reliably get
This is not a small list, and for drive test it is most of the value:
PSS/SSS → PCI; **MIB** (bandwidth, PHICH, SFN, Tx ports); **SIB1** (PLMN list, TAC,
cellIdentity, cellBarred, freqBandIndicator, TDD config, SI scheduling);
**SIB2** (RACH, PRACH, uplink power control, common radio config);
**SIB3/4/5** (intra/inter-frequency reselection thresholds);
**SIB6/7/8** (UTRA/GERAN/CDMA2000 reselection); **SIB10/11/12** (ETWS/CMAS);
paging on P-RNTI; RACH preambles and RARs. Plus, on your *own* test network with a
test SIM, the complete unencrypted RRC connection establishment and NAS attach up
to SecurityModeCommand, and the whole thing decrypted if you feed Wireshark the keys.

### 5.5 Legal position — read this before shipping anything

Not legal advice. Get counsel. But the shape of it:

- **US — Wiretap Act.** 18 U.S.C. § 2511(2)(g)(i) permits intercepting an electronic
  communication that is "readily accessible to the general public." § 2510(16)
  defines that term to **exclude** any radio communication "transmitted over a
  communication system provided by a common carrier." Cellular is exactly that.
  **So the public-accessibility safe harbour does not cover cellular.**
  47 U.S.C. § 605 separately restricts divulging or using intercepted radio.
- **US — FCC scanner rules.** § 302(d) of the Communications Act and **47 CFR 15.121**
  bar the FCC from authorizing, and bar manufacture/import of, *scanning receivers*
  capable of receiving cellular-allocated frequencies or readily alterable to do so
  (effective for equipment after 26 Apr 1994). General-purpose SDR development boards
  have not historically been certified as scanners, but a **product** built on one and
  marketed as a cellular receiver sits squarely in that rule's line of fire. This is a
  real go-to-market question for FieldTap, not a theoretical one.
- **UK** — s.48 Wireless Telegraphy Act 2006 makes unauthorised interception of
  wireless telegraphy an offence. **EU** — Art. 5 of Directive 2002/58/EC (ePrivacy).
- **The counter-argument, honestly stated:** MIB and SIBs are unencrypted network
  configuration broadcast to the world, not the *contents* of any subscriber's
  communication. That is precisely why R&S, PCTEL and Keysight can legally sell
  scanners that decode them. The exposure rises sharply the moment you decode
  dedicated channels (PDCCH DCI, C-RNTI PDSCH, NAS) belonging to third-party UEs.

**Practical engineering posture for FieldTap:**
1. Do development **conducted or in a shielded enclosure** against your own eNB/gNB
   (you already have the Callbox and the Simnovator box — use them).
2. On live networks, restrict the shipping product to **broadcast channels only**
   (MIB/SIB/paging) and document that boundary in the product.
3. Get written authorization from any operator whose network you drive-test against.
4. Have counsel review the 15.121 equipment-authorization question before any
   SDR-based SKU ships in the US.

---

## 6. Getting-started recipe

### Recipe A — $30, 30 minutes: prove the toolchain, LTE MIB only

```bash
sudo apt install -y cmake libfftw3-dev libmbedtls-dev libboost-program-options-dev \
    libconfig++-dev libsctp-dev libsoapysdr-dev soapysdr-module-rtlsdr rtl-sdr
git clone https://github.com/srsran/srsRAN_4G.git
cd srsRAN_4G && mkdir build && cd build && cmake ../ && make -j$(nproc)

# Confirm the dongle is visible through Soapy
SoapySDRUtil --find

# Scan band 3 (1805-1880 MHz DL) for cells; prints PCI, PRB count, Tx ports, MIB
./lib/examples/cell_search -b 3 -g 50
# Narrow it: ./lib/examples/cell_search -b 3 -s 1400 -e 1500 -g 50
```

You get PCI / EARFCN / bandwidth / SFN. You do **not** get SIB1 — RTL-SDR cannot
reach 15.36 MS/s. Stop here and buy a real radio.

### Recipe B — the real one: LTE MIB + SIB1 + SIB2+ into Wireshark

Hardware: USRP B210 ($2,387) or bladeRF 2.0 micro xA4 ($540) or AntSDR E200 (~$450).

```bash
# deps + UHD
sudo apt install -y cmake libfftw3-dev libmbedtls-dev libboost-program-options-dev \
    libconfig++-dev libsctp-dev libuhd-dev uhd-host
sudo uhd_images_downloader

git clone https://github.com/srsran/srsRAN_4G.git
cd srsRAN_4G && mkdir build && cd build && cmake ../ && make -j$(nproc)
sudo make install
sudo ./srsran_install_configs.sh user      # writes ~/.config/srsran_4g/

# 1. Find cells
./lib/examples/cell_search -b 3 -g 40

# 2. Read system information directly (default RNTI is SI-RNTI 0xFFFF)
./lib/examples/pdsch_ue -f 1842.5e6 -g 40 -A 2
#    -i file.bin replays a capture; -n limits subframes
```

Then edit `~/.config/srsran_4g/ue.conf`:

```ini
[rf]
dl_earfcn   = 1650          ; the EARFCN cell_search found
device_name = uhd
device_args = type=b200,master_clock_rate=23.04e6
rx_gain     = 40

[pcap]
enable       = mac,nas
mac_filename = /tmp/ue_mac.pcap
nas_filename = /tmp/ue_nas.pcap

[log]
all_level = info
rrc_level = debug          ; ASN.1 dumps of MIB/SIB in the text log too
```

```bash
sudo srsue ~/.config/srsran_4g/ue.conf
# It will find the cell, decode MIB -> SIB1 -> SIB2, attempt PRACH, and fail to
# attach (no valid SIM for that operator). That is fine. Ctrl-C.
```

Then in Wireshark, once:
- Preferences → Protocols → DLT_USER → Encapsulations Table → Edit →
  add `User 2 (DLT=149)` → payload protocol `udp`; add `User 1 (DLT=148)` → `nas-eps`
- Analyze → Enabled Protocols → MAC-LTE → tick **`mac_lte_udp`**
- Preferences → Protocols → MAC-LTE → tick "Attempt to decode BCH, PCH and CCCH data
  using LTE RRC dissector" and "Attempt to dissect LCID 1&2 as SRB 1&2"

```bash
wireshark /tmp/ue_mac.pcap
# or headless:
tshark -r /tmp/ue_mac.pcap -V -Y 'lte_rrc.BCCH_DL_SCH_Message'
tshark -r /tmp/ue_mac.pcap -Y 'lte_rrc.BCCH_BCH_Message'   # the MIB
```

You will see `MAC-LTE / LTE-RRC` frames dissected as **BCCH-BCH-Message (MIB)** and
**BCCH-DL-SCH-Message (SIB1, SystemInformation carrying SIB2/3/4/…)**, fully
expanded ASN.1. Remember §2.7: they appear once, near the start of the file.

### Recipe C — 5G NR

Cheapest credible path, offline first:

```bash
git clone https://github.com/asset-group/Sni5Gect-5GNR-sniffing-and-exploitation
cd Sni5Gect-5GNR-sniffing-and-exploitation
# Download their Zenodo example recording, point the config's source at it:
docker compose build sni5gect && docker compose up -d sni5gect
docker exec -it sni5gect bash
./build/shadower/shadower configs/srsran-n78-20MHz-b210.yaml
# pcaps land in logs/ ; open them in Wireshark
```

That validates the NR decode chain and the Wireshark output with **zero hardware
spend**. If it looks right, add a B210 and run it live on n78 (3427.5 MHz is their
tested frequency) or n3.

Parallel path worth costing: **patch srsue's NR MAC to write BCH and SI-RNTI PDUs
to pcap** (uncomment the `write_dl_bch` line in `srsue/src/stack/mac_nr/mac_nr.cc`
and add a `write_dl_si_rnti_nr()` call where SIB1 is delivered). srsue already
decodes NR MIB and SIB1 correctly — only the pcap plumbing is missing. That is
probably a day of work and gives FieldTap NR broadcast in the same Wireshark
pipeline as LTE, on a B210, with no research code in the product.

---

## 7. Recommendation for FieldTap

1. **Buy a bladeRF 2.0 micro xA4 ($540) or an AntSDR E200 (~$450) now.** Add a B210
   later if you need a second RX chain or LTESniffer.
2. **LTE broadcast is solved today**: `srsue` + `[pcap] enable = mac,nas` gives you
   MIB, SIB1, SIB2+, paging and (on your own network) full RRC/NAS in a pcap that
   stock Wireshark dissects. This is genuinely a viable substitute for the missing
   diag path on the OnePlus 10 Pro *for broadcast*.
3. **NR broadcast is a small patch away.** srsue decodes NR MIB/SIB1 and simply does
   not write them to pcap. Fixing that upstream is the highest-leverage work item
   in this whole note.
4. **Do not expect an SDR to replace diag for dedicated signalling.** Post-security
   RRC and NAS on a live commercial network are unreachable. If FieldTap needs that,
   the answer is a different handset with an accessible diag interface (or SCAT
   against a Samsung/Exynos or Intel modem), not a radio.
5. **Treat the legal/equipment-authorization question as a launch blocker**, in the
   same tier as the VYAARO OTP hole — not as paperwork to do afterwards.

---

## Citations

**srsRAN source and docs**
- srsRAN_4G repo (not archived, last push Jan 2026, changelog 25.10) — https://github.com/srsran/srsRAN_4G
- `lib/include/srsran/common/pcap.h` (DLT constants) — https://github.com/srsran/srsRAN_4G/blob/master/lib/include/srsran/common/pcap.h
- `lib/src/common/pcap.c` (MAC vs MAC-UDP writers) — https://github.com/srsran/srsRAN_4G/blob/master/lib/src/common/pcap.c
- `lib/src/common/nas_pcap.cc` — https://github.com/srsran/srsRAN_4G/blob/master/lib/src/common/nas_pcap.cc
- `lib/include/srsran/common/mac_pcap_base.h` (write_dl_bch / write_dl_sirnti / NR variants) — https://github.com/srsran/srsRAN_4G/blob/master/lib/include/srsran/common/mac_pcap_base.h
- `srsue/src/stack/mac/mac.cc` (`bch_decoded_ok` → `write_dl_bch`) — https://github.com/srsran/srsRAN_4G/blob/master/srsue/src/stack/mac/mac.cc
- `srsue/src/stack/mac/dl_harq.cc` (`is_bcch` → `write_dl_sirnti`) — https://github.com/srsran/srsRAN_4G/blob/master/srsue/src/stack/mac/dl_harq.cc
- `srsue/src/stack/mac_nr/mac_nr.cc` (NR BCH pcap commented out) — https://github.com/srsran/srsRAN_4G/blob/master/srsue/src/stack/mac_nr/mac_nr.cc
- `srsue/ue.conf.example` ([pcap] section) — https://github.com/srsran/srsRAN_4G/blob/master/srsue/ue.conf.example
- `lib/examples/cell_search.c` — https://github.com/srsran/srsRAN_4G/blob/master/lib/examples/cell_search.c
- `lib/examples/pdsch_ue.c` — https://github.com/srsran/srsRAN_4G/blob/master/lib/examples/pdsch_ue.c
- srsRAN 4G Troubleshooting / "Examining PCAPs with Wireshark" — https://docs.srsran.com/projects/4g/en/latest/general/source/4_troubleshooting.html
- srsRAN 4G srsUE Getting Started — https://docs.srsran.com/projects/4g/en/latest/usermanuals/source/srsue/source/2_ue_getstarted.html
- srsRAN 4G 5G SA srsUE app note — https://docs.srsran.com/projects/4g/en/latest/app_notes/source/5g_sa_amari/source/
- srsRAN Project Outputs (DLT table for MAC/RLC/NGAP/N3/E1AP/F1AP/E2AP) — https://docs.srsran.com/projects/project/en/latest/user_manuals/source/outputs.html
- srsRAN Project Configuration Reference (pcap keys) — https://docs.srsran.com/projects/project/en/latest/user_manuals/source/config_ref.html
- srsRAN issue #1453 "srsRAN 4G with RTL-SDR" — https://github.com/srsran/srsRAN_4G/issues/1453
- srsRAN issue #362 "Can I use srsUE to connect to a commercial network?" — https://github.com/srsran/srsRAN_4G/issues/362

**Wireshark**
- MAC-LTE wiki (framed vs UDP framing, BCH/PCH/CCCH RRC preference) — https://wiki.wireshark.org/MAC-LTE
- `epan/dissectors/packet-mac-lte.c` (rntiType enum, lte_rrc_bcch_* handles) — https://github.com/wireshark/wireshark/blob/master/epan/dissectors/packet-mac-lte.c
- `epan/exported_pdu.h` / exported PDU TLV format — https://github.com/wireshark/wireshark/blob/master/epan/exported_pdu.h
- Protocols/exported_pdu wiki — https://wiki.wireshark.org/Protocols/exported_pdu
- tcpdump link-layer header types (147–162 = USER0–15; 252 = WIRESHARK_UPPER_PDU) — https://www.tcpdump.org/linktypes.html
- nr-rrc.cnf (@bcch.bch, @bcch.dl.sch) — https://github.com/wireshark/wireshark/blob/master/epan/dissectors/asn1/nr-rrc/nr-rrc.cnf

**LTE tools**
- LTE-Cell-Scanner — https://github.com/JiaoXianjun/LTE-Cell-Scanner
- LTE-Cell-Scanner TODO ("rtl-sdr's BW is not enough for decoding SIB") — https://github.com/JiaoXianjun/LTE-Cell-Scanner/blob/master/TODO
- FALCON — https://github.com/falkenber9/falcon
- FALCON paper (arXiv 1907.10110) — https://arxiv.org/pdf/1907.10110
- imdeaOWL — https://git.networks.imdea.org/nicola_bui/imdeaowl
- LTESniffer — https://github.com/SysSec-KAIST/LTESniffer
- openLTE — https://openlte.sourceforge.net/ and https://github.com/osh/openlte
- Daniel Estévez, "Analysing the srsRAN LTE MAC layer with Wireshark" (2024) — https://destevez.net/2024/05/analysing-the-srsran-lte-mac-layer-with-wireshark/
- Nick vs Networking, "Viewing the SIB — The LTE System Information Block with SDRs" — https://nickvsnetworking.com/viewing-the-sib-the-lte-system-information-block-with-sdrs/
- MATLAB LTE Toolbox, Cell Search / MIB / SIB1 Recovery — https://www.mathworks.com/help/lte/ug/cell-search-mib-and-sib1-recovery.html

**5G NR tools**
- NR-Scope — https://github.com/PrincetonUniversity/NR-Scope
- NR-Scope paper (CoNEXT 2024) — https://wanhaoran.github.io/assets/papers/conext24_short_ngscope5g_camera_ready.pdf
- 5GSniffer — https://github.com/spritelab/5GSniffer (fork: https://github.com/oran-testing/5g-sniffer)
- 5GSniffer paper (IEEE S&P 2023) — https://ieeexplore.ieee.org/document/10179353/
- Sni5Gect — https://github.com/asset-group/Sni5Gect-5GNR-sniffing-and-exploitation
- Sni5Gect project site (USENIX Security 2025) — https://asset-group.github.io/Sni5Gect-5GNR-sniffing-and-exploitation/
- MATLAB NR Cell Search and MIB/SIB1 Recovery — https://www.mathworks.com/help/5g/ug/nr-cell-search-and-mib-and-sib1-recovery.html

**Hardware**
- RTL-SDR Blog V4 release/pricing — https://www.rtl-sdr.com/rtl-sdr-blog-v4-dongle-initial-release/
- RTL-SDR dongle store page — https://www.rtl-sdr.com/buy-rtl-sdr-dvb-t-dongles/
- HackRF product family — https://greatscottgadgets.com/hackrf/ ; HackRF Pro — https://greatscottgadgets.com/hackrf/pro/
- USRP B210 ($2,387; GPSDO $1,502) — https://www.ettus.com/all-products/ub210-kit/
- Ettus B200/B210/B200mini/B205mini KB — https://kb.ettus.com/B200/B210/B200mini/B205mini
- bladeRF 2.0 micro xA4 ($540) — https://www.nuand.com/product/bladerf-xa4/ ; xA9 — https://www.nuand.com/product/bladerf-xa9/
- LimeSDR Mini 2.0 ($399) — https://limemicro.com/sdr/limesdr-mini-2-0/ and https://www.rtl-sdr.com/limesdr-2-0-mini-now-crowdfunding-standard-limesdr-discontinued/
- AntSDR E200 — https://www.crowdsupply.com/microphase-technology/antsdr-e200 ; "Using AntSDR for Cellular Networking" — https://www.crowdsupply.com/microphase-technology/antsdr-e200/updates/using-antsdr-for-cellular-networking
- Ettus KB, 5G srsRAN End-to-End Reference Architecture with USRP — https://kb.ettus.com/5G_srsRAN_End-to-End_Reference_Architecture_with_USRP

**Commercial / legal**
- Keysight SJ001A WaveJudge — https://www.keysight.com/us/en/product/SJ001A/wavejudge-wireless-analyzer-toolset.html
- Keysight acquires Sanjole (Feb 2021) — https://www.rcrwireless.com/20210222/test-and-measurement/keysight-acquires-sanjole-for-ota-network-testing
- 18 U.S.C. § 2510 definitions (incl. (16) "readily accessible to the general public") — https://www.law.cornell.edu/uscode/text/18/2510
- 18 U.S.C. § 2511 — https://www.law.cornell.edu/uscode/text/18/2511
- 47 CFR Part 15 / § 15.121 scanning receiver restrictions — https://en.wikipedia.org/wiki/Title_47_CFR_Part_15
- Interception and Divulgence of Radio Communications (47 U.S.C. § 605 overview) — https://corporate.findlaw.com/litigation-disputes/interception-and-divulgence-of-radio-communications.html
