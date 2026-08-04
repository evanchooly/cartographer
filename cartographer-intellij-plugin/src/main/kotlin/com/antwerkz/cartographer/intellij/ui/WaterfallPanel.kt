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
