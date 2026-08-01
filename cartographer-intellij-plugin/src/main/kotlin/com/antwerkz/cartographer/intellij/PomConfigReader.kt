package com.antwerkz.cartographer.intellij

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

object PomConfigReader {

    fun readOutputDir(projectRoot: File): File {
        val pom = File(projectRoot, "pom.xml")
        if (!pom.exists()) return fallback(projectRoot)
        return try {
            val outputDir = findPluginConfig(parseDom(pom), "outputDir")
            if (outputDir.isNullOrBlank()) fallback(projectRoot) else File(projectRoot, outputDir)
        } catch (_: Exception) {
            fallback(projectRoot)
        }
    }

    fun readModules(projectRoot: File): List<Pair<String?, File>> {
        val pom = File(projectRoot, "pom.xml")
        if (!pom.exists()) return listOf(null to readOutputDir(projectRoot))
        return try {
            val doc = parseDom(pom)
            val root = doc.documentElement

            // Maven 4.1 <subprojects>/<subproject>
            val subprojects = childTexts(root, "subprojects", "subproject")
            if (subprojects.isNotEmpty()) {
                return subprojects.map { it to File(projectRoot, "$it/target/cartographer") }
            }
            // Maven 3 / Maven 4.0 <modules>/<module>
            val modules = childTexts(root, "modules", "module")
            if (modules.isNotEmpty()) {
                return modules.map { it to File(projectRoot, "$it/target/cartographer") }
            }
            // Single-module: check for custom outputDir
            val outputDir = findPluginConfig(doc, "outputDir")
            listOf(null to if (outputDir.isNullOrBlank()) fallback(projectRoot) else File(projectRoot, outputDir))
        } catch (_: Exception) {
            listOf(null to fallback(projectRoot))
        }
    }

    internal fun packaging(pom: File): String =
        try { parseDom(pom).documentElement.getElementsByTagName("packaging").item(0)?.textContent?.trim() ?: "jar" }
        catch (_: Exception) { "jar" }

    internal fun hasProfile(pom: File, profileId: String): Boolean =
        try { profileIds(parseDom(pom)).contains(profileId) }
        catch (_: Exception) { false }

    private fun profileIds(doc: Document): Set<String> {
        val result = mutableSetOf<String>()
        val profiles = doc.getElementsByTagName("profile")
        for (i in 0 until profiles.length) {
            val id = (profiles.item(i) as? Element)
                ?.getElementsByTagName("id")?.item(0)?.textContent?.trim()
            if (id != null) result += id
        }
        return result
    }

    private fun parseDom(pom: File): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom)
            .also { it.documentElement.normalize() }

    private fun childTexts(root: Element, containerTag: String, childTag: String): List<String> {
        val container = root.getElementsByTagName(containerTag).item(0) as? Element ?: return emptyList()
        val nodes = container.childNodes
        return (0 until nodes.length).mapNotNull { i ->
            val node = nodes.item(i)
            if (node is Element && node.tagName == childTag)
                node.textContent?.trim()?.takeIf { it.isNotEmpty() }
            else null
        }
    }

    private fun findPluginConfig(doc: Document, key: String): String? {
        val plugins = doc.getElementsByTagName("plugin")
        for (i in 0 until plugins.length) {
            val plugin = plugins.item(i) as? Element ?: continue
            val groupId = plugin.getElementsByTagName("groupId").item(0)?.textContent?.trim()
            val artifactId = plugin.getElementsByTagName("artifactId").item(0)?.textContent?.trim()
            if (groupId == "com.antwerkz" && artifactId == "cartographer-maven-plugin") {
                val config = plugin.getElementsByTagName("configuration").item(0) as? Element ?: continue
                return config.getElementsByTagName(key).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun fallback(projectRoot: File) = File(projectRoot, "target/cartographer")
}
