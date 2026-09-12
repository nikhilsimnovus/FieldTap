# 5gto6G FieldTap design system — "Fieldbook"

The rules every screen follows. Tokens live in `ui/theme`, components in `ui/components`, and the one
entry point is `com.fieldtap.ui.FieldTapTheme`. Every component has `@FieldTapPreviews` previews: light,
dark, and font scale 1.3.

**Fieldbook** is a bright, editorial field-analytics aesthetic: a warm neutral *paper* ground with
pure-white cards that lift on soft depth **and** a 1 px hairline; uppercase, letter-spaced *eyebrows*
leading exactly one big honest number per screen; a continuous four-zone *signal meter* with ticked,
labelled thresholds as the signature element; a single **Cobalt** brand (violet demoted to the SINR line);
ghost (outline) chips; and a genuinely deep **true-dark** theme that walk mode forces. Bright by
construction for daylight; the dark theme is a real equal for night.

## Principles

1. **Trust every number.** Live values use tabular figures, show their age, grey out when stale, and show
   a dash (never 0) when unknown. Numbers never animate their value.
2. **Colour is never the only cue.** Every signal colour has its level word, every tone its mark, every
   state its words; the meter always names its thresholds.
3. **One big number per screen.** An editorial hero — `numeric.display` on Live, `numeric.hero` in a card —
   the eye lands on before it reads. Everything else steps down.
4. **Same on every phone.** Dynamic colour is off; the brand and the signal scale ignore the wallpaper.
   Walk mode forces true-dark.
5. **Calm, not flashy. Daylight first.** Low-chrome, hairline-and-soft-depth, one accent. Motion is short
   and physical — an instrument settling, never a bounce. Every load-bearing mark clears AA on its real
   surface, and a card is never defined by shadow alone (so every card carries a 1 px hairline).

## Entry point

```kotlin
FieldTapTheme { /* activity content */ }     // follows the system light/dark setting
FieldTapTheme(darkTheme = true) { /* */ }    // walk mode: forces the dark surface; bar icons follow
```

Never wrap content in a bare `MaterialTheme`, never call `dynamicLightColorScheme`, never set system bar
colours yourself. The window theme (`res/values*/themes.xml`) paints the Compose surface colour, so launch
has no flash; `BrandResourcesTest` keeps `res/values/colors.xml` equal to the Kotlin tokens.

FieldTap's own components take their motion from the `Motion` token object (springs and short cross-fades)
and collapse every spec to `snap()` when the user has removed animations (`rememberReducedMotion()`, from
`Settings.Global.ANIMATOR_DURATION_SCALE`, exposed as `LocalReducedMotion`), so their behaviour is
unit-testable and independent of Material internals. Material 3 1.4.0's `MotionScheme` interface and the
`MaterialTheme(colorScheme, motionScheme, …)` overload are `internal` to the material3 module — the JVM
bytecode is `public`, but the Kotlin metadata marks them `internal`, so they cannot be referenced from this
module — so the theme uses the public, non-experimental `MaterialTheme(colorScheme, shapes, typography,
content)` overload and bare Material components (Switch, AlertDialog, the nav bar) keep Material's own
default motion.

| Read | From |
| --- | --- |
| Material roles, type scale, shapes | `MaterialTheme.colorScheme`, `.typography`, `.shapes` |
| Status tones, recording, chart colours | `FieldTapDesign.colors` |
| Signal scale colours | `FieldTapDesign.signal.of(quality)` |
| Tabular number styles, `display` | `FieldTapDesign.numeric` |
| The editorial overline | the `Eyebrow` / `EyebrowTag` components (style: `theme.Eyebrow`) |
| Spacing, sizes, shapes by role | `Spacing`, `Sizes`, `ShapeRoles` |
| Depth (shadow + hairline) | `Elevation`, and the `cardShadowElevation()` / `cardHairline()` helpers |
| Motion specs (reduced-aware) | `Motion.spatial/container/entry/effect(LocalReducedMotion.current)`, `Durations` |
| Icons | `FieldTapIcons` |
| Numbers as text | `Formats` |

No literal `Color(...)`, `dp`, `sp` or animation spec in screens: add a token here if one is missing.

