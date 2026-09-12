# 5gto6G FieldTap design system — "Clearsheet"

The rules every screen follows. Tokens live in `ui/theme`, components in `ui/components`, and the one
entry point is `com.fieldtap.ui.FieldTapTheme`. Every component has `@FieldTapPreviews` previews: light,
dark, and font scale 1.3.

**Clearsheet** is a clean, modern, broadly-acceptable utility look, built for instant familiarity and ease
of use — not a brand statement. A neutral palette (cool near-white/soft-grey surfaces, near-black text)
with a **single restrained accent** used sparingly on primary actions, selection and focus only; calm
sentence-case section labels; a flat, labelled signal meter; a neutral chart well with a faint zone tint;
hairline-led, near-flat cards; and calm, standard motion with no bounce. Light-first and daylight-legible,
with a true-dark theme that is a real equal, which walk mode forces. The **functional signal-quality
colours are data, not branding**, and are kept exactly.

## Principles

1. **Trust every number.** Live values use tabular figures, show their age, grey out when stale, and show
   a dash (never 0) when unknown. Numbers never animate their value.
2. **Colour is never the only cue.** Every signal colour has its level word, every tone its mark, every
   state its words; the meter always names its thresholds.
3. **One obvious primary action, one hero number per screen.** The filled accent button is the dominant
   control; the eye lands on the one big number (`numeric.display` on Live, `numeric.hero` in a card)
   first, and everything else steps down.
4. **Neutral, not branded.** The accent is a calm blue used only where it must be; no identity colour, no
   accent-tinted surfaces. Dynamic colour is off, so the app looks the same on every phone.
5. **Instant familiarity.** Plain sentence-case language, generous space, large targets (48 dp min, 64 dp
   primary button), minimal steps, calm standard motion — a best-in-class mainstream utility, done well.

## Entry point

```kotlin
FieldTapTheme { /* activity content */ }     // follows the system light/dark setting
FieldTapTheme(darkTheme = true) { /* */ }    // walk mode: forces the dark surface; bar icons follow
```

Never wrap content in a bare `MaterialTheme`, never call `dynamicLightColorScheme`, never set system bar
colours yourself. The window theme (`res/values*/themes.xml`) paints the Compose surface colour, so launch
has no flash; `BrandResourcesTest` keeps `res/values/colors.xml` equal to the Kotlin tokens.

FieldTap's own components take their motion from the `Motion` token object (no-overshoot springs and short
cross-fades) and collapse every spec to `snap()` when the user has removed animations
(`rememberReducedMotion()`, from `Settings.Global.ANIMATOR_DURATION_SCALE`, exposed as
`LocalReducedMotion`), so their behaviour is unit-testable and independent of Material internals. Material 3
1.4.0's `MotionScheme` interface and the `MaterialTheme(colorScheme, motionScheme, …)` overload are
`internal` to the material3 module (the JVM bytecode is `public`, but the Kotlin metadata marks them
`internal`), so the theme uses the public, non-experimental `MaterialTheme(colorScheme, shapes, typography,
content)` overload and bare Material components (Switch, AlertDialog, the nav bar) keep Material's own
default motion.

| Read | From |
| --- | --- |
| Material roles, type scale, shapes | `MaterialTheme.colorScheme`, `.typography`, `.shapes` |
| Status tones, recording, chart colours | `FieldTapDesign.colors` |
| Signal scale colours | `FieldTapDesign.signal.of(quality)` |
| Tabular number styles, `display` | `FieldTapDesign.numeric` |
| The sentence-case section label | the `Eyebrow` / `EyebrowTag` components (style: `SectionLabel`) |
| Spacing, sizes, shapes by role | `Spacing`, `Sizes`, `ShapeRoles` |
| Depth (hairline + a whisper of shadow) | `Elevation`, and the `cardShadowElevation()` / `cardHairline()` helpers |
| Motion specs (reduced-aware) | `Motion.spatial/container/entry/effect(LocalReducedMotion.current)`, `Durations` |
| Icons | `FieldTapIcons` |
| Numbers as text | `Formats` |

No literal `Color(...)`, `dp`, `sp` or animation spec in screens: add a token here if one is missing.

