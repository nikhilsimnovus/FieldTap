# The "app collects, uploads to a cloud account" market

Research for the decision on whether FieldTap should add (or pivot to) an Android app that
logs and uploads to a per-customer cloud account with a web dashboard.

Researched September 2026. Prices and chipset claims move; re-confirm before quoting
anything externally. Where a number came off a rendered pricing page rather than a PDF it
is marked as such.

---

## 0. The one-paragraph answer

Every high-volume crowdsourced platform in this market — Ookla, Opensignal, Tutela,
CellMapper, Speedchecker, Netradar, RantCell — stops at Android public-API KPIs plus
active tests. **None of them collect RRC, NAS or SIBs.** RantCell says so in its own FAQ.
The reason is structural, not commercial: on Android 11+ the diag path is closed by
SELinux to anything that is not root or a vendor-domain binary, so a mass-market app
literally cannot get layer 3. Everything that *does* get layer 3 on a handset — QualiPoc,
Nemo Handy, TEMS Pocket, XCAL-Mobile, NSG, HiCellTek — is a per-seat licensed tool needing
root, a vendor-provisioned device, or an external USB module, and its "cloud" is fleet
management for a paying fleet, not crowdsourcing. So there are two different businesses
here wearing the same words, and the team has to pick one.

---

## 1. Who already does this

### 1a. The crowdsourced / consumer-scale tier (public-API KPIs only)

| Player | App / source | Cloud portal | Model | Buyer |
| --- | --- | --- | --- | --- |
| **Ookla** (Speedtest, Cell Analytics, Speedtest Intelligence) | Own consumer app + partner SDKs | Yes — Cell Analytics, Speedtest Intelligence | Data licence / SaaS, quoted | Operators, regulators, ISPs, Esri GIS users |
| **Opensignal** (Comlinkdata) | Own app + SDK in third-party apps | Yes — Opensignal insights portals | Data licence / subscription | Operators, regulators (Ofcom), analysts, press |
| **Tutela** (Comlinkdata → folded into Opensignal) | SDK embedded in thousands of partner apps | Yes | Data licence | Operators, marketing/CX teams |
| **CellMapper** | Own app, volunteer contributors | Yes — public map + premium | Consumer freemium, **~CAD $3/month** | Hobbyists, enthusiasts, some RF engineers informally |
| **RantCell** (Megron Tech, UK) | Own app, paid device licences | Yes — RantCell cloud | **SaaS, $1,600–$3,000/yr, published** | Small operators, regulators, subcontractors, private-network installers |
| **Speedchecker** | SDK in partner apps + own apps | Yes, white-label / multi-tenant | Data licence + white-label | Regulators, MNOs, ISPs |
| **Netradar** (Finland, ex-Aalto) | SDK activated inside any app | Yes — dashboard, often deployed *in operator's own cloud* | Licence, private-cloud deployment | Operators, critical comms, private 5G, government |
| **5GMARK** | Consumer app + 5GMARK Pro | Yes | Freemium consumer + pro tool | Operators, press, consumers |
| **SigCap** (academic, Notre Dame / SpectrumX) | Research app | Central DB + front end | Research/grant funded | Academics, spectrum researchers |

**What they collect, uniformly:** signal strength and quality (RSRP/RSRQ/RSSNR/RSSI),
serving and neighbour cell identity, network type/operator, throughput, latency, packet
loss, location, device model, and increasingly device sensors. Opensignal's own privacy
policy enumerates the list and it contains no protocol layer at all — signal strength and
quality, network type, cell/WiFi identifiers, IP, speed/responsiveness/reliability, device
model and radio specs, accelerometer/pressure/light, battery temperature and voltage, GPS.
Nothing resembling RRC or NAS.

Opensignal's active tests are ordinary HTTP: three HTTP HEAD requests to google.com for
latency, eight concurrent HTTP GETs from a CloudFront replica for download, concurrent
HTTPS POSTs to S3 for upload. That is the whole measurement stack.

### 1b. The professional handset-probe tier (real layer 3, per-seat)

