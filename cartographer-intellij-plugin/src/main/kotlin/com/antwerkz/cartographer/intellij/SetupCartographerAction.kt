package com.antwerkz.cartographer.intellij

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

class SetupCartographerAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file?.name == "pom.xml"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val pomFile = File(virtualFile.path)
        val project = e.project

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { doSetup(pomFile) }
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(pomFile)
            ApplicationManager.getApplication().invokeLater {
                val (message, type) = when {
                    result.isSuccess && result.getOrNull() == false ->
                        "Cartographer profile already exists in ${pomFile.name} — nothing changed." to NotificationType.INFORMATION
                    result.isSuccess ->
                        "Cartographer profile added to ${pomFile.name}. Review auto-detected values before running." to NotificationType.WARNING
                    else ->
                        "Failed to set up Cartographer: ${result.exceptionOrNull()?.message}" to NotificationType.ERROR
                }
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Cartographer")
                    .createNotification(message, type)
                    .notify(project)
            }
        }
    }

    /** Returns true if the profile was inserted, false if it already existed. */
    private fun doSetup(pomFile: File): Boolean {
        if (PomConfigReader.hasProfile(pomFile, "cartographer")) return false
        val packaging = PomConfigReader.packaging(pomFile)
        insertProfile(pomFile, buildProfileXml(resolvePackages(pomFile, packaging)))
        return true
    }

    private fun resolvePackages(pomFile: File, packaging: String): String? {
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
        val version = PluginManagerCore.getPlugin(PluginId.getId("com.antwerkz.cartographer"))?.version ?: "LATEST"
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

        return """
    <profile>
      <id>cartographer</id>
      <activation>
        <property>
          <name>cartographer</name>
        </property>
      </activation>
      <build>
        <plugins>
          <plugin>
            <groupId>com.antwerkz</groupId>
            <artifactId>cartographer-maven-plugin</artifactId>
            <version>$version</version>
            <configuration>
              <packages>
$packagesContent
              </packages>
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