## Colour

Light ("sheet") is a cool near-white canvas (`#F7F8FA`) with white cards; dark ("true dark") is a
near-black canvas (`#121316`), a real equal. The **accent** (`primary` — a calm blue, `#2C5CB0` light /
`#9EC1FF` dark) appears in exactly four places and nowhere else:

1. the single filled **primary button** per screen (Start session, Allow, Run diagnostics, Save…),
2. the **selected** state (nav item, chosen radio/segmented value, active tab, walk-mode-on circle),
3. the **focus** ring and the text cursor,
4. the **RSRP chart line** (RSRP is accent blue; SINR is the muted violet `tertiary` `#6D53B5` / `#C4A9FF`).

Everything else is neutral (`onSurface` / `onSurfaceVariant`) or a functional signal/status colour: top-bar
icons, section labels and their leading icons, list chevrons, dividers, switches-when-off. No accent-tinted
card backgrounds, no accent headers, no accent-filled chips (the one filled chip is recording).

- **Surfaces:** screen `background` (canvas); cards, tiles, rows, the floating action bar, sheets and the
  top bar `surfaceContainerLow`; chart wells `surfaceContainerHigh` + an `outlineVariant` hairline; chips
  and meter tracks `surfaceContainerHighest`.
- **Text:** `onSurface` for values and titles, `onSurfaceVariant` for labels, section labels and secondary
  text.
- **Boundaries:** `outline` for control boundaries that must read (text-field border, meter-track edge,
  focus ring — ≥ 3:1); `outlineVariant` for decorative hairlines (card frame, dividers, chart-well frame).
- **Depth (`Elevation`, `tonalElevation` always 0):** light cards = a 1 px `outlineVariant` hairline
  **plus** a whisper of `Elevation.Card` (2 dp) shadow (the canvas→card step is tiny, so the hairline is
  mandatory); dark cards = the hairline + the lift of `surfaceContainerLow` over the canvas, no shadow.
  Raised surfaces (sheets, dialogs, the action bar, the scrolled top bar) use `Elevation.Raised`. Chart
  wells are the hairline only. Use `cardShadowElevation()` and `cardHairline()`.
- **Status tones** (`StatusTone`, `FieldTapDesign.colors.status(tone)`, icon `statusIcon(tone)`): NEUTRAL;
  INFO (the accent); SUCCESS (granted, ready, 2 s cadence); WARNING (works but worse: 10 s cadence, an
  aging sample, advice); ERROR (blocked, failed, stale, lost). Each family has `color`, `onColor`,
  `container` and `onContainer`. The tonal `container`/`onContainer` fill is used **only** for WARNING and
  ERROR banners; calm tones sit on a plain card.
- **Recording** (`colors.recording`) is the running session, its own crimson family — never `error`.
  Filled chips are reserved for the recording state; all other chips are ghost (outline).
- **Charts:** RSRP `chartRsrp` (accent), SINR `chartSinr` (violet), decorative grid `chartGrid`, dashed
  threshold `chartReference` (`outline`), solid −105 dBm key line `chartKeyReference` (`onSurfaceVariant`).

`ThemeContrastTest` proves the palette in light and dark on the surfaces each thing is placed on: text
roles ≥ 4.5:1 on every surface; status words and signal `content` ≥ 4.5:1 on card and chip track; signal
`fill`/`edge`, chart lines and `outline` ≥ 3:1 on the grounds they frame; `onPrimary` ≥ 4.5 on `primary`.
The tightest pairs (guarded explicitly, DESIGN.md §2.6 of the spec) sit on the light canvas (`outline`
4.22), a light card (FAIR edge 4.25), a dark card (POOR edge 4.40), the light well (chart SINR ~5.18) and
the chip track (GOOD content 5.49, dark POOR content 6.05, `onSurfaceVariant` 7.41) — change a colour only
with the test green.

## Signal scale

One scale, `SignalScale`, identical to the report's route colours, with the same thresholds for LTE and NR.
**Frozen** — reviewers must not neutralise it; it is data.

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
- Display ranges are for drawing only (RSRP -140..-40, RSRQ -30..0, SINR -25..40). Text always shows the
  measured value. The emphasised reference line is -105 dBm (0 dB for SINR).