| Player | Layer 3? | Device access model | Cloud |
| --- | --- | --- | --- |
| **R&S QualiPoc Android** | Yes — "real-time Layer 3 signaling and IP trace decoding", full 3GPP/L2/L3/TCP-IP/IMS/SIP recording | Vendor-supplied/provisioned handsets (they literally sell you a preloaded Galaxy S22+) | SmartBenchmarker (web, campaign config) + SmartAnalytics (post-processing/insights) |
| **Keysight Nemo Handy** | Yes | COTS phone **plus the external Nemo Diagnostic Module (NDM) over USB** — "no special firmware or rooting is needed" *because* the NDM does the diag access. Requires the phone's external diag USB port to be enabled and a driver to exist | Nemo Cloud — fleet control, log upload, automated post-processing, KPI alerts |
| **Infovista TEMS Pocket / TEMS Sense** | Yes (Pocket) | Supported-device list, vendor cooperation | VistaTest+ (formerly TEMS Cloud) — test orchestration and analytics |
| **Accuver XCAL-Mobile** | Yes | Supported handsets, diag/DM provisioned via vendor cooperation | XCAL-Manager (server, fleet, web reports within an hour) + XCAP-Cloud (server-side post-processing, shared analytics) |
| **QTRUN NSG** | Yes — on-device ASN.1 decode of L2/L3/SIP, plus modem *write* (band lock, cell lock) | **Root required.** Qualcomm and MediaTek need root; HiSilicon needs a custom ROM; Samsung Exynos needs a Samsung token to restore the diag port after rooting | **None.** No cloud server or upload portal |
| **HiCellTek** (new EU entrant) | Claims yes — real-time RRC per TS 36.331/38.331 and NAS per TS 24.301/24.501 | **"runs directly on a rooted Qualcomm Android smartphone"** | Yes — EU-sovereign cloud post-processing, Desktop Analyzer, REST decoder + IMEI APIs |

### 1c. umlaut / P3 and the benchmarking-services tier

umlaut (formerly P3 Group) is a *services* business, not a product: it runs benchmark
campaigns and publishes network scores across 200+ networks in 120+ countries, plus a
crowdsourced panel. Accenture acquired it for **~$1 billion**, closing 1 October 2021,
absorbing 4,200+ engineers into Industry X.

Then, on **3–4 March 2026, Accenture agreed to buy Ziff Davis's entire Connectivity
division — Ookla/Speedtest, Downdetector, Ekahau and RootMetrics — for $1.2 billion cash
(~£900m).** So as of now Accenture owns both umlaut *and* Ookla. That is the single most
important structural fact in this market and it is six months old.

> Caution: a couple of secondary blogs claim "GTCR paid $1.3B for Ookla". That does not
> match the Ziff Davis and Accenture announcements or the trade press. Treat $1.2B /
> Accenture / March 2026 as the reliable figure.

### 1d. Newer entrants worth naming

- **HiCellTek** — the only entrant found that is genuinely attempting *exactly* what the
  team is considering: L3 on Android, cloud post-processing, published SaaS pricing,
  explicitly positioned as "the XCAL alternative" and "the TEMS alternative". Requires
  root. Running a "Founder's Series" promo (€700 for a kit that normally lists €3,000),
  which reads like a pre-revenue company buying its first reference customers. Unverified
  scale — treat as a signal of the opportunity, not proof of a market.
- **Speedchecker** — SDK-in-partner-apps play, white-label for regulators, 170+ countries.
- **Netradar** — notable because it sells *deployed into the operator's own cloud/VMs*
  rather than as a multi-tenant SaaS. That is a materially different and more defensible
  posture for anything touching subscriber data.

---

## 2. The critical question: does anyone collect layer 3 from an app?

**Short answer: no crowdsourced platform does, and the reason is a hard OS boundary.**

### The Android public-API ceiling

`TelephonyManager` / `CellInfo` is the whole surface available to an unprivileged app:
`CellInfoGsm/Cdma/Tdscdma/Lte/Nr` giving cell identity (CID, LAC/TAC, MCC/MNC, PCI,
ARFCN), signal strength in dBm, and quality metrics — for serving *and* neighbour cells.
That is it. There is no RRC, no NAS, no SIB, no measurement report, no ASN.1.

Two extra restrictions bite:

- It requires `ACCESS_FINE_LOCATION`.
- Apps targeting **Android Q+ no longer trigger a refresh** — they get the latest *cached*
  CellInfo, which may be stale. For a drive test that is a real accuracy problem, and it
  is the reason crowdsourced coverage maps are statistically useful but individually
  unreliable.

### The diag ceiling

Everything below that requires the Qualcomm DIAG interface, and:

- `/dev/diag` access **requires root**. Both MobileInsight and SnoopSnitch use it and both
  require root; SnoopSnitch additionally requires a Qualcomm chipset and the `DIAG_CHAR`
  kernel driver to be present.
- On **Android 11+, SELinux `neverallow` rules block `/dev/diag`** from app domains, and
  those rules "cannot be overridden without rebuilding the boot image."
- The one documented workaround is `diag_mdlog`, which runs in the *vendor* SELinux domain
  with pre-granted access. It buys Android 11+ support with no kernel or boot-image
  changes — but it **loses real-time streaming**, depends on the vendor having shipped the
  binary in firmware, and is Qualcomm-specific.
