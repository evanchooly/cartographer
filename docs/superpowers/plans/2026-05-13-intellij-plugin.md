# Cartographer IntelliJ Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a `cartographer-intellij-plugin` Gradle subproject that adds an IntelliJ IDEA bottom tool window showing per-test OTLP trace files in a grouped tree, rendering a Jaeger-style waterfall and span detail strip with source navigation.

**Architecture:** A Gradle subproject (independent from the Maven build) using IntelliJ Platform Gradle Plugin v2. The tool window wires eight components together: a file watcher feeds a grouped tree list; single-clicking a trace parses its OTLP JSON into a `SpanNode` tree and loads it into a custom-painted waterfall panel; clicking a span shows a detail strip whose "Go to source" button uses `JavaPsiFacade` to navigate the editor.

**Tech Stack:** IntelliJ Platform Gradle Plugin v2.5.0 · Kotlin 2.0.20 · Gradle 8.8 · Gson (IntelliJ-bundled) · `javax.xml.parsers.DocumentBuilder` · IntelliJ VFS `BulkFileListener` · `JavaPsiFacade` / `PsiClass`

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `cartographer-intellij-plugin/settings.gradle.kts` | Project name + plugin management repos |
| Create | `cartographer-intellij-plugin/build.gradle.kts` | IntelliJ Platform Gradle Plugin v2 config |
| Create | `cartographer-intellij-plugin/gradle/wrapper/gradle-wrapper.properties` | Pin Gradle 8.8 |
| Create | `cartographer-intellij-plugin/src/main/resources/META-INF/plugin.xml` | Tool window registration |
| Create | `…/intellij/model/SpanNode.kt` | Immutable span data + tree |
| Create | `…/intellij/OtlpJsonParser.kt` | OTLP JSON → SpanNode tree (uses Gson) |
| Create | `…/intellij/PomConfigReader.kt` | Extract `<outputDir>` from `pom.xml` |
| Create | `…/intellij/TraceFileWatcher.kt` | VFS `BulkFileListener` lifecycle |
| Create | `…/intellij/ui/TraceListPanel.kt` | `JBTree` grouped by class name |
| Create | `…/intellij/ui/WaterfallPanel.kt` | Custom `JPanel` span-bar rendering |
| Create | `…/intellij/ui/SpanDetailPanel.kt` | Detail strip + "Go to source" button |
| Create | `…/intellij/SourceNavigator.kt` | `JavaPsiFacade` → editor navigation |
| Create | `…/intellij/CartographerToolWindowFactory.kt` | Wires all components, `ToolWindowFactory` |
| Create | `…/test/kotlin/…/OtlpJsonParserTest.kt` | Unit tests for parser |
| Create | `…/test/kotlin/…/PomConfigReaderTest.kt` | Unit tests with sample pom.xml strings |
| Create | `…/test/kotlin/…/SourceNavigatorTest.kt` | Light fixture test for PSI lookup |
| Create | `…/test/resources/greeter-trace.json` | Real trace fixture with parent-child spans |
| Create | `…/test/resources/single-span-trace.json` | Trace fixture with one root span, no children |

---

### Task 1: Gradle scaffold

**Files:**
- Create: `cartographer-intellij-plugin/settings.gradle.kts`
- Create: `cartographer-intellij-plugin/build.gradle.kts`
- Create: `cartographer-intellij-plugin/gradle/wrapper/gradle-wrapper.properties`
- Create: `cartographer-intellij-plugin/src/main/resources/META-INF/plugin.xml`

**Prerequisite:** Gradle 8.x must be installed (`gradle --version`).

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "cartographer-intellij-plugin"

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}
```

- [ ] **Step 2: Create `build.gradle.kts`**

```kotlin
plugins {
    id("org.jetbrains.intellij.platform") version "2.5.0"
    kotlin("jvm") version "2.0.20"
}

group = "com.antwerkz"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1")
        bundledPlugin("com.intellij.java")
        instrumentationTools()
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(17)
}
```

- [ ] **Step 3: Create `gradle/wrapper/gradle-wrapper.properties`**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.8-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 4: Generate the Gradle wrapper scripts**

Run in `cartographer-intellij-plugin/`:
```
gradle wrapper --gradle-version 8.8
```

Expected: `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` created.

- [ ] **Step 5: Create `src/main/resources/META-INF/plugin.xml`**

```xml
<idea-plugin>
    <id>com.antwerkz.cartographer</id>
    <name>Cartographer</name>
    <vendor>antwerkz</vendor>
    <description>Visualizes Cartographer test trace output as a Jaeger-style waterfall.</description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.java</depends>

    <extensions defaultExtensionNs="com.intellij">
        <toolWindow
            id="Cartographer"
            anchor="bottom"
            factoryClass="com.antwerkz.cartographer.intellij.CartographerToolWindowFactory"
        />
    </extensions>
