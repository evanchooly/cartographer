package com.antwerkz.cartographer.intellij.ui

import com.antwerkz.cartographer.intellij.model.SpanNode
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val COLOR_ROOT = Color(0x3d, 0x6b, 0x8e)
private val COLOR_CHILD = Color(0x5e, 0x8e, 0x4d)
private val COLOR_SELECTED = Color(0xe8, 0xa8, 0x38)
private val COLOR_HOVER = JBColor(Color(0, 0, 0, 20), Color(255, 255, 255, 20))
private const val ROW_PADDING = 8
private const val AXIS_PADDING = 10
private const val LABEL_PADDING = 6
private const val MAX_LABEL_WIDTH = 320
private const val MIN_LABEL_WIDTH = 60
private const val LABEL_RESIZE_HANDLE_PX = 4
private const val LABEL_SCROLL_STEP = 20
private const val DEFAULT_LABEL_WIDTH = 200
private const val DEFAULT_VIEWPORT_WIDTH = 600
private const val INDENT_PX = 12
private const val MIN_BAR_WIDTH = 2
private const val TICK_INTERVALS = 5 // produces TICK_INTERVALS+1 tick marks (0..TICK_INTERVALS)
private const val MIN_BAR_AREA_WIDTH = 300
private const val DURATION_LABEL_GAP = 4
private const val DURATION_LABEL_MARGIN = 8
private const val ZOOM_STEP = 1.1
private const val MIN_ZOOM = 1.0
private const val MAX_ZOOM = 20.0

