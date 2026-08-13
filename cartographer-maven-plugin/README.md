# Cartographer Maven Plugin

Wires the [Cartographer agent](../cartographer-agent) into your test JVM — the same
`-javaagent` mechanism JaCoCo uses — so `mvn test` produces a full call-graph trace for
every test method, with zero changes to your test code.

Part of [Cartographer](../README.md), a JVM agent + Maven plugin combo for visualizing test
execution as OpenTelemetry traces.

## Quick start

### Automatic setup (recommended)

Run the `setup` goal directly against your project — no need to add it to your `pom.xml`
first:

```bash
mvn com.antwerkz:cartographer-maven-plugin:setup
```

This detects your project's root package from `src/main/java` or `src/main/kotlin` and adds
a `cartographer` profile to your `pom.xml`. Then run your tests with it enabled:

```bash
mvn test -Dcartographer
```

`setup` options (all optional, pass as `-Dcartographer.<name>=<value>`):

| Property                       | Default        | Description                                             |
|---------------------------------|----------------|-----------------------------------------------------------|
| `cartographer.packages`         | auto-detected  | Comma-separated root package(s) to instrument            |
| `cartographer.pomFile`          | `./pom.xml`    | Which `pom.xml` to modify                                 |
| `cartographer.profileId`        | `cartographer` | Name of the generated Maven profile                       |
| `cartographer.endpoint`         | *(none)*       | OTLP endpoint to add to the generated configuration        |
| `cartographer.captureArgs`      | `false`        | Enable argument capture in the generated configuration     |
| `cartographer.maxArgLength`     | `256`          | Max captured argument length in the generated configuration|

If a profile with the same ID already exists, `setup` leaves your `pom.xml` untouched and
warns instead of overwriting it. Always review the generated profile — auto-detected
packages may need adjustment.

### Manual setup

Add the plugin under a profile so it only runs when you ask for it:

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
          <version>1.0.0-SNAPSHOT</version>
          <configuration>
            <packages>
              <package>com.example</package>
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
            <argLine>@{argLine}</argLine> <!-- @{} required for late binding -->
          </configuration>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

```bash
mvn test -Dcartographer
```

Cartographer does not ship a fixed profile name or auto-activation — you choose when it
runs.

## `instrument` goal configuration

Bound to the `initialize` phase; prepends a `-javaagent` argument to the `argLine` property
consumed by `maven-surefire-plugin` (and `maven-failsafe-plugin`, if present).

| Parameter         | Default                                     | Required | Description                                       |
|--------------------|----------------------------------------------|----------|-----------------------------------------------------|
| `packages`         | —                                              | Yes      | Root package(s) to instrument                       |
| `outputDirectory`  | `${project.build.directory}/cartographer`      | No       | Directory for per-test trace files                  |
| `endpoint`         | —                                              | No       | OTLP HTTP endpoint (e.g. `http://localhost:4318`)    |
| `captureArgs`      | `false`                                        | No       | Capture method arguments as span attributes          |
| `maxArgLength`     | `256`                                          | No       | Max characters per argument before truncation        |
| `skip`             | `false`                                        | No       | Skip instrumentation entirely                        |

## Output

One OTLP JSON trace file per test method lands in `target/cartographer/`, e.g.
`com.example.MyTest.testFoo.json`. Point an OTLP endpoint at Jaeger, Grafana Tempo, or
Datadog to stream traces live instead of (or alongside) the file output:

```bash
docker run -d --name jaeger \
  -p 4317:4317 -p 4318:4318 -p 16686:16686 \
  jaegertracing/jaeger:latest
```

```xml
<configuration>
  <packages>
    <package>com.example</package>
  </packages>
  <endpoint>http://localhost:4318</endpoint>
</configuration>
```

Or skip external tooling and inspect trace files directly with the
[Cartographer IntelliJ plugin](../cartographer-intellij-plugin), which renders them as an
interactive waterfall right inside the IDE.

## Composing with JaCoCo

Because Cartographer injects into `argLine` the same way JaCoCo does, both plugins can be
active in the same test run — just make sure both are configured to append rather than
overwrite `argLine`.

## Development use only

Cartographer instruments every method in the configured packages; it's built for local test
runs and CI investigation, not production workloads.