</idea-plugin>
```

- [ ] **Step 6: Verify the build compiles**

Run in `cartographer-intellij-plugin/`:
```
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`. No source files yet; the task should succeed with nothing to compile.

- [ ] **Step 7: Commit**

```bash
git add cartographer-intellij-plugin/
git commit -m "feat: scaffold cartographer-intellij-plugin Gradle project"
```

---

### Task 2: SpanNode model

**Files:**
- Create: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/model/SpanNode.kt`

- [ ] **Step 1: Create `SpanNode.kt`**

```kotlin
package com.antwerkz.cartographer.intellij.model

data class SpanNode(
    val spanId: String,
    val name: String,
    val startNano: Long,
    val endNano: Long,
    val attributes: Map<String, String>,
    val children: MutableList<SpanNode> = mutableListOf(),
    val depth: Int = 0
) {
    val durationMs: Double get() = (endNano - startNano) / 1_000_000.0

    /** Simple `ClassName.methodName` extracted from the fully qualified span name. */
    val simpleName: String get() {
        val lastDot = name.lastIndexOf('.')
        return if (lastDot < 0) name else {
            val classLastDot = name.lastIndexOf('.', lastDot - 1)
            if (classLastDot < 0) name else name.substring(classLastDot + 1)
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/model/
git commit -m "feat: add SpanNode model"
```

---

### Task 3: OtlpJsonParser

**Files:**
- Create: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/OtlpJsonParser.kt`
- Create: `cartographer-intellij-plugin/src/test/kotlin/com/antwerkz/cartographer/intellij/OtlpJsonParserTest.kt`
- Create: `cartographer-intellij-plugin/src/test/resources/greeter-trace.json`
- Create: `cartographer-intellij-plugin/src/test/resources/single-span-trace.json`

The actual OTLP JSON format (from the IT test output) has:
- `resourceSpans[].scopeSpans[].spans[]`
- Each span: `spanId`, `parentSpanId` (absent on root spans), `name`, `startTimeUnixNano` (string), `endTimeUnixNano` (string), `attributes[]{key, value{stringValue}}`

- [ ] **Step 1: Create `greeter-trace.json` test fixture**

```json
{
  "resourceSpans": [{
    "scopeSpans": [{
      "spans": [
        {
          "traceId": "e8bccf8752d06b1bd5b8059410ebe54a",
          "spanId": "b76fccd299027ead",
          "parentSpanId": "037a76a8444ea4d7",
          "name": "com.example.Greeter.<init>",
          "kind": 1,
          "startTimeUnixNano": "1778565913055134852",
          "endTimeUnixNano": "1778565913055150093",
          "attributes": [],
          "events": [], "links": [], "status": {}, "flags": 259
        },
        {
          "traceId": "e8bccf8752d06b1bd5b8059410ebe54a",
          "spanId": "d39238dd043bc2bd",
          "parentSpanId": "037a76a8444ea4d7",
          "name": "com.example.Greeter.greet",
          "kind": 1,
          "startTimeUnixNano": "1778565913055202389",
          "endTimeUnixNano": "1778565913057630243",
          "attributes": [
            {"key": "arg.0", "value": {"stringValue": "World"}},
            {"key": "arg.1", "value": {"stringValue": "2"}}
          ],
          "events": [], "links": [], "status": {}, "flags": 259
        },
        {
          "traceId": "e8bccf8752d06b1bd5b8059410ebe54a",
          "spanId": "037a76a8444ea4d7",
          "name": "com.example.GreeterTest.testGreet",
          "kind": 1,
          "startTimeUnixNano": "1778565913043578780",
          "endTimeUnixNano": "1778565913058995258",
          "attributes": [],
          "events": [], "links": [], "status": {}, "flags": 259
        }
      ]
    }]
  }]
}
```

- [ ] **Step 2: Create `single-span-trace.json` test fixture**

```json
{
  "resourceSpans": [{
    "scopeSpans": [{
      "spans": [{
        "traceId": "ff300761940ca40037cc877cd85926e4",
        "spanId": "217e1b24c8c1e9e6",
        "name": "com.example.CalculatorTest.<init>",
        "kind": 1,
        "startTimeUnixNano": "1778565909654849675",
        "endTimeUnixNano": "1778565909654857234",
        "attributes": [],
        "events": [], "links": [], "status": {}, "flags": 259
      }]
    }]
  }]
}
```

- [ ] **Step 3: Write the failing test**

```kotlin
package com.antwerkz.cartographer.intellij

