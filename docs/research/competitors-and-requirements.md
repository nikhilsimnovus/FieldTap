# Drive-test / network-probe competitive research

Research pass for FieldTap: commercial and open drive-test tools, the KPI/event
vocabulary the industry has standardized on, typical drive-test workflow and report
contents, and a prioritized MVP checklist. Compiled from vendor product pages,
datasheets, brochures, user manuals, and third-party reseller/forum material — every
claim below is cited with a URL at the point it is made. Items that could not be
verified from public sources are flagged explicitly rather than guessed at.

Companion reading in this repo: [`../COMPETITIVE-LANDSCAPE.md`](../COMPETITIVE-LANDSCAPE.md)
(internal analysis, no citations, written before this pass), [`../ARCHITECTURE.md`](../ARCHITECTURE.md)
and [`../ROADMAP.md`](../ROADMAP.md) (what FieldTap is actually building today). This
document is the citation-backed research layer underneath those.

---

## 1. Feature matrix

### 1.1 Summary table

| Product | Vendor | Form factor | Capture depth | Chipsets/tech | GPS/map | Automated tests | Multi-device | Cloud/fleet | Licensing |
|---|---|---|---|---|---|---|---|---|---|
| XCAL-Mobile | Accuver | On-device Android app, laptop-tethered for post-processing | L1/L2/L3 air-interface + app QoE, via diag/DM port | GSM/CDMA/EVDO/WCDMA/HSPA/LTE/LTE-A/NB-IoT/5G-NR, Qualcomm & Samsung Exynos chipsets [[1]](#ref1)[[9]](#ref9) | On-device GPS tagging | Voice, VoLTE, FTP, HTTP/web, SMS, email, ping, YouTube, Netflix, social apps, autocall/MOS [[1]](#ref1) | 1 UE per handset app; XCAL-Mate backpack rig up to 12 UEs off one controller [[10]](#ref10) | XCAL-Pu6 benchmarking rig + tablet/palm controller for large campaigns [[11]](#ref11) | Enterprise, per-seat, roughly five figures/seat per internal estimate; UK Home Office paid **£30,240** for 3-yr XCAL/XCAP maintenance+support [[12]](#ref12) |
| XCAP | Accuver | Windows post-processing/analysis workstation app | Full L2/L3 decode plus TCP/IP/RTP/FTP/HTTP; consumes XCAL logs | All of the above plus mmWave 5G-NR [[2]](#ref2)[[9]](#ref9) | Geographic (map) visualization of KPIs | N/A (post-processing only) | Imports multi-device campaign logs, multi-window synchronized playback [[2]](#ref2) | Client-server enterprise edition available [[2]](#ref2) | Bundled/paired with XCAL seat |
| XCAL-Solo (Solo3/SoloII) | Accuver | Handheld dongle clipped to a phone | Air-interface + voice MOS, no root needed on host phone | GSM/CDMA/WCDMA/LTE-A up to 5CA, 5G-NR, DSS [[13]](#ref13) | Not confirmed in public docs | Automated call tests: voice, VoLTE, FTP, web, YouTube [[13]](#ref13) | "one or multiple devices simultaneously" per product page [[13]](#ref13) | Not verified | Not public |
| QualiPoc Android | Rohde & Schwarz | On-device Android app | L3 signaling + IP trace + audio/video QoE; "premier handheld troubleshooter" [[3]](#ref3) | Not itemized in public brochure; general Android smartphone/tablet support, CSV export [[19]](#ref19) | Not detailed in public pages (flagged) | Voice/audio quality (POLQA, PESQ, SQuad per ITU-T), data throughput across major protocols [[3]](#ref3) | QualiPoc Android Probe variant for fixed/multi-probe deployment [[3]](#ref3) | SmartBenchmarker (campaign config/monitoring) + SmartAnalytics (drill-down analytics, ML-assisted) [[14]](#ref14)[[15]](#ref15) | SmartLicenser floating license server [[3]](#ref3) |
| ROMES4 + SmartAnalytics | Rohde & Schwarz | Laptop software platform, pairs with test phones and R&S scanners (TSMx/TSME6) | Realtime statistics + instant processing; with TSMx scanner, estimates sector location/azimuth during drive [[4]](#ref4) | 5G, LTE, WCDMA, CDMA2000, 1xEVDO, WiMAX, TETRA, NB-IoT [[4]](#ref4) | Map-based, scanner-fed | Coverage, interference ID (GSM), pilot pollution, missing-neighbor detection | Multiple probes/scanners under one campaign via SmartBenchmarker | SmartBenchmarker orchestrates fleet + cloud config; SmartAnalytics is the web analytics suite [[14]](#ref14)[[15]](#ref15)[[16]](#ref16) | Not public |
| Nemo Handy | Keysight (formerly Anite) | On-device Android app, optional external Nemo Diagnostic Module (NDM) | Full RF + signaling logged to phone storage in Nemo file format [[5]](#ref5) | Qualcomm (X75/X80/X85), Samsung Exynos, MediaTek (M70/M80/M90 w/ NDM); 5G NR NSA/SA, CA, NR-DC, LTE-A, 2G-4G, WiFi/WiFi6 [[17]](#ref17) | Optional turn-by-turn navigation app for drive routes [[17]](#ref17) | Voice, VoLTE/ViLTE, FTP/HTTP, HTML browsing, email, iPerf, TWAMP, ping, SMS/MMS, Fast.com, YouTube/social apps, POLQA/ViSQOL audio, video QoE (ITU-T P.1204.1) [[17]](#ref17) | Up to 2 Nemo Handy units per NDM [[17]](#ref17); Nemo Network Benchmarking connects **up to 9 smartphones/IoT devices to one laptop** [[18]](#ref18) | Nemo Cloud (centralized remote control), Nemo Instant Report [[17]](#ref17) | No special firmware/rooting needed with NDM — commercial off-the-shelf phones reusable across models at no extra cost [[17]](#ref17) |
| Nemo Outdoor | Keysight | Laptop-based drive test software | Air-interface, protocol signaling, user-level performance; connects to phones, IoT devices, scanners [[6]](#ref6) | Qualcomm (X35 RedCap→X85), Samsung Exynos, MediaTek (M-series RedCap/5G); 5G SA/NSA, CA incl. mmWave/sub-6, 4x4 MIMO, massive MIMO, DSS, VoNR/VoLTE/ViLTE, VoWiFi/ViWiFi, LTE-A up to 8CC, NB-IoT, eMBMS [[6]](#ref6)[[20]](#ref20) | Real-time missing-neighbor and pilot-pollution analysis | Large-scale scripted measurement lists; QoE metrics | Proprietary "media router" interface for efficient multi-phone deployment [[20]](#ref20) | Nemo Network Benchmarking Solution (NBM), Nemo Backpack Pro [[6]](#ref6) | Public reseller listing: Qualcomm 5G Edition floating single-site perpetual license **$50,389.73** incl. 24 mo support [[21]](#ref21) |
| TEMS Pocket | Infovista | On-device app, remotely controllable | Not fully itemized in public datasheet excerpt (flagged) | 5G-capable devices + scanners; partner agreements with Samsung, OnePlus, Xiaomi, Sony, Asus [[7]](#ref7) | Not detailed publicly | Scripted service tests, device performance alarms | Remote-controlled units in vehicles or carried by non-technical staff [[7]](#ref7) | Not detailed publicly | Not public |
| TEMS Investigation | Infovista | Windows laptop application (data collection + real-time + post-processing + reporting in one) [[8]](#ref8) | Full RF, L2/L3 messages, IP info; 250+ predefined presentation windows [[8]](#ref8) | NR 5G, LTE FDD/TDD, NB-IoT, WCDMA, HSPA/HSPA+, GSM/GPRS/EDGE, CDMA IS-95→EV-DO Rev.B, Wi-Fi, LoRa; 300+ verified devices from 30+ vendors incl. Apple, Samsung, Huawei, PCTEL, R&S, Qualcomm, HiSilicon [[8]](#ref8) | Map windows synchronized with all other views; cell-site data overlay [[8]](#ref8) | Service Control Designer scripting: CS/PS voice & video, VoIP/VoLTE/VoWiFi/ViLTE, FTP/HTTP/TCP/UDP, WAP, email, MMS/SMS, video streaming, social apps, ping, traceroute, bandwidth tests, parallel sessions [[8]](#ref8) | Multiple devices connected and run simultaneously per session [[8]](#ref8) | GLS web license portal, cloud or mapped licenses [[8]](#ref8) | Purchased by feature/technology package; data-collection-optional tier exists for pure post-processing [[8]](#ref8) |
| NSG (Network Signal Guru) | QTRUN Technologies | On-device Android app (advanced mode = live decode on handset) | Full L2/L3 + SIP decode live on the phone; direct decode of signaling and data protocol packets [[22]](#ref22) | **Requires root + Qualcomm baseband** [[23]](#ref23); wide range of Qualcomm chipsets validated (Snapdragon 200–800 series, several MSM/APQ parts, some Exynos+Qualcomm-modem Samsung models) [[23]](#ref23) | Built-in GPS positioning of log data [[24]](#ref24) | Scripted: Voice MO/MT, FTP up/down, HTTP GET/POST — one scenario per script, run sequentially [[25]](#ref25) | Single device per app instance | None advertised | Reported "hundreds of USD, tiered" per internal note [[26]](#ref26); official pricing not found publicly |
| Cellular-Z | JerseyHo (indie) | On-device Android app, no root | Non-diag: Android `TelephonyManager`-level cell info, not raw L3 | Dual-SIM serving + neighbor cell info, WiFi info, 5G NSA [[27]](#ref27) | Basic GPS/location display | None (manual inspection tool) | Single device | None | Free [[27]](#ref27) |
| RantCell | MegronTech | On-device Android app + cloud backend | QoE/app-layer + some RF parameters (Enterprise tier adds "additional RF parameters") [[28]](#ref28) | Not itemized; targets commercial Android phones broadly | Google-Maps-based coverage/QoE overlays, geo-location query [[29]](#ref29) | Ping, HTTP, Speed(FTP-like), Call/voice, SMS, video streaming, coverage survey across 2G/3G/4G/5G [[28]](#ref28) | "single or hundreds of Android-based smartphones for mass drive testing" [[29]](#ref29); crowd-sourcing tier supports 50+ licenses [[28]](#ref28) | Native cloud platform: real-time upload, dashboard, remote/on-demand testing, device live tracking, threshold alerts [[29]](#ref29)[[28]](#ref28) | SaaS subscription, reported starting ~$1,600/year with a monthly test-minutes allowance [[30]](#ref30); tiered Basic/Professional/Enterprise plans [[28]](#ref28) |
| G-NetTrack Pro | Gyokov Solutions | On-device Android app, no root | Android-API-level serving+neighbor cell measurements only — no raw L3/RRC | 2G/3G/4G/5G level, quality, frequency for serving/neighbor cells [[31]](#ref31) | Logs to KML + text; requires "Allow all the time" location permission [[31]](#ref31) | Scripted data-sequence tests; summary exported as text file [[31]](#ref31) | Single device | None | One-time purchase, no subscription; reported price **$34.99** [[32]](#ref32) |
| QCSuper | P1sec (open source) | Laptop-tethered CLI, pulls from rooted phone/USB dongle | Raw 2G/3G/4G (and some 5G) radio frames via Qualcomm Diag/QCDM protocol → GSMTAP → pcap [[33]](#ref33) | Qualcomm only | None built-in (pcap is the product) | None | Single device per invocation | None | Free, GPLv3-adjacent (per repo) |
| SCAT | fgsect (open source) | Laptop-tethered, USB | Parses Qualcomm, Samsung Shannon, HiSilicon diag → GSMTAP stream [[36]](#ref36) | Qualcomm, Samsung Shannon, HiSilicon | None built-in | None | Single device | None | Free, open source |
| MobileInsight | mobile-insight.net (open source) | On-device Android app (also desktop monitor) | Runtime 4G/3G data via native `diag_revealer` daemon proxying `/dev/diag`; `.mi2log` format [[34]](#ref34)[[35]](#ref35) | Qualcomm and MediaTek chipsets claimed [[34]](#ref34); breaks on Android 11+ SELinux without per-kernel compile per third-party writeup [[9]](#ref9) | Not primary focus | Plugin-based (NetLogger, RrcAnalysis, NasAnalysis) | Single device | None | Free, open source |
| SnoopSnitch | SRLabs (open source) | On-device Android app, root required | IMSI-catcher/security-event detection from baseband data, not a general drive-test tool | Qualcomm-based Android, stock ROM ≥4.1, root required [[37]](#ref37) | Not primary focus | N/A | Single device | None | Free, open source, on F-Droid |

**Not verified / could not confirm:** "SmartRF" as named in the research brief does not
resolve to any drive-test or network-probe product in public search results — the
closest hits are Texas Instruments' unrelated SMARTRF-STUDIO RF-chip configuration tool
[[38]](#ref38) and general RF drive-test vendors (ThinkRF, ComSearch-style hardware).
Flagging this rather than guessing; if the brief meant a specific product, it needs a
name correction.

### 1.2 Notes on capture-depth claims

- Accuver explicitly ties XCAL/XCAP feature availability to chipset generation —
  business-wire release headlines announcing "ready for Snapdragon X55" and "ready to
  support 5G NR Standalone" show the vendor gates new-RAT support behind per-chipset
  qualification work, the same problem FieldTap's own architecture notes identify as
  the real technical moat (packet-version variance) [[39]](#ref39)[[40]](#ref40).
- NSG is the one commercial-adjacent tool in this set that explicitly documents a root
  + Qualcomm-baseband requirement in its own user manual, and documents exporting to
  Qualcomm's own `.dlf` diagnostic format for consumption by Actix Analyzer, TEMS
  Investigation, or QCAT [[41]](#ref41) — i.e., NSG's own vendor manual treats Qualcomm's
  native format as the interchange point, not a Wireshark-native one.
- TEMS Investigation is the most thoroughly documented tool in this set (its 78-page
  Technical Product Description is public) and is unusual in explicitly supporting
  **logfile export to pcap for IP protocol data**, plus tab-separated ASCII and
  MapInfo/ArcView-compatible formats [[42]](#ref42) — the only mainstream commercial tool
  in this list confirmed to emit pcap directly from its own export function.

---

## 2. KPI and event vocabulary

### 2.1 Physical-layer / radio KPIs

| KPI | Definition | Source |
|---|---|---|
| **RSRP** (Reference Signal Received Power) | 3GPP TS 36.214: "the linear average over the power contributions (in W) of the resource elements that carry cell-specific reference signals within the considered measurement frequency bandwidth," measured on antenna port 0 (CRS0) in LTE; in 5G NR, SS-RSRP is measured on SSB resource elements. Unit dBm. | [[43]](#ref43)[[44]](#ref44) |
| **RSRQ** (Reference Signal Received Quality) | RSRQ = N × RSRP / RSSI, where N is the number of resource blocks over the RSSI measurement bandwidth. Combines signal strength (RSRP) with the total received power (RSSI) to reflect quality under load/interference. Unit dB. | [[44]](#ref44)[[43]](#ref43) |
| **RSSI** (Received Signal Strength Indicator) | Total received wideband power observed by the UE, including the serving cell's signal plus interference and noise from all sources. Not 3GPP-standardized as a UE-reportable LTE measurement in the same formal sense as RSRP/RSRQ, but is a component of the RSRQ formula and is universally logged. | [[44]](#ref44) |
| **SINR** (Signal to Interference plus Noise Ratio) | Ratio of wanted signal power to interference-plus-noise power. Not defined in 3GPP specifications — computed and reported per UE-chipset-vendor implementation (this is why SINR values differ subtly between phone models on the same cell). Regarded by field engineers as the most direct predictor of achievable throughput/QoS. | [[43]](#ref43)[[44]](#ref44) |
| **CQI** (Channel Quality Indicator) | UE-reported index indicating downlink channel quality; the gNB/eNB maps a higher CQI to a higher-order MCS and larger transport block size for scheduling decisions. | [[45]](#ref45) |
| **MCS** (Modulation and Coding Scheme) | Index selecting modulation order (QPSK/16QAM/64QAM/256QAM…) and target code rate for a scheduled transmission; the network's link-adaptation decision, informed by CQI feedback. | [[45]](#ref45) |
| **BLER** (Block Error Rate) | Fraction of transport blocks received in error. Link adaptation targets a specific BLER operating point — historically ~10% for eMBB CQI tables; a separate low-BLER CQI table (target ~0.00001) exists for URLLC. | [[45]](#ref45) |
| **TA** (Timing Advance) | MAC-layer value the network signals to the UE so uplink transmissions arrive time-aligned at the receiver despite propagation delay; a foundational synchronization primitive carried over into 5G NR's OFDMA uplink. | [[45]](#ref45) |
| **PUSCH power / power control** | PUSCH (physical uplink shared channel) is power-controlled toward the reception point offering the best link, distinguishing it from PUCCH (control channel), which is power-controlled toward the serving cell. Field tools log the UE's actual PUSCH Tx power alongside the network's power-control commands. | [[45]](#ref45) |
| **Throughput — layer dependency** | Throughput is not one number: RLC-layer throughput is typically *higher* than application-layer throughput because L2/L3 signalling overhead and retransmissions sit between them, and application throughput additionally absorbs app-layer drops/retransmissions above RLC. Field tools and reports therefore distinguish PHY/MAC, RLC/PDCP, and application-layer throughput, with application throughput treated as "what the subscriber actually experiences." | [[46]](#ref46)[[47]](#ref47) |

### 2.2 Accessibility / mobility / retainability event vocabulary

| Event / KPI | Definition & formula | Source |
|---|---|---|
| **RRC Setup (Connection) Success Rate** | (Successful RRC Connection Setup completions ÷ RRC Connection Request attempts) × 100%, counted at the eNB/gNB from the RRC Connection Request through Setup Complete; can be broken out by `establishmentCause` (e.g., mo-Signalling vs mo-Data) in the initial RRC message. | [[48]](#ref48)[[49]](#ref49) |
| **Call Setup Success Rate (CS)** | Commonly composed as RRC setup success × S1 signaling connection success × E-RAB setup success — i.e., a chained product of the accessibility sub-procedures that must all succeed before a call/session is usable. | [[50]](#ref50) |
| **E-RAB / PDU session setup success rate** | Fraction of Evolved Radio Access Bearer (LTE) or PDU Session (5G) setup attempts that complete successfully; failures are attributed to specific reject/cause codes carried in NAS signaling. | [[50]](#ref50)[[52]](#ref52) |
| **Attach Success Rate (ASR)** | Total successful Attach completions ÷ total Attach attempts. For VoLTE specifically, often tracked as the percentage of PDN Connectivity Requests for the IMS APN that receive a successful response. | [[52]](#ref52) |
| **PDN/PDU Connectivity Success Rate** | ((Successful PDN connections + PDN connection rejections that were legitimately sent) ÷ total PDN connection attempts) × 100 — the MME-side formula distinguishes "correctly rejected" from "failed" attempts. | [[52]](#ref52) |
| **Cause codes (EMM/ESM/5GMM/5GSM)** | Standardized reject/failure reason codes carried in NAS reject messages (e.g., EMM cause #19 "ESM failure," returned in an Attach Reject when the embedded default-bearer/PDN-connection procedure fails) — the mechanism by which a drive-test tool attributes *why* an attach/session failed, not just that it failed. | [[53]](#ref53)[[52]](#ref52) |
| **Handover Success Rate** | Successful handovers ÷ handover attempts, broken out by type: intra-eNB (between cells of the same eNB) vs inter-eNB X2/S1 handover, and (in multi-RAT contexts) inter-RAT handover; ping-pong rate is tracked as a companion mobility-quality metric. | [[50]](#ref50)[[51]](#ref51) |
| **RLF (Radio Link Failure) & Re-establishment** | Declared when the UE's radio link to the serving cell is judged lost (e.g., persistent out-of-sync); the UE then attempts cell selection and RRC Connection Re-establishment. Reported service-interruption durations: roughly 80–130 ms for a successful handover, 800–3,000 ms for RLF recovery after a handover failure, and 3,000–5,000 ms for NAS-level recovery after a failed RLF recovery. | [[51]](#ref51) |
| **Dropped Call Rate (DCR)** | Fraction of calls that are cut off for technical reasons before either party ends the call normally — the classic retainability KPI, carried over largely unchanged from 2G/3G into VoLTE/VoNR reporting. | [[54]](#ref54) |
| **Call Setup Time (voice)** | VoLTE benchmark commonly cited around ~2 s end-to-end; VoNR field-test target commonly cited as under ~4 s, measured from UE-initiated Service Request through the SIP 200 OK on the answering leg. | [[55]](#ref55) |
| **MOS / POLQA / PESQ (voice quality)** | MOS (Mean Opinion Score) is the 1–5 subjective-equivalent scale (4 = "fair, minor impairments"); POLQA (ITU-T P.863) and PESQ are the standardized *objective* algorithms tools use to compute a MOS-equivalent score without a live listener. Field-tool acceptance thresholds commonly cited: POLQA MOS ≥ 4.0 on EVS Super-Wideband, ≥ 3.5 on AMR-WB. | [[55]](#ref55)[[3]](#ref3) |
| **VoNR field-proof KPI set** | A commonly cited six-KPI bar for "is this actually a working VoNR call": setup time < 4 s; POLQA MOS ≥ 4.0 (EVS-SWB); 5QI=1 one-way latency < 100 ms; EPS Fallback rate < 5%; Service Request reject rate < 1%; correct EVS codec negotiation. | [[55]](#ref55) |

### 2.3 Vendor-defined "Events" as a first-class object

TEMS Investigation's own technical description is explicit that **Events** (not raw IEs)
are the primary troubleshooting vocabulary the tool is built around: predefined events
like Blocked Call, Dropped Call, PDP Context Activation Failure, Radio Link Addition
Failure, Hard Handoff, and Traffic Handoff to EV-DO are generated by the software from
underlying protocol data, and the tool additionally lets users compose **custom events**
as logical expressions (AND/OR/XOR/NOT) over: occurrence of another event, appearance of
a specific L3 message, a value change in an information element, or a threshold crossing
on an information element [[56]](#ref56). This event-first design — not a raw log viewer,
but a semantic layer the engineer builds workflow around — is the pattern every
commercial tool in this list converges on, and is a strong signal for what a v1 "events"
feature in FieldTap should look like structurally.

---

## 3. Typical drive-test workflow and report contents

### 3.1 Workflow phases

1. **Plan.** Define the test route/cluster, the technologies/operators to test, and the
   automated test scripts to run (voice MO/MT pairs, FTP/HTTP up/down, ping, browsing,
   video streaming, SMS) — TEMS Investigation's Service Control Designer and NSG's script
   editor both formalize this as a flowchart/sequence the tool executes unattended
   [[8]](#ref8)[[25]](#ref25).
2. **Collect.** Drive or walk the route with one or more probes running; RF, L2/L3
   signaling, and IP-level data log continuously and are geotagged in real time; message
   windows, line charts, bar charts, map windows, and status windows update live so the
   engineer can catch a problem while still on-site rather than discovering it in
   post-processing [[8]](#ref8).
3. **Transfer / upload.** Logfiles are compressed and pushed to a server automatically
   (TEMS Investigation supports scheduled FTP transfer triggered on recording-stop; NSG
   supports export to Qualcomm `.dlf`; RantCell and Nemo Cloud push to a cloud backend in
   near-real time) [[8]](#ref8)[[41]](#ref41)[[29]](#ref29).
4. **Post-process.** A dedicated analysis tool (XCAP, TEMS Discovery, Nemo Analyze, Actix
   Analyzer, or SmartAnalytics) merges multi-log/multi-device campaigns, computes KPIs
   against 3GPP/ETSI/vendor formulas, and lets an engineer drill from a network-wide KPI
   down to the single sample/message that caused it [[2]](#ref2)[[14]](#ref14)[[57]](#ref57).
5. **Report.** A KPI report is generated — TEMS Investigation literally emits a live
   Microsoft Excel workbook that updates as new logfiles land, with an accompanying
   zoomable map report plotting service-related events [[58]](#ref58); other vendors ship
   PPT/PDF/HTML-based templates.

### 3.2 What an RF engineer expects to see in a session report

Based on the vendor report/KPI-window documentation above plus general drive-test
methodology writeups:

- **Coverage map(s)** — colored by RSRP/RSRQ/SINR threshold bands, per operator/technology,
  usually exportable as KML/KMZ for Google Earth overlay so a non-tool-holder can review
  it [[59]](#ref59)[[60]](#ref60).
- **KPI summary table(s)** — one row per KPI (RRC setup success, attach success, call
  setup success, handover success, drop rate, BLER, MOS) with pass/fail against an
  operator-defined threshold, filterable by operator, technology, and route segment
  [[58]](#ref58)[[61]](#ref61).
- **Event map/log** — every event (drop, handover failure, RLF, reject with cause code)
  plotted at its GPS location and clickable back to the contributing logfile/message
  [[56]](#ref56)[[58]](#ref58).
- **Throughput/latency charts over time and over distance**, usually per-layer (PHY/MAC
  vs application) since the two diverge [[46]](#ref46)[[47]](#ref47).
- **Voice/video quality scores** — MOS/POLQA per call, call setup time distribution, drop
  rate — usually its own section since voice QoE is judged against different thresholds
  than data KPIs [[3]](#ref3)[[55]](#ref55).
- **Message/signaling detail on demand** — full decoded L3 messages available by clicking
  into any point on the map or any row of the KPI table, not just summary numbers
  [[8]](#ref8).
- **Device/test metadata** — device model, firmware/chipset, operator/PLMN, band, route,
  date/time, tool version — the sidecar metadata that makes a report reproducible and
  comparable to a later drive of the same route. FieldTap's own roadmap already commits
  to this pattern (device, firmware, network, MCC/MNC, band, location, operator,
  start/stop time) [[62]](#ref62).
- **Export formats**: CSV (near-universal for KPI tables and cell lists) [[8]](#ref8)[[63]](#ref63);
  pcap for IP-layer data (TEMS Investigation explicitly, and it is FieldTap's own native
  format) [[42]](#ref42)[[64]](#ref64); MapInfo/ArcView-compatible and KML/KMZ for
  GIS/map tools [[42]](#ref42)[[59]](#ref59); Excel/HTML/PDF for the human-facing report
  itself [[58]](#ref58).

---

## 4. Prioritized MVP checklist

Positioning per the task brief and FieldTap's own roadmap: compete on "clean pcap, real
NR decode, honest Western vendor," plug-and-go laptop workflow, multi-phone support —
**not** on out-building XCAP's decade of post-processing depth
[[65]](#ref65). Each item below is marked **must** (blocks a credible v1 pitch),
**should** (materially strengthens it but v1 survives without it), or **later**
(post-v1), and whether it needs on-phone cooperation (root + app/diag access) or is
achievable laptop-only against a stock/diag-only device.

| # | Item | Priority | Needs on-phone cooperation? | Rationale / comparison point |
|---|---|---|---|---|
| 1 | One-command capture from a supported rooted handset to a valid pcap | **Must** | Yes — root + diag | This is FieldTap's Phase 1 exit criterion already [[66]](#ref66); every commercial tool in this list clears this bar trivially, it's the floor, not the differentiator |
| 2 | Correct NR RRC + NAS-5GS decode landing in stock Wireshark dissectors, no third-party plugin | **Must** | Yes | This *is* the differentiator per FieldTap's own architecture doc [[67]](#ref67) — no open tool here (QCSuper, SCAT, MobileInsight) advertises this as solid across modem generations, and it's the direct rebuttal to XCAL's closed decoder |
| 3 | Device/session metadata sidecar (device, firmware, MCC/MNC, band, GPS, operator, timestamps) written alongside every capture | **Must** | Partial (GPS/location needs phone or laptop GPS) | Universal across every commercial tool's report format [[62]](#ref62); without it a pcap is not a drive-test artifact, just a packet capture |
| 4 | GPS tagging of the capture (phone GPS or laptop-attached GPS/GNSS dongle) | **Must** | Either works | Every tool in this matrix ties measurements to location; it's non-negotiable for a coverage map [[59]](#ref59) |
| 5 | Multi-phone capture from one laptop (2+ devices, independent sessions, single UI) | **Must** | Yes, per device | Directly what "multi-phone support" in the brief means; XCAL-Mate does 12 UEs [[10]](#ref10)], Nemo does 9 [[18]](#ref18)] — FieldTap doesn't need to match that count for v1, but 1-device-only concedes the whole "plug-and-go laptop" pitch |
| 6 | Live/streaming decode view while capturing (not just post-hoc file open) | **Should** | Yes | Already Phase 3.2 on FieldTap's roadmap [[68]](#ref68); every commercial tool offers real-time display as table stakes [[8]](#ref8)[[3]](#ref3) |
| 7 | KPI extraction as a time series alongside signaling (RSRP/RSRQ/SINR minimum; CQI/MCS/BLER/TA/PUSCH power as reach) | **Should** | Yes (from diag) | Phase 3.3 on the roadmap [[69]](#ref69); this is what turns a pcap into something a non-Wireshark-fluent RF engineer can read |
| 8 | Event detection: attach/RRC/handover success-fail with cause codes, RLF/re-establishment, drop | **Should** | Yes | This is the semantic layer every commercial tool converges on (§2.3) [[56]](#ref56); can be built as a post-processing pass over decoded pcap rather than live, lowering v1 cost |
| 9 | Call-flow ladder diagram view of RRC/NAS procedures | **Should** | No (pure visualization over decoded data) | Explicitly what engineers look at per FieldTap's own roadmap note [[70]](#ref70); there's already a reference artifact in-repo for the target shape |
| 10 | CSV/JSON KPI export sidecar next to the pcap | **Should** | No | Matches the near-universal CSV export convention [[8]](#ref8)[[63]](#ref63) at near-zero engineering cost once KPIs are extracted |
| 11 | Device support matrix, published and honest (which handsets/chipsets/firmware are verified) | **Should** | N/A (documentation) | Already flagged on the roadmap as customer-facing [[71]](#ref71); directly counters XCAL's "supported list, vendor-provisioned" opacity — a *published*, honest list is itself a differentiator against a vendor that gates support behind quiet chipset-qualification cycles [[39]](#ref39)[[40]](#ref40) |
| 12 | Automated test sequencing (voice MO/MT, FTP/HTTP up-down, ping, browsing, SMS) driven from the laptop | **Should** | Yes (dial/data triggers need phone cooperation) | Every commercial tool treats this as core (§3.1); it's what makes a session unattended/repeatable rather than manual-click-through, but FieldTap can ship v1 as a pure capture+decode tool and add scripting after |
| 13 | Coverage map with KML/KMZ export | **Should** | No | Universal expectation in a session report (§3.2) [[59]](#ref59); straightforward once GPS+RSRP are in the sidecar |
| 14 | HTML/PDF session report generation | **Later** | No | High vendor-parity value but not what makes the pcap "clean" or the decode "real" — pure polish once 1–10 exist |
| 15 | VoLTE/VoNR audio quality scoring (POLQA/PESQ/MOS) | **Later** | Yes, needs an audio path | Requires either an audio-capture accessory or an on-device client, which is exactly the kind of hardware dependency (Keysight's NDM, R&S's ACU) that adds cost/complexity disproportionate to a v1 [[3]](#ref3)[[17]](#ref17) |
| 16 | Cloud/fleet dashboard, remote-control of distributed probes | **Later** | No (server-side) | This is what RantCell, Nemo Cloud, and SmartBenchmarker sell as the enterprise tier [[29]](#ref29)[[17]](#ref17)[[14]](#ref14) — valuable, but it's a scaling feature for after the core capture+decode product is trusted, not part of "clean pcap, real NR decode" |
| 17 | Modem write-path (band lock, cell lock, RAT force) | **Later**, explicitly sequenced after the product shell per FieldTap's own roadmap [[72]](#ref72) | Yes, and higher-risk (bricking risk) | This is NSG's signature capability, not XCAL's — chasing it early trades the "honest Western vendor" pitch's safety margin for a feature that isn't what differentiates FieldTap from XCAL specifically |
| 18 | MediaTek / non-Qualcomm chipset support | **Later** | Yes, different diag mechanism entirely | FieldTap's own Phase-0 decision already recommends Qualcomm-only for v1 [[73]](#ref73); every open tool here (QCSuper, SCAT, MobileInsight) is Qualcomm-first for the same reason, and NSG hard-requires Qualcomm baseband [[23]](#ref23) |
| 19 | Multi-session regression corpus / CI decode-correctness suite | **Should** (internal, not customer-facing) | No | Already Phase 2.5 on the roadmap [[74]](#ref74); this is what makes claim #2 ("real NR decode") durable across modem generations rather than a one-time demo |
| 20 | Voice-call quality without a hardware accessory, using an on-device software client | **Later** | Yes | Both Keysight and Infovista document on-device VoIP clients as one voice-QoE path that avoids external hardware [[17]](#ref17)[[8]](#ref8); worth revisiting once #15 is scoped, since it may be cheaper than an accessory-based approach |

**Reading the table as a sequence:** items 1–5 are the pipeline FieldTap already has
partially working per `ARCHITECTURE.md` [[75]](#ref75) and needs to harden; items 6–13
are what turn that pipeline into a "product shell" (FieldTap's own Phase 3
[[76]](#ref76)); items 14–16 are commercial polish that matters for a paying customer but
not for the technical claim being staked out; items 17–18 are deliberately deferred
because they are either higher-risk (modem write access) or dilute the Qualcomm-first
moat (MediaTek) rather than reinforcing it.

---

## 5. Gaps and unverifiable claims

Flagging explicitly, per the research brief's instruction not to guess:

- **"SmartRF"** — no drive-test/network-probe product by this name was found; see §1.1.
- **QualiPoc Android's exact chipset list, root requirement (if any), and report export
  formats** — the public brochure/datasheet pages returned mostly navigation chrome
  rather than technical body copy; R&S gates the detailed spec sheet and device release
  notes behind a login/contact-sales flow that WebFetch could not reach [[3]](#ref3)[[19]](#ref19).
- **ROMES4's own report/export format list** — same access limitation; only the
  SmartAnalytics companion suite's capabilities were confirmed [[4]](#ref4)[[14]](#ref14).
- **TEMS Pocket's captured-layer depth and multi-device count** — the public datasheet
  excerpt covered remote-control and device-monitoring features but not a layer-by-layer
  capture breakdown [[7]](#ref7).
- **Public pricing** is the norm nowhere in this market except three data points found
  incidentally: a UK government XCAL/XCAP maintenance contract (£30,240 / 3 yr)
  [[12]](#ref12), a reseller listing for a Keysight Nemo Outdoor Qualcomm 5G Edition
  floating license ($50,389.73) [[21]](#ref21), and RantCell's advertised SaaS starting
  price (~$1,600/yr) [[30]](#ref30). Every other vendor requires a sales quote; treat
  "roughly five figures per seat" for XCAL-Mobile as a directional internal estimate
  already flagged as such in `COMPETITIVE-LANDSCAPE.md`, not a sourced figure
  [[65]](#ref65).
- **NSG's current (2026) pricing and device list** — the only manual found dates to
  August 2017 [[24]](#ref24); QTRUN's own site did not surface a current price list.
  Chipset/device support has almost certainly expanded materially since; treat the
  specific Snapdragon/MSM part numbers in §1.1 as a 2017 snapshot, not current.
- **XCAL-Solo's GPS handling and pricing** — not found in public sources.

---

## References

<a id="ref1"></a>[1] [XCAL-Mobile — Accuver](https://www.accuver.com/products/network-optimization/XCAL-Mobile)
<a id="ref2"></a>[2] [XCAP — Accuver](https://www.accuver.com/products/post-processing/XCAP)
<a id="ref3"></a>[3] [QualiPoc Android — Brochure and Datasheet, Rohde & Schwarz](https://www.rohde-schwarz.com/us/brochure-datasheet/qualipoc_android/)
<a id="ref4"></a>[4] [R&S ROMES4 Drive Test Software — Brochure and Datasheet](https://www.rohde-schwarz.com/us/brochure-datasheet/romes/)
<a id="ref5"></a>[5] Keysight Nemo Handy Flyer (PDF), 5992-2050EN, July 2026 — https://www.keysight.com/us/en/assets/7018-05575/flyers/5992-2050.pdf
<a id="ref6"></a>[6] Keysight Nemo Outdoor Flyer (PDF), 5992-2057 — https://www.keysight.com/us/en/assets/7018-05580/flyers/5992-2057.pdf
<a id="ref7"></a>[7] [TEMS Pocket Datasheet — Infovista](https://www.infovista.com/sites/default/files/pdfs/ds_infovista_tems_pocket.pdf)
<a id="ref8"></a>[8] TEMS Investigation 23.0 Technical Product Description (PDF) — https://infocom.haradacorp.co.jp/wp/wp-content/uploads/2021/05/TEMS-Investigation-23.0-Technical-Product-Description.pdf
<a id="ref9"></a>[9] [XCAL Mobile network drive test software — Accuver](https://www.accuver.com/products/network-optimization/XCAL)
<a id="ref10"></a>[10] [Best XCAL Alternative 2026 — HiCellTek](https://hicelltek.com/en/alternative-xcal/)
<a id="ref11"></a>[11] [XCAL-Pu6 Scalable 5G Wireless Network Benchmarking Solution — Accuver](https://www.accuver.com/products/network-optimization/XCAL-Pu6)
<a id="ref12"></a>[12] [Accuver XCAL/XCAP licences maintenance and support 2024 — Contracts Finder (UK govt)](https://www.contractsfinder.service.gov.uk/notice/29612f67-32d1-4be6-a792-e2d5acb4384b)
<a id="ref13"></a>[13] [XCAL-Solo Handheld Mobile network measurement solution — Accuver](https://www.accuver.com/products/network-optimization/XCAL-Solo3)
<a id="ref14"></a>[14] [SmartBenchmarker — Rohde & Schwarz](https://www.rohde-schwarz.com/us/products/test-and-measurement/network-data-collection/smartbenchmarker_63493-528256.html)
<a id="ref15"></a>[15] [Network benchmarking — Rohde & Schwarz](https://www.rohde-schwarz.com/nl/solutions/test-and-measurement/mobile-network-testing/quality-benchmarking/network-benchmarking_231992.html)
<a id="ref16"></a>[16] [Rohde & Schwarz is the first provider of a complete end-to-end 5G NR network measurement solution](https://www.rohde-schwarz.com/us/about/news-press/all-news/rohde-schwarz-is-the-first-provider-of-a-complete-end-to-end-5g-nr-network-measurement-solution-press-release-detailpage_229356-631633.html)
<a id="ref17"></a>[17] Keysight Nemo Handy Flyer (PDF, full text), see [[5]](#ref5)
<a id="ref18"></a>[18] [Network Benchmarking: NTG10095A — Keysight](https://www.keysight.com/us/en/product/NTG10095A/nemo-network-benchmarking-solution.html)
<a id="ref19"></a>[19] [QualiPoc Android — Rohde & Schwarz product page](https://www.rohde-schwarz.com/us/products/test-and-measurement/network-data-collection/qualipoc-android_63493-55430.html)
<a id="ref20"></a>[20] [Outdoor 5G NR: NTA50000B — Keysight](https://www.keysight.com/us/en/product/NTA50000B/nemo-outdoor-5g-nr-drive-test-solution.html)
<a id="ref21"></a>[21] [KEYSIGHT Nemo Outdoor Qualcomm 5G Edition Floating license — WirelessUnits reseller listing](https://wirelessunits.com/keysight-nemo-outdoor-qualcomm-5g-edition-floating-single-site-perpetual-license-includes-sw1000-sup-01-for-24-months/)
<a id="ref22"></a>[22] [Network Signal Guru — Qtrun Technologies](https://www.qtrun.com/eng/nsg/)
<a id="ref23"></a>[23] Network Signal Guru User Manual, Aug 2017, §3.1–3.4 (PDF) — https://m.qtrun.com/docs/NSG_Manual_Aug_2017.pdf
<a id="ref24"></a>[24] NSG User Manual §5.7 GPS Positioning, same source as [[23]](#ref23)
<a id="ref25"></a>[25] NSG User Manual §7 Testing Scripts, same source as [[23]](#ref23)
<a id="ref26"></a>[26] [FieldTap internal competitive landscape notes](../COMPETITIVE-LANDSCAPE.md) (this repo; directional, not independently priced)
<a id="ref27"></a>[27] [Cellular-Z — AppBrain](https://www.appbrain.com/app/cellular-z/make.more.r2d2.cellular_z)
<a id="ref28"></a>[28] [RantCell QoE drive testing](https://rantcell.com/qoe-drive-testing.html)
<a id="ref29"></a>[29] [Mobile network test — RantCell](https://rantcell.com/) and [RantCell FAQ](https://rantcell.com/FAQ.html)
<a id="ref30"></a>[30] Search-aggregated RantCell pricing reference (~$1,600/yr starting SaaS plan), corroborated by [RantCell corporate plan page](https://rantcell.com/corporate-plan.html) tier structure
<a id="ref31"></a>[31] [G-NetTrack Pro manual — Gyokov Solutions](https://gyokovsolutions.com/manual-g-nettrack/)
<a id="ref32"></a>[32] [G-NetTrack Pro — AppBrain](https://www.appbrain.com/app/g-nettrack-pro/com.gyokovsolutions.gnettrackproplus)
<a id="ref33"></a>[33] [QCSuper — GitHub (P1sec)](https://github.com/P1sec/QCSuper)
<a id="ref34"></a>[34] [MobileInsight official site](http://www.mobileinsight.net/)
<a id="ref35"></a>[35] [mobileInsight-core Part-I: Monitor](http://www.mobileinsight.net/desktop-part-i-monitor.html)
<a id="ref36"></a>[36] [SCAT — GitHub (fgsect)](https://github.com/fgsect/scat)
<a id="ref37"></a>[37] [SnoopSnitch — F-Droid](https://f-droid.org/en/packages/de.srlabs.snoopsnitch/)
<a id="ref38"></a>[38] [SMARTRF-STUDIO Calculation tool — TI.com](https://www.ti.com/tool/SMARTRFTM-STUDIO) (unrelated product, included to document the disambiguation)
<a id="ref39"></a>[39] [Accuver's XCAL and XCAP Tools Ready for Qualcomm Snapdragon X55 Chipset — BusinessWire](https://www.businesswire.com/news/home/20191021005073/en/Accuver%E2%80%99s-XCAL-and-XCAP-Tools-Ready-for-Qualcomm-Snapdragon-X55-Chipset)
<a id="ref40"></a>[40] [Accuver's XCAL and XCAP Tools Ready to Support 5G NR Standalone (SA) — BusinessWire](https://www.businesswire.com/news/home/20200505005326/en/Accuvers-XCAL-and-XCAP-Tools-Ready-to-Support-5G-NR-Standalone-SA)
<a id="ref41"></a>[41] NSG User Manual §15.2 Qualcomm diagnostics log file / §15.3 Actix Analysis, same source as [[23]](#ref23)
<a id="ref42"></a>[42] TEMS Investigation Technical Product Description §10 Logfile Export, same source as [[8]](#ref8)
<a id="ref43"></a>[43] [RSRP (Reference Signal Received Power) — ShareTechnote](https://www.sharetechnote.com/html/Handbook_LTE_RSRP.html) (quotes 3GPP TS 36.214 definition directly)
<a id="ref44"></a>[44] [RSRP, RSRQ, SINR: complete field guide for RF engineers — HiCellTek](https://hicelltek.com/en/blog/rsrp-rsrq-sinr-field-guide/)
<a id="ref45"></a>[45] [5G URLLC: CQI & MCS — Medium](https://medium.com/@jessica.chchuang/5g-urllc-cqi-mcs-fb3e3ad994cf)
<a id="ref46"></a>[46] [RLC throughput / Application throughput — Westbay Engineers (Erlang.com forum)](https://www.erlang.com/topic/1-5455/)
<a id="ref47"></a>[47] [Throughput: LTE: RLC/PDCP Factors — ShareTechnote](https://www.sharetechnote.com/html/Throughput_LTE_RLC.html)
<a id="ref48"></a>[48] [RRC Connection Setup Success Rate — ResearchGate figure](https://www.researchgate.net/figure/RRC-connection-setup-success-rate_fig4_336331330)
<a id="ref49"></a>[49] [RRC Setup Success Rate (Service) — Group Telecom Engineering](http://grouptelecomengineering.blogspot.com/2018/09/rrc-setup-success-rate-service.html)
<a id="ref50"></a>[50] [LTE KPI Guide for Accessibility, Retainability, Integrity & Mobility — SlideShare](https://www.slideshare.net/MradulNagpal/lte-kpis-and-formulae)
<a id="ref51"></a>[51] [Self-Organizing Mobility Robustness Optimization in LTE Networks with eICIC — arXiv](https://arxiv.org/pdf/1310.6173)
<a id="ref52"></a>[52] [LTE EMM Cause #19: ESM Failure in Attach Reject — APN and PDN Diagnosis — HiCellTek](https://hicelltek.com/en/blog/lte-emm-cause-19-esm-failure/)
<a id="ref53"></a>[53] Same as [[52]](#ref52), EMM cause code discussion
<a id="ref54"></a>[54] [Dropped-call rate — Wikipedia](https://en.wikipedia.org/wiki/Dropped-call_rate)
<a id="ref55"></a>[55] [VoNR field testing 2026: how to validate voice quality on 5G Standalone — HiCellTek](https://hicelltek.com/en/blog/vonr-field-testing-5g-sa-voice-quality-validation/)
<a id="ref56"></a>[56] TEMS Investigation Technical Product Description §3.2 Events, same source as [[8]](#ref8)
<a id="ref57"></a>[57] [Actix Analyzer: The Global Standard in Drive Test Data Post-Processing — Scribd](https://www.scribd.com/document/368150555/Actix-Analyzer-Overview)
<a id="ref58"></a>[58] TEMS Investigation Technical Product Description §8 KPI Reporting, same source as [[8]](#ref8)
<a id="ref59"></a>[59] [Creating a drive test report in 10 minutes with G-Station](https://ekspresa.com/drive-test-report-10-minutes-gstation/)
<a id="ref60"></a>[60] [RF drive test 4G and 5G: what it is and how it works — Teleproject](https://www.teleproject.it/en/articles/rf-drive-test-4g-5g)
<a id="ref61"></a>[61] [Volte Drive Test — Scribd presentation](https://www.scribd.com/presentation/405932293/Volte-Drive-test-pptx)
<a id="ref62"></a>[62] [`../ROADMAP.md`](../ROADMAP.md) §Phase 1.3 (this repo) — capture metadata sidecar plan
<a id="ref63"></a>[63] TEMS Investigation Technical Product Description §5.1 Message Windows ("exported to a CSV file"), same source as [[8]](#ref8)
<a id="ref64"></a>[64] [`../ARCHITECTURE.md`](../ARCHITECTURE.md) (this repo) — FieldTap's own pcap-native pipeline
<a id="ref65"></a>[65] [`../COMPETITIVE-LANDSCAPE.md`](../COMPETITIVE-LANDSCAPE.md) (this repo) — "Where FieldTap fits" section, source of the "clean pcap, real NR decode, honest Western vendor" framing
<a id="ref66"></a>[66] [`../ROADMAP.md`](../ROADMAP.md) §Phase 1 exit criterion
<a id="ref67"></a>[67] [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §"The target pipeline" / "do not write an ASN.1 decoder"
<a id="ref68"></a>[68] [`../ROADMAP.md`](../ROADMAP.md) §Phase 3.2 Live view
<a id="ref69"></a>[69] [`../ROADMAP.md`](../ROADMAP.md) §Phase 3.3 KPI extraction
<a id="ref70"></a>[70] [`../ROADMAP.md`](../ROADMAP.md) §Phase 3.5 Call-flow view
<a id="ref71"></a>[71] [`../ROADMAP.md`](../ROADMAP.md) §Phase 1.4 Device support matrix
<a id="ref72"></a>[72] [`../ROADMAP.md`](../ROADMAP.md) §Phase 4, sequencing note
<a id="ref73"></a>[73] [`../ROADMAP.md`](../ROADMAP.md) §Phase 0.3 Chipset scope decision
<a id="ref74"></a>[74] [`../ROADMAP.md`](../ROADMAP.md) §Phase 2.5 Regression corpus
<a id="ref75"></a>[75] [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §"The pipeline as it stands"
<a id="ref76"></a>[76] [`../ROADMAP.md`](../ROADMAP.md) §Phase 3 "The product shell"