## Colour

**Cobalt** (`primary #2C33C7` light / `#A7B2FF` dark) is the single accent; **violet** (`tertiary`) is
demoted to the SINR line only. Neither collides with the signal scale (greens, orange, red) or warnings
(amber). Light is warm **paper** (`#F6F6F4`) with white cards; dark is genuine **true-dark ink**
(`#0A0B0D`).

- **Surfaces:** screen `background` (paper/ink); cards, tiles, rows, the floating action bar and sheets
  `surfaceContainerLow`; chart wells `surfaceContainerLowest`; chips and meter tracks `surfaceContainerHighest`.
- **Text:** `onSurface` for values and titles, `onSurfaceVariant` for labels, eyebrows and secondary text.
- **Depth (`Elevation`, `tonalElevation` always 0):** light cards = `Elevation.Card` shadow **plus** a 1 px
  `outlineVariant` hairline (the paper→card step is only 1.08:1, so the hairline is mandatory); dark cards =
  no shadow (does not read on ink) + the hairline. Raised surfaces (sheets, dialogs, the action bar, the top
  bar once scrolled) use `Elevation.Raised`. Chart wells are the hairline only. Use `cardShadowElevation()`
  and `cardHairline()` so a card lifts the same way in both themes.
- **Status tones** (`StatusTone`, `FieldTapDesign.colors.status(tone)`, icon `statusIcon(tone)`): NEUTRAL;
  INFO (brand); SUCCESS (granted, ready, 2 s cadence); WARNING (works but worse: 10 s cadence, an aging
  sample, advice); ERROR (blocked, failed, stale, lost). Each family has `color` (text on any surface),
  `onColor`, `container` and `onContainer`.
- **Recording** (`colors.recording`) is the running session. Never use `error` for it. Filled chips are
  reserved for the recording state; all other chips are ghost (outline).
- **Charts:** RSRP `chartRsrp` (Cobalt), SINR `chartSinr` (violet), grid `chartGrid`, reference lines
  `chartReference`.

`ThemeContrastTest` proves the palette in light and dark on the surfaces each thing is placed on: text
roles at least 4.5:1 on every surface; status words and signal marks at least 4.5:1 / 3:1 on the content
surfaces (all but `surfaceDim`, a dimmed backdrop); `outline` and chart lines at least 3:1 on the paper/
white grounds they frame. The tightest pairs sit on the chip track (light EXCELLENT fill 3.00, FAIR edge
3.33, success word 4.54) and on paper (`outline` 3.10), guarded explicitly — change a colour only with the
test green.

## Signal scale

One scale, `SignalScale`, identical to the report's route colours, with the same thresholds for LTE and NR.

| Level | RSRP (dBm) | RSRQ (dB) | SINR (dB) | Fill |
| --- | --- | --- | --- | --- |
| EXCELLENT | >= -85 | >= -10 | >= 20 | #1a9641 green |
| GOOD | >= -95 | >= -15 | >= 13 | #a6d96a light green |
| FAIR | >= -105 | >= -20 | >= 0 | #fdae61 orange |
| POOR | below | below | below | #d7191c red (#f0443e in dark) |

- `SignalScale.quality(metric, value)` is null for an unknown value: show the neutral swatch and the
  unknown word.
- `SignalLevelColors`: `fill` for swatches and bars (never text); `edge` around a fill, which keeps 3:1
  where the pale green and orange are too light; `onFill` for text on a fill; `content` for the level
  word on a surface.
