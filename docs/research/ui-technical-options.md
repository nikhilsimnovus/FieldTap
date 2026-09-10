# FieldTap UI: technical options

**Status:** research, 2026-09-10. Opinionated. Not a decision record — Phase 0 decisions in
[`../ROADMAP.md`](../ROADMAP.md) still gate the licence model, and one option below (Qt) interacts
with that directly.

---

## The recommendation in one paragraph

**Serve a local web UI from the FieldTap process itself, on `127.0.0.1`, opened in the system
browser. Flask + waitress. Server-rendered HTML with a small amount of hand-written vanilla
JavaScript — no npm, no bundler, no framework. Live data over one Server-Sent Events stream per
tab, coalesced server-side to ~5 Hz, with the capture threads never blocking on it. Maps:
MapLibre GL JS reading a PMTiles basemap served over that same local HTTP server, with a plain
coordinate plot as the always-available fallback. Charts: uPlot, fed pre-decimated series.
Package with PyInstaller `--onedir` behind Inno Setup on Windows and a signed, notarised `.app`
in a DMG on macOS. Add a pywebview shell later if the browser tab is judged unprofessional; it
is a two-day change, not an architecture.**

The reasoning: this is a small team shipping a commercial engineering tool. Every option below
that isn't this one either adds a second language toolchain to CI, adds a copyleft or LGPL
obligation to a product whose licence model is already an open question, or throws away the
existing self-contained HTML report. The web-UI path is the only one where the live UI and the
archived report share the same rendering code.

---

## 0. What constrains the answer

Re-stating the constraints, because several of them silently eliminate popular options:

| Constraint | What it kills |
| --- | --- |
| Ships to a customer as a per-seat commercial product | PyQt6 (GPL) without buying a Riverbank licence; PySide6 (LGPL) carries relinking obligations that a frozen bundle blurs |
| RF engineer will not touch pip or a terminal | anything whose install story is `pip install`; anything that needs Node or Rust on the customer's machine |
| No internet in the field | any hosted tile API, any CDN-loaded JS, any telemetry or licence check that assumes connectivity |
| Long-running threaded capture, several devices at once | Streamlit's re-run-the-script model; anything single-threaded |
| Tens of thousands of points, thousands of messages | Plotly's default SVG path; naive "send every point to the browser" |
| Already produces a self-contained HTML report that opens from a USB stick | any native toolkit, because you'd then maintain two renderers |
| Small team, maintenance > novelty | Tauri/Electron sidecar builds; a JS build pipeline |

The last row is the one that should carry the most weight and usually carries the least.

---

## 1. A local web UI served by the Python process

### Verdict: yes. This is the recommendation.

The FieldTap process already knows how to render its data to HTML — `fieldtap/report.py` is 642
lines of exactly that. A local web UI is the same code with a different lifetime. Nothing else on
this list gives you that reuse.

### Which server

| Option | Honest assessment |
| --- | --- |
| **Flask + waitress** | **Recommended.** Small, boring, and its API has been stable for a decade. Sync/threaded, which matches a threaded capture loop with no async bridge. Freezes cleanly under PyInstaller with no `multiprocessing` involvement. Werkzeug's `send_file(conditional=True)` implements HTTP Range responses, which is exactly what PMTiles needs — that is a real, specific reason to prefer it over stdlib. |
| **FastAPI + uvicorn** | Good framework, wrong shape here. It drags in Starlette *and* Pydantic (a Rust extension) for what is roughly ten endpoints and one stream. Pydantic's v1→v2 migration is a live memory of what "maintenance burden" means. And it introduces the async/sync colour problem: every read from a capture thread has to cross into the event loop via `run_in_executor` or a thread-safe queue bridge. Frozen-executable behaviour also needs care — `uvicorn` with `workers > 1` under PyInstaller is a known failure, and `multiprocessing.freeze_support()` plus `workers=1` is the documented workaround. You'd be setting `workers=1` anyway, at which point uvicorn's headline advantage is gone. |
| **aiohttp** | Fewer dependencies than FastAPI, same async-bridge cost as uvicorn, smaller community, and you write routing and static-file range handling yourself. No advantage over Flask here. |
| **stdlib `http.server`** | Tempting, because `pyproject.toml` currently declares **zero** runtime dependencies and that is a genuinely valuable property. `ThreadingHTTPServer` is adequate for one local user. But `SimpleHTTPRequestHandler` does not implement Range requests, so PMTiles will not work until you write that yourself, and you also write routing, MIME handling, and SSE framing. Call it 200–300 lines of infrastructure you now own and test forever. Not worth it to avoid two pip dependencies that you are vendoring into a frozen bundle regardless. |