class WaterfallPanel(
    private val onSpanSelected: (SpanNode) -> Unit,
    private val onSpanActivated: (SpanNode) -> Unit
) : JPanel() {

    private var flatSpans: List<SpanNode> = emptyList()
    var selectedSpan: SpanNode? = null
        private set

    private var hoveredSpan: SpanNode? = null

    private var rootStartNano: Long = 0
    private var totalNano: Long = 1
    private var zoomFactor: Double = MIN_ZOOM

    // Null until the user drags the label/bar divider; once set, it overrides the
    // content-based auto-fit width computed by labelWidth().
    private var labelColumnWidth: Int? = null
    private var resizingLabelColumn = false

    // Horizontal pan within the label column, for names too long to fit even after resizing.
    private var labelScrollOffset: Int = 0

    private val inner =
        object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                paintWaterfall(g as Graphics2D)
            }

            override fun getPreferredSize(): Dimension {
                val fm = getFontMetrics(font)
                val labelWidth = labelWidth(fm)
                val durationWidth = durationLabelWidth(fm)
                val viewportWidth = parent?.width ?: DEFAULT_VIEWPORT_WIDTH
                val baseBarAreaWidth =
                    max(MIN_BAR_AREA_WIDTH, viewportWidth - labelWidth - durationWidth)
                val barAreaWidth = (baseBarAreaWidth * zoomFactor).toInt()
                return Dimension(
                    labelWidth + barAreaWidth + durationWidth,
                    axisHeight(fm) + flatSpans.size * rowHeight(fm)
                )
            }

            override fun getToolTipText(event: MouseEvent): String? {
                val fm = getFontMetrics(font)
                val axisHeight = axisHeight(fm)
                if (event.y < axisHeight) return null
                val row = (event.y - axisHeight) / rowHeight(fm)
                if (row !in flatSpans.indices) return null
                val span = flatSpans[row]
                val label = span.simpleName
                return if (fm.stringWidth(label) > labelWidth(fm) - LABEL_PADDING * 2) label
                else null
            }
        }

    private val scroll = JBScrollPane(inner)

    init {
        layout = BorderLayout()
        add(scroll, BorderLayout.CENTER)
        inner.background = JBColor.background()
        inner.toolTipText = ""
        val mouseHandler =
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val fm = inner.getFontMetrics(inner.font)
                    val span = spanAtY(e.y, fm) ?: return
                    selectedSpan = span
                    inner.repaint()
                    onSpanSelected(span)
                    if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                        onSpanActivated(span)
                    }
                }

                override fun mouseExited(e: MouseEvent) {
                    if (hoveredSpan != null) {
                        hoveredSpan = null
                        inner.repaint()
                    }
                }

                override fun mousePressed(e: MouseEvent) {
                    val fm = inner.getFontMetrics(inner.font)
                    if (isOnLabelDivider(e.x, fm)) {
                        resizingLabelColumn = true
                        e.consume()
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    resizingLabelColumn = false
                }

                override fun mouseDragged(e: MouseEvent) {
                    if (!resizingLabelColumn) return
                    val fm = inner.getFontMetrics(inner.font)
                    val durationWidth = durationLabelWidth(fm)
                    val maxWidth =
                        max(MIN_LABEL_WIDTH, inner.width - MIN_BAR_AREA_WIDTH - durationWidth)
                    labelColumnWidth = e.x.coerceIn(MIN_LABEL_WIDTH, maxWidth)
                    inner.revalidate()
                    inner.repaint()
                    e.consume()
                }

                override fun mouseMoved(e: MouseEvent) {
                    val fm = inner.getFontMetrics(inner.font)
                    inner.cursor =
                        if (isOnLabelDivider(e.x, fm)) {
                            Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
                        } else {
                            Cursor.getDefaultCursor()
                        }

                    val span = spanAtY(e.y, fm)
                    if (span != hoveredSpan) {
                        hoveredSpan = span
                        inner.repaint()
                    }
                }
            }
        inner.addMouseListener(mouseHandler)
        inner.addMouseMotionListener(mouseHandler)
        inner.addMouseWheelListener { e ->
            val fm = inner.getFontMetrics(inner.font)
            if (e.isControlDown) {
                zoomFactor =
                    if (e.wheelRotation < 0) min(MAX_ZOOM, zoomFactor * ZOOM_STEP)
                    else max(MIN_ZOOM, zoomFactor / ZOOM_STEP)
                inner.revalidate()
                inner.repaint()
                e.consume()
            } else if (e.isShiftDown && e.x < labelWidth(fm)) {
                val maxOffset = maxLabelScrollOffset(fm)
                labelScrollOffset =
                    (labelScrollOffset + e.wheelRotation * LABEL_SCROLL_STEP).coerceIn(0, maxOffset)
                inner.repaint()
                e.consume()
            } else {
                // Registering a MouseWheelListener on `inner` stops AWT from forwarding
                // unhandled wheel events up to the scroll pane's own handler, so plain
                // (non-zoom) scrolling has to be re-dispatched to it explicitly here.
                scroll.dispatchEvent(
                    MouseWheelEvent(
                        scroll,
                        e.id,
                        e.`when`,
                        e.modifiersEx,
                        e.x,
                        e.y,
                        e.xOnScreen,
                        e.yOnScreen,
                        e.clickCount,
                        e.isPopupTrigger,
                        e.scrollType,
                        e.scrollAmount,
                        e.wheelRotation,
                        e.preciseWheelRotation
                    )
                )
            }
        }
    }

    fun load(roots: List<SpanNode>) {
        assert(SwingUtilities.isEventDispatchThread()) { "load() must be called on EDT" }
        flatSpans = flatten(roots)
        selectedSpan = null
        hoveredSpan = null
        zoomFactor = MIN_ZOOM
        labelScrollOffset = 0
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
        labelColumnWidth?.let {
            return it
        }
        if (flatSpans.isEmpty()) return DEFAULT_LABEL_WIDTH
        return min(MAX_LABEL_WIDTH, longestLabelTextWidth(fm) + LABEL_PADDING * 2)
    }

    private fun longestLabelTextWidth(fm: FontMetrics): Int =
        if (flatSpans.isEmpty()) 0 else flatSpans.maxOf { fm.stringWidth(it.simpleName) }

    private fun maxLabelScrollOffset(fm: FontMetrics): Int =
        max(0, longestLabelTextWidth(fm) - (labelWidth(fm) - LABEL_PADDING * 2))

    private fun isOnLabelDivider(x: Int, fm: FontMetrics) =
        abs(x - labelWidth(fm)) <= LABEL_RESIZE_HANDLE_PX

    private fun spanAtY(y: Int, fm: FontMetrics): SpanNode? {
        if (y < axisHeight(fm)) return null
        val row = (y - axisHeight(fm)) / rowHeight(fm)
        return flatSpans.getOrNull(row)
    }

    private fun durationLabelWidth(fm: FontMetrics): Int {
        if (flatSpans.isEmpty()) return 0
        val longest = flatSpans.maxOf { fm.stringWidth("%.0fms".format(it.durationMs)) }
        return DURATION_LABEL_GAP + longest + DURATION_LABEL_MARGIN
    }

    private fun paintWaterfall(g: Graphics2D) {
        val fm = g.fontMetrics
        val rowHeight = rowHeight(fm)
        val axisHeight = axisHeight(fm)
        val labelWidth = labelWidth(fm)
        val durationWidth = durationLabelWidth(fm)

        val w = inner.width
        val barAreaWidth = w - labelWidth - durationWidth
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

            if (span == hoveredSpan) {
                g.color = COLOR_HOVER
                g.fillRect(0, y, w, rowHeight)
            }

            // Label (clipped to the label column so long names truncate instead of
            // bleeding into the bar area)
            g.setClip(0, y, labelWidth, rowHeight)
            g.color = if (span == selectedSpan) JBColor.foreground() else JBColor.GRAY
            val label = span.simpleName
            val labelX = labelWidth - LABEL_PADDING - fm.stringWidth(label)
            g.drawString(
                label,
                labelX.coerceAtLeast(0) - labelScrollOffset,
                y + rowHeight - ROW_PADDING / 2
            )

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
                barX + barW + DURATION_LABEL_GAP,
                y + rowHeight - ROW_PADDING / 2
            )
        }
    }
}
