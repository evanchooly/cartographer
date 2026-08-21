package com.antwerkz.cartographer.agent

import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CartographerContextTest {

    @Test
    fun `a deeply nested unadopted call chain preserves real parent-child nesting`() {
        val mem = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(mem))
            .build()
        val tracer = provider.get("test")

        // Simulate a single top-level call, outside any test, that recurses into nested
        // instrumented calls (parent stays active the whole way down) — e.g. a mapping-cache
        // warm-up triggered from a JUnit extension. resolveParent() must keep using each frame's
        // real immediate parent (Context.current()), not flatten every descendant onto the
        // thread's pending-trace root — that would corrupt the call graph and, worse, silently
        // defeat any attempt to cap growth by making it impossible to tell a genuinely nested
        // nested call apart from a fresh top-level one.
        val frames = (0 until 50).map { i ->
            val parent = CartographerContext.resolveParent()
            val span = tracer.spanBuilder("call$i").setParent(parent).startSpan()
            span to span.makeCurrent()
        }
        frames.asReversed().forEach { (span, scope) ->
            span.end()
            scope.close()
        }

        val spans = mem.finishedSpanItems.sortedBy { it.name }
        val byName = spans.associateBy { it.name }
        val traceId = spans.first().traceId
        assertTrue(spans.all { it.traceId == traceId }, "Expected the whole chain to share one trace ID")

        // call0 -> call1 -> call2 -> ... each should be parented to the previous frame, not to
        // some shared synthetic root.
        for (i in 1 until frames.size) {
            val child = byName.getValue("call$i")
            val expectedParent = byName.getValue("call${i - 1}")
            assertEquals(
                expectedParent.spanId,
                child.parentSpanId,
                "Expected call$i to be parented to call${i - 1}, not flattened onto a shared root"
            )
        }
    }

    @Test
    fun `sequential top-level calls on the same thread share one pending trace until adopted`() {
        val mem = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(mem))
            .build()
        val tracer = provider.get("test")

        repeat(5) { i ->
            val span = tracer.spanBuilder("top$i").setParent(CartographerContext.resolveParent()).startSpan()
            span.end()
        }

        val traceIds = mem.finishedSpanItems.map { it.traceId }.toSet()
        assertEquals(1, traceIds.size, "Expected sequential top-level orphan calls to share one trace ID")
    }
}