**Choose Flask + waitress.** Bind waitress to `127.0.0.1` on an ephemeral port, set
`threads=8` (the default 4 is too few once an SSE stream holds one), write the chosen port and a
one-time session token to a file in the user's profile, and launch the browser at
`http://127.0.0.1:<port>/?t=<token>`. Binding to loopback rather than `0.0.0.0` avoids the
Windows Firewall prompt and is the correct posture anyway — a drive-test laptop on a customer's
network should not be serving capture control to the LAN.

### Which front end

| Option | Assessment |
| --- | --- |
| **Server-rendered HTML + vanilla JS islands** | **Recommended.** Jinja renders the page shell, the device list, the message table, and the summary — the same templates the archived report uses. One hand-written ES module (~300–500 lines) opens the EventSource and dispatches updates into uPlot and MapLibre. No build step, no `node_modules`, no transpiler, nothing to bump but three vendored libraries. |
| **htmx** | Genuinely good at what it does, and it has an SSE extension. But the live parts of this UI are a map, six charts, and a virtualised table — components that take JSON, not HTML fragments. Pushing an RSRP sample as an HTML `<span>` so htmx can swap it, then having JS read it back out to feed uPlot, is worse than just sending the number. ~14 KB and a mental model to teach the team for a benefit this page's shape doesn't collect. Skip it. |
| **Alpine.js** | ~7–17 KB depending on how you count, no build step, pleasant for small reactive bits (a toggled panel, a filter box). Defensible as a *later* addition if the vanilla JS starts growing state-management scar tissue. Not needed on day one. |
| **Vue / React** | Introduces npm, a bundler, a lockfile, a build step in CI on two OSes, and a dependency tree that needs security attention. For a UI with roughly five screens, this is the single most expensive decision available. No. |

### What this costs to maintain

Low, and specifically: three vendored JS files (`maplibre-gl.js`, `pmtiles.js`, `uPlot.js`),
pinned by version and checked into the repo, not fetched from a CDN — the field has no internet
and a CDN is also a supply-chain surface. Budget roughly two days a year to bump them and re-run
the smoke tests. Flask and waitress are close to zero-churn. There is no npm audit, no
`node_modules`, and no bundler configuration to inherit.

The real ongoing cost is in *your* JS module, and it is proportional to how much you write. Keep
it to event dispatch and leave the rendering to the libraries.

---

## 2. Desktop shells, ranked

| Shell | Live-updating engineering tool? | Windows packaging | macOS packaging | Real maintenance cost for this team |
| --- | --- | --- | --- | --- |
| **System browser** (no shell) | Yes | Nothing to package beyond the Python app | Nothing extra | **Lowest.** Recommended starting point. |
| **pywebview** | Yes | Uses the WebView2 runtime (present on Win11, evergreen on Win10; ship the Evergreen Bootstrapper as installer fallback). PyInstaller-friendly. | Uses WKWebView, always present. No extra runtime. | **Low.** Two files of code, one pip dependency. Recommended for phase 2 if the browser tab looks unprofessional to customers. |
| **Tauri** | Yes, technically excellent | Requires a Rust toolchain plus a JS build in CI; your Python must be bundled as a PyInstaller "sidecar" binary inside the Tauri bundle | Same, and you now sign and notarise both the sidecar and the shell | **High.** You add a second language to the build for a shell that pywebview provides for free. Small installers and low idle memory are real Tauri wins, but they are not FieldTap's problem. No. |
| **Electron** | Yes | Mature tooling, but the bundle is >100 MB *before* you add the Python runtime | Same | **High**, and you still ship Python separately. Strictly worse than pywebview here. No. |
| **Qt (PySide6)** | Yes — it is a real GUI toolkit with real threading | PyInstaller works; QtWebEngine adds ~150 MB if you want embedded HTML | Works, large | **High, and legally awkward.** PySide6 is LGPLv3. Shipping a proprietary per-seat product against LGPL requires either dynamic linking with a documented relink path or an object-code offer — and a PyInstaller bundle blurs exactly that boundary. Given `docs/LICENSING.md` is already an open item, do not add a second licence question. Also: choosing Qt means rebuilding the map and charts in QtLocation/QtCharts and maintaining a *second* renderer alongside the HTML report. |
| **Qt (PyQt6)** | Same capability | Same | Same | **Disqualified** unless you buy a Riverbank commercial licence. PyQt6 is GPLv3-or-commercial; shipping it in a closed per-seat product under GPL terms is not the business model in `LICENSING.md`. |
| **wxPython** | Marginal | Works | Works | **High.** Dated widget set, thin community now, and no credible mapping or high-density charting story. Nothing here that Qt doesn't do better and Qt is already ruled out. No. |
| **Dear PyGui** | Fast live plots, yes — genuinely good at real-time numeric display | Freezes fine | Freezes fine | **Wrong tool.** Immediate-mode GPU UI: no HTML, no map, no selectable/copyable text tables, poor accessibility, and it looks like a game debug overlay. An RF engineer needs to copy a cell ID out of a table and paste it into a ticket. **Toy for this use.** |
| **Flet** | Possible | Flutter runtime bundled | Bundled | **High and speculative.** You'd rebuild the map and charts as Flutter widgets, inherit a Flutter runtime, and bet on a young desktop packaging story. There is no uPlot or MapLibre equivalent you'd get for free. No. |
| **NiceGUI** | Yes, and it's the strongest alternative on this list | Has a `native` mode via pywebview; PyInstaller documented | Same | **Medium.** Event-driven, asyncio-based, explicitly designed for monitoring-style UIs, with built-in Leaflet and ECharts elements — genuinely the fastest path to a demo. Two real objections: (a) every interaction round-trips through a socket to Python, and pushing tens of thousands of points through its element abstraction is not what it is tuned for; (b) when you need something it doesn't expose — a custom uPlot plugin, a MapLibre layer with per-point colour — you are writing custom Vue components against *its* abstraction rather than plain JS. It also does nothing for the static report. Viable; not recommended. |
| **Streamlit** | **No.** | — | — | **Toy for this use.** It re-runs the script top-to-bottom on every interaction. There is no clean model for a long-running multi-device capture, session state becomes a workaround pile, and it cannot do 5 Hz updates into a chart. It is a data-app harness, not an instrument front panel. Rule out explicitly so nobody re-proposes it. |
| **Gradio** | **No.** | — | — | **Toy for this use.** An ML demo harness. Same objections as Streamlit plus a narrower widget set. Rule out. |

