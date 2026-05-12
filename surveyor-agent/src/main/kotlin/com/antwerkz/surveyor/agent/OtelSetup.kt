package com.antwerkz.surveyor.agent

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter

object OtelSetup {
    fun initialize(config: AgentConfig, fileExporter: FileSpanExporter): SdkTracerProvider {
        val exporters = mutableListOf<SpanExporter>(fileExporter)

        if (config.endpoint != null) {
            exporters += OtlpHttpSpanExporter.builder()
                .setEndpoint(config.endpoint)
                .build()
        }

        val processor = BatchSpanProcessor.builder(SpanExporter.composite(exporters)).build()
        return SdkTracerProvider.builder().addSpanProcessor(processor).build()
    }
}
