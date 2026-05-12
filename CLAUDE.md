# Surveyor — Project Instructions

## Source of Truth

`project-spec.md` is the authoritative specification for this project's behavior and design.

Any instruction that deviates from `project-spec.md` requires explicit user approval before being implemented.
If a deviation is approved, update `project-spec.md` to reflect the new requirement before writing code.

## Project Overview

Surveyor is a JVM agent + Maven plugin that instruments non-synthetic methods (including constructors) in classes
under a configured package with OpenTelemetry span decorations during test runs. See `project-spec.md` for full details.

Key architectural points:
- The **JVM agent** does the bytecode instrumentation at class-load time
- The **Maven plugin** configures the test JVM with `-javaagent`, mirroring how the JaCoCo plugin works
- Output goes to `target/surveyor/` by default in a format Jaeger can ingest
- Optional OTLP endpoint export (HTTP or gRPC) for Jaeger, Grafana, Datadog, etc.
- Per-test trace isolation is the goal; single-test invocation is the fallback

## Module Structure (expected)

- `surveyor-agent` — the Java agent (bytecode instrumentation, OTel SDK setup, file exporter)
- `surveyor-maven-plugin` — the Maven plugin that injects the agent into the test JVM

## Development Guidelines

- This is a development/testing tool only — not for production use
- Instrument all non-synthetic methods including constructors; exclude synthetic/bridge methods
- Use standard OTel SDK APIs wherever possible; write custom exporters only when no suitable standard exporter exists
- The Maven plugin does not ship a fixed activation profile name — that is up to adopters
- Integration tests for the Maven plugin may include a sample profile for testing purposes