**Decision:** ship the system browser first. Add pywebview when a customer complains that "it opens
a browser tab" — and note that the browser has a genuine advantage in the field: the engineer can
open the same URL on a second screen, or on a tablet over USB tethering, without you building
anything.

---

## 3. Live updates from a threaded capture

### Verdict: Server-Sent Events for the sample stream, plain polling for everything else.

### Why SSE

- One-way server→browser is exactly the shape of the problem. The UI's *commands* (start, stop,
  mark waypoint) are ordinary infrequent POSTs.
- It is plain HTTP over the server you already have. WebSockets in a WSGI app need `flask-sock`
  or gevent — another dependency, another thing to freeze.
- `EventSource` reconnects automatically and replays `Last-Event-ID` for you. You get resume
  semantics for free if you assign monotonic ids.

### The architecture that actually works from threaded Python

```
capture thread ──write──> session dir on disk   (source of truth, survives a UI crash)
       │
       └──put_nowait──> per-client bounded deque ──> SSE generator ──> browser
```

Three rules, in order of importance:

1. **The capture thread never blocks on the UI.** Use a bounded deque per connected client and
   `put_nowait`; on overflow, drop and set a `gap` flag that the next frame carries. A slow or
   suspended browser tab must never be able to stall a USB read loop or drop diag frames.
2. **Coalesce before sending.** The capture produces samples far faster than a human reads them.
   Flush at 4–10 Hz, sending the latest scalar values plus the accumulated new points as one
   batch. Sending 200 individual events per second is how you make a browser tab pin a core.
3. **Disk is the source of truth, the stream is a latency optimisation.** The UI reconstructs
   state on connect by reading the session directory, then applies the stream. This is also how
   you get "reopen the UI mid-capture and see everything" for free.

### Failure modes and what to do about them

| Failure | What happens | Mitigation |
| --- | --- | --- |
| **User closes the tab** | The generator does not find out until it next writes and the socket errors. The waitress thread stays parked. | Emit a heartbeat comment (`: ping\n\n`) every 10–15 s. This both detects dead clients and keeps intermediaries from timing the connection out. Without it, threads leak until waitress starves. |
| **Reconnect** | `EventSource` retries automatically (~3 s default) with `Last-Event-ID`. | Keep a ring buffer of the last N frames keyed by sequence id and replay from `Last-Event-ID`. Without this, every reconnect either duplicates points or silently loses them — and a drive test that loses 3 s of samples on a Wi-Fi blip is a support ticket you cannot reproduce. |
| **Many streams, one origin** | Browsers cap HTTP/1.1 at **6 concurrent connections per origin**, and an open SSE stream permanently occupies one. Open one stream per device across three tabs and the page deadlocks — subsequent fetches never start. Chrome and Firefox have both marked this "won't fix". | **Exactly one `EventSource` per tab.** Multiplex all devices onto it with a `device` field in the event payload. Do not be clever here. |
| **Suspended/backgrounded tab** | Browsers throttle timers in background tabs; the deque fills. | The bounded deque plus `gap` marker already handles it; on regaining focus, the UI refetches state rather than replaying. |
| **Server restart** | Stream dies, client reconnects to a server with no memory of the ids. | Include a server-instance id in the event stream; on mismatch the client does a full reload instead of a resume. |

