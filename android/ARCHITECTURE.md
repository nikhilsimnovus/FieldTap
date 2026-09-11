# 5gto6G FieldTap for Android: architecture

This is the code plan for the app in `android/`. It turns [`docs/APP-PLAN.md`](../docs/APP-PLAN.md)
into modules, packages, interfaces and workstreams that can be built in parallel. The stubs it
describes are in the tree: every public type and function exists, compiles, and carries its
contract in KDoc, with `TODO("<workstream>")` bodies.

Where sources disagree, this order wins:

1. [`schema/columns.json`](../schema/columns.json), then [`docs/SESSION-FORMAT.md`](../docs/SESSION-FORMAT.md),
   then the golden session in [`tests/fixtures/android_session/`](../tests/fixtures/android_session/).
   All three are merged into this branch before implementation starts.
2. [`docs/APP-PLAN.md`](../docs/APP-PLAN.md) for what the product does.
3. This document and the KDoc in the stubs for how the code is arranged. If the two disagree, raise
   it; do not pick one silently.

---

## 0. Decisions of 2026-09-10

These answer the open questions from the design pass. They override anything below and any stub
KDoc that disagrees.

1. **Limits statement.** The app shows exactly: "Reads what Android exposes: cell identity,
   RSRP/RSRQ/SINR, band, ARFCN, service state, plus ping and download tests. It does not decode RRC,
   NAS, SIB or any layer-3 signalling, cannot lock bands or cells, cannot scan operators, and needs
   no root." There is no laptop or modem sentence, because there is no modem product yet.
2. **Consent text.** Plain language: what is recorded (cell measurements, the GPS track during
   sessions you start, test results, the phone model), that it stays on this phone and leaves only
   when you share a zip, that no phone or SIM identifiers are ever read, and that consent can be
   withdrawn in Settings, which stops new sessions. Version `2026-09-10-draft`. It has not been
   legally reviewed; say so in a code comment, not in the UI.
3. **Backup and transfer.** `android:allowBackup="false"`, plus `data_extraction_rules.xml` and
   `full_backup_content` that exclude everything from cloud backup and from device-to-device
   transfer.
4. **Tokens.** The stop and gap tokens this design adds are accepted: `summary.stopped_by`
   `recording` while open; `storage_full`, `permission_revoked`, `service_destroyed`; the
   `ApplicationExitInfo` reason names; gap reasons `app_paused`, `no_service`, `screen_off`,
   `unknown`. The lead adds them to `docs/SESSION-FORMAT.md` and `schema/columns.json`.
5. **Event order.** Events are appended in derivation order. Out of order by at most 11 s is accepted
   and documented.
6. **Test defaults.** Download: `https://speed.cloudflare.com/__down?bytes=10000000`, editable in
   Settings, with the host named on the Settings screen. Ping: `8.8.8.8`, 5 echoes, every 60 s. Both
   stay off by default and opt-in per session. The debug automation hook accepts overrides for the
   targets, the size and the intervals; the emulator test pings `10.0.2.2`, because ICMP beyond the
   emulator's NAT is dropped, and downloads 1 MB.
7. **Readiness.** Tapping Start runs the readiness checks automatically; no separate visit is required,
   and `READINESS_REQUIRED` is removed as a refusal. Only these block a start: no consent, no precise
   location, location services off, storage full. Everything else (battery optimisation on,
   background restricted, a restrictive standby bucket, Wi-Fi on so the cadence is 10 s, no SIM,
   notifications denied, a phone maker known for killing background apps) shows a pre-start sheet
   naming the specific problem and its fix, with "Start anyway". The soak test stays optional on the
   Readiness screen.
8. **Schema constants** are hand-copied from `columns.json` and guarded by the drift test. Accepted.
9. **Phone permission.** `READ_PHONE_STATE` stays declared, but is requested only when the user turns
   on "Instant cell updates" in Settings, which explains that it enables Android's push updates. The
   app works fully without it.
10. **Mock locations.** Release builds reject mock fixes; debug builds accept them.
11. **Builds.** Implementers do not run Gradle while they work in parallel; the integrator builds.

---

## 1. Modules

```
:format   Kotlin/JVM   the bytes of the seven session files        deps: kotlinx-serialization-json (reading only)
   ^
:core     Kotlin/JVM   every decision that needs no Android         deps: api(:format), api(coroutines-core)
   ^
:app      Android      thin adapters, the service, the screens      deps: :core, :format, AndroidX, Compose
```

Rules:

- `:format` and `:core` never import `android.*`. Everything in them is unit-tested on a JVM,
  including on the Windows PC.
- `:app` decides nothing that `:core` could decide. An adapter converts an Android callback into a
  plain value, stamps it with the injected clock, and hands it on.
- No DI framework, Room, WorkManager, OkHttp, Google Play services, Firebase, analytics or
  crash-reporting SDK. Nothing uploads: sessions leave the phone only as a shared zip.
- No dependency is added by an implementer. Everything the workstreams need is already declared:
  `kotlinx-coroutines-test` 1.11.0 (Maven Central, newest stable) was added for `:core` and `:app`
  tests.

## 2. Packages

| Package | Module | What | Owner |
| --- | --- | --- | --- |
| `com.fieldtap.format` | :format | Schema constants, enums, typed rows, CSV and JSON writers, session.json model, directory names, coordinates | format |
| `com.fieldtap.core` (`CellValues`) | :core | Android sentinel values to null | radio-core |
| `com.fieldtap.core.time` | :core | `Clock`, `ManualClock` | session-core |
| `com.fieldtap.core.input` | :core | `MeasurementInput` and every platform input value | session-core (root), radio-core (`RadioInputs.kt`), location-privacy-core (`LocationInputs.kt`) |
| `com.fieldtap.core.radio` | :core | Freshness, serving cells, cadence, KPI and cellinfo rows, cells table, radio events, gaps, collection stats, `RadioPipeline` | radio-core |
| `com.fieldtap.core.location` | :core | Fix selection, GPS join, GPS events, track rows, `LocationPipeline` | location-privacy-core |
| `com.fieldtap.core.privacy` | :core | Privacy zones, pause gate, consent text and record | location-privacy-core |
| `com.fieldtap.core.export` | :core | Precision reduction, export zip, SHA-256 | location-privacy-core |
| `com.fieldtap.core.settings` | :core | `AppSettings` and its JSON codec | location-privacy-core |
| `com.fieldtap.core.session` | :core | State machine, recorder, file writer, store, recovery, heartbeat, storage cap, exit reasons | session-core |
| `com.fieldtap.core.nettest` | :core | Test settings, scheduling, ping statistics, traffic records, ICMP packets | service-and-tests |
| `com.fieldtap.core.soak` | :core | Soak test evaluation | service-and-tests |
| `com.fieldtap.core.readiness` | :core | Readiness facts, levels, policy | platform-adapters |
| `com.fieldtap.core.probe` | :core | Probe report, accumulators, JSON | platform-adapters |
| `com.fieldtap.core.live` | :core | `LiveState` and its reducer | ui-session |
| `com.fieldtap.app` | :app | `FieldTapApplication`, `AppGraph` facades, `DefaultAppGraph`, `MeasurementHub`, `HubLiveFeed` | service-and-tests |
| `com.fieldtap.service` | :app | `SessionService`, notification, `SessionRuntime`, session and soak control | service-and-tests |
| `com.fieldtap.nettest` | :app | Cellular network request, ICMP ping, download | service-and-tests |
| `com.fieldtap.recovery` | :app | Launch recovery | service-and-tests |
| `com.fieldtap.data` | :app | `FileSessionRepository` | service-and-tests |
| `com.fieldtap.platform.*` | :app | Clock, permissions, app info, telephony, location, device state, exit reasons, probe runner, readiness checker, DataStore settings | platform-adapters |
| `com.fieldtap` (`MainActivity`), `com.fieldtap.ui` (theme), `.ui.nav`, `.ui.common`, `.ui.live`, `.ui.sessions` | :app | Activity, navigation, Live, Sessions, Session detail, sharing | ui-session |
| `com.fieldtap.ui.onboarding`, `.ui.readiness`, `.ui.probe`, `.ui.settings`, `.ui.about`, `.ui.setup` | :app | Disclosure, permissions, readiness, probe, settings, about | ui-setup |
| `com.fieldtap.debug` (`src/debug` only) | :app | End-to-end automation hook | ui-session |

