# WaterfallPanel UX Updates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the trace waterfall view in `cartographer-intellij-plugin` follow the IDE theme's font size, scroll instead of squeezing when content overflows, support Ctrl+wheel zoom, and support double-click-to-source navigation.

**Architecture:** All changes are confined to `WaterfallPanel.kt` (font/row sizing, zoom, double-click), with small follow-on edits to `CartographerToolWindowFactory.kt` (wiring a new callback) and `SpanDetailPanel.kt` (removing the now-redundant "Go to source" button). No new files, no new dependencies.

**Tech Stack:** Kotlin, Swing (`JPanel`, `JBScrollPane`), IntelliJ Platform SDK, JUnit4 (`kotlin("test-junit")`).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-03-waterfall-panel-ux-design.md` — follow it exactly; do not add scope beyond it (no cursor-anchored zoom, no toolbar/keyboard zoom controls, no `TraceListPanel` changes).
- All edits are within `cartographer-intellij-plugin`; do not touch `cartographer-agent` or `cartographer-maven-plugin`.
- Tests use plain JUnit4 (no `LightJavaCodeInsightFixtureTestCase`) since none of this touches PSI/project state — instantiate Swing components directly.
- Run tests with: `cd cartographer-intellij-plugin && ./gradlew test --tests "com.antwerkz.cartographer.intellij.ui.*"` (the Gradle project lives inside `cartographer-intellij-plugin/`, not the repo root — there is no root-level `gradlew`).

---

### Task 1: Font-aware row/axis sizing, drop hardcoded font sizes

**Files:**
- Modify: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/WaterfallPanel.kt`
- Test: `cartographer-intellij-plugin/src/test/kotlin/com/antwerkz/cartographer/intellij/ui/WaterfallPanelTest.kt` (new file)

**Interfaces:**
- Consumes: `SpanNode` (`spanId`, `name`, `startNano`, `endNano`, `attributes`, `depth`, `children`, `durationMs`, `simpleName`) from `com.antwerkz.cartographer.intellij.model`.
- Produces: `WaterfallPanel(onSpanSelected: (SpanNode) -> Unit)` (constructor unchanged in this task), `fun load(roots: List<SpanNode>)`, `var selectedSpan: SpanNode?` (public getter, private setter) — all unchanged signatures. Later tasks build on these.

- [ ] **Step 1: Write the failing tests**

Create `cartographer-intellij-plugin/src/test/kotlin/com/antwerkz/cartographer/intellij/ui/WaterfallPanelTest.kt`:

```kotlin
package com.antwerkz.cartographer.intellij.ui

import com.antwerkz.cartographer.intellij.model.SpanNode
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import org.junit.Assert.assertEquals
import org.junit.Test

class WaterfallPanelTest {

    private fun span(name: String, start: Long, end: Long, depth: Int = 0) =
        SpanNode(
            spanId = name,
            name = name,
            startNano = start,
            endNano = end,
            attributes = emptyMap(),
            depth = depth
        )

    private fun innerPanelOf(panel: WaterfallPanel): JPanel {
        val scroll = panel.getComponent(0) as JScrollPane
        return scroll.viewport.view as JPanel
    }

    @Test
    fun `preferred height matches font metrics based row and axis height`() {
        val panel = WaterfallPanel {}
        SwingUtilities.invokeAndWait {
            panel.load(listOf(span("com.example.Foo.bar", 0, 1_000_000)))
        }

        val inner = innerPanelOf(panel)
        val fm = inner.getFontMetrics(inner.font)
        val expectedHeight = (fm.height + 10) + 1 * (fm.height + 8)

        assertEquals(expectedHeight, inner.preferredSize.height)
    }

    @Test
    fun `single click selects the span under the cursor`() {
        var selected: SpanNode? = null
        val panel = WaterfallPanel { selected = it }
        val root = span("com.example.Foo.bar", 0, 1_000_000)
        SwingUtilities.invokeAndWait { panel.load(listOf(root)) }

        val inner = innerPanelOf(panel)
        val fm = inner.getFontMetrics(inner.font)
        val axisHeight = fm.height + 10
        val rowHeight = fm.height + 8
        val y = axisHeight + rowHeight / 2

        SwingUtilities.invokeAndWait {
            inner.dispatchEvent(
                MouseEvent(inner, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 10, y, 1, false)
            )
        }

        assertEquals(root, selected)
        assertEquals(root, panel.selectedSpan)
    }

    @Test
    fun `click below all rows selects nothing`() {
        var selected: SpanNode? = null
        val panel = WaterfallPanel { selected = it }
        SwingUtilities.invokeAndWait {
            panel.load(listOf(span("com.example.Foo.bar", 0, 1_000_000)))
        }

        val inner = innerPanelOf(panel)
        SwingUtilities.invokeAndWait {
            inner.dispatchEvent(
                MouseEvent(inner, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 10, 5000, 1, false)
            )
        }

        assertEquals(null, selected)
        assertEquals(null, panel.selectedSpan)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd cartographer-intellij-plugin && ./gradlew test --tests "com.antwerkz.cartographer.intellij.ui.WaterfallPanelTest"`
