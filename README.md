# Cartographer

Cartographer is a JVM agent + Maven plugin that instruments every non-synthetic method (including constructors) in classes under a configured package with OpenTelemetry span decorations during test runs. Each JUnit/TestNG test method produces its own trace, letting you visualize the full call graph and relative timing of every method call in a tool like Jaeger.

**Primary use case:** identify code paths executed during a test run to find areas being revisited unnecessarily — cache misses, redundant computation, unexpected re-initialization, etc.

> This tool is intended for development and testing only, not production use.

## How it works

The Maven plugin injects the Cartographer JVM agent via `-javaagent` into the test JVM (the same mechanism JaCoCo uses). The agent instruments all matching classes at class-load time using Byte Buddy and records OTel spans. Trace output is written per-test to `target/cartographer/` and, optionally, exported live to any OTLP-compatible backend (Jaeger, Grafana, Datadog, etc.).

## Quick start

### 1. Add the plugin to your `pom.xml`

```xml
<build>
  <plugins>
    <plugin>
      <groupId>com.antwerkz</groupId>
      <artifactId>cartographer-maven-plugin</artifactId>
      <version>1.0-SNAPSHOT</version>
      <configuration>
        <packages>
          <package>com.example</package>  <!-- root package(s) to instrument -->
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
      <version>3.5.3</version>
      <configuration>
        <argLine>${argLine}</argLine>  <!-- required: lets Cartographer inject the agent -->
      </configuration>
    </plugin>
  </plugins>
</build>
```

### 2. Run your tests

```bash
mvn test
```

Trace files are written to `target/cartographer/` — one JSON file per test method (e.g. `com.example.MyTest.testFoo.json`).

### 3. Visualize in Jaeger (optional)

Start Jaeger locally:

```bash
docker compose up -d   # uses the docker-compose.yml at the repo root
```

Then add `endpoint` to the plugin configuration to stream traces live:

```xml
<configuration>
  <packages>
    <package>com.example</package>
  </packages>
  <endpoint>http://localhost:4318</endpoint>
</configuration>
```

Open the Jaeger UI at `http://localhost:16686`.

## Configuration reference

| Parameter | Default | Description |
|-----------|---------|-------------|
| `packages` | *(required)* | Root package(s) to instrument. All classes whose name starts with any listed package are included. |
| `outputDir` | `${project.build.directory}/cartographer` | Directory where per-test trace JSON files are written. |
| `endpoint` | *(none)* | OTLP HTTP endpoint for live export, e.g. `http://localhost:4318`. |
| `captureArgs` | `false` | When `true`, records method argument values as span attributes (`arg.0`, `arg.1`, …). |
| `maxArgLength` | `256` | Maximum length of a captured argument string before truncation. |

## Building from source

```bash
mvn install
```

Requires Java 17+ and Maven 4.