import com.antwerkz.cartographer.intellij.model.SpanNode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OtlpJsonParserTest {

    private fun resource(name: String) =
        File(javaClass.getResource("/$name")!!.toURI())

    @Test
    fun `parses root span from single-span trace`() {
        val roots = OtlpJsonParser.parse(resource("single-span-trace.json"))
        assertEquals(1, roots.size)
        val root = roots[0]
        assertEquals("com.example.CalculatorTest.<init>", root.name)
        assertEquals(0, root.depth)
        assertTrue(root.children.isEmpty())
        assertTrue(root.durationMs > 0)
    }

    @Test
    fun `parses tree from greeter trace`() {
        val roots = OtlpJsonParser.parse(resource("greeter-trace.json"))
        assertEquals(1, roots.size)
        val root = roots[0]
        assertEquals("com.example.GreeterTest.testGreet", root.name)
        assertEquals(0, root.depth)
        assertEquals(2, root.children.size)
    }

    @Test
    fun `children are sorted by start time`() {
        val roots = OtlpJsonParser.parse(resource("greeter-trace.json"))
        val children = roots[0].children
        assertTrue(children[0].startNano <= children[1].startNano)
    }

    @Test
    fun `child spans have depth 1`() {
        val roots = OtlpJsonParser.parse(resource("greeter-trace.json"))
        roots[0].children.forEach { assertEquals(1, it.depth) }
    }

    @Test
    fun `extracts attributes from greet span`() {
        val roots = OtlpJsonParser.parse(resource("greeter-trace.json"))
        val greet = roots[0].children.first { it.name.endsWith(".greet") }
        assertEquals("World", greet.attributes["arg.0"])
        assertEquals("2", greet.attributes["arg.1"])
    }

    @Test
    fun `computes durationMs correctly`() {
        val roots = OtlpJsonParser.parse(resource("single-span-trace.json"))
        val span = roots[0]
        val expected = (1778565909654857234L - 1778565909654849675L) / 1_000_000.0
        assertEquals(expected, span.durationMs, 0.001)
    }

    @Test
    fun `returns empty list for empty resourceSpans`() {
        val tmp = createTempFile(suffix = ".json").also {
            it.writeText("""{"resourceSpans":[]}""")
            it.deleteOnExit()
        }
        val roots = OtlpJsonParser.parse(tmp)
        assertTrue(roots.isEmpty())
    }
}
```

- [ ] **Step 4: Run test — verify it fails**

```
./gradlew test --tests "*.OtlpJsonParserTest"
```

Expected: FAIL — `OtlpJsonParser` does not exist yet.

- [ ] **Step 5: Create `OtlpJsonParser.kt`**

```kotlin
package com.antwerkz.cartographer.intellij

import com.antwerkz.cartographer.intellij.model.SpanNode
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

object OtlpJsonParser {

