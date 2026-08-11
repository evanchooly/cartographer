# Cartographer IntelliJ Plugin

An IntelliJ IDEA tool window for browsing [Cartographer](../README.md) trace output without
leaving the IDE — no Jaeger, no Docker, just click a test and see its call graph.

## What it does

- Adds a **Cartographer** tool window (anchored to the bottom, next to Run/Debug/TODO) that
  watches your project's trace output directory and updates live as tests run
- Lists recorded traces grouped by test class, with the root span's duration shown inline
  (e.g. `testAdd  15ms`)
- Renders the selected trace as a **Jaeger-style waterfall**: one row per span, indented by
  call depth, sized and colored using your editor's font metrics and theme so it looks native
  in both light and dark IDEs
- **Scroll to zoom**: hold <kbd>Ctrl</kbd> and scroll over the waterfall to zoom the timeline
  in on long or deeply nested traces
- Click a span to see its duration and captured argument attributes in a detail strip
- **Double-click a span** (or use the "Go to source" button) to jump straight to that
  method's definition in the editor
- One-click **"Add Cartographer Profile"** action (project view / editor right-click) that
  inserts the Maven profile for you — the IDE-side equivalent of
  `mvn com.antwerkz:cartographer-maven-plugin:setup`

## Requirements

- IntelliJ IDEA 2025.3+ (Community or Ultimate)
- A project already producing Cartographer trace output — see the
  [Maven plugin](../cartographer-maven-plugin) for how to enable it

## Getting started

1. Install the plugin (from the JetBrains Marketplace once published, or via
   **Settings → Plugins → Install from Disk** using a locally built distribution zip)
2. Right-click your `pom.xml` and choose **Add Cartographer Profile** if you haven't set up
   Cartographer yet, or configure it manually per the Maven plugin's README
3. Run `mvn test -Dcartographer` (or your project's equivalent)
4. Open the **Cartographer** tool window at the bottom of the IDE — traces appear as they're
   written, grouped by test class
5. Click a trace to see its waterfall; click or double-click a span to inspect it or jump to
   its source

## How it finds your traces

The plugin reads your `pom.xml` looking for the `cartographer-maven-plugin`'s `outputDir`
configuration and watches that directory. If none is configured, it falls back to the
default, `target/cartographer/`.

## Building from source

This module is a Gradle subproject (IntelliJ Platform Gradle Plugin), independent of the
Maven build used by the agent and Maven plugin:

```bash
cd cartographer-intellij-plugin
./gradlew build       # compile + test
./gradlew runIde       # launch a sandbox IDE with the plugin installed
./gradlew buildPlugin  # produce a distributable plugin zip
```

## Development use only

Like the rest of Cartographer, this plugin is a local development aid for understanding test
execution — it has no bearing on your production build or runtime.