- QCSuper's own requirements confirm the shape: rooted phone over ADB, or a USB/pseudo-
  serial diag port, or TCP. Kernel ≤4.9 gets `/dev/diag` directly; 4.14+ needs USB
  mode-switching; Android 13+ needs `setprop sys.usb.config diag,adb`. "Root access
  remains the primary barrier for standard smartphone usage."

### Where each player's ceiling actually sits

| Player | Ceiling | Root? |
| --- | --- | --- |
| Ookla / Speedtest / Cell Analytics | Public-API KPIs + active HTTP tests. Cell identity, RF metrics, density, derived cell-site locations, 3D/vertical binning at 15 m | No |
| Opensignal / Tutela | Public-API KPIs + HTTP active tests + device sensors. Explicitly no protocol data | No |
| CellMapper | Public-API KPIs, cell identity, tower/sector inference | No |
| Speedchecker / Netradar / 5GMARK / SigCap | Public-API KPIs + active tests | No |
| **RantCell** | **Explicitly stated: "RantCell does not support layer 2 or layer 3 messages."** Also no MOS/POLQA. Collects MCC, MNC, PSC, Cell ID, RSSI/RSCP, data type, NR params | No — ships a "non-rooted" datasheet |
| QualiPoc | Full L3 | No root — but vendor-provisioned handsets |
| Nemo Handy | Full L3 | No root — but external NDM hardware over USB |
| TEMS Pocket | Full L3 | No root — supported-device list |
| XCAL-Mobile | Full L3 | No root — vendor-provisioned diag |
| **NSG** | Full L3 **+ modem write** | **Yes, root** (plus Samsung token / custom ROM per chipset) |
| **HiCellTek** | Claims full L3 | **Yes, root** |

### The strategic read

The market has bifurcated because of this boundary, not despite it:

1. **No-root, no-L3, huge N** — sell statistics. Value comes from scale and coverage. Wins
   on price per data point. Loses any question that starts "why did this cell fail?"
2. **L3, small N, per-seat** — sell root cause. Value comes from fidelity. Every vendor
   solves device access by *not* being a mass-market app: vendor-provisioned firmware
   (QualiPoc/XCAL/TEMS), external hardware (Nemo NDM), or root (NSG/HiCellTek).

FieldTap's existing S23 `*#0808#` finding (see `CAPTURE-OPTIONS.md`) sits in an interesting
third position: **L3 on a stock, unrooted, carrier-unlocked handset.** That is the one
combination none of the cheap tier has and only the expensive tier achieves, and only via
vendor cooperation or a USB dongle. It is a narrow door — specific Samsung models, resets
on some OTAs — but it is a genuinely differentiated one, and it is the strongest argument
for doing an app at all.

---

## 3. Pricing

### Published, verified

**RantCell** — read live off `rantcell.com/corporate-plan.html` (Stripe pricing table,
rendered September 2026):

| Plan | Price | Includes |
| --- | --- | --- |
| Basic | **$1,600/year** | 5 device licences, 1,620 testing hours/yr, coverage mapping 2G–5G, QoE visualisation, remote testing, basic automation, voice KPIs (CSSR, drops, setup time), CSFB & SRVCC, data KPI reports |
| Professional | **$3,000/year** | 8 device licences, 3,000 testing hours/yr, extended QoE reporting, dynamic alerts, **advanced RF parameters**, RAN automation, iPerf, cell tower QoE, benchmarking, up to 5 operators |
| Enterprise | Contact us | 12+ device licences, unlimited hours, dedicated instance, API access (Power BI/Grafana/Tableau), full Python automation, data hosting in preferred region, IoT/private network integrations |

Effective unit economics: **$320/device/year** on Basic, **$375/device/year** on
Professional. Crowdsourcing is gated behind Enterprise at **50+ licences**.

**HiCellTek** — published tiers:

| Plan | Price |
| --- | --- |
| Free (Google Play) | €0 — L3 decoder + band lock, **15-minute session cap**, no export |
| Pro (in-app) | **€22.99/month** — unlimited sessions, CSV & **PCAP layer 3 export**, PCI lock |
| Pro Field (direct) | **€249/month/device** (€2,490/yr, 2 months free annually), min 1 device — Voice MOS, FTP/iperf3/VoLTE scenarios, scripted drive test |
| Team | Quote, min 3 devices — Video MOS (ITU-T P.1204.3), encrypted HLOG, Desktop Analyzer (stated at **€2,490/yr/seat value**), team workspace |
| Enterprise | Quote, min 10 devices — SSO, audit logs, public REST API, EU data residency, DPA/SCC, SLA |

