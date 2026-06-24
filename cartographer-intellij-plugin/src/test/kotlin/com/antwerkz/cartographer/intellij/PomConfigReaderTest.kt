package com.antwerkz.cartographer.intellij

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
                        <artifactId>cartographer-maven-plugin</artifactId>
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
    fun `falls back to target-cartographer when plugin present but no outputDir`() {
        val pom = tmp.newFile("pom.xml").also {
            it.writeText("""
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>com.antwerkz</groupId>
                        <artifactId>cartographer-maven-plugin</artifactId>
                      </plugin>
                    </plugins>
                  </build>
                </project>
            """.trimIndent())
        }
        val result = PomConfigReader.readOutputDir(pom.parentFile)
        assertEquals(File(pom.parentFile, "target/cartographer"), result)
    }

    @Test
    fun `falls back to target-cartographer when no pom xml`() {
        val result = PomConfigReader.readOutputDir(tmp.root)
        assertEquals(File(tmp.root, "target/cartographer"), result)
    }

    @Test
    fun `falls back to target-cartographer when plugin absent`() {
        val pom = tmp.newFile("pom.xml").also {
            it.writeText("<project><build><plugins></plugins></build></project>")
        }
        val result = PomConfigReader.readOutputDir(pom.parentFile)
        assertEquals(File(pom.parentFile, "target/cartographer"), result)
    }

    @Test
    fun `does not confuse plugin dependency groupId with plugin groupId`() {
        val pom = tmp.newFile("pom.xml").also {
            it.writeText("""
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <dependencies>
                          <dependency>
                            <groupId>com.antwerkz</groupId>
                            <artifactId>cartographer-maven-plugin</artifactId>
                          </dependency>
                        </dependencies>
                      </plugin>
                      <plugin>
                        <groupId>com.antwerkz</groupId>
                        <artifactId>cartographer-maven-plugin</artifactId>
                        <configuration>
                          <outputDir>real/output</outputDir>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
            """.trimIndent())
        }
        val result = PomConfigReader.readOutputDir(pom.parentFile)
        assertEquals(File(pom.parentFile, "real/output"), result)
    }

    @Test
    fun `readModules returns single null entry when no modules element`() {
        val pom = tmp.newFile("pom.xml").also {
            it.writeText("<project><build><plugins></plugins></build></project>")
        }
        val result = PomConfigReader.readModules(pom.parentFile)
        assertEquals(1, result.size)
        assertEquals(null, result[0].first)
        assertEquals(File(pom.parentFile, "target/cartographer"), result[0].second)
    }

    @Test
    fun `readModules reads Maven 3 modules`() {
        val pom = tmp.newFile("pom.xml").also {
            it.writeText("""
                <project>
                  <modules>
                    <module>module-a</module>
                    <module>module-b</module>
                  </modules>
                </project>
            """.trimIndent())
        }
        val result = PomConfigReader.readModules(pom.parentFile)
        assertEquals(2, result.size)
        assertEquals("module-a" to File(pom.parentFile, "module-a/target/cartographer"), result[0])
        assertEquals("module-b" to File(pom.parentFile, "module-b/target/cartographer"), result[1])
    }

    @Test
    fun `readModules reads Maven 4 subprojects`() {
        val pom = tmp.newFile("pom.xml").also {
            it.writeText("""
                <project>
                  <subprojects>
                    <subproject>service-a</subproject>
                    <subproject>service-b</subproject>
                  </subprojects>
                </project>
            """.trimIndent())
        }
        val result = PomConfigReader.readModules(pom.parentFile)
        assertEquals(2, result.size)
        assertEquals("service-a" to File(pom.parentFile, "service-a/target/cartographer"), result[0])
        assertEquals("service-b" to File(pom.parentFile, "service-b/target/cartographer"), result[1])
    }

    @Test
    fun `readModules prefers subprojects over modules when both present`() {
        val pom = tmp.newFile("pom.xml").also {
            it.writeText("""
                <project>
                  <subprojects>
                    <subproject>new-module</subproject>
                  </subprojects>
                  <modules>
                    <module>old-module</module>
                  </modules>
                </project>
            """.trimIndent())
        }
        val result = PomConfigReader.readModules(pom.parentFile)
        assertEquals(1, result.size)
        assertEquals("new-module", result[0].first)
    }

    @Test
    fun `readModules returns single null entry when no pom xml`() {
        val result = PomConfigReader.readModules(tmp.root)
        assertEquals(1, result.size)
        assertEquals(null, result[0].first)
    }
}
