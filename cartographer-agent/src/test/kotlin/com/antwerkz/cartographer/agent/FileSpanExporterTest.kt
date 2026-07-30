package com.antwerkz.cartographer.agent

import io.opentelemetry.context.Context
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

    private fun rootSpan(name: String): List<SpanData> {
        val mem = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(mem))
            .build()
        provider.get("test").spanBuilder(name).startSpan().end()
        return mem.finishedSpanItems
    }

    private fun childSpan(name: String): List<SpanData> {
        val mem = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(mem))
            .build()
        val tracer = provider.get("test")
        val parent = tracer.spanBuilder("parent").startSpan()
        val scope = parent.makeCurrent()
        tracer.spanBuilder(name).startSpan().end()
        scope.close()
        parent.end()
        return mem.finishedSpanItems.filter { it.name == name }
    }

    @Test
    fun `explicit flush writes json file named after test`() {
        val exporter = FileSpanExporter(tempDir) { "com.example.FooTest.myTest" }
        exporter.export(childSpan("span"))
        exporter.flush()
        val files = tempDir.listFiles()!!
        assertEquals(1, files.size)
        assertTrue(files[0].name.startsWith("com.example.FooTest.myTest"))
        assertTrue(files[0].length() > 0)
    }

    @Test
    fun `output file contains valid json`() {
        val exporter = FileSpanExporter(tempDir) { "MyTest.myMethod" }
        exporter.export(childSpan("s"))
        exporter.flush()
        val content = tempDir.listFiles()!!.first().readText().trim()
        assertTrue(content.startsWith("{"), "Expected JSON object, got: ${content.take(30)}")
    }

    @Test
    fun `flush creates output directory if absent`() {
        val subDir = File(tempDir, "nested/path")
        val exporter = FileSpanExporter(subDir) { "test" }
        exporter.export(childSpan("s"))
        exporter.flush()
        assertTrue(subDir.exists())
    }

    @Test
    fun `flush with no pending spans writes nothing`() {
        val exporter = FileSpanExporter(tempDir) { "test" }
        exporter.flush()
        assertEquals(0, tempDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `second flush after buffer cleared writes nothing new`() {
        val exporter = FileSpanExporter(tempDir) { "test" }
        exporter.export(childSpan("s"))
        exporter.flush()
        exporter.flush()
        assertEquals(1, tempDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `root span triggers auto-flush`() {
        val exporter = FileSpanExporter(tempDir) { "AutoFlush.test" }
        exporter.export(rootSpan("root"))
        assertEquals(1, tempDir.listFiles()?.size ?: 0)
    }
}
