package com.antwerkz.surveyor.intellij

import org.w3c.dom.Element
import org.w3c.dom.NodeList
import org.xml.sax.SAXException
import java.io.File
import java.io.IOException
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
                val groupId = plugin.directChildText("groupId")
                val artifactId = plugin.directChildText("artifactId")
                if (groupId == "com.antwerkz" && artifactId == "surveyor-maven-plugin") {
                    val config = plugin.directChild("configuration")
                    val outputDir = config?.directChildText("outputDir")
                    return if (outputDir.isNullOrBlank()) fallback(projectRoot)
                    else File(projectRoot, outputDir)
                }
            }
            fallback(projectRoot)
        } catch (_: SAXException) {
            fallback(projectRoot)
        } catch (_: IOException) {
            fallback(projectRoot)
        }
    }

    private fun Element.directChildText(tag: String): String =
        directChild(tag)?.textContent ?: ""

    private fun Element.directChild(tag: String): Element? {
        val nodes: NodeList = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName == tag) return node
        }
        return null
    }

    private fun fallback(projectRoot: File) = File(projectRoot, "target/surveyor")
}
