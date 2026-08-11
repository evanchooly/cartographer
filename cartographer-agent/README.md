# Cartographer Agent

The JVM agent at the heart of [Cartographer](../README.md). It instruments your code at
class-load time and turns a single test run into a full OpenTelemetry trace — one span per
method call, one root trace per test.

> Usually you don't depend on this module directly. The
> [`cartographer-maven-plugin`](../cartographer-maven-plugin) wires it into your test JVM
> automatically. This module is documented separately for anyone driving the agent by hand
> (Gradle, a custom runner, `JAVA_TOOL_OPTIONS`, etc.).

## What it does

- Attaches via `-javaagent` and uses [ByteBuddy](https://bytebuddy.net/) to instrument every
  non-synthetic method and constructor in the packages you configure
- Starts a fresh root span per JUnit 5 / TestNG test method, so every test gets its own
  isolated trace — no changes to your test code required
- Every instrumented call becomes a child span named `ClassName#methodName`
- Exceptions are recorded on the span (`span.recordException`) and the span status is set to
  `ERROR`, without altering the exception that propagates out of your code
- Writes one OTLP JSON trace file per test to an output directory, and optionally streams
  live to any OTLP HTTP endpoint (Jaeger, Grafana Tempo, Datadog, etc.)

**Guiding principle:** Cartographer never changes the outcome of your test run. If the agent
fails to start, or a span operation throws, instrumentation is skipped and your tests run
exactly as they would without it.

## Manual usage

```bash
java -javaagent:cartographer-agent.jar=packages=com.example,outputDir=target/cartographer \
     -jar your-app-tests.jar
```

### Agent arguments

Arguments are passed as a `key=value` string, pipe-separated, appended after `=` on the
`-javaagent` flag:

| Key             | Default              | Description                                              |
|-----------------|----------------------|------------------------------------------------------------|
| `packages`      | *(required)*         | Comma-separated root package(s) to instrument             |
| `outputDir`     | `target/cartographer`| Directory for per-test OTLP JSON trace files               |
| `endpoint`      | *(none)*             | OTLP HTTP endpoint to stream spans to live                 |
| `captureArgs`   | `false`              | Record method arguments as span attributes (`arg.0`, …)    |
| `maxArgLength`  | `256`                | Max characters per captured argument before truncation     |

Example:

```
-javaagent:cartographer-agent.jar=packages=com.example,com.example.internal|outputDir=/tmp/traces|endpoint=http://localhost:4318|captureArgs=true|maxArgLength=128
```

## Argument capture

When `captureArgs=true`, each span gets `arg.0`, `arg.1`, … attributes from `toString()` on
each parameter:

- Values longer than `maxArgLength` are truncated with a trailing `…`
- `null` arguments are recorded as the literal string `"null"`
- If `toString()` throws, the attribute is recorded as `"<error: ClassName>"` instead of
  propagating
- When debug symbols are present, parameter names are also recorded as `param.<name>`

## Output format

Trace files are standard OTLP JSON — one file per test method, e.g.
`com.example.MyTest.testFoo.json` — ready to import into any OTLP-compatible viewer, or open
directly with the [Cartographer IntelliJ plugin](../cartographer-intellij-plugin).

## Development use only

This agent instruments every method call and is not tuned for production overhead. It's
built for local test runs and CI, not for services under real load.
