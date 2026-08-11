# Multimodule Plugin Support & TestNG IT Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Maven multimodule awareness to the IntelliJ plugin (collapsible per-module sections, multi-directory watcher) and add a TestNG integration test project to verify the agent instruments TestNG `@Test` methods correctly.

**Architecture:** `PomConfigReader` gains a `readModules()` method using `MavenXpp3Reader` (from `org.apache.maven:maven-model`) to read `<subprojects>` (Maven 4.1) or `<modules>` (Maven 3/4.0) into typed module lists. `TraceFileWatcher` is extended to watch one directory per module via a single `BulkFileListener`, emitting `Map<String?, List<File>>`. `TraceListPanel` wraps its existing class-group tree under per-module nodes when more than one module is present. `CartographerToolWindowFactory` is re-wired to pass the new types through. A separate `testng-test` Maven IT project exercises the agent's existing TestNG annotation handling end-to-end.

**Tech Stack:** `org.apache.maven:maven-model:3.9.9` · `org.codehaus.plexus:plexus-utils` (transitive, provides `Xpp3Dom`) · IntelliJ VFS `BulkFileListener` · Kotlin · JUnit 4 (existing plugin test framework) · TestNG 7.10.2 (IT project only) · Maven Invoker Plugin

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Modify | `cartographer-intellij-plugin/build.gradle.kts` | Add `maven-model` dependency |
| Modify | `…/intellij/PomConfigReader.kt` | Refactor `readOutputDir` to use `MavenXpp3Reader`; add `readModules()` |
| Modify | `…/intellij/TraceFileWatcher.kt` | Multi-directory constructor + listener |
| Modify | `…/intellij/ui/TraceListPanel.kt` | `refresh(Map<String?, List<File>>)` with optional module grouping |
| Modify | `…/intellij/CartographerToolWindowFactory.kt` | Wire `readModules` → `TraceFileWatcher` → `TraceListPanel` |
| Modify | `…/test/kotlin/…/PomConfigReaderTest.kt` | New `readModules` test cases |
| Create | `cartographer-maven-plugin/src/it/testng-test/pom.xml` | TestNG IT project POM |
| Create | `cartographer-maven-plugin/src/it/testng-test/src/main/java/com/example/Calculator.java` | Subject class |
| Create | `cartographer-maven-plugin/src/it/testng-test/src/test/java/com/example/CalculatorTest.java` | TestNG test class |
| Create | `cartographer-maven-plugin/src/it/testng-test/verify.groovy` | Assert trace files exist |

---

## Task 1: Add `maven-model` dependency and refactor `PomConfigReader`

Replaces the bare DOM parser in `readOutputDir` with `MavenXpp3Reader` for consistency.
`model.build.plugins` returns only build plugins (not plugins nested inside `<dependencies>`),
so the "does not confuse plugin dependency groupId" test remains valid and should still pass.

**Files:**
- Modify: `cartographer-intellij-plugin/build.gradle.kts`
- Modify: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/PomConfigReader.kt`

- [ ] **Step 1: Add `maven-model` to `build.gradle.kts`**

In the `dependencies { }` block, add after the `testImplementation` lines:

```kotlin
implementation("org.apache.maven:maven-model:3.9.9")
```

Full updated `dependencies` block:

```kotlin
dependencies {
    intellijPlatform {
        intellijIdea("2025.3")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Bundled)
    }
    implementation("org.apache.maven:maven-model:3.9.9")
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 2: Refactor `PomConfigReader.kt`**

Replace the entire file with the version below. `readOutputDir` now uses `MavenXpp3Reader`
and `Xpp3Dom` (from `plexus-utils`, a transitive dependency of `maven-model`). Behaviour and
fallback rules are identical to the previous version.

