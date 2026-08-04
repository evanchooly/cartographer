# WaterfallPanel UX Updates

## Context

`cartographer-intellij-plugin`'s `WaterfallPanel` (`src/main/kotlin/com/antwerkz/cartographer/intellij/ui/WaterfallPanel.kt`)
renders the trace/span waterfall view in the tool window. It currently:

- Hardcodes font sizes (`11f` for span-name labels, `9f` for duration/axis text) instead of
  following the IDE theme's font size.
- Fixes `ROW_HEIGHT` (22px) and `AXIS_HEIGHT` (24px) as constants, so a larger theme font can
  clip text within a row.
- Clamps `inner`'s preferred width to `parent.width` in `getPreferredSize()`, so bars/labels are
  always squeezed to fit the viewport — a horizontal scrollbar can never appear, even when content
  (e.g. long labels, many indent levels) can't be displayed legibly.
- Only wires plain mouse-wheel scrolling (via `JBScrollPane` default behavior); there is no
  Ctrl+wheel handling, and there is no concept of zoom — bars are always scaled to fit whatever
  width is available.
- Single-click on a span row selects it and shows `SpanDetailPanel`, which has a "→ Go to source"
  button wired to `SourceNavigator.navigate`. There's no way to jump to source directly from the
  waterfall.

This spec covers four related UX fixes to that panel.

## Requirements

1. **Theme-matched font size.** Span-name labels, duration text, and axis-tick labels all use the
   same font size as the rest of the IDE theme (no hardcoded point sizes). `ROW_HEIGHT` and
   `AXIS_HEIGHT` are derived from font metrics (text height + fixed padding) instead of fixed
   pixel constants, so rows never clip regardless of theme font size.

2. **Scrollbars instead of squeezing.** When the waterfall's content (label column + bar area)
   cannot be displayed legibly within the viewport — because the viewport is narrow, because a
   larger theme font increases the label column width, or because the user has zoomed in — a
   horizontal scrollbar appears via the existing `JBScrollPane`, rather than compressing
   bars/labels into unreadable widths. Vertical scrolling for tall content (many spans) already
   works today via row-count × row-height inside `JBScrollPane` and is preserved as-is (with the
   new font-aware `ROW_HEIGHT`).

3. **Ctrl+mouse-wheel zoom.** Holding Ctrl while scrolling the mouse wheel over the waterfall
   zooms the timeline in or out (scrolling up/away zooms in, down/toward zooms out), increasing or
   decreasing how many pixels represent a given time span. Zooming in can make the bar area wider
   than the viewport, at which point the horizontal scrollbar (requirement 2) is how the rest of
   the timeline is reached. Plain wheel scrolling (no Ctrl) continues to scroll vertically as
   today, unaffected by zoom level.

4. **Double-click to open source.** Double-clicking a span row in the waterfall navigates directly
   to that span's source method via `SourceNavigator.navigate`, using the same class/method
   resolution already implemented there. Single-click continues to just select the row and show
   `SpanDetailPanel`. The now-redundant "→ Go to source" button and its callback are removed from
   `SpanDetailPanel`.

## Design

### Font sizing

- Remove `g.font.deriveFont(11f)` / `deriveFont(9f)`. Use `inner.font` (the component's font,
  which IntelliJ's L&F sets from the current theme) directly for all text painted in
  `paintWaterfall`: span-name labels, duration labels, and axis-tick labels.
- Compute `ROW_HEIGHT` per-paint (or cache on font change) as
  `g.fontMetrics.height + fixed vertical padding` instead of the `22` constant.
- Compute `AXIS_HEIGHT` similarly from font metrics plus padding for the tick marks, instead of
  the `24` constant.
- `inner.getPreferredSize()` uses the computed `ROW_HEIGHT`/`AXIS_HEIGHT` when calculating total
  height: `AXIS_HEIGHT + flatSpans.size * ROW_HEIGHT`.

### Scrollbars for overflow

- `LABEL_WIDTH` becomes derived from font metrics: the width of the longest visible
  `simpleName` among `flatSpans`, plus fixed padding, capped at a reasonable maximum (to avoid one
  long name blowing out the column) — replacing the fixed `200`.
- Introduce a `MIN_BAR_AREA_WIDTH` constant (the minimum pixel width below which bars/ticks stop
  being legible, e.g. `300`).
- Add a `zoomFactor: Double` field on `WaterfallPanel`, default `1.0`, clamped to a fixed range
  (e.g. `1.0..20.0` — `1.0` means "fit to viewport", today's behavior; zooming out past fit isn't
  useful since bars would just shrink into the same squeeze this spec is removing).
- `inner.getPreferredSize()` width becomes:
  `LABEL_WIDTH + max(MIN_BAR_AREA_WIDTH, (parent?.width ?: default) - LABEL_WIDTH) * zoomFactor`.
  At `zoomFactor == 1.0` this reproduces today's fit-to-viewport behavior (no scrollbar). As
  `zoomFactor` increases, preferred width grows past the viewport width and `JBScrollPane` shows a
  horizontal scrollbar. A larger theme font (wider `LABEL_WIDTH`) or a narrow viewport can also
  push preferred width past `MIN_BAR_AREA_WIDTH`'s floor even at `zoomFactor == 1.0`.
- The bar-scaling math in `paintWaterfall` (`barAreaWidth`, `available`, `barX`, `barW`) is
  unchanged — it already scales spans to whatever `barAreaWidth` it's given (computed from
  `inner.width`, which now reflects the zoomed preferred width once laid out by the scroll pane).

