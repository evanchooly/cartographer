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
        val parenIndex = name.indexOf('(')
        val methodPart = if (parenIndex < 0) name else name.substring(0, parenIndex)
        val paramsPart =
            if (parenIndex < 0) null else name.substring(parenIndex + 1, name.length - 1)

        val lastDot = methodPart.lastIndexOf('.')
        val simpleMethodPart =
            if (lastDot < 0) methodPart
            else {
                val classLastDot = methodPart.lastIndexOf('.', lastDot - 1)
                if (classLastDot < 0) methodPart else methodPart.substring(classLastDot + 1)
            }

        if (paramsPart == null) simpleMethodPart
        else {
            val simpleParams =
                if (paramsPart.isEmpty()) ""
                else paramsPart.split(',').joinToString(", ") { simpleTypeName(it) }
            "$simpleMethodPart($simpleParams)"
        }
    }
}

/**
 * Renders a fully-qualified parameter type (as produced by ByteBuddy's `#s` origin placeholder,
 * which uses JVM array notation like `[Ljava.lang.String;` for arrays) as a short, readable
 * Java-source-style name, e.g. `String[]`.
 */
private fun simpleTypeName(rawType: String): String {
    var type = rawType
    var arrayDepth = 0
    while (type.startsWith("[")) {
        arrayDepth++
        type = type.substring(1)
    }
    val elementName =
        when {
            arrayDepth == 0 -> type
            type.startsWith("L") && type.endsWith(";") -> type.substring(1, type.length - 1)
            else ->
                when (type) {
                    "I" -> "int"
                    "J" -> "long"
                    "Z" -> "boolean"
                    "B" -> "byte"
                    "C" -> "char"
                    "S" -> "short"
                    "F" -> "float"
                    "D" -> "double"
                    else -> type
                }
        }
    return elementName.substringAfterLast('.') + "[]".repeat(arrayDepth)
}