**CellMapper** — ~CAD $3/month premium, ad-free + faster processing. Consumer scale.

**QualiPoc Android** — a *refurbished* preloaded Galaxy S22+ unit lists at **$9,250 (from
$10,500)** on a secondary reseller. Useful as a floor on what the professional tier costs
per seat.

**NSG** — free to download, premium tier; user complaints reference a subscription around
**$50**. Hundreds of USD, tiered (consistent with `COMPETITIVE-LANDSCAPE.md`).

### Vendor-claimed incumbent pricing (HiCellTek's own comparison — treat as marketing)

- TEMS: scanner hardware **€15,000–50,000**, annual licence **€5,000–15,000**, 3-year total
  **€40,000–80,000**.
- Their framing: "from ~€500" vs "€30,000–80,000".

Keysight (Nemo Handy / Nemo Cloud), Infovista (TEMS Sense / VistaTest+) and Accuver publish
**no pricing at all** — quote-only, with Keysight offering lease-based subscriptions via
"Financial Alternatives". Accuver XCAL-Mobile is roughly five figures per seat.

### The pricing gap that matters

| Tier | Price per device-year | What you get |
| --- | --- | --- |
| Crowdsourced consumer | ~$0 (ad/data-funded) | Statistics |
| **RantCell** | **$320–375** | KPIs + active tests, no L3 |
| **HiCellTek Pro (app)** | **~€276** | L3 + PCAP export, root required |
| **HiCellTek Pro Field** | **~€2,988** | L3 + drive test + MOS |
| QualiPoc / XCAL / TEMS / Nemo | **$10,000–30,000+** | L3 + suite + procurement clearance |

There is roughly a **10x hole between $375 and $3,000**, and a **3–10x hole between $3,000
and $10,000+**. HiCellTek is the only company found trying to fill it, and it is doing so
with a root dependency that blocks operator procurement — the exact objection
`COMPETITIVE-LANDSCAPE.md` already identifies as NSG's weakness.

---

## 4. Who buys, and why they pick this over XCAL/Nemo

| Segment | What they buy | Why not the incumbent |
| --- | --- | --- |
| **Regulators / NRAs** | Crowdsourced feeds and white-label portals | Need national statistical coverage over years, not 40 drive-test hours. **Ofcom has negotiated access to the Opensignal crowdsourced dataset** and uses it in its "Map Your Mobile" postcode checker — 1M+ users. Ofcom's Oct 2024–Mar 2025 analysis (28% of connections on 5G, 71% 4G, 0.7% 3G, 0.2% 2G) is crowdsourced. Canada's CRTC commissioned a FARRPOINT review of mobile coverage reporting standards covering exactly this trade-off. |
| **Tier-1 operators** | Both. Crowdsourced for competitive/marketing/coverage claims; XCAL/TEMS/Nemo for RAN engineering | Different jobs. Crowdsourced data never answers "why did SIB1 fail on this cell?" |
| **Tier-2/3 and greenfield operators** | RantCell-tier SaaS | Cannot justify five figures per seat plus a scanner |
| **Subcontractors / rollout & SSV firms** | Cheap per-device tools | Margin-thin, per-site paid, headcount seasonal. A $375/device/yr tool they can put on 20 casual staff beats a $15k seat they must ration. This is RantCell's actual sweet spot. |
| **Tower companies / neutral hosts / DAS integrators** | Walk-test tools, indoor floor-plan surveys | Need proof-of-coverage per building, not RAN root cause. RantCell explicitly sells indoor/walk testing and up to 20 floor plans; "micro-crowdsourcing" via field staff on moving assets. |
| **Enterprises with private 4G/5G/CBRS** | Continuous monitoring probes | Have no RF team. Want a dashboard and an alarm, not ASN.1. RantCell and Netradar both target this. |
| **Journalists / analysts / investors** | Published league tables | Free or cheap, comparative, quotable |
| **Device vendors / chipset teams** | L3 tools only | Nothing else is useful to them |

**The honest generalisation:** buyers choose crowdsourced/SaaS when the question is *where
and how good*, and they choose XCAL/Nemo/TEMS when the question is *why*. Price is the
stated reason and coverage is the real one. Nobody has ever replaced an XCAL seat with a
crowdsourced feed for a debugging workflow — they add the feed and keep the seat.

---

## 5. Is it a real business? The honest base rate

### Successes

- **Ookla** — the category winner. Ziff Davis's Connectivity division (Ookla + Downdetector
  + Ekahau + RootMetrics) did **$231M revenue in 2025**, ~16% of Ziff Davis, and sold to
  Accenture for **$1.2B** (~5.2x revenue). 250M+ tests/month.
