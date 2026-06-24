package com.antwerkz.cartographer.maven

import org.apache.maven.artifact.Artifact
import org.apache.maven.artifact.DefaultArtifact
import org.apache.maven.artifact.handler.DefaultArtifactHandler
import org.apache.maven.project.MavenProject
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class CartographerMojoTest {

    private lateinit var mojo: CartographerMojo
    private lateinit var project: MavenProject
    private lateinit var fakeAgentJar: File

    @BeforeEach
    fun setUp() {
        fakeAgentJar = File.createTempFile("cartographer-agent", ".jar")
        fakeAgentJar.deleteOnExit()

        project = MavenProject()
        mojo = CartographerMojo()
        mojo.project = project
        mojo.packages = listOf("com.example")
        mojo.outputDir = "/tmp/cartographer"
        mojo.captureArgs = false
        mojo.maxArgLength = 256
        mojo.pluginArtifacts = listOf(fakeAgentArtifact(fakeAgentJar))
    }

    @Test
    fun `sets argLine property with javaagent`() {
        mojo.execute()
        val argLine = project.properties.getProperty("argLine") ?: ""
        assertTrue(argLine.contains("-javaagent:"), "argLine should contain -javaagent: but was: $argLine")
        assertTrue(argLine.contains("cartographer-agent"), "argLine should reference cartographer-agent jar")
        assertTrue(argLine.contains("packages=com.example"), "argLine should contain packages config")
        assertTrue(argLine.contains("outputDir=/tmp/cartographer"), "argLine should contain outputDir config")
    }

    @Test
    fun `appends to existing argLine`() {
        project.properties.setProperty("argLine", "-Xmx512m")
        mojo.execute()
        val argLine = project.properties.getProperty("argLine") ?: ""
        assertTrue(argLine.startsWith("-Xmx512m "), "should preserve existing argLine prefix")
        assertTrue(argLine.contains("-javaagent:"), "should append javaagent")
    }

    @Test
    fun `includes endpoint when configured`() {
        mojo.endpoint = "http://localhost:4318"
        mojo.execute()
        val argLine = project.properties.getProperty("argLine") ?: ""
        assertTrue(argLine.contains("endpoint=http://localhost:4318"), "should include endpoint")
    }

    @Test
    fun `includes captureArgs when enabled`() {
        mojo.captureArgs = true
        mojo.execute()
        val argLine = project.properties.getProperty("argLine") ?: ""
        assertTrue(argLine.contains("captureArgs=true"), "should include captureArgs=true")
    }

    @Test
    fun `includes maxArgLength when not default`() {
        mojo.maxArgLength = 128
        mojo.execute()
        val argLine = project.properties.getProperty("argLine") ?: ""
        assertTrue(argLine.contains("maxArgLength=128"), "should include custom maxArgLength")
    }

    @Test
    fun `omits maxArgLength when default`() {
        mojo.maxArgLength = 256
        mojo.execute()
        val argLine = project.properties.getProperty("argLine") ?: ""
        assertTrue(!argLine.contains("maxArgLength"), "should not include maxArgLength when default")
    }

    @Test
    fun `throws when agent artifact not found`() {
        mojo.pluginArtifacts = emptyList()
        assertThrows(org.apache.maven.plugin.MojoExecutionException::class.java) {
            mojo.execute()
        }
    }

    private fun fakeAgentArtifact(jar: File): Artifact {
        val artifact = DefaultArtifact(
            "com.antwerkz",
            "cartographer-agent",
            "1.0-SNAPSHOT",
            "compile",
            "jar",
            null,
            DefaultArtifactHandler("jar")
        )
        artifact.file = jar
        return artifact
    }
}
