# Qualcomm diag LOG record layouts for continuous radio measurements

Research pass for the clean-room fieldTap decoder. Goal: decode continuous serving-cell
RSRP/RSRQ/SINR and PHY-layer throughput without depending on RRC `MeasurementReport`
messages, using publicly documented layouts of Qualcomm diag `LOG` records (the `0xBxxx`
codes carried in DIAG `Log Config`/`Log On Demand` responses, distinct from the `0x71xx`
NAS and `0xB0C0`/`0xB821` OTA message codes the team already decodes).

## Licensing methodology (read first)

This is a clean-room effort. The decoder must not contain code copied from GPL projects.
The rule applied throughout this document:

- **Apache-2.0 / permissive sources** (MobileInsight) are cited directly; field tables
  below are still written in my own words (names/offsets/widths/scaling are facts, not
  copyrightable expression), but no source is verbatim-quoted at length.
- **GPL sources** (SCAT = GPL-2.0, QCSuper = GPL-3.0) were read for their factual content
  only (struct layouts, field names, scaling constants). Every table sourced from a GPL
  project is marked **"Source: GPL, read-only"** and carries this notice: *these numbers
  came from reading a GPL-licensed parser; a human must confirm them against a real
  hardware capture (QCAT/QXDM decode of a `.dlf`/`.isf` log, or a byte-level diff against
  known-good samples) before they are trusted in the clean-room implementation. Do not
  copy the GPL project's source code, comments, or variable-naming structure — only the
  wire-format facts were extracted.*
- Where a field layout is corroborated by **two independently-licensed sources** (e.g.
  Apache MobileInsight and GPL SCAT agree byte-for-byte), that is called out explicitly —
  it is the strongest evidence available short of a hardware capture, because it means
  independent teams reverse-engineered the same modem firmware and got the same answer.

### Sources used

| Source | License | Repo / URL | Used for |
|---|---|---|---|
| MobileInsight (`mobileinsight-core`) | **Apache License 2.0** (UCLA WiNG / Purdue Peng Group) | https://github.com/mobile-insight/mobileinsight-core | Primary field-table source, `dm_collector_c/log_packet.h`, `log_packet.cpp`, `log_packet_helper.h`, and per-message headers (`lte_pdsch_stat_indication.h`, `lte_phy_pusch_tx_report.h`, `nr_ml1_search_meas_database_update.h`, `nr_ml1_serving_cell_beam_mngt.h`, `nr_mac_pdsch_stats.h`, `nr_mac_ul_physical_channel_schedule_report.h`, `nr_mac_ul_tb_stats.h`, `nr_l2_ul_tb.h`, `nr_nas_mm5g_state.h`) |
| SCAT (`fgsect/scat`) | **GPL-2.0** | https://github.com/fgsect/scat | Log-code enumeration (`diagcmd.py`), and read-only field-layout cross-check (`diagltelogparser.py`, `diagnrlogparser.py`) — GPL, disclaimer applies |
| QCSuper | **GPL-3.0** | https://github.com/P1sec/QCSuper | Log-code name/number corroboration only (`log_types.py`); already vendored (and licence-isolated) elsewhere in this repo per project memory — GPL, disclaimer applies. QCSuper's own log-type list does **not** cover the PHY/ML1/MAC measurement codes researched here, only RRC/NAS OTA codes already decoded by fieldTap. |
| LTE Guide (lteguide.blogspot.com) | Personal blog, no explicit licence — treated as informal public documentation, cited for factual/observed values only | http://lteguide.blogspot.com/2012/01/ml1-serving-cell-measurement-response.html | Real QXDM-decoded sample of 0xB193 (field names + example values), corroborates scaling |
| Techplayon | Blog, informal public documentation | https://www.techplayon.com/ | QXDM log-mask conventions, general RSRP/RSRQ/SINR mapping description |
| HiCellTek | Blog, informal public documentation | https://hicelltek.com/ | 3GPP RSRP/RSRQ/SINR range-to-dBm mapping tables (restates TS 36.133 / TS 38.133) |
| 3GPP TS 36.133, TS 38.133 | 3GPP specification (freely published) | 3gpp.org | Normative RSRP/RSRQ/SINR unit definitions (the *RRC-reported* quantized scale, distinct from the finer-grained internal diag register scale) |

No code was copied from any GPL source into this document or into the fieldTap
repository. Every table below is a restatement, in this document's own words, of the
*facts* (byte offsets, widths, field names, scaling constants) found by reading these
projects.

## IMPORTANT — log-code number mismatches found during research

The task brief's log codes were checked one-by-one against the two independent decoder
projects. **Most matched exactly.** A few did not, and those mismatches matter more than
anything else in this document if the goal is "pick the right code to enable on real
hardware":

| Brief said | Brief's name | What was actually found | Verdict |
|---|---|---|---|
| 0xB193 | LTE ML1 Serving Cell Measurement Result | Confirmed exactly (`LOG_LTE_ML1_SERVING_CELL_MEAS_RESPONSE` / `LTE_PHY_Serving_Cell_Measurement_Result`) | Match |
| 0xB17F | LTE ML1 Serving Cell Meas and Eval | Confirmed exactly (`LOG_LTE_ML1_SERVING_CELL_MEAS_AND_EVAL`) — SCAT only | Match |
| 0xB179 | LTE ML1 Connected Mode Intra-Freq Meas Results | Confirmed exactly in both sources | Match |
| 0xB180 | LTE ML1 idle neighbour meas | Confirmed exactly (`LOG_LTE_ML1_NEIGHBOR_MEASUREMENTS`) — SCAT only. Note: MobileInsight's *separate* `LTE_PHY_Idle_Neighbor_Cell_Meas` is a **different** code, `0xB192` (request/response, not a periodic measurement dump) — do not conflate the two. | Match, but a same-sounding neighbour code exists at a different address |
| 0xB0C2 | LTE RRC Serving Cell Info | Confirmed exactly in both sources | Match |
| 0xB173 | LTE PDSCH Stat Indication | Confirmed exactly (MobileInsight only) | Match |
| 0xB139 | LTE PUSCH Tx report | Confirmed exactly (MobileInsight only) | Match |
| 0xB063 / 0xB064 | MAC DL/UL transport block | Confirmed exactly in both sources | Match |
| 0xB0C1 | LTE MIB | Confirmed exactly in both sources | Match |
| 0xB97F | NR ML1 Searcher Measurement DB Update Ext | Confirmed exactly in both sources | Match |
| 0xB975 | NR ML1 Serving Cell Beam Management | Confirmed exactly (MobileInsight only; SCAT does not implement this code) | Match |
| 0xB823 | NR RRC Serving Cell Info | Confirmed exactly (SCAT only) | Match |
| 0xB822 | NR RRC MIB Info | Confirmed exactly (SCAT only) | Match |
| 0xB825 | NR RRC Configuration Info | Code+name confirmed (SCAT enum), **but no parser/layout exists in either source** — SCAT's handler for this code is a stub (`pass`) | Name matches, layout unknown |
| **0xB887** | NR MAC PDSCH Info | **Not found under this number in any source.** The message with this *name/purpose* (PDSCH decode stats, BLER) is `NR_MAC_PDSCH_Stats = 0xB888` in MobileInsight — one hex digit different. | **Mismatch — verify on hardware** |
| **0xB88A** | NR MAC UL Physical Channel Schedule Report | **0xB88A is a different message** — SCAT's enum has `0x88A = LOG_5GNR_MAC_RACH_ATTEMPT` ("NR MAC RACH Attempt"), unimplemented, no layout available. The message with the *name* "NR MAC UL Physical Channel Schedule Report" is `0xB883` in MobileInsight, fully documented there. | **Mismatch — verify on hardware** |
| **0xB8D8** | NR L2 UL TB | **Not found under this number in any source.** The message with this name/purpose is `NR_L2_UL_TB = 0xB872` in MobileInsight (SCAT does not implement it at all). | **Mismatch — verify on hardware** |
| **0xB80E** | MM5G state | **Not found under this number in any source.** Both MobileInsight and SCAT agree the 5GMM state message is `0xB80C` (`NR_NAS_MM5G_State` / `LOG_5GNR_NAS_5GMM_STATE`), fully documented and cross-validated. | **Mismatch — verify on hardware** |