- Display ranges are for drawing only (RSRP -140..-40, RSRQ -30..0, SINR -25..40, the report's axes).
  Text always shows the measured value. The emphasised reference line is -105 dBm (0 dB for SINR).
- A signal bar spans `SignalScale.barRange` (RSRP -130..-50) so its four zones are wide enough to tell apart; a Live chart
  panel spans `ChartMath.fittedRange`: at least `RSRP_CHART_RANGE` (-120..-60) or `SINR_CHART_RANGE` (-10..30), grown to
  the values it draws, at most the display range. `SignalScale.zones(metric, range)` gives the zones either draws.
- Session statistics (`SignalSummary`, from kpi.csv) are of one RAT, as the report keeps LTE and NR apart: the median RSRP
  and the share below -105 dBm, labelled with the RAT ("Median LTE RSRP").
- Build one `SignalQualityLabels` per screen from string resources and pass `labels.of(quality)`
  wherever a level colour appears.

## Typography

The system font (`FontFamily.Default`) at Material 3 sizes in sp, so text follows the user's font scale.
The editorial character comes from scale, weight and tracking, not a bundled face: **Bold** display styles
and **Bold** headlines and `titleLarge`, SemiBold `titleMedium`/`titleSmall`.

The **eyebrow** (`Eyebrow` component; style `theme.Eyebrow`, from `labelSmall` + SemiBold + 0.09em, rendered
UPPERCASE) is a small letter-spaced overline above every metric and section header, replacing heavy title
rows. `EyebrowTag` is the same type as a tracked-caps identity chip (`NR N78`, `PLMN 311480`).

| Numeric style | Use |
| --- | --- |
| `numeric.display` 56 sp | The Live serving-RSRP hero; the screen renders it with `TextAutoSize` (min 36 sp) so it shrinks, never clips |
| `numeric.hero` 44 sp | Session-detail median RSRP: the hero within a card |
| `numeric.large` 32 sp | Metric-tile / stat-well values |
| `numeric.medium` 22 sp | Compact tiles, elapsed time, statistics |
| `numeric.body`, `numeric.bodySmall` | Values in rows and lists |
| `numeric.label` | Badges and chips |
| `numeric.axis` | Chart axes |

Units (`dBm`, `dB`) render one step down in `onSurfaceVariant`, baseline-aligned. Exactly one `display`/
`hero` figure per screen. Any other text whose numbers change: `MaterialTheme.typography.titleSmall.tabular()`.

## Shape, spacing, size, motion

- `ShapeRoles`: `Tile` (12 dp) for tiles/wells, list rows and chart panels; `Card` (16 dp) for section
  cards, banners and the floating action bar; `Sheet` (24 dp) for sheets and dialogs; `Field` (8 dp) for
  text fields; `Control` (12 dp) for buttons (rounded rectangles, not pills); `Pill` for badges, chips and
  identity tags; `Bar` (4 dp) for the signal meter, bar tracks and the spectrum strip.
- `Spacing` is a 4 dp grid: `ScreenGutter` 20 dp (`ScreenGutterWide` 32 dp from 600 dp), `SectionGap` 20 dp
  between cards, `ItemGap` 12 dp and `CardPadding` 20 dp inside them, `EyebrowGap` 6 dp between an overline
  and the value it leads.
- `Sizes.MinTouchTarget` (48 dp) for everything tappable. Cap content at `Sizes.MaxContentWidth` (720 dp),
  centred, on tablets and in landscape; centred prose at `Sizes.MaxTextWidth`.
- A window at least `Sizes.WideLayoutMinWidth` wide and lower than `Sizes.ShortWindowMaxHeight` (a phone in
  landscape) has no room for a bottom action bar or a top bar: primary actions move to a `Sizes.ActionRailWidth`
  (168 dp) column beside the content, at its bottom, and the bar's actions to its top, so the number a screen is about
  stays in view. Two panes need `Sizes.WideLayoutMinWidth` beside that column; a smaller window keeps one pane. A dialog
  with fields fills such a window (Close, the title and the confirm action in a bar over a scrolling form).
- Words that explain a missing value wrap; a chip holds only a short state word, never a sentence.
- Never let a separator (" · ") end a line: break a value of two parts with "\n" (a phone model, then its Android version;
  a cell's identity, then its operator). A line that must stay one line (the recording strip, a list row's secondary line)
  has `maxLines = 1` and gives up its least important words before it would be cut short.
- Dense cards of labelled values (Overview, Collection, Files, Serving cell, Cadence details) use
  `SectionCard(itemGap = Spacing.Sm)` and `KeyValueRow(minHeight = Sizes.KeyValueRowDenseMinHeight)`.
- **Motion** — an instrument settling, not a toy. `Durations`: SHORT 150, MEDIUM 250, LONG 400 ms. `Motion`
  gives critically-damped springs (`spatial` for position and the meter marker, `container` for resize,
  `entry` a whisper of bounce for one-shot appearance) and a `effect` tween for colour/opacity cross-fades.
  Numbers never tween their value; only their colour and the meter marker's position move. Each spec takes
  `LocalReducedMotion.current`: when animations are removed, every spec collapses to `snap()` and the
  recording dot goes steady. (Bare Material components keep Material's own motion — its `MotionScheme` is
  `internal` in 1.4.0, so it cannot be customised from this module.)

## Icons, words, numbers

- `FieldTapIcons` are 24 dp line icons (Material 3 1.4 bundles none and no icon library is added). An
  icon that stands alone needs a content description; pass null when a label beside it says the same.
- Components take every user-facing word as a parameter and add no strings. Screens take the words from
  their own files (`strings_session_ui.xml`, `strings_setup_ui.xml`).
- `Formats` makes the numbers; the words around them come from resources. `ageSeconds(ms)` gives "2.1"
  for `<string name="age_old">%1$s s old</string>`; `elapsed` "12:34" or "1:02:03"; `decimalBytes`
  "4.2 MB"; `oneDecimal` "88.3". Signal values are plain integers, and unknown stays null.

## Components

| Component | Use it for |
| --- | --- |
| `FieldTapTopBar`, `TopBarAction`, `TopBarToggleAction`, `rememberTopBarScroll` | Every screen's top bar; screens need no experimental opt-in. Pass `rememberTopBarScroll()` and put `Modifier.nestedScroll(scroll.connection)` on the Scaffold, so the bar sets itself apart from content scrolled under it. `TopBarToggleAction` is a mode that is on or off (walk mode), in a tonal circle when on, with "On"/"Off" as its TalkBack state. `navigationIcon = FieldTapIcons.Close` for a full-screen dialog. |
| `MetricTile`, `MetricGrid` | A live value with an `Eyebrow` label, unit, quality chip, age badge and optional `footer` (a `SignalMeter`, or a quality chip under a compact value). `MetricEmphasis.HERO` is an open editorial block on the paper ground (no card); other tiles are bordered white wells (hairline, no shadow). `ageInHeader` puts the age beside the quality chip where the tile is wide; `valueTone` WARNING or ERROR colours a worse-than-expected number and adds the tone's icon. `MetricGrid(minCellWidth = Sizes.TileCompactMinWidth, maxColumns = 4)` keeps a four-tile headline at two columns on a phone at font scale 1.3. |
| `SecondaryMetricTile` | Two side by side under a hero tile: RSRQ and SINR on Live. A value the cell does not report is one 48 dp line, the label and why ("Not reported"), with no dash; when the cell reports neither, Live shows no tiles and says so in the hero. The dash is only for no serving cell. |
| `AgeIndicator` | "2.1 s old" from `LiveState.badge`: FRESH neutral, AGING amber with a timer, STALE red with a warning. |
| `Eyebrow`, `EyebrowTag` | The uppercase overline above every metric and section header (`heading = true` makes it a TalkBack heading); `EyebrowTag` is the tracked-caps identity chip (`NR N78`, `PLMN 311480`). |
| `SignalQualityChip`, `SignalQualityLabels` | A **ghost chip** — a signal swatch and its level word — wherever a signal colour appears. |
| `SignalMeter` (was `SignalBar`) | The signature: a continuous four-zone meter over `SignalScale.barRange`, edge-stroked, with a marker at the value (glides on change) and the thresholds ticked and labelled from 280 dp wide. Pair it with the number and a `SignalQualityChip`. (`SignalBar` remains as an alias.) |
| `SpectrumStrip` | The session-at-a-glance route verdict: one proportional stacked bar of the four route colours (share of samples per band), edge-stroked; place a `SignalQualityChip` legend beneath. |
| `CellSignalRow`, `SignalBars` | A neighbour or the NSA leg: bars, identity, value, level word. |
| `CadenceIndicator`, `cadenceTone` | Ghost chip: "2 s" (success) or "10 s" (warning), with the reason. Live shows the cadence as a `StatusChip` in `cadenceTone` among the other chips, and the reason in its cadence details. |
| `StatusBanner` | A bordered banner card with an optional fix: Wi-Fi forcing 10 s, a session interrupted, paused in a privacy zone, a refused listener. At the top of the content, one per cause; WARNING and ERROR announce themselves. |
| `StatusChip` | Ghost chips side by side: service, data, 5G icon, GPS. Tone lives in the leading mark and the word, never a filled background (that is reserved for recording). Tabular text keeps a per-second value's width. |
| `FieldTapFloatingActionBar` | The inset raised `Surface` holding a screen's primary actions in portrait (Start → Mark/Stop, or Share/Delete); the `ActionRailWidth` column replaces it in a short, wide window. |
| `SectionCard`, `KeyValueRow`, `SectionDivider` | Titled groups of labelled values; the header is an `Eyebrow` overline. `KeyValueRow(stacked = true, selectable = true)` for a SHA-256 or a URL; `itemGap` and `minHeight` for a dense card. |
| `ToggleRow`, `RadioRow`, `NavigationRow` | Settings rows: on or off (walk mode, tests, instant updates); one of several (share precision, inside `Modifier.selectableGroup()`); a link to a screen or system setting. |
| `ChecklistRow` | A check with its level and fix: Readiness items, probe findings. |
| `ReadinessSheet`, `ReadinessSheetContent`, `ReadinessProblems` | The pre-start sheet: named problems with fixes, blocking first, "Start anyway" only when nothing blocks. |
| `SessionButton`, `RecordingDot` | Start, Starting, Recording (elapsed time, Stop), Stopping — a filled Cobalt `Control` (12 dp), 64 dp tall, inside the floating action bar. Confirm Stop in a dialog. `stacked` in the landscape rail: the icon over a one-line short label, the full words for TalkBack. The recording dot goes steady under reduced motion. |
| `SignalHistoryChart`, `TimeSeriesChart`, `ChartMath` | Five minutes of RSRP and SINR over the scale's zones as flat bands, with reference lines labelled as far as labels fit apart (the key line's first), gaps left open, and a TalkBack summary. `sinrReported = false` shows SINR as one "Not reported by this cell" line instead of an empty panel, and the RSRP panel takes `Sizes.ChartPanelTallHeight`. |
| `SessionListRow`, `RecordingChip` | A session on one 72 dp row: status badge, name with its signal chip ("Good -92") or `RecordingChip`, and one line of start, duration and size, with "Interrupted" or "Unreadable" in its colour in the size's place. TalkBack reads the full stop reason. |
| `EmptyState`, `LoadingState` | Nothing to show, waiting, or could not load (tone ERROR). Show loading only for waits over about 300 ms. |
| `PermissionRationale` | A permission, why it is needed, its status and the fix button. |
| `LimitsStatementCard` | `R.string.limits_statement`, word for word, never truncated. |
| `FieldTapBrandMark` | The 5gto6G mark on the disclosure and About screens. |
| `PreviewSurface`, `@FieldTapPreviews` | Every preview, of components and screens alike. |

