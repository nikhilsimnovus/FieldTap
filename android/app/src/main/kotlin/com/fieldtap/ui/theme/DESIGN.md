# 5gto6G FieldTap design system

The rules every screen follows. Tokens live in `ui/theme`, components in `ui/components`, and the one
entry point is `com.fieldtap.ui.FieldTapTheme`. Every component has `@FieldTapPreviews` previews: light,
dark, and font scale 1.3.

## Principles

1. **Trust every number.** Live values use tabular figures, show their age, grey out when stale, and show
   a dash (never 0) when unknown.
2. **Colour is never the only cue.** Every signal colour has its level word, every tone its icon, every
   state its words.
3. **Glanceable in the field.** One hero number per screen, 48 dp targets, short motion.
4. **Same on every phone.** Dynamic colour is off; the brand and the signal scale ignore the wallpaper.

## Entry point

```kotlin
FieldTapTheme { /* activity content */ }     // follows the system light/dark setting
FieldTapTheme(darkTheme = true) { /* */ }    // walk mode: forces the dark surface; bar icons follow
```

Never wrap content in a bare `MaterialTheme`, never call `dynamicLightColorScheme`, never set system bar
colours yourself. The window theme (`res/values*/themes.xml`) paints the Compose surface colour, so launch
has no flash; `BrandResourcesTest` keeps `res/values/colors.xml` equal to the Kotlin tokens.

| Read | From |
| --- | --- |
| Material roles, type scale, shapes | `MaterialTheme.colorScheme`, `.typography`, `.shapes` |
| Status tones, recording, chart colours | `FieldTapDesign.colors` |
| Signal scale colours | `FieldTapDesign.signal.of(quality)` |
| Tabular number styles | `FieldTapDesign.numeric` |
| Spacing, sizes, shapes by role, motion | `Spacing`, `Sizes`, `ShapeRoles`, `Durations` |
| Icons | `FieldTapIcons` |
| Numbers as text | `Formats` |

No literal `Color(...)`, `dp` or `sp` in screens: add a token here if one is missing.

## Colour

Radio blue (primary) leading into 6G violet (tertiary), on cool neutral surfaces. Blue and violet never
collide with the signal scale (greens, orange, red) or with warnings (amber).

- **Surfaces:** screen `background`; cards, tiles, rows and sheets `surfaceContainerLow`; chart panels
  `surfaceContainerLowest`; chips and bar tracks `surfaceContainerHighest`.
- **Text:** `onSurface` for values and titles, `onSurfaceVariant` for labels and secondary text.
- **Status tones** (`StatusTone`, `FieldTapDesign.colors.status(tone)`, icon `statusIcon(tone)`): NEUTRAL;
  INFO (brand); SUCCESS (granted, ready, 2 s cadence); WARNING (works but worse: 10 s cadence, an aging
  sample, advice); ERROR (blocked, failed, stale, lost). Each family has `color` (text on any surface),
  `onColor`, `container` and `onContainer`.
- **Recording** (`colors.recording`) is the running session. Never use `error` for it.
- **Charts:** RSRP `chartRsrp` (blue), SINR `chartSinr` (violet), grid `chartGrid`, reference lines
  `chartReference`.

`ThemeContrastTest` proves the palette in light and dark: every text role and tone colour at least 4.5:1
on every surface, and `outline`, chart lines, signal marks and the brand mark at least 3:1. The tightest
pairs sit on `surfaceDim` (orange mark 3.04, light-green word 4.57), so change a colour only with that
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

The system font at Material 3 sizes in sp, so text follows the user's font scale; headlines and titles
are semibold.

| Numeric style | Use |
| --- | --- |
| `numeric.hero` 48 sp | The number a screen is about: serving RSRP on Live |
| `numeric.large` 32 sp | Metric tiles |
| `numeric.medium` 22 sp | Compact tiles, elapsed time, statistics |
| `numeric.body`, `numeric.bodySmall` | Values in rows and lists |
| `numeric.label` | Badges and chips |
| `numeric.axis` | Chart axes |

Any other text whose numbers change: `MaterialTheme.typography.titleSmall.tabular()`.

## Shape, spacing, size, motion

- `ShapeRoles`: `Tile` (14 dp) for tiles, list rows and chart panels; `Card` (20 dp) for section cards
  and banners; `Sheet` (28 dp) for sheets and dialogs; `Field` (6 dp) for text fields and row ripples;
  `Pill` for badges, chips and buttons; `Bar` for signal bars.
