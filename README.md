# Cartographer

Cartographer is a JVM agent + Maven plugin that instruments every non-synthetic method (including constructors) in classes under a configured package with OpenTelemetry span decorations during test runs. Each JUnit/TestNG test method produces its own trace, letting you visualize the full call graph and relative timing of every method call in a tool like Jaeger.

**Primary use case:** identify code paths executed during a test run to find areas being revisited unnecessarily — cache misses, redundant computation, unexpected re-initialization, etc.

> This tool is intended for development and testing only, not production use.

## How it works

The Maven plugin injects the Cartographer JVM agent via `-javaagent` into the test JVM (the same mechanism JaCoCo uses). The agent instruments all matching classes at class-load time using Byte Buddy and records OTel spans. Trace output is written per-test to `target/cartographer/` and, optionally, exported live to any OTLP-compatible backend (Jaeger, Grafana, Datadog, etc.).

## Modules

| Module | Description |
|---|---|
| [`cartographer-agent`](cartographer-agent) | The JVM agent that does the instrumentation and trace export. |
| [`cartographer-maven-plugin`](cartographer-maven-plugin) | Maven plugin that wires the agent into your test JVM; includes an automatic `setup` goal. |
| [`cartographer-intellij-plugin`](cartographer-intellij-plugin) | IntelliJ IDEA tool window for browsing trace output as an in-IDE waterfall view. |

## Quick start

### Option A — Automatic setup (recommended)

Run the `setup` goal directly against your project. It adds a `cartographer` Maven profile to your `pom.xml` automatically:

```bash
mvn com.antwerkz:cartographer-maven-plugin:setup
```

Then enable the profile when running tests:

```bash
mvn test -Dcartographer
```

See [Automatic setup](#automatic-setup-goal) for full details and options.

---

### Option B — Manual setup

#### 1. Add a profile to your `pom.xml`

Wrap the plugin in a profile so you can enable it on demand without slowing down every build:

```xml
<profiles>
  <profile>
    <id>cartographer</id>
    <activation>
      <property><name>cartographer</name></property>
    </activation>
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
          <configuration>
            <argLine>@{argLine}</argLine>  <!-- @{} is required for late binding -->
          </configuration>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

#### 2. Run your tests

```bash
mvn test -Dcartographer
```

Trace files are written to `target/cartographer/` — one JSON file per test method (e.g. `com.example.MyTest.testFoo.json`).

#### 3. Visualize in Jaeger (optional)

Start Jaeger locally:

```bash
docker run -d --name jaeger \
  -p 4317:4317 -p 4318:4318 -p 16686:16686 \
  jaegertracing/jaeger:latest
```

Open the UI at http://localhost:16686. Then add `endpoint` to the plugin configuration to stream traces live:

```xml
<configuration>
  <packages>
    <package>com.example</package>
  </packages>
  <endpoint>http://localhost:4318</endpoint>
</configuration>
```

Open the Jaeger UI at `http://localhost:16686`.

---

## Automatic setup goal

The `setup` goal adds a ready-to-use Cartographer profile to your `pom.xml` so you don't have to write the XML by hand.

### Basic usage

```bash
mvn com.antwerkz:cartographer-maven-plugin:setup
```

The goal:
1. Parses your `pom.xml` to detect the packaging type and root source package.
2. Inserts a `cartographer` profile with the `instrument` goal and a surefire `argLine` wired up for late binding.
3. Warns you to review the inserted values — auto-detection may not be 100% accurate.

The profile is activated by setting the `cartographer` system property, so normal builds are unaffected.

### Package auto-detection

If you don't supply `packages`, the goal walks `src/main/{java or kotlin}`and descends while there is exactly one subdirectory at each level. The deepest such path becomes the default package. For example, a tree with `src/main/kotlin/com/example/myapp/` containing `service/` and `model/` produces `com.example.myapp`.

If the packaging is `pom`, or no source tree is found, a comment placeholder is inserted instead — edit it before running the profile.

### Setup goal parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `packages` | *(auto-detected)* | Comma-separated root package(s) to instrument. Auto-detected from the source tree when omitted. |
| `pomFile` | `${basedir}/pom.xml` | Path to the `pom.xml` to modify. |
| `profileId` | `cartographer` | ID of the profile to insert (also used as the activation property name). |
| `endpoint` | *(none)* | OTLP HTTP endpoint to include in the generated profile, e.g. `http://localhost:4318`. |
| `captureArgs` | `false` | When `true`, adds `<captureArgs>true</captureArgs>` to the generated configuration. |
| `maxArgLength` | `256` | Included in generated configuration only when non-default. |

The goal is idempotent — re-running it when the profile already exists prints a warning and exits without modifying the file.

### Examples

```bash
# Specify packages explicitly
mvn com.antwerkz:cartographer-maven-plugin:setup -Dcartographer.packages=com.example.myapp

# Use a custom profile ID and include a Jaeger endpoint
mvn com.antwerkz:cartographer-maven-plugin:setup \
  -Dcartographer.profileId=tracing \
  -Dcartographer.endpoint=http://localhost:4318

# Point at a pom in a different directory
mvn com.antwerkz:cartographer-maven-plugin:setup -Dcartographer.pomFile=/path/to/other/pom.xml
```

## Configuration reference (`instrument` goal)

| Parameter | Default | Description |
|-----------|---------|-------------|
| `packages` | *(required)* | Root package(s) to instrument. All classes whose name starts with any listed package are included. |
| `outputDir` | `${project.build.directory}/cartographer` | Directory where per-test trace JSON files are written. |
| `endpoint` | *(none)* | OTLP HTTP endpoint for live export, e.g. `http://localhost:4318`. |
| `captureArgs` | `false` | When `true`, records method argument values as span attributes (`arg.0`, `arg.1`, …). |
| `maxArgLength` | `256` | Maximum length of a captured argument string before truncation. |
| `skip` | `false` | When `true` (or `-Dcartographer.skip`), skips instrumentation entirely without error. |

## Building from source

```bash
mvn install
```

Requires Java 17+ and Maven 4.