- **umlaut/P3** — **~$1B** to Accenture (2021), 4,200 staff. But that is a consulting
  business valued as consulting.
- **Opensignal** — acquired by Comlinkdata (2021), price undisclosed. Still the reference
  brand for regulators. Alive and used by Ofcom.

### Failures, cheap exits, and quiet deaths

- **TEMS itself is the cautionary tale.** Ericsson → Ascom in 2009 for **CHF 190M (~$198M)**.
  Ascom → Infovista in 2016 for a cash-free/debt-free enterprise value of **$45M** ($30M
  cash + a $15M 7-year subordinated vendor loan at 4%). **~77% of the value destroyed in
  seven years**, on the flagship brand of the entire category.
- **Tutela** — acquired 2019 for an undisclosed sum; brand folded into Opensignal;
  **Tutela Networks Limited dissolved 29 October 2024**. A VC-backed crowdsourcing pure-play
  that ended as a line item.
- **RootMetrics** — Root Wireless → IHS (2015) → **Ookla (Dec 2021)** → standalone
  CoverageMap app **discontinued**, folded into the Ookla app. Two acquisitions and the
  consumer product still got switched off.
- **Actix** — the independent RAN optimisation software leader, sold to Amdocs for **$120M**
  (2013). A good outcome, not a great one, for the category's best independent.
- **Anite** (Nemo) → Keysight 2015 for **~$606M / £388M** — but Nemo was one line inside a
  device-test business.
- **CellRebel** → Ookla, July 2022, undisclosed. Another crowdsourcing pure-play absorbed.
- **RantCell — the most relevant data point of all.** Megron Tech Ltd, UK company 07354880,
  **incorporated 24 August 2010**, SIC 62012, last accounts to 30 June 2025. Third-party
  estimates put it at **<$1M revenue and <10 employees**. That is *sixteen years* building
  precisely the product under consideration — Android app, cloud account, web dashboard,
  published SaaS pricing, regulator and operator positioning — and it is still a
  microbusiness.

### The base rate, stated plainly

- **Category winners are consolidators, not builders.** Every survivor at scale got there
  by acquisition (Ookla bought RootMetrics and CellRebel; Comlinkdata bought Tutela and
  Opensignal; Accenture bought umlaut and then Ookla). Independent crowdsourcing pure-plays
  do not compound — they get bought, folded and dissolved.
- **The crowdsourced data business is a scale business with a brutal minimum viable N.**
  Ookla has 250M+ tests/month and hundreds of millions of installs. Opensignal has 100M+
  devices and billions of daily measurements. A new entrant with 50 devices has no
  crowdsourced product at all — it has a fleet-management product.
- **The whole global #1 is a $231M revenue business** — and that includes Ekahau (Wi-Fi
  hardware/software) and Downdetector (which is not network measurement). The pure mobile
  crowdsourcing slice is smaller. This is not a large market at the top.
- **The paid-SaaS-for-engineers slice is real but small.** RantCell proves demand exists at
  $1,600–3,000/yr and proves it does not scale to a big company on its own.
- **Consolidation is accelerating and just closed the door.** Accenture now owns umlaut and
  Ookla. Comlinkdata owns Opensignal and Tutela. Two buyers, both fed. The obvious exit
  path for a new crowdsourcing entrant narrowed materially in March 2026.

> One methodological warning: market-research reports for "Crowdsourced Testing Market"
> ($2.61B 2025 → $4.17B 2030) are about **software QA crowdtesting**, not network
> measurement. Do not cite them for this. The relevant sizing is "Mobile Network Drive Test
> Equipment", quoted anywhere from **$2.54B (MRFR) to $6.17B (Mordor) in 2025** — a 2.4x
> spread between two vendors that should itself tell you how soft these numbers are.

---

## 6. Backend cost: what a minimal version actually requires

### Scope of "minimal but sellable"

1. **Accounts and orgs** — signup, org/tenant, roles (admin/engineer/viewer), invites, SSO
   later. Buy this: Cognito (free to 10k MAU), Clerk or Supabase Auth. Do not build it.
2. **Device enrolment** — device registry, pairing token/QR, licence binding to a device
   (HiCellTek binds device-side with RSA-2048), add/remove devices, seat counting.
3. **Ingest API** — metadata POST + presigned S3 PUT. Must be **chunked and resumable**:
   these uploads happen on the cellular network being tested, which is by definition the
   bad one. Idempotency keys, retry with backoff, dedupe.
4. **Object storage** — raw `.qmdl`/`.dlf` plus derived `.pcap`, lifecycle to Infrequent
   Access/Glacier after 30–90 days.
5. **Processing queue + workers** — SQS/Redis + a worker that runs the *existing* FieldTap
   decoder. This is the piece that reuses what the team already owns and is the single
   biggest argument for doing this at all.