Also set `Cache-Control: no-cache` and `X-Accel-Buffering: no` on the stream response. Neither
matters on loopback today; both matter the day someone puts this behind a reverse proxy.

### The alternatives, honestly

- **WebSockets** — the right answer if the browser needed to send high-rate data *back*. It
  doesn't. Costs a dependency and hand-written reconnect/backoff logic that `EventSource`
  provides. Skip.
- **Polling** — genuinely fine, and underrated for a single-user localhost app. `GET /api/state`
  at 1 Hz over loopback is free. **Use it deliberately** for the device list, session status, and
  KPI summary, so you are never tempted to open a second SSE stream and hit the six-connection
  wall. Reserve SSE for the sample stream where latency actually shows.
- **A file the UI tails** — the browser cannot tail a file; you'd tail it in the server and
  re-serve it, which is a strictly worse SSE with extra I/O and no ordering guarantees. But
  *do* keep writing the CSVs the capture already writes: that is the crash-recovery story and
  the reason the disk-first architecture above works.

---

## 4. Offline maps

This is the requirement that most drive-test tooling gets wrong, and the one with real legal
teeth. Three separable questions: which renderer, which tile source, and what may be
redistributed.

### 4.1 Recommended: MapLibre GL JS + PMTiles over the local server

- **MapLibre GL JS** is BSD-3-Clause. You may embed it in a closed-source commercial product;
  the obligation is to retain the copyright notice. No per-seat fee, no attribution beyond that.
- **PMTiles** is a single-file tile archive read via HTTP Range requests, with the format
  registered as a custom MapLibre protocol (`pmtiles://…`). No tile server, no ops, no SQLite —
  one file you copy onto the laptop. The `.pmtiles` specification itself is public domain.
- Because FieldTap already runs a local HTTP server, PMTiles works with a `send_file(…,
  conditional=True)` route and nothing else. This is the concrete reason to prefer Flask over
  `http.server`.
- Glyphs and sprites must be self-hosted too. MapLibre needs fonts as PBF glyph ranges and icons
  as a sprite sheet; both are static files you ship alongside the basemap. Forgetting them
  produces a map with no labels and no error — budget an afternoon.

**The `file://` trap.** PMTiles requires HTTP Range requests, and browsers block `fetch` on
`file://` origins. So the *live* app (which has a server) gets a real interactive map, and the
*archived* report — which must open from a USB stick with no server — cannot. Do not discover
this at the end.

**Fix for the archived report:** render the basemap server-side into a PNG for the session's
bounding box, embed it as a `data:` URI, and draw the track as SVG on top. This preserves the
"opens from a USB stick, no dependencies" property that `report.py` currently guarantees, and it
is the same trick that makes the report emailable.

### 4.2 Licensing — read this before shipping anything

- **OpenStreetMap data is ODbL.** Rendered tiles are a *Produced Work* of that database.
  Distributing a Produced Work does not force you to open your own data, but it does require
  visible attribution: **"© OpenStreetMap contributors"** must appear on the map in the UI *and*
  in the archived report. If you ever ship a derived *database* (not just rendered tiles), ODbL
  share-alike attaches.
- **Protomaps builds** are distributed as an ODbL Produced Work with OSM attribution required.
  The cartographic styles are CC0; the generation code is BSD-3-Clause; the tile schema derives
  from Tilezen under MIT. If you modify and redistribute the styles or tilesets, you must name
  the result something other than Protomaps. Note the distinction that trips people up:
  Protomaps' **hosted API** has a sponsor-for-commercial-use expectation; the **downloadable
  builds and the open-source tooling** do not carry that restriction. FieldTap downloads and
  ships files, so it is in the second category — but re-read the terms at the point of shipping,
  and consider sponsoring anyway.
- **Never point at `tile.openstreetmap.org`.** Their policy is explicit and unambiguous: *"Offline
  use is not permitted"*; bulk downloading is *"any pre-emptive fetching of tiles other than those
  a user is actively viewing"*; *"building tile archives (e.g. `.zip`, `.mbtiles`) for later
  distribution"* is forbidden; and it warns that commercial services *"should be especially aware
  that access may be withdrawn at any point."* Everything FieldTap needs to do with tiles is on
  their forbidden list. Access is also blocked without notice.
- The same caution applies to every hosted provider. Mapbox, Google, Esri, and Thunderforest all
  restrict caching duration and prohibit redistribution. **A drive-test tool ships tiles onto a
  customer's laptop — that is redistribution.** Only tiles you generate yourself from OSM data,
  or builds explicitly licensed for redistribution, are safe.

