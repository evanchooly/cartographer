package com.antwerkz.cartographer.intellij.model

data class SpanNode(
    val spanId: String,
    val name: String,
    val startNano: Long,
    val endNano: Long,
    val attributes: Map<String, String>,
    val depth: Int = 0
) {
    val children: MutableList<SpanNode> = mutableListOf()
    val durationMs: Double = (endNano - startNano) / 1_000_000.0
    val simpleName: String = run {
        val lastDot = name.lastIndexOf('.')
        if (lastDot < 0) name
        else {
            val classLastDot = name.lastIndexOf('.', lastDot - 1)
            if (classLastDot < 0) name else name.substring(classLastDot + 1)
        }
    }
}
