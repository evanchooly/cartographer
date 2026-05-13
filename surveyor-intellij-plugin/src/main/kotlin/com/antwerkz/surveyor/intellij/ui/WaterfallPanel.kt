package com.antwerkz.surveyor.intellij.ui

import com.antwerkz.surveyor.intellij.model.SpanNode
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.max

private val COLOR_ROOT = Color(0x3d, 0x6b, 0x8e)
private val COLOR_CHILD = Color(0x5e, 0x8e, 0x4d)
private val COLOR_SELECTED = Color(0xe8, 0xa8, 0x38)
private const val ROW_HEIGHT = 22
private const val AXIS_HEIGHT = 24
private const val LABEL_WIDTH = 200
private const val INDENT_PX = 12
private const val MIN_BAR_WIDTH = 2
private const val TICK_INTERVALS = 5  // produces TICK_INTERVALS+1 tick marks (0..TICK_INTERVALS)

class WaterfallPanel(private val onSpanSelected: (SpanNode) -> Unit) : JPanel() {

    private var flatSpans: List<SpanNode> = emptyList()
    var selectedSpan: SpanNode? = null
        private set
    private var rootStartNano: Long = 0
    private var totalNano: Long = 1

    private val inner = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            paintWaterfall(g as Graphics2D)
        }
        override fun getPreferredSize() =
            Dimension(parent?.width ?: 600, AXIS_HEIGHT + flatSpans.size * ROW_HEIGHT)
    }

    private val scroll = JBScrollPane(inner)

    init {
        layout = BorderLayout()
        add(scroll, BorderLayout.CENTER)
        inner.background = JBColor.background()
        inner.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = (e.y - AXIS_HEIGHT) / ROW_HEIGHT
                if (row in flatSpans.indices) {
                    selectedSpan = flatSpans[row]
                    inner.repaint()
                    onSpanSelected(selectedSpan!!)
                }
            }
        })
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

    private fun paintWaterfall(g: Graphics2D) {
        val w = inner.width
        val barAreaWidth = w - LABEL_WIDTH
        if (barAreaWidth <= 0) return

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Pre-derive fonts once
        val labelFont = g.font.deriveFont(11f)
        val smallFont = g.font.deriveFont(9f)

        // Time axis
        g.font = smallFont
        g.color = JBColor.border()
        g.drawLine(LABEL_WIDTH, AXIS_HEIGHT - 1, w, AXIS_HEIGHT - 1)
        for (i in 0..TICK_INTERVALS) {
            val x = LABEL_WIDTH + (barAreaWidth * i / TICK_INTERVALS)
            g.color = JBColor.GRAY
            g.drawLine(x, AXIS_HEIGHT - 4, x, AXIS_HEIGHT)
            val ms = "%.0fms".format(totalNano / 1_000_000.0 * i / TICK_INTERVALS)
            g.drawString(ms, x + 2, AXIS_HEIGHT - 6)
        }

        // Clip to bar area to prevent bars bleeding outside bounds
        g.setClip(0, AXIS_HEIGHT, w, inner.height - AXIS_HEIGHT)

        // Span rows
        flatSpans.forEachIndexed { i, span ->
            val y = AXIS_HEIGHT + i * ROW_HEIGHT
            val indent = span.depth * INDENT_PX

            // Label (draw outside clip by temporarily removing it)
            g.clip = null
            g.color = if (span == selectedSpan) JBColor.foreground() else JBColor.GRAY
            g.font = labelFont
            val label = span.simpleName
            val labelX = LABEL_WIDTH - 6 - g.fontMetrics.stringWidth(label)
            g.drawString(label, labelX.coerceAtLeast(0), y + ROW_HEIGHT - 6)

            // Bar (clipped)
            g.setClip(LABEL_WIDTH, AXIS_HEIGHT, barAreaWidth, inner.height - AXIS_HEIGHT)
            val relStart = span.startNano - rootStartNano
            val available = max(1, barAreaWidth - indent)
            val barX = LABEL_WIDTH + indent + (relStart.toDouble() / totalNano * available).toInt()
            val barW = max(MIN_BAR_WIDTH, ((span.endNano - span.startNano).toDouble() / totalNano * available).toInt())

            g.color = when {
                span == selectedSpan -> COLOR_SELECTED
                span.depth == 0 -> COLOR_ROOT
                else -> COLOR_CHILD
            }
            g.fillRoundRect(barX, y + 5, barW, ROW_HEIGHT - 10, 3, 3)

            if (span == selectedSpan) {
                g.color = Color.WHITE
                g.drawRoundRect(barX, y + 5, barW, ROW_HEIGHT - 10, 3, 3)
            }

            // Duration label
            g.clip = null
            g.color = JBColor.GRAY
            g.font = smallFont
            g.drawString("%.0fms".format(span.durationMs), barX + barW + 4, y + ROW_HEIGHT - 6)
        }
    }
}