**Working theory:** log-code numbering for the newer NR MAC/L2/NAS-state family is known
to shift across Qualcomm chipset/baseband generations (this is visible even between the
two decoder projects' own version histories). The four mismatches above are most likely
either (a) a newer/different baseband's renumbering that neither open decoder has seen
yet, or (b) a transcription mix-up somewhere upstream of the task brief. Either way: **do
not implement a byte-offset parser for 0xB887, 0xB88A, 0xB8D8 or 0xB80E from this
document.** Enable the *neighbouring* confirmed codes (0xB888, 0xB883, 0xB872, 0xB80C)
during a hardware capture and see which log ID actually appears in the `.dlf`/`.isf`; that
observation is authoritative over anything below.

---

# LTE records

## 0xB0C1 — LTE RRC MIB Message Log Packet

Carries the decoded MIB (bandwidth, SFN, PCI is implicit from context/companion fields,
TX antenna count). Not a measurement record, but useful as a low-rate anchor for
EARFCN/PCI/SFN/bandwidth alongside the ML1 measurement records below.

**Versions found:** 1, 2, 3, 17 (all four cross-validated between MobileInsight and SCAT
except v3, which is MobileInsight-only and appears to be a minor NB-IoT variant of v2).

### Version 1 (outer `Version` byte = 1)

| Offset | Width | Field | Units / scaling |
|---|---|---|---|
| 0 | 1 | Version | = 1 |
| 1 | 2 | Physical Cell ID | raw PCI |
| 3 | 2 | EARFCN | raw |
| 5 | 2 | SFN | raw (0–1023) |
| 7 | 1 | Number of TX antennas | raw |
| 8 | 1 | DL bandwidth | raw PRB count; both sources map it to MHz as `PRB / 5` for {25,50,75,100} PRB (5/10/15/20 MHz) — note the 6-PRB (1.4 MHz) case does not fit this simple division exactly, use the PRB→MHz lookup {6:1.4, 15:3, 25:5, 50:10, 75:15, 100:20} instead of the raw `/5` shortcut |

### Version 2 (EARFCN widened to 4 bytes for higher band numbers)

| Offset | Width | Field | Units / scaling |
|---|---|---|---|
| 1 | 2 | Physical Cell ID | raw |
| 3 | 4 | EARFCN | raw (32-bit to cover Band 65+/CA numbering) |
| 7 | 2 | SFN | raw |
| 9 | 1 | Number of TX antennas | raw |
| 10 | 1 | DL bandwidth | same PRB table as v1 |

### Version 17 (adds SIB1 scheduling / access-barring / NB-IoT-adjacent fields)

| Offset | Width | Field | Units / scaling |
|---|---|---|---|
| 1 | 2 | Physical Cell ID | raw |
| 3 | 4 | EARFCN | raw |
| 7 | 2 | SFN | raw, 10 significant bits |
| 9 | 1 | SFN MSB4 | extends SFN range |
| 10 | 1 | HSFN LSB2 | Hyper-SFN low bits |
| 11 | 1 | SIB1 scheduling info | raw |
| 12 | 1 | System Info value tag | raw |
| 13 | 1 | Access barring enabled | boolean |
| 14 | 1 | Operation mode type | enum: 0=inband-DifferentPCI, 1=inband-SamePCI, 2=guardband, 3=standalone (NB-IoT) |
| 15 | 2 | Raster offset | enum: 0=-7.5kHz, 1=-2.5kHz, 2=+2.5kHz, 3=+7.5kHz |
| 17 | 1 | Number of TX antennas | raw |

**Sources:** MobileInsight `dm_collector_c/log_packet.h` lines ~1381–1438 (Apache-2.0,
cited directly). Cross-validated field order/widths for v1/v2/v17 against SCAT
`diagltelogparser.py::parse_lte_mib` (GPL-2.0, read-only, disclaimer applies) — struct
formats `<HHH BB>`, `<HLH BB>`, `<HLH BBBBB BHB>` for v1/v2/v17 match this table exactly.

**Confidence: HIGH** (cross-validated, two independently-licensed sources agree
byte-for-byte).

---

## 0xB0C2 — LTE RRC Serving Cell Info Log Packet

Low-rate cell-identity anchor (PCI, EARFCN, bandwidth, cell ID, TAC, PLMN, band). Useful
for correlating ML1 measurement records to a specific cell/PLMN over time.

### Version 2

| Offset | Width | Field | Units / scaling |
|---|---|---|---|
| 1 | 2 | Physical Cell ID | raw |
| 3 | 2 | DL EARFCN | raw |
| 5 | 2 | UL EARFCN | raw |
| 7 | 1 | DL bandwidth | PRB→MHz table (as above) |
| 8 | 1 | UL bandwidth | PRB→MHz table |
| 9 | 4 | Cell Identity (28-bit ECI) | raw |
| 13 | 2 | TAC | raw |
| 15 | 4 | Band indicator | raw E-UTRA band number |
| 19 | 2 | MCC | raw (3 BCD digits) |
| 21 | 1 | MNC digit count | 2 or 3 |
| 22 | 2 | MNC | raw |
| 24 | 1 | Allowed access | bitmask, meaning not fully decoded by either source (marked TODO upstream) |

### Version 3 (DL/UL EARFCN widened to 4 bytes each)

Same field order as v2, but DL EARFCN and UL EARFCN are each 4 bytes instead of 2
(everything after shifts by +4 bytes total).

**Sources:** MobileInsight `log_packet.h` lines ~1344–1376 (Apache-2.0). Cross-validated
against SCAT `diagltelogparser.py::parse_lte_rrc_cell_info` (GPL-2.0, read-only,
disclaimer applies) — struct formats `<H HH BB LH L HBH B>` (v2) and
`<H LL BB LH L HBH B>` (v3) match this table exactly, field-for-field.

**Confidence: HIGH** (cross-validated).

---

## 0xB063 / 0xB064 — LTE MAC DL / UL Transport Block

The most direct source of **per-TTI throughput**: sum `DL TBS` (0xB063) or `Grant` bytes
(0xB064) over a window and divide by time for a running Mbps figure, independent of any
RRC message. Both codes share one container shape:

```
Version (1B) | Num Subpackets (1B) | reserved (2B)
  → repeated subpackets:
    Subpacket ID (1B) | Subpacket Version (1B) | Subpacket Size (2B)
      → Num Samples (1B), then repeated fixed-size sample records
```

### 0xB063 DL Transport Block — sample record

| Version | Bytes | Fields (in order) |
|---|---|---|
| Subpkt v2 | 12 | Sub-FN (2B: low 4 bits = subframe 0–9, upper 12 bits = SFN 0–1023), RNTI Type (1B: 0=C-RNTI,2=P-RNTI,3=RA-RNTI,4=Temp-C-RNTI,5=SI-RNTI), HARQ ID (1B), PMCH ID (2B), **DL TBS bytes (2B, this is the transport-block-size-in-bytes field to sum for DL throughput)**, RLC PDUs count (1B), Padding bytes (2B), MAC header length (1B) |
| Subpkt v4 | 14 | Sub ID (1B), Cell ID (1B) prefix, then identical to v2 |

Followed by `header_len` bytes of MAC sub-header (LC-ID + length octets) — needed to
split the MAC PDU into RLC PDUs, not needed for a pure throughput/measurement decoder.

### 0xB064 UL Transport Block — sample record

| Version | Bytes | Fields (in order) |
|---|---|---|
| Subpkt v1 | 12 | HARQ ID (1B), RNTI Type (1B), Sub-FN (2B, same packing as DL), **Grant bytes (2B, UL TB size to sum for UL throughput)**, RLC PDUs (1B), Padding bytes (2B), BSR event (1B: 0=none,1=periodic,2=high-data-arrival), BSR trigger (1B: 0=no-BSR,3=S-BSR,4=Pad-L-BSR), MAC header length (1B) |
| Subpkt v2/v3/v5/v8 | 14 | Sub ID (1B), Cell ID (1B) prefix, then identical to v1 |

**Sources:** MobileInsight `log_packet.h` lines ~1742–1961 (Apache-2.0). Cross-validated
against SCAT `diagltelogparser.py::parse_lte_mac_subpkt_v1_dl_transport_block` /
`..._ul_transport_block` (GPL-2.0, read-only, disclaimer applies) — struct formats
`<HBBHHBHB>` (DL v2), `<BBHBBHHBHB>` (DL v4), `<BBHHBHBBB>` (UL v1),
`<BBBBHHBHBBB>` (UL v2/3/5/8) match this table field-for-field.

**Confidence: HIGH** (cross-validated, and this is the strongest of all candidates for a
throughput number that has zero dependency on RRC state).

---

## 0xB179 — LTE ML1 Connected Mode Intra-Freq Meas Results

Periodic serving + intra-frequency-neighbour RSRP/RSRQ dump while RRC_CONNECTED, emitted
independent of any `MeasurementReport` — this is one of the two strongest candidates for
the task's "continuous serving-cell RSRP/RSRQ without RRC" goal.

Container: `Version (1B) | reserved (3B) | Serving Cell Index (1B, low 3 bits) | reserved (3B)`,
then a version-specific header followed by 0..N neighbour-cell records and 0..M
detected-but-unmeasured-cell records.

### Version 3 header (18 bytes) and version 4 header (20 bytes, adds 2 reserved trailing bytes)

| Field | Width | Units / scaling |
|---|---|---|
| E-ARFCN | 2 (v3) / 4 (v4) | raw |
| Serving Physical Cell ID | 2 | raw |
| Sub-frame Number | 2 | raw |
| **RSRP (dBm)** | 2, signed | **raw × 0.0625 − 180** (filtered/L3 RSRP) |
| RSRQ (dB) | 2, signed | raw × 0.0625 − 30 |
| Number of Neighbor Cells | 1 | raw |
| Number of Detected Cells | 1 | raw |

### Per-neighbour-cell record (v3/v4, 10 bytes)

| Field | Width | Units / scaling |
|---|---|---|
| Physical Cell ID | 2 | raw |
| RSRP (dBm) | 2, signed | raw × 0.0625 − 180 |
| RSRQ (dB) | 2, signed | raw × 0.0625 − 30 |

### Per-detected-cell record (cell found by PSS/SSS search but not yet measured)

v3: Physical Cell ID (4B) + SSS Corr Value (4B) + Reference Time (8B).
v4: Physical Cell ID (2B, 2B pad) + SSS Corr Value (4B) + Reference Time (8B).

**Scaling constants** (`RSRP_dBm = raw*0.0625 - 180`, `RSRQ_dB = raw*0.0625 - 30`) are
implemented as a dedicated format-type handler (`case RSRP:` / `case RSRQ:` in
`log_packet_helper.h`, asserting a signed 16-bit field) shared by every LTE ML1 message
in MobileInsight, and are **numerically identical** to SCAT's independently-written
`parse_rsrp`/`parse_rsrq` helpers (`-180 + rsrp*0.0625`, `-30 + rsrq*0.0625`) used across
its own LTE ML1 parsers. Same constants, two unrelated codebases — strong signal these
are correct.

**Sources:** MobileInsight `log_packet.h` lines ~897–962 (`LtePhyCmlifmrFmt*`, Apache-2.0)
and `log_packet_helper.h` lines ~508–524 (RSRP/RSRQ scaling, Apache-2.0). SCAT confirms
the log-code number and name (`diagcmd.py` line 230, `0x179`) but its handler for this
specific code is commented out / unimplemented, so the byte-level layout above is
**single-sourced to MobileInsight** even though the code identity is cross-validated.

**Confidence: HIGH** for the code number/name and the RSRP/RSRQ scaling constants
(cross-validated); **MEDIUM-HIGH** for the exact byte layout (Apache-licensed source,
internally consistent across 2 versions, but no independent second implementation to
diff against).

---

## 0xB17F — LTE ML1 Serving Cell Meas and Eval

Periodic serving-cell measurement **plus cell-reselection evaluation criteria**
(Srxlev, Qrxlevmin, etc.) — the other strong "continuous serving RSRP/RSRQ" candidate.
**This code was found only in SCAT; treat the table below as read from GPL-2.0 source,
disclaimer applies, needs hardware confirmation.**

Container: `Version (1B)`, then version-specific fixed layout (no subpacket wrapper).

### Version 4 (31/35 bytes depending on RRC release trailer)

| Field | Width | Units / scaling / packing |
|---|---|---|
| RRC standard release | 1B (bit-packed byte 1) | 0 or 1 (1 = Rel-9, adds trailer fields) |
| EARFCN | 2 | raw |
| PCI (9 bits) / Serving Layer Priority (7 bits) | 2 | packed 16-bit word, PCI = bits 0–8, priority = bits 9–15 |
| Measured RSRP (12 bits) | low 12 bits of a 4-byte word | **raw × 0.0625 − 180 dBm** |
| Average/filtered RSRP (12 bits) | low 12 bits of next 4-byte word | raw × 0.0625 − 180 dBm |
| Measured RSRQ (10 bits, bits 0–9) / Averaged RSRQ (10 bits, bits 20–29) | 4-byte word | each: raw × 0.0625 − 30 dB |
| Measured RSSI (11 bits, bits 10–20) | 4-byte word | raw × 0.0625 − 110 dBm |
| Q_rxlevmin (6 bits) / P_max (7 bits) / Max UE TX power (6 bits) / S_rxlev (7 bits) / Num DRX S-fail (6 bits) | 4-byte word, bit-packed | cell-reselection criteria, raw integer units per 36.304/36.133 |
| S_intra_search (6 bits) / S_non_intra_search (6 bits) | 4-byte word | raw |
| *(Rel-9 only)* Q_qual_min (7b) / S_qual (7b) / S_intra_search_Q (6b) / S_nonintra_search_Q (6b) | trailing 4-byte word | raw |

### Version 5 differs only in field widths

EARFCN widened to 4 bytes, PCI/priority word offset shifts by 2 bytes (padding inserted);
all bit-packing and scaling formulas are otherwise identical to v4.

**Source: GPL-2.0, read-only** — SCAT `diagltelogparser.py::parse_lte_ml1_scell_meas`
(struct formats `<BHHHLLLLLL>` for v4, `<BHLH2xLLLLLL>` for v5, plus documented bit
offsets via `bitstring.Bits(...)`) and `parse_rsrp`/`parse_rsrq`/`parse_rssi` (same
`0.0625`/offset constants as MobileInsight, see 0xB179 above). *These numbers came from
reading SCAT's parser only — no independent second implementation was found. A human
must confirm this layout against a real hardware capture before it is trusted.*

**Confidence: MEDIUM** (single source, GPL, disclaimer applies) for the exact bit-packing
of the reselection-criteria trailer; the RSRP/RSRQ/RSSI scaling constants themselves are
HIGH confidence since they are shared with the cross-validated 0xB179/0xB193 constants.

---

## 0xB180 — LTE ML1 Neighbor Measurements (idle-mode neighbour meas)

Periodic list of neighbour-cell RSRP/RSRQ/RSSI while idle or connected, keyed to the same
EARFCN as the serving cell. **SCAT-only source — GPL-2.0, disclaimer applies.**

Container: `Version (1B)`, `RRC release (1B)`, `EARFCN (2B v4 / 4B v5)`,
`Q_rxlevmin (6 bits) | Num Cells (10 bits)` packed into a trailing word, then
`Num Cells` repeated 32-byte neighbour records (+4-byte Rel-9 trailer per cell).

### Per-neighbour-cell record (32 bytes fixed + optional 4-byte Rel-9 trailer)

| Field | Width | Units / scaling / packing |
|---|---|---|
| PCI (9 bits, bits 0–8) / Measured RSSI (11 bits, bits 9–19) / Measured RSRP (12 bits, bits 20–31) | 4-byte word | RSSI: raw×0.0625−110 dBm; RSRP: raw×0.0625−180 dBm |
| Average RSRP (12 bits, bits 12–23 of next word) | 4-byte word | raw×0.0625−180 dBm |
| Measured RSRQ (10 bits, bits 12–21 of next word) | 4-byte word | raw×0.0625−30 dB |
| Average RSRQ (10 bits, bits 0–9) / S_rxlev (6 bits, bits 20–25) | 4-byte word | RSRQ: raw×0.0625−30 dB |
| Ant0 Frame Offset (11 bits) / Ant0 Sample Offset (remaining bits) | 2 | raw timing units |
| Ant1 Frame Offset (11 bits) / Ant1 Sample Offset (remaining bits) | 2 | raw timing units |
| *(Rel-9 only)* S_qual | trailing 4B word | raw |

**Source: GPL-2.0, read-only** — SCAT `diagltelogparser.py::parse_lte_ml1_ncell_meas`
(struct formats `<BHHH>`/`<BHLL>` for the header, `<LLLLHHLL>` per neighbour record, with
documented bit offsets). Same RSRP/RSRQ/RSSI scaling constants as elsewhere in this
document (cross-validated with MobileInsight's copy of the same constants, even though
this specific message layout is not implemented in MobileInsight). *A human must confirm
this bit-packing against a real hardware capture before it is trusted.*

**Confidence: MEDIUM** (single source, GPL, disclaimer applies) for the exact
bit-packing; HIGH for the RSRP/RSRQ/RSSI scaling constants themselves.

Note: do not confuse this with MobileInsight's `LTE_PHY_Idle_Neighbor_Cell_Meas`, which
is `0xB192` — a request/response-style packet, structurally unrelated, not researched
here since the brief specifically asked about `0xB180`.

---

## 0xB193 — LTE ML1 Serving Cell Measurement Result (a.k.a. "Serving Cell Meas Response")

**The single strongest candidate in this whole document.** Cross-validated in exhaustive
bit-level detail between MobileInsight (Apache-2.0) and SCAT (GPL-2.0, read-only). Gives
per-Rx-antenna RSRP/RSRQ/RSSI, combined RSRP/RSRQ/RSSI, SINR/SNR per antenna, and (in
later subpacket versions) CINR and IC-adjusted RSRQ.

Container: `Version (1B) | Number of SubPackets (1B) | reserved (2B)`, then repeated
subpackets: `SubPacket ID (1B) | SubPacket Version (1B) | SubPacket Size (2B)`. Subpacket
ID `0x19` (25 decimal) = "Serving Cell Measurement Result" — this is the one carrying the
RF measurements.

### Subpacket 0x19, version 36 / 48 / 50 (SCAT numbering) ≈ MobileInsight's v19 / v22

Subpacket header: `E-ARFCN (4B or 2B depending on outer version) | Num Cells (2B) | Valid Rx bitmap (2B)` (v48+ adds a 4-byte Rx-map field).

Then, per serving/CA cell, a fixed-size record (128 bytes for v36, 140 bytes for v48+):

| Field | Width | Units / scaling |
|---|---|---|
| PCI (9 bits) / Serving Cell Index (3 bits) / Is-Serving-Cell flag (1 bit) | 2B word | raw |
| SFN (10 bits) / Sub-frame Number (4 bits) | 2B word | raw |
| RSRP Rx[0] (12 bits) | packed in a run of 32-bit words (see note) | raw × 0.0625 − 180 dBm |
| RSRP Rx[1] (12 bits) | " | raw × 0.0625 − 180 dBm |
| RSRP Rx[2], RSRP Rx[3] (12 bits each, 4-antenna variants only) | " | raw × 0.0625 − 180 dBm |
| **RSRP (combined, 12 bits)** | " | raw × 0.0625 − 180 dBm, **plus a documented +40 dB correction observed in the SCAT decode** (treat this offset as unverified until confirmed on hardware — MobileInsight's table does not show an equivalent correction, this is a SCAT-only detail) |
| Filtered RSRP (12 bits) | " | raw × 0.0625 − 180 dBm |
| RSRQ Rx[0], Rx[1] (10 bits each) | " | raw × 0.0625 − 30 dB |
| RSRQ (combined, 10 bits) / Filtered RSRQ (10 bits) | " | raw × 0.0625 − 30 dB |
| RSSI Rx[0..3] (11 bits each) / RSSI combined (11 bits) | " | raw × 0.0625 − 110 dBm |
| **FTL SNR Rx[0], Rx[1] (9 bits each)** | " | **raw × 0.1 − 20.0 dB** |
| **Projected SIR** | 4B, signed (two's-complement over 32 bits) | **raw ÷ 16 → dB** (v19+/v48+ only) |
| **Post-IC RSRQ** | 4B | raw × 0.0625 − 30 dB (v19+/v48+ only) |
| CINR Rx[0], Rx[1] | 4B each | raw integer, units not independently confirmed by either source (v22+/v48+ only) |

The RSRP/RSRQ/RSSI/SNR/CINR fields above are **bit-packed across a contiguous run of
32-bit little-endian words that are then re-read MSB-first as one long bitstream** in
both implementations — this is unusual enough to flag: do not assume simple
byte-aligned struct-unpack; both sources build a `bitstring`/joined-word view first, then
slice bit ranges out of it. Exact bit offsets are version-specific; see the source files for
the precise slice indices per subpacket version before implementing.

**Sources:** MobileInsight `log_packet.h` lines ~965–1123 (`LtePhySubpktFmt_v1_Scmr_v4`
through `_v22`, Apache-2.0, cited directly — includes the RSRP/RSRQ/RSSI/SNR/Projected-SIR/
Post-IC-RSRQ/CINR field names, order and bit widths verbatim as comments in the source).
Cross-validated against SCAT `diagltelogparser.py::parse_lte_ml1_scell_meas_response*`
(GPL-2.0, read-only, disclaimer applies) — independently arrives at the same field set,
same scaling constants (`0.0625`, offsets `-180/-30/-110`, SNR `*0.1-20.0`, SIR `/16`),
and the same "SCell Meas Response" semantics for subpacket ID `0x19`. LTE Guide's blog
post (informal source) shows a real decoded 0xB193 packet from a Samsung device with
sane-looking output (RSRP ≈ -77.5 dBm, RSRQ ≈ -8 dB, SINR ≈ 8-11 dB — all in-range per the
3GPP tables below), corroborating that these constants produce physically sensible
numbers.

**Confidence: HIGH** (cross-validated by two independently-licensed implementations plus
a real-world decoded sample). The one open question is the `+40 dB` correction on
combined RSRP, seen only in the SCAT code path — flag for hardware confirmation, don't
implement blindly.

---

## 0xB173 — LTE PDSCH Stat Indication

Per-TB PDSCH decode statistics: MCS, number of RBs, modulation, CRC pass/fail (for BLER),
transport block size (for DL throughput independent of MAC-layer 0xB063). MobileInsight-only.

Container: `Version (1B)`, then a version-specific payload header
`{Num Records (1B), reserved (2B)}`, then `Num Records` repeated per-subframe records.
Each subframe record has a small **P1 header** followed by 1..N **transport-block (TB)
records** (`Num Transport Blocks Present` from the P1 header).

### P1 header (varies slightly by version, 8–14 bytes)

| Field | Width | Notes |
|---|---|---|
| Subframe Num (12 bits) / Frame Num (placeholder, decoded from same word) | 2 | raw SFN/subframe |
| Num RBs | 1 | raw resource-block count |
| Num Layers | 1 | 1 or 2 (spatial multiplexing) |
| Num Transport Blocks Present | 1 | 1 or 2 |
| Serving Cell Index | 1 | for CA |

### TB record (per transport block, ~5–8 bytes depending on version)

| Field | Width | Notes |
|---|---|---|
| HARQ ID (4 bits) / RV (2 bits) / NDI (1 bit) / **CRC Result (1 bit)** | 1 | **CRC Result is the pass/fail bit — count failures over passes for a running BLER%** |
| RNTI Type (4 bits) / TB Index (1 bit) / flags | 1 | RNTI type enum shared with MAC TB records |
| **TB Size** | 2 | bytes — sum over time for **DL PHY-layer throughput** |
| **MCS** | 1 | raw MCS index (0–31 for LTE) |
| Num RBs (per-TB) | 1 | raw |
| Modulation Type (v24+) | 1 | raw enum (QPSK/16QAM/64QAM/256QAM depending on chipset/version) |

Versions 5, 16, 24, 32, 36 were all found with the same overall shape; each version adds
one or two extra trailing fields (Modulation Type at v24+, "QED2"/iteration fields and an
ACK/NACK decision bit at v36) — see the source for the exact per-version field list if a
specific device reports version 36.

**Source:** MobileInsight `dm_collector_c/lte_pdsch_stat_indication.h` (Apache-2.0, cited
directly, full 1736-line file — internally consistent across all 5 versions). SCAT does
**not** implement this log code at all, so there is no independent second implementation
to cross-check against.

**Confidence: HIGH per source quality** (non-GPL, internally consistent across 5 known
versions) but **single-sourced** — no independent project confirms the byte layout.
Recommended: implement, but verify TB-Size/MCS/CRC-Result fields against a hardware
capture before trusting BLER numbers in a report.

---

## 0xB139 — LTE PHY PUSCH Tx Report

Per-UL-grant PUSCH transmission report: TB size (UL throughput), coding rate, modulation
order, Tx power, and ACK/RI/CQI payload metadata. MobileInsight-only.

Container: `Version (1B)`, payload header `{Serving Cell ID (9 bits) | Number of Records
(5 bits) (2B word), reserved (1B), Dispatch SFN/SF (2B), reserved (2B)}`, then `Number of
Records` repeated per-grant records (~64 bytes, exact size version-dependent).

### Per-record fields (version 23, representative — version 24 is a trimmed variant)

| Field | Width | Units / scaling |
|---|---|---|
| Current SFN/SF | 2 | raw |
| Coding Rate Data | 2 | **raw ÷ 1024.0 → effective code rate** |
| ACK / CQI / RI / Freq-Hopping / Redund-Ver / Mirror-Hopping / DMRS cyclic-shift / DMRS root / Start-RB slot0/1 / Num-RB flags | 4 | bit-packed word, mostly UL scheduling/DMRS metadata, not RF quality |
| **PUSCH TB Size** | 2 | bytes — sum over time for **UL PHY-layer throughput** |
| Num ACK Bits / ACK Payload | 2 + bitfield | HARQ-ACK feedback bits carried on PUSCH |
| Rate-Matched ACK Bits / RI bits / RI payload / PUSCH Mod Order | 4 | bit-packed; **PUSCH Mod Order** is the modulation index actually used |
| **PUSCH Digital Gain (dB)** | 1 | raw dB value |
| SRS Occasion / Re-tx Index | 1 | flags |
| **PUSCH Tx Power (dBm)** | 4 (10 significant bits) | raw dBm — device's actual UL transmit power for this grant |
| Num CQI Bits / Rate-Matched CQI Bits | packed in same word | UL CSI feedback metadata |
| CQI Payload | 16 | raw bytestream, needs UCI-payload decode to interpret |
| Tx Resampler | 4 | raw, purpose not documented by source |

**Source:** MobileInsight `dm_collector_c/lte_phy_pusch_tx_report.h` (Apache-2.0, cited
directly, full 2427-line file covering versions 23 and 24 in detail plus more beyond what
was inspected here). SCAT does not implement this log code — single-sourced.

**Confidence: HIGH per source quality** (non-GPL) but **single-sourced**; verify
`PUSCH TB Size` and `PUSCH Tx Power` against a hardware capture before trusting UL
throughput/power numbers derived from it.

---

# NR records

## 0xB822 — NR RRC MIB Info

Decoded NR MIB: PCI, NR-ARFCN, SFN, subcarrier spacing. Low-rate anchor, analogous to
LTE's 0xB0C1. **SCAT-only source — GPL-2.0, disclaimer applies.**

| Field | Width | Units / scaling |
|---|---|---|
| Minor Version | 2 | version discriminator |
| Major Version | 2 | version discriminator |
| PCI | 2 | raw |
| NR-ARFCN | 4 | raw |
| SFN (10 bits) | packed in a 4-byte (v0.3) or 5-byte (v2.0) bitfield read MSB-first | raw (0–1023) |
| Subcarrier Spacing (2 bits) | same bitfield | enum: 0=15kHz, 1=30kHz, 2=60kHz, 3=120kHz |

Two packet-version families seen: major.minor `0.3` and `2.0` — same field set, slightly
different bit offsets for the SFN/SCS bitfield (bits 0–9 / bits 30–31 for v0.3 vs bits
0–9 / bits 31–32 for v2.0, per SCAT's `bitstring` slice indices).

**Source: GPL-2.0, read-only** — SCAT `diagnrlogparser.py::parse_nr_mib_info`. A separate,
uncorroborated web mention describes this code's underlying struct name as
`nr5g_rrc_log_mib_s_V0x20000` with fields "DL Frequency", "Half Frame", "Intra Frequency
Reselection", "Cell Barred", "PDCCH Config SIB1", "DMRS TypeA Position", "SSB Subcarrier
Offset" — consistent in spirit (same message) but not independently verified against
SCAT's byte offsets, so it is **not** treated as a second corroborating source here, only
as weak supporting evidence that the code number and general content are right.

**Confidence: MEDIUM** (effectively single-sourced with GPL disclaimer; the weak web
corroboration raises confidence in the code identity but not the byte layout).

---

## 0xB823 — NR RRC Serving Cell Info

Cell-identity anchor: PCI, DL/UL NR-ARFCN, bandwidth, Cell ID, PLMN, TAC, band. NR
analogue of 0xB0C2. **SCAT-only source — GPL-2.0, disclaimer applies.**

### Version 0.4

| Field | Width | Units / scaling |
|---|---|---|
| PCI | 2 | raw |
| DL NR-ARFCN | 4 | raw |
| UL NR-ARFCN | 4 | raw |
| DL Bandwidth | 2 | raw, MHz-ish units (not separately confirmed) |
| UL Bandwidth | 2 | raw |
| Cell ID (NCI, 36-bit) | 8 | raw |
| MCC | 2 | raw |
| MCC-digit/MNC packing | 1 + 2 | MNC digit count + MNC |
| Allowed Access | 1 | bitmask |
| TAC | 4 | raw |
| Band | 2 | raw NR band number |

### Version 3.0 / 3.2 / 3.3 (adds a 64-bit NR-CGI field before the rest; 3.2/3.3 also add a
3-byte unknown prefix before PCI)

Same trailing field set as v0.4, with an 8-byte **NR CGI** field inserted right after PCI.

**Source: GPL-2.0, read-only** — SCAT `diagnrlogparser.py::parse_nr_rrc_scell_info`
(struct formats `<H LLHH Q H BH B LH>` for v0.4, `<H Q LLHH Q H BH B LH>` for v3.x).

**Confidence: MEDIUM** (single source, GPL, disclaimer applies).

---

## 0xB825 — NR RRC Configuration Info

Log-code number and name are confirmed correct (`LOG_5GNR_RRC_CONFIGURATION_INFO =
0x825` in SCAT's enum, matching the brief exactly) — but **no field layout was found in
either source.** SCAT defines the constant and even stubs out a handler function
(`parse_nr_rrc_conf_info`), but that function's body is just `pass` — it is not wired
into SCAT's dispatch table and does no parsing. MobileInsight does not reference this
code at all.

**Confidence: LOW.** This is a genuine "needs a hardware capture" case — there is
currently no public documentation of this record's byte layout anywhere that was found.
If this record is important (it likely carries RRC-configured measurement-object/
report-config parameters, based on the name), plan to reverse it from a live `.isf`/`.dlf`
capture rather than from any existing decoder.

---

## 0xB97F — NR ML1 Searcher Measurement Database Update Ext

**The strongest NR candidate**, analogous to LTE's 0xB193: per-carrier, per-cell,
per-beam(SSB) SS-RSRP/SS-RSRQ, cross-validated between MobileInsight and SCAT.

Container header: `Minor Version (2B) | Major Version (2B) | Num Layers (1B) | SSB
Periodicity Serv Cell (1B) | reserved (2B)`, then for each of `Num Layers` component
carriers, a **carrier record**, each carrier followed by `Num Cells` **cell records**,
each cell followed by `Num Beams` **beam records**.

### Carrier record (major.minor 2.7, 32 bytes)

| Field | Width | Units / scaling |
|---|---|---|
| Raster ARFCN | 4 | raw NR-ARFCN |
| Num Cells | 1 | raw |
| Serving Cell Index | 1 | raw |
| Serving Cell PCI | 2 | raw |
| Serving SSB index | 1 | raw |
| reserved | 3 | — |
| **Serving RSRP Rx[0]** | 4 | see scaling note below |
| **Serving RSRP Rx[1]** | 4 | see scaling note below |
| Serving Rx Beam[0], Rx Beam[1] | 2 each | raw beam ID, `0xFFFF` = not applicable |
| Serving RFIC ID | 2 | raw |
| reserved | 2 | — |
| Serving Subarray ID[0], [1] | 2 each | raw |

(major.minor 3.0 widens this to 40 bytes, adding a Carrier-Component ID byte and 2 more
Rx-antenna RSRP fields for 4-antenna devices — `serv_rsrp_rx_2`, `serv_rsrp_rx_3`.)

### Cell record (16 bytes)

| Field | Width | Units / scaling |
|---|---|---|
| PCI | 2 | raw |
| PBCH SFN | 2 | raw |
| Num Beams | 1 | raw |
| reserved | 3 | — |
| **Cell Quality RSRP** | 4 | see scaling note below |
| **Cell Quality RSRQ** | 4 | see scaling note below |

### Beam record (44 bytes for major.minor 2.7/2.9, 84 bytes for 2.10/3.0 — the larger
version adds ~10 extra 4-byte "unknown" fields between the two RSRP fields and the
L3-filtered fields)

| Field | Width | Units / scaling |
|---|---|---|
| SSB Index | 2 | raw |
| reserved | 2 | — |
| Rx Beam ID[0], [1] | 2 each | raw, `0` = not applicable |
| reserved | 4 | — |
| SSB Ref Timing | 8 | raw timing |
| **Rx Beam Info RSRP[0]** | 4 | see scaling note |
| **Rx Beam Info RSRP[1]** | 4 | see scaling note |
| *(2.10/3.0 only: ~10 × 4B unidentified fields)* | 40 | not decoded by either source |
| **NR2NR Filtered Beam RSRP (L3)** | 4 | see scaling note |
| **NR2NR Filtered Beam RSRQ (L3)** | 4 | see scaling note |
| **L2-NR Filtered Tx Beam RSRP (L3)** | 4 | see scaling note |
| **L2-NR Filtered Tx Beam RSRQ (L3)** | 4 | see scaling note |

### RSRP/RSRQ scaling — two different formulas found, use the newer one

- **Major.minor 2.7 (and later) — cross-validated, HIGH confidence:** the raw 32-bit
  field is read as an 8.7-ish signed fixed point: take the high byte of the low 16 bits
  as `integer = (raw >> 7) & 0xFF`, the low 7 bits as `frac = raw & 0x7F`, then
  `value_dB = (integer − 256) + frac × 0.0078125` (i.e. `−(256−integer) + frac/128`).
  **This exact bit manipulation is implemented identically in both MobileInsight's
  `_convert_nr_rsrp_rsrq_v2_7()` (Apache-2.0) and SCAT's `parse_float_q7()` (GPL-2.0)** —
  independent re-implementations landing on the same formula is strong evidence it's
  correct. A raw value of `0` is a documented "not available" sentinel (both sources map
  it to `NA` rather than `−256.0`).
- **Major.minor 2.6 (older, MobileInsight only) — LOW confidence, source itself flags
  it as unverified:** MobileInsight's older `_convert_nr_rsrp()`/`_convert_nr_rsrq()`
  helpers instead do `value = raw × 0.0078 − 0.0003` — a linear polynomial-fit
  approximation, and the source comments this as `// TODO: Based on polyfit. To be more
  accurate`, i.e. MobileInsight's own authors mark this one as an approximation, not a
  verified formula. **Do not implement this v2.6 formula as-is** — if a v2.6 packet is
  seen on real hardware, treat its RSRP/RSRQ scaling as unknown and re-derive it, rather
  than trusting the polyfit.

**Sources:** MobileInsight `dm_collector_c/nr_ml1_search_meas_database_update.h`
(Apache-2.0, cited directly, full 353-line file, versions 2.6 and 2.7). Cross-validated
against SCAT `diagnrlogparser.py::parse_nr_ml1_meas_db_update` (GPL-2.0, read-only,
disclaimer applies), which additionally covers versions 2.9, 2.10 and 3.0 (SCAT-only
extension, not cross-checked).

**Confidence: HIGH** for the v2.7 layout and RSRP/RSRQ scaling (cross-validated).
**MEDIUM** for the v2.9/2.10/3.0 4-antenna extensions (SCAT-only, GPL, disclaimer
applies, ~10 unidentified fields per beam record). **LOW** for the v2.6 scaling formula
specifically (source itself calls it an approximation).

---

## 0xB975 — NR ML1 Serving Cell Beam Management

Serving-cell filtered RSRP/RSRQ plus a per-detected-beam RSRP/RSRQ list — a lighter-weight
companion to 0xB97F focused on beam management rather than full search results.
MobileInsight-only (SCAT does not implement or enumerate this code at all).

Container header (major.minor 2.1, 24 bytes):

| Field | Width | Units / scaling |
|---|---|---|
| Minor Version | 2 | version discriminator |
| Major Version | 2 | version discriminator |
| PCI | 2 | raw |
| reserved | 2 | — |
| SSB Periodicity Serv Cell (ms) | 1 | raw |
| Serving Beam SSB Index | 1 | raw, post-processed as `(raw>>12) \| (raw>>4)` per the source — unusual, verify on hardware |
| reserved | 2 | — |
| **RSRP Filtered** | 4 | Q7-style scaling, same formula as 0xB97F v2.7 above |
| **RSRQ Filtered** | 4 | Q7-style scaling, same formula |
| reserved | 8 | — |
| Frequency Offset | 4 | raw |
| Time Offset | 4 | raw |
| Num Detected Beams | 1 | raw |
| reserved | 3 | — |

Per-beam record (major.minor 2.1, 12 bytes), repeated `Num Detected Beams` times:

| Field | Width | Units / scaling |
|---|---|---|
| Tx Beam Index | 2 | raw |
| reserved | 2 | — |
| **RSRP Filtered** | 4 | Q7-style scaling |
| **RSRQ Filtered** | 4 | Q7-style scaling |
| reserved | 4 | — |

**Source:** MobileInsight `dm_collector_c/nr_ml1_serving_cell_beam_mngt.h` (Apache-2.0,
cited directly, full 112-line file). Uses the same Q7 RSRP/RSRQ conversion function
(`_convert_nr_rsrp`/`_convert_nr_rsrp_rsrq_v2_7`-family) validated above for 0xB97F.

**Confidence: HIGH** for the RSRP/RSRQ scaling (shares the cross-validated 0xB97F
formula); **MEDIUM-HIGH** for the exact container/beam-record byte layout (non-GPL,
single source, no independent second implementation).

---

## 0xB80C — NR NAS 5GMM State ("MM5G state" — brief's 0xB80E not found, see mismatch table)

Not strictly a radio measurement, but useful context (registration state, TAC, PLMN,
GUTI) to pair with the RF records above. Cross-validated between MobileInsight and SCAT.

Container: `Version (4B, =1)`, then version-1 body:

| Field | Width | Units / scaling |
|---|---|---|
| MM5G State | 1 | enum: 1=DEREGISTERED, 2=REGISTERED_INITIATED, 3=REGISTERED, 4=SERVICE_REQUEST_INITIATED |
| MM5G Deregistered Substate | 2 | enum: 0=NORMAL_SERVICE, 1=PLMN_SEARCH, 2=NO_CELL_AVAILABLE, 5=LIMITED_SERVICE |
| PLMN ID | 4 (big-endian, 3 significant bytes) | packed MCC/MNC per standard 3GPP PLMN-ID octet encoding |
| GUTI: UE ID type | 1 | `2` = 5G-GUTI |
| GUTI: PLMN ID | 4 (big-endian, 3 sig. bytes) | packed MCC/MNC |
| GUTI: AMF Region ID | 1 | raw |
| GUTI: AMF Set ID | 2 | raw |
| GUTI: AMF Pointer | 1 | raw |
| GUTI: 5G-TMSI | 4 (big-endian) | raw |
| MM5G Update Status | 1 | enum: 0=UPDATED, 1=NOT_UPDATED |
| TAC | 3 | raw (24-bit NR TAC) |

**Sources:** MobileInsight `dm_collector_c/nr_nas_mm5g_state.h` (Apache-2.0, cited
directly). Cross-validated against SCAT `diagnrlogparser.py::parse_nr_mm_state` (GPL-2.0,
read-only, disclaimer applies) — struct format `<BH3s12sb3s>` matches this field
breakdown exactly, and SCAT additionally documents a second packet-version
(major.minor 3.0) with the identical body layout.

**Confidence: HIGH** (cross-validated) **for code 0xB80C**. The brief's `0xB80E` was not
found anywhere — see the mismatch table at the top of this document.

---

## 0xB888 — NR MAC PDSCH Stats ("NR MAC PDSCH Info" — brief's 0xB887 not found, see mismatch table)

Per-carrier DL MAC statistics aggregated over a reporting window: decode attempts, CRC
pass/fail counts (for BLER), retransmissions, and byte counters (for DL throughput and
padding overhead). MobileInsight-only.

Container header (24 bytes): `Minor Version (2B) | Major Version (2B) | Sleep (1B) |
Beam Change (1B) | Signal Change (1B) | DL Dynamic Cfg Change (1B) | DL Config (1B) | UL
Config (1B) | reserved (2B) | Log Fields Change Bitmask (2B) | reserved (1B) | Num
Records (1B) | reserved (12B, "some less important fields are skipped" per source
comment)`.

Per-carrier record (major.minor 2.2, 64 bytes):

| Field | Width | Units / scaling |
|---|---|---|
| Carrier ID | 4 | raw |
| Num Slots Elapsed | 4 | raw — denominator for a decode-rate calc |
| Num PDSCH Decode | 4 | raw count |
| **Num CRC Pass TB** | 4 | raw count |
| **Num CRC Fail TB** | 4 | raw count — **BLER% = 100 × Fail / (Fail + Pass)**, exactly as MobileInsight itself computes and appends as a derived "BLER (%)" field |
| Num ReTx | 4 | raw count |
| ACK-as-NACK count | 4 | raw count |
| HARQ Failure count | 4 | raw count |
| **CRC Pass TB Bytes** | 8 | bytes — numerator for **DL PHY throughput** |
| CRC Fail TB Bytes | 8 | bytes |
| TB Bytes (total) | 8 | bytes |
| Padding Bytes | 8 | bytes — overhead, subtract from TB Bytes for "useful" throughput |
| ReTx Bytes | 8 | bytes |

**Source:** MobileInsight `dm_collector_c/nr_mac_pdsch_stats.h` (Apache-2.0, cited
directly, full 115-line file). SCAT does not implement this code — single-sourced.

**Confidence: HIGH per source quality** (non-GPL, and the BLER-derivation logic visible
in the source is self-consistent and matches standard practice) but **single-sourced**
for the exact byte layout — verify on hardware. Note: this is `0xB888`, not the brief's
`0xB887` — see mismatch table.

---

## 0xB883 — NR MAC UL Physical Channel Schedule Report (brief's 0xB88A not found, see mismatch table)

Very detailed per-slot UL scheduling report — PUSCH/PUCCH/SRS grant parameters (MCS, RB
allocation, TB size, code rate, DMRS config, TPC, RNTI). MobileInsight-only; heavier on UL
scheduling detail than on RF quality, but the TB-size and MCS fields are directly useful
for UL throughput.

Container header (16 bytes), same "Sleep/Beam Change/Signal Change/..." shape as 0xB888
above, ending in `Num Records (1B)`.

Per-record structure is a 3-level nest: **slot record → per-carrier record → per-channel
(PUSCH/PUCCH/SRS) record.**

### Slot record (major.minor 2.11, 4 bytes) → Num Carrier records follow

| Field | Width |
|---|---|
| Slot | 1 |
| Numerology | 1 |
| Frame | 2 |

### Per-carrier record header (4 bytes)

| Field | Width | Notes |
|---|---|---|
| Carrier ID (packed with RNTI Type) | 1 | raw |
| PhyChan Bitmask (packed with Dual-Pol status) | 1 | bit N set → that physical channel (PUSCH/PUCCH/SRS) is present this slot |
| reserved | 2 | — |

### PUSCH record (major.minor 2.11, ~72 bytes, heavily bit/byte-packed)

Selected throughput-relevant fields (full record also carries DMRS/TPC/beta-offset/UCI
scheduling detail not reproduced here — see source for the complete ~40-field table):

| Field | Width | Notes |
|---|---|---|
| MCS | 1 | raw MCS index |
| RB Start | 1 | raw |
| **Num RBs** | 2 | raw |
| **TB Size (bytes)** | 2 | **UL PHY throughput numerator** |
| TX Mode | 1 | raw |
| Code Rate | 2 | raw (scaling not independently confirmed) |
| Num CBs (code blocks) | 1 | raw |
| RNTI Value | 2 | raw |
| RBG Bitmap | 4 | raw |

### PUCCH record (major.minor 2.11, ~20 bytes) — UCI/ACK scheduling, no throughput content.

**Source:** MobileInsight `dm_collector_c/nr_mac_ul_physical_channel_schedule_report.h`
(Apache-2.0, cited directly, full 528-line file). SCAT's enum has a *different* message at
`0x88A` (RACH Attempt, unimplemented) — no independent cross-check for this specific
message exists.

**Confidence: HIGH per source quality** (non-GPL, extremely detailed and internally
consistent) but **single-sourced**. This is `0xB883`, not the brief's `0xB88A` — see
mismatch table. Given its complexity, this is a lower implementation priority than
0xB888/0xB97F/0xB193 unless per-slot UL grant detail (not just aggregate throughput) is
actually needed.

---

## 0xB872 — NR L2 UL TB (brief's 0xB8D8 not found, see mismatch table)

Per-TTI UL MAC transport-block build report: grant size actually used, bytes built (for
UL throughput), and MAC control-element (BSR/PHR) presence. MobileInsight-only.

Container: `Version (4B, =4)`, then:

`Num TTI (packed byte: low 4 bits = count, bit 4 = "Is Type2 SCell", bit 5 = "Is Type2
Other Cell") | reserved (3B)`, then `Num TTI` repeated **TTI records**.

### TTI record

`Sys Time Slot Number (1B) | reserved (1B) | Sys Time FN (2B, low 10 bits significant) |
Num TB (1B, low 4 bits) | reserved (3B)`, then `Num TB` repeated **TB records**.

### TB record (18-byte fixed portion + variable MAC-CE payload)

| Field | Width | Notes |
|---|---|---|
| Numerology (3 bits) / HARQ ID (4 bits) | 1 | raw |
| Carrier ID (2 bits, split across two bytes) / TB Type (4 bits) / RNTI Type (3 bits) | 1 | TB Type enum: 0=CONNECTED, 4=RNTI, 8=IRACH; RNTI Type: 0=C-RNTI |
| Start PDU Segment flag | 1 | boolean |
| Num Complete PDUs (10 bits, split across two bytes) / End PDU Segment flag (1 bit) | 1 | raw |
| **Grant Size** | 4 | bytes — UL grant actually allocated |
| **Bytes Built** | 4 | bytes — **actual UL PHY throughput numerator (what was actually packed into the TB, vs. the grant offered)** |
| MCE Req/Build Bitmask (2×1B) | 2 | bit 0=PHR present, bit 1=BSR present |
| PHR Reason, BSR Reason (conditionally present per bitmask) | 2 | raw enums |
| MCE Length | 1 | length in bytes of following MAC-CE payload list |
| reserved | 3 | — |
| MAC-CE payload list (S-BSR / S-PHR entries) | variable | `MCE Length` bytes of `{Type(1B), payload}` entries; Type `0x3D`=S-BSR (adds BSR Index 5 bits + BSR LCG 3 bits), Type `0x39`=S-PHR (adds PH 6 bits + Pcmax-fc 6 bits + R 2 bits) |

**Source:** MobileInsight `dm_collector_c/nr_l2_ul_tb.h` (Apache-2.0, cited directly, full
409-line file, version 4 only observed). SCAT does not implement or enumerate this code at
all — single-sourced.

**Confidence: HIGH per source quality** (non-GPL, detailed bit-level comments) but
**single-sourced**. This is `0xB872`, not the brief's `0xB8D8` — see mismatch table.

---

# Common QXDM/QCAT log-mask sets for drive testing

No single canonical "the" drive-test mask exists publicly (QXDM/QCAT log-mask `.cfg`
files are themselves Qualcomm-internal and not published), but cross-referencing
Techplayon's and LTE Guide's descriptions of "important QXDM log packets" with the union
of codes both open-source decoders actually implement gives a consistent picture of what
field-test tools enable at minimum:

**LTE minimum measurement set:** `0xB0C0` (RRC OTA — already decoded), `0xB0C1` (MIB),
`0xB0C2` (Serving Cell Info), `0xB193` (ML1 Serving Cell Meas), `0xB17F`/`0xB179`/`0xB180`
(ML1 serving-eval / intra-freq-neighbour / neighbour-meas), `0xB173` (PDSCH stats),
`0xB139` (PUSCH Tx report), `0xB063`/`0xB064` (MAC DL/UL transport block), plus the NAS
OTA codes (`0xB0E2/E3/EC/ED`) already decoded by fieldTap. Techplayon additionally calls
out PUCCH CQI/RI/PMI/HARQ logs (`0xB14D` PUCCH CSF) and BSR/PHR MAC-CE logs as commonly
enabled for UL throughput debugging — not researched in depth here since they weren't in
the requested list, but worth a follow-up pass if UL diagnostics become a priority.

**NR minimum measurement set:** `0xB821` (RRC OTA — already decoded), `0xB822` (MIB),
`0xB823` (Serving Cell Info), `0xB825` (Configuration Info, layout unknown), `0xB97F` (ML1
measurement DB), `0xB975` (beam management), `0xB80C` (5GMM state), `0xB888` (MAC PDSCH
stats), `0xB883` (MAC UL schedule report), `0xB872` (L2 UL TB), plus NAS OTA codes
(`0xB800/801/808/809/80A/80B`) already decoded.

QCSuper's own `log_types.py` (GPL-3.0, name-only reference) confirms it enables only the
RRC-OTA and NAS-OTA layer for both LTE and NR (`0xB0C0`, `0xB0E2/E3/EC/ED`, `0xB821`) —
it does not touch any PHY/ML1/MAC measurement code at all, consistent with fieldTap's own
current scope (OTA-message decoding) and confirming there is no overlap/conflict with the
GPL-vendored QCSuper code already in this repo for the new measurement codes researched
here.

---

# 3GPP normative unit definitions (for sanity-checking decoded values)

These are the **RRC-reported, coarsely-quantized** scales defined by 3GPP for
`MeasurementReport` IEs — useful as a sanity range-check on decoded diag values, but note
the **diag-internal register values decoded above use a much finer 1/16 dB (0.0625)
resolution** with different zero-offsets, not these coarser RRC report scales directly.

| Quantity | Spec | Range (index) | Formula | dBm/dB range | Resolution |
|---|---|---|---|---|---|
| LTE RSRP | TS 36.133 §9.1.4, Table 9.1.4-1 | INTEGER(0..97) | dBm = index − 141 | −140 to −44 dBm (and above) | 1 dB |
| LTE RSRQ | TS 36.133 §9.1.7, Table 9.1.7-1 | INTEGER(0..34, extended range in later releases) | dB ≈ −19.5 + 0.5 × index | −19.5 to −3 dB (extended tables go lower in later 3GPP releases) | 0.5 dB |
| NR SS-RSRP / CSI-RSRP | TS 38.133 §10.1.6.1 | INTEGER(0..127) | dBm = index − 157 | −156 to −31/−30 dBm | 1 dB |
| NR SS-RSRQ / CSI-RSRQ | TS 38.133 §10.1.11.1 | INTEGER(0..127) | dB ≈ −43 + 0.5 × index (approximate — confirm exact table against the TS 38.133 revision in use) | ≈ −43 to +20 dB | 0.5 dB |
| NR SS-SINR / CSI-SINR | TS 38.133 (section numbering varies by release — seen cited as both §10.1.16 and §10.1.24 depending on revision/source) | INTEGER(0..127) | dB = −23 + 0.5 × index | −23 to +40 dB | 0.5 dB |

**Internal diag register scaling** (the values actually used by the field tables above,
cross-validated across MobileInsight and SCAT for LTE, and across MobileInsight and SCAT
for the NR Q7 format):

| Quantity | Formula | Applies to |
|---|---|---|
| LTE RSRP (diag) | `dBm = raw × 0.0625 − 180` | 0xB179, 0xB17F, 0xB180, 0xB193 |
| LTE RSRQ (diag) | `dB = raw × 0.0625 − 30` | 0xB179, 0xB17F, 0xB180, 0xB193 |
| LTE RSSI (diag) | `dBm = raw × 0.0625 − 110` | 0xB17F, 0xB180, 0xB193 |
| LTE ML1 SNR/FTL-SNR (diag) | `dB = raw × 0.1 − 20.0` | 0xB193 |
| LTE ML1 Projected SIR (diag) | `dB = signed(raw) ÷ 16` | 0xB193 (v19+/v48+) |
| NR RSRP/RSRQ (diag, Q7 format, v2.7+) | `dB = ((raw>>7 & 0xFF) − 256) + (raw & 0x7F) × 0.0078125` | 0xB97F (v2.7+), 0xB975 |
| NR RSRP/RSRQ (diag, older linear-fit, v2.6) | `≈ raw × 0.0078 − 0.0003` (source-flagged approximation, low confidence) | 0xB97F (v2.6 only) |

The diag-internal LTE constants (`0.0625` / `-180` / `-30` / `-110`) are a finer-grained
1/16-dB fixed-point encoding of essentially the same physical quantities the 3GPP tables
above describe at 1 dB / 0.5 dB RRC-report resolution — a decoded diag RSRP of, say,
−91.3 dBm should fall inside (or very near) the 3GPP RSRP-Range bucket a simultaneous RRC
MeasurementReport would report for the same instant, which is a good end-to-end sanity
check once both are decoded.

---

# Confidence summary

| Code | Name | Confidence | Basis |
|---|---|---|---|
| 0xB0C1 | LTE RRC MIB | **HIGH** | Cross-validated (Apache + GPL) |
| 0xB0C2 | LTE RRC Serving Cell Info | **HIGH** | Cross-validated |
| 0xB063 | LTE MAC DL Transport Block | **HIGH** | Cross-validated |
| 0xB064 | LTE MAC UL Transport Block | **HIGH** | Cross-validated |
| 0xB179 | LTE ML1 Connected-Mode Intra-Freq Meas | **HIGH** (code/scaling) / MEDIUM-HIGH (exact layout) | Code+constants cross-validated; layout single-sourced (Apache) |
| 0xB193 | LTE ML1 Serving Cell Meas Result | **HIGH** | Cross-validated in full bit-level detail; real decoded sample seen |
| 0xB173 | LTE PDSCH Stat Indication | **HIGH** (source quality) / single-sourced | Apache-only, internally consistent, no 2nd implementation |
| 0xB139 | LTE PUSCH Tx Report | **HIGH** (source quality) / single-sourced | Apache-only, internally consistent, no 2nd implementation |
| 0xB17F | LTE ML1 Serving Cell Meas and Eval | **MEDIUM** | GPL-only (SCAT), disclaimer applies |
| 0xB180 | LTE ML1 Neighbor Measurements | **MEDIUM** | GPL-only (SCAT), disclaimer applies |
| 0xB97F | NR ML1 Search Meas DB Update Ext | **HIGH** (v2.7 core) / MEDIUM (v2.9-3.0 ext.) / LOW (v2.6 scaling) | Cross-validated core; SCAT-only extensions; source-flagged approximation for v2.6 |
| 0xB975 | NR ML1 Serving Cell Beam Mgmt | **HIGH** (scaling) / MEDIUM-HIGH (layout) | Apache-only, shares cross-validated scaling formula |
| 0xB80C | NR 5GMM State (brief said 0xB80E) | **HIGH** | Cross-validated; **code number mismatch vs. brief** |
| 0xB888 | NR MAC PDSCH Stats (brief said 0xB887) | **HIGH** (source quality) / single-sourced | Apache-only; **code number mismatch vs. brief** |
| 0xB883 | NR MAC UL Phy Chan Sched Report (brief said 0xB88A) | **HIGH** (source quality) / single-sourced | Apache-only; **code number mismatch vs. brief** |
| 0xB872 | NR L2 UL TB (brief said 0xB8D8) | **HIGH** (source quality) / single-sourced | Apache-only; **code number mismatch vs. brief** |
| 0xB822 | NR RRC MIB Info | **MEDIUM** | GPL-only (SCAT), disclaimer applies |
| 0xB823 | NR RRC Serving Cell Info | **MEDIUM** | GPL-only (SCAT), disclaimer applies |
| 0xB825 | NR RRC Configuration Info | **LOW** | Code/name confirmed; no layout in any source |

## Implementation recommendation

**Implement now, high confidence:** 0xB193 (LTE serving RSRP/RSRQ/SINR — the primary
target), 0xB97F (NR serving/neighbour RSRP/RSRQ — the primary NR target, v2.7 format
only), 0xB063/0xB064 (LTE throughput), 0xB0C1/0xB0C2 (LTE cell anchor), 0xB80C (NR 5GMM
state, at the corrected code number), 0xB179 (LTE intra-freq neighbour RSRP/RSRQ).

**Implement with a hardware-verification checkpoint before trusting output:** 0xB173,
0xB139, 0xB975, 0xB888, 0xB883, 0xB872, 0xB822, 0xB823 (all single-sourced to one project,
several needing the corrected code number from the mismatch table).

**Do not implement from documentation alone — needs a hardware capture first:** 0xB17F
and 0xB180 (GPL-only source, bit-packing complex enough that a byte-level diff against a
real capture is warranted before trusting the reselection-criteria/neighbour fields), and
0xB825 (no layout exists anywhere).

**Do not implement at all under the brief's stated hex codes** — reroute to the
corrected codes instead, or capture-and-observe on real hardware: 0xB887 (use 0xB888),
0xB88A (use 0xB883), 0xB8D8 (use 0xB872), 0xB80E (use 0xB80C).
