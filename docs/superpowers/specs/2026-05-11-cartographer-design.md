# Cartographer — Design Document
Date: 2026-05-11

## Overview

Cartographer is a JVM agent + Maven plugin that instruments every non-synthetic method
(including constructors) in classes under a configured package with OpenTelemetry span
decorations during test runs. Its primary purpose is to surface the call graph and
relative timing of a test execution, helping identify code paths that are visited more
often than expected (e.g. due to missing caching or memoization).

It is a development/testing aid only — not intended for production use.

---

## Module Structure

```
cartographer/                          ← parent POM (com.antwerkz:cartographer, packaging=pom)
├── cartographer-agent/                ← com.antwerkz:cartographer-agent
│   └── pom.xml                    (jar → shaded fat JAR via maven-shade-plugin)
└── cartographer-maven-plugin/         ← com.antwerkz:cartographer-maven-plugin
    └── pom.xml                    (maven-plugin packaging)
```

The parent POM holds shared properties: Kotlin version, Java 17 target, OTel SDK version,
ByteBuddy version, and Maven Central release config (GPG signing, nexus-staging-maven-plugin).

---

## Agent Module (`cartographer-agent`)

### Entry point

`com.antwerkz.cartographer.agent.CartographerAgent` is declared as `Premain-Class` in the
JAR manifest.

### Startup sequence (`premain`)

1. Parse agent arguments passed from the Maven plugin as `|`-delimited `key=value` pairs
   (e.g. `packages=com.example|outputDir=target/cartographer|captureArgs=true`):
   `packages`, `outputDir`, `endpoint`, `captureArgs`, `maxArgLength`