## 3. How a measurement becomes a row

```
Android callbacks                  :app                          :core (one session dispatcher)                         files
-----------------                  ----                          ------------------------------                         -----
requestCellInfoUpdate (1 s) ----+
CellInfoListener (Phone perm.)  |  TelephonySource.inputs()  --+
ServiceState / DisplayInfo /    |  (each listener separate,    |
DataConnectionState / Signal ---+   SecurityException caught)   |
                                                               +--> MeasurementHub.inputs (SharedFlow, WhileSubscribed)
LocationManager GPS/fused/net --+  LocationSource.inputs()  ---+        |                   |
GnssStatus ---------------------+                                       |                   +--> HubLiveFeed -> LiveStateReducer -> Live screen
DeviceStateSource.current() ----> read inside the telephony callback    |
                                                                        v
                                                     SessionRecorder.submit(Measurement)  (unbounded channel)
                                                                        |
                             +------------------------------------------+-------------------------------------------+
                             |                                          |                                           |
                  RadioPipeline.onCellInfo(answer, writing)   LocationPipeline.onFix(fix)                 Traffic / Mark / Tick
                  FreshnessEngine -> ServingCellSelector      FixSelector -> PrivacyZoneGate              NetTestRunner -> TrafficRecord
                  -> RadioRows (kpi, cellinfo candidates)     -> FixJoiner, GpsEventDeriver, TrackRows    SessionEvents.marker
                  -> RadioEventDeriver, SamplingGapDetector,                                               WriteSchedule: flush 1 s,
                     CollectionStats                                                                       sync 5 s, snapshot 60 s,
                             |                                          |                                  heartbeat 5 s, storage 60 s
                             v                                          v
                  pending rows (FIFO) --- LocationPipeline.join() Resolved? ---> SessionFiles.append*  ----> kpi.csv, cellinfo.csv,
                             |                                                                                track.csv, events.csv,
                             +--> RadioPipeline.onKpiWritten -> ServingCellTable                              traffic.csv (append-only)
                                                                SessionFiles.writeSnapshot ---------------> cells.csv, session.json (atomic)
                                                                SessionFiles.writeHeartbeat ---------------> session-state/<dir>.heartbeat
```

Step by step, for one `requestCellInfoUpdate` answer while recording:

1. `TelephonySource` receives `onCellInfo(list)`. It reads both clocks and `DeviceStateSource.current()`,
   maps each `CellInfo` with `CellInfoMapper` (sentinels to null), and emits a `CellInfoAnswer`.
2. The hub delivers it to the recorder's forwarding collector, which calls `submit`.
3. On the session dispatcher, the recorder calls `radio.onCellInfo(answer, writing = !location.paused)`.
4. `FreshnessEngine` marks each cell stale or fresh on its cell key and modem timestamp, computes age
   and measurement time, and picks the primary and NSA secondary serving cells.
5. `RadioRows` builds a cellinfo candidate for every cell, and a KPI candidate for each fresh serving
   cell no older than 2.5 s (short interval) or 11 s (long interval).
6. `RadioEventDeriver` emits `rat_change` and `serving_cell` from accepted samples; the gap detector
   emits `sampling_gap` if this answer ends a gap; statistics are counted. Events are appended at once.
7. The candidates join the pending FIFO. On this and every later command, the head row is released
   when `location.join(measurementElapsed, now)` is final: it gets its position (nearest fix within
   5 s on the monotonic clock, earlier on a tie) and is appended. Each written KPI row updates the
   cells table and `summary.plmns`.
8. The next tick flushes; every fifth tick syncs; every sixtieth rewrites cells.csv and session.json.

## 4. Time

- Every class that needs time takes a `com.fieldtap.core.time.Clock`. Only
  `com.fieldtap.platform.clock.AndroidClock` reads the real clocks. Tests use `ManualClock`.
- Ages, intervals, gaps, heartbeats, schedules and the GPS join use `elapsedRealtime`, because the wall
  clock can jump.
- A measurement row (`kpi.csv`, `cellinfo.csv`, `cells.csv` `first_seen_utc`) carries measurement time:
  the answer's wall clock minus the sample's age. Every other row carries the wall clock when the adapter
  observed the callback, stamped in the adapter, not when a consumer processes it.
- A fix's wall time is its `elapsedRealtime` translated onto the app's wall clock, not `Location.getTime()`,
  so track and measurement times share one clock.

## 5. Threads

| Where | Thread | Rule |
| --- | --- | --- |
| Platform callbacks | An executor owned by each adapter | Convert, stamp, `trySend`. No IO, no decisions. |
| `MeasurementHub` | Its collectors | `SharedFlow` with no replay; collectors never block. |
| `SessionRecorder` | `SessionDispatchers.newSessionDispatcher()` = `Dispatchers.IO.limitedParallelism(1)`, one per session | The only code that touches the active session directory. All engines it calls are single-threaded. |
| `HubLiveFeed` | `Dispatchers.Default` | Its own `LiveStateReducer`; never writes files. |
| Repositories | `Dispatchers.IO` | Read closed sessions; refuse to delete or export the active one. |
| View models | Main | Collect facades; call suspend facades in `viewModelScope`. |