6. **Measurement store** — Postgres + PostGIS for sessions/metadata; a columnar store
   (ClickHouse/Timescale) for the per-sample rows once volume bites.
7. **Map dashboard** — session list, session detail, KPI charts, **binned/aggregated** map
   layers (never raw points at scale), L3 message browser, pcap download, CSV export.
8. **Billing** — Stripe Billing + seat/device metering + dunning.
9. **The unglamorous half** — IaC, CI/CD, staging, backups + a *tested* restore, monitoring
   and alerting, audit logs, retention policy, DPA, and a data-residency story. Note that
   HiCellTek leads with "EU data residency, DPA/SCC" and Netradar's answer is to deploy
   inside the operator's own cloud. For anything touching subscriber-adjacent data, this
   is not optional polish — it is a gating requirement in procurement.

### Monthly run cost at 50 devices / 10 GB/month

All us-east-1 on-demand, current 2026 pricing.

| Line | Assumption | $/month |
| --- | --- | --- |
| S3 Standard | 10 GB/mo in; ~60 GB avg stored yr 1; ~3x with derived pcap ≈ 180 GB @ $0.023/GB | **~$4** |
| S3 requests | PUT $0.005/1k, GET $0.0004/1k at this volume | **<$1** |
| Decode workers | 10 GB/mo ≈ ~17 min of actual CPU. But you need a warm worker: Fargate 0.5 vCPU/1 GB (~$0.02/hr) or t4g.small | **$15–30** |
| API service | Fargate/App Runner small task, or Lambda + API Gateway ($1.00/M reqs) | **$15–30** |
| Load balancer | ALB fixed floor — the sneaky one | **$16–22** |
| RDS Postgres | db.t4g.micro ~$11.68 + gp3 storage; realistically db.t4g.small + 100 GB | **$25–50** |
| Data transfer out | First 100 GB/mo free, then $0.09/GB. Dashboard + log pulls likely inside free tier | **$0–10** |
| Auth | Cognito free at 50 MAU; Clerk free tier | **$0–25** |
| Map tiles | MapLibre + free tiles = $0; Mapbox free to 50k loads/mo | **$0–50** |
| Monitoring/logs | CloudWatch or Grafana Cloud free tier | **$10–30** |
| Domain, TLS, SES | | **~$5** |
| Staging environment | Scaled-down duplicate | **$30–50** |

**Realistic totals:**

- Lean, single environment, serverless-leaning: **~$70–120/month**
- Sane production + staging + monitoring: **~$150–300/month**
- With paid managed extras (Clerk, Mapbox, ClickHouse Cloud): **~$300–500/month**

**Infrastructure is not the cost.** At 50 devices this is rounding error against one
engineer-week. Do not let a cloud-cost spreadsheet drive this decision.

The volume that *does* bite is row count, not bytes: 50 devices × 1 sample/sec × 8 h/day ×
22 days ≈ **32M measurement rows/month**. Plan partitioning and retention from day one or
the dashboard gets slow at month four.

### Build estimate

| Workstream | Person-weeks |
| --- | --- |
| Android app: background capture service, diag path, chunked resumable upload, enrolment, foreground-service/battery/doze survival, storage management | 8–14 |
| On-device diag capture porting + device compatibility matrix (if not already done) | 4–8 |
| Backend: auth/org/device registry, ingest, queue, decoder worker, schema, REST API | 6–10 |
| Web dashboard: map, session list/detail, KPI charts, L3 browser, exports | 8–12 |
| Billing, plans, licence enforcement | 2–3 |
| DevOps: IaC, CI/CD, monitoring, backup/restore | 3–4 |
| Security & privacy: retention, DPA, residency, pen test | 2–4 |

**Total ~33–55 person-weeks ≈ 8–13 person-months.** With two engineers, **4–7 calendar
months** to a credible v1.

- At Indian blended fully-loaded cost (~$8–12k/engineer-month): **$65k–155k**
- At Western rates (~$12–18k/engineer-month): **$100k–235k**

**The recurring tax nobody budgets:** device compatibility churn. Every Android major
version, every OEM firmware change, every new chipset can break the diag path — the S23
`*#0808#` route already resets on OTAs carrying a carrier-config change. NSG maintains a
per-chipset matrix (Qualcomm/MediaTek root, HiSilicon custom ROM, Exynos token) because it
has to. Budget **~0.5 FTE indefinitely** just to keep capture working. This is the real
cost of the app path and it never goes away.

---

## 7. What this means for the decision

