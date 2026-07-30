package com.antwerkz.cartographer.maven

import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import java.io.File

@Mojo(
    name = "setup",
    requiresProject = false,
    threadSafe = true
)
class SetupMojo : AbstractMojo() {

    @Parameter(property = "cartographer.packages")
    var packages: String = ""

    @Parameter(defaultValue = "\${basedir}/pom.xml", property = "cartographer.pomFile")
    lateinit var pomFile: File

    @Parameter(defaultValue = "cartographer", property = "cartographer.profileId")
    var profileId: String = "cartographer"

    @Parameter(property = "cartographer.endpoint")
    var endpoint: String? = null

    @Parameter(defaultValue = "false", property = "cartographer.captureArgs")
    var captureArgs: Boolean = false

    @Parameter(defaultValue = "256", property = "cartographer.maxArgLength")
    var maxArgLength: Int = 256

    @Parameter(defaultValue = "\${plugin.version}", readonly = true)
    var pluginVersion: String = "LATEST"

    override fun execute() {
        if (!pomFile.exists()) {
            throw MojoExecutionException("No pom.xml found at ${pomFile.absolutePath}")
        }

        val model = try {
            MavenXpp3Reader().read(pomFile.bufferedReader())
        } catch (e: Exception) {
            throw MojoExecutionException("Failed to parse ${pomFile.name}: ${e.message}", e)
        }

        if (model.profiles.any { it.id == profileId }) {
            log.warn("Profile '$profileId' already exists in ${pomFile.name} — nothing to do")
            return
        }

        val resolvedPackages = resolvePackages(model.packaging ?: "jar")
        insertProfile(pomFile, buildProfileXml(resolvedPackages))
        log.info("Added '$profileId' profile to ${pomFile.absolutePath}")
        log.info("Activate with: mvn test -D$profileId")
        log.warn("Review the '$profileId' profile in ${pomFile.name} — auto-detected values may need adjustment")
    }

    internal fun resolvePackages(packaging: String): String? {
        if (packages.isNotBlank()) return packages
        if (packaging == "pom") return null
        return detectPackage(pomFile.parentFile)
    }

    internal fun detectPackage(basedir: File): String? {
        val sourceRoot = listOf("kotlin", "java")
            .map { File(basedir, "src/main/$it") }
            .firstOrNull { it.isDirectory }
            ?: return null

        val segments = mutableListOf<String>()
        var current = sourceRoot

        while (true) {
            val subdirs = current.listFiles { f -> f.isDirectory }
                ?.sortedBy { it.name }
                ?: break
            when (subdirs.size) {
                1 -> { segments += subdirs[0].name; current = subdirs[0] }
                else -> break
            }
        }

        return segments.takeIf { it.isNotEmpty() }?.joinToString(".")
    }

    private fun buildProfileXml(resolvedPackages: String?): String {
        val packagesContent = if (resolvedPackages != null) {
            resolvedPackages.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n") { "                <package>$it</package>" }
        } else {
            "                <!-- TODO: replace with the root package(s) to instrument, e.g.:\n" +
            "                <package>com.example.myapp</package>\n" +
            "                -->"
        }

        val extras = buildString {
            if (endpoint != null) append("\n              <endpoint>$endpoint</endpoint>")
            if (captureArgs) append("\n              <captureArgs>true</captureArgs>")
            if (maxArgLength != 256) append("\n              <maxArgLength>$maxArgLength</maxArgLength>")
        }

        return """
    <profile>
      <id>$profileId</id>
      <activation>
        <property>
          <name>$profileId</name>
        </property>
      </activation>
      <build>
        <plugins>
          <plugin>
            <groupId>com.antwerkz</groupId>
            <artifactId>cartographer-maven-plugin</artifactId>
            <version>$pluginVersion</version>
            <configuration>
              <packages>
$packagesContent
              </packages>$extras
            </configuration>
            <executions>
              <execution>
                <goals><goal>instrument</goal></goals>
              </execution>
            </executions>
          </plugin>
          <plugin>
            <artifactId>maven-surefire-plugin</artifactId>
            <configuration>
              <argLine>@{argLine}</argLine>
            </configuration>
          </plugin>
        </plugins>
      </build>
    </profile>"""
    }

    private fun insertProfile(pom: File, profileXml: String) {
        val content = pom.readText()
        val modified = when {
            "</profiles>" in content ->
                content.replace("</profiles>", "$profileXml\n  </profiles>")
            Regex("<profiles\\s*/>").containsMatchIn(content) ->
                content.replace(Regex("<profiles\\s*/>"), "<profiles>$profileXml\n  </profiles>")
            else ->
                content.replace("</project>", "  <profiles>$profileXml\n  </profiles>\n</project>")
        }
        pom.writeText(modified)
    }
}
