package com.antwerkz.cartographer.maven

import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.apache.maven.plugin.MojoExecutionException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SetupMojoTest {

    @TempDir
    lateinit var tempDir: Path

    // ── pom helpers ────────────────────────────────────────────────────────────

    private fun minimalPom(packaging: String = "jar"): File =
        tempDir.resolve("pom.xml").toFile().also {
            it.writeText("""
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>my-app</artifactId>
                  <version>1.0-SNAPSHOT</version>
                  <packaging>$packaging</packaging>
                </project>
            """.trimIndent())
        }

    private fun pomWithProfile(profileXml: String): File =
        tempDir.resolve("pom.xml").toFile().also {
            it.writeText("""
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>my-app</artifactId>
                  <version>1.0-SNAPSHOT</version>
                  <profiles>
                    $profileXml
                  </profiles>
                </project>
            """.trimIndent())
        }

    private fun mojo(pom: File, configure: SetupMojo.() -> Unit = {}): SetupMojo =
        SetupMojo().apply {
            pomFile = pom
            pluginVersion = "1.0.0-SNAPSHOT"
            configure()
        }

    // ── explicit packages ──────────────────────────────────────────────────────

    @Test
    fun `explicit packages are written to profile`() {
        val pom = minimalPom()
        mojo(pom) { packages = "com.example" }.execute()
        val content = pom.readText()
        assertTrue(content.contains("<package>com.example</package>"))
    }

    @Test
    fun `multiple comma-separated packages each become a package element`() {
        val pom = minimalPom()
        mojo(pom) { packages = "com.example,com.other" }.execute()
        val content = pom.readText()
        assertTrue(content.contains("<package>com.example</package>"))
        assertTrue(content.contains("<package>com.other</package>"))
    }

    // ── package auto-detection ─────────────────────────────────────────────────

    @Test
    fun `detects package from src-main-java source tree`() {
        File(tempDir.toFile(), "src/main/java/com/example/myapp/service").mkdirs()
        File(tempDir.toFile(), "src/main/java/com/example/myapp/model").mkdirs()
        val pom = minimalPom()
        val detected = mojo(pom).detectPackage(tempDir.toFile())
        assertEquals("com.example.myapp", detected)
    }

    @Test
    fun `detects package from src-main-kotlin source tree`() {
    File(tempDir.toFile(), "src/main/kotlin/com/example/myapp/service").mkdirs()
        File(tempDir.toFile(), "src/main/kotlin/com/example/myapp/model").mkdirs()
        val pom = minimalPom()
        val detected = mojo(pom).detectPackage(tempDir.toFile())
        assertEquals("com.example.myapp", detected)
    }

    @Test
    fun `prefers kotlin over java when both exist`() {
        File(tempDir.toFile(), "src/main/kotlin/com/kotlin/pkg/a").mkdirs()
        File(tempDir.toFile(), "src/main/kotlin/com/kotlin/pkg/b").mkdirs()
        File(tempDir.toFile(), "src/main/java/com/java/pkg/a").mkdirs()
        File(tempDir.toFile(), "src/main/java/com/java/pkg/b").mkdirs()
        val detected = mojo(minimalPom()).detectPackage(tempDir.toFile())
        assertEquals("com.kotlin.pkg", detected)
    }

    @Test
    fun `returns null when no source directory exists`() {
        assertNull(mojo(minimalPom()).detectPackage(tempDir.toFile()))
    }

    @Test
    fun `returns null when source root itself has multiple subdirs`() {
        File(tempDir.toFile(), "src/main/java/com").mkdirs()
        File(tempDir.toFile(), "src/main/java/org").mkdirs()
        assertNull(mojo(minimalPom()).detectPackage(tempDir.toFile()))
    }

    @Test
    fun `auto-detected package is used when packages not specified`() {
        File(tempDir.toFile(), "src/main/java/com/example/myapp/service").mkdirs()
        File(tempDir.toFile(), "src/main/java/com/example/myapp/model").mkdirs()
        val pom = minimalPom()
        mojo(pom).execute()
        assertTrue(pom.readText().contains("<package>com.example.myapp</package>"))
    }

    // ── pom-packaging → comment placeholder ───────────────────────────────────

    @Test
    fun `pom packaging inserts comment placeholder for packages`() {
        val pom = minimalPom(packaging = "pom")
        mojo(pom).execute()
        val content = pom.readText()
        assertTrue(content.contains("<!-- TODO"), "expected TODO comment when packaging=pom")
        // <package> should only appear as example text inside the comment, not as live XML
        val commentBlock = content.substringAfter("<!--").substringBefore("-->")
        assertTrue(commentBlock.contains("<package>"), "comment should show example package element")
        assertTrue(!content.substringAfter("-->").contains("<package>"), "no active package element after comment")
    }

    @Test
    fun `no source dir and non-pom packaging also inserts comment placeholder`() {
        val pom = minimalPom(packaging = "jar")
        mojo(pom).execute()
        val content = pom.readText()
        assertTrue(content.contains("TODO"))
    }

    @Test
    fun `resolvePackages returns null for pom packaging regardless of source dirs`() {
        File(tempDir.toFile(), "src/main/java/com/example/a").mkdirs()
        File(tempDir.toFile(), "src/main/java/com/example/b").mkdirs()
        assertNull(mojo(minimalPom(packaging = "pom")).resolvePackages("pom"))
    }

    @Test
    fun `explicit packages override auto-detection`() {
        File(tempDir.toFile(), "src/main/java/com/detected/a").mkdirs()
        File(tempDir.toFile(), "src/main/java/com/detected/b").mkdirs()
        val pom = minimalPom()
        mojo(pom) { packages = "com.explicit" }.execute()
        val content = pom.readText()
        assertTrue(content.contains("<package>com.explicit</package>"))
        assertTrue(!content.contains("com.detected"))
    }

    // ── profile structure ──────────────────────────────────────────────────────

    @Test
    fun `adds profile to pom without existing profiles`() {
        val pom = minimalPom()
        mojo(pom) { packages = "com.example" }.execute()
        val content = pom.readText()
        assertTrue(content.contains("<id>cartographer</id>"))
        assertTrue(content.contains("<name>cartographer</name>"))
        assertTrue(content.contains("cartographer-maven-plugin"))
        assertTrue(content.contains("@{argLine}"))
        assertTrue(content.contains("<goal>instrument</goal>"))
    }

    @Test
    fun `adds profile alongside existing profiles`() {
        val pom = pomWithProfile("<profile><id>other</id></profile>")
        mojo(pom) { packages = "com.example" }.execute()
        val content = pom.readText()
        assertTrue(content.contains("<id>other</id>"), "existing profile preserved")
        assertTrue(content.contains("<id>cartographer</id>"), "new profile added")
    }

    @Test
    fun `is idempotent when profile already exists`() {
        val pom = pomWithProfile("<profile><id>cartographer</id></profile>")
        val before = pom.readText()
        mojo(pom) { packages = "com.example" }.execute()
        assertEquals(before, pom.readText())
    }

    @Test
    fun `profile activation uses profile id as property name`() {
        val pom = minimalPom()
        mojo(pom) { packages = "com.example" }.execute()
        val model = MavenXpp3Reader().read(pom.bufferedReader())
        val profile = model.profiles.first { it.id == "cartographer" }
        assertEquals("cartographer", profile.activation.property.name)
    }

    @Test
    fun `generated pom remains valid xml`() {
        val pom = minimalPom()
        mojo(pom) { packages = "com.example" }.execute()
        assertDoesNotThrow { MavenXpp3Reader().read(pom.bufferedReader()) }
    }

    // ── optional config ────────────────────────────────────────────────────────

    @Test
    fun `includes optional endpoint when configured`() {
        val pom = minimalPom()
        mojo(pom) { packages = "com.example"; endpoint = "http://localhost:4318" }.execute()
        assertTrue(pom.readText().contains("<endpoint>http://localhost:4318</endpoint>"))
    }

    @Test
    fun `omits optional config when using defaults`() {
        val pom = minimalPom()
        mojo(pom) { packages = "com.example" }.execute()
        val content = pom.readText()
        assertTrue(!content.contains("<endpoint>"))
        assertTrue(!content.contains("<captureArgs>"))
        assertTrue(!content.contains("<maxArgLength>"))
    }

    @Test
    fun `includes captureArgs when enabled`() {
        val pom = minimalPom()
        mojo(pom) { packages = "com.example"; captureArgs = true }.execute()
        assertTrue(pom.readText().contains("<captureArgs>true</captureArgs>"))
    }

    @Test
    fun `custom profileId is used for profile id and activation property`() {
        val pom = minimalPom()
        mojo(pom) { packages = "com.example"; profileId = "tracing" }.execute()
        val content = pom.readText()
        assertTrue(content.contains("<id>tracing</id>"))
        assertTrue(content.contains("<name>tracing</name>"))
    }

    @Test
    fun `throws when pom not found`() {
        val m = mojo(File(tempDir.toFile(), "nonexistent.xml"))
        assertThrows(MojoExecutionException::class.java) { m.execute() }
    }
}
