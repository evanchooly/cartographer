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