## 6. Contracts in :core

The KDoc of each stub is the full contract. This section is the summary an implementer reads first.

### 6.1 Freshness and KPI rows (radio-core)

| Rule | Where |
| --- | --- |
| A cell is a repeat when an equal `CellKey` (rat, plmn, pci, arfcn, cell id) with an equal `timestampMs` was already seen, by any answer, request or push. Repeats go to cellinfo.csv with `stale` 1, never to kpi.csv. | `FreshnessEngine` |
| An answer is fresh when its primary serving cell is fresh (no primary: any cell). `fresh_samples` counts fresh answers; `repeats_dropped` counts repeats. | `FreshnessEngine`, `CollectionStats` |
| Age = elapsed at answer - `timestampMs` (clamped to 0). Measurement time = wall at answer - age. | `FreshnessEngine` |
| Short interval (2 s) when screen on and (Wi-Fi off or charging); otherwise 10 s. KPI rows at most 2500 ms old on the short interval, 11000 ms on the long. | `CadencePolicy`, `RadioRows` |
| Primary serving = connection status 1 (vendors without statuses: first registered LTE or NR cell). NSA leg = NR cell with status 2 under an LTE primary. At most one KPI row per RAT per answer. | `ServingCellSelector`, `RadioRows` |
| Emergency-only comes from service state only; emergency-camped samples are still KPI rows. | `ServingCellSelector` |
| Values outside the schema's ranges are blank, never clipped; Android's unavailable values are null before they reach :core. | `RadioRows`, `CellValues` |

### 6.2 Gaps and collection statistics (radio-core)

- A sampling gap ends at a fresh answer more than twice the Android interval after the previous fresh
  sample, where the interval is the one in force when that previous sample was measured (the newest answer
  at or before its measurement time). It is written when it ends, as a `sampling_gap` event (time = the
  ending answer's arrival) and a `collection.gaps` entry (start and stop = measurement times).
- Reason: `app_paused` (our ticker stalled more than 3 s), else `no_service`, else `screen_off`, else
  `unknown`. A privacy-zone resume resets the detector, so no gap straddles a pause.
- `median_fresh_interval_ms`: sorted intervals between fresh answers, element at `size / 2`.
- `*_pct`: share of `request` answers with that condition, unrounded; session.json writes one decimal.

### 6.3 Events

| Kind | Produced by | Time | Title (detail) |
| --- | --- | --- | --- |
| `serving_cell` | `RadioEventDeriver.onAccepted` | Measurement time of the first sample from the cell | `Serving cell` / `Serving cell changed` (`PLMN .. TAC .. eNB .. sector .. PCI .. EARFCN .. band ..`) |
| `rat_change` | `RadioEventDeriver.onAccepted` | Measurement time | `RAT changed` (`NR to LTE`), warn on NR to LTE |
| `service_lost` | `RadioEventDeriver.onServiceState` | Observed | `Service lost` (`out of service` / `radio off`) |
| `emergency_only` | `RadioEventDeriver.onServiceState` | Observed, or the next sample when no recent cell | `Emergency calls only` |
| `service_restored` | `RadioEventDeriver.onServiceState` | Observed, or the next sample | `Service restored` |
| `data_state` | `RadioEventDeriver.onDataState` | Observed | `Mobile data ...` (`connected, LTE`), warn on disconnect |
| `nr_display` | `RadioEventDeriver.onDisplayInfo` | Observed | `5G icon on` / `5G icon off` (`override NR_NSA, network LTE`) |
| `sampling_gap` | `SamplingGap.toEvent` | Ending answer's arrival | `Sampling gap` (`no fresh cell info for 14.0 s`), cause = reason |
| `gps_lost` / `gps_restored` | `GpsEventDeriver` | Tick / fix observed | `GPS lost` / `GPS restored` |
| `privacy_zone` | `PrivacyZoneGate` | Fix observed | `Logging paused in a privacy zone` / `Logging resumed`, never a place |
| `marker` | `SessionEvents.marker` | Tap | `Marker` (the note) |
| `test_failed` | `SessionEvents.testFailed` via `TrafficRecords` | Test start | `Ping failed` / `Download failed` (error) |
| `session_interrupted` | `SessionEvents.sessionInterrupted` via `SessionRecovery` | Last heartbeat | `Session interrupted`, cause = exit token |

`EventKind` has exactly these fourteen values, so no signalling kind can be written. Events are
appended in the order they are derived. Rows that carry a measurement time can therefore precede the
row before them by at most the sample age (at most 11 s). `fieldtap validate` does not check event order.

### 6.4 GPS join and track (location-privacy-core)

- `FixSelector`: no mock fixes in release builds, no 0,0, strictly increasing fix time; GPS always,
  fused and network only when no GPS fix in the last 3 s (accepted only after more than 3000 ms without
  one; a gap of exactly 3000 ms still counts as recent).
- `FixJoiner`: nearest accepted fix within 5000 ms inclusive on `elapsedRealtime`, earlier on a tie.
  `Pending` while a nearer fix could still arrive (no fix at or after the measurement yet, and less than
  5 s plus 1.5 s slack has passed). `joinFinal` at stop.
- Stale cellinfo rows join at their original measurement time. The buffer keeps 60 s of fixes.

### 6.5 Privacy zones (location-privacy-core, enforced by session-core)

- Logging pauses at the first accepted fix inside a zone (distance at most radius plus up to 50 m of
  accuracy) and resumes at the first accepted fix outside every zone. With no fix yet, it is not paused.
- While paused, nothing is written to any file except the two `privacy_zone` events. That includes
  kpi, cellinfo, track, traffic rows and markers, radio events and statistics. A fix inside a zone never
  enters the join buffer, so no row gets a position from inside a zone.
- De-duplication and the latest service, data and display state keep updating while paused. On resume,
  the pipeline re-feeds that state stamped with the resume time, so the files learn what changed but not
  when.
- `privacy.zone_pauses` counts pauses. A zone's label never leaves the Settings screen.

### 6.6 Location precision and the export zip (location-privacy-core)

- The local copy is always full precision. `SessionExporter.export(dir, precision, cacheDir/exports)`
  builds a copy: `approx_110m` rewrites lat and lon to 3 decimals written with 7; `none` blanks them and
  leaves out track.csv; session.json gets the precision and the `files` map to match.
- The zip is flat, holds only the seven names in `SessionFile.BUNDLE` order, uses deflate, has no
  directories and no zip64, and is at most 50 MiB (200 MiB uncompressed). Its SHA-256 is shown with the
  share. Open sessions are refused.

### 6.7 Session files (session-core, bytes by format)

| File | Written | Rule |
| --- | --- | --- |
| kpi.csv, cellinfo.csv, track.csv, events.csv, traffic.csv | Append-only | Header at creation; flush every 1 s; `FileDescriptor.sync()` every 5 s. |
| cells.csv | Rewritten atomically every 60 s and at stop | May hold only its header early on. |
| session.json | Created at start; rewritten atomically every 60 s and at stop | `.tmp`, sync, atomic rename. While open: `stopped_utc` null and `summary.stopped_by` `recording`. |
| `<filesDir>/session-state/<dir>.heartbeat` | Every 5 s, deleted at close | `v1 <wallMs> <elapsedMs> <pid>`. Outside the session directory. |

- Root: `<getExternalFilesDir(null)>/sessions/`, pullable at `/sdcard/Android/data/<applicationId>/files/sessions`.
- Directory `SessionDirName.of(startedUtcMs, name)`. On a collision within the same second,
  `SessionStore.allocate` waits for the next second; it never reuses a directory or adds a suffix.
- The app never writes summary.json, report.html or index.html.

### 6.8 Lifecycle, stop reasons and recovery (session-core, applied by service-and-tests)

- `SessionStateMachine.reduce`: IDLE -> STARTING -> RECORDING -> STOPPING -> IDLE, with refusals checked
  in order: `BLANK_NAME`, `NO_CONSENT`, `NO_PRECISE_LOCATION`, `LOCATION_OFF`, `STORAGE_FULL`;
  `SESSION_RUNNING` whenever a session exists. Readiness is not a precondition (decision 7).
- `summary.stopped_by`: `user`, or an app stop token (`storage_full`, `permission_revoked`,
  `service_destroyed`), or, after a kill, the `ApplicationExitInfo` reason name in lower case without
  `REASON_` (`low_memory`, `freezer`, `crash`, `anr`, `user_requested`, `other`, `unknown`, ...).
- The service returns `START_NOT_STICKY`. At the next process start, `LaunchRecovery` finds sessions with
  `stopped_utc` null. `RecoveryPlanner` takes the stop time from the heartbeat (else the newest file time)
  and the reason from the exit record with the heartbeat's pid (else the first record at or after the stop
  time minus 10 s, else `unknown`). `SessionRecovery.close` cuts each CSV back to its last CR LF, appends
  `session_interrupted`, and rewrites session.json from the last snapshot. Summary counts of an
  interrupted session are therefore up to 60 s old.