### 4.3 How big is a usable offline set, and how is it shipped

Real figures:

| Coverage | Size |
| --- | --- |
| Planet, z0–15 | ~120 GB |
| Planet, z0–6 | ~60 MB |
| Berlin bbox, full zoom | ~84 MB |
| Berlin region, maxzoom 6 | ~813 kB |
| Hamburg city extract (building-level detail, restricted zoom) | ~2 MB, extracted in ~4.2 s |

Dropping maxzoom from 15 to 14 roughly halves the file while staying sharp enough for
drive-test work — you are locating a street, not surveying a parcel.

**Ship this way:**

1. Bundle **planet z0–6 (~60 MB)** with the installer as permanent world context. The map is
   never blank.
2. Add `fieldtap maps add --bbox <w,s,e,n> --maxzoom 14`, which runs a `pmtiles extract` against
   a Protomaps build URL and writes into `%APPDATA%\FieldTap\maps\` (or
   `~/Library/Application Support/FieldTap/maps/`). **This is an office operation, done the day
   before the drive test.** Never attempt a download in the field.
3. A metro-scale extract at z7–14 lands in the 60–120 MB range. A laptop covering one metro for a
   week of testing carries well under 250 MB of map — it fits on the same USB stick as the
   captures.
4. For enterprise customers, ship a pre-built national extract with the installer. Country-scale
   at full zoom runs to one or a few gigabytes, so offer it as a separate download, not on the
   installer's critical path.

### 4.4 The alternatives

**Leaflet + pre-cached raster tiles.** Simpler API, and it has one genuine advantage: a plain
directory of PNG tiles needs no Range requests, so it *does* work from `file://`. That makes it
the only way to get a live interactive map inside the archived USB-stick report. Against it:
raster for a city across z0–16 is substantially larger than the vector equivalent, you cannot
restyle or rotate labels, dark mode means a second tile set, and you must still generate the
tiles yourself — which means running a raster renderer over OSM data, a heavier pipeline than
`pmtiles extract`. Note that `.mbtiles` is SQLite and therefore needs a server too; only a loose
PNG directory is `file://`-safe. **Use Leaflet only if you decide the archived report must have a
live map**, and then ship PNGs for that session's bbox only.

**No tiles: a plain coordinate plot.** Keep this, and make it the default when no map file is
installed. An equirectangular lat/lon scatter coloured by RSRP, with a scale bar and a north
arrow, is what `report.py` already draws and it is genuinely what RF engineers read all day. It
costs nothing, has zero licensing exposure, works from `file://`, and never fails in the field.

**Design rule: the basemap is an enhancement, never a dependency.** Every map view must degrade
to the coordinate plot without an error dialog. A drive test does not stop because a tile file is
missing.

**Plotting the points themselves** is not a problem at this scale — MapLibre draws a GeoJSON
circle or line layer of tens of thousands of features on the GPU without complaint. Feed it a
single GeoJSON source with a data-driven colour expression on RSRP, not thirty thousand markers.

---

## 5. Charts with tens of thousands of points

### Verdict: uPlot, fed pre-decimated series. The decimation matters more than the library.

### The libraries

| Library | Size | 100k-point line render | Assessment |
| --- | --- | --- | --- |
| **uPlot** | ~48 KB min | **~9 ms** | **Recommended.** Purpose-built time-series canvas plotter. Cold start: 166,650 points interactive in **25 ms**, then scaling linearly at **~100,000 pts/ms**. Streaming 3,600 points at 60 fps costs **10% CPU / 12.3 MB RAM**, against Chart.js at 40% / 77 MB and ECharts at 70% / 85 MB — and streaming is exactly what a live capture does. MIT, no build step. |
| **Chart.js** | 254 KB | ~33–38 ms | Perfectly fine, and better than uPlot at scatter. But 5× the size and 4× the streaming CPU for a shape uPlot was designed for. |
| **ECharts** | ~1 MB | ~55 ms | Excellent breadth and the best-looking defaults. A megabyte of features you won't use, and the worst streaming profile of the three canvas libraries measured. Reasonable if you later want heatmaps and complex composite views without writing them. |
| **Plotly.js** | 3.6 MB | **~310 ms** | **Rule out.** Default SVG rendering creates a DOM node per point and collapses above ~10,000 elements. `scattergl` bypasses that, but browsers cap WebGL contexts at roughly **4–8 per page** — fatal for a dashboard showing six or more charts at once, which is precisely this UI. |

uPlot's deliberate omissions — no data aggregation, no animations, no stacking — are all things
you do not want in an instrument display. Its lack of built-in pan/drag is the one real gap;
there are plugins, and you want custom zoom behaviour anyway (see below).

