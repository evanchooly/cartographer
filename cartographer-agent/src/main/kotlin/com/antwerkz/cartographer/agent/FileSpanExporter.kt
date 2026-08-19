package com.antwerkz.cartographer.agent

import com.fasterxml.jackson.core.JsonFactory
import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.io.ByteArrayOutputStream
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
        if (spans.any { !it.parentSpanContext.isValid }) {
            flush()
        }
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode {
        val toWrite = lock.withLock { pending.toList().also { pending.clear() } }
        if (toWrite.isEmpty()) return CompletableResultCode.ofSuccess()

        outputDir.mkdirs()
        val safeName = testNameSupplier().replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outFile = File(outputDir, "$safeName.json")

        val jsonBytes = ByteArrayOutputStream().also {
            TraceRequestMarshaler.create(toWrite).writeJsonTo(it)
        }.toByteArray()

        val jsonFactory = JsonFactory()
        outFile.bufferedWriter().use { writer ->
            val generator = jsonFactory.createGenerator(writer).setPrettyPrinter(
                com.fasterxml.jackson.core.util.DefaultPrettyPrinter()
            )
            jsonFactory.createParser(jsonBytes).use { parser ->
                parser.nextToken()
                generator.copyCurrentStructure(parser)
            }
            generator.close()
        }

        return CompletableResultCode.ofSuccess()
    }

    override fun shutdown(): CompletableResultCode = flush()
}
