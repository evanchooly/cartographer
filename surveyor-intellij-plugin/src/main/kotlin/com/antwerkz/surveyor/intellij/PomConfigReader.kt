package com.antwerkz.surveyor.intellij

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

object PomConfigReader {

    fun readOutputDir(projectRoot: File): File {
        val pom = File(projectRoot, "pom.xml")
        if (!pom.exists()) return fallback(projectRoot)

        return try {
            val doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(pom)
            doc.documentElement.normalize()
            val plugins = doc.getElementsByTagName("plugin")
            for (i in 0 until plugins.length) {
                val plugin = plugins.item(i) as? Element ?: continue
                val groupId = plugin.getElementsByTagName("groupId").item(0)?.textContent ?: ""
                val artifactId = plugin.getElementsByTagName("artifactId").item(0)?.textContent ?: ""
                if (groupId == "com.antwerkz" && artifactId == "surveyor-maven-plugin") {
                    val outputDir = plugin.getElementsByTagName("outputDir").item(0)?.textContent
                    return if (outputDir.isNullOrBlank()) fallback(projectRoot)
                    else File(projectRoot, outputDir)
                }
            }
            fallback(projectRoot)
        } catch (_: Exception) {
            fallback(projectRoot)
        }
    }

    private fun fallback(projectRoot: File) = File(projectRoot, "target/surveyor")
}
