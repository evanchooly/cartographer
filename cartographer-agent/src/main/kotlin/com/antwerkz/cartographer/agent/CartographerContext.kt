package com.antwerkz.cartographer.agent

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.IdGenerator
import io.opentelemetry.sdk.trace.SdkTracerProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object CartographerContext {
    @Volatile var tracer: Tracer? = null
    @Volatile var tracerProvider: SdkTracerProvider? = null
    @Volatile var fileExporter: FileSpanExporter? = null
    @Volatile var captureArgs: Boolean = false
    @Volatile var maxArgLength: Int = 256

    // Keyed by trace ID (from the test-root span's SpanContext) rather than a ThreadLocal, so
    // naming survives spans that complete on a different thread than the one that started the
    // test — e.g. driver/executor threads whose work is a child of the test's trace.
    private val testNamesByTraceId = ConcurrentHashMap<String, String>()

    fun registerTestRoot(traceId: String, testName: String) {
        testNamesByTraceId[traceId] = testName
    }

    fun testNameForTrace(traceId: String): String? = testNamesByTraceId[traceId]

    fun clearTestRoot(traceId: String) {
        testNamesByTraceId.remove(traceId)
    }

    // Instrumented calls that happen before any test-root span exists (e.g. a test class's own
    // field initializers constructing production objects, or a JUnit extension's one-time setup)
    // have no active span to parent to. Rather than let OTel mint each one its own fresh,
    // never-to-be-named trace, they're parented to a per-thread "pending" trace that
    // TestRootAdvice adopts as the real trace ID once the test method actually starts —
    // deferring the name rather than losing the attribution.
    //
    // Bounding how large an unadopted lineage can grow is deliberately NOT done here via a
    // rotating counter: a rotation decided mid-recursion only affects the branch of the call
    // tree active at that moment. Sibling/ancestor frames still on the real call stack keep a
    // valid Span.current() from the now-abandoned generation, and there's no way to distinguish
    // that from a legitimately adopted trace without losing real parent/child nesting for
    // everything else. The size cap instead lives in FileSpanExporter, which caps the actual
    // accumulated span buffer per trace ID — ground truth regardless of call-tree shape.
    private val idGenerator = IdGenerator.random()
    private class PendingTrace(val context: Context)
    private val pending = ThreadLocal<PendingTrace?>()

    private fun newPendingTrace(): PendingTrace {
        val spanContext = SpanContext.create(
            idGenerator.generateTraceId(),
            idGenerator.generateSpanId(),
            TraceFlags.getSampled(),
            TraceState.getDefault()
        )
        val trace = PendingTrace(Span.wrap(spanContext).storeInContext(Context.root()))
        pending.set(trace)
        return trace
    }

    /** Parent context for a new span: the real current span if nested, else this thread's pending trace. */
    fun resolveParent(): Context {
        if (Span.current().spanContext.isValid) return Context.current()
        return (pending.get() ?: newPendingTrace()).context
    }

    /** Called by TestRootAdvice: adopt this thread's pending trace as the test's own, if any. */
    fun adoptPendingTrace(): Context? {
        val trace = pending.get() ?: return null
        pending.remove()
        return trace.context
    }

    // SimpleSpanProcessor.forceFlush() only waits on already in-flight async exports; for a
    // synchronous exporter like FileSpanExporter it never actually calls exporter.flush(). So
    // writing a just-finished trace to disk requires calling the exporter directly rather than
    // going through SdkTracerProvider.forceFlush().
    fun forceFlush() {
        fileExporter?.flush()?.join(5, TimeUnit.SECONDS)
        tracerProvider?.forceFlush()?.join(5, TimeUnit.SECONDS)
    }
}