```kotlin
package com.antwerkz.cartographer.intellij

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
                it.groupId == "com.antwerkz" && it.artifactId == "cartographer-maven-plugin"
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
                return subprojects.map { it to File(projectRoot, "$it/target/cartographer") }
            }
            // Maven 3 / Maven 4.0 <modules>
            val model = MavenXpp3Reader().read(pom.bufferedReader())
            val modules = model.modules ?: emptyList()
            if (modules.isNotEmpty()) {
                return modules.map { it to File(projectRoot, "$it/target/cartographer") }
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

    private fun fallback(projectRoot: File) = File(projectRoot, "target/cartographer")
}
```

- [ ] **Step 3: Run existing `PomConfigReaderTest` to verify no regressions**

Run in `cartographer-intellij-plugin/`:
```
./gradlew test --tests "*.PomConfigReaderTest"
```

Expected: All 5 existing tests PASS.

- [ ] **Step 4: Commit**

```bash
git add cartographer-intellij-plugin/build.gradle.kts \
        cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/PomConfigReader.kt
git commit -m "feat: use MavenXpp3Reader in PomConfigReader, add readModules stub"
```

---

## Task 2: Test and implement `PomConfigReader.readModules()`

**Files:**
- Modify: `cartographer-intellij-plugin/src/test/kotlin/com/antwerkz/cartographer/intellij/PomConfigReaderTest.kt`

- [ ] **Step 1: Add failing tests for `readModules`**

Append these tests to `PomConfigReaderTest.kt` (inside the class, after existing tests):

```kotlin
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
```

- [ ] **Step 2: Run the new tests — verify they fail**

```
./gradlew test --tests "*.PomConfigReaderTest"
```