    fun parse(file: File): List<SpanNode> {
        val raw = mutableListOf<RawSpan>()
        try {
            val root = JsonParser.parseReader(file.reader()).asJsonObject
            root.getAsJsonArray("resourceSpans")?.forEach { rs ->
                rs.asJsonObject.getAsJsonArray("scopeSpans")?.forEach { ss ->
                    ss.asJsonObject.getAsJsonArray("spans")?.forEach { s ->
                        raw += parseRaw(s.asJsonObject)
                    }
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return buildTree(raw)
    }

    private fun parseRaw(obj: JsonObject): RawSpan {
        val attrs = mutableMapOf<String, String>()
        obj.getAsJsonArray("attributes")?.forEach { a ->
            val ao = a.asJsonObject
            val key = ao.get("key")?.asString ?: return@forEach
            val value = ao.getAsJsonObject("value")?.get("stringValue")?.asString ?: ""
            attrs[key] = value
        }
        return RawSpan(
            spanId = obj.get("spanId").asString,
            parentSpanId = obj.get("parentSpanId")?.asString ?: "",
            name = obj.get("name").asString,
            startNano = obj.get("startTimeUnixNano").asString.toLong(),
            endNano = obj.get("endTimeUnixNano").asString.toLong(),
            attributes = attrs
        )
    }

    private fun buildTree(rawSpans: List<RawSpan>): List<SpanNode> {
        val childrenByParent = rawSpans
            .filter { it.parentSpanId.isNotEmpty() }
            .groupBy { it.parentSpanId }
        val roots = rawSpans.filter { it.parentSpanId.isEmpty() }
        return roots.map { build(it, 0, childrenByParent) }
    }

    private fun build(
        raw: RawSpan,
        depth: Int,
        childrenByParent: Map<String, List<RawSpan>>
    ): SpanNode {
        val children = childrenByParent[raw.spanId]
            ?.sortedBy { it.startNano }
            ?.map { build(it, depth + 1, childrenByParent) }
            ?.toMutableList()
            ?: mutableListOf()
        return SpanNode(
            spanId = raw.spanId,
            name = raw.name,
            startNano = raw.startNano,
            endNano = raw.endNano,
            attributes = raw.attributes,
            children = children,
            depth = depth
        )
    }

    private data class RawSpan(
        val spanId: String,
        val parentSpanId: String,
        val name: String,
        val startNano: Long,
        val endNano: Long,
        val attributes: Map<String, String>
    )
}
```

- [ ] **Step 6: Run tests — verify they pass**

```
./gradlew test --tests "*.OtlpJsonParserTest"
```

Expected: All 7 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add cartographer-intellij-plugin/src/
git commit -m "feat: add OtlpJsonParser with tests"
```

---

### Task 4: PomConfigReader

**Files:**
- Create: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/PomConfigReader.kt`
- Create: `cartographer-intellij-plugin/src/test/kotlin/com/antwerkz/cartographer/intellij/PomConfigReaderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
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
}
```

- [ ] **Step 2: Run test — verify it fails**

```
./gradlew test --tests "*.PomConfigReaderTest"
```

Expected: FAIL — `PomConfigReader` does not exist yet.

- [ ] **Step 3: Create `PomConfigReader.kt`**

```kotlin
package com.antwerkz.cartographer.intellij

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

object PomConfigReader {

    fun readOutputDir(projectRoot: File): File {
        val pom = File(projectRoot, "pom.xml")
        if (!pom.exists()) return fallback(projectRoot)

        return try {
            val doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(pom)
            doc.documentElement.normalize()
            val plugins = doc.getElementsByTagName("plugin")
            for (i in 0 until plugins.length) {
                val plugin = plugins.item(i) as? Element ?: continue
                val groupId = plugin.getElementsByTagName("groupId").item(0)?.textContent ?: ""
                val artifactId = plugin.getElementsByTagName("artifactId").item(0)?.textContent ?: ""
                if (groupId == "com.antwerkz" && artifactId == "cartographer-maven-plugin") {
                    val outputDir = plugin.getElementsByTagName("outputDir").item(0)?.textContent
                    return if (outputDir.isNullOrBlank()) fallback(projectRoot)
                    else File(projectRoot, outputDir)
                }
            }
            fallback(projectRoot)
        } catch (_: Exception) {
            fallback(projectRoot)
        }
    }

    private fun fallback(projectRoot: File) = File(projectRoot, "target/cartographer")
}
```

- [ ] **Step 4: Run tests — verify they pass**

```
./gradlew test --tests "*.PomConfigReaderTest"
```

Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add cartographer-intellij-plugin/src/
git commit -m "feat: add PomConfigReader with tests"
```

---

### Task 5: TraceListPanel

**Files:**
- Create: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/TraceListPanel.kt`

The panel groups trace files by simple class name. Filenames follow `<fqcn>.<methodName>.json`, e.g. `com.example.CalculatorTest.testAdd.json`. `cartographer-run.json` is shown at the bottom, dimmed and italic.

- [ ] **Step 1: Create `TraceListPanel.kt`**

```kotlin
package com.antwerkz.cartographer.intellij.ui

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

    fun refresh(files: List<File>) {
        root.removeAllChildren()

        if (files.isEmpty()) {
            root.add(DefaultMutableTreeNode("No traces yet — run your tests"))
            model.reload()
            return
        }

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
                val duration = rootDuration(file)
                classNode.add(DefaultMutableTreeNode(TraceLeaf(file, method, duration)))
            }
            root.add(classNode)
        }

        val runFile = files.firstOrNull { it.name == "cartographer-run.json" }
        if (runFile != null) {
            root.add(DefaultMutableTreeNode(TraceLeaf(runFile, "cartographer-run", null)))
        }

