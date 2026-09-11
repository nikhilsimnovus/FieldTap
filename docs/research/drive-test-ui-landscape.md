# Drive-test UI landscape

What the incumbent tools actually put on screen, what an engineer looks at during a drive,
what only exists after the drive, and where the workflow is genuinely bad.

Research date: 2026-09-10. Sources are vendor manuals and technical product descriptions,
vendor training decks, app-store listings and reviews, and engineer forums. Every claim
below is attributed; where a source was thin I say so rather than filling the gap.

A note on method: vendor marketing pages are close to useless for this question — they all
say "intuitive user interface". The useful sources are (a) technical product descriptions
and user manuals, which enumerate views by name, (b) training decks, which occasionally
leak numbers the marketing page would never print, and (c) app-store reviews and forums,
which are where the complaints live.

---

## 0. The three architectural families

Before the screen inventory, the shape of the market. Everything below falls into one of
three families, and they have genuinely different UIs because they have different jobs.

| Family | Members | Screen model |
| --- | --- | --- |
| **Laptop + scanner** | TEMS Investigation, Nemo Outdoor, ROMES4, XCAL | MDI desktop app. Dockable windows arranged into user-saved tab groups. Windows-only. |
| **Post-processing workstation** | XCAP, Nemo Analyze, TEMS Discovery, NQDI/SmartAnalytics, AirScreen | Database-backed. Import step, then workbook/workspace of synchronised views. |
| **Handset** | TEMS Pocket, QualiPoc Android, Nemo Handy, XCAL-Mobile, NSG | One full-screen "data view" at a time, swiped horizontally, with an action bar on top. |

The handheld family has converged on a **single UI idiom** to a degree that is not
accidental — see §1.3.4.

---

## 1. Screen inventory

### 1.1 Laptop-class collection tools

#### 1.1.1 TEMS Investigation (Infovista)

Structure: **Workspace → Worksheets → Presentation Windows**, plus a **Navigator** and an
**equipment navigator** side panel.

