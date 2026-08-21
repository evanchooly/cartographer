package com.antwerkz.cartographer.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.DefaultTreeSelectionModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class TraceListPanel(private val onSelect: (File) -> Unit, private val onRescan: () -> Unit) :
    JPanel(BorderLayout()) {

    private val root = DefaultMutableTreeNode("root")
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val rescanButton = JButton(AllIcons.Actions.Refresh)
    private val scanIcon = JBLabel(AnimatedIcon.Default())
    private val filterField = JBTextField(20).apply { emptyText.text = "Filter tests" }
    private var allModules: Map<String?, List<File>> = emptyMap()

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.selectionModel =
            object : DefaultTreeSelectionModel() {
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
            val node =
                e.path?.lastPathComponent as? DefaultMutableTreeNode
                    ?: return@addTreeSelectionListener
            val leaf = node.userObject as? TraceLeaf ?: return@addTreeSelectionListener
            onSelect(leaf.file)
        }
        tree.addTreeWillExpandListener(
            object : TreeWillExpandListener {
                override fun treeWillExpand(event: TreeExpansionEvent) {
                    val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val header = node.userObject as? ClassHeader ?: return
                    if (!isPlaceholderOnly(node)) return
                    node.removeAllChildren()
                    methodLeaves(header.files).forEach { leaf ->
                        node.add(DefaultMutableTreeNode(leaf))
                    }
                    model.nodeStructureChanged(node)
                }

                override fun treeWillCollapse(event: TreeExpansionEvent) {
                    val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    node.userObject as? ClassHeader ?: return
                    node.removeAllChildren()
                    node.add(DefaultMutableTreeNode(LoadingPlaceholder))
                    model.nodeStructureChanged(node)
                }
            }
        )

        rescanButton.addActionListener { onRescan() }
        scanIcon.isVisible = false
        filterField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = applyFilter()

                override fun removeUpdate(e: DocumentEvent) = applyFilter()

                override fun changedUpdate(e: DocumentEvent) = applyFilter()
            }
        )

        val toolbar =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(rescanButton)
                add(scanIcon)
                add(filterField)
            }
        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    fun setScanning(active: Boolean) {
        rescanButton.isEnabled = !active
        scanIcon.isVisible = active
    }

    private fun isPlaceholderOnly(node: DefaultMutableTreeNode): Boolean {
        if (node.childCount != 1) return false
        val onlyChild = node.getChildAt(0) as? DefaultMutableTreeNode ?: return false
        return onlyChild.userObject === LoadingPlaceholder
    }

    fun refresh(modules: Map<String?, List<File>>) {
        allModules = modules
        applyFilter()
    }

    private fun applyFilter() {
        val query = filterField.text.trim()
        val modules = if (query.isBlank()) allModules else filterModules(allModules, query)

        root.removeAllChildren()

        val singleModule = modules.size == 1 && modules.containsKey(null)

        if (singleModule) {
            populateClassNodes(root, modules[null] ?: emptyList())
        } else {
            modules.entries
                .sortedBy { it.key ?: "" }
                .forEach { (moduleName, files) ->
                    val moduleNode = DefaultMutableTreeNode(ModuleHeader(moduleName ?: ""))
                    populateClassNodes(moduleNode, files)
                    if (moduleNode.childCount > 0) root.add(moduleNode)
                }
        }

        if (root.childCount == 0) {
            val message = if (query.isBlank()) "No traces yet — run your tests" else "No matches"
            root.add(DefaultMutableTreeNode(message))
        }

        model.reload()

        if (!singleModule || query.isNotBlank()) {
            (0 until root.childCount)
                .map { root.getChildAt(it) as DefaultMutableTreeNode }
                .forEach { tree.expandPath(TreePath(it.path)) }
        }
    }

    private fun filterModules(
        modules: Map<String?, List<File>>,
        query: String
    ): Map<String?, List<File>> {
        val needle = query.lowercase()
        return modules
            .mapValues { (_, files) ->
                files.filter { file ->
                    if (file.name == "cartographer-run.json") return@filter true
                    val parsed = parseFileName(file) ?: return@filter false
                    val (fqcn, method) = parsed
                    fqcn.lowercase().contains(needle) || method.lowercase().contains(needle)
                }
            }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * Groups files by fully-qualified class name only — no test method names are resolved (and no
     * trace content is read) until a class node is expanded, keeping the initial view cheap for
     * large trace directories.
     */
    private fun populateClassNodes(parent: DefaultMutableTreeNode, files: List<File>) {
        val filesByClass =
            files
                .filter { it.name != "cartographer-run.json" }
                .mapNotNull { file -> classFqcn(file)?.let { fqcn -> fqcn to file } }
                .groupBy({ it.first }, { it.second })
                .toSortedMap()

        filesByClass.forEach { (fqcn, classFiles) ->
            val classNode = DefaultMutableTreeNode(ClassHeader(fqcn, classFiles))
            classNode.add(DefaultMutableTreeNode(LoadingPlaceholder))
            parent.add(classNode)
        }

        val runFile = files.firstOrNull { it.name == "cartographer-run.json" }
        if (runFile != null) {
            parent.add(DefaultMutableTreeNode(TraceLeaf(runFile, "cartographer-run")))
        }
    }

    private fun methodLeaves(files: List<File>): List<TraceLeaf> =
        files
            .mapNotNull { file ->
                parseFileName(file)?.let { (_, method) -> TraceLeaf(file, method) }
            }
            .sortedBy { it.label }

    private fun classFqcn(file: File): String? = parseFileName(file)?.first

    private fun parseFileName(file: File): Pair<String, String>? {
        val base = file.nameWithoutExtension
        val lastDot = base.lastIndexOf('.')
        if (lastDot < 0) return null
        return base.substring(0, lastDot) to base.substring(lastDot + 1)
    }

    data class ModuleHeader(val name: String)

    data class ClassHeader(val fqcn: String, val files: List<File>)

    data class TraceLeaf(val file: File, val label: String)

    private object LoadingPlaceholder

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
                is ClassHeader -> {
                    text = uo.fqcn.substringAfterLast('.')
                    icon = null
                    if (!selected) foreground = JBColor.GRAY
                    font = font.deriveFont(Font.ITALIC)
                }
                is TraceLeaf -> {
                    text = uo.label
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