### 6.9 Storage cap (session-core)

A new session starts only when sessions use less than 2 000 000 000 bytes and at least 200 MB is free.
A running session stops with `storage_full` when the cap is reached or free space falls below 50 MB.
Old sessions are never deleted automatically.

### 6.10 Tests (service-and-tests)

Tests are off by default and opted into per session. Ping targets `8.8.8.8` by default, 5 echoes every
60 s. The download defaults to `https://speed.cloudflare.com/__down?bytes=10000000` (decision 6), editable in
Settings, which names its host; it runs every 5 min, is capped at 10 MB, and has a 100 MB session budget. Both run on a cellular `Network` requested explicitly: the ICMP datagram socket is bound
with `Network.bindSocket`, the download uses `Network.openConnection`. With no cellular network, the row
has `ok` 0 and error `no cellular network`, plus a `test_failed` event. No tests run while paused in a zone.

### 6.11 Readiness, soak, probe (platform-adapters, service-and-tests)

- `ReadinessPolicy.evaluate(facts)`: blockers are no precise location and location off. Advice covers
  notifications, battery optimisation, background restriction, a rare or restricted standby bucket, no ready
  SIM, and Wi-Fi while not charging; OnePlus, OPPO and realme are flagged as makers known for stopping
  background apps. The phone permission is always OK (decision 9). Tapping Start runs the checks and shows the
  pre-start sheet (decision 7); no separate visit is required, and `ReadinessPolicy.requiredBeforeSession`
  remains a pure rule that no start depends on.
- Soak: the foreground service runs the telephony ticker for 10 min with no files. `SoakEvaluator` reports
  seconds logged against seconds elapsed.
- Probe: 30 s of every input plus a registration attempt on the three restricted listeners. Exported as
  `fieldtap-probe/1` JSON through `JsonText`.

## 7. Adapters in :app (platform-adapters)

| Adapter | Android API | Emits |
| --- | --- | --- |
| `TelephonySource` | `TelephonyManager.requestCellInfoUpdate` every 1 s; `getAllCellInfo` once; `registerTelephonyCallback` once per listener (SignalStrengths, ServiceState, DisplayInfo, DataConnectionState; CellInfo only with the Phone permission) | `CellInfoAnswer`, `CellInfoRequestFailed`, `ServiceStateSnapshot`, `DataStateSnapshot`, `DisplayInfoSnapshot`, `SignalSnapshot`, `ListenerReport` |
| `CellInfoMapper`, `TelephonyStateMapper` | `CellInfo*`, `ServiceState`, `NetworkRegistrationInfo.getAvailableServices`, `TelephonyDisplayInfo`, `SignalStrength` | Plain values |
| `LocationSource` | `LocationManager` GPS, fused and network via `LocationRequest.Builder`; `GnssStatus.Callback` | `FixSample`, `GnssSnapshot`, `LocationAvailability` |
| `DeviceStateSource` | `PowerManager.isInteractive`, battery broadcasts, `ConnectivityManager` Wi-Fi transport callback | `DeviceConditions` |
| `ExitReasonReader` | `ActivityManager.getHistoricalProcessExitReasons` | `ExitRecord` |
| `HandsetInfoReader`, `AppInfoReader`, `Permissions` | `Build`, `TelephonyManager` operator fields, `PackageManager` | `HandsetMeta`, `AppInfo`, booleans |
| `AndroidReadinessChecker` | Permissions, `LocationManager`, `PowerManager`, `ActivityManager`, `UsageStatsManager`, SIM state | `ReadinessReport` |
| `CapabilityProbeRunner` | `TelephonySource` plus restricted listener attempts | `ProbeReport` |
| `DataStoreSettingsRepository` | DataStore Preferences, one JSON string | `AppSettings` |

The public APIs these names rely on were checked against the installed `platforms;android-37.0`
`android.jar`. `ServiceState` has no public `isEmergencyOnly()`: emergency-only is read from `getState()` and
`NetworkRegistrationInfo.getAvailableServices()`.

## 8. Screens and navigation

| Screen | Route | View model | Facades | Owner |
| --- | --- | --- | --- | --- |
| Disclosure and consent | `disclosure` | `OnboardingViewModel` | settings | ui-setup |
| Permissions | `permissions` | none | platform `Permissions` | ui-setup |
| Live | `live` | `LiveViewModel` | live, sessionControl, settings, recovery | ui-session |
| Sessions | `sessions` | `SessionsViewModel` | sessions | ui-session |
| Session detail | `sessions/{dirName}` | `SessionDetailViewModel` | sessions (detail, export, delete) | ui-session |
| Readiness | `readiness` | `ReadinessViewModel` | readiness, soak | ui-setup |
| Probe | `probe` | `ProbeViewModel` | probe | ui-setup |
| Settings | `settings` | `SettingsViewModel` | settings, live | ui-setup |
| About | `about` | none | appInfo | ui-setup |