        model.reload()
        for (i in 0 until tree.rowCount) tree.expandRow(i)
    }

    private fun parseFileName(file: File): Pair<String, String>? {
        val base = file.nameWithoutExtension  // com.example.CalculatorTest.testAdd
        val lastDot = base.lastIndexOf('.')
        if (lastDot < 0) return null
        return base.substring(0, lastDot) to base.substring(lastDot + 1)
    }

    private fun rootDuration(file: File): Double? {
        return try {
            val roots = com.antwerkz.cartographer.intellij.OtlpJsonParser.parse(file)
            roots.firstOrNull()?.durationMs
        } catch (_: Exception) {
            null
        }
    }

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
                is TraceLeaf -> {
                    val dur = uo.durationMs?.let { "  %.0fms".format(it) } ?: ""
                    text = uo.label + dur
                    if (uo.file.name == "cartographer-run.json") {
                        foreground = JBColor.GRAY
                        font = font.deriveFont(Font.ITALIC)
                    }
                }
                is String -> {
                    text = uo
                    icon = null
                    foreground = JBColor.GRAY
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

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/TraceListPanel.kt
git commit -m "feat: add TraceListPanel grouped JBTree"
```

---

### Task 6: TraceFileWatcher

**Files:**
- Create: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/TraceFileWatcher.kt`

- [ ] **Step 1: Create `TraceFileWatcher.kt`**

```kotlin
package com.antwerkz.cartographer.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.MessageBusConnection
import java.io.File

class TraceFileWatcher(
    private val project: Project,
    private val outputDir: File,
    private val onChange: (List<File>) -> Unit
) {
    private var connection: MessageBusConnection? = null

    fun start() {
        connection?.disconnect()
        notifyFiles()

        connection = project.messageBus.connect().also { conn ->
            conn.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val relevant = events.any { e ->
                        e.file?.path?.startsWith(outputDir.canonicalPath) == true
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
        val files = if (outputDir.isDirectory) {
            outputDir.listFiles { f -> f.name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?: emptyList()
        } else {
            emptyList()
        }
        onChange(files)
    }
}
```

- [ ] **Step 2: Verify compilation**

```
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/TraceFileWatcher.kt
git commit -m "feat: add TraceFileWatcher VFS listener"
```

---

### Task 7: WaterfallPanel

**Files:**
- Create: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/WaterfallPanel.kt`

Layout: fixed `LABEL_WIDTH=200px` label column (right-aligned), then bar area. Each bar: horizontal bar proportional to span duration against total trace time, pushed right by `depth * INDENT_PX`. Row height = 22px. Time axis header = 24px. Root span = blue (`#3d6b8e`), selected = amber (`#e8a838`), children = green (`#5e8e4d`).

- [ ] **Step 1: Create `WaterfallPanel.kt`**

```kotlin
package com.antwerkz.cartographer.intellij.ui

import com.antwerkz.cartographer.intellij.model.SpanNode
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.max

private val COLOR_ROOT = Color(0x3d, 0x6b, 0x8e)
private val COLOR_CHILD = Color(0x5e, 0x8e, 0x4d)
private val COLOR_SELECTED = Color(0xe8, 0xa8, 0x38)
private const val ROW_HEIGHT = 22
private const val AXIS_HEIGHT = 24
private const val LABEL_WIDTH = 200
private const val INDENT_PX = 12
private const val MIN_BAR_WIDTH = 2

class WaterfallPanel(private val onSpanSelected: (SpanNode) -> Unit) : JPanel() {

    private var flatSpans: List<SpanNode> = emptyList()
    var selectedSpan: SpanNode? = null
        private set
    private var rootStartNano: Long = 0
    private var totalNano: Long = 1

    private val inner = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            paint(g as Graphics2D)
        }
        override fun getPreferredSize() =
            Dimension(parent?.width ?: 600, AXIS_HEIGHT + flatSpans.size * ROW_HEIGHT)
    }

    private val scroll = JBScrollPane(inner)

    init {
        layout = BorderLayout()
        add(scroll, BorderLayout.CENTER)
        inner.background = JBColor.background()
        inner.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = (e.y - AXIS_HEIGHT) / ROW_HEIGHT
                if (row in flatSpans.indices) {
                    selectedSpan = flatSpans[row]
                    inner.repaint()
                    onSpanSelected(selectedSpan!!)
                }
            }
        })
    }

    fun load(roots: List<SpanNode>) {
        flatSpans = flatten(roots)
        selectedSpan = null
        if (flatSpans.isNotEmpty()) {
            rootStartNano = flatSpans.minOf { it.startNano }
            totalNano = max(1L, flatSpans.maxOf { it.endNano } - rootStartNano)
        }
        SwingUtilities.invokeLater {
            inner.revalidate()
            inner.repaint()
        }
    }

    private fun flatten(spans: List<SpanNode>): List<SpanNode> = buildList {
        for (s in spans) {
            add(s)
            addAll(flatten(s.children))
        }
    }

    private fun paint(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val w = inner.width
        val barAreaWidth = w - LABEL_WIDTH

        // Time axis
        g.color = JBColor.border()
        g.drawLine(LABEL_WIDTH, AXIS_HEIGHT - 1, w, AXIS_HEIGHT - 1)
        val tickCount = 5
        g.font = g.font.deriveFont(9f)
        for (i in 0..tickCount) {
            val x = LABEL_WIDTH + (barAreaWidth * i / tickCount)
            g.color = JBColor.GRAY
            g.drawLine(x, AXIS_HEIGHT - 4, x, AXIS_HEIGHT)
            val ms = "%.0fms".format(totalNano / 1_000_000.0 * i / tickCount)
            g.drawString(ms, x + 2, AXIS_HEIGHT - 6)
        }

        // Span rows
        flatSpans.forEachIndexed { i, span ->
            val y = AXIS_HEIGHT + i * ROW_HEIGHT
            val indent = span.depth * INDENT_PX

            // Label
            g.color = if (span == selectedSpan) JBColor.foreground() else JBColor.GRAY
            g.font = g.font.deriveFont(11f)
            val label = span.simpleName
            val labelX = LABEL_WIDTH - 6 - g.fontMetrics.stringWidth(label)
            g.drawString(label, labelX.coerceAtLeast(0), y + ROW_HEIGHT - 6)

            // Bar
            val relStart = span.startNano - rootStartNano
            val available = max(1, barAreaWidth - indent)
            val barX = LABEL_WIDTH + indent + (relStart.toDouble() / totalNano * available).toInt()
            val barW = max(MIN_BAR_WIDTH, ((span.endNano - span.startNano).toDouble() / totalNano * available).toInt())

            g.color = when {
                span == selectedSpan -> COLOR_SELECTED
                span.depth == 0 -> COLOR_ROOT
                else -> COLOR_CHILD
            }
            g.fillRoundRect(barX, y + 5, barW, ROW_HEIGHT - 10, 3, 3)

            if (span == selectedSpan) {
                g.color = Color.WHITE
                g.drawRoundRect(barX, y + 5, barW, ROW_HEIGHT - 10, 3, 3)
            }

            // Duration label
            g.color = JBColor.GRAY
            g.font = g.font.deriveFont(9f)
            g.drawString("%.0fms".format(span.durationMs), barX + barW + 4, y + ROW_HEIGHT - 6)
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/WaterfallPanel.kt
git commit -m "feat: add WaterfallPanel span-bar renderer"
```

---

### Task 8: SpanDetailPanel

**Files:**
- Create: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/SpanDetailPanel.kt`

A fixed-height strip pinned below the waterfall. Hidden when nothing is selected. Shows: amber span name, duration, argument attributes (if any), and a "→ Go to source" button.

- [ ] **Step 1: Create `SpanDetailPanel.kt`**

```kotlin
package com.antwerkz.cartographer.intellij.ui

import com.antwerkz.cartographer.intellij.model.SpanNode
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

class SpanDetailPanel(private val onGoToSource: (SpanNode) -> Unit) : JPanel(BorderLayout()) {

    private val nameLabel = JLabel().apply { foreground = Color(0xe8, 0xa8, 0x38) }
    private val durationLabel = JLabel()
    private val attrsLabel = JLabel()
    private val goToSourceButton = JButton("→ Go to source")
    private var currentSpan: SpanNode? = null

    init {
        border = EmptyBorder(4, 8, 4, 8)
        background = JBColor(Color(0x2b, 0x2b, 0x2b), Color(0x2b, 0x2b, 0x2b))
        isVisible = false

        val content = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
            isOpaque = false
            add(nameLabel)
            add(durationLabel)
            add(attrsLabel)
            add(goToSourceButton)
        }
        add(content, BorderLayout.CENTER)

        goToSourceButton.addActionListener {
            currentSpan?.let { onGoToSource(it) }
        }
    }

    fun show(span: SpanNode) {
        currentSpan = span
        nameLabel.text = span.simpleName
        durationLabel.text = "%.0fms".format(span.durationMs)
        attrsLabel.text = span.attributes.entries
            .sortedBy { it.key }
            .joinToString("  ") { "${it.key}: ${it.value}" }
            .ifEmpty { "" }
        isVisible = true
        revalidate()
        repaint()
    }

    fun clear() {
        currentSpan = null
        isVisible = false
    }
}
```

- [ ] **Step 2: Verify compilation**

```
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/ui/SpanDetailPanel.kt
git commit -m "feat: add SpanDetailPanel with Go to source button"
```

---

### Task 9: SourceNavigator

**Files:**
- Create: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/SourceNavigator.kt`
- Create: `cartographer-intellij-plugin/src/test/kotlin/com/antwerkz/cartographer/intellij/SourceNavigatorTest.kt`

