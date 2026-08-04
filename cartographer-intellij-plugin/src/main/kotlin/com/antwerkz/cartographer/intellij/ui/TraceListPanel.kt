package com.antwerkz.cartographer.intellij.ui

import com.antwerkz.cartographer.intellij.OtlpJsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.io.File
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.DefaultTreeSelectionModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class TraceListPanel(private val onSelect: (File) -> Unit) : JPanel(BorderLayout()) {
    companion object {
        private val log = Logger.getInstance(TraceListPanel::class.java)
    }


    private val root = DefaultMutableTreeNode("root")
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.selectionModel = object : DefaultTreeSelectionModel() {
            override fun setSelectionPath(path: TreePath?) {
                val node = path?.lastPathComponent as? DefaultMutableTreeNode
                if (node?.userObject is ModuleHeader) return
                super.setSelectionPath(path)
            }
            override fun addSelectionPath(path: TreePath?) {
                val node = path?.lastPathComponent as? DefaultMutableTreeNode
                if (node?.userObject is ModuleHeader) return
                super.addSelectionPath(path)
            }
        }
        tree.cellRenderer = TraceTreeCellRenderer()
        tree.addTreeSelectionListener { e ->
            val node = e.path?.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val leaf = node.userObject as? TraceLeaf ?: return@addTreeSelectionListener
            onSelect(leaf.file)
        }
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    fun refresh(modules: Map<String?, List<File>>) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val durations: Map<File, Double?> = modules.values.flatten()
                .associateWith { f ->
                    try {
                        OtlpJsonParser.parse(f).firstOrNull()?.durationMs
                    } catch (e: Exception) {
                        log.warn("Failed to parse ${f.name}", e)
                        null
                    }
                }
            ApplicationManager.getApplication().invokeLater { buildTree(modules, durations) }
        }
    }

    private fun buildTree(modules: Map<String?, List<File>>, durations: Map<File, Double?>) {
        root.removeAllChildren()

        val singleModule = modules.size == 1 && modules.containsKey(null)

        if (singleModule) {
            populateClassNodes(root, modules[null] ?: emptyList(), durations)
        } else {
            modules.entries.sortedBy { it.key ?: "" }.forEach { (moduleName, files) ->
                val moduleNode = DefaultMutableTreeNode(ModuleHeader(moduleName ?: ""))
                populateClassNodes(moduleNode, files, durations)
                if (moduleNode.childCount > 0) root.add(moduleNode)
            }
        }

        if (root.childCount == 0) {
            root.add(DefaultMutableTreeNode("No traces yet — run your tests"))
        }

        model.reload()
        for (i in 0 until tree.rowCount) tree.expandRow(i)
    }

    private fun populateClassNodes(parent: DefaultMutableTreeNode, files: List<File>, durations: Map<File, Double?>) {
        val byClass = files
            .filter { it.name != "cartographer-run.json" }
            .mapNotNull { file ->
                val parseFileName = parseFileName(file)
                val (fqcn, method) = parseFileName ?: return@mapNotNull null
                Triple(file, fqcn, method)
            }
            .groupBy { (_, fqcn, _) -> fqcn }
            .toSortedMap()

        byClass.forEach { (fqcn, entries) ->
            val simpleClass = fqcn.substringAfterLast('.')
            val classNode = DefaultMutableTreeNode(simpleClass)
            entries.sortedBy { it.third }.forEach { (file, _, method) ->
                classNode.add(DefaultMutableTreeNode(TraceLeaf(file, method, durations[file])))
            }
            parent.add(classNode)
        }

        val runFile = files.firstOrNull { it.name == "cartographer-run.json" }
        if (runFile != null) {
            parent.add(DefaultMutableTreeNode(TraceLeaf(runFile, "cartographer-run", null)))
        }
    }

    private fun parseFileName(file: File): Pair<String, String>? {
        val base = file.nameWithoutExtension
        val lastDot = base.lastIndexOf('.')
        if (lastDot < 0) return null
        return base.substring(0, lastDot) to base.substring(lastDot + 1)
    }

    data class ModuleHeader(val name: String)
    data class TraceLeaf(val file: File, val label: String, val durationMs: Double?)

    private class TraceTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: javax.swing.JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): Component {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
            val node = value as? DefaultMutableTreeNode ?: return this
            when (val uo = node.userObject) {
                is ModuleHeader -> {
                    text = uo.name
                    icon = null
                    font = font.deriveFont(Font.BOLD)
                    if (!selected) foreground = JBColor.foreground()
                }
                is TraceLeaf -> {
                    val dur = uo.durationMs?.let { "  %.0fms".format(it) } ?: ""
                    text = uo.label + dur
                    if (uo.file.name == "cartographer-run.json") {
                        if (!selected) foreground = JBColor.GRAY
                        font = font.deriveFont(Font.ITALIC)
                    }
                }
                is String -> {
                    text = uo
                    icon = null
                    if (!selected) foreground = JBColor.GRAY
                    font = font.deriveFont(Font.ITALIC)
                }
            }
            return this
        }
    }
}