- The start destination is `disclosure` until consent is current, then `permissions` until precise
  location is granted, then `live`. No location prompt appears before the disclosure is accepted.
- Every view model takes only `AppGraph` (plus route arguments) and is built with
  `graphViewModelFactory`, so tests pass a fake graph.
- `FieldTapNavHost` (ui-session) calls ui-setup's composables with the signatures in their stub files.
- `MainActivity` keeps its placeholder until ui-session replaces the content with `FieldTapNavHost`,
  so the app launches during parallel work.

## 9. Debug automation hook

Debug builds only. `app/src/debug/AndroidManifest.xml` declares an exported, translucent
`com.fieldtap.debug.AutomationActivity`. Release has no such source set, so a release APK contains no
exported automation entry point. CI checks this with
`aapt2 dump xmltree --file AndroidManifest.xml <release apk>`: no `com.fieldtap.debug` may appear.

```
APP=com.fieldtap   # android/gradle.properties fieldtap.applicationId
adb install -g app-debug.apk
adb shell am start -W -n $APP/com.fieldtap.debug.AutomationActivity -a com.fieldtap.debug.START_SESSION \
    --es name e2e-walk --ez accept_consent true --ez mark_ready true
adb shell am start -W -n $APP/com.fieldtap.debug.AutomationActivity -a com.fieldtap.debug.MARK --es note "checkpoint 1"
adb shell am start -W -n $APP/com.fieldtap.debug.AutomationActivity -a com.fieldtap.debug.STOP_SESSION
# am start -W returns before START reaches Recording or STOP reaches Idle: wait for this action's result.
adb shell "f=/sdcard/Android/data/$APP/files/automation/last-result.json; while [ ! -f \$f ]; do sleep 1; done; cat \$f"
adb pull /sdcard/Android/data/$APP/files/sessions/<dir_name>
```

| Extra | Type | Actions | Meaning |
| --- | --- | --- | --- |
| `name` | string | START (required) | Session name |
| `note`, `location` | string | START; `note` also MARK | Session note and place, or the marker's note |
| `tests` | boolean | START | Run ping and download |
| `accept_consent` | boolean | START | Record consent to the current text first |
| `mark_ready` | boolean | START | Record the readiness check as run now |
| `timeout_ms` | long | START, STOP | Wait for Recording or Idle (default 20000) |
| `ping_target`, `download_url` | string | START | Saved to the test settings first; empty turns that test off |
| `ping_interval_ms`, `download_interval_ms`, `download_cap_bytes`, `session_budget_bytes` | long | START | Saved to the test settings first (decision 6) |
| `ping_count` | int | START | Echoes per ping test |

Each action writes one line of JSON (`{"action", "ok", "dir_name", "error"}`) to logcat under the tag
`FieldTapAutomation` and to `files/automation/last-result.json`, then finishes. The file is removed when an
action begins and written atomically when it ends, so a harness waits for it (or for the logcat line) and
never reads the previous action's result. `am start` brings the activity to the foreground, which is what
allows it to start the location foreground service.

Extras may be passed with any `am start` flag (`--ez`, `--es`, `--el`, `--ei`); a value that cannot be read as
its type fails the action with `invalid_<extra>`. Besides the `StartRefusal` names, `error` is one of
`timeout`, `not_recording`, `paused`, `start_failed` (accepted, then back to Idle without an outcome),
`stopped_while_starting`, `missing_name`, `unknown_action`, `interrupted` (Android destroyed the activity
mid-action) and `exception`.
Instrumentation tests can launch the same intent with `ActivityScenario`, or call `DebugAutomation`
directly while an activity of the app is resumed.

### The end-to-end proof

`android/e2e/run_e2e.sh` proves the app on an emulator, through its UI, in the emulator job of
`.github/workflows/android.yml` (API 36, the target, and API 31, the minSdk). The instrumented tests are in
`app/src/androidTest/kotlin/com/fieldtap/e2e/`: Compose UI testing drives the app, UiAutomator answers Android's permission
dialogs and the share sheet. Each test writes screenshots and a result JSON to `<externalFilesDir>/e2e/`, which the host
pulls after every run. `android/e2e/check_e2e.py` asserts what the files hold.

| Step | Driven by | Must hold |
| --- | --- | --- |
| First run, in light, dark and font scale 1.3 (`cmd uimode night`, `font_scale`, each after `pm clear`) | `FirstRunScreensTest` | The disclosure shows before any permission prompt; nothing is granted before Allow |
| The walk: consent, permissions in Android's dialog, Live, Settings (ping `10.0.2.2`, 1 MB download), Start through the pre-start sheet, a marker, Stop after 180 s, Build zip, Share | `EndToEndWalkTest`, while the host sends `adb emu geo fix` once a second and `adb emu gsm signal-profile` every 20 s | Live shows a serving cell with its age badge; the zip's SHA-256 on screen is the file's; the share sheet opens |
| Every screen in the three variants | `ScreenTourTest` | Live, Start dialog, Sessions, detail and Share card, Readiness, Probe, Settings, About |
| Process death | The host: START through the automation hook, `am force-stop` (relaunch with `am start`) and `run-as <pkg> kill -9` (relaunch by `RecoveryUiTest`) | The session is closed with a `session_interrupted` event whose cause is the exit reason `dumpsys activity exit-info` gives for the killed pid, at the last heartbeat; Live names it |
| The files | `python -m fieldtap validate DIR --upload`, `python -m fieldtap report DIR`, `fieldtap validate` on the zip, `check_e2e.py` | Every session validates; kpi rows are fresh serving-cell measurements in cellinfo.csv, never a modem timestamp twice for a cell, positioned from the nearest fix within 5 s; track fixes lie on the injected walk; serving_cell and the marker with its note; ping and download rows; `stopped_by` user, `layer3` false, collection statistics; cells.csv agrees with `summary.plmns`; the report has no Procedures or Call flow section |

The artifacts of each leg are `e2e-sessions-api<N>` (sessions with report.html, the exported zip),
`e2e-screenshots-api<N>`, `e2e-logcat-api<N>` (redacted as the probe redacts) and `e2e-reports-api<N>` (summary, JUnit XML
of each instrumentation run, fieldtap output, check results).

## 10. Workstreams

Nine workstreams: the screens split cleanly into session screens and setup screens, and the design system
became its own stream so both screen streams build on the same tokens and components. Each owns the files
listed and nothing else. A test directory listed as owned may gain any new files. "Ownership as built", at the
end of this section, records where the lists grew during implementation.