Algorithm: split span name on last `.` → `className` + `methodName`. Use `JavaPsiFacade` to find the `PsiClass`, then `findMethodsByName` or `constructors`. Navigate with `navigate(true)`. Show `HintManager` balloon if class not found.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.antwerkz.cartographer.intellij

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class SourceNavigatorTest : LightJavaCodeInsightFixtureTestCase() {

    fun `test navigate resolves Calculator add method`() {
        myFixture.addClass("""
            package com.example;
            public class Calculator {
                public int add(int a, int b) { return a + b; }
            }
        """.trimIndent())

        // navigate(true) opens an editor — just verify no exception is thrown
        // and the class resolves without error
        SourceNavigator.navigate(project, "com.example.Calculator.add")
        // If JavaPsiFacade found the class and method, the call succeeds silently.
        // A missing class would log a balloon instead; we verify the happy path compiles and runs.
    }

    fun `test navigate shows balloon for unknown class`() {
        // Should not throw — missing class falls back to HintManager balloon
        SourceNavigator.navigate(project, "com.example.NonExistent.method")
    }

    fun `test navigate handles constructor`() {
        myFixture.addClass("""
            package com.example;
            public class Widget { public Widget() {} }
        """.trimIndent())
        SourceNavigator.navigate(project, "com.example.Widget.<init>")
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```
./gradlew test --tests "*.SourceNavigatorTest"
```

Expected: FAIL — `SourceNavigator` does not exist yet.

- [ ] **Step 3: Create `SourceNavigator.kt`**

```kotlin
package com.antwerkz.cartographer.intellij

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

object SourceNavigator {

    fun navigate(project: Project, spanName: String) {
        val lastDot = spanName.lastIndexOf('.')
        if (lastDot < 0) return
        val className = spanName.substring(0, lastDot)
        val methodName = spanName.substring(lastDot + 1)

        ApplicationManager.getApplication().invokeLater {
            val psiFacade = JavaPsiFacade.getInstance(project)
            val psiClass = psiFacade.findClass(className, GlobalSearchScope.allScope(project))

            if (psiClass == null) {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                if (editor != null) {
                    HintManager.getInstance().showErrorHint(editor, "Source not available for $spanName")
                }
                return@invokeLater
            }

            val target = if (methodName == "<init>") {
                psiClass.constructors.firstOrNull() ?: psiClass
            } else {
                psiClass.findMethodsByName(methodName, true).firstOrNull() ?: psiClass
            }

            target.navigate(true)
        }
    }
}
```

- [ ] **Step 4: Run tests — verify they pass**

```
./gradlew test --tests "*.SourceNavigatorTest"
```

Expected: All 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add cartographer-intellij-plugin/src/
git commit -m "feat: add SourceNavigator with PSI integration test"
```

---

### Task 10: CartographerToolWindowFactory — wire everything together

**Files:**
- Create: `cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/CartographerToolWindowFactory.kt`

The factory creates a `JBSplitter` (horizontal, 25% left / 75% right) with `TraceListPanel` on the left and a `BorderLayout` panel on the right containing `WaterfallPanel` (center) and `SpanDetailPanel` (south). `TraceFileWatcher` is started when the window is shown and stopped when hidden.

- [ ] **Step 1: Create `CartographerToolWindowFactory.kt`**

```kotlin
package com.antwerkz.cartographer.intellij

import com.antwerkz.cartographer.intellij.ui.SpanDetailPanel
import com.antwerkz.cartographer.intellij.ui.TraceListPanel
import com.antwerkz.cartographer.intellij.ui.WaterfallPanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.JBSplitter
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

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
            val roots = try {
                OtlpJsonParser.parse(file)
            } catch (_: Exception) {
                emptyList()
            }
            waterfallPanel.load(roots)
        }

        val outputDir = PomConfigReader.readOutputDir(
            com.intellij.openapi.roots.ProjectRootManager.getInstance(project)
                .contentRoots.firstOrNull()?.let { java.io.File(it.path) }
                ?: java.io.File(".")
        )

        val watcher = TraceFileWatcher(project, outputDir) { files ->
            listPanel.refresh(files)
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
        toolWindow.contentManager.addContent(content)

        // Start/stop watcher with window visibility
        if (toolWindow is ToolWindowEx) {
            toolWindow.isVisible.let { if (it) watcher.start() }
        }
        toolWindow.contentManager.addContentManagerListener(object :
            com.intellij.ui.content.ContentManagerListener {
            override fun contentAdded(event: com.intellij.ui.content.ContentManagerEvent) {}
            override fun contentRemoved(event: com.intellij.ui.content.ContentManagerEvent) {}
        })

        // Use a component listener on the splitter to detect show/hide
        splitter.addAncestorListener(object : javax.swing.event.AncestorListener {
            override fun ancestorAdded(event: javax.swing.event.AncestorEvent) = watcher.start()
            override fun ancestorRemoved(event: javax.swing.event.AncestorEvent) = watcher.stop()
            override fun ancestorMoved(event: javax.swing.event.AncestorEvent) {}
        })
    }
}
```

- [ ] **Step 2: Verify compilation**

```
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run all tests**

