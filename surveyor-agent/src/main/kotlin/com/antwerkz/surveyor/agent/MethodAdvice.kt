package com.antwerkz.surveyor.agent

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Scope
import net.bytebuddy.asm.Advice

object MethodAdvice {

    @JvmStatic
    @Advice.OnMethodEnter(suppress = Throwable::class)
    fun onEnter(
        @Advice.Origin("#t##m") signature: String,
        @Advice.AllArguments args: Array<Any?>
    ): Scope? {
        val tracer = SurveyorContext.tracer ?: return null
        val span = tracer.spanBuilder(signature).startSpan()

        if (SurveyorContext.captureArgs && args.isNotEmpty()) {
            val maxLen = SurveyorContext.maxArgLength
            args.forEachIndexed { i, arg ->
                val value = when (arg) {
                    null -> "null"
                    else -> try {
                        val s = arg.toString()
                        if (s.length > maxLen) s.take(maxLen) + "…" else s
                    } catch (_: Throwable) {
                        "<error: ${arg.javaClass.name}>"
                    }
                }
                span.setAttribute("arg.$i", value)
            }
        }

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
    }
}
