package com.antwerkz.cartographer.intellij

import org.apache.maven.api.model.Model
import org.apache.maven.api.model.Plugin
import org.apache.maven.model.v4.MavenStaxReader
import java.io.File

object PomConfigReader {

    fun readOutputDir(projectRoot: File): File {
        val pom = File(projectRoot, "pom.xml")
        if (!pom.exists()) return fallback(projectRoot)
        return try {
            val outputDir = cartographerOutputDir(readModel(pom))
            if (outputDir.isNullOrBlank()) fallback(projectRoot) else File(projectRoot, outputDir)
        } catch (_: Exception) {
            fallback(projectRoot)
        }
    }

    fun readModules(projectRoot: File): List<Pair<String?, File>> {
        val pom = File(projectRoot, "pom.xml")
        if (!pom.exists()) return listOf(null to fallback(projectRoot))
        return try {
            collectLeafModules(projectRoot, projectRoot)
                .ifEmpty { listOf(null to readOutputDir(projectRoot)) }
        } catch (_: Exception) {
            listOf(null to fallback(projectRoot))
        }
    }

    fun readModel(pom: File): Model = withPluginClassLoader {
        pom.inputStream().use { MavenStaxReader().read(it) }
    }

    internal fun packaging(pom: File): String =
        try { readModel(pom).packaging ?: "jar" }
        catch (_: Exception) { "jar" }

    internal fun hasProfile(pom: File, profileId: String): Boolean =
        try { readModel(pom).profiles.any { it.id == profileId } }
        catch (_: Exception) { false }

    private fun collectLeafModules(projectRoot: File, moduleDir: File): List<Pair<String?, File>> {
        val pom = File(moduleDir, "pom.xml")
        val moduleName = if (moduleDir == projectRoot) null else moduleDir.relativeTo(projectRoot).path
        val defaultEntry = moduleName to File(moduleDir, "target/cartographer")

        if (!pom.exists()) return listOf(defaultEntry)
        val model = try { readModel(pom) } catch (_: Exception) { return listOf(defaultEntry) }

        // Maven 4.1 subprojects take precedence over Maven 3/4.0 modules.
        // model.modules is deprecated in Maven 4 (replaced by subprojects) but still needed for Maven 3 poms.
        @Suppress("DEPRECATION")
        val submoduleNames = model.subprojects.ifEmpty { model.modules }
        if (submoduleNames.isNotEmpty()) {
            return submoduleNames.flatMap { name ->
                collectLeafModules(projectRoot, File(moduleDir, name))
            }
        }

        val customOutputDir = cartographerOutputDir(model)
        val outputDir = when {
            customOutputDir.isNullOrBlank() -> File(moduleDir, "target/cartographer")
            File(customOutputDir).isAbsolute -> File(customOutputDir)
            else -> File(moduleDir, customOutputDir)
        }
        return listOf(moduleName to outputDir)
    }

    private fun cartographerPlugins(model: Model): Sequence<Plugin> = sequence {
        yieldAll(model.build?.plugins ?: emptyList())
        yieldAll(model.profiles.flatMap { it.build?.plugins ?: emptyList() })
    }.filter { it.groupId == "com.antwerkz" && it.artifactId == "cartographer-maven-plugin" }

    private fun cartographerOutputDir(model: Model): String? =
        cartographerPlugins(model)
            .firstOrNull()
            ?.configuration
            ?.child("outputDir")
            ?.value()

    private fun <T> withPluginClassLoader(block: () -> T): T {
        val original = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = PomConfigReader::class.java.classLoader
        return try {
            block()
        } finally {
            Thread.currentThread().contextClassLoader = original
        }
    }

    private fun fallback(projectRoot: File) = File(projectRoot, "target/cartographer")
}