### Downsample server-side. This is the actual answer.

A chart 1,200 px wide can display 1,200 columns. Sending 40,000 points to draw 1,200 columns
wastes ~97% of the transfer, the JSON parse, and the memory — and no library choice fixes that.

- Use **min/max-per-pixel-column** decimation, not averaging, not LTTB, for RF measurements.
  Two points per column (2,400 total for a 1,200 px chart) reproduces the visible envelope
  exactly. **Averaging will smooth away a 20 dB drop**, which is the single thing the engineer
  opened the chart to find. LTTB is prettier for general time series but does not guarantee
  extrema are preserved; do not use it for RSRP, SINR, or BLER.
- On zoom, fetch the raw window from the server (`/api/series?from=&to=&width=`) and re-decimate
  for that range. The user gets full fidelity where they are looking and never pays for the rest.
- This makes six charts of 40k points feel instantaneous regardless of which library renders them,
  and it is maybe 60 lines of Python.

### Server-side SVG/PNG rendering

Keep it — for the archived report, where it is already the right answer and already implemented.
Static SVG in the report means no JS dependency and a file that opens anywhere. Do not use it for
the live UI: you would re-render and re-transfer an image on every pan and zoom.

### The message table

Thousands of decoded messages is a table problem, not a chart problem, and it is where naive
implementations die. Do not render 5,000 `<tr>` elements. Paginate server-side, or virtualise with
a fixed row height and an absolutely-positioned window — roughly 80 lines of vanilla JS. Filter
and sort on the server, where the data already is.

---

## 6. Packaging for a non-technical user

### Verdict: PyInstaller `--onedir`, wrapped in a native installer. Sign on both platforms.

### Why PyInstaller, and why `--onedir`

`--onefile` extracts the whole bundle to a temp directory on every launch. That is slow on a
cold start and — more importantly — **the runtime self-extraction behaviour is a documented
antivirus heuristic trigger**. `--onedir` produces a folder that starts fast and looks like a
normal installed application. Put that folder behind a real installer and the user never sees it.

### The alternatives

| Tool | Assessment |
| --- | --- |
| **PyInstaller `--onedir`** | **Recommended.** Best-documented failure modes, largest community, works on both targets, and every problem you hit has already been asked about. |
| **Nuitka** | The strongest second choice. It compiles to C and produces a genuinely native binary, which **trips fewer AV heuristics** and starts faster — a real benefit when a customer's endpoint protection quarantines your installer. Costs: a C toolchain in CI on both OSes, long compile times, and debugging a miscompiled extension is a bad day for a small team. Note its own `--onefile` mode reintroduces the extraction heuristic, so use standalone mode. **Switch to this if AV false positives become a recurring support burden** — they will occasionally. |
| **Briefcase (BeeWare)** | Philosophically the right tool: it produces genuinely native artifacts (MSI on Windows, `.app`/DMG on macOS) and native installers get more AV trust. But it is opinionated about project layout, has a smaller community to search when it breaks, and it does not compile — so you carry PyInstaller's AV exposure with less documentation. Worth revisiting only if a mobile target appears on the roadmap. |
| **Bundle a plain Python runtime + a custom installer** | Most controllable in principle (`python-build-standalone` plus a small launcher). In practice you write the launcher, path handling, and upgrade logic yourself — which is what PyInstaller `--onedir` already is, already maintained. Only do this if PyInstaller becomes a blocker. |

**Installers:** Inno Setup on Windows (free, scriptable, well understood, handles Start Menu,
uninstall, and the WebView2 Evergreen Bootstrapper if you adopt pywebview). On macOS, a signed
`.app` inside a DMG with a drag-to-Applications background — a `.pkg` only if you need a
privileged install step, which FieldTap should avoid.

### macOS code signing and notarisation — not optional

Required chain:

1. **Apple Developer Program membership — $99/year.** Notarisation requires an enrolled team.
2. **Developer ID Application certificate** issued to that team.
3. **Sign with the hardened runtime enabled.** It has been a prerequisite for notarisation since
   June 2019.
4. **Submit with `notarytool`**, then **`stapler staple`** the ticket to the `.app` — and if you
   distribute in a ZIP, re-zip *after* stapling.

**What happens without it.** On macOS Sequoia and later, Apple removed the Control-click override
that used to let a user open an unsigned or un-notarised app. The remaining path is a deliberate
gauntlet: the launch is blocked by a warning, the user must go to **System Settings → Privacy &
Security**, click **Open Anyway**, dismiss a second warning that advises them not to open it
unless they are certain of the source, and then **authenticate**. Three dialogs, one of which
explicitly tells them not to proceed. An RF engineer on a managed corporate Mac may not be
*permitted* to complete that sequence at all. Also note that the quarantine attribute is what
triggers this — anything downloaded or AirDropped carries it.

