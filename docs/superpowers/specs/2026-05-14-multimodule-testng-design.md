# Cartographer — Multimodule Plugin Support & TestNG IT — Design Document

Date: 2026-05-14

## Overview

Two related enhancements:

1. **Multimodule plugin support** — The IntelliJ plugin's file list gains top-level collapsible
   sections per Maven submodule. The watcher discovers and monitors each submodule's
   `target/cartographer/` directory. Works transparently for single-module projects (no UI change).

2. **TestNG integration test** — A new Maven IT project (`testng-test`) exercises the agent's
   existing `@org.testng.annotations.Test` annotation matching end-to-end, verifying that
   per-test trace files are generated for TestNG-based test suites.

---

## Part 1: Multimodule Plugin Support

### `PomConfigReader` — new method `readModules`

Reads the parent `pom.xml` and returns a list of `(folderName: String?, outputDir: File)` pairs.

**Dependency:** `org.apache.maven:maven-model` added to `build.gradle.kts` of the IntelliJ
plugin. Uses `MavenXpp3Reader` to parse the POM into a typed `Model` object — cleaner than
raw DOM traversal and handles encoding edge cases automatically.

Algorithm:

1. Parse `pom.xml` with `MavenXpp3Reader().read(FileReader(pom))` → `Model`.
2. Try `model.subprojects` — use these entries if non-empty (Maven 4 format).
3. Else try `model.modules` — use these entries (Maven 3 format).
4. For each entry `folderName`:
   - Resolve `outputDir = File(projectRoot, "$folderName/target/cartographer")`.
   - Pair: `folderName to outputDir`.
5. If both are empty (single-module project):
   - Return `listOf(null to readOutputDir(projectRoot))` — the `null` key signals no module
     wrapper in the UI.

The existing `readOutputDir` is unchanged and continues to serve the single-module path and
any other callers that need just one directory. It may optionally be refactored internally to
also use `MavenXpp3Reader` for consistency, but its signature and behaviour are unaffected.

### `TraceFileWatcher` — multi-directory

Constructor signature changes from `(project, outputDir, onChange)` to
`(project, modules, onChange)` where `modules: List<Pair<String?, File>>` and
`onChange: (Map<String?, List<File>>) -> Unit`.

- A single `BulkFileListener` is registered for all watched directories.
- `notifyFiles()` builds and emits `Map<String?, List<File>>` — keyed by the module folder
  name (or `null` for single-module). Each value is the sorted `.json` file list for that dir.
- `start()` / `stop()` lifecycle is unchanged.
- Canonical path resolution is done once per directory at construction time.

### `TraceListPanel` — module-level grouping

`refresh(files: List<File>)` becomes `refresh(modules: Map<String?, List<File>>)`.

Tree structure:

```
[root — invisible]
 ├── module-a           ← String node, shown only when key != null; collapsible
 │   ├── CalculatorTest ← class group node (existing behaviour)
 │   │   ├── testAdd  15ms
 │   │   └── testMultiply  8ms
 │   └── …
 ├── module-b
 │   └── …
 └── cartographer-run       ← TraceLeaf, dimmed/italic; shown at bottom of each module
                          (or at root level for single-module)
```

- If `modules` has only a single `null` key, no module wrapper node is added — tree renders
  exactly as before.
- Module nodes are sorted alphabetically by folder name. They are not selectable.
- `cartographer-run.json`, if present, appears as a leaf at the bottom of its module section.
- All existing class/method grouping and leaf rendering logic is unchanged.

### `CartographerToolWindowFactory` — wiring update

Calls `PomConfigReader.readModules(projectRoot)` instead of `readOutputDir`.
Passes the result to `TraceFileWatcher`. Forwards `Map<String?, List<File>>` from the
watcher to `TraceListPanel.refresh`.

---

## Part 2: TestNG Integration Test

### Location

`cartographer-maven-plugin/src/it/testng-test/`

Mirrors the existing `basic-test` IT project structure.

### `pom.xml` (test project)

- Parent: `com.antwerkz:cartographer-maven-plugin` (standard IT parent).
- Dependency: `org.testng:testng:7.10.2` (test scope).
- `maven-surefire-plugin` configured to use TestNG (no JUnit provider).
- Cartographer plugin configured with `packages=com.example`, `captureArgs=true`.

### Test class

`src/test/java/com/example/CalculatorTest.java`

```java
package com.example;

import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

public class CalculatorTest {
    private final Calculator calc = new Calculator();

    @Test public void testAdd()      { assertEquals(calc.add(2, 3), 5); }
    @Test public void testMultiply() { assertEquals(calc.multiply(3, 4), 12); }
}
```

Plus a minimal `Calculator.java` in `src/main/java/com/example/`.

### Verification (`invoker.properties` / `verify.groovy`)

Checks that `target/cartographer/` contains at least two `.json` files after the build,
confirming per-test trace isolation works with TestNG's `@Test` annotation.

---

## Error Handling

| Situation | Behaviour |
|---|---|
| Module dir does not exist | `TraceFileWatcher` emits empty list for that module; panel hides the section or shows placeholder |
| `<modules>` / `<subprojects>` element missing | Single-module path: `null` key, existing behaviour |
| Both `<subprojects>` and `<modules>` present | `<subprojects>` takes precedence (Maven 4 wins) |
| TestNG IT: no trace files produced | Maven invoker marks build as failed |

---

## Testing

- **`PomConfigReaderTest`** — add cases for: Maven 4 `<subprojects>`, Maven 3 `<modules>`,
  both present (subprojects wins), neither present (single-module fallback).
- **`TraceListPanel`** — existing rendering verified via `./gradlew runIde` manual check;
  module grouping confirmed by running against a real multimodule project.
- **IT: `testng-test`** — `mvn verify` in `cartographer-maven-plugin/` runs the new IT project;
  `verify.groovy` asserts two trace files exist.