Expected: The 5 new `readModules` tests FAIL (method doesn't exist yet / wrong return). The original 5 tests still PASS.

Wait — `readModules` was already added in Task 1. Run the tests now to see if they pass. If they all pass already, skip to Step 3.

- [ ] **Step 3: Run all `PomConfigReaderTest` tests — verify all pass**

```
./gradlew test --tests "*.PomConfigReaderTest"
```

Expected: All 10 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add cartographer-intellij-plugin/src/test/kotlin/com/antwerkz/cartographer/intellij/PomConfigReaderTest.kt
git commit -m "test: add readModules tests for Maven 3, Maven 4, and single-module"
```

---

## Task 3: Update `TraceFileWatcher` for multi-directory

**Files:**
- Modify: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/TraceFileWatcher.kt`

- [ ] **Step 1: Replace `TraceFileWatcher.kt`**

```kotlin
package com.antwerkz.cartographer.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.MessageBusConnection
import java.io.File
import java.io.IOException

class TraceFileWatcher(
    private val project: Project,
    private val modules: List<Pair<String?, File>>,
    private val onChange: (Map<String?, List<File>>) -> Unit
) {
    private val canonicalPaths: List<Pair<String?, String>> = modules.map { (name, dir) ->
        name to try { dir.canonicalPath } catch (_: IOException) { dir.absolutePath }
    }

    @Volatile
    private var connection: MessageBusConnection? = null

    fun start() {
        connection?.disconnect()
        notifyFiles()
        connection = project.messageBus.connect(project).also { conn ->
            conn.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val relevant = events.any { e ->
                        val path = e.file?.path ?: return@any false
                        canonicalPaths.any { (_, canonical) -> path.startsWith(canonical) }
                    }
                    if (relevant) notifyFiles()
                }
            })
        }
    }

    fun stop() {
        connection?.disconnect()
        connection = null
    }

    private fun notifyFiles() {
        val result = modules.associate { (name, dir) ->
            name to if (dir.isDirectory) {
                dir.listFiles { f -> f.name.endsWith(".json") }
                    ?.sortedBy { it.name }
                    ?: emptyList()
            } else {
                emptyList()
            }
        }
        ApplicationManager.getApplication().invokeLater { onChange(result) }
    }
}
```

- [ ] **Step 2: Verify compilation**

```
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL. (`CartographerToolWindowFactory` will have a compile error since it still passes the old signature — that's fixed in Task 5.)

- [ ] **Step 3: Commit**

```bash
git add cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/TraceFileWatcher.kt
git commit -m "feat: extend TraceFileWatcher to watch multiple module directories"
```

---

## Task 4: Update `TraceListPanel` for module-level grouping

**Files:**
- Modify: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/TraceListPanel.kt`

- [ ] **Step 1: Replace `TraceListPanel.kt`**

The key change: `refresh` now accepts `Map<String?, List<File>>`. When the map has only a
single `null` key, the existing tree structure is built directly at root (no module node).
When module names are present, each module gets its own collapsible parent node whose children
are the existing class-group subtree. `cartographer-run.json` appears at the bottom of each
module section (or at root for single-module).

```kotlin
package com.antwerkz.cartographer.intellij.ui

import com.antwerkz.cartographer.intellij.OtlpJsonParser
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.io.File
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class TraceListPanel(private val onSelect: (File) -> Unit) : JPanel(BorderLayout()) {

    private val root = DefaultMutableTreeNode("root")
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = TraceTreeCellRenderer()
        tree.addTreeSelectionListener { e ->
            val node = e.path?.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val leaf = node.userObject as? TraceLeaf ?: return@addTreeSelectionListener
            onSelect(leaf.file)
        }
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    fun refresh(modules: Map<String?, List<File>>) {
        root.removeAllChildren()

        val singleModule = modules.size == 1 && modules.containsKey(null)

        if (singleModule) {
            populateClassNodes(root, modules[null] ?: emptyList())
        } else {
            modules.entries.sortedBy { it.key ?: "" }.forEach { (moduleName, files) ->
                val moduleNode = DefaultMutableTreeNode(ModuleHeader(moduleName ?: ""))
                populateClassNodes(moduleNode, files)
                if (moduleNode.childCount > 0) root.add(moduleNode)
            }
        }

        if (root.childCount == 0) {
            root.add(DefaultMutableTreeNode("No traces yet — run your tests"))
        }

        model.reload()
        for (i in 0 until tree.rowCount) tree.expandRow(i)
    }

    private fun populateClassNodes(parent: DefaultMutableTreeNode, files: List<File>) {
        val byClass = files
            .filter { it.name != "cartographer-run.json" }
            .mapNotNull { file ->
                val (fqcn, method) = parseFileName(file) ?: return@mapNotNull null
                Triple(file, fqcn, method)
            }
            .groupBy { (_, fqcn, _) -> fqcn }
            .toSortedMap()

        byClass.forEach { (fqcn, entries) ->
            val simpleClass = fqcn.substringAfterLast('.')
            val classNode = DefaultMutableTreeNode(simpleClass)
            entries.sortedBy { it.third }.forEach { (file, _, method) ->
                classNode.add(DefaultMutableTreeNode(TraceLeaf(file, method, rootDuration(file))))
            }
            parent.add(classNode)
        }

        val runFile = files.firstOrNull { it.name == "cartographer-run.json" }
        if (runFile != null) {
            parent.add(DefaultMutableTreeNode(TraceLeaf(runFile, "cartographer-run", null)))
        }
    }

    private fun parseFileName(file: File): Pair<String, String>? {
        val base = file.nameWithoutExtension
        val lastDot = base.lastIndexOf('.')
        if (lastDot < 0) return null
        return base.substring(0, lastDot) to base.substring(lastDot + 1)
    }

    private fun rootDuration(file: File): Double? = try {
        OtlpJsonParser.parse(file).firstOrNull()?.durationMs
    } catch (_: Exception) {
        null
    }

    data class ModuleHeader(val name: String)
    data class TraceLeaf(val file: File, val label: String, val durationMs: Double?)

    private class TraceTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: javax.swing.JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): Component {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
            val node = value as? DefaultMutableTreeNode ?: return this
            when (val uo = node.userObject) {
                is ModuleHeader -> {
                    text = uo.name
                    icon = null
                    font = font.deriveFont(Font.BOLD)
                    if (!selected) foreground = JBColor.foreground()
                }
                is TraceLeaf -> {
                    val dur = uo.durationMs?.let { "  %.0fms".format(it) } ?: ""
                    text = uo.label + dur
                    if (uo.file.name == "cartographer-run.json") {
                        if (!selected) foreground = JBColor.GRAY
                        font = font.deriveFont(Font.ITALIC)
                    }
                }
                is String -> {
                    text = uo
                    icon = null
                    if (!selected) foreground = JBColor.GRAY
                    font = font.deriveFont(Font.ITALIC)
                }
            }
            return this
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL (factory still broken — fixed in Task 5).

- [ ] **Step 3: Commit**

```bash
git add cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/TraceListPanel.kt
git commit -m "feat: add module-level grouping to TraceListPanel"
```

---

## Task 5: Update `CartographerToolWindowFactory` wiring

**Files:**
- Modify: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/CartographerToolWindowFactory.kt`

- [ ] **Step 1: Replace `CartographerToolWindowFactory.kt`**

```kotlin
package com.antwerkz.cartographer.intellij

import com.antwerkz.cartographer.intellij.ui.SpanDetailPanel
import com.antwerkz.cartographer.intellij.ui.TraceListPanel
import com.antwerkz.cartographer.intellij.ui.WaterfallPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.io.File
import javax.swing.JPanel
import javax.swing.event.AncestorEvent
import javax.swing.event.AncestorListener

class CartographerToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val detailPanel = SpanDetailPanel { span ->
            SourceNavigator.navigate(project, span.name)
        }

        val waterfallPanel = WaterfallPanel { span ->
            detailPanel.show(span)
        }

        val listPanel = TraceListPanel { file ->
            detailPanel.clear()
            ApplicationManager.getApplication().executeOnPooledThread {
                val roots = OtlpJsonParser.parse(file)
                ApplicationManager.getApplication().invokeLater {
                    waterfallPanel.load(roots)
                }
            }
        }

        val projectRoot = ProjectRootManager.getInstance(project)
            .contentRoots.firstOrNull()?.let { File(it.path) }
            ?: project.basePath?.let { File(it) }
            ?: File(".")

        val modules = PomConfigReader.readModules(projectRoot)

        val watcher = TraceFileWatcher(project, modules) { moduleFiles ->
            listPanel.refresh(moduleFiles)
        }

        val rightPanel = JPanel(BorderLayout()).apply {
            add(waterfallPanel, BorderLayout.CENTER)
            add(detailPanel, BorderLayout.SOUTH)
        }

        val splitter = JBSplitter(false, 0.25f).apply {
            firstComponent = listPanel
            secondComponent = rightPanel
        }

        val content = ContentFactory.getInstance()
            .createContent(splitter, "", false)

        splitter.addAncestorListener(object : AncestorListener {
            override fun ancestorAdded(event: AncestorEvent) = watcher.start()
            override fun ancestorRemoved(event: AncestorEvent) = watcher.stop()
            override fun ancestorMoved(event: AncestorEvent) = Unit
        })

        toolWindow.contentManager.addContent(content)
        watcher.start()
    }
}
```

- [ ] **Step 2: Verify full build and tests pass**

```
./gradlew build
```

Expected: BUILD SUCCESSFUL. All tests pass.

- [ ] **Step 3: Commit**

```bash
git add cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/CartographerToolWindowFactory.kt
git commit -m "feat: wire multimodule support through CartographerToolWindowFactory"
```

---

## Task 6: TestNG integration test project

**Files:**
- Create: `cartographer-maven-plugin/src/it/testng-test/pom.xml`
- Create: `cartographer-maven-plugin/src/it/testng-test/src/main/java/com/example/Calculator.java`
- Create: `cartographer-maven-plugin/src/it/testng-test/src/test/java/com/example/CalculatorTest.java`
- Create: `cartographer-maven-plugin/src/it/testng-test/verify.groovy`

- [ ] **Step 1: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>testng-test</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <dependencies>
        <dependency>
            <groupId>org.testng</groupId>
            <artifactId>testng</artifactId>
            <version>7.10.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>com.antwerkz</groupId>
                <artifactId>cartographer-maven-plugin</artifactId>
                <version>@project.version@</version>
                <configuration>
                    <packages>
                        <package>com.example</package>
                    </packages>
                    <captureArgs>true</captureArgs>
                </configuration>
                <executions>
                    <execution>
                        <goals><goal>instrument</goal></goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.3</version>
                <configuration>
                    <argLine>${argLine}</argLine>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create `Calculator.java`**

```java
package com.example;

public class Calculator {
    public int add(int a, int b) {
        return a + b;
    }

    public int multiply(int a, int b) {
        return a * b;
    }
}
```

- [ ] **Step 3: Create `CalculatorTest.java`**

```java
package com.example;

import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

public class CalculatorTest {

    private final Calculator calc = new Calculator();

    @Test
    public void testAdd() {
        assertEquals(calc.add(2, 3), 5);
    }

    @Test
    public void testMultiply() {
        assertEquals(calc.multiply(3, 4), 12);
    }
}
```

- [ ] **Step 4: Create `verify.groovy`**

```groovy
def cartographerDir = new File(basedir, "target/cartographer")
assert cartographerDir.exists() : "target/cartographer directory should exist"

def traceFiles = cartographerDir.listFiles { f -> f.name.endsWith(".json") && !f.name.startsWith("cartographer-run") }
assert traceFiles != null && traceFiles.length == 2 :
    "Expected 2 trace files (one per @Test), found: ${traceFiles?.length ?: 0}"

traceFiles.each { f ->
    assert f.length() > 0 : "Trace file ${f.name} should be non-empty"
}

true
```

- [ ] **Step 5: Run the IT suite to verify**

Run from `cartographer-maven-plugin/`:
```
mvn verify -Dinvoker.test=testng-test
```

Expected: BUILD SUCCESS. `target/cartographer/` contains exactly 2 non-empty `.json` files.

- [ ] **Step 6: Commit**

```bash
git add cartographer-maven-plugin/src/it/testng-test/
git commit -m "feat: add TestNG integration test to verify per-test trace generation"
```

---

## Self-Review Checklist

**Spec coverage:**
- ✅ `PomConfigReader.readModules()` with Maven 3 `<modules>` and Maven 4 `<subprojects>` — Task 1 + 2
- ✅ `<subprojects>` takes precedence over `<modules>` — Task 2 test
- ✅ Single-module fallback (`null` key) — Task 2 test + Task 4 branch
- ✅ `TraceFileWatcher` multi-directory with single `BulkFileListener` — Task 3
- ✅ `TraceListPanel` module sections; single-module renders as before — Task 4
- ✅ Module nodes sorted alphabetically, not selectable — Task 4 (`sortedBy { it.key }`)
- ✅ `cartographer-run.json` at bottom of each module section — Task 4 `populateClassNodes`
- ✅ `CartographerToolWindowFactory` re-wired — Task 5
- ✅ `maven-model` `MavenXpp3Reader` used instead of bare DOM — Task 1
- ✅ TestNG IT project with 2 test methods and `verify.groovy` assertion — Task 6

**No placeholders found.**

**Type consistency:**
- `readModules()` returns `List<Pair<String?, File>>` — consumed by `TraceFileWatcher(project, modules, onChange)` ✅
- `TraceFileWatcher.onChange` type is `(Map<String?, List<File>>) -> Unit` — matches `TraceListPanel.refresh(Map<String?, List<File>>)` ✅
- `ModuleHeader` and `TraceLeaf` are both defined in `TraceListPanel` and referenced only in the renderer ✅
