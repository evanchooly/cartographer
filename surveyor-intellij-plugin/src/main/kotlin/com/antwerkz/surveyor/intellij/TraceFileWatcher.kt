package com.antwerkz.surveyor.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.MessageBusConnection
import java.io.File

class TraceFileWatcher(
    private val project: Project,
    private val outputDir: File,
    private val onChange: (List<File>) -> Unit
) {
    private var connection: MessageBusConnection? = null

    fun start() {
        connection?.disconnect()
        notifyFiles()

        connection = project.messageBus.connect().also { conn ->
            conn.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val relevant = events.any { e ->
                        e.file?.path?.startsWith(outputDir.canonicalPath) == true
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
        val files = if (outputDir.isDirectory) {
            outputDir.listFiles { f -> f.name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?: emptyList()
        } else {
            emptyList()
        }
        onChange(files)
    }
}
