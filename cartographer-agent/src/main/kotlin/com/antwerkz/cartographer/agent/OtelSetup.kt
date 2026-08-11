package com.antwerkz.cartographer.agent

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor

object OtelSetup {
    fun initialize(config: AgentConfig, fileExporter: FileSpanExporter): SdkTracerProvider {
        // FileSpanExporter must use SimpleSpanProcessor: it relies on a ThreadLocal for the
        // current test name, which is only accessible on the test thread. BatchSpanProcessor
        // exports on a background thread where the ThreadLocal would be null.
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