### format: session file writers and schema constants

- **Owns:** `android/format/src/main/kotlin/com/fieldtap/format/*` (SessionFormat.kt, Csv.kt, Schema.kt,
  SessionFile.kt, Enums.kt, EventKind.kt, Rows.kt, CsvEncoders.kt, SessionMeta.kt, JsonText.kt,
  SessionJson.kt, SessionDirName.kt, Coordinates.kt); `android/format/src/test/kotlin/com/fieldtap/format/**`.
- **Provides:** `Csv`, the six `*Csv` encoders, `SessionJson.encode/decode`, `JsonText`, `SessionDirName`,
  `Coordinates`, `SessionFormat.utc/timeEpoch/parseUtc`, `Schema`, `Ranges`, and the row, enum and meta types.
- **Consumes:** `schema/columns.json`, `docs/SESSION-FORMAT.md`, the golden fixture. Nothing from other workstreams.
- **Unit tests:** `SchemaDriftTest` compares every header, list and number in `Schema` and `Ranges` with
  `../../schema/columns.json`. Golden byte tests parse each golden CSV record into its row type, re-encode,
  and compare the whole file byte for byte, header included; session.json does the same through
  decode/encode. Also: number edge cases (HALF_EVEN, no `-0.0`, German locale), quoting, CR and LF in text,
  `time_epoch` from integer milliseconds, and the slug examples.

### radio-core: freshness engine, serving cells, radio events, gaps and statistics

- **Owns:** `android/core/src/main/kotlin/com/fieldtap/core/CellValues.kt`, `core/input/RadioInputs.kt`,
  `core/radio/*`; `android/core/src/test/kotlin/com/fieldtap/core/CellValuesTest.kt`,
  `android/core/src/test/kotlin/com/fieldtap/core/radio/**`.
- **Provides:** `RadioPipeline`/`DefaultRadioPipeline`, `FreshnessEngine`, `ServingCellSelector`,
  `CadencePolicy`, `RadioRows`, `CellIdentityMath`, `ServingCellTable`, `RadioEventDeriver`, `SamplingGap(s)`,
  `CollectionStats`, `NetworkTypeNames`, `CellValues.intOrNull/longOrNull`, the radio input types.
- **Consumes:** :format types and `Ranges`.
- **Unit tests:** feed the golden session's 120 answers (rebuilt from `tests/fixtures/make_android_session.py`).
  Expect the golden kpi rows, cellinfo rows (without positions), both `serving_cell` rows, the 14.0 s
  `screen_off` gap, the collection values (54 fresh, 66 repeats, 88.3, 2000 ms), cells.csv, and
  `plmns {"311480": 54}`. Plus: age gating at 2500 and 11000 ms, push repeats, NSA leg drop without an
  event, SA to LTE fallback, SIM-less emergency start, a pause suppressing output, resume re-emitting
  state, and the UL EARFCN table.

### location-privacy-core: GPS join, privacy zones, precision, export, settings

- **Owns:** `core/input/LocationInputs.kt`, `core/location/*`, `core/privacy/*`, `core/export/*`,
  `core/settings/*`; `android/core/src/test/kotlin/com/fieldtap/core/{location,privacy,export,settings}/**`.
- **Provides:** `LocationPipeline`/`DefaultLocationPipeline`, `FixSelector`, `FixJoiner`, `JoinResult`,
  `GpsEventDeriver`, `TrackRows`, `PrivacyZone(s)`, `PrivacyZoneGate`, `Consent`/`ConsentText`/`ConsentRecord`,
  `PrecisionReducer`, `SessionExporter`, `ExportResult`, `ExportException`, `Sha256`, `AppSettings`,
  `AppSettingsCodec`, the location input types.
- **Consumes:** :format (`Coordinates`, `Csv`, `SessionJson`, `SessionFile`, rows); the `TestSettings` shape
  (service-and-tests).
- **Unit tests:** the golden kpi and cellinfo lat/lon from the golden track; the 5000/5001 ms boundary;
  ties; pending then resolved; a walk in and out of a zone (two privacy_zone rows, no coordinates, no track
  rows inside, joins never use inside fixes); precision rewrites of the golden files; the zip's names,
  order, method, no directories, SHA-256 and size limits; the consent hash; settings codec round trip and
  corrupt input.

### session-core: recorder, writer, state machine, recovery, heartbeat, storage

- **Owns:** `core/time/Clock.kt`, `core/input/MeasurementInput.kt`, `core/session/*` (SessionState.kt,
  Lifecycle.kt, SessionFiles.kt, SessionStore.kt, SessionRecorder.kt);
  `android/core/src/test/kotlin/com/fieldtap/core/{session,time}/**`.
- **Provides:** `Clock`, `ManualClock`, `MeasurementInput`, `SessionRecorder`, `RecorderCommand`,
  `RecorderSnapshot`, `SessionDispatchers`, `SessionStateMachine` and its types, `SessionFiles`/`FileSessionFiles`,
  `AtomicFiles`, `CsvRepair`, `SessionStore`, `SessionPaths`, `RecoveryPlanner`, `SessionRecovery`, `ExitReasons`,
  `HeartbeatRecord`, `WriteSchedule`, `StoragePolicy`/`StorageStatus`/`StorageUsage`, `SessionEvents`,
  `SessionMetaFactory`, `SessionIdentity`.
- **Consumes:** `RadioPipeline` and `LocationPipeline` (interfaces only, faked in tests), `TrafficRecord`,
  `ConsentRecord`, the :format encoders, `SessionJson`, `SessionDirName`.
- **Unit tests:** the recorder with fakes, a `ManualClock` and virtual time: row order across the join
  delay; nothing but privacy_zone while paused; flush, sync, snapshot and heartbeat cadence; stop releases
  pending rows and writes `stopped_utc`; the storage stop; commands after stop ignored. Also the state
  machine table; the recovery planner (pid match, timestamp fallback, no heartbeat, reboot); recovery on
  a temp directory with a torn last row; atomic writes; directory collision within a second; exit reason
  tokens 0 to 16.

### platform-adapters: telephony, location, device state, exit reasons, probe, readiness, settings

- **Owns:** `android/app/src/main/kotlin/com/fieldtap/platform/**`, `core/probe/*`, `core/readiness/*`;
  `android/core/src/test/kotlin/com/fieldtap/core/{probe,readiness}/**`,
  `android/app/src/test/kotlin/com/fieldtap/platform/**`.
