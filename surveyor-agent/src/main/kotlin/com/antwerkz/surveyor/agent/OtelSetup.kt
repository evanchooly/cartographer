package com.antwerkz.surveyor.agent

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter

object OtelSetup {
    fun initialize(config: AgentConfig, fileExporter: FileSpanExporter): SdkTracerProvider {
        val exporters = mutableListOf<SpanExporter>(fileExporter)

        if (config.endpoint != null) {
            exporters += OtlpHttpSpanExporter.builder()
                .setEndpoint(config.endpoint)
                .build()
        }

        val processor = SimpleSpanProcessor.create(SpanExporter.composite(exporters))
        return SdkTracerProvider.builder().addSpanProcessor(processor).build()
    }
}