Independently, FieldTap needs USB device access; on Apple Silicon laptops USB accessories are
gated behind a user approval (see `fieldtap-host-platforms` notes and `docs/MACOS.md`). Budget for
both, and test on a machine that has never seen a developer build.

### Windows code signing

Since **1 June 2023**, code-signing private keys must be generated and stored in a hardware crypto
module meeting **FIPS 140-2 Level 2 or Common Criteria EAL4+**, for OV as well as EV certificates,
and the key cannot be exported. Practically:

- An OV certificate now means a shipped USB token or a cloud signing service — roughly
  $200–600/year, plus the awkwardness of signing from CI (a cloud signing API, or a self-hosted
  runner with the token attached).
- **Unsigned:** SmartScreen shows "Windows protected your PC" and the user must click
  **More info → Run anyway**. Many corporate policies simply block it, silently.
- **Newly signed with OV:** SmartScreen warnings persist until reputation accrues over downloads
  and time. **EV** certificates get reputation immediately and cost more.
- Validation lead time is 1–3 weeks of *calendar* time. Start procurement early; it is not effort,
  but it is a schedule dependency.

### Small things that decide whether it feels finished

- Bind to `127.0.0.1` only. Binding `0.0.0.0` triggers the Windows Firewall prompt on first run,
  which looks alarming and which a locked-down machine may deny outright.
- Choose an ephemeral port; never hardcode 8000 or 8080. Write the port and a one-time token to
  the user profile and put the token in the URL, so another local account's browser cannot drive
  a capture.
- The tray/console window: build with `console=False` and log to a file in the user profile, with
  a "Show logs" menu item. A visible console window reads as unfinished.
