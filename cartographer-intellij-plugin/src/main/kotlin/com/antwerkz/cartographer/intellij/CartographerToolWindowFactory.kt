package com.antwerkz.cartographer.intellij

import com.antwerkz.cartographer.intellij.ui.SpanDetailPanel
import com.antwerkz.cartographer.intellij.ui.TraceListPanel
import com.antwerkz.cartographer.intellij.ui.WaterfallPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.io.File
import javax.swing.JPanel
import javax.swing.event.AncestorEvent
import javax.swing.event.AncestorListener

class CartographerToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val detailPanel = SpanDetailPanel { span ->
            SourceNavigator.navigate(project, span.name)
        }

        val waterfallPanel = WaterfallPanel { span ->
            detailPanel.show(span)
        }

        val listPanel = TraceListPanel { file ->
            detailPanel.clear()
            ApplicationManager.getApplication().executeOnPooledThread {
                val roots = OtlpJsonParser.parse(file)
                ApplicationManager.getApplication().invokeLater {
                    waterfallPanel.load(roots)
                }
            }
        }

        val projectRoot = ProjectRootManager.getInstance(project)
            .contentRoots.firstOrNull()?.let { File(it.path) }
            ?: project.basePath?.let { File(it) }
            ?: File(".")

        val modules = PomConfigReader.readModules(projectRoot)

        val watcher = TraceFileWatcher(project, modules) { moduleFiles ->
            listPanel.refresh(moduleFiles)
        }

        val rightPanel = JPanel(BorderLayout()).apply {
            add(waterfallPanel, BorderLayout.CENTER)
            add(detailPanel, BorderLayout.SOUTH)
        }

        val splitter = JBSplitter(false, 0.25f).apply {
            firstComponent = listPanel
            secondComponent = rightPanel
        }

        val content = ContentFactory.getInstance()
            .createContent(splitter, "", false)

        splitter.addAncestorListener(object : AncestorListener {
            override fun ancestorAdded(event: AncestorEvent) = watcher.start()
            override fun ancestorRemoved(event: AncestorEvent) = watcher.stop()
            override fun ancestorMoved(event: AncestorEvent) = Unit
        })

        toolWindow.contentManager.addContent(content)
        // Start immediately in case the tool window is already visible when content is added
        watcher.start()
    }
}
