# Cartographer IntelliJ Plugin — Design Document

Date: 2026-05-13

## Overview

A new `cartographer-intellij-plugin` module adds an IntelliJ IDEA tool window that watches
the agent's trace output directory, shows recorded per-test traces in a grouped list, and
renders a Jaeger-style waterfall view when a trace is selected. Clicking a span shows a
detail strip with duration, captured arguments, and a "Go to source" button that opens
the corresponding method in the editor.

---

## Module Structure

The plugin lives as a Gradle subproject inside the existing Maven repo. It is built and
distributed independently from the Maven modules.

```
cartographer/
├── pom.xml                          ← Maven parent (unchanged)
├── cartographer-agent/                  ← unchanged
├── cartographer-maven-plugin/           ← unchanged
└── cartographer-intellij-plugin/        ← NEW: Gradle project
    ├── build.gradle.kts             ← IntelliJ Platform Gradle Plugin v2
    ├── gradle/
    │   └── wrapper/                 ← Gradle wrapper
    ├── src/
    │   ├── main/
    │   │   ├── kotlin/com/antwerkz/cartographer/intellij/
    │   │   │   ├── CartographerToolWindowFactory.kt
    │   │   │   ├── PomConfigReader.kt
    │   │   │   ├── TraceFileWatcher.kt
    │   │   │   ├── OtlpJsonParser.kt
    │   │   │   ├── model/SpanNode.kt
    │   │   │   ├── ui/TraceListPanel.kt
    │   │   │   ├── ui/WaterfallPanel.kt
    │   │   │   ├── ui/SpanDetailPanel.kt
    │   │   │   └── SourceNavigator.kt
    │   │   └── resources/META-INF/plugin.xml
    │   └── test/kotlin/…
    └── settings.gradle.kts
```

### `build.gradle.kts` key settings

```kotlin
plugins {
    id("org.jetbrains.intellij.platform") version "2.x"
    kotlin("jvm")
}
intellijPlatform {
    intellijIdeaCommunity("2024.1")   // minimum supported version
}
```

The plugin targets **IntelliJ IDEA 2024.1+** (Community and Ultimate).
Kotlin is the implementation language throughout.

---

## Components

### `PomConfigReader`

Reads the project's `pom.xml` on the calling thread when the tool window first opens.
Traverses the XML tree looking for:

```xml
<plugin>
  <groupId>com.antwerkz</groupId>
  <artifactId>cartographer-maven-plugin</artifactId>
  <configuration>
    <outputDir>…</outputDir>
  </configuration>
</plugin>
```

Returns the configured path resolved relative to the project root, or
`${project.basedir}/target/cartographer/` as the fallback. Uses the JDK's built-in
`javax.xml.parsers.DocumentBuilder` — no extra dependency.

---

### `TraceFileWatcher`

Manages the VFS subscription lifecycle tied to the tool window's visibility:

- **On tool window expand:** registers a `BulkFileListener` on the IntelliJ message bus
  scoped to the `VirtualFileManager.VFS_CHANGES` topic. Filters for events in `outputDir`.
  Performs an initial directory scan and populates the list.
- **On tool window collapse:** unregisters the listener.
- **On reopen:** re-scans the directory. If the currently selected file's last-modified
  timestamp has changed, re-parses and re-renders the waterfall for that file.
- Emits change events to `CartographerToolWindowFactory` via a simple listener interface.

---

### `OtlpJsonParser`

Parses OTLP JSON trace files (as written by `TraceRequestMarshaler.writeJsonTo`) using
**Gson**, which is bundled with the IntelliJ platform.

Input shape (relevant fields only):
```json
{
  "resourceSpans": [{
    "scopeSpans": [{
      "spans": [{
        "traceId": "…",
        "spanId": "…",
        "parentSpanId": "…",
        "name": "com.example.Calculator.add",
        "startTimeUnixNano": "…",
        "endTimeUnixNano": "…",
        "attributes": [{"key": "arg.0", "value": {"stringValue": "2"}}]
      }]
    }]
  }]
}
```

Output: a list of `SpanNode` objects arranged into a tree via `parentSpanId` linkage.

---

### `SpanNode` (model)

```kotlin
data class SpanNode(
    val spanId: String,
    val name: String,                      // e.g. "com.example.Calculator.add"
    val startNano: Long,
    val endNano: Long,
    val attributes: Map<String, String>,   // flattened: "arg.0" -> "2"
    val children: MutableList<SpanNode> = mutableListOf(),
    val depth: Int = 0
) {
    val durationMs: Double get() = (endNano - startNano) / 1_000_000.0
}
```

---

### `CartographerToolWindowFactory`

Registered in `plugin.xml` as a `ToolWindowFactory` anchored to the **bottom** stripe
(anchor = `BOTTOM`). Creates a `JBSplitter` (vertical split, ~25% left / ~75% right)
containing `TraceListPanel` on the left and `WaterfallPanel` + `SpanDetailPanel` stacked
on the right.

Wires all components together:
- Gives `TraceFileWatcher` a callback that calls `TraceListPanel.refresh(files)`
- Forwards `TraceListPanel` selection events to `WaterfallPanel.load(spans)`
- Forwards `WaterfallPanel` span-click events to `SpanDetailPanel.show(span)`
- Wires `SpanDetailPanel`'s "Go to source" action to `SourceNavigator`