- A signal meter spans `SignalScale.barRange` (RSRP -130..-50); a Live chart panel spans
  `ChartMath.fittedRange`. `SignalScale.zones(metric, range)` gives the zones either draws.
- Session statistics (`SignalSummary`, from kpi.csv) are of one RAT: the median RSRP and the share below
  -105 dBm, labelled with the RAT ("Median LTE RSRP").
- Build one `SignalQualityLabels` per screen from string resources and pass `labels.of(quality)`.

## Typography

The system font (`FontFamily.Default`) at Material 3 sizes in sp, so text follows the user's font scale.
The character comes from scale and weight, not tracking or a bundled face: **Bold** display styles and
headlines and `titleLarge`, SemiBold `titleMedium`/`titleSmall`, Normal body/label.

The **section label** (`Eyebrow` component; style `SectionLabel`, from `labelLarge` + SemiBold, no
tracking) is a calm **sentence-case** overline above a metric or a section header — no uppercase, no
letter-spacing, nothing shouting. The text is shown verbatim, in `onSurfaceVariant`. `EyebrowTag` is a
subtle neutral identity pill (`NR N78`, `PLMN 311480`), text as-is with normal tracking — acronym
identifiers stay uppercase because that is how they are written.

| Numeric style | Use |
| --- | --- |
| `numeric.display` 56 sp | The Live serving-RSRP hero; rendered with `TextAutoSize` (min 36 sp) so it shrinks, never clips |
| `numeric.hero` 44 sp | Session-detail median RSRP: the hero within a card |
| `numeric.large` 32 sp | Metric-tile / stat-tile values |
| `numeric.medium` 22 sp | Compact tiles, elapsed time, statistics |
| `numeric.body`, `numeric.bodySmall` | Values in rows and lists |
| `numeric.label` | Badges and chips |
| `numeric.axis` | Chart axes |

The hero numeral is the plain system numeral, SemiBold, `-0.01em` tracking — a readable big number, never a
condensed or expressive face. Units (`dBm`, `dB`) render one step down in `onSurfaceVariant`,
baseline-aligned. Exactly one `display`/`hero` figure per screen. Any other changing number:
`MaterialTheme.typography.titleSmall.tabular()`. All numeric styles keep tabular figures.

## Shape, spacing, size, motion

- `ShapeRoles`: `Tile` (12 dp) for tiles/wells, list rows and chart panels; `Card` (16 dp) for section
  cards, banners and the floating action bar; `Sheet` (24 dp) for sheets and dialogs; `Field` (8 dp) for
  text fields; `Control` (12 dp) for buttons (rounded rectangles, not pills); `Pill` for badges, chips and
  identity tags; `Bar` (4 dp) for the signal meter, bar tracks and the spectrum strip.
- `Spacing` is a 4 dp grid: `ScreenGutter` 20 dp (`ScreenGutterWide` 32 dp from 600 dp), `SectionGap` 20 dp
  between cards, `ItemGap` 12 dp and `CardPadding` 20 dp inside them, `EyebrowGap` 6 dp.
- `Sizes.MinTouchTarget` (48 dp) for everything tappable; `PrimaryButtonHeight` 64 dp. Cap content at
  `Sizes.MaxContentWidth` (720 dp), centred, on tablets and in landscape; prose at `Sizes.MaxTextWidth`.
- A window at least `Sizes.WideLayoutMinWidth` wide and lower than `Sizes.ShortWindowMaxHeight` (a phone in
  landscape) moves primary actions to a `Sizes.ActionRailWidth` (168 dp) column beside the content, so the
  hero number stays in view. A dialog with fields fills such a window.
