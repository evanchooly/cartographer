package com.antwerkz.surveyor.intellij

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PomConfigReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `reads configured outputDir from plugin config`() {
        val pom = tmp.newFile("pom.xml").also {
            it.writeText("""
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>com.antwerkz</groupId>
                        <artifactId>surveyor-maven-plugin</artifactId>
                        <configuration>
                          <outputDir>custom/traces</outputDir>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
            """.trimIndent())
        }
        val result = PomConfigReader.readOutputDir(pom.parentFile)
        assertEquals(File(pom.parentFile, "custom/traces"), result)
    }

    @Test
    fun `falls back to target-surveyor when plugin present but no outputDir`() {
        val pom = tmp.newFile("pom.xml").also {
            it.writeText("""
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>com.antwerkz</groupId>
                        <artifactId>surveyor-maven-plugin</artifactId>
                      </plugin>
                    </plugins>
                  </build>
                </project>
            """.trimIndent())
        }
        val result = PomConfigReader.readOutputDir(pom.parentFile)
        assertEquals(File(pom.parentFile, "target/surveyor"), result)
    }

    @Test
    fun `falls back to target-surveyor when no pom xml`() {
        val result = PomConfigReader.readOutputDir(tmp.root)
        assertEquals(File(tmp.root, "target/surveyor"), result)
    }

    @Test
    fun `falls back to target-surveyor when plugin absent`() {
        val pom = tmp.newFile("pom.xml").also {
            it.writeText("<project><build><plugins></plugins></build></project>")
        }
        val result = PomConfigReader.readOutputDir(pom.parentFile)
        assertEquals(File(pom.parentFile, "target/surveyor"), result)
    }
}