- Up to ten **worksheets** active at once; the shipped workspace has worksheets named
  Overview, Data, Signaling and similar. A worksheet is a tab holding a fixed arrangement
  of windows. ([TEMS Investigation 15.x training, via Scribd](https://www.scribd.com/doc/239063121/TEMS-Investigation-15-x-Training))
- Six window *types*, per the 22.3 TPD §5:
  - **Message windows** — Layer 3 messages, Layer 2 messages, mode reports, error reports
    from external devices, and TEMS-generated events. Columns: message name, direction
    (UL/DL), originating protocol. Has a filter to pick exactly which L3 messages appear.
    **Clicking a message freezes the window** so you can read it without the list scrolling
    away. A dedicated events window shows the same event symbols used on maps and charts.
    Right-click exports the whole list to CSV.
  - **Line charts** — subdivided into synchronised sub-panes. The cdma2000 example in the
    TPD has: active-set membership count as bars with Ec/I0 curves overlaid, a radio-link
    event lane, receive power, FER, a scales column, a pane of extra non-graphed values,
    and a readout of *values at the highlighted time instant*.
  - **Bar charts** — primarily frequency scans, but any numeric IE.
  - **Map windows** — route markers where each marker encodes **up to three IEs** (size,
    colour, shape), multiple marker layers drawable in parallel, cell sites plotted with
    serving-cell/active-set connecting lines, a right-hand auxiliary pane for the legend and
    for click-to-inspect, rubber-band area selection that computes statistics for the
    selected region. MapInfo, uncompressed TIFF or bitmap.
  - **Status windows** — tabular, "constantly refreshed, showing the situation at one
    instant in time". Ready-made ones per category (signal strength, speech quality,
    WCDMA radio parameters, LTE PCFICH/CFI, HSDPA, HSUPA…) plus a blank template.
  - **Event Counter window** — user-configurable counters with default tabs grouped
    "Voice", "Packet Switched" etc.; resets automatically on opening a new logfile.
  - Plus **MRDC Monitors**, added for DSS/NSA work, which let you mix IEs from 5G and LTE
    RAT states in one monitor.
- The synchronisation model is the important bit: *"Status windows are constantly refreshed
  … whereas maps and line charts accumulate information and display the whole history of the
  testing session. All windows are synchronized: When the user selects an arbitrary time
  instant in a map or line chart, the status windows are automatically updated."*
  ([TEMS Investigation 22.3 TPD §5](https://infocom.haradacorp.co.jp/wp/wp-content/uploads/2020/10/TEMS-Investigation-22.3-Technical-Product-Description.pdf))
- Scale: **"more than 250 predefined presentation windows"** (TPD §1.2.1). Remember that
  number; it comes back in §4.
- Modes: recording (drive) and **Replay** for logfile playback (TPD §4.2).

#### 1.1.2 Nemo Outdoor (Keysight)

Standard Windows MDI: menu bar, toolbar, status bar with a **time slider** for scrubbing
through measurement data, and **View Groups** — tabs that hold multiple measurement windows
so graphs and maps do not overlap; window membership persists across restarts.
([Nemo Outdoor 5 UI, Pathloss blog](http://pathloss40.blogspot.com/2010/03/nemo-outdoor-5-user-interface.html))

Conventional layout during a drive: **textual windows on the left** (Statistics, Decoded
Layer 3, Parameters, Events, Tables, Layer 3), **graphical on the right** (scatter plot,
line graph, bar graph, scanning plots).
([Nemo Outdoor GUI tutorial](http://rfoptimisation.blogspot.com/2017/06/tutorial-nemo-outdoor-graphical-user.html))

From the 8.01 user guide: Welcome page, Configuration Manager, Device Status view, Devices
view, Output window, Script Status window; graph types extend to gauge, spectrum and colour
grid; grids carry **search over decoded message content**; maps are MapXtreme plus raster.
Layouts save to a **workspace `.wor` file**, device config to a **`.hwc` hardware
configuration file**, with a "Load workspace on startup" option. Live measurement writes
`.nmf` (measurement), `.nbl` (binary) and `.pcap` (packet trace) simultaneously.
([Nemo Outdoor 8.01 user guide](https://pdfcoffee.com/nemo-outdoor-801-user-guide-pdf-free.html))

Distinctive feature worth stealing: **multi-route** — plot two terminals' routes at once, or
two parameters from one terminal, for direct comparison.

#### 1.1.3 XCAL / XCAL5 (Accuver)

From the XCAL-M user guide, the main window decomposes into: **Menu Bar** (File, Setting,
Window, Help — *"menu items vary depending on supported technologies"*), **Icon Bar**,
**Workspace** ("display various parameter windows"), **Status Bar** (slot, mobile, GPS
state), and **Worksheet** — *"saves currently opened windows and their settings in a
worksheet. You may create max 30 worksheets."*

Thirty worksheets. That is the same concept as TEMS's ten, with three times the ceiling.

Named windows, from the guide's contents:
- Messages group: **Signaling Message**, **Alarm Event Manager**, **Packet Message**,
  **Packet Capture Viewer**, **PPP Frame Message**
- Statistics/Status group: **Call Statistics (Current Scenario)**, **Call Statistics (All
  Scenario)**, EVDO Session Assignment Test, **Ping Status**, **TraceRT Status**,
  **Throughput Info**, **GPS Status**, GPS Satellite Status, **Logging Info**,
  Communication Statistics, QPCH Statistics, **Network Info**
- User Define group: **Graph**, **Table**, **Summary Info**, **Cell Measurement**
- **Real Time Mapping Window** — a separate map subsystem with its own engine choice
  (MapX / MapXtreme / Smart Map), map control icons, a **Trace** control that steps
  10 seconds back/forward, and BTS/Repeater/Serving Line/Coverage overlays.

The Icon Bar carries port setup, start/stop logging, replay, and the trace scrubber.
Installation includes *"Installing Security Program (Key Lock Driver)"* — i.e. a hardware
dongle. Supported OS in that revision: Windows XP/Vista/7/8/8.1.
([XCAL-M User Guide v3.3.4](http://www.accuver-emea.com/download/doc/xcal/XCAL-M_User_Guide_v3.3.4.xx_(rev0).pdf))

#### 1.1.4 R&S ROMES4

Weakest source coverage of the set — the operating manual is ~2,575 pages and not readily
extractable. What is confirmed: ROMES4 is the **"Expert UI"** of the R&S stack, paired with
a web-based **"Standard" UI** (SmartBenchmarker) for simpler tasks; it is workspace- and
project-based; it has **Alphanumeric View** and **Route Track View** among its view
templates. ([R&S ROMES4 operating manual listing, sekorm](https://en.sekorm.com/doc/3083591.html),
[R&S ROMES4 product page](https://www.rohde-schwarz.com/us/products/test-and-measurement/network-data-collection/rs-romes4-drive-test-software_63493-8650.html))

Treat ROMES4's view list here as incomplete. If it matters commercially, get the manual.

### 1.2 Post-processing workstations

#### 1.2.1 XCAP (Accuver)

Six display types: **Graph**, **Map**, **Table**, **Message**, **CDF/PDF**, and a
**Display Manager** that controls parameter visibility, ordering and legends. Message view
shows signalling and packet messages with hex. Map does route traces, BTS positions and
binning. Import accepts Qualcomm DM, Nortel SBS, TEMS, NEMO and Tektronix logs. Output:
single-parameter reports, auto-reports into Excel workbooks, full reports in PDF/Excel/DOC/HTML.

The single most useful number found in this entire research pass, from Accuver's own
training deck — **"Log File Size (WCDMA) → Processing Time (approx.)"**:

| Log size | Processing time |
| --- | --- |
| 1 GB | ~10 min |
| 2 GB | ~30 min |
| 3 GB | ~58 min |

That is markedly superlinear: tripling the file sextuples the wait. The deck's own mitigation
is *selective parsing* — deselect GPS, packets or the message viewer at import time to make it
finish. In other words, the vendor's advice for a slow import is to throw data away.
([XCAP training deck, SlideShare](https://www.slideshare.net/SandeepYadav71/xcap-training-201102))

#### 1.2.2 Nemo Analyze (Keysight)

**Workbook**-centric: a workbook is a collection of **data views** across **pages**;
views are graphs, grids, numerical views and maps, time-synchronised. A **Parameters**
panel lists all available parameters and KPIs for the selected file/measurement/folder.
Custom KPIs are user-definable. Ships ready-made report templates and "playback workbooks".
Backed by PostgreSQL for concurrent uploads.
([Nemo Analyze 8.90 user guide](https://www.scribd.com/document/738580118/Nemo-Analyze-8-90-User-Guide),
[Keysight Nemo Analyze product page](https://www.keysight.com/us/en/product/NTN50046C/nemo-analyze-drive-test-post-processing-solution.html))

#### 1.2.3 TEMS Discovery Device (Infovista)

Two-panel workspace: **Data Explorer** on one side, synchronisable views on the other.
Data selector for views and tasks; multiple workspaces so several logfiles can be worked at
once; map views with route display and binning.
([Infovista TEMS Discovery](https://www.infovista.com/products/tems-discovery/network-testing-analytics),
[TEMS Discovery Device 10.0 User Guide](http://www.adinstruments.es/WebRoot/StoreLES/Shops/62688782/5693/E6FF/573F/45D5/D3B3/C0A8/2BB9/6409/TEMS_Discovery_Device_10.0_User_Guide.pdf))

#### 1.2.4 NQDI / SmartAnalytics (R&S, ex-SwissQual)

NQDI presents *"time-synchronized views using maps, message monitors, grids, line graphs,
bar graphs, pie charts, tables and hierarchical lists"*; deployable on-prem or cloud.
SmartAnalytics is the newer ML-flavoured successor.
([SwissQual NQDI](https://tele-tools.com/product/swissqual-nqdi/),
[R&S SmartAnalytics](https://www.rohde-schwarz.com/us/products/test-and-measurement/network-data-analytics/smartanalytics_63493-528448.html))

#### 1.2.5 AirScreen (QTRUN) — the interesting outlier

Windows post-processing tool from the NSG vendor, built on **QGIS**. Reads NSG logs.
An engineer on telecomHall describes it exactly the way a challenger product wants to be
described: *"A post processing tool. Same like Actix, XCap, TEMS discovery, etc. **It is
very fast compared to others**, but of course don't have all capabilities."*

And, three years later in the same thread, the limitation: asked whether it reads TEMS
`.trp`, the answer is *"No, it does not support trp extension; It just supports logs from
NSGURU application installed on UE."*
([telecomHall: AirScreen — Post Processing Tool alternative](https://www.telecomhall.net/t/airscreen-post-processing-tool-alternative/12828))

Read those two quotes together. Speed wins attention; format lock-in caps the addressable
market. That is the whole strategic lesson of this document in two forum posts.

### 1.3 Handheld tools

#### 1.3.1 TEMS Pocket (Infovista)

The most completely documented handheld UI, and the reference design the others follow.

- **Action bar** immediately below the Android status bar, context-dependent buttons:
  change data view category, insert filemark, start/stop log recording, start/stop script,
  screenshot, settings, overflow menu. On start-up it opens **a cell list data view for the
  RAT currently in use**.
- **Data views** organised into named **categories**: Idle, Dedicated, Scanning, Data,
  Test Status, Location, Wi-Fi, Messages, Custom, Statistics. Named views inside them
  include GSM/WCDMA/LTE/5G NR **Cell List** and **Cell Line Chart**, **LTE Cell
  Configuration**, **GSM/WCDMA/LTE RACH Analysis**, **LTE MIMO Measurements**,
  **eNB TX Antenna Difference**, **Scanning Status**, per-ARFCN/EARFCN scan views,
  **LTE PHY Throughput**, **PDP Context Information**, **Script Progress**, and a whole
  family of per-service progress views (FTP, HTTP DL/UL, YouTube, Iperf, Ping, SMS, Voice,
  Email, Facebook, Instagram, Twitter, Logfile Upload).
- **Messages category**: **Events**, **Layer 3 Messages**, **SIP Messages**.
- **Map views** are a separate chapter: **Outdoor Map** (route markers, line to serving cell
  with **beam width highlighted**, cell sectors lettered by RAT — C/L/N; simplifies cell
  plotting to black squares when zoomed out "for reasons of readability and performance";
  can inhibit tile downloads mid-measurement so map traffic does not pollute throughput
  results) and **Indoor Map: Pinpointing** with iBwave transmitter file support.
- **Custom** category: up to five user-built views. You select a rectangular area of a grid
  and drop in a line chart, a **value bar** (length + colour encode the value, text printed
  on top), a **value label**, or a static text label. The manual's example: LTE and WCDMA
  signal metrics side by side, "ideal for studying 4G–3G RAT transitions".
- **Interaction detail worth copying**: the Events and Layer 3 views auto-scroll live; you
  **freeze them by dragging the list gently downward**, a blue notification bar then counts
  how many new items arrived while frozen, and "Scroll to top" returns to live. Logging is
  unaffected by view state.
  ([TEMS Pocket 22.0 TPD §4, §11, §12](https://infocom.haradacorp.co.jp/wp/wp-content/uploads/2020/10/TEMS-Pocket-22.0-Technical-Product-Description.pdf))

#### 1.3.2 QualiPoc Android (R&S)

Workspace of **monitors**, swipe-to-next-tab, user-creatable custom monitors. Named
monitors: **Status** (operator, technology, GPS, test statistics), **Log**,
**WCDMA/GSM/LTE** (serving cell per active technology), **HSDPA/HSUPA**, **GPRS/EDGE**,
**LTE DL / LTE UL**, **Cells** (serving + neighbours, content varies with technology),
**Coverage line-chart**, **Test** (on-going test summary including a **KPI bar chart**),
**Layer3** (list of L3 headers, each expandable to a decode), **IP** (HTTP, FTP, TCP, DNS,
ICMP), **Events**, **Map** (Google or OSM, position/route/BTS), **Indoor** (floor plan with
signal markers). A **Main Toolbar** slides up from a bottom arrow for idle monitoring,
screen capture and settings.
([QualiPoc Android manual 12.0.0](https://idoc.pub/documents/manual-qualipoc-android-1200-od4pwwdzjdnp))

R&S's own claim for the live decode: *"The QualiPoc signaling monitor shows all L3 messages
and offers message decoding in real time. Multiple filters allow fine-tuning of the data on
the monitor."*
([R&S QualiPoc Android](https://www.rohde-schwarz.com/us/products/test-and-measurement/network-data-collection/qualipoc-android_63493-55430.html))

#### 1.3.3 Nemo Handy (Keysight)

Navigation: **horizontal swipe** between views, **vertical swipe** between pages of a
multi-page view, or **tap the page header for a popup shortcut menu**. A customisable
**home view** of shortcuts, returned to by tapping the Handy icon in the action bar.

Named views: **Status**, **Script**, **GPS**, **Notification History**, **Map** (BTS
locations, serving-cell lines, routes, markers), **Indoor Map** (iBwave, DAS), **BTS**,
**Signaling**, **Voice Quality** (POLQA/PESQ), **Transaction Log**, **Nemo Cloud**,
**E2E**, **RF Ingress Analysis**, **WiFi**, and per-app views for **YouTube**, **Facebook**,
**LinkedIn**, **Twitter**, **Instagram**. Per-RAT families: Summary, Cell measurements,
Cell table, Inter-system cells, Power control, Throughput, PPP throughput, IPerf, HSDPA,
HSUPA, LTE Link adaptation DL/UL, CA cell and CA throughput, ROHC.

**Custom View** with user-chosen parameters and "splits". Long-press a parameter name for a
menu offering **Set Active** (change which metric is graphed), **Auto Scale**, and
**Number Format**.
([Nemo Handy 3.30 user guide](https://pdfcoffee.com/nemo-handy-330-user-guide-pdf-free.html);
marketing claim of *"the best real-time measurement visualization on the handheld market"*
from the [Nemo Handy flyer](https://www.keysight.com/us/en/assets/7018-05575/flyers/5992-2050.pdf))

#### 1.3.4 XCAL-Mobile (Accuver)

Main screen is a **launcher**, not a data view: Classic / All Scenario tabs, swipe left–right
to pick call type, a combo box for AutoCall scenario, scenario detail, a big Start AutoCall
button, and four entry points — map, RF information, real-time test result, AutoCall.

The RF information section is the drive screen. You reach view types by **swiping down the
green bar at the top of the screen** to reveal a grid of view-type icons; then **swipe
left/right between screens and up/down within a screen**. Named views: **Android RF**,
**WiFi Info**, **Signal Messages**, **External DM Summary** (XCAL-Solo hardware),
**3G Summary / 3G Signal / 3G Cell**, **LTE Summary / LTE Signal / LTE Cell**,
**LTE Tx Power** (cumulative Tx-power histogram), **LTE SIB1/2/3/8**, **RTP Info / RTCP
Info**, **GSM Summary / Signal / Cell**, **CDMA Summary / Signal / Cell**, **EVDO Cell**.

The Summary/Signal pairing is systematic: *Summary* = KPIs in a table, *Signal* = the same
KPIs as a graph, *Cell* = serving and neighbour list.

A **Status Icon Bar** at the upper right carries traffic-light indicators for AutoCall,
Logging File, DM Data Collection (green = data flowing from modem), GPS, Master/Slave (the
number of boxes in the icon = number of connected slave handsets), and IP Frame.

Also present: Master/Slave multi-handset testing, Inbuilding test with moving-point and
fixed-point pinpointing, Replay, Screen Capture, log split, TTS alarms, log upload.
([XCAL-Mobile 4G for Android user guide v4.7](http://www.accuver-emea.com/download/doc/xcal-mobile/XCAL-Mobile_4G_(for_Android)_User_Guide_v4.7.190.pdf))

Note the **Signal Messages** description carefully: *"Signal Messages screen shows RRC
messages of corresponding technology… Tap a message from Signal Message list, and
corresponding code is shown."* Code, not decode. See §2.

#### 1.3.5 NSG — Network Signal Guru (QTRUN)

Structure, from the NSG manual:

- **Initial View** — on start, the cell list data view for the current RAT.
- **Action Bar** at the top, below the Android status bar, context-dependent buttons; shows
  network and Testing/halted status. *"From here you can select what data view to show,
  inspect various other categories of data, and perform all of the actions and configuration
  tasks."*
- **Data View Header** — a persistent band of serving-cell data at the top of every view;
  per-RAT contents (GSM: ARFCN, BSIC, RxLev, C/I, PLMN, LAC, CellID). Also shows a fine-
  grained **data mode** indicator (LTE / LTE CA / HSPA+ DC MIMO / …) explicitly noted as
  "much more fine-grained than the one given on the Android status bar".
- **Data views**, browsed by **swiping left and right**, with a **row of position indicators
  along the bottom edge** counting only views belonging to the current RAT. **View switching
  between RATs is automatic** — the phone changes RAT, the view family follows. Invalid
  parameters render as a dash. Tapping a graph hides/shows its legend.
- Per-RAT view families. LTE: **LTE Cell Table**, **LTE CA Matrix** (carriers as a matrix),
  **LTE Cell Configurations**, **LTE Dedicated Mode**, **EUTRA Sessions**, **LTE MIMO**,
  **LTE RACH and VoLTE Analysis**, **LTE Cell Graphing** (RSRP / RSSI / SINR / PUSCH Tx
  Power, each with legend), **LTE Signaling**. Equivalents exist for GSM, WCDMA, TD-SCDMA,
  CDMA, CDMA2000/1xEV.
- The **RACH/VoLTE view** is the standout for depth: RRC state, RACH reason, ordered vs
  current preamble Tx power, Max Preambles and Preamble Step *pulled from SIB2*, retry
  counter, contention-free vs contention-based, and an explicit result enum —
  `{Success, Failure at MSG2, Failure at MSG4 due to CT timer expired, Failure at MSG4 due
  to CT resolution not passed, Aborted}` — plus, during a VoLTE call, AMR rate and codec
  DL/UL, SSRC, payload size, RTP jitter, IPDV per RFC 3550 §6.4.1, and packet loss derived
  from RTP sequence discontinuity.
- **Outdoor Map View** with measurements, events and cell sites; OSM and China map sources;
  cell display range and additional cell info configurable.
- **Testing Scripts**, edited from a panel at the bottom of the screen: Voice MO/MT, FTP
  up/down, HTTP GET/POST. One active script at a time — *"user can only run one test
  scenario once. For instance, if you'd like to run ftp and voice, you need two scripts
  separately."*
- **Data View Synchronization** — tap the lock button and a control panel appears at the
  bottom giving step-forward, step-backward and time-seek across *all* views at once.
  *"It's something like a replay function… you can discover what is happening in the past
  testing time."* The same slider drives **logfile replay** (load from the left drawer; the
  action bar shows replaying status).
- **Forcing features**: RAT lock, band lock (per RAT, with a Snapdragon-835-specific path),
  LTE/WCDMA cell and frequency locking, clear forcings.
- **Dual SIM**: since v1.3 the two SIMs get separate data views rather than the views
  flipping between them.
- Post-processing story is explicitly *"take it elsewhere"*: NSG raw, Qualcomm, JSON and
  text formats, for Actix Analyzer, Qualcomm QCAT and TEMS Investigation.
  ([NSG User Manual, Aug 2017](https://m.qtrun.com/docs/NSG_Manual_Aug_2017.pdf);
  [NSG online help](https://www.qtrun.com/help/1Introduction.html);
  [QTRUN NSG product page](https://www.qtrun.com/eng/nsg/))

**A finding the team should absorb.** NSG's manual is a lightly edited copy of the TEMS
Pocket technical product description. Compare:

> TEMS Pocket TPD §1.2: *"TEMS Pocket is designed as an integral part of the device's user
> interface. This promotes continuous use by engineers and technicians…"*
>
> NSG manual §1: *"NSG is designed as an integral part of the device's user interface. This
> promotes continuous use by engineers and technicians…"*

And §4 of the TPD versus §5.2 of the NSG manual are the same sentence about the action bar,
word for word. NSG did not invent the handheld drive-test UI; it cloned Infovista's and then
out-executed it on modem depth and price. That is a reproducible strategy, and it is
evidence that **the data-view/swipe/action-bar idiom is a de facto standard** an engineer
already knows how to use. Deviating from it is a cost, not a feature.

It also corrects two claims in `docs/COMPETITIVE-LANDSCAPE.md`:

- *"NSG … decode ON THE HANDSET … That is its signature capability."* — TEMS Pocket does
  full human-readable L3 expansion on device ("you can tap a message… and immediately see
  the full contents of the message in a human-readable format… troubleshoot signalling
  issues directly in the field, for example by viewing MIB or SIB configurations"), and
  QualiPoc advertises real-time decoding with filters. On-device decode is table stakes in
  this tier, not NSG's moat.
- *"NSG writes to the modem… XCAL controls the phone… NSG reaches into the modem's radio
  selection."* — TEMS Pocket ships RAT lock, band lock, EARFCN/PCI lock, WCDMA cell lock,
  GSM cell lock/prevent, voice codec lock, cell barred lock, access class lock, fast
  dormancy control, APN change and attach/detach. NSG's real advantage is that it does this
  on **self-serviced rooted retail handsets** rather than on a vendor-provisioned device
  list — an access-model advantage, not a capability one. Worth fixing before that doc is
  shown to anyone.

### 1.4 The free tier

#### 1.4.1 G-NetTrack Pro (Gyokov Solutions) — $34.99 one-time

**Five tabs: CELL, NEI, MAP, INFO, DRIVE.**

- **CELL** — network + geographic identity (Operator, MCC, MNC, LAC, NODE, CID, PCI/PSC/BSIC,
  ARFCN, band, TA, Type, LEVEL, QUAL, SNR, CQI), position (lat/lon/speed/accuracy/height/
  altitude), UL/DL rate, data path, phone state — plus **Serving Time** and a **serving-cell
  history table** with time and level at each change. That history table is the tab's real
  purpose: it makes cell-reselection thrash and coverage holes (rows with level = −201)
  visible at a glance.
- **NEI** — serving cell table + neighbour table, with **PCI collision flags** rendered as
  `!` (mod3), `!!` (mod6), `!!!` (mod30) and the serving-to-neighbour level delta.
- **MAP** — thematic map of LEVEL / QUAL / CELL / DL / UL / SPEED with distance and bearing
  to serving cell; buttons for thematic metric, **Export** (KML on the fly, without having
  started logging), **Screenshot**, **Clear**.
- **INFO** — log status, IMSI, IMEI, operator/country, roaming, app folder, cellfile name,
  versions, device build, and the voice-sequence counters (calls, successful, blocked,
  dropped).
- **DRIVE** — "the main serving cells information in comfortable format with big font
  letters". A dedicated large-type screen for someone actually driving.

The manual is candid about two limits. Cell geometry: *"to visualize serving and neighbors
cell you need to load cellfile with cell locations. There is no magic way to guess exact cell
locations."* And rendering: there is an **Auto clear log points** setting with a maximum
point count because *"too many log points slow the app"*, and the MAP tab's Clear button
exists because *"if there are a lot of points, it can slow the map view"*.
([G-NetTrack Pro manual](https://gyokovsolutions.com/manual-g-nettrack/),
[Play listing](https://play.google.com/store/apps/details?id=com.gyokovsolutions.gnettrackproplus))

Post-processing is a separate purchase: G-NetLook Pro / G-NetView / G-NetLook Web.

#### 1.4.2 NetMonster — free with ads + IAP, 5M+ downloads, 3.8★ / 11K reviews

Cell list, signal-change visualisation with plain-language explanations of what each
measurement means for reception and theoretical max speed, continuous logging of every cell
connected to, a browsable/filterable cell map with manual location correction, and export.
GSM through 5G SA, with LTE-A and 4G+5G NSA carrier-aggregation detection. Open-source core
([NetMonster Core](https://github.com/mroczis/netmonster-core)).
([Play listing](https://play.google.com/store/apps/details?id=cz.mroczis.netmonster))

#### 1.4.3 CellMapper — crowd-sourced coverage map, ~2,575 reviews

Hamburger menu → Map. Blue circle = you, blue line = link to the serving tower; tap a tower
to see its coverage broken into shaded per-cell sectors; tap a sector for bandwidth, max
signal etc.; colour encodes signal quality.
([Android Police tutorial](https://www.androidpolice.com/cellmapper-coverage-tutorial/),
[cellmapper.net/map](https://www.cellmapper.net/map))
The app wiki is login-gated, so screen-level detail beyond this is unverified.

#### 1.4.4 Cellular-Z — free, 3.9★ / ~2K reviews

Four functional areas: SIM/cell (dual-SIM, serving cell, serving-cell signal quality,
neighbours), WiFi (connected network, nearby list, 2.4/5 GHz channel view), device/location
(GPS NMEA log, battery, hardware, system), and speed test + map track / indoor coverage.
([Play listing](https://play.google.com/store/apps/details?id=make.more.r2d2.cellular_z.play))

---

## 2. Live during the drive vs post-processing only

The dividing line is not where the marketing puts it. Here is what is actually true.

| | Live on screen during the drive | Only after |
| --- | --- | --- |
| **TEMS Investigation** | Everything. All 250+ presentation windows work in recording mode; status windows refresh continuously, maps and line charts accumulate. Message windows list L3/L2 live and freeze on click. | Cross-logfile comparison, campaign statistics, binning at scale — those move to Discovery/Director. |
| **Nemo Outdoor** | Decoded Layer 3 window, parameters, events, statistics, grids, all graph types, map with live route colouring and serving/neighbour lines. Multi-route comparison. | Workbook-level KPI analysis, custom KPIs, report templates — Nemo Analyze. |
| **XCAL** | Signaling Message, Packet Message, PPP Frame, Packet Capture Viewer, all Statistics/Status windows, User Define graph/table, Real Time Mapping with the 10-second Trace scrubber. | Everything statistical and comparative — XCAP. |
| **TEMS Pocket** | Every data view, including **Layer 3 Messages tap-to-expand into human-readable form** and SIP messages "in plain-text decoded form". Outdoor/Indoor map. Script progress. **Instant reports**: PDF generated *"in near real-time directly after a script has been executed"*, viewable on the device. | Cross-session analysis, fleet dashboards, analytics — TEMS Director. |
| **QualiPoc Android** | All monitors; **Layer3 monitor with real-time decoding and filters**; IP monitor; KPI bar chart in the Test monitor; map and indoor. On-device replay via NQView. | NQDI / SmartAnalytics for anything comparative. |
| **Nemo Handy** | Signaling view, all per-RAT parameter views, map with BTS lines, voice quality scores, transaction log, per-app views. Optional **Nemo Instant Report**. | Nemo Analyze. |
| **XCAL-Mobile** | RF views (Summary/Signal/Cell per RAT), LTE SIB1/2/3/8 summary, Tx-power histogram, RTP/RTCP stats, digital map, call result history. **Signal Messages gives you the message list and "corresponding code"** — the raw bytes, not an expanded ASN.1 tree. | The actual decode and all analysis — XCAP. The phone is a collection endpoint. |
| **NSG** | **Full ASN.1 decode on the handset**, plus every RAT-specific view, the CA matrix, MIMO, RACH/VoLTE analysis with SIB2-derived parameters, and the cross-view time-scrub. Logfile replay in-app. | Nothing much — NSG deliberately has almost no post-processing. It exports to Actix/QCAT/TEMS, or to AirScreen. |
| **G-NetTrack Pro** | All five tabs, live KPML export from the MAP tab, voice/data sequence counters. | G-NetLook Pro / Web. No L3 at all, ever — it is an Android-API tool. |
| **NetMonster / CellMapper / Cellular-Z** | Serving + neighbour identity, signal, CA state, map. | Nothing. There is no post-processing tier. |

**The three real distinctions**, stated plainly:

1. **Live decode is now common in the handheld tier** — NSG, TEMS Pocket and QualiPoc all do
   it. It is *not* common in the XCAL-Mobile lineage, which shows you the message name and
   the hex and expects you to open XCAP later.
2. **Live *statistics* are rare everywhere.** Every tool will draw you a live RSRP line. Very
   few will tell you, mid-drive, "your handover success rate on this cluster is 91% and here
   are the four failures". TEMS Pocket's Instant Report and Nemo's Instant Report are the
   only mainstream attempts, and both fire *after a script completes*, not continuously.
3. **Nothing in the handheld tier gives you a live call-flow ladder.** Message lists, yes.
   A rendered ladder diagram of the RRC/NAS procedure with the failure highlighted — no
   source found for any of these products doing that on-device during a drive.

---

## 3. What users actually complain about

Sorted by how much a newcomer can exploit it.

### 3.1 Licensing that fails in the field

The sharpest one, and it comes from the vendor's own release notes:

> **2.1.3.8 License verification enhancements at start-up — with detailed error information.**
> "TEMS Investigation and TEMS Paragon will now provide better information what to do when
> **it has not had contact with a license server for more than 5 days and cannot start.**"
> — [TEMS Investigation 22.3 TPD](https://infocom.haradacorp.co.jp/wp/wp-content/uploads/2020/10/TEMS-Investigation-22.3-Technical-Product-Description.pdf)

A field tool that stops working after five days without internet. The "fix" shipped in 22.1
was a better error message. Licences are cloud-based or "mapped", administered on the GLS
web portal (TPD §1.2.3).

Accuver's XCAL ships a **"Security Program (Key Lock Driver)"** — a USB dongle — per its
install guide. Nemo Analyze is still sold by resellers as a
["License(Dongle)"](https://www.temsnemo.com/index.php/product/analyze-post-processing-solution-dongle/).

The scale of the grey market is itself the complaint. Finetopix / Wire Free Alliance carries
threads for TEMS dongle dumps, emulators and "免狗" (dongle-free) registration files across
versions 9.0.2 to 18.0.1 — plus install reports like *"Installed but crash few minute"* and
*"there is a problem with this windows installer package"* for 15.2.2, 14 and 13.
([TEMS 12.1 dongle dump](https://www.finetopix.com/archive/index.php/t-23447.html),
[TEMS Investigation collection](https://www.finetopix.com/showthread.php/46944-OFFICIAL-TEMS-Investigation-Collection),
[Problem with Install TEMS Investigation 15.2.2](https://www.finetopix.com/showthread.php/39781-Problem-with-Install-TEMS-Investigation-15-2-2))
People do not build dongle emulators for software they find reasonably priced and easy to
license.

### 3.2 Proprietary formats, and being charged to read your own data

telecomHall thread "Tems DT Log Reading": engineers report *"you have to get an API license
\$\$\$ from Infovista in order to directly read the trp files."* The workaround is to open
TEMS, export to text, and work from that. One developer reverse-engineered the container —
`.trp` opens as an archive, contains deflate-compressed `.cdf` files, which decompress to
binary protobuf needing message descriptors that are not published. Several engineers in the
thread asked for the descriptor library. Nobody got it.
([telecomHall: Tems DT Log Reading](https://www.telecomhall.net/t/tems-dt-log-reading/17098))

The mirror image, from the challenger side: AirScreen *"does not support trp extension; It
just supports logs from NSGURU application installed on UE."*
([telecomHall](https://www.telecomhall.net/t/airscreen-post-processing-tool-alternative/12828))

Everyone's format is a moat, and every engineer is standing in one.

### 3.3 Post-processing is slow, and the vendor knows

The XCAP training deck's own table: 1 GB → 10 min, 2 GB → 30 min, 3 GB → 58 min, with
selective parsing offered as the mitigation.
([XCAP training deck](https://www.slideshare.net/SandeepYadav71/xcap-training-201102))
Infovista markets Discovery on its ability to *"post-process and analyze large network
testing data sets"*, which is an admission of the shape of the problem.
([TEMS Discovery datasheet](https://www.slideshare.net/slideshow/tems-discovery-postprocess-and-analyze-large-network-testing-data-sets-datasheet-infovista/264779457))
The telecomHall forum index carries a thread titled *"Nemo analyze 5.13 scanner logfile
parsing slow"*. And the single line an engineer chose to describe AirScreen was *"It is very
fast compared to others"* — speed is the axis people notice.

There is also a whole cottage industry of "reduce your TEMS logfile size" tutorials, which
exists because the tools do not cope well with big ones.
([TEMS Investigation Logfile Configuration to reduce Logfile Size](https://www.youtube.com/watch?v=iCa2srr3888))

### 3.4 Windows-only, and the hardware tail

TEMS Investigation 22.3 supports **Windows 10 and Windows 8 Pro** only; the XCAL-M guide of
its era lists XP/Vista/7/8/8.1. The whole laptop tier is Windows.
HiCellTek's competitive page frames the cost the way a customer would: *"The hardware
footprint (laptop, scanner, cabling, power inverter) is a trade-off for everyday field teams
doing routine KPI collection"*, and dragging *"a laptop and scanner is impractical"* for
indoor walk tests.
([HiCellTek: Best XCAL Alternative 2026](https://hicelltek.com/en/alternative-xcal/))
It is vendor marketing, but the observation is not wrong and it is the pitch customers are
already hearing.

### 3.5 Price, stated by users in their own words

NSG on Google Play — 3.9★, 4.19K reviews, 500K+ downloads:

- *"I would love to be able to lock my phone to a certain pci number but $50 is way to much.
  Who pays that anyways. Please make it $5 or less a month you will get a lot more
  subscribers."* (25 found helpful)
- *"the cost just to stop the pages from scrolling and the ad to go away is completely well
  out of the price range for most of us enthusiasts! I understand that you're targeting this
  to more professionals but there are a lot of enthusiasts like us who use it not for the
  professional features but for simple band locking and cell identifying… $50/month is way
  too much."* (103 found helpful)

And QTRUN's own store description, which is a positioning statement worth reading twice:

> *"What that NSG team is doing right now is to lower the cost of network maintenance,
> optimization and engineering processes. Currently a lot of test tools provided in the
> market are very expensive, **some of those are much more expensive than a basestation**."*

([NSG on Google Play](https://play.google.com/store/apps/details?id=com.qtrun.QuickTest))

For calibration at the other end: a reseller lists a TEMS Investigation V28.x GLS licence at
**$2,250–$2,300** ([temsnemo.com](https://www.temsnemo.com/index.php/product/tems-investigation-gls-license-reliable-5g-network-testing/)) —
that is a module, not a full multi-technology seat, and full seats are widely reported in the
five figures.

### 3.6 The rooted-handset tax (NSG specifically)

The XDA NSG thread is a multi-year log of the platform fighting the tool. Selected, with
dates:

- Feb 2020: NSG needs a **userdebug `vendor.img`**; flashing it costs Active Edge (squeeze)
  and breaks DSDS. *"It seems like Google broke a bunch of drivers in the February userdebug
  version of vendor.img"* (Ingenium13).
- Mar 2020: *"Just flashed March yesterday, nope still does not work without the userdebug
  vendor image. On top of that custom kernels seem to cause issu[es]"* (ryaniskira).
- Apr 2020: a working recipe on Android 11 DP3 with Magisk Canary — followed immediately by
  *"Let's hope Google doesn't 'fix' that."*
- Sep 2020: *"NSG also doesn't work as of Beta 3."* (cstark27)
- A real product bug: *"**DO NOT use 'Clear Forcings'** — it will currently cause you to lose
  LTE bands at reboot and airplane mode toggles"*, with a `*#*#4636#*#*` → toggle DSDS →
  reboot workaround.
- Version fragmentation: *"how to get NSG 3.7 or above? Cause latest google play version is
  2.12."*
- Users landing on the failure mode: *"With the vendor img i use i open up NSG and get **N/A
  testing** at the top and it does not pick up anything."*

([XDA: Network Signal Guru, page 4](https://xdaforums.com/t/network-signal-guru.4005511/page-4);
also [ISPreview thread](https://www.ispreview.co.uk/talk/threads/network-signal-guru.39242/))

QTRUN's own device page documents the tax: Huawei Kirin needs a custom ROM; **Samsung Exynos
needs a token issued by Samsung, "issued by Samsung not us"**, to recover the diag port after
root. ([qtrun.com/eng/nsg](https://www.qtrun.com/eng/nsg/))

### 3.7 Free-tier complaints

- **G-NetTrack Pro**, 4.5★/703: *"The only negative thing about the app is there is no way to
  export the metrics log in CSV."* The developer's reply — the text log is tab-delimited, open
  it in Excel — is the answer of someone who has not felt the pain of a downstream toolchain.
  Also a stuck-metric bug report (*"the ECNO is now stuck on -20"*) and cellfile loading that
  needs a remove-and-repaste to recover.
- **NetMonster**, 3.8★/11K: *"it fails to recognize 5G++ carrier aggregation. The app only
  displays a single band connection, even though the status bar indication and blazing data
  speeds clearly prove carrier aggregation is active"*; and *"The feature to estimate cell
  location (premium) does not work for 5G SA / 5G+. I've tried reporting this to the
  developer but I've not had any acknowledgement."*
- **CellMapper**: *"The UI makes it unpleasant to use"* — unclear registration, login field at
  the bottom of the screen behind the keyboard, browser-ad spam on first open that reopens
  after force-close; plus battery drain from continuous GPS and a screen that will not sleep,
  and recurring doubts about tower-location accuracy.
  ([AppGrooves negative reviews](https://appgrooves.com/android/cellmapper.net.cellmapper/cellmapper/cellmappernet/negative))
- **G-NetTrack**'s structural limit, from XDA: without a cellfile *"the app is only useful for
  viewing the serving cell connected to the phone"*, and metrics like ECNO simply are not
  reported by most handsets through the Android API.
  ([XDA G-NetTrack thread](https://xdaforums.com/t/app-2-2-g-nettrack-gsm-umts-drive-test-tool-for-android-os.1751074/))

### 3.8 What I could *not* find

Being straight about the negative results, because they matter for how much weight to put on
§3:

- **No public complaint corpus for QualiPoc, ROMES4 or SmartAnalytics.** R&S's customers are
  operators under NDA; they complain in support tickets, not on forums.
- **Reddit was inaccessible** from this environment (blocked by policy), so the r/RFElectronics
  / r/telecom voice is missing from this pass.
- **G2 review pages** for TEMS Suite and Actix Analyzer would not render. Worth a manual look.
- **No hard data on TEMS Investigation crashing on large logs** beyond scattered install-crash
  reports on Finetopix. The "slow post-processing" complaint is well evidenced; "crashes on
  large logs" is folklore I could not source.

---

## 4. Daily drivers vs demo-ware

### Used every day

1. **The map with route markers colour-coded by one metric.** Universal. It is the first thing
   opened and the thing screenshotted into the report. Everything else is drill-down from a bad
   patch on the map.
2. **The serving + neighbour cell list.** TEMS Pocket opens on it. NSG opens on it. XCAL-Mobile,
   QualiPoc, Nemo Handy, G-NetTrack, NetMonster all have it as a primary tab. It answers "what
   am I on and what should I be on".
3. **The L3 message list, filtered.** Not browsed — *filtered*, to a handful of message types,
   and read around a failure timestamp. TEMS's per-message-type filter and click-to-freeze, and
   TEMS Pocket's drag-to-freeze, exist precisely because the unfiltered firehose is unusable.
4. **One line chart with three or four series** — serving RSRP/RSRQ/SINR plus one neighbour, or
   throughput plus BLER.
5. **The event list.** Drops, blocks, handover failures, RLF. The list of things that went wrong
   is the actual deliverable.
6. **A serving-cell history / reselection table.** G-NetTrack's CELL tab makes this its centre;
   it is where cell thrash becomes obvious.

The practitioner blog at DriveTester.US is a clean corroboration of this ranking. His stack:
G-NetTrack Pro auto-logging to a server, an **Elasticsearch instance for the KPI maps**, and
then — only when the map shows something — *"note the timestamps and dig deeper using Network
Signal Guru and Airscreen"*, with CellMapper for tower reference. What he looks at: RSRP, RSRQ,
SINR, band, bandwidth, CA state, download/ping stats, BLER, beam tables, and radio-link failure
messages. Map first, signalling second, on a timestamp.
([DriveTester.US, Drive Testing Methods Part 1](https://drivetester.us/drive-testing-methods-part-1/))

Note what he did *not* use: any of the incumbent post-processors. He built a KPI map in
Elasticsearch instead. That is a customer telling you what the gap is.

### Demo-ware

- **"More than 250 predefined presentation windows."** No engineer uses 250 windows. They use a
  worksheet someone set up years ago and never touched again. The number is a procurement
  weapon; the workspace file is the real product. (Which is why workspaces being *shareable*
  matters more than the window count.)
- **The per-app social-media views.** Nemo Handy ships **Facebook, LinkedIn, Twitter and
  Instagram views**. TEMS Pocket ships Facebook, Instagram and Twitter progress views. These
  exist for benchmarking RFPs and for slides. A cell-site engineer chasing a bad handover will
  never open them.
- **Exotic per-technology views for dead RATs.** TD-SCDMA, cdma2000, EV-DO Rev B, GPRS/EDGE
  RLC throughput. Present in every product's view list, kept for the long tail, opened by
  almost nobody in 2026.
- **CDF/PDF statistical views.** Real in an acceptance report; rarely open during a drive.
- **Scanner-specific views** — genuinely essential to the small population doing calibrated
  interference and pilot-pollution work, and irrelevant to everyone else. Not demo-ware, but
  badly mis-weighted in the UI: they occupy first-class real estate for a minority use case.
- **KPI dashboards inside the collection tool.** Every product has one; the actual reporting
  happens in Excel, or in the operator's own BI stack, because that is where the template the
  customer signed off lives.

The honest summary: roughly **six views carry the work, and the products ship two hundred**.
The surplus is not free — it is the reason the UI is described as dense, the reason training
courses exist, and the reason a new engineer's first week is spent arranging windows.

---

## 5. The gap

Not "prettier". Five workflow failures, ranked by how badly the incumbents handle them and
how cheaply a newcomer can do better.

### 5.1 The 30-second answer

Today's loop: drive → stop → copy a multi-GB proprietary log to a workstation → import (10 to
58 minutes) → open a workspace → find the timestamp → read the messages → write it up. The
question being answered is almost always small: *did this cell fail, why, and where*.

Nobody has made the small question cheap. TEMS Pocket's and Nemo's Instant Reports are the
closest, and they are PDFs fired after a script completes.

The gap is **a report that exists the moment the drive ends, is opened by double-clicking,
needs no import and no licence, and answers the six-view question set** — map, cell history,
KPI timeline, event list, procedure outcomes, call flow. FieldTap already emits precisely
this: `report.html` with Session, Route, Radio, Procedures, Events, Serving cells, Traffic
tests and Call flow sections, plus `summary.json`, `events.csv`, `kpi.csv`. The incumbents'
answer to "can I email that to my RF lead" is "install our client and buy a seat"; a
self-contained HTML file's answer is "yes".

**This is the strongest card in the hand and it is already built.** It should be the headline,
not a by-product of the capture tool.

### 5.2 Open output as a product feature, not an afterthought

Every incumbent's format is a moat, and the moat is visibly resented: `.trp` needs a paid API
licence to read; AirScreen reads only NSG logs; XCAP reads five formats and emits its own.
Meanwhile FieldTap's decode is **Wireshark's**, and the output is **pcapng that stock
Wireshark dissects**.

That is a categorical difference, not a feature. It means:
- The customer's existing tooling works on day one — tshark, Lua taps, CI, whatever they have.
- No format reverse-engineering thread will ever be written about you.
- The claim "your data stays yours, in a format with a 25-year-old open dissector behind it"
  is one no incumbent can match without abandoning their moat.

Lean on it hard. Make `capture.pcapng` a headline deliverable in the marketing, not row two
of a table.

### 5.3 Live *statistics*, not live values

Everything shows live values. Almost nothing shows live *derived state*: attach succeeded,
handover 47 failed with cause X, RACH is failing at MSG4 on contention resolution, you have
lost the anchor. NSG's RACH/VoLTE view is the honourable exception, and it is one view among
dozens; TEMS Pocket has no equivalent live procedure-outcome view at all.

`fieldtap/events.py` (533 lines) and `flow.py` already compute procedures and failures with
3GPP causes. **Rendering that live, during the drive, is a differentiator nobody in the
handheld tier currently offers.** The screen you want is not a message list — it is a running
list of *procedures with outcomes*, newest at top, failures in red, each expandable to the
messages that produced it. That is the call-flow ladder as a live view, which per §2 is a
white space across the whole field.

### 5.4 The modem-module opportunity, and its UI consequence

FieldTap's capture backend may be a **USB modem module rather than a phone**. Every constraint
in §3.6 evaporates: no root, no `vendor.img`, no Magisk, no Samsung token, no OS update
breaking the tool next month, no "N/A testing" at the top of the screen. That removes NSG's
single largest source of user pain and the procurement objection that follows it.

But it also removes the on-device screen, and with it the entire handheld UI idiom of §1.3.
The honest reframe: **you are not building a handheld tool with a worse phone. You are
building a laptop-class tool with none of the laptop-class baggage** — no dongle, no
Windows-only binary, no five-day licence timeout, no scanner cabling, no import step.

That points the UI at a **local browser page served by the capture process**, not at a
desktop MDI app and not at a phone screen. Live view and report become the same artefact at
two points in time — which is exactly the thing no incumbent has, because their live UI and
their post-processing UI are different products from different codebases with different
licences.

### 5.5 Multi-device without a fleet-management purchase

`fieldtap auto` already handles several phones at once, each with its own session and report.
In incumbent-land that capability is called Master/Slave (XCAL-Mobile), Multi-device TEMS
Pocket, or TEMS Director, and it is an upsell — Director is a separate central management
system with fleet dashboards and its own licence. A tool where "plug in four modems" is just
the default behaviour is a materially different offer to a team doing cluster acceptance.

### 5.6 What *not* to try to out-build

- **XCAP/Discovery-class analytics.** A decade of engineering, and the customer already owns
  one. Interoperate; do not compete.
- **Scanner integration.** Calibrated wideband scanning is hardware, and Keysight/R&S own it.
- **View count.** The lesson of §4 is that 250 windows is a liability. Ship six good ones and
  a genuinely good custom-view builder — TEMS Pocket's grid-mosaic model (select a rectangle,
  drop in a line chart / value bar / label) is the best design in the field and is worth
  copying outright.
- **Opaque naming.** Already covered in `COMPETITIVE-LANDSCAPE.md` and correct.

### 5.7 Small UI details worth stealing outright

- **Drag-down to freeze a live list**, with a counter of items queued while frozen and a
  "scroll to top" to resume (TEMS Pocket). Better than a pause button because it is
  discoverable by accident.
- **Cross-view time scrub** — one slider that moves every view to the same instant, working
  identically in live-with-history and in replay (NSG's Data View Synchronization; TEMS's
  "select an instant in a map or line chart and status windows follow").
- **Route markers encoding three values at once** via size, colour and shape, with multiple
  marker layers in parallel (TEMS Investigation).
- **A big-type DRIVE screen** for the person who is actually driving (G-NetTrack).
- **Rendering caps as a first-class setting** — max points on map, auto-clear, log reduction
  factor (G-NetTrack); zoom-dependent simplification of cell plotting (TEMS Pocket). Any map
  view will need these, and finding out late is expensive.
- **Inhibit map-tile downloads during measurement** so the map does not contaminate the
  throughput result (TEMS Pocket). A subtle, correct detail that shows the designers had done
  a drive test.
- **A `Summary` / `Signal` / `Cell` triplet per RAT** — same KPIs as table, as graph, and as
  neighbour list (XCAL-Mobile). A clean, learnable convention.

---

## 6. Corrections to `docs/COMPETITIVE-LANDSCAPE.md`

Flagged here so they are not lost:

1. **On-device live decode is not unique to NSG.** TEMS Pocket expands L3 messages into
   human-readable form on the handset; QualiPoc advertises real-time decoding with filters.
   NSG is *deeper* (CA matrix, RACH/VoLTE analysis, SIB-derived parameters) but not alone.
2. **Modem write access is not unique to NSG.** TEMS Pocket ships RAT/band/EARFCN/PCI/cell
   locks, codec lock, access class lock, fast dormancy control, attach/detach. NSG's genuine
   edge is the **access model** — self-serviced root on retail handsets rather than a
   vendor-provisioned device list — which is a distribution advantage, and one that a USB
   modem backend neutralises entirely in FieldTap's favour.
3. **XCAL-Mobile does show L3 on device**, via its Signal Messages view — but as message name
   plus "corresponding code", i.e. bytes. The "phone is a collection endpoint" framing is
   right; the reason is decode depth, not absence of a message screen.

---

## 7. Sources

**Manuals and technical product descriptions**
- [TEMS Investigation 22.3 Technical Product Description](https://infocom.haradacorp.co.jp/wp/wp-content/uploads/2020/10/TEMS-Investigation-22.3-Technical-Product-Description.pdf)
- [TEMS Pocket 22.0 Technical Product Description](https://infocom.haradacorp.co.jp/wp/wp-content/uploads/2020/10/TEMS-Pocket-22.0-Technical-Product-Description.pdf)
- [NSG User Manual, Aug 2017 (QTRUN)](https://m.qtrun.com/docs/NSG_Manual_Aug_2017.pdf) · [NSG online help](https://www.qtrun.com/help/1Introduction.html)
- [XCAL-Mobile 4G (Android) User Guide v4.7](http://www.accuver-emea.com/download/doc/xcal-mobile/XCAL-Mobile_4G_(for_Android)_User_Guide_v4.7.190.pdf)
- [XCAL-M User Guide v3.3.4](http://www.accuver-emea.com/download/doc/xcal/XCAL-M_User_Guide_v3.3.4.xx_(rev0).pdf)
- [QualiPoc Android manual 12.0.0](https://idoc.pub/documents/manual-qualipoc-android-1200-od4pwwdzjdnp)
- [Nemo Handy 3.30 User Guide](https://pdfcoffee.com/nemo-handy-330-user-guide-pdf-free.html) · [Nemo Handy flyer](https://www.keysight.com/us/en/assets/7018-05575/flyers/5992-2050.pdf)
- [Nemo Outdoor 8.01 User Guide](https://pdfcoffee.com/nemo-outdoor-801-user-guide-pdf-free.html)
- [Nemo Analyze 8.90 User Guide](https://www.scribd.com/document/738580118/Nemo-Analyze-8-90-User-Guide)
- [TEMS Discovery Device 10.0 User Guide](http://www.adinstruments.es/WebRoot/StoreLES/Shops/62688782/5693/E6FF/573F/45D5/D3B3/C0A8/2BB9/6409/TEMS_Discovery_Device_10.0_User_Guide.pdf)
- [G-NetTrack Pro manual](https://gyokovsolutions.com/manual-g-nettrack/)
- [R&S ROMES4 Operating Manual listing](https://en.sekorm.com/doc/3083591.html)

**Training decks and walkthroughs**
- [XCAP training deck (processing-time table)](https://www.slideshare.net/SandeepYadav71/xcap-training-201102)
- [TEMS Investigation 15.x training](https://www.scribd.com/doc/239063121/TEMS-Investigation-15-x-Training)
- [Nemo Outdoor GUI tutorial](http://rfoptimisation.blogspot.com/2017/06/tutorial-nemo-outdoor-graphical-user.html) · [Nemo Outdoor 5 UI](http://pathloss40.blogspot.com/2010/03/nemo-outdoor-5-user-interface.html)
- [TEMS Investigation Tutorial 1 — interface](https://www.youtube.com/watch?v=KH7_cL2EMRA) · [Reducing TEMS logfile size](https://www.youtube.com/watch?v=iCa2srr3888)
- [Android Police — CellMapper tutorial](https://www.androidpolice.com/cellmapper-coverage-tutorial/)

**Vendor pages**
- [R&S QualiPoc Android](https://www.rohde-schwarz.com/us/products/test-and-measurement/network-data-collection/qualipoc-android_63493-55430.html) · [R&S SmartAnalytics](https://www.rohde-schwarz.com/us/products/test-and-measurement/network-data-analytics/smartanalytics_63493-528448.html) · [R&S ROMES4](https://www.rohde-schwarz.com/us/products/test-and-measurement/network-data-collection/rs-romes4-drive-test-software_63493-8650.html)
- [Accuver XCAP](https://www.accuver.com/products/post-processing/XCAP) · [Accuver XCAL](https://www.accuver.com/products/network-optimization/XCAL)
- [Infovista TEMS Discovery](https://www.infovista.com/products/tems-discovery/network-testing-analytics)
- [Keysight Nemo Analyze](https://www.keysight.com/us/en/product/NTN50046C/nemo-analyze-drive-test-post-processing-solution.html)
- [QTRUN NSG](https://www.qtrun.com/eng/nsg/) · [QTRUN AirScreen](https://www.qtrun.com/eng/airscreen/)
- [SwissQual NQDI](https://tele-tools.com/product/swissqual-nqdi/)
- [HiCellTek — Best XCAL Alternative 2026](https://hicelltek.com/en/alternative-xcal/) *(competitor marketing; useful for the framing customers hear)*

**Forums, reviews and practitioner accounts**
- [XDA — Network Signal Guru (p.4)](https://xdaforums.com/t/network-signal-guru.4005511/page-4) · [ISPreview — Network Signal Guru](https://www.ispreview.co.uk/talk/threads/network-signal-guru.39242/)
- [XDA — G-NetTrack thread](https://xdaforums.com/t/app-2-2-g-nettrack-gsm-umts-drive-test-tool-for-android-os.1751074/)
- [telecomHall — Tems DT Log Reading (.trp API licence)](https://www.telecomhall.net/t/tems-dt-log-reading/17098)
- [telecomHall — AirScreen as post-processing alternative](https://www.telecomhall.net/t/airscreen-post-processing-tool-alternative/12828)
- [telecomHall — Most useful tool for Drive Test](https://www.telecomhall.net/t/most-useful-tool-for-drive-test/8620)
- [DriveTester.US — Drive Testing Methods (Part 1)](https://drivetester.us/drive-testing-methods-part-1/)
- Google Play: [NSG](https://play.google.com/store/apps/details?id=com.qtrun.QuickTest) · [G-NetTrack Pro](https://play.google.com/store/apps/details?id=com.gyokovsolutions.gnettrackproplus) · [NetMonster](https://play.google.com/store/apps/details?id=cz.mroczis.netmonster) · [Cellular-Z](https://play.google.com/store/apps/details?id=make.more.r2d2.cellular_z.play) · [CellMapper](https://play.google.com/store/apps/details?id=cellmapper.net.cellmapper)
- [AppGrooves — CellMapper negative reviews](https://appgrooves.com/android/cellmapper.net.cellmapper/cellmapper/cellmappernet/negative)
- Finetopix / Wire Free Alliance: [TEMS 12.1 dongle dump](https://www.finetopix.com/archive/index.php/t-23447.html) · [TEMS Investigation collection](https://www.finetopix.com/showthread.php/46944-OFFICIAL-TEMS-Investigation-Collection) · [Install problems 15.2.2](https://www.finetopix.com/showthread.php/39781-Problem-with-Install-TEMS-Investigation-15-2-2)
- Pricing reference: [TEMS Investigation V28.x GLS licence, reseller listing](https://www.temsnemo.com/index.php/product/tems-investigation-gls-license-reliable-5g-network-testing/) · [Nemo Analyze licence/dongle listing](https://www.temsnemo.com/index.php/product/analyze-post-processing-solution-dongle/)