- Never let a separator (" · ") end a line: break a two-part value with "\n". A line that must stay one
  line (the recording strip, a list row's secondary line) has `maxLines = 1` and gives up its least
  important words before it would be cut short.
- Dense cards of labelled values use `SectionCard(itemGap = Spacing.Sm)` and
  `KeyValueRow(minHeight = Sizes.KeyValueRowDenseMinHeight)`.
- **Motion** — calm and standard, never showy. `Durations`: SHORT 150, MEDIUM 250, LONG 400 ms. `Motion`
  gives critically-damped, **no-overshoot** springs (`spatial` for position and the meter marker,
  `container` for resize, `entry` a calm settle for one-shot appearance — no bounce) and an `effect` tween
  for colour/opacity cross-fades. Numbers never tween their value; only their colour and the meter marker's
  position move. Each spec takes `LocalReducedMotion.current`: when animations are removed, every spec
  collapses to `snap()` and the recording dot goes steady.

## Icons, words, numbers

- `FieldTapIcons` are 24 dp line icons (Material 3 1.4 bundles none and no icon library is added). An icon
  that stands alone needs a content description; pass null when a label beside it says the same.
- Components take every user-facing word as a parameter and add no strings. Screens take the words from
  their own files (`strings_session_ui.xml`, `strings_setup_ui.xml`).
- `Formats` makes the numbers; the words around them come from resources. `ageSeconds(ms)` gives "2.1";
  `elapsed` "12:34" or "1:02:03"; `decimalBytes` "4.2 MB"; `oneDecimal` "88.3". Signal values are plain
  integers, and unknown stays null.

## Components

| Component | Use it for |
| --- | --- |
| `FieldTapTopBar`, `TopBarAction`, `TopBarToggleAction`, `rememberTopBarScroll` | Every screen's top bar; neutral icon actions. A 1 px hairline fades in once content scrolls under it. `TopBarToggleAction` sits in a tonal accent circle when on (walk mode). |
| `MetricTile`, `MetricGrid` | A live value with a sentence-case `Eyebrow` label, unit, quality chip, age badge and optional `footer` (a `SignalMeter`). `MetricEmphasis.HERO` is an open block on the canvas (no card); other tiles are white wells (hairline, a whisper of shadow). `MetricGrid(minCellWidth, maxColumns = 4)` keeps a four-tile headline at two columns on a phone at font scale 1.3. |
| `SecondaryMetricTile` | Two under a hero: RSRQ and SINR. A value the cell does not report is one 48 dp line ("Not reported"), no dash. |
| `AgeIndicator` | "2.1 s old": FRESH neutral, AGING WARNING with a timer, STALE ERROR with a warning. |
| `Eyebrow`, `EyebrowTag` | The sentence-case section label (`heading = true` makes it a TalkBack heading); `EyebrowTag` a subtle neutral identity pill. |
| `SignalQualityChip`, `SignalQualityLabels` | A **ghost chip** — a signal swatch and its level word — wherever a signal colour appears. |
| `SignalMeter` (was `SignalBar`) | The flat, labelled four-zone gauge over `SignalScale.barRange`, edge-stroked, with a neutral `onSurface` marker (2 dp `surfaceContainerLow` halo) that glides on change, and the thresholds ticked and labelled from 280 dp wide, the -105 key tick a hair heavier. Pair it with the number and a `SignalQualityChip`. |
| `SpectrumStrip` | The session-at-a-glance route verdict: one proportional stacked bar of the four route colours, edge-stroked; a `SignalQualityChip` legend beneath. |
| `CellSignalRow`, `SignalBars` | A neighbour or the NSA leg: bars, identity, value, level word. |
| `CadenceIndicator`, `cadenceTone` | Ghost chip: "2 s" (success) or "10 s" (warning), with the reason. |
| `StatusBanner` | A bordered banner: tone `color` for the leading icon, the tonal `container` fill **only** for WARNING/ERROR (which announce themselves); calm tones on a plain card. Optional accent fix button. |
| `StatusChip` | Ghost chips side by side: service, data, 5G icon, GPS, cadence. Tone lives in the leading mark and the word, never a filled background. Tabular text. |
| `FieldTapFloatingActionBar` | The inset raised `Surface` holding a screen's primary actions in portrait; the `ActionRailWidth` column replaces it in a short, wide window. |
| `SectionCard`, `KeyValueRow`, `SectionDivider` | Titled groups of labelled values; the header is a sentence-case `Eyebrow` with a neutral leading icon. `KeyValueRow(stacked, selectable)` for a SHA-256 or a URL; `itemGap`/`minHeight` for a dense card. |
| `ToggleRow`, `RadioRow`, `NavigationRow` | Settings rows: switch-on = accent; selected radio = accent ring; `NavigationRow` a neutral trailing chevron. |
| `ChecklistRow` | A check with its level and fix: Readiness items, findings. |
| `ReadinessSheet`, `ReadinessSheetContent`, `ReadinessProblems` | The pre-start sheet: named problems with fixes, blocking first, "Start anyway" only when nothing blocks. |
| `SessionButton`, `RecordingDot` | The one dominant primary action: a filled accent `Control` (12 dp), 64 dp tall, in the action bar. Start → Starting → Recording (elapsed + Stop) → Stopping. The recording dot is a gentle steady pulse; steady under reduced motion. Confirm Stop in a dialog. |
| `SignalHistoryChart`, `TimeSeriesChart`, `ChartMath` | Five minutes of RSRP and SINR over a neutral well: a faint zone tint (≤ 12 %), 1 dp dashed threshold lines in `chartReference` and a solid `chartKeyReference` -105 line, the accent RSRP line with a faint accent area fill, the violet SINR line, gaps left open, and a TalkBack summary. |
| `SessionListRow`, `RecordingChip` | A session on one 72 dp row: neutral status badge, name with its `SignalQualityChip` ("Good -92") or `RecordingChip`, and one line of start, duration and size. |
| `EmptyState`, `LoadingState` | Nothing to show, waiting, or could not load. A neutral badge, plain copy, an accent primary button when there is an action. Loading only for waits over ~300 ms. |
| `PermissionRationale` | A permission, why it is needed (neutral tonal icon circle), its status, and an accent fix button. |
| `LimitsStatementCard` | `R.string.limits_statement`, word for word, never truncated. |
| `FieldTapBrandMark` | The 5gto6G mark, on the disclosure and About screens only — a small signature, not a UI accent. |
| `PreviewSurface`, `@FieldTapPreviews` | Every preview, of components and screens alike. |

The theme already styles the Material components it does not wrap, so use them as they come: `Scaffold`,
`AlertDialog` (`Sheet` shape, `Elevation.Raised`), `OutlinedTextField`, `Button`, `TextButton`, `Snackbar`.
Pass `shape = ShapeRoles.Control` to a `Button`/`OutlinedButton`/`FilledTonalButton` (Material's default is
a stadium). The one filled accent `Button` per screen lives in the `FieldTapFloatingActionBar`.

## Brand assets

- Launcher: the adaptive icon `mipmap-anydpi/ic_launcher.xml` (and `ic_launcher_round.xml`), with the
  5gto6G blue-to-violet gradient background, the mark as foreground (five bars + a `#7FE7FF` sixth), and a
  monochrome layer for themed icons. This is a home-screen identity, not the app's chrome; the app's UI is
  neutral. The launcher hexes live in `res/values/colors.xml` = `BrandColors`.
- Window/splash: `res/values*/themes.xml` paint the neutral `surface` colour and the accent
  `colorPrimary`/`colorAccent`, kept equal to the Kotlin tokens by `BrandResourcesTest`.
- Notification: `R.drawable.ic_stat_fieldtap`, a flat silhouette for `setSmallIcon`.
- `FieldTapBrandMark` draws the mark in Compose for the disclosure and About screens.

## Accessibility checklist

- TalkBack: tiles, rows and chips read as one phrase; section labels are headings; the chart reads its
  summary; WARNING and ERROR banners announce themselves. Never put a live region on a value that ticks.
- 48 dp targets, no information by colour alone, and AA contrast from the tokens in both themes.
- Check every screen in its `@FieldTapPreviews`, in landscape, and with TalkBack. Numbers shrink to fit
  rather than clip; rows wrap.

## Tests

`app/src/test/kotlin/com/fieldtap/ui/theme` and `.../ui/components`: contrast (re-baselined for Clearsheet,
the tightest pairs guarded), the signal scale, number formats, brand resources against the XML, the motion
contract (physical specs by default, `snap()` under reduced motion; `entry` no longer bounces), chart and
grid arithmetic, and the component decisions (age and cadence tones, readiness ordering, level words).