---

### `TraceListPanel`

A `Tree` (`com.intellij.ui.treeStructure.Tree`) on the left side of the split, backed
by a `DefaultTreeModel`. Group nodes are non-selectable; leaf nodes are selectable.

**Grouping and ordering:**

Trace filenames follow the pattern `<fqcn>.<methodName>.json`
(e.g. `com.example.CalculatorTest.testAdd.json`). The list is organized as a tree:

- Group headers use the **simple class name** (`CalculatorTest`), sorted alphabetically.
- Leaf nodes show the **method name** (`testAdd`), sorted alphabetically within the group.
- `cartographer-run.json` appears at the **bottom**, outside any group, rendered dimmed/italic
  (it collects spans that fall outside a test root — typically class constructors fired
  before a `@Test` method starts).

Interaction:
- **Single click** on a leaf → fires a selection event → `WaterfallPanel` loads that trace.
- Group headers are not selectable (clicking expands/collapses the group).
- Leaf nodes show the duration from the root span as a secondary label, e.g. `testAdd  15ms`.

---

### `WaterfallPanel`

A custom `JPanel` on the right side of the split. Renders spans as horizontal bars on a
shared time axis.

**Layout:**
- Each row: left-aligned label (`<simpleClassName>.<methodName>`) right-aligned in a
  fixed-width column, then a bar proportional to the span's duration relative to the
  trace root, indented by `depth * 12px`.
- Root span (test method) uses a blue bar. Child spans use green. The selected span uses
  amber with a white outline.
- A time axis header shows tick marks at even intervals (e.g. 0ms, 5ms, 10ms…).

**Interaction:**
- **Single click** on a row → selects the span, fires an event to `SpanDetailPanel`.
- The panel is scrollable vertically for deep call trees.
- The label column width is fixed; the bar area fills remaining width proportionally.

---

### `SpanDetailPanel`

A fixed-height strip (~40px) pinned below `WaterfallPanel`. Hidden when nothing is
selected.

Displays (left to right):
1. Span name in amber — e.g. `Calculator.add`
2. Duration — e.g. `8ms`
3. Captured argument attributes — e.g. `arg.0: 2  arg.1: 2` (omitted if none)
4. **"→ Go to source"** button — calls `SourceNavigator`

---

### `SourceNavigator`

Maps a span name to a source location and opens the editor.

Algorithm:
1. Split span name on the **last `.`** → `className` = `com.example.Calculator`,
   `methodName` = `add` (or `<init>` for constructors).
2. Call `JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.allScope(project))`
   to get the `PsiClass`.
3. For regular methods: `psiClass.findMethodsByName(methodName, true)[0]`.
   For constructors (`<init>`): `psiClass.constructors[0]`.
4. Navigate using `psiElement.navigate(true)` or `OpenFileDescriptor` pointing at the
   method's text offset.
5. If the class is not found (e.g. a dependency class, not in source): show an
   `HintManager` balloon — "Source not available for `<name>`" — instead of navigating.

---

## Data Flow

```
Project opens
  └─ CartographerToolWindowFactory created (tool window registered, not yet visible)

User expands Cartographer tool window
  └─ PomConfigReader.read(project) → outputDir
  └─ TraceFileWatcher.start(outputDir)
       └─ Initial scan → existing .json files
       └─ TraceListPanel.refresh(files)
       └─ BulkFileListener registered

New .json file written (test just finished)
  └─ BulkFileListener fires
  └─ TraceListPanel.refresh(files) → list updates

User clicks a leaf in the trace list
  └─ OtlpJsonParser.parse(file) → List<SpanNode> (tree)
  └─ WaterfallPanel.load(spans) → repaints

User clicks a span bar in the waterfall
  └─ WaterfallPanel fires spanSelected(SpanNode)
  └─ SpanDetailPanel.show(span)

User clicks "→ Go to source"
  └─ SourceNavigator.navigate(project, span.name)
  └─ Editor opens at method

User collapses tool window
  └─ TraceFileWatcher.stop() → BulkFileListener unregistered

User reopens tool window
  └─ TraceFileWatcher.start(outputDir)
  └─ If selected file mtime changed → re-parse → WaterfallPanel.load(spans)
```

---

## Error Handling

| Situation | Behaviour |
|---|---|
| `outputDir` does not exist yet | List shows "No traces yet — run your tests" placeholder |
| Trace file is malformed JSON | File shown in list with ⚠ icon; clicking shows parse error in detail strip |
| Class not found in PSI index | "→ Go to source" shows a balloon: "Source not available for `<name>`" |
| Multiple overloads of the same method | Navigates to the first match; no disambiguation UI (acceptable for v1) |

---

## Testing

- **`OtlpJsonParserTest`** — unit tests against the actual `.json` files from the IT suite.
  Verifies correct tree construction, `durationMs` calculation, and attribute extraction.
- **`PomConfigReaderTest`** — unit tests with sample `pom.xml` strings: configured dir,
  missing config (fallback), and missing plugin entirely (fallback).
- **`SourceNavigatorTest`** — integration test using the IntelliJ test framework
  (`LightJavaCodeInsightFixtureTestCase`) to verify PSI lookup resolves correctly.
- Manual verification: run `./gradlew runIde` in the plugin directory to launch a
  sandboxed IDE instance against the `basic-test` IT project.