- Ship the CLI in the same bundle. The GUI is a front end onto the same session directories; the
  CLI stays the primary interface for automation and for support ("run this and send me the
  output").

---

## 7. Effort

One engineer, working steadily. Calendar time, not ideal-days.

| Stage | Scope | Effort |
| --- | --- | --- |
| **1. Local UI skeleton** | Flask + waitress on loopback, session/device list, start/stop, live KPI readouts, uPlot charts, coordinate-plot track, virtualised message table. Reuses `report.py`'s data model. | **3–4 weeks** |
| **2. Live stream** | SSE with heartbeats, ring-buffer resume, bounded per-client deques, server-side coalescing and min/max decimation. | **1–1.5 weeks** |
| **3. Offline maps** | MapLibre + PMTiles route with Range support, self-hosted glyphs/sprites, `fieldtap maps add`, attribution in both UI and report, degrade-to-coordinate-plot path. Plus a day on licensing review. | **1.5–2 weeks** |
| **4. Packaging, unsigned** | PyInstaller `--onedir` on both OSes, Inno Setup, DMG, CI matrix. Internal builds only. | **1 week** |
| **5. Signing + notarisation** | Apple enrolment, Developer ID, hardened runtime, `notarytool`/`stapler` in CI; Windows OV/EV cert and cloud signing. | **1–2 weeks effort, 3–4 weeks calendar** (certificate validation is the long pole) |
| **6. Static-report map snapshot** | Server-side basemap PNG into the archived report, keeping it self-contained. | **~1 week** |
| **7. pywebview shell** *(optional)* | Native window instead of a browser tab; WebView2 bootstrapper in the installer. | **2–3 days** |

**Total to a shippable, signed, offline-capable UI on both platforms: roughly 8–10 weeks of
engineering, with calendar time dominated by certificate procurement.** Ongoing maintenance is
about two days a year for vendored JS bumps plus normal feature work — which is the whole reason
for choosing this stack.

Start stage 5's certificate procurement in parallel with stage 1. It is the only item on the list
that cannot be compressed by working harder.

---

## Citations

- uPlot — benchmarks, size, and deliberate omissions: <https://github.com/leeoniya/uPlot/blob/master/README.md>
- JS chart library benchmark (100k points, per-shape results): <https://apexcharts.com/blog/javascript-chart-library-benchmark/>
- Plotly SVG/WebGL limits and per-page WebGL context cap: <https://www.scichart.com/blog/alternatives-to-plotly-js/> and <https://plotly.com/javascript/webgl-vs-svg/>
- PMTiles concepts, Range requests, CORS requirements: <https://docs.protomaps.com/pmtiles/>
- PMTiles + MapLibre protocol registration: <https://github.com/protomaps/PMTiles/blob/main/js/examples/maplibre.html> and <https://github.com/maplibre/maplibre-agent-skills/blob/main/skills/maplibre-pmtiles-patterns/SKILL.md>
- Protomaps basemap downloads and sizes: <https://docs.protomaps.com/basemaps/downloads>
- Protomaps `pmtiles extract` and getting started: <https://docs.protomaps.com/guide/getting-started> and <https://docs.protomaps.com/pmtiles/cli>
- Protomaps licence (CC0 styles, BSD-3 code, ODbL Produced Work tiles, fork naming): <https://github.com/protomaps/basemaps/blob/main/LICENSE.md> and <https://github.com/protomaps/basemaps/blob/main/LICENSE_DATA.md>
- OSM Tile Usage Policy — offline use, bulk downloading, tile archives, commercial warning: <https://operations.osmfoundation.org/policies/tiles/>
- OSM Vector Tile Usage Policy: <https://operations.osmfoundation.org/policies/vector/>
- Local HTTP server needed for PMTiles (file:// has no Range/CORS): <https://walker-data.com/freestiler/reference/serve_tiles.html>
- Offline MapLibre with Protomaps, self-hosted glyphs and sprites: <https://blog.wxm.be/2024/01/14/offline-map-with-protomaps-maplibre.html> and <https://keimaps.com/articles/self-hosted-basemap-maplibre-terrain>
- Offline Leaflet + Protomaps: <https://blog.wxm.be/2023/10/25/offline-map-with-protomaps.html>
- SSE and the 6-connection HTTP/1.1 limit ("won't fix" in Chrome and Firefox): <https://textslashplain.com/2019/12/04/the-pitfalls-of-eventsource-over-http-1-1/>, <https://issues.chromium.org/issues/40329530>, <https://bugzilla.mozilla.org/show_bug.cgi?id=906896>
- htmx SSE extension: <https://htmx.org/extensions/sse/>
- htmx / Alpine.js sizes: <https://www.pkgpulse.com/guides/htmx-vs-alpinejs-2026>
- PyInstaller common issues — multiprocessing, `freeze_support()`, spawn on Windows: <https://pyinstaller.org/en/stable/common-issues-and-pitfalls.html>
- uvicorn workers failing under PyInstaller: <https://github.com/Kludex/uvicorn/discussions/1820>
- PyInstaller AV false positives and onefile vs onedir: <https://www.pythonguis.com/faq/problems-with-antivirus-software-and-pyinstaller/> and <https://github.com/pyinstaller/pyinstaller/issues/6754>
- PyQt6 vs PySide6 licensing (GPL vs LGPL, commercial use): <https://www.pythonguis.com/faq/licensing-differences-between-pyqt6-and-pyside6/>
- Which Python GUI library in 2026: <https://www.pythonguis.com/faq/which-python-gui-library/>
- NiceGUI vs Streamlit — event-driven vs script re-run, background tasks: <https://www.bitdoze.com/streamlit-vs-nicegui/> and <https://github.com/zauberzeug/nicegui/discussions/3020>
- pywebview / Electron / Tauri comparison and PyTauri: <https://www.pkgpulse.com/guides/best-desktop-app-frameworks-2026> and <https://biggo.com/news/202510140726_PyTauri_Python_Tauri_Binding>
- macOS Sequoia removes the Control-click Gatekeeper override: <https://www.idownloadblog.com/2024/08/07/apple-macos-sequoia-gatekeeper-change-install-unsigned-apps-mac/> and <https://mjtsai.com/blog/2024/07/05/sequoia-removes-gatekeeper-contextual-menu-override/>
- What users actually face without notarisation (the three-dialog sequence, quarantine attribute): <https://eclecticlight.co/2024/10/01/living-without-notarization/>
- Apple runtime protection updates in Sequoia: <https://developer.apple.com/news/?id=saqachfa>
- macOS signing/notarisation chain — $99 programme, Developer ID, hardened runtime, notarytool, stapler: <https://gist.github.com/rsms/929c9c2fec231f0cf843a1a746a416f5> and <https://federicoterzi.com/blog/automatic-code-signing-and-notarization-for-macos-apps-using-github-actions/>
- CA/Browser Forum hardware-key requirement effective 1 June 2023 (FIPS 140-2 L2 / EAL4+, OV and EV): <https://www.encryptionconsulting.com/understanding-the-ca-browser-forum-code-signing-requirements/> and <https://knowledge.digicert.com/general-information/new-private-key-storage-requirement-for-standard-code-signing-certificates-november-2022>
- MapLibre GL JS BSD-3 licence and embedding: <https://www.npmjs.com/package/maplibre-gl> and <https://www.blog.brightcoding.dev/2025/10/05/maplibre-gl-js-the-open-source-powerhouse-for-interactive-maps-in-modern-web-apps>
- Loopback binding vs `0.0.0.0` and firewall behaviour: <https://www.toolhq.io/blog/what-is-localhost-127-0-0-1>