2. Initialize the OTel SDK with two independent span processors:
   - **FileSpanExporter**, wrapped in a `SimpleSpanProcessor` — writes OTLP JSON to
     `<outputDir>/<test-name>.json` synchronously as spans complete (see "Per-test trace
     isolation" below for why this must not be batched/async)
   - **OtlpHttpExporter**, wrapped in a `BatchSpanProcessor` — exports to the configured
     endpoint (omitted if not set)
3. Install a ByteBuddy `AgentBuilder` that matches all classes under the configured
   package(s) and applies method instrumentation advice.

### Bytecode instrumentation

- **Library:** ByteBuddy with `AgentBuilder` (class-load-time transformation)
- **Scope:** All non-synthetic, non-abstract methods and non-synthetic constructors in
  classes whose package starts with any configured root package, **except** constructors
  of classes that themselves declare a `@Test`-style method (see "Per-test trace
  isolation" below) — those constructors run before the test's root span exists and would
  otherwise generate their own orphaned trace per test invocation
- **Mechanism:** `@Advice.OnMethodEnter` / `@Advice.OnMethodExit` pair
  - Enter: start a child span named `ClassName#methodName`
  - Exit: end the span; if an exception is being thrown, call `span.recordException(e)`
    and set status to `ERROR`

### Per-test trace isolation

Rather than hooking a test framework's internal dispatch machinery, ByteBuddy matches
methods directly annotated with `org.junit.jupiter.api.Test`,
`org.junit.jupiter.params.ParameterizedTest`, or `org.testng.annotations.Test` within the
configured package(s), and applies `TestRootAdvice` to them (in place of the general
method advice used for everything else). Each matched test method receives a fresh root
span (new trace ID, `setNoParent()`) named `TestClass#testMethod`. All instrumented calls
made during that test — on the same thread, or on any thread the OTel `Context` is
explicitly propagated to — become child spans of that root. This requires no changes to
user test code.

Because the annotation matcher only sees `@Test`-style methods, framework lifecycle
callbacks (`@BeforeEach`/`@AfterEach`, extensions) and `@RepeatedTest`/`@TestFactory`/
custom composed annotations are not wrapped with a root span. This is a deliberate
trade-off: JUnit's public annotation API is stable across versions, whereas its internal
engine execution/invocation classes are not.

Test classes' own constructors are excluded from instrumentation entirely (see
"Bytecode instrumentation" above). Test runners instantiate a fresh test instance before
invoking the `@Test` method, i.e. before any root span exists, so an instrumented
constructor would start its own parentless trace on every single test invocation. Since
nothing is known about the upcoming test at construction time, this was observed to
produce one orphaned, unnamed output file per test (see below) — multiplied by every
constructor call reachable during test-instance construction (field initializers, DI
setup, etc.) — rather than useful data. Constructors of non-test classes reachable
*during* an active test (i.e. after the `@Test` method's root span has started) are
unaffected and continue to be instrumented normally.

Test-to-trace naming is tracked explicitly rather than via a `ThreadLocal`:
`TestRootAdvice` registers the root span's OTel trace ID against the test's signature in
`CartographerContext` when the span is created, and clears it once flushed. `FileExporter`
buckets incoming spans by trace ID (not into one shared buffer) and, when a trace's root
span completes, looks up its registered name to pick the output filename before writing
and clearing that trace's spans. This keeps concurrently executing tests (e.g. JUnit 5
parallel class execution) from having their spans interleaved into the same file. Any
span that ends up with no valid parent and no trace registered — e.g. from a background
thread the OTel `Context` was never propagated to — still gets its own isolated output
file, named `cartographer-run-<traceId>`, rather than being merged into a single shared
fallback file.

After each root test span ends, the agent calls `SdkTracerProvider.forceFlush()` before
the next test begins, ensuring that trace's spans are written before the next test's
spans start arriving. One output file is written per test method (or per orphaned trace,
in the fallback case above).

### Argument capture

When `captureArgs=true`, each span gains attributes `arg.0`, `arg.1`, … using
`toString()` on each argument value:
- Values are truncated to `maxArgLength` characters (appending `…` if truncated)
- Null arguments are recorded as the string `"null"`
- If `toString()` throws, the attribute is recorded as `"<error: ClassName>"`
- Parameter names from debug symbols are used when available (added as `param.<name>`
  alongside the index form)

### Shading

All agent dependencies (ByteBuddy, OTel SDK, and transitive deps) are relocated under
`com.antwerkz.cartographer.shaded.*` via `maven-shade-plugin` to prevent version conflicts
with the target application's own classpath.

---

## Maven Plugin Module (`cartographer-maven-plugin`)

### Lifecycle binding

Binds to the `initialize` phase. Locates the `cartographer-agent` JAR from the plugin's
own classpath (bundled as a dependency) and prepends a `-javaagent` argument to the
`${argLine}` property consumed by `maven-surefire-plugin` and `maven-failsafe-plugin`.

This is the same mechanism used by JaCoCo and composes correctly when both are active.

### Configuration parameters

| Parameter        | Default                                    | Required | Description                                          |
|------------------|--------------------------------------------|----------|------------------------------------------------------|
| `packages`       | —                                          | Yes      | Root package(s) to instrument, comma-separated       |
| `outputDirectory`| `${project.build.directory}/cartographer`      | No       | Directory for output trace files                     |
| `endpoint`       | —                                          | No       | OTLP endpoint URL (e.g. `http://localhost:4318`)     |
| `captureArgs`    | `false`                                    | No       | Capture method arguments as span attributes          |
| `maxArgLength`   | `256`                                      | No       | Max chars per argument value before truncation       |
| `skip`           | `false`                                    | No       | Skip all instrumentation                             |

### Generated argLine fragment

```
-javaagent:/path/to/cartographer-agent.jar=packages=com.example,outputDir=/path/to/dir[,endpoint=http://...,captureArgs=true,maxArgLength=256]
```

### Activation

The plugin is added to the user's project under a Maven profile of their choosing.
Cartographer does not ship a fixed profile name.

---

## Data Flow

```
Test JVM startup
  └── premain() fires
        ├── Parse agent args
        ├── Init OTel SDK (SimpleSpanProcessor → FileExporter, BatchSpanProcessor → OtlpHttpExporter?)
        └── Install ByteBuddy AgentBuilder

Test execution
  └── JUnit/TestNG test method begins
        └── ByteBuddy advice → startSpan("TestClass#testMethod", ROOT)
              └── Instrumented app code runs
                    ├── Each method entry → startSpan(child)
                    └── Each method exit  → endSpan (+ exception recording if thrown)
        └── Test method ends → endSpan (root)
              └── FileExporter flushes → target/cartographer/<TestClass#testMethod>.json

JVM shutdown
  └── OTel SDK flush (remaining spans)
        └── OtlpHttpExporter flush → configured endpoint (if set)
```

---

## Error Handling

**Agent startup failures:** Log to stderr, skip instrumentation, let tests run normally.
Cartographer must never fail the build.

**Per-method instrumentation errors:** ByteBuddy advice wraps span operations in
try/finally. If span machinery throws, the exception is suppressed and the original
method behaviour is preserved.

**Exception recording:** When an instrumented method throws, the span records the
exception via `span.recordException(e)`, sets status to `ERROR`, and rethrows unchanged.

**Export failures:** File exporter logs once to stderr on failure and drops remaining
spans. OTLP exporter uses OTel's built-in retry/timeout; failures are logged but do
not affect the test outcome.

**Argument capture failures:** If `toString()` throws, the attribute is recorded as
`"<error: ClassName>"` rather than propagating.

**Guiding principle: Cartographer must never alter the outcome of a test run.**

---

## Testing Strategy

### Unit tests — `cartographer-agent`

- ByteBuddy transformer: verify a sample class's methods get instrumented (spans created,
  args captured, exceptions recorded)
- OTel setup: verify SDK initializes correctly for various config combinations
- Argument capture: truncation, null handling, `toString()` failure fallback

### Unit tests — `cartographer-maven-plugin`

- `argLine` construction for various config combinations
- `skip=true` produces no `argLine` modification
- Uses `maven-plugin-testing-harness`

### Integration tests — `cartographer-maven-plugin/src/it/`

Run via `maven-invoker-plugin` against real Maven sub-projects:

1. **Basic case:** Trace file written, contains expected spans for the instrumented package,
   per-test isolation produces one file per test method
2. **OTLP endpoint:** Export to a mock HTTP server, verify payloads received
3. **Argument capture:** `captureArgs=true` produces `arg.0` attributes on spans

### Out of scope

- OTel SDK internals
- ByteBuddy internals
- Jaeger/Grafana visualization

---

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `io.opentelemetry:opentelemetry-sdk` | OTel SDK core |
| `io.opentelemetry:opentelemetry-exporter-otlp` | OTLP HTTP/gRPC exporter |
| `net.bytebuddy:byte-buddy` | Bytecode instrumentation |
| `net.bytebuddy:byte-buddy-agent` | Agent bootstrap |
| `org.jetbrains.kotlin:kotlin-stdlib` | Kotlin runtime (shaded in agent) |

---

## Distribution

Published to Maven Central under `com.antwerkz`. Requires GPG signing and
`nexus-staging-maven-plugin` (or `central-publishing-maven-plugin`) configured in the
parent POM. The agent JAR is bundled inside the plugin JAR so users only declare one
dependency.
