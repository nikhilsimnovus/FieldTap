# The UI question, and the app question

Two questions, two different answers. The research behind them:
[`research/drive-test-ui-landscape.md`](research/drive-test-ui-landscape.md),
[`research/ui-technical-options.md`](research/ui-technical-options.md),
[`research/android-app-question.md`](research/android-app-question.md).

---

## Should there be a UI? Yes. A local web app, served by FieldTap itself.

Not a desktop framework, not Electron, not Qt. FieldTap opens a page on
`127.0.0.1` in the system browser.

The reason is that it is barely new work. The report generator already produces
the charts, the route map and the message tables. A live UI is the same
rendering with a different data source, so one set of views serves both live
capture and the archived report, rather than two renderers drifting apart.

**The stack, in one line:** Flask plus waitress, server-rendered HTML, one
hand-written JavaScript module of roughly 400 lines, three vendored libraries.
No npm, no bundler, no framework, nothing to upgrade but three files.

| Concern | Choice | Why |
| --- | --- | --- |
| Live updates | Server-sent events, coalesced to about 5 Hz | One-way, reconnects itself, and the capture threads never block on a browser |
| Maps offline | MapLibre GL JS reading a PMTiles basemap from disk | Drive tests happen where there is no network. A tile download mid-test also pollutes throughput results, which is why TEMS ships a switch to inhibit it |
| Charts | uPlot, fed pre-decimated series | The decimation matters more than the library. Do not send 40,000 points to a browser |
| Packaging | PyInstaller one-dir inside an installer, signed on both platforms | An RF engineer will not touch pip. Unsigned on current macOS is close to unopenable |

Deliberately rejected: Qt, because PySide6 is LGPL and the licence question is
already open, and because it would mean maintaining a second renderer alongside
the HTML report. Streamlit and Flet, because they are not built for a
live-updating engineering tool. htmx, because the live parts are a map and six
charts, which want JSON rather than HTML fragments.

## Who is doing this

Three families, and the shape of each is a lesson.

**Laptop, Windows-only, dockable windows.** TEMS Investigation, Nemo Outdoor,
XCAL. TEMS ships **more than 250 predefined presentation windows** and lets you
build worksheets from them; XCAL allows 30 worksheets and needs a **USB dongle**
to run. This is where the category's reputation for density comes from.

**Post-processing workstations.** XCAP, Nemo Analyze, TEMS Discovery. A separate
purchase, a separate skill, often a separate person. The log goes in, a report
comes out hours later.

**Handheld apps.** TEMS Pocket, QualiPoc Android, Nemo Handy, XCAL-Mobile. One
full-screen view at a time, swiped horizontally, grouped into categories. Worth
stealing from TEMS Pocket: the live message list is frozen by **dragging it
gently downward**, with a counter showing what queued while frozen, and logging
carries on regardless.

**Free tier, no root.** NetMonster, CellMapper (over a million installs),
G-NetTrack Pro at $34.99, LTE Discovery. All of them sit at the same ceiling,
because that ceiling is set by Android's public API and is identical for
everyone.

### The gap worth aiming at

The incumbents make you choose between a dense Windows application with a dongle
and a phone app that cannot show you a real message. Nobody ships **a capture
that opens in Wireshark** as the primary artifact. That is the actual
differentiator, and the UI should lead with it rather than trying to out-window
TEMS.

---

## Should there be an Android app? Not now. Never in the obvious shape.

The obvious shape is an app that reads diag on the phone it runs on, like NSG.
Do not build it, and the reason is structural rather than a matter of effort.

**The no-root diag paths do not help an app.** Samsung's dialer code and
Xiaomi's vendor diag app change the phone's **USB composition**. They publish
diag as an endpoint pair for a host at the other end of the cable. A phone
cannot enumerate itself as a USB host to its own device port, so both are
laptop-side capabilities by construction. An on-phone app wanting that data has
to go back to root, throwing away the exact advantage that made the Samsung
route interesting. And rooting a Samsung has been reported to **break diag
permanently**.

**The industry already answered this.** Keysight's Nemo Handy is an Android app.
When they hit this wall their answer was the Nemo Diagnostic Module: external
capture hardware on USB to a **non-rooted** phone, no special firmware and no
rooting. That is architecturally identical to a modem module on USB, with a
phone instead of a laptop at the host end.

**A non-root app is not a product.** It would be a fifth entrant into a category
where the incumbents are free or $34.99, and where the ceiling is fixed by
Android itself. It cannot produce a single row of what FieldTap already
produces, and it would teach the market that FieldTap is a NetMonster clone.

**The cost.** An app is roughly ten to thirty times the remaining effort of
finishing the laptop and modem path, for zero additional decode capability.

### When to revisit

The one sensible shape is an app that **hosts external capture hardware** over
Android's USB Host API, which needs no root at either end. Build it when at
least two of these are true:

1. A paying customer asks for walk test in writing and will not accept a laptop.
2. The modem path is proven end to end on hardware.
3. Android has a named owner: target-SDK bumps and store declarations are a
   recurring tax, not a project. This is what killed MobileInsight.
4. A second buyer segment appears at a price supporting a subscription.

If a walk-test need arrives before then, **buy** rather than build. G-NetTrack
Pro is $34.99 and NSG is free, and neither threatens the differentiator.

---

## The plan

**Now, before any UI.** Prove the modem module end to end on hardware. A UI for a
pipeline that has never run on real hardware is a demo of a demo.

**Stage 1, days.** A read-only web view over the existing captures directory:
session list, and each report served rather than opened from disk. Reuses the
report renderer as it stands. This is the smallest thing that replaces "find the
folder and double-click the HTML".

**Stage 2, one to two weeks.** Live view during capture. Device list with state,
KPI tiles, the event stream, and the message list with the freeze-on-drag
behaviour. Server-sent events, uPlot, no map yet.

**Stage 3, one to two weeks.** The map, with offline tiles, route coloured by
the metric the user picks. Then start and stop a capture from the page, so a
non-terminal user never sees a command line.

**Stage 4, when a customer needs an installer.** PyInstaller one-dir, Inno Setup
on Windows, signed and notarised on macOS.

**Not on the plan:** an Android app, a cloud fleet dashboard, and out-windowing
TEMS.
