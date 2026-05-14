package com.antwerkz.surveyor.intellij

import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.codehaus.plexus.util.xml.Xpp3Dom
import java.io.File

object PomConfigReader {

    fun readOutputDir(projectRoot: File): File {
        val pom = File(projectRoot, "pom.xml")
        if (!pom.exists()) return fallback(projectRoot)
        return try {
            val model = MavenXpp3Reader().read(pom.bufferedReader())
            val plugin = model.build?.plugins?.find {
                it.groupId == "com.antwerkz" && it.artifactId == "surveyor-maven-plugin"
            } ?: return fallback(projectRoot)
            val outputDir = (plugin.configuration as? Xpp3Dom)?.getChild("outputDir")?.value
            if (outputDir.isNullOrBlank()) fallback(projectRoot) else File(projectRoot, outputDir)
        } catch (_: Exception) {
            fallback(projectRoot)
        }
    }

    fun readModules(projectRoot: File): List<Pair<String?, File>> {
        val pom = File(projectRoot, "pom.xml")
        if (!pom.exists()) return listOf(null to readOutputDir(projectRoot))
        return try {
            // Maven 4.1 <subprojects> — MavenXpp3Reader 3.x doesn't model this element,
            // so we read it from the DOM directly.
            val subprojects = domTagValues(pom, "subproject")
            if (subprojects.isNotEmpty()) {
                return subprojects.map { it to File(projectRoot, "$it/target/surveyor") }
            }
            // Maven 3 / Maven 4.0 <modules>
            val model = MavenXpp3Reader().read(pom.bufferedReader())
            val modules = model.modules ?: emptyList()
            if (modules.isNotEmpty()) {
                return modules.map { it to File(projectRoot, "$it/target/surveyor") }
            }
            listOf(null to readOutputDir(projectRoot))
        } catch (_: Exception) {
            listOf(null to readOutputDir(projectRoot))
        }
    }

    private fun domTagValues(pom: File, tagName: String): List<String> {
        return try {
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(pom)
            doc.documentElement.normalize()
            val nodes = doc.getElementsByTagName(tagName)
            (0 until nodes.length).mapNotNull {
                nodes.item(it)?.textContent?.trim()?.takeIf { s -> s.isNotEmpty() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fallback(projectRoot: File) = File(projectRoot, "target/surveyor")
}
