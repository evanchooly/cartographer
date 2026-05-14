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
    private val modules: List<Pair<String?, File>>,
    private val onChange: (Map<String?, List<File>>) -> Unit
) {
    private val canonicalPaths: List<Pair<String?, String>> = modules.map { (name, dir) ->
        name to try { dir.canonicalPath } catch (_: IOException) { dir.absolutePath }
    }

    @Volatile
    private var connection: MessageBusConnection? = null

    fun start() {
        connection?.disconnect()
        notifyFiles()
        connection = project.messageBus.connect(project).also { conn ->
            conn.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val relevant = events.any { e ->
                        val path = e.file?.path ?: return@any false
                        canonicalPaths.any { (_, canonical) -> path.startsWith(canonical) }
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
        val result = modules.associate { (name, dir) ->
            name to if (dir.isDirectory) {
                dir.listFiles { f -> f.name.endsWith(".json") }
                    ?.sortedBy { it.name }
                    ?: emptyList()
            } else {
                emptyList()
            }
        }
        ApplicationManager.getApplication().invokeLater { onChange(result) }
    }
}