1. **"Crowdsourced" is off the table.** It needs 10^6–10^8 devices to have a product.
   Ookla and Opensignal have that, Accenture and Comlinkdata own them, and the March 2026
   Ookla deal just closed the last obvious exit. Do not enter this.
2. **"App collects L3, uploads to a paid cloud account" is a real but small business.**
   RantCell at $1,600–3,000/yr proves willingness to pay at the low end without L3.
   HiCellTek at €249/device/month is betting the L3 version commands 8x that. Sixteen
   years of RantCell says the ceiling on this alone is a microbusiness unless something
   else differentiates.
3. **The one genuine asymmetry the team holds is unrooted L3.** Every L3 competitor
   requires root (NSG, HiCellTek), vendor-provisioned firmware (QualiPoc, XCAL, TEMS), or
   an external USB module (Nemo NDM). The S23 `*#0808#` route plus a Qualcomm modem module
   is the only cheap path to L3 that a customer's security team will actually approve.
   That, not the cloud dashboard, is the product.
4. **The cloud is a feature, not the business.** Nemo Cloud, XCAL-Manager/XCAP-Cloud,
   SmartBenchmarker/SmartAnalytics and VistaTest+ all exist as *attachments* to a licensed
   fleet. The realistic version here is "FieldTap plus a place to put the logs and share
   them", priced per device, not "a data platform".
5. **Sequence matters.** The decoder and the capture path already exist and are the hard
   part. The backend is 8–13 person-months and ~$150–300/month to run. If the app is
   built, build it as fleet-and-log management for paying seats — never as a data-collection
   play — and put the EU/data-residency and no-root story in the first sentence of the
   pitch, because that is where both NSG and HiCellTek lose the deal.

---

## Sources

