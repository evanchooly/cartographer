package com.antwerkz.surveyor.intellij

import com.antwerkz.surveyor.intellij.ui.SpanDetailPanel
import com.antwerkz.surveyor.intellij.ui.TraceListPanel
import com.antwerkz.surveyor.intellij.ui.WaterfallPanel
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

class SurveyorToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val detailPanel = SpanDetailPanel { span ->
            SourceNavigator.navigate(project, span.name)
        }

        val waterfallPanel = WaterfallPanel { span ->
            detailPanel.show(span)
        }

        val listPanel = TraceListPanel { file ->
            detailPanel.clear()
            val roots = OtlpJsonParser.parse(file)
            waterfallPanel.load(roots)
        }

        val projectRoot = ProjectRootManager.getInstance(project)
            .contentRoots.firstOrNull()?.let { File(it.path) }
            ?: File(".")
        val outputDir = PomConfigReader.readOutputDir(projectRoot)

        val watcher = TraceFileWatcher(project, outputDir) { files ->
            listPanel.refresh(files)
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
        toolWindow.contentManager.addContent(content)

        // Start watcher when panel is shown, stop when hidden
        splitter.addAncestorListener(object : AncestorListener {
            override fun ancestorAdded(event: AncestorEvent) = watcher.start()
            override fun ancestorRemoved(event: AncestorEvent) = watcher.stop()
            override fun ancestorMoved(event: AncestorEvent) = Unit
        })
    }
}
