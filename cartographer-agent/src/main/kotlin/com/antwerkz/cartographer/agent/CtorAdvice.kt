package com.antwerkz.cartographer.agent

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Scope
import net.bytebuddy.asm.Advice

object CtorAdvice {

    @JvmStatic
    @Advice.OnMethodEnter(suppress = Throwable::class)
    fun onEnter(
        @Advice.Origin("#t.#m") signature: String
    ): Scope? {
        val tracer = CartographerContext.tracer ?: return null
        return tracer.spanBuilder(signature).startSpan().makeCurrent()
    }

    @JvmStatic
    @Advice.OnMethodExit(suppress = Throwable::class)
    fun onExit(@Advice.Enter scope: Scope?) {
        Span.current().end()
        scope?.close()
    }
}
