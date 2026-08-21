package com.antwerkz.cartographer.agent

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import net.bytebuddy.agent.ByteBuddyAgent
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class CartographerTransformerTest {

    companion object {
        val spanExporter: InMemorySpanExporter = InMemorySpanExporter.create()

        @JvmStatic
        @BeforeAll
        fun setup() {
            val provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build()
            CartographerContext.tracer = provider.get("cartographer-test")
            CartographerContext.tracerProvider = provider
            CartographerContext.captureArgs = false

            val instrumentation = ByteBuddyAgent.install()
            val config = AgentConfig(
                packages = listOf("com.antwerkz.cartographer.agent.fixture"),
                outputDir = Files.createTempDirectory("cartographer-test").toFile(),
                endpoint = null,
                captureArgs = false,
                maxArgLength = 256
            )
            CartographerTransformer.install(config, instrumentation)
        }
    }

    @BeforeEach
    fun reset() {
        spanExporter.reset()
    }

    @Test
    fun `instrumented method produces a span`() {
        com.antwerkz.cartographer.agent.fixture.SimpleFixture().add(2, 3)
        val spans = spanExporter.finishedSpanItems
        assertTrue(spans.any { it.name.contains("add") }, "Expected span for add(), got: ${spans.map { it.name }}")
    }

    @Test
    fun `constructor produces a span`() {
        com.antwerkz.cartographer.agent.fixture.SimpleFixture()
        val spans = spanExporter.finishedSpanItems
        assertTrue(spans.any { it.name.contains("<init>") }, "Expected span for constructor, got: ${spans.map { it.name }}")
    }

    @Test
    fun `constructor of a class declaring a Test method is not instrumented`() {
        com.antwerkz.cartographer.agent.fixture.TestClassFixture()
        val spans = spanExporter.finishedSpanItems
        assertTrue(
            spans.none { it.name.contains("TestClassFixture") },
            "Expected no span for TestClassFixture construction, got: ${spans.map { it.name }}"
        )
    }

    @Test
    fun `pre-test-root spans are backfilled into the test's trace once it starts`() {
        val fixture = com.antwerkz.cartographer.agent.fixture.DeferredNamingFixture()
        fixture.exampleTest()
        val spans = spanExporter.finishedSpanItems
        val helperSpan = spans.first { it.name.contains("HelperFixture") }
        val testSpan = spans.first { it.name.contains("exampleTest") }
        assertTrue(
            helperSpan.traceId == testSpan.traceId,
            "Expected constructor span from before the test method started to share the test's " +
                "trace ID, got helper=${helperSpan.traceId} test=${testSpan.traceId}"
        )
    }

    @Test
    fun `exception in instrumented method is recorded on span`() {
        assertThrows(ArithmeticException::class.java) {
            com.antwerkz.cartographer.agent.fixture.SimpleFixture().divide(1, 0)
        }
        val spans = spanExporter.finishedSpanItems
        val divideSpan = spans.first { it.name.contains("divide") }
        assertTrue(divideSpan.events.any { it.name == "exception" }, "Expected exception event on divide span")
    }
}
