package com.antwerkz.cartographer.maven

import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.artifact.Artifact

@Mojo(
    name = "instrument",
    defaultPhase = LifecyclePhase.INITIALIZE,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true
)
class CartographerMojo : AbstractMojo() {

    @Parameter(required = true)
    lateinit var packages: List<String>

    @Parameter(defaultValue = "\${project.build.directory}/cartographer")
    lateinit var outputDir: String

    @Parameter
    var endpoint: String? = null

    @Parameter(defaultValue = "false")
    var captureArgs: Boolean = false

    @Parameter(defaultValue = "256")
    var maxArgLength: Int = 256

    @Parameter(defaultValue = "\${plugin.artifacts}", readonly = true, required = true)
    lateinit var pluginArtifacts: List<Artifact>

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    lateinit var project: org.apache.maven.project.MavenProject

    override fun execute() {
        val agentJar = pluginArtifacts
            .firstOrNull { it.groupId == "com.antwerkz" && it.artifactId == "cartographer-agent" }
            ?.file
            ?: throw org.apache.maven.plugin.MojoExecutionException(
                "cartographer-agent artifact not found in plugin artifacts. " +
                "Ensure the cartographer-maven-plugin is correctly configured."
            )

        val agentArgs = buildString {
            append("packages=")
            append(packages.joinToString(","))
            append("|outputDir=")
            append(outputDir)
            endpoint?.let { append("|endpoint=$it") }
            if (captureArgs) append("|captureArgs=true")
            if (maxArgLength != 256) append("|maxArgLength=$maxArgLength")
        }

        val javaAgentArg = "-javaagent:${agentJar.absolutePath}=$agentArgs"

        val existing = project.properties.getProperty("argLine") ?: ""
        val updated = if (existing.isBlank()) javaAgentArg else "$existing $javaAgentArg"
        project.properties.setProperty("argLine", updated)

        log.info("Cartographer agent configured: $javaAgentArg")
    }
}