- `Spacing` is a 4 dp grid: `ScreenGutter` 16 dp (`ScreenGutterWide` 24 dp from 600 dp), `SectionGap`
  between cards, `ItemGap` and `CardPadding` inside them.
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
- `Durations`: SHORT 150, MEDIUM 250, LONG 400 ms. Animate colour and state, never the numbers.

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
| `MetricTile`, `MetricGrid` | A live value with unit, quality chip, age badge and optional `footer` (a `SignalBar`, or a quality chip under a compact value). `MetricEmphasis.HERO` for the main number; `ageInHeader` puts the age beside the quality chip where the tile is wide; `valueTone` WARNING or ERROR colours a worse-than-expected number and adds the tone's icon. `MetricGrid(minCellWidth = Sizes.TileCompactMinWidth, maxColumns = 4)` keeps a four-tile headline at two columns on a phone at font scale 1.3. |
| `SecondaryMetricTile` | Two side by side under a hero tile: RSRQ and SINR on Live. A value the cell does not report is one 48 dp line, the label and why ("Not reported"), with no dash; when the cell reports neither, Live shows no tiles and says so in the hero. The dash is only for no serving cell. |
| `AgeIndicator` | "2.1 s old" from `LiveState.badge`: FRESH neutral, AGING amber with a timer, STALE red with a warning. |
| `SignalQualityChip`, `SignalQualityLabels` | A swatch and its level word, wherever a signal colour appears. |
| `SignalBar` | A value's place on the scale, next to the number: the scale's four zones (the value's solid, the others pale) with a marker at the value, and the thresholds named under the gaps from 280 dp wide. |
| `CellSignalRow`, `SignalBars` | A neighbour or the NSA leg: bars, identity, value, level word. |
| `CadenceIndicator`, `cadenceTone` | "2 s" (success) or "10 s" (warning), with the reason. Live shows the cadence as a `StatusChip` in `cadenceTone` among the other chips, and the reason in its cadence details. |
| `StatusBanner` | A condition with an optional fix: Wi-Fi forcing 10 s, a session interrupted, paused in a privacy zone, a refused listener. At the top of the content, one per cause; WARNING and ERROR announce themselves. |
| `StatusChip` | Short states side by side: service, data, 5G icon, GPS. Its text has tabular figures, so a number that changes every second keeps the chip's width. |
| `SectionCard`, `KeyValueRow`, `SectionDivider` | Titled groups of labelled values. `KeyValueRow(stacked = true, selectable = true)` for a SHA-256 or a URL; `itemGap` and `minHeight` for a dense card. |
| `ToggleRow`, `RadioRow`, `NavigationRow` | Settings rows: on or off (walk mode, tests, instant updates); one of several (share precision, inside `Modifier.selectableGroup()`); a link to a screen or system setting. |
| `ChecklistRow` | A check with its level and fix: Readiness items, probe findings. |
| `ReadinessSheet`, `ReadinessSheetContent`, `ReadinessProblems` | The pre-start sheet: named problems with fixes, blocking first, "Start anyway" only when nothing blocks. |
| `SessionButton`, `RecordingDot` | Start, Starting, Recording (elapsed time, Stop), Stopping. Confirm Stop in a dialog. `stacked` in the landscape rail: the icon over a one-line short label, the full words for TalkBack. |
| `SignalHistoryChart`, `TimeSeriesChart`, `ChartMath` | Five minutes of RSRP and SINR over the scale's zones as flat bands, with reference lines labelled as far as labels fit apart (the key line's first), gaps left open, and a TalkBack summary. `sinrReported = false` shows SINR as one "Not reported by this cell" line instead of an empty panel, and the RSRP panel takes `Sizes.ChartPanelTallHeight`. |
| `SessionListRow`, `RecordingChip` | A session on one 72 dp row: status badge, name with its signal chip ("Good -92") or `RecordingChip`, and one line of start, duration and size, with "Interrupted" or "Unreadable" in its colour in the size's place. TalkBack reads the full stop reason. |
| `EmptyState`, `LoadingState` | Nothing to show, waiting, or could not load (tone ERROR). Show loading only for waits over about 300 ms. |
| `PermissionRationale` | A permission, why it is needed, its status and the fix button. |
| `LimitsStatementCard` | `R.string.limits_statement`, word for word, never truncated. |
| `FieldTapBrandMark` | The 5gto6G mark on the disclosure and About screens. |
| `PreviewSurface`, `@FieldTapPreviews` | Every preview, of components and screens alike. |

The theme already styles the Material components it does not wrap, so use them as they come: `Scaffold`,
`AlertDialog` (Stop and Delete confirmations, the Mark note, the Start details), `OutlinedTextField` (no
shape argument), `Button`, `TextButton`, `Snackbar`.

### From app state to components

| State | Component input |
| --- | --- |
| `LiveState.serving`, `servingAgeMs`, `badge` | Hero `MetricTile(value = rsrp?.toString(), quality = SignalScale.quality(SignalMetric.RSRP, rsrp), ageText = stringResource(R.string.age_old, Formats.ageSeconds(ageMs)), badge = badge) { SignalBar(SignalMetric.RSRP, rsrp) }` |
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

- Launcher: the adaptive icon `mipmap-anydpi/ic_launcher.xml`, with the gradient background, the mark as
  foreground, and a monochrome layer (sixth bar outlined) for themed icons.
- Notification: `R.drawable.ic_stat_fieldtap`, a flat silhouette for `setSmallIcon`. The launcher
  foreground is a 108 dp layer and looks tiny in the status bar.

## Accessibility checklist

- TalkBack: tiles, rows and chips read as one phrase; section titles are headings; the chart reads its
  summary; WARNING and ERROR banners announce themselves. Never put a live region on a value that ticks.
- 48 dp targets, no information by colour alone, and AA contrast from the tokens.
- Check every screen in its `@FieldTapPreviews`, in landscape, and with TalkBack. Numbers shrink to fit
  rather than clip; rows wrap.

## Tests

`app/src/test/kotlin/com/fieldtap/ui/theme` and `.../ui/components`: contrast, the signal scale, number
formats, brand resources against the XML, chart and grid arithmetic, and the component decisions (age and
cadence tones, readiness ordering, level words).
