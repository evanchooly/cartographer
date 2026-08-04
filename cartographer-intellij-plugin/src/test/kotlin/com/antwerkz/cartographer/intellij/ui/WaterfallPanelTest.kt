package com.antwerkz.cartographer.intellij.ui

import com.antwerkz.cartographer.intellij.model.SpanNode
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val panel = WaterfallPanel(onSpanSelected = {}, onSpanActivated = {})
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
        val panel = WaterfallPanel(onSpanSelected = { selected = it }, onSpanActivated = {})
        val root = span("com.example.Foo.bar", 0, 1_000_000)
        SwingUtilities.invokeAndWait { panel.load(listOf(root)) }

        val inner = innerPanelOf(panel)
        val fm = inner.getFontMetrics(inner.font)
        val axisHeight = fm.height + 10
        val rowHeight = fm.height + 8
        val y = axisHeight + rowHeight / 2

        SwingUtilities.invokeAndWait {
            inner.dispatchEvent(
                MouseEvent(
                    inner,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    10,
                    y,
                    1,
                    false
                )
            )
        }

        assertEquals(root, selected)
        assertEquals(root, panel.selectedSpan)
    }

    @Test
    fun `click below all rows selects nothing`() {
        var selected: SpanNode? = null
        val panel = WaterfallPanel(onSpanSelected = { selected = it }, onSpanActivated = {})
        SwingUtilities.invokeAndWait {
            panel.load(listOf(span("com.example.Foo.bar", 0, 1_000_000)))
        }

        val inner = innerPanelOf(panel)
        SwingUtilities.invokeAndWait {
            inner.dispatchEvent(
                MouseEvent(
                    inner,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    10,
                    5000,
                    1,
                    false
                )
            )
        }

        assertEquals(null, selected)
        assertEquals(null, panel.selectedSpan)
    }

    @Test
    fun `ctrl wheel zooms in and widens the preferred size`() {
        val panel = WaterfallPanel(onSpanSelected = {}, onSpanActivated = {})
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

        assertTrue(inner.preferredSize.width > widthBefore)
    }

    @Test
    fun `plain wheel does not change preferred width and is not consumed`() {
        // Regression test for the finding that registering a MouseWheelListener on `inner`
        // stops AWT from forwarding unhandled wheel events to JBScrollPane's own handler.
        // We can't reliably assert an actual scrollbar-position change in a headless test
        // (the viewport has no real size without a visible window), so instead we assert the
        // observable side effects of the fix: zoomFactor-driven width is unaffected by a plain
        // wheel event, AND the event is not left consumed -- pre-fix code's `if (isControlDown)`
        // branch simply does nothing in the else case, which happens to also leave the event
        // unconsumed, so this specific assertion alone doesn't distinguish pre/post fix. What
        // *does* distinguish them is that the fix re-dispatches the event to `scroll`, which is
        // exercised (without throwing) below; a real regression would be observed manually via
        // "plain scroll wheel no longer scrolls the waterfall vertically in a live IDE window".
        val panel = WaterfallPanel(onSpanSelected = {}, onSpanActivated = {})
        SwingUtilities.invokeAndWait {
            panel.load(listOf(span("com.example.Foo.bar", 0, 1_000_000)))
        }
        val inner = innerPanelOf(panel)
        val widthBefore = inner.preferredSize.width

        val event =
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

        SwingUtilities.invokeAndWait { inner.dispatchEvent(event) }

        assertEquals(widthBefore, inner.preferredSize.width)
        assertFalse(event.isConsumed)
    }

    @Test
    fun `plain wheel listener re-dispatches to the scroll pane instead of swallowing the event`() {
        // Verifies the mechanism of the fix directly: a MouseWheelListener registered on a
        // component with a plain (non-Ctrl) wheel event must forward that event on to the
        // JBScrollPane so its default vertical-scroll UI handler still runs. We verify this by
        // installing our own MouseWheelListener on `scroll` and confirming it receives a
        // synthetic event when a plain wheel event is dispatched to `inner`. Against the
        // pre-fix code (no `else` branch re-dispatching to `scroll`), this listener would never
        // be invoked for a plain wheel event, so this test fails pre-fix and passes post-fix.
        val panel = WaterfallPanel(onSpanSelected = {}, onSpanActivated = {})
        SwingUtilities.invokeAndWait {
            panel.load(listOf(span("com.example.Foo.bar", 0, 1_000_000)))
        }
        val inner = innerPanelOf(panel)
        val scroll = panel.getComponent(0) as JScrollPane

        var receivedByScroll = false
        scroll.addMouseWheelListener { receivedByScroll = true }

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

        assertTrue(
            "plain wheel event on `inner` should be re-dispatched to `scroll` so vertical " +
                "scrolling keeps working",
            receivedByScroll
        )
    }

    @Test
    fun `double click selects and activates the span`() {
        var selected: SpanNode? = null
        var activated: SpanNode? = null
        val panel =
            WaterfallPanel(onSpanSelected = { selected = it }, onSpanActivated = { activated = it })
        val root = span("com.example.Foo.bar", 0, 1_000_000)
        SwingUtilities.invokeAndWait { panel.load(listOf(root)) }

        val inner = innerPanelOf(panel)
        val fm = inner.getFontMetrics(inner.font)
        val y = (fm.height + 10) + (fm.height + 8) / 2

        SwingUtilities.invokeAndWait {
            inner.dispatchEvent(
                MouseEvent(
                    inner,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    10,
                    y,
                    1,
                    false
                )
            )
            inner.dispatchEvent(
                MouseEvent(
                    inner,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    InputEvent.BUTTON1_DOWN_MASK,
                    10,
                    y,
                    2,
                    false,
                    MouseEvent.BUTTON1
                )
            )
        }

        assertEquals(root, selected)
        assertEquals(root, activated)
    }

    @Test
    fun `right button double click selects but does not activate`() {
        var selected: SpanNode? = null
        var activated: SpanNode? = null
        val panel =
            WaterfallPanel(onSpanSelected = { selected = it }, onSpanActivated = { activated = it })
        val root = span("com.example.Foo.bar", 0, 1_000_000)
        SwingUtilities.invokeAndWait { panel.load(listOf(root)) }

        val inner = innerPanelOf(panel)
        val fm = inner.getFontMetrics(inner.font)
        val y = (fm.height + 10) + (fm.height + 8) / 2

        SwingUtilities.invokeAndWait {
            inner.dispatchEvent(
                MouseEvent(
                    inner,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    InputEvent.BUTTON3_DOWN_MASK,
                    10,
                    y,
                    2,
                    false,
                    MouseEvent.BUTTON3
                )
            )
        }

        assertEquals(root, selected)
        assertNull(activated)
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
                MouseEvent(
                    inner,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    10,
                    y,
                    1,
                    false
                )
            )
        }

        assertEquals(null, activated)
    }

    @Test
    fun `click inside the axis header selects nothing`() {
        // Regression test: (e.y - axisHeight) / rowHeight truncates towards zero, so without a
        // guard, any e.y in roughly [3, axisHeight) resolves to row 0 instead of "no row".
        var selected: SpanNode? = null
        var activated: SpanNode? = null
        val panel =
            WaterfallPanel(onSpanSelected = { selected = it }, onSpanActivated = { activated = it })
        SwingUtilities.invokeAndWait {
            panel.load(listOf(span("com.example.Foo.bar", 0, 1_000_000)))
        }

        val inner = innerPanelOf(panel)
        val fm = inner.getFontMetrics(inner.font)
        val axisHeight = fm.height + 10
        val y = axisHeight - 1

        SwingUtilities.invokeAndWait {
            inner.dispatchEvent(
                MouseEvent(
                    inner,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    10,
                    y,
                    2,
                    false
                )
            )
        }

        assertNull(selected)
        assertNull(activated)
        assertNull(panel.selectedSpan)
    }

    @Test
    fun `repeated ctrl wheel zoom in saturates at the max zoom`() {
        val panel = WaterfallPanel(onSpanSelected = {}, onSpanActivated = {})
        SwingUtilities.invokeAndWait {
            panel.load(listOf(span("com.example.Foo.bar", 0, 1_000_000)))
        }
        val inner = innerPanelOf(panel)

        fun zoomIn(notches: Int) {
            SwingUtilities.invokeAndWait {
                repeat(notches) {
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
            }
        }

        zoomIn(100)
        val widthAt100 = inner.preferredSize.width
        zoomIn(1)
        val widthAt101 = inner.preferredSize.width

        assertEquals(
            "zoomFactor should have saturated at MAX_ZOOM well before 100 notches",
            widthAt100,
            widthAt101
        )
    }

    @Test
    fun `repeated ctrl wheel zoom out floors at the min zoom`() {
        val panel = WaterfallPanel(onSpanSelected = {}, onSpanActivated = {})
        SwingUtilities.invokeAndWait {
            panel.load(listOf(span("com.example.Foo.bar", 0, 1_000_000)))
        }
        val inner = innerPanelOf(panel)
        val widthAtMinZoom = inner.preferredSize.width

        SwingUtilities.invokeAndWait {
            repeat(100) {
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
                        1
                    )
                )
            }
        }

        assertEquals(
            "zoomFactor should not go below MIN_ZOOM (the un-zoomed fit-to-viewport width)",
            widthAtMinZoom,
            inner.preferredSize.width
        )
    }
}
