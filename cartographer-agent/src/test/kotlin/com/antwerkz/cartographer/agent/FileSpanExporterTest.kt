package com.antwerkz.cartographer.agent

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileSpanExporterTest {

    @TempDir
    lateinit var tempDir: File

    private fun rootSpan(name: String, testName: String? = null): List<SpanData> {
        val mem = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(mem))
            .build()
        val span = provider.get("test").spanBuilder(name).startSpan()
        if (testName != null) {
            CartographerContext.registerTestRoot(span.spanContext.traceId, testName)
        }
        span.end()
        return mem.finishedSpanItems
    }

    private fun childSpan(name: String, testName: String? = null): List<SpanData> {
        val mem = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(mem))
            .build()
        val tracer = provider.get("test")
        val parent = tracer.spanBuilder("parent").startSpan()
        if (testName != null) {
            CartographerContext.registerTestRoot(parent.spanContext.traceId, testName)
        }
        val scope = parent.makeCurrent()
        tracer.spanBuilder(name).startSpan().end()
        scope.close()
        parent.end()
        return mem.finishedSpanItems.filter { it.name == name }
    }

    @Test
    fun `explicit flush writes json file named after registered test`() {
        val exporter = FileSpanExporter(tempDir)
        exporter.export(childSpan("span", "com.example.FooTest.myTest"))
        exporter.flush()
        val files = tempDir.listFiles()!!
        assertEquals(1, files.size)
        assertTrue(files[0].name.startsWith("com.example.FooTest.myTest"))
        assertTrue(files[0].length() > 0)
    }

    @Test
    fun `output file contains valid json`() {
        val exporter = FileSpanExporter(tempDir)
        exporter.export(childSpan("s", "MyTest.myMethod"))
        exporter.flush()
        val content = tempDir.listFiles()!!.first().readText().trim()
        assertTrue(content.startsWith("{"), "Expected JSON object, got: ${content.take(30)}")
    }

    @Test
    fun `flush creates output directory if absent`() {
        val subDir = File(tempDir, "nested/path")
        val exporter = FileSpanExporter(subDir)
        exporter.export(childSpan("s", "test"))
        exporter.flush()
        assertTrue(subDir.exists())
    }

    @Test
    fun `flush with no pending spans writes nothing`() {
        val exporter = FileSpanExporter(tempDir)
        exporter.flush()
        assertEquals(0, tempDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `second flush after buffer cleared writes nothing new`() {
        val exporter = FileSpanExporter(tempDir)
        exporter.export(childSpan("s", "test"))
        exporter.flush()
        exporter.flush()
        assertEquals(1, tempDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `root span is not written until explicitly flushed`() {
        val exporter = FileSpanExporter(tempDir)
        exporter.export(rootSpan("root", "AutoFlush.test"))
        assertEquals(0, tempDir.listFiles()?.size ?: 0)
        exporter.flush()
        assertEquals(1, tempDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `unregistered trace falls back to a name scoped to its own trace id`() {
        val exporter = FileSpanExporter(tempDir)
        exporter.export(rootSpan("root"))
        exporter.flush()
        val files = tempDir.listFiles()!!
        assertEquals(1, files.size)
        assertTrue(files[0].name.startsWith("cartographer-run-"))
    }

    @Test
    fun `an unadopted trace that grows past the size cap is flushed automatically`() {
        val mem = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(mem))
            .build()
        val tracer = provider.get("test")
        val parent = tracer.spanBuilder("root").startSpan()
        val scope = parent.makeCurrent()
        // No CartographerContext.registerTestRoot() call — this trace is never adopted, same as
        // a shared cache warmed up once from a JUnit extension on a thread that never runs a
        // @Test method itself.
        repeat(5_001) { tracer.spanBuilder("call$it").startSpan().end() }
        scope.close()
        parent.end()

        val exporter = FileSpanExporter(tempDir)
        exporter.export(mem.finishedSpanItems)
        // No explicit flush() call: the cap must trigger the write on its own.
        val files = tempDir.listFiles()!!
        assertEquals(1, files.size)
        assertTrue(files[0].name.startsWith("cartographer-run-"))
    }

    @Test
    fun `an adopted trace is never auto-split even past the unadopted-trace size cap`() {
        val mem = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(mem))
            .build()
        val tracer = provider.get("test")
        val parent = tracer.spanBuilder("root").startSpan()
        CartographerContext.registerTestRoot(parent.spanContext.traceId, "BigTest.method")
        val scope = parent.makeCurrent()
        repeat(5_001) { tracer.spanBuilder("call$it").startSpan().end() }
        scope.close()
        parent.end()

        val exporter = FileSpanExporter(tempDir)
        exporter.export(mem.finishedSpanItems)
        assertEquals(
            0,
            tempDir.listFiles()?.size ?: 0,
            "A legitimately large, already-named test trace must not be split by the unadopted-trace cap"
        )
        exporter.flush()
        val files = tempDir.listFiles()!!
        assertEquals(1, files.size)
        assertTrue(files[0].name.startsWith("BigTest.method"))
    }

    @Test
    fun `spans from two concurrent traces land in separate files`() {
        val exporter = FileSpanExporter(tempDir)
        exporter.export(childSpan("a", "TestA.method"))
        exporter.export(childSpan("b", "TestB.method"))
        exporter.flush()
        val files = tempDir.listFiles()!!
        assertEquals(2, files.size)
        assertTrue(files.any { it.name.startsWith("TestA.method") })
        assertTrue(files.any { it.name.startsWith("TestB.method") })
    }
}