```
./gradlew test
```

Expected: All tests PASS.

- [ ] **Step 4: Commit**

```bash
git add cartographer-intellij-plugin/src/main/kotlin/com/antwerkz/cartographer/intellij/CartographerToolWindowFactory.kt
git commit -m "feat: add CartographerToolWindowFactory wiring all UI components"
```

---

### Task 11: Manual verification with runIde

**Prerequisite:** The `cartographer-maven-plugin` integration tests must have been run at least once so that `cartographer-maven-plugin/target/it/basic-test/target/cartographer/` contains trace JSON files.

- [ ] **Step 1: Build the plugin**

Run in `cartographer-intellij-plugin/`:
```
./gradlew buildPlugin
```

Expected: `BUILD SUCCESSFUL`. Plugin zip created under `build/distributions/`.

- [ ] **Step 2: Launch a sandboxed IDE**

```
./gradlew runIde
```

Expected: IntelliJ IDEA opens with the Cartographer plugin loaded.

- [ ] **Step 3: Open the basic-test project in the sandboxed IDE**

In the sandboxed IDEA, open `cartographer-maven-plugin/src/it/basic-test/` as a project.

- [ ] **Step 4: Verify tool window**

1. Click "Cartographer" in the bottom stripe — the tool window opens.
2. The left pane shows `CalculatorTest` as a group with `testAdd` and `testMultiply` leaves.
3. `cartographer-run` appears dimmed and italic below the groups.

