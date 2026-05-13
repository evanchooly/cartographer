package com.antwerkz.surveyor.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.MessageBusConnection
import java.io.File
import java.io.IOException

class TraceFileWatcher(
    private val project: Project,
    private val outputDir: File,
    private val onChange: (List<File>) -> Unit
) {
    // Resolved once to avoid repeated syscalls and IOException inside EDT callbacks
    private val outputCanonical: String = try {
        outputDir.canonicalPath
    } catch (_: IOException) {
        outputDir.absolutePath
    }

    @Volatile
    private var connection: MessageBusConnection? = null

    fun start() {
        connection?.disconnect()
        notifyFiles()

        // connect(project) ties the connection to the project lifecycle for auto-disposal
        connection = project.messageBus.connect(project).also { conn ->
            conn.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val relevant = events.any { e ->
                        e.file?.path?.startsWith(outputCanonical) == true
                    }
                    if (relevant) notifyFiles()
                }
            })
        }
    }

    fun stop() {
        connection?.disconnect()
        connection = null
    }

    private fun notifyFiles() {
        // listFiles can return null if dir disappears between isDirectory check and the call
        val files = if (outputDir.isDirectory) {
            outputDir.listFiles { f -> f.name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?: emptyList()
        } else {
            emptyList()
        }
        ApplicationManager.getApplication().invokeLater { onChange(files) }
    }
}