- **Provides:** `AndroidClock`, `Permissions`, `AppInfoReader`, `TelephonySource`, `CellInfoMapper`,
  `TelephonyStateMapper`, `HandsetInfoReader`, `LocationSource`, `DeviceStateSource`, `ExitReasonReader`,
  `CapabilityProbeRunner` (a `CapabilityProbe`), `AndroidReadinessChecker` (a `ReadinessChecker`),
  `DataStoreSettingsRepository` (a `SettingsRepository`), `ReadinessPolicy`, `ProbeJson`, the probe accumulators.
- **Consumes:** the :core input types, `CellValues`, `NetworkTypeNames`, `CadencePolicy`, `AppSettingsCodec`,
  the `AppGraph` facade interfaces, `JsonText`.
- **Unit tests:** readiness levels and the required-before-session table; probe accumulators and JSON; any
  mapping logic factored into pure functions (for example data-state ints, operator strings, blank to
  null). Android-bound code is proven by compiling and by the later emulator stage.

### service-and-tests: foreground service, wiring, ping and download, launch recovery

- **Owns:** `android/app/src/main/kotlin/com/fieldtap/app/**`, `.../service/**`, `.../nettest/**`,
  `.../recovery/**`, `.../data/**`, `android/app/src/main/res/values/strings_service.xml`,
  `core/nettest/*`, `core/soak/*`; `android/core/src/test/kotlin/com/fieldtap/core/{nettest,soak}/**`,
  `android/app/src/test/kotlin/com/fieldtap/{app,service,nettest,recovery,data}/**`.
- **Provides:** `AppGraph` and all its facades (`SessionControl`, `SessionRepository`, `LiveFeed`, `SoakControl`,
  `RecoveryNotices`, wiring of `SettingsRepository`, `ReadinessChecker` and `CapabilityProbe`),
  `FieldTapApplication`, `DefaultAppGraph`, `MeasurementHub`, `HubLiveFeed`, `SessionService`,
  `SessionNotification`, `SessionRuntime`, `CellularNetworks`, `CellularTestTransport`, `LaunchRecovery`,
  `FileSessionRepository`, `NetTestRunner`, `NetTestScheduler`, `PingStats`, `TrafficRecords`, `IcmpEcho`,
  `SoakEvaluator`.
- **Consumes:** everything in :core, and all platform adapters.
- **Unit tests:** scheduler timing and budget; ping statistics; traffic records for success, no reply,
  no cellular network and HTTP errors; ICMP packet bytes; the soak evaluator; `FileSessionRepository` on a
  temp directory; the `SessionRuntime` effect handling with a fake recorder; launch recovery ordering.

### ui-session: Live, Sessions, Session detail, navigation, sharing, debug hook

- **Owns:** `android/app/src/main/kotlin/com/fieldtap/MainActivity.kt`, `.../ui/FieldTapTheme.kt`,
  `.../ui/nav/**`, `.../ui/common/**`, `.../ui/live/**`, `.../ui/sessions/**`,
  `android/app/src/main/res/values/strings_session_ui.xml`, `android/app/src/main/res/values/themes.xml`,
  `values-night/themes.xml`, `values/colors.xml`, `res/xml/file_paths.xml`, the launcher icon resources,
  `core/live/*`, `android/app/src/debug/**`; `android/core/src/test/kotlin/com/fieldtap/core/live/**`,
  `android/app/src/test/kotlin/com/fieldtap/ui/{nav,common,live,sessions}/**`.
- **Provides:** `FieldTapNavHost`, `Routes`, `graphViewModelFactory`, `FileSharer`, `LiveScreen`,
  `LiveViewModel`, `SignalChart`, `WalkModeEffect`, `SessionsScreen`, `SessionDetailScreen` and their view
  models, `LiveStateReducer`, and the debug automation hook.
- **Consumes:** the `AppGraph` facades; ui-setup's screen composables and view models; `Consent`.
- **Unit tests:** the Live reducer (window, repeats, neighbours, badge); view models with a fake `AppGraph`
  (start refusal handling, mark while paused, export state); `AutomationResult.toJson`; route building.

### ui-setup: disclosure and consent, permissions, readiness, probe, settings, about

- **Owns:** `android/app/src/main/kotlin/com/fieldtap/ui/{onboarding,readiness,probe,settings,about,setup}/**`,
  `android/app/src/main/res/values/strings_setup_ui.xml`; `android/app/src/test/kotlin/com/fieldtap/AppStringsTest.kt`,
  `android/app/src/test/kotlin/com/fieldtap/ui/{onboarding,readiness,probe,settings,about,setup}/**`.
- **Provides:** `DisclosureScreen`, `PermissionsScreen`, `ReadinessScreen`, `ProbeScreen`, `SettingsScreen`,
  `AboutScreen`, `OnboardingViewModel`, `ReadinessViewModel`, `ProbeViewModel`, `SettingsViewModel`,
  `SettingsIntents`.
- **Consumes:** the `AppGraph` facades, `Consent`, `PrivacyZones`, `ReadinessReport`, `ProbeReport`,
  `FileSharer`, `graphViewModelFactory`.
- **Unit tests:** view models with a fake `AppGraph` (consent record written with the current hash, zone
  validation surfaced, test settings saved); `AppStringsTest` stays green.

### design-system: tokens, components, theme and brand resources

- **Owns:** `android/app/src/main/kotlin/com/fieldtap/ui/theme/**` (including `DESIGN.md`), `.../ui/components/**`,
  `.../ui/FieldTapTheme.kt`, `res/values/themes.xml`, `res/values-night/themes.xml`, `res/values/colors.xml`, the
  launcher icon resources and `res/drawable/ic_stat_fieldtap.xml`;
  `android/app/src/test/kotlin/com/fieldtap/ui/{theme,components}/**`.
- **Provides:** colour schemes and the one signal scale, type, shapes, `Spacing` and `Sizes` (including
  `Sizes.WideLayoutMinWidth`), `FieldTapIcons`, `Formats`, and the components the screens are built from: metric
  tiles, signal bars and chips, the cadence indicator, banners and chips, cards and rows, empty and loading
  states, `SignalHistoryChart`, `SessionButton` and `ReadinessSheet`.
- **Unit tests:** WCAG AA contrast of every text and status colour in both themes, signal scale boundaries,
  formats, brand resources against the Kotlin tokens, chart and grid maths.

### Ownership as built

Recorded at integration, where workstreams added files beyond their lists:

- ui-session also owns `android/app/src/testDebug/kotlin/com/fieldtap/debug/**` (classes in `src/debug` can be
  unit-tested only from the debug unit-test source set) and `ui/common/{DisplayTime,ScreenSupport,StopReasons,
  SystemSettings}.kt`, `ui/live/{LivePresentation,Prestart}.kt`, `ui/sessions/SessionsPresentation.kt`. The theme,
  colour and launcher resources first listed under ui-session belong to design-system.