### Ctrl+mouse-wheel zoom

- Add a `MouseWheelListener` to `inner` (added in `init`, alongside the existing
  `MouseAdapter`). On `mouseWheelMoved(e)`:
  - If `e.isControlDown`: update `zoomFactor` multiplicatively by a fixed step per notch (e.g.
    `*1.1` per notch scrolled up/away, `/1.1` per notch scrolled down/toward — `e.wheelRotation`
    gives direction), clamp to the zoom range, call `inner.revalidate()` and `inner.repaint()` so
    `getPreferredSize()` picks up the new width, and call `e.consume()` so default vertical
    scrolling doesn't also fire.
  - Otherwise, do nothing (let the event propagate to `JBScrollPane`'s default vertical
    scrolling, unchanged).
- Zoom is anchored at the current viewport scroll position (no attempt to keep the point under
  the cursor fixed) — simplest behavior that satisfies the requirement; cursor-anchored zoom can
  be a follow-up if it proves annoying in practice.

### Double-click to open source

- `WaterfallPanel`'s constructor gains a second callback parameter,
  `onSpanActivated: (SpanNode) -> Unit`, following the existing `onSpanSelected` pattern.
- In the existing `MouseAdapter.mouseClicked(e)`, after resolving `row`/`span` as today: if
  `e.clickCount == 2`, call `onSpanActivated(span)` in addition to (or instead of, since selection
  is a prerequisite state) the existing single-click selection logic. Single click still just
  selects/repaints/calls `onSpanSelected`.
- `CartographerToolWindowFactory` wires the new callback:
  `WaterfallPanel(onSpanSelected = { span -> detailPanel.show(span) }, onSpanActivated = { span -> SourceNavigator.navigate(project, span.name) })`.
- `SpanDetailPanel`'s constructor drops the `onGoToSource` callback parameter; the
  `goToSourceButton` field, its `addActionListener`, and its inclusion in the `content` FlowLayout
  are removed. `CartographerToolWindowFactory`'s `SpanDetailPanel { ... }` call is updated to a
  no-arg constructor.

## Testing

- Existing plugin tests (`src/test/kotlin/.../intellij`) should be checked for coverage of
  `WaterfallPanel` mouse handling and `SpanDetailPanel` construction; update/add tests for:
  - Double-click on a row triggers `onSpanActivated` with the correct `SpanNode`.
  - Single-click continues to trigger only `onSpanSelected`.
  - `SpanDetailPanel` no longer exposes a "Go to source" button.
  - Ctrl+wheel zoom updates `zoomFactor` in the expected direction and stays within the clamped
    range (e.g. repeated zoom-in eventually saturates at the max instead of growing unbounded).
  - Plain wheel (no Ctrl) does not change `zoomFactor`.
- Font/layout/scrollbar behavior is visual and not practically unit-testable; verify manually by
  running the plugin (`./gradlew runIde` or equivalent) with a changed IDE font size and a narrow
  tool window.

## Out of scope

- Cursor-anchored zoom (keeping the timeline point under the mouse fixed while zooming). Zoom
  anchors at the current scroll position instead.
- Any other way to change zoom (toolbar buttons, keyboard shortcuts, reset-to-fit control) beyond
  Ctrl+wheel.
- Changes to `TraceListPanel` or `SpanDetailPanel` layout beyond removing the button.
- Changes to `SourceNavigator`'s resolution logic — it's reused as-is.