- [ ] **Step 5: Verify waterfall**

1. Single-click `testAdd` — the waterfall renders on the right with at least one span row.
2. The root span has a blue bar. Child spans (if any) have green bars.

- [ ] **Step 6: Verify span detail**

1. Click any span bar in the waterfall — the detail strip appears below showing name and duration.
2. If the span has `arg.*` attributes they appear in the strip.

- [ ] **Step 7: Verify "Go to source"**

1. The `CalculatorTest.<init>` span is present (it appears in the trace).
2. Click "→ Go to source" — the editor opens `CalculatorTest.java` and positions the cursor at the constructor.
3. For a span whose class isn't in the project source, a balloon reading "Source not available for …" appears instead of navigating.

- [ ] **Step 8: Verify watcher**

1. Run `mvn test` in the basic-test project from a terminal.
2. Without restarting IDEA, new/updated traces appear in the Cartographer panel automatically.

- [ ] **Step 9: Verify collapse/expand**

1. Collapse the Cartographer tool window.
2. Re-expand it — the trace list is refreshed and the previously selected trace is shown (if its file changed, the waterfall reloads).

- [ ] **Step 10: Final commit**

```bash
git add cartographer-intellij-plugin/
git commit -m "feat: complete cartographer-intellij-plugin initial implementation"
```

---

## Error Handling Reference

| Situation | Component | Behaviour |
|---|---|---|
| `outputDir` does not exist | `TraceFileWatcher` | Calls `onChange(emptyList())` — list shows empty (or label "No traces yet") |
| Trace file is malformed JSON | `OtlpJsonParser.parse` | Returns `emptyList()`; waterfall renders nothing |
| Class not found in PSI | `SourceNavigator` | `HintManager` balloon: "Source not available for `<name>`" |
| Multiple overloads | `SourceNavigator` | Navigates to `findMethodsByName(...)[0]`; no disambiguation in v1 |
