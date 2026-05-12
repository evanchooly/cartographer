package com.antwerkz.surveyor.agent

import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class FileSpanExporter(
    private val outputDir: File,
    private val testNameSupplier: () -> String
) : SpanExporter {

    private val lock = ReentrantLock()
    private val pending = mutableListOf<SpanData>()

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        lock.withLock { pending.addAll(spans) }
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode {
        val toWrite = lock.withLock { pending.toList().also { pending.clear() } }
        if (toWrite.isEmpty()) return CompletableResultCode.ofSuccess()

        outputDir.mkdirs()
        val safeName = testNameSupplier().replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outFile = File(outputDir, "$safeName.json")

        outFile.outputStream().use { stream ->
            TraceRequestMarshaler.create(toWrite).writeJsonTo(stream)
        }

        return CompletableResultCode.ofSuccess()
    }

    override fun shutdown(): CompletableResultCode = flush()
}