The theme already styles the Material components it does not wrap, so use them as they come: `Scaffold`,
`AlertDialog` (Stop and Delete confirmations, the Mark note, the Start details; `Sheet` shape,
`Elevation.Raised`), `OutlinedTextField` (no shape argument), `Button`, `TextButton`, `Snackbar`. Pass
`shape = ShapeRoles.Control` to a `Button`/`OutlinedButton`/`FilledTonalButton` (Material's default button
shape is a stadium; the shared components already do this). The primary CTA — one filled Cobalt `Button`
per screen — lives in the `FieldTapFloatingActionBar`.

### From app state to components

| State | Component input |
| --- | --- |
| `LiveState.serving`, `servingAgeMs`, `badge` | The open hero: `Eyebrow("SERVING · NR SA")`, the RSRP as `numeric.display`, a `SignalQualityChip`, then `SignalMeter(SignalMetric.RSRP, rsrp, stateDescription = labels.of(quality))`, with the `AgeIndicator` for `badge` |
| `LiveCell.rsrq`, `sinr` of the serving cell | Two `SecondaryMetricTile`s in a row under the hero, "SS-RSRQ" and "SS-SINR" on NR; one null with a serving cell is "Not reported" on one line; both null is one line in the hero, "SS-RSRQ and SS-SINR not reported by this cell" |
| `LiveState.nsaLeg`, `neighbours` | One `CellSignalRow` per cell, in the given order (strongest first) |
| `LiveState.shortInterval` | `CadenceIndicator(shortInterval = ...)` and `ChartMath.gapThresholdMs(shortInterval)` |
| `LiveState.rsrpSeries`, `sinrSeries`, `nowElapsedMs` | `SignalHistoryChart`, with the summary built from `ChartMath.stats` |
| `LiveState.service`, `data`, `display`, `lastFix` | `StatusChip`s |
| `SessionStatus` Idle, Starting, Recording, Stopping | `SessionButtonState`; `Formats.elapsed(snapshot.elapsedMs)` |
| `RecorderSnapshot.paused` | `StatusBanner(tone = StatusTone.INFO)`; Mark disabled |
| `RecorderSnapshot.holdingInputs`, `markersDropped` | Mark confirms "Marker kept until your location is known"; a rise in `markersDropped` is said in the snackbar, and for a few seconds in the notification |
| `SessionDetail.markersDropped` | A WARNING `StatusBanner` with the Flag icon at the top of Session detail: "2 markers were not saved: …" |
| `ReadinessItem.level` OK, ADVICE, BLOCKER | `ChecklistRow(tone = SUCCESS, WARNING, ERROR)`, with a fix button unless `target` is NONE |
| Problems found by Start | `ReadinessProblem(blocking = level == BLOCKER)`; the refusals NO_CONSENT, NO_PRECISE_LOCATION, LOCATION_OFF and STORAGE_FULL are blocking too |
| `SessionSummary.recording`, `readable`, `stoppedBy` | `SessionRowStatus` RECORDING, UNREADABLE, INTERRUPTED (when `stoppedBy` is an Android exit reason), else COMPLETED |
| `SessionSummary.signal` (`SignalSummary`) | The row's `SignalQualityChip` ("Good -92"), "No signal" when null; on Session detail the "Median LTE RSRP" and "Below -105 dBm" tiles, the share in WARNING above 10 % |

