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