- RantCell FAQ (explicit "does not support layer 2 or layer 3 messages"): https://rantcell.com/FAQ.html
- RantCell pricing (rendered Stripe table, Sept 2026): https://rantcell.com/corporate-plan.html
- RantCell crowdsourcing for regulators: https://www.rantcell.com/crowdsourcing-platform-regulators.html
- Megron Tech Ltd, Companies House 07354880: https://find-and-update.company-information.service.gov.uk/company/07354880
- Megron Tech size estimate: https://www.owler.com/company/megrontech
- HiCellTek pricing: https://hicelltek.com/en/pricing/
- HiCellTek vs TEMS (root requirement, TEMS price claims): https://hicelltek.com/en/hicelltek-vs-tems/
- HiCellTek XCAL alternative (L3/RRC/NAS claims): https://hicelltek.com/en/alternative-xcal/
- Opensignal privacy policy (full collected-field list, no L3): https://www.opensignal.com/privacy-policy-apps-connectivity-assistant
- Opensignal methodology: https://insights.opensignal.com/2022/03/24/understanding-mobile-network-experience-what-do-opensignals-metrics-mean
- Opensignal methodology, independent review: https://coveragecritic.com/mobile-phone-service/opensignals-methodology/
- Comlinkdata acquires Opensignal (2021): https://insights.opensignal.com/2021/09/14/comlinkdata-acquires-opensignal-further-extending-its-leadership-in-customer-analytics-for-the
- Comlinkdata acquires Tutela (2019): https://www.rcrwireless.com/20190909/big-data-analytics/tutela-acquired-by-comlinkdata
- Ookla Cell Analytics (Esri partner page): https://www.esri.com/partners/ookla-a2T70000000TNK1EAO/cell-analytics-a2d5x000006jrMEAAY
- Ookla acquires CellRebel (2022): https://www.businesswire.com/news/home/20220727005067/en/Ookla-Acquires-CellRebel
- Ookla acquires RootMetrics (2021): https://www.fierce-network.com/wireless/ookla-buys-rootmetrics
- RootMetrics CoverageMap app shutdown: https://www.howardforums.com/threads/ookla-shutting-down-rootmetrics-coverage-map-app.1925951/
- Accenture buys Ookla, $1.2B (March 2026): https://www.techzine.eu/news/analytics/139252/accenture-buys-ookla-speedtest-downdetector-under-new-management/
- Same deal, £900m framing + Ziff Davis as seller: https://www.ispreview.co.uk/index.php/2026/03/broadband-speed-testing-giant-ookla-acquired-by-accenture.html
- Ziff Davis FY2025 results (Connectivity division $231M revenue): https://www.businesswire.com/news/home/20260223488387/en/Ziff-Davis-Reports-Fourth-Quarter-and-Full-Year-2025-Financial-Results
- Accenture acquires umlaut (~$1B, 2021): https://newsroom.accenture.com/news/2021/accenture-to-acquire-umlaut
- umlaut benchmarking scope: https://www.accenture.com/us-en/services/cloud/network/telco-benchmarking
- Ascom divests TEMS to Infovista, $45M enterprise value (2016): https://www.infovista.com/press-release/ascom-to-divest-its-network-testing-division-to-infovista
- Same, with deal structure: https://www.telecoms.com/wireless-networking/infovista-writes-45m-cheque-for-ascom-s-network-testing-biz
- Ericsson→Ascom TEMS sale, CHF 190M (2009) + TEMS history: https://en.wikipedia.org/wiki/Test_Mobile_System
- Amdocs acquires Actix, $120M (2013): https://www.telecoms.com/enterprise-telecoms/amdocs-buys-actix-in-120m-deal
- Keysight acquires Anite, ~$606M (2015): https://www.rcrwireless.com/20150617/test-and-measurement/keysight-acquires-anite-for-606-million-tag6
- R&S QualiPoc Android: https://www.rohde-schwarz.com/us/products/test-and-measurement/network-data-collection/qualipoc-android_63493-55430.html
- QualiPoc S22+ reseller price ($9,250): https://dasdeals.com/products/rohdeandschwarz-qualipoc-androids22
- R&S SmartBenchmarker: https://www.rohde-schwarz.com/us/products/test-and-measurement/network-data-collection/smartbenchmarker_63493-528256.html
- R&S SmartAnalytics: https://www.rohde-schwarz.com/us/products/test-and-measurement/network-data-analytics/smartanalytics_63493-528448.html
- Keysight Nemo Handy flyer (NDM, "no special firmware or rooting", chipset list): https://www.keysight.com/us/en/assets/7018-05575/flyers/5992-2050.pdf
- Keysight Nemo Cloud: https://www.keysight.com/us/en/product/NTC10011A/nemo-cloud-remote-monitoring-solution.html
- Infovista TEMS Sense: https://www.infovista.com/tems/sense
- Infovista VistaTest+ (formerly TEMS Cloud): https://www.infovista.com/products/tems-cloud/network-test-automation
- Accuver XCAL-Mobile: https://www.accuver.com/products/network-optimization/XCAL-Mobile
- QTRUN Network Signal Guru (L2/L3 decode, per-chipset requirements): https://www.qtrun.com/eng/nsg/
- NSG on Google Play (root requirement, pricing complaints): https://play.google.com/store/apps/details?id=com.qtrun.QuickTest
- SnoopSnitch (root + Qualcomm + DIAG_CHAR requirement): https://f-droid.org/en/packages/de.srlabs.snoopsnitch/
- MobileInsight paper: http://metro.cs.ucla.edu/papers/mobicom16-mobileinsight.pdf
- QCSuper (access methods, root barrier, Android 11/13 constraints): https://github.com/P1sec/QCSuper
- Android 11+ SELinux / diag_mdlog vendor-domain workaround (JISIS, 2026): https://jisis.org/wp-content/uploads/2026/03/2026.I1.034.pdf
- Android TelephonyManager / CellInfo API surface + Android Q caching restriction: https://developer.android.com/reference/android/telephony/TelephonyManager
- CellMapper premium pricing: https://www.cellmapper.net/subscribe
- Speedchecker crowdsourcing system: https://www.speedchecker.com/products/crowdsourcing-system.html
- Netradar products (SDK, operator private-cloud deployment): https://www.netradar.com/products/
- 5GMARK: https://www.5gmark.com/the-solutions
- SigCap (SpectrumX): https://www.spectrumx.org/exhibitors/sigcap-a-crowdsourcing-platform-for-wireless-broadband-mapping/
- Ofcom Map Your Mobile / Opensignal crowdsourced dataset: https://www.thinkbroadband.com/news/ofcom-launches-map-your-mobile-coverage-and-performance-checker
- Ofcom checker usage (1M+ users): https://www.ispreview.co.uk/index.php/2026/05/1-million-people-have-used-ofcoms-uk-mobile-network-coverage-checker.html
- CRTC / FARRPOINT mobile coverage reporting review: https://crtc.gc.ca/eng/publications/reports/farrpoint2025.htm
- Drive test equipment market sizing (Mordor, $6.17B 2025): https://www.mordorintelligence.com/industry-reports/mobile-network-drive-test-equipment-market
- Drive test equipment market sizing (MRFR, $2.54B 2025): https://www.marketresearchfuture.com/reports/mobile-network-drive-test-equipment-market-43775
- AWS S3 pricing 2026: https://www.cloudzero.com/blog/s3-pricing/
- AWS data transfer out pricing 2026: https://egresscost.com/aws/data-transfer-pricing/
- AWS RDS db.t4g.micro pricing: https://www.economize.cloud/resources/aws/pricing/rds/db.t4g.micro/
