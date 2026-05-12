package com.antwerkz.surveyor.agent

import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.trace.SdkTracerProvider
import java.util.concurrent.TimeUnit

object SurveyorContext {
    @Volatile var tracer: Tracer? = null
    @Volatile var tracerProvider: SdkTracerProvider? = null
    @Volatile var captureArgs: Boolean = false
    @Volatile var maxArgLength: Int = 256

    private val testName = ThreadLocal<String?>()

    var currentTestName: String?
        get() = testName.get()
        set(value) { testName.set(value) }

    fun forceFlush() {
        tracerProvider?.forceFlush()?.join(5, TimeUnit.SECONDS)
    }
}
