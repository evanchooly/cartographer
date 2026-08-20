package com.antwerkz.cartographer.intellij.ui

import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import java.awt.Container
import java.io.File
import javax.swing.JButton
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceListPanelTest {

    private fun traceFile(fqcnDotMethod: String) = File("$fqcnDotMethod.json")

    private fun treeOf(panel: TraceListPanel): Tree {
        val scroll = panel.components.filterIsInstance<JBScrollPane>().first()
        return scroll.viewport.view as Tree
    }

    private fun <T> findComponent(container: Container, type: Class<T>): T {
        container.components.forEach {
            if (type.isInstance(it)) @Suppress("UNCHECKED_CAST") return it as T
            if (it is Container) {
                try {
                    return findComponent(it, type)
                } catch (_: NoSuchElementException) {
                    // keep searching siblings
                }
            }
        }
        throw NoSuchElementException("No component of type $type found")
    }

    private fun rootOf(panel: TraceListPanel): DefaultMutableTreeNode =
        treeOf(panel).model.root as DefaultMutableTreeNode

    @Test
    fun `initial refresh shows only collapsed class names, no method leaves`() {
        val panel = TraceListPanel(onSelect = {}, onRescan = {})
        val files =
            listOf(
                traceFile("com.example.FooTest.testA"),
                traceFile("com.example.FooTest.testB"),
                traceFile("com.example.BarTest.testC")
            )

        SwingUtilities.invokeAndWait { panel.refresh(mapOf<String?, List<File>>(null to files)) }

        val root = rootOf(panel)
        assertEquals(2, root.childCount)
        val classNodes =
            (0 until root.childCount).map { root.getChildAt(it) as DefaultMutableTreeNode }
        classNodes.forEach { node ->
            assertTrue(node.userObject is TraceListPanel.ClassHeader)
            assertEquals(1, node.childCount)
            val onlyChild = node.getChildAt(0) as DefaultMutableTreeNode
            assertFalse(onlyChild.userObject is TraceListPanel.TraceLeaf)
        }
    }

    @Test
    fun `expanding a class node lazily resolves and sorts its method leaves`() {
        val panel = TraceListPanel(onSelect = {}, onRescan = {})
        val files =
            listOf(traceFile("com.example.FooTest.testB"), traceFile("com.example.FooTest.testA"))
        SwingUtilities.invokeAndWait { panel.refresh(mapOf<String?, List<File>>(null to files)) }

        val tree = treeOf(panel)
        val classNode = rootOf(panel).getChildAt(0) as DefaultMutableTreeNode
        SwingUtilities.invokeAndWait { tree.expandPath(TreePath(classNode.path)) }

        assertEquals(2, classNode.childCount)
        val labels =
            (0 until classNode.childCount)
                .map {
                    (classNode.getChildAt(it) as DefaultMutableTreeNode).userObject
                        as TraceListPanel.TraceLeaf
                }
                .map { it.label }
        assertEquals(listOf("testA", "testB"), labels)
    }

    @Test
    fun `collapsing a class node discards its method leaves`() {
        val panel = TraceListPanel(onSelect = {}, onRescan = {})
        val files = listOf(traceFile("com.example.FooTest.testA"))
        SwingUtilities.invokeAndWait { panel.refresh(mapOf<String?, List<File>>(null to files)) }

        val tree = treeOf(panel)
        val classNode = rootOf(panel).getChildAt(0) as DefaultMutableTreeNode
        SwingUtilities.invokeAndWait { tree.expandPath(TreePath(classNode.path)) }
        assertEquals(1, classNode.childCount)

        SwingUtilities.invokeAndWait { tree.collapsePath(TreePath(classNode.path)) }

        assertEquals(1, classNode.childCount)
        val onlyChild = classNode.getChildAt(0) as DefaultMutableTreeNode
        assertFalse(onlyChild.userObject is TraceListPanel.TraceLeaf)
    }

    @Test
    fun `selecting a leaf reports its file, refresh and expand do not select anything`() {
        var selected: File? = null
        val panel = TraceListPanel(onSelect = { selected = it }, onRescan = {})
        val file = traceFile("com.example.FooTest.testA")
        SwingUtilities.invokeAndWait {
            panel.refresh(mapOf<String?, List<File>>(null to listOf(file)))
        }

        val tree = treeOf(panel)
        val classNode = rootOf(panel).getChildAt(0) as DefaultMutableTreeNode
        SwingUtilities.invokeAndWait { tree.expandPath(TreePath(classNode.path)) }
        assertNull(selected)

        val leafNode = classNode.getChildAt(0) as DefaultMutableTreeNode
        SwingUtilities.invokeAndWait { tree.selectionPath = TreePath(leafNode.path) }

        assertEquals(file, selected)
    }

    @Test
    fun `multiple modules auto-expand module rows but leave class rows collapsed`() {
        val panel = TraceListPanel(onSelect = {}, onRescan = {})
        val modules =
            mapOf<String?, List<File>>(
                "module-a" to listOf(traceFile("com.example.FooTest.testA")),
                "module-b" to listOf(traceFile("com.example.BarTest.testB"))
            )

        SwingUtilities.invokeAndWait { panel.refresh(modules) }

        val tree = treeOf(panel)
        val root = rootOf(panel)
        assertEquals(2, root.childCount)
        (0 until root.childCount).forEach { i ->
            val moduleNode = root.getChildAt(i) as DefaultMutableTreeNode
            assertTrue(moduleNode.userObject is TraceListPanel.ModuleHeader)
            assertTrue(tree.isExpanded(TreePath(moduleNode.path)))

            val classNode = moduleNode.getChildAt(0) as DefaultMutableTreeNode
            assertFalse(tree.isExpanded(TreePath(classNode.path)))
        }
    }

    @Test
    fun `rescan button invokes callback and setScanning toggles button and spinner`() {
        var rescanCount = 0
        val panel = TraceListPanel(onSelect = {}, onRescan = { rescanCount++ })
        val button = findComponent(panel, JButton::class.java)
        val spinner = findComponent(panel, JBLabel::class.java)
        assertTrue(spinner.icon is AnimatedIcon)

        assertTrue(button.isEnabled)
        assertFalse(spinner.isVisible)

        SwingUtilities.invokeAndWait { button.doClick() }
        assertEquals(1, rescanCount)

        SwingUtilities.invokeAndWait { panel.setScanning(true) }
        assertFalse(button.isEnabled)
        assertTrue(spinner.isVisible)

        SwingUtilities.invokeAndWait { panel.setScanning(false) }
        assertTrue(button.isEnabled)
        assertFalse(spinner.isVisible)
    }
}
