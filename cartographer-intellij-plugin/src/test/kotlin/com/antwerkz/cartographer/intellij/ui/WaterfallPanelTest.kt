package com.antwerkz.cartographer.intellij.ui

import com.antwerkz.cartographer.intellij.model.SpanNode
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
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
        val panel = WaterfallPanel { selected = it }
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
}