## Screen recipe

```kotlin
Scaffold(topBar = { FieldTapTopBar(title, onNavigateUp = onBack, navigateUpContentDescription = back) }) { padding ->
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(Spacing.ScreenGutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.SectionGap),
    ) { /* banners first, then SectionCards; LoadingState or EmptyState when there is nothing */ }
}
```

Constrain the content with `Modifier.widthIn(max = Sizes.MaxContentWidth)` and centre it when wider.

## Brand assets

- Launcher: the adaptive icon `mipmap-anydpi/ic_launcher.xml` (and `ic_launcher_round.xml`), with the
  Cobalt gradient background (`#1D24C4 → #5A2BD6`), the mark as foreground (five white bars + a `#7FE7FF`
  sixth), and a monochrome layer (sixth bar outlined) for themed icons. The gradient hexes live in
  `res/values/colors.xml` = `BrandColors`.
- Notification: `R.drawable.ic_stat_fieldtap`, a flat silhouette for `setSmallIcon`. The launcher
  foreground is a 108 dp layer and looks tiny in the status bar.
- `FieldTapBrandMark` draws the mark in Compose for the disclosure and About screens.

## Accessibility checklist

- TalkBack: tiles, rows and chips read as one phrase; section titles are headings; the chart reads its
  summary; WARNING and ERROR banners announce themselves. Never put a live region on a value that ticks.
- 48 dp targets, no information by colour alone, and AA contrast from the tokens.
- Check every screen in its `@FieldTapPreviews`, in landscape, and with TalkBack. Numbers shrink to fit
  rather than clip; rows wrap.

## Tests

`app/src/test/kotlin/com/fieldtap/ui/theme` and `.../ui/components`: contrast (re-baselined for Fieldbook),
the signal scale, number formats, brand resources against the XML, the motion contract (physical specs by
default, `snap()` under reduced motion), chart and grid arithmetic, and the component decisions (age and
cadence tones, readiness ordering, level words).
