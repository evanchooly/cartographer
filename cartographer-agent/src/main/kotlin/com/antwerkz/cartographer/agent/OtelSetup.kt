package com.antwerkz.cartographer.agent

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor

object OtelSetup {
    fun initialize(config: AgentConfig, fileExporter: FileSpanExporter): SdkTracerProvider {
        // FileSpanExporter must use SimpleSpanProcessor: TestRootAdvice clears the trace's
        // registered test name right after forceFlush() returns, so the export of a trace's
        // spans must happen synchronously with span completion. BatchSpanProcessor would export
        // on a delayed background thread, racing that cleanup.
        val builder = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(fileExporter))

        if (config.endpoint != null) {
            val otlpExporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(config.endpoint)
                .build()
            builder.addSpanProcessor(BatchSpanProcessor.builder(otlpExporter).build())
        }

        return builder.build()
    }
}