- ui-setup also owns `ui/readiness/ReadinessPresentation.kt`, `ui/probe/ProbePresentation.kt`,
  `ui/settings/{TestSettingsForm,ZoneDrafts}.kt` and `ui/setup/{PermissionRules,SetupFormats,SetupLayout}.kt`.
- service-and-tests also owns `service/{SessionRuntime,SessionFactory,NotificationText,AndroidSessionPlatform}.kt`
  and `core/nettest/BodyCounter.kt`; platform-adapters also owns `platform/AdapterExecutor.kt`,
  `platform/settings/StoredSettings.kt` and `platform/telephony/TelephonyValues.kt`.
- `res/xml/data_extraction_rules.xml` and `res/xml/backup_rules.xml` (decision 3) are shared files, like the manifest.
- The end-to-end proof, added at the emulator stage: `app/src/androidTest/kotlin/com/fieldtap/e2e/**`, `android/e2e/run_e2e.sh`
  and `android/e2e/check_e2e.py` (section 9). `fieldtap/__main__.py` was added so `python -m fieldtap` runs the CLI.

## 11. Shared files and frozen contracts

Written in the design stage and owned by no workstream. Implementers do not edit them; a needed change is
reported to the orchestrator, who applies it:

- `android/build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`,
  `format/build.gradle.kts`, `core/build.gradle.kts`, `app/build.gradle.kts`, the wrapper
- `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/res/values/strings.xml` (app name and
  limits statement, word for word)
- this document, `.gitattributes`, `android/.gitignore`

These shapes are frozen: an implementer fills the bodies but does not change the signatures:

- the `com.fieldtap.core.input` types;
- the :format row, enum and `SessionMeta` types;
- `RadioPipeline`, `LocationPipeline`, `SessionFiles`, `NetTestTransport`;
- the `AppGraph` facades;
- the ui-setup composable signatures called by `FieldTapNavHost`.

A change to any of them is agreed through the orchestrator, because another workstream codes against it.
Changes agreed at integration:

- `CellRow.pci` and `CellRow.dlEarfcn` are nullable: a leg Android reports without them is still a cells.csv row,
  with the value blank and `plausible` False (docs/SESSION-FORMAT.md, cells.csv).
- `StartRefusal.READINESS_REQUIRED` and `StartPreconditions.readinessRequired` are removed (decision 7).
- Found by the end-to-end proof: the API 31 emulator reports only a registered GSM cell (status 0) while its data runs on
  HSPA, never an LTE or NR cell. Live said "Waiting for the first cell measurement" for as long as it ran; it now says
  Android reports no LTE or NR serving cell, with the data network type (`LivePresentation.servingAbsence`). The
  proof reads the registry first: the LTE and NR checks are required on API 36, and a modem without LTE or NR gets a
  session checked to hold no kpi row. `ServingCellSelector` is unchanged.

Inside owned files, an implementer may add private or internal helpers, new files in owned packages, and
tests. Public API added for one's own use is fine; public API another workstream needs goes through the
orchestrator.

## 12. Building on this PC

- Commands, from `android/`:
  `./gradlew :format:test :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`; to compile only:
  `./gradlew :format:compileKotlin :core:compileKotlin :app:compileDebugKotlin`.
- In Bash, first `source /c/Users/Simnovus-Lab/tools/android-env.sh` and
  `export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\Simnovus-Lab\.gradle\afunix'`. Without the
  second, JDK 21's selector cannot open its AF_UNIX pipe under `%TEMP%` on this PC.
- The PC has 7.8 GB of RAM. **Never run two Gradle builds at once.** Parallel implementers take a
  build lock first: `mkdir C:/Users/Simnovus-Lab/.gradle/fieldtap-build.lock` succeeds only for one of them.
  Build, run `./gradlew --stop`, then `rmdir` the lock. On failure, retry after a minute; a lock older than
  30 minutes may be removed.
- The golden session and schema are read by tests at `../../tests/fixtures/android_session/` and
  `../../schema/columns.json` relative to the module directory. Tests fail, not skip, when they are missing.
- Emulator runs happen only in GitHub Actions: the end-to-end proof (section 9) and the capability probe. From `android/`,
  `./gradlew :app:assembleDebugAndroidTest` builds the instrumented tests locally; running them needs an emulator.

## 13. Decisions taken here

| Decision | Why |
| --- | --- |
| Three modules (`:format`, `:core`, `:app`), in `android/` of this repository, applicationId `com.fieldtap` from one Gradle property | The updated plan and the scaffold. |
| Schema constants written by hand plus `SchemaDriftTest`, not generated from columns.json | No build logic to maintain. Drift fails a test just as surely. |
| CSV lines end in CR LF | SESSION-FORMAT.md overrides the plan's LF: Python's csv module and the golden files use CR LF. |
| session.json and cells.csv written by the app's own `JsonText`, not kotlinx-serialization | Exact Python layout and one-decimal numbers; serialization is used only to read. |
| `summary.stopped_by` is `recording` while open; extra stop tokens `storage_full`, `permission_revoked`, `service_destroyed` | The field is required and non-null, and these stops are not Android exit reasons. |
| Gap reasons `app_paused`, `no_service`, `screen_off`, `unknown` | The contract gives examples only. |
| Heartbeat outside the session directory | The directory holds only the seven files. |
| cells.csv and session.json snapshotted every 60 s | Recovery then needs no reconstruction. The cost is up to 60 s of stale counts after a kill. |
| Privacy pause is sticky until a fix outside, stops statistics too, and resume re-stamps state | Nothing written inside a zone, including times. |
| Mock fixes rejected in release builds | Honest tracks. Debug builds accept them for the emulator. |
| No WorkManager or OkHttp | No upload in this version. `Network.openConnection` binds to cellular. |
| The Live screen runs the sources while visible, with no session | Live is a screen of the plan. Location is while-in-use there. |
| Readiness never refuses a start; Start runs the checks and shows a pre-start sheet naming each problem, with "Start anyway" unless something blocks | Decision 7, replacing the earlier `READINESS_REQUIRED` refusal. |
| Storage cap in decimal units | Matches "2 GB" and "10 MB" (the golden download is 10 000 000 bytes). |
| Consent text `2026-09-10-draft` in `Consent.CURRENT`, worded per decision 2 | Not legally reviewed. Any change to the text needs a new version and the new SHA-256 pinned in `ConsentTest`, because stored consent records and every session's `consent_sha256` depend on the exact bytes. |
| The sampling-gap threshold uses the interval in force when the previous fresh sample was measured | Only this reproduces the golden 14.0 s `screen_off` gap: the answer that delivered the previous fresh sample already reports the screen off. |
| A cells.csv row may lack `pci` or `dl_earfcn` | SESSION-FORMAT.md: the cells must account for every row of kpi.csv, and `plausible` says which rows are incomplete. |
