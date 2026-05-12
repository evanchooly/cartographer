package com.antwerkz.surveyor.agent

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Scope
import net.bytebuddy.asm.Advice

object TestRootAdvice {

    @JvmStatic
    @Advice.OnMethodEnter(suppress = Throwable::class)
    fun onEnter(@Advice.Origin("#t.#m") signature: String): Scope? {
        val tracer = SurveyorContext.tracer ?: return null
        SurveyorContext.currentTestName = signature
        val span = tracer.spanBuilder(signature).setNoParent().startSpan()
        return span.makeCurrent()
    }

    @JvmStatic
    @Advice.OnMethodExit(suppress = Throwable::class, onThrowable = Throwable::class)
    fun onExit(
        @Advice.Enter scope: Scope?,
        @Advice.Thrown throwable: Throwable?
    ) {
        val span = Span.current()
        if (throwable != null) {
            span.recordException(throwable)
            span.setStatus(StatusCode.ERROR)
        }
        span.end()
        scope?.close()
        SurveyorContext.forceFlush()
    }
}
