package com.antwerkz.surveyor.intellij.model

data class SpanNode(
    val spanId: String,
    val name: String,
    val startNano: Long,
    val endNano: Long,
    val attributes: Map<String, String>,
    val children: MutableList<SpanNode> = mutableListOf(),
    val depth: Int = 0
) {
    val durationMs: Double get() = (endNano - startNano) / 1_000_000.0

    /** Simple `ClassName.methodName` extracted from the fully qualified span name. */
    val simpleName: String get() {
        val lastDot = name.lastIndexOf('.')
        return if (lastDot < 0) name else {
            val classLastDot = name.lastIndexOf('.', lastDot - 1)
            if (classLastDot < 0) name else name.substring(classLastDot + 1)
        }
    }
}
