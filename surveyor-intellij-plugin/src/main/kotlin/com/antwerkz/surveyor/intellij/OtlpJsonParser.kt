package com.antwerkz.surveyor.intellij

import com.antwerkz.surveyor.intellij.model.SpanNode
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

object OtlpJsonParser {

    fun parse(file: File): List<SpanNode> {
        val raw = mutableListOf<RawSpan>()
        try {
            val root = JsonParser.parseReader(file.reader()).asJsonObject
            root.getAsJsonArray("resourceSpans")?.forEach { rs ->
                rs.asJsonObject.getAsJsonArray("scopeSpans")?.forEach { ss ->
                    ss.asJsonObject.getAsJsonArray("spans")?.forEach { s ->
                        raw += parseRaw(s.asJsonObject)
                    }
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return buildTree(raw)
    }

    private fun parseRaw(obj: JsonObject): RawSpan {
        val attrs = mutableMapOf<String, String>()
        obj.getAsJsonArray("attributes")?.forEach { a ->
            val ao = a.asJsonObject
            val key = ao.get("key")?.asString ?: return@forEach
            val value = ao.getAsJsonObject("value")?.get("stringValue")?.asString ?: ""
            attrs[key] = value
        }
        return RawSpan(
            spanId = obj.get("spanId").asString,
            parentSpanId = obj.get("parentSpanId")?.asString ?: "",
            name = obj.get("name").asString,
            startNano = obj.get("startTimeUnixNano").asString.toLong(),
            endNano = obj.get("endTimeUnixNano").asString.toLong(),
            attributes = attrs
        )
    }

    private fun buildTree(rawSpans: List<RawSpan>): List<SpanNode> {
        val childrenByParent = rawSpans
            .filter { it.parentSpanId.isNotEmpty() }
            .groupBy { it.parentSpanId }
        val roots = rawSpans.filter { it.parentSpanId.isEmpty() }
        return roots.map { build(it, 0, childrenByParent) }
    }

    private fun build(
        raw: RawSpan,
        depth: Int,
        childrenByParent: Map<String, List<RawSpan>>
    ): SpanNode {
        val children = childrenByParent[raw.spanId]
            ?.sortedBy { it.startNano }
            ?.map { build(it, depth + 1, childrenByParent) }
            ?: emptyList()
        return SpanNode(
            spanId = raw.spanId,
            name = raw.name,
            startNano = raw.startNano,
            endNano = raw.endNano,
            attributes = raw.attributes,
            depth = depth
        ).also { node -> node.children.addAll(children) }
    }

    private data class RawSpan(
        val spanId: String,
        val parentSpanId: String,
        val name: String,
        val startNano: Long,
        val endNano: Long,
        val attributes: Map<String, String>
    )
}