Expected: compiles against the current `WaterfallPanel` (no signature changes needed yet for this test file), but `preferred height matches font metrics based row and axis height` FAILS because current code uses fixed `ROW_HEIGHT = 22` / `AXIS_HEIGHT = 24` instead of `fm.height + 8` / `fm.height + 10`. (The other two tests should already pass against current code — that's fine, they lock in behavior this task must not break.)

- [ ] **Step 3: Replace `WaterfallPanel.kt` with the font-aware version**

Replace the full contents of `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/WaterfallPanel.kt` with:

```kotlin
package com.antwerkz.cartographer.intellij.ui

import com.antwerkz.cartographer.intellij.model.SpanNode
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.min

private val COLOR_ROOT = Color(0x3d, 0x6b, 0x8e)
private val COLOR_CHILD = Color(0x5e, 0x8e, 0x4d)
private val COLOR_SELECTED = Color(0xe8, 0xa8, 0x38)
private const val ROW_PADDING = 8
private const val AXIS_PADDING = 10
private const val LABEL_PADDING = 6
private const val MAX_LABEL_WIDTH = 320
private const val DEFAULT_LABEL_WIDTH = 200
private const val DEFAULT_VIEWPORT_WIDTH = 600
private const val INDENT_PX = 12
private const val MIN_BAR_WIDTH = 2
private const val TICK_INTERVALS = 5 // produces TICK_INTERVALS+1 tick marks (0..TICK_INTERVALS)

class WaterfallPanel(private val onSpanSelected: (SpanNode) -> Unit) : JPanel() {

    private var flatSpans: List<SpanNode> = emptyList()
    var selectedSpan: SpanNode? = null
        private set

    private var rootStartNano: Long = 0
    private var totalNano: Long = 1

    private val inner =
        object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                paintWaterfall(g as Graphics2D)
            }

            override fun getPreferredSize(): Dimension {
                val fm = getFontMetrics(font)
                return Dimension(
                    parent?.width ?: DEFAULT_VIEWPORT_WIDTH,
                    axisHeight(fm) + flatSpans.size * rowHeight(fm)
                )
            }
        }

    private val scroll = JBScrollPane(inner)

    init {
        layout = BorderLayout()
        add(scroll, BorderLayout.CENTER)
        inner.background = JBColor.background()
        inner.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val fm = inner.getFontMetrics(inner.font)
                    val row = (e.y - axisHeight(fm)) / rowHeight(fm)
                    if (row in flatSpans.indices) {
                        selectedSpan = flatSpans[row]
                        inner.repaint()
                        onSpanSelected(selectedSpan!!)
                    }
                }
            }
        )
    }

    fun load(roots: List<SpanNode>) {
        assert(SwingUtilities.isEventDispatchThread()) { "load() must be called on EDT" }
        flatSpans = flatten(roots)
        selectedSpan = null
        if (flatSpans.isNotEmpty()) {
            rootStartNano = flatSpans.minOf { it.startNano }
            totalNano = max(1L, flatSpans.maxOf { it.endNano } - rootStartNano)
        }
        inner.revalidate()
        inner.repaint()
    }

    private fun flatten(spans: List<SpanNode>): List<SpanNode> {
        val result = mutableListOf<SpanNode>()
        val stack = ArrayDeque<SpanNode>()
        spans.reversed().forEach { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val span = stack.removeLast()
            result += span
            span.children.reversed().forEach { stack.addLast(it) }
        }
        return result
    }

    private fun rowHeight(fm: FontMetrics) = fm.height + ROW_PADDING

    private fun axisHeight(fm: FontMetrics) = fm.height + AXIS_PADDING

    private fun labelWidth(fm: FontMetrics): Int {
        if (flatSpans.isEmpty()) return DEFAULT_LABEL_WIDTH
        val longest = flatSpans.maxOf { fm.stringWidth(it.simpleName) }
        return min(MAX_LABEL_WIDTH, longest + LABEL_PADDING * 2)
    }

    private fun paintWaterfall(g: Graphics2D) {
        val fm = g.fontMetrics
        val rowHeight = rowHeight(fm)
        val axisHeight = axisHeight(fm)
        val labelWidth = labelWidth(fm)

        val w = inner.width
        val barAreaWidth = w - labelWidth
        if (barAreaWidth <= 0) return

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Time axis
        g.color = JBColor.border()
        g.drawLine(labelWidth, axisHeight - 1, w, axisHeight - 1)
        for (i in 0..TICK_INTERVALS) {
            val x = labelWidth + (barAreaWidth * i / TICK_INTERVALS)
            g.color = JBColor.GRAY
            g.drawLine(x, axisHeight - 4, x, axisHeight)
            val ms = "%.0fms".format(totalNano / 1_000_000.0 * i / TICK_INTERVALS)
            g.drawString(ms, x + 2, axisHeight - 6)
        }

        // Clip to bar area to prevent bars bleeding outside bounds
        g.setClip(0, axisHeight, w, inner.height - axisHeight)

        // Span rows
        flatSpans.forEachIndexed { i, span ->
            val y = axisHeight + i * rowHeight
            val indent = span.depth * INDENT_PX

            // Label (draw outside clip by temporarily removing it)
            g.clip = null
            g.color = if (span == selectedSpan) JBColor.foreground() else JBColor.GRAY
            val label = span.simpleName
            val labelX = labelWidth - LABEL_PADDING - fm.stringWidth(label)
            g.drawString(label, labelX.coerceAtLeast(0), y + rowHeight - ROW_PADDING / 2)

            // Bar (clipped)
            g.setClip(labelWidth, axisHeight, barAreaWidth, inner.height - axisHeight)
            val relStart = span.startNano - rootStartNano
            val available = max(1, barAreaWidth - indent)
            val barX = labelWidth + indent + (relStart.toDouble() / totalNano * available).toInt()
            val barW =
                max(
                    MIN_BAR_WIDTH,
                    ((span.endNano - span.startNano).toDouble() / totalNano * available).toInt()
                )

            g.color =
                when {
                    span == selectedSpan -> COLOR_SELECTED
                    span.depth == 0 -> COLOR_ROOT
                    else -> COLOR_CHILD
                }
            val barHeight = rowHeight - ROW_PADDING
            g.fillRoundRect(barX, y + ROW_PADDING / 2, barW, barHeight, 3, 3)

            if (span == selectedSpan) {
                g.color = Color.WHITE
                g.drawRoundRect(barX, y + ROW_PADDING / 2, barW, barHeight, 3, 3)
            }

            // Duration label
            g.clip = null
            g.color = JBColor.GRAY
            g.drawString(
                "%.0fms".format(span.durationMs),
                barX + barW + 4,
                y + rowHeight - ROW_PADDING / 2
            )
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd cartographer-intellij-plugin && ./gradlew test --tests "com.antwerkz.cartographer.intellij.ui.WaterfallPanelTest"`
Expected: PASS (all three tests)

- [ ] **Step 5: Commit**

```bash
git add cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/WaterfallPanel.kt cartographer-intellij-plugin/src/test/kotlin/com/antwerkz/cartographer/intellij/ui/WaterfallPanelTest.kt
git commit -m "feat: derive WaterfallPanel row/axis/label sizing from theme font metrics"
```

---

### Task 2: Ctrl+wheel zoom with scrollbar overflow

**Files:**
- Modify: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/WaterfallPanel.kt`
- Test: `cartographer-intellij-plugin/src/test/kotlin/com/antwerkz/cartographer/intellij/ui/WaterfallPanelTest.kt`

**Interfaces:**
- Consumes: `rowHeight(fm)`, `axisHeight(fm)`, `labelWidth(fm)` private helpers from Task 1 (same file, unchanged).
- Produces: no new public API — `getPreferredSize()`'s width now depends on an internal `zoomFactor` reachable only via Ctrl+wheel; later tasks don't depend on this directly, but must not break the wheel listener wiring.

- [ ] **Step 1: Write the failing tests**

Add to `WaterfallPanelTest.kt` (new imports: `java.awt.event.InputEvent`, `java.awt.event.MouseWheelEvent`; new test methods):

```kotlin
    @Test
    fun `ctrl wheel zooms in and widens the preferred size`() {
        val panel = WaterfallPanel {}
        SwingUtilities.invokeAndWait {
            panel.load(listOf(span("com.example.Foo.bar", 0, 1_000_000)))
        }
        val inner = innerPanelOf(panel)
        val widthBefore = inner.preferredSize.width

        SwingUtilities.invokeAndWait {
            inner.dispatchEvent(
                MouseWheelEvent(
                    inner,
                    MouseWheelEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(),
                    InputEvent.CTRL_DOWN_MASK,
                    10,
                    10,
                    0,
                    false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    1,
                    -1
                )
            )
        }

        assertEquals(true, inner.preferredSize.width > widthBefore)
    }

    @Test
    fun `plain wheel does not change preferred width`() {
        val panel = WaterfallPanel {}
        SwingUtilities.invokeAndWait {
            panel.load(listOf(span("com.example.Foo.bar", 0, 1_000_000)))
        }
        val inner = innerPanelOf(panel)
        val widthBefore = inner.preferredSize.width

        SwingUtilities.invokeAndWait {
            inner.dispatchEvent(
                MouseWheelEvent(
                    inner,
                    MouseWheelEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(),
                    0,
                    10,
                    10,
                    0,
                    false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    1,
                    -1
                )
            )
        }

        assertEquals(widthBefore, inner.preferredSize.width)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd cartographer-intellij-plugin && ./gradlew test --tests "com.antwerkz.cartographer.intellij.ui.WaterfallPanelTest"`
Expected: `ctrl wheel zooms in and widens the preferred size` FAILS (no wheel listener exists yet, width unchanged). `plain wheel does not change preferred width` should already pass (nothing reacts to wheel events yet) — that's fine, it locks in the no-op case.

- [ ] **Step 3: Add zoom state and Ctrl+wheel handling**

In `WaterfallPanel.kt`, add new constants near the existing ones:

```kotlin
private const val MIN_BAR_AREA_WIDTH = 300
private const val ZOOM_STEP = 1.1
private const val MIN_ZOOM = 1.0
private const val MAX_ZOOM = 20.0
```

Add the import: `import java.awt.event.MouseWheelEvent` (Kotlin allows a lambda for the functional `MouseWheelListener` interface, so no separate import for the listener type is needed).

Add a `zoomFactor` field next to `rootStartNano`/`totalNano`:

```kotlin
    private var rootStartNano: Long = 0
    private var totalNano: Long = 1
    private var zoomFactor: Double = MIN_ZOOM
```

Replace `getPreferredSize()` inside `inner`:

```kotlin
            override fun getPreferredSize(): Dimension {
                val fm = getFontMetrics(font)
                val labelWidth = labelWidth(fm)
                val viewportWidth = parent?.width ?: DEFAULT_VIEWPORT_WIDTH
                val baseBarAreaWidth = max(MIN_BAR_AREA_WIDTH, viewportWidth - labelWidth)
                val barAreaWidth = (baseBarAreaWidth * zoomFactor).toInt()
                return Dimension(
                    labelWidth + barAreaWidth,
                    axisHeight(fm) + flatSpans.size * rowHeight(fm)
                )
            }
```

In `init`, after the existing `inner.addMouseListener(...)` block, add:

```kotlin
        inner.addMouseWheelListener { e ->
            if (e.isControlDown) {
                zoomFactor =
                    if (e.wheelRotation < 0) min(MAX_ZOOM, zoomFactor * ZOOM_STEP)
                    else max(MIN_ZOOM, zoomFactor / ZOOM_STEP)
                inner.revalidate()
                inner.repaint()
                e.consume()
            }
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd cartographer-intellij-plugin && ./gradlew test --tests "com.antwerkz.cartographer.intellij.ui.WaterfallPanelTest"`
Expected: PASS (all five tests)

- [ ] **Step 5: Commit**

```bash
git add cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/WaterfallPanel.kt cartographer-intellij-plugin/src/test/kotlin/com/antwerkz/cartographer/intellij/ui/WaterfallPanelTest.kt
git commit -m "feat: zoom WaterfallPanel timeline on Ctrl+mouse-wheel"
```

---

### Task 3: Double-click to open source, remove "Go to source" button

**Files:**
- Modify: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/WaterfallPanel.kt`
- Modify: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/SpanDetailPanel.kt`
- Modify: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/CartographerToolWindowFactory.kt`
- Test: `cartographer-intellij-plugin/src/test/kotlin/com/antwerkz/cartographer/intellij/ui/WaterfallPanelTest.kt`
- Test: `cartographer-intellij-plugin/src/test/kotlin/com/antwerkz/cartographer/intellij/ui/SpanDetailPanelTest.kt` (new file)

**Interfaces:**
- Consumes: `SourceNavigator.navigate(project: Project, spanName: String)` from `com.antwerkz.cartographer.intellij.SourceNavigator` (unchanged, existing).
- Produces: `WaterfallPanel(onSpanSelected: (SpanNode) -> Unit, onSpanActivated: (SpanNode) -> Unit)` — **breaking signature change**, all call sites must be updated in this task. `SpanDetailPanel()` — no-arg constructor, `onGoToSource` removed.

- [ ] **Step 1: Update existing WaterfallPanelTest call sites for the new constructor**

The five tests written in Tasks 1–2 all call `WaterfallPanel { ... }` (single-arg trailing lambda for `onSpanSelected`). Update each to pass a no-op second argument, e.g. change:

```kotlin
val panel = WaterfallPanel { selected = it }
```

to:

```kotlin
val panel = WaterfallPanel(onSpanSelected = { selected = it }, onSpanActivated = {})
```

and change bare `WaterfallPanel {}` to `WaterfallPanel(onSpanSelected = {}, onSpanActivated = {})`. Apply this to all five existing tests in the file.

- [ ] **Step 2: Write the failing tests for double-click and button removal**

Add to `WaterfallPanelTest.kt`:

```kotlin
    @Test
    fun `double click selects and activates the span`() {
        var selected: SpanNode? = null
        var activated: SpanNode? = null
        val panel = WaterfallPanel(onSpanSelected = { selected = it }, onSpanActivated = { activated = it })
        val root = span("com.example.Foo.bar", 0, 1_000_000)
        SwingUtilities.invokeAndWait { panel.load(listOf(root)) }

        val inner = innerPanelOf(panel)
        val fm = inner.getFontMetrics(inner.font)
        val y = (fm.height + 10) + (fm.height + 8) / 2

        SwingUtilities.invokeAndWait {
            inner.dispatchEvent(
                MouseEvent(inner, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 10, y, 1, false)
            )
            inner.dispatchEvent(
                MouseEvent(inner, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 10, y, 2, false)
            )
        }

        assertEquals(root, selected)
        assertEquals(root, activated)
    }

    @Test
    fun `single click does not activate the span`() {
        var activated: SpanNode? = null
        val panel = WaterfallPanel(onSpanSelected = {}, onSpanActivated = { activated = it })
        val root = span("com.example.Foo.bar", 0, 1_000_000)
        SwingUtilities.invokeAndWait { panel.load(listOf(root)) }

        val inner = innerPanelOf(panel)
        val fm = inner.getFontMetrics(inner.font)
        val y = (fm.height + 10) + (fm.height + 8) / 2

        SwingUtilities.invokeAndWait {
            inner.dispatchEvent(
                MouseEvent(inner, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 10, y, 1, false)
            )
        }

        assertEquals(null, activated)
    }
```

Create `cartographer-intellij-plugin/src/test/kotlin/com/antwerkz/cartographer/intellij/ui/SpanDetailPanelTest.kt`:

```kotlin
package com.antwerkz.cartographer.intellij.ui

import java.awt.Container
import javax.swing.JButton
import org.junit.Assert.assertFalse
import org.junit.Test

class SpanDetailPanelTest {

    private fun containsButton(container: Container): Boolean =
        container.components.any { it is JButton || (it is Container && containsButton(it)) }

    @Test
    fun `detail panel has no go to source button`() {
        val panel = SpanDetailPanel()
        assertFalse(containsButton(panel))
    }
}
```

- [ ] **Step 3: Run tests to verify the new ones fail**

Run: `cd cartographer-intellij-plugin && ./gradlew test --tests "com.antwerkz.cartographer.intellij.ui.*"`
Expected: compile error — `WaterfallPanel` doesn't yet accept a second constructor argument, and `SpanDetailPanel()` no-arg constructor doesn't exist yet. (This is expected: the test step precedes the production-code step, per TDD, even though here the failure is a compile error rather than an assertion failure — that's normal for a constructor-signature change.)

- [ ] **Step 4: Add `onSpanActivated` and double-click handling to `WaterfallPanel`**

In `WaterfallPanel.kt`, change the class declaration:

```kotlin
class WaterfallPanel(
    private val onSpanSelected: (SpanNode) -> Unit,
    private val onSpanActivated: (SpanNode) -> Unit
) : JPanel() {
```

Replace the `mouseClicked` body:

```kotlin
                override fun mouseClicked(e: MouseEvent) {
                    val fm = inner.getFontMetrics(inner.font)
                    val row = (e.y - axisHeight(fm)) / rowHeight(fm)
                    if (row in flatSpans.indices) {
                        val span = flatSpans[row]
                        selectedSpan = span
                        inner.repaint()
                        onSpanSelected(span)
                        if (e.clickCount == 2) {
                            onSpanActivated(span)
                        }
                    }
                }
```

- [ ] **Step 5: Remove the "Go to source" button from `SpanDetailPanel`**

Replace the full contents of `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/SpanDetailPanel.kt` with:

```kotlin
package com.antwerkz.cartographer.intellij.ui

import com.antwerkz.cartographer.intellij.model.SpanNode
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

class SpanDetailPanel : JPanel(BorderLayout()) {

    private val nameLabel = JLabel().apply { foreground = Color(0xe8, 0xa8, 0x38) }
    private val durationLabel = JLabel()
    private val attrsLabel = JLabel()

    init {
        border = EmptyBorder(4, 8, 4, 8)
        background = JBColor(Color(0x2b, 0x2b, 0x2b), Color(0x2b, 0x2b, 0x2b))
        isVisible = false

        val content =
            JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
                isOpaque = false
                add(nameLabel)
                add(durationLabel)
                add(attrsLabel)
            }
        add(content, BorderLayout.CENTER)
    }

    fun show(span: SpanNode) {
        nameLabel.text = span.simpleName
        durationLabel.text = "%.0fms".format(span.durationMs)
        attrsLabel.text =
            span.attributes.entries
                .filter { it.key.startsWith("arg.") }
                .sortedBy { it.key }
                .joinToString("  ") { "${it.key}: ${it.value}" }
        isVisible = true
        revalidate()
        repaint()
    }

    fun clear() {
        isVisible = false
    }
}
```

- [ ] **Step 6: Update `CartographerToolWindowFactory` wiring**

In `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/CartographerToolWindowFactory.kt`, replace:

```kotlin
        val detailPanel = SpanDetailPanel { span -> SourceNavigator.navigate(project, span.name) }

        val waterfallPanel = WaterfallPanel { span -> detailPanel.show(span) }
```

with:

```kotlin
        val detailPanel = SpanDetailPanel()

        val waterfallPanel =
            WaterfallPanel(
                onSpanSelected = { span -> detailPanel.show(span) },
                onSpanActivated = { span -> SourceNavigator.navigate(project, span.name) }
            )
```

- [ ] **Step 7: Run tests to verify everything passes**

Run: `cd cartographer-intellij-plugin && ./gradlew test --tests "com.antwerkz.cartographer.intellij.ui.*"`
Expected: PASS (all `WaterfallPanelTest` and `SpanDetailPanelTest` tests)

Then run the full plugin test suite to catch any other call site this plan didn't anticipate:

Run: `cd cartographer-intellij-plugin && ./gradlew test`
Expected: PASS, no compile errors elsewhere referencing the old `WaterfallPanel`/`SpanDetailPanel` constructors

- [ ] **Step 8: Commit**

```bash
git add cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/WaterfallPanel.kt cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/SpanDetailPanel.kt cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/CartographerToolWindowFactory.kt cartographer-intellij-plugin/src/test/kotlin/com/antwerkz/cartographer/intellij/ui/WaterfallPanelTest.kt cartographer-intellij-plugin/src/test/kotlin/com/antwerkz/cartographer/intellij/ui/SpanDetailPanelTest.kt
git commit -m "feat: double-click a span in the waterfall to open its source"
```

---

### Task 4: Manual verification

**Files:** none (manual QA pass, no code changes)

- [ ] **Step 1: Run the plugin sandbox**

Run: `cd cartographer-intellij-plugin && ./gradlew runIde`

- [ ] **Step 2: Verify font sizing**

Open the Cartographer tool window with a loaded trace. Change the IDE's editor/UI font size (Settings → Appearance & Behavior → Appearance, or Settings → Editor → Font) to a noticeably larger size, reopen/refresh the trace view, and confirm span labels, duration text, and axis ticks all grow to match — no clipped text, no leftover small hardcoded text.

- [ ] **Step 3: Verify scrollbar overflow**

Narrow the tool window (drag the splitter) until the label column plus minimum bar width can't fit. Confirm a horizontal scrollbar appears instead of bars/labels being crushed unreadably. Confirm vertical scrolling still works normally with a trace that has many spans.

- [ ] **Step 4: Verify Ctrl+wheel zoom**

With the mouse over the waterfall, hold Ctrl and scroll up: confirm the timeline zooms in (bars widen, a horizontal scrollbar appears/grows). Scroll down while still holding Ctrl: confirm it zooms back out, down to the fit-to-viewport minimum (no scrollbar). Confirm plain wheel scrolling (no Ctrl) still scrolls vertically and does not zoom.

- [ ] **Step 5: Verify double-click navigation**

Double-click a span row. Confirm the corresponding source method (or constructor) opens in the editor, matching today's "Go to source" button behavior. Confirm the button itself is gone from the detail panel, and that single-click still just selects the row and shows its details.
