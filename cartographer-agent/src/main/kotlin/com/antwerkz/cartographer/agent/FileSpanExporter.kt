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
    private val outputDir: File
) : SpanExporter {

    private val lock = ReentrantLock()

    // Spans are bucketed by trace ID rather than collected into one shared buffer, so that
    // concurrently running tests (or background threads whose spans join a test's trace) never
    // have their spans interleaved into someone else's output file.
    private val pendingByTrace = mutableMapOf<String, MutableList<SpanData>>()

    companion object {
        // Caps how large a trace's buffer can grow while it's still unadopted (no test name
        // registered for it) before it's dropped outright. This is the backstop against
        // unbounded growth — checked against the real accumulated buffer, not a proxy counter —
        // because a never-adopted lineage (e.g. a shared cache warmed up once from a JUnit
        // extension, on a thread that never itself runs a @Test method) can otherwise recurse
        // into hundreds of thousands of spans with no other trigger to stop it. Traces that do
        // get adopted are never touched by this: registerTestRoot() runs before the test body
        // executes, so a legitimately huge single test's spans are never dropped.
        private const val MAX_UNADOPTED_TRACE_SPANS = 5_000
    }

    // Spans are otherwise only ever written out by an explicit flush (TestRootAdvice.onExit's
    // forceFlush() once a test finishes, or shutdown()'s final flush) — not by inspecting an
    // individual span's parent here. Pre-test-root spans (e.g. constructors run during a test
    // class's own field initialization) are parented to a per-thread pending trace
    // (CartographerContext.resolveParent) that TestRootAdvice later adopts as the real test
    // trace, so they end up flushed under the real test name once it's known. A trace that never
    // gets adopted isn't attributable to any single test, so it's simply dropped rather than
    // written out under a meaningless trace-ID filename.
    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        lock.withLock {
            spans.forEach { span ->
                val bucket = pendingByTrace.getOrPut(span.traceId) { mutableListOf() }
                bucket.add(span)
                if (bucket.size >= MAX_UNADOPTED_TRACE_SPANS &&
                    CartographerContext.testNameForTrace(span.traceId) == null
                ) {
                    pendingByTrace.remove(span.traceId)
                }
            }
        }
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode {
        val traceIds = lock.withLock { pendingByTrace.keys.toList() }
        traceIds.forEach { flushTrace(it) }
        return CompletableResultCode.ofSuccess()
    }

    private fun flushTrace(traceId: String): CompletableResultCode {
        val testName = CartographerContext.testNameForTrace(traceId)
        val toWrite = lock.withLock { pendingByTrace.remove(traceId) }
        if (toWrite.isNullOrEmpty() || testName == null) return CompletableResultCode.ofSuccess()

        outputDir.mkdirs()
        val safeName = testName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
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
