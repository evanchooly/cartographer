package com.antwerkz.cartographer.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class TraceFileWatcher(
    private val project: Project,
    private val projectRoot: File,
    private val onChange: (Map<String?, List<File>>) -> Unit
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cartographer-watcher").also { it.isDaemon = true }
    }

    @Volatile private var pollTask: ScheduledFuture<*>? = null
    @Volatile private var lastSnapshot: Map<String?, List<File>> = emptyMap()

    fun start() {
        pollTask?.cancel(false)
        // Poll immediately, then every 2 seconds
        pollTask = scheduler.scheduleWithFixedDelay(::poll, 0, 2, TimeUnit.SECONDS)
    }

    fun stop() {
        pollTask?.cancel(false)
        pollTask = null
    }

    private fun poll() {
        val modules = PomConfigReader.readModules(projectRoot)
        val snapshot = modules.associate { (name, dir) ->
            name to if (dir.isDirectory) {
                dir.listFiles { f -> f.name.endsWith(".json") }
                    ?.sortedBy { it.name }
                    ?: emptyList()
            } else {
                emptyList()
            }
        }
        if (snapshot != lastSnapshot) {
            lastSnapshot = snapshot
            ApplicationManager.getApplication().invokeLater { onChange(snapshot) }
        }
    }
}
