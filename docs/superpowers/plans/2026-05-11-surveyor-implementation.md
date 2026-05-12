# Surveyor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a JVM agent + Maven plugin that instruments every non-synthetic method under a configured package with OpenTelemetry spans, producing per-test trace files compatible with Jaeger.

**Architecture:** A shaded fat-JAR agent (`surveyor-agent`) does bytecode instrumentation via ByteBuddy at class-load time, with OTel SDK span collection; the agent adds itself to the system classloader search so injected advice bytecode can reference agent classes at runtime. A Maven plugin (`surveyor-maven-plugin`) injects the agent via `-javaagent` into Surefire/Failsafe's `argLine`, mirroring JaCoCo's approach.

**Tech Stack:** Kotlin 2.3.20, Java 17, ByteBuddy 1.14.18, OpenTelemetry Java SDK 1.40.0, Maven Plugin API 3.9.x, maven-shade-plugin, maven-invoker-plugin

---

## File Map

```
pom.xml                                                       ← convert to parent POM

surveyor-agent/
  pom.xml                                                     ← module POM, shade config, manifest
  src/main/kotlin/com/antwerkz/surveyor/agent/
    AgentConfig.kt                                            ← parse agent arg string
    SurveyorContext.kt                                        ← shared volatile state (tracer, test name, flags)
    FileSpanExporter.kt                                       ← SpanExporter → OTLP JSON files
    OtelSetup.kt                                              ← init OTel SDK
    MethodAdvice.kt                                           ← ByteBuddy @Advice for general methods
    TestRootAdvice.kt                                         ← ByteBuddy @Advice for @Test methods (root spans)
    SurveyorTransformer.kt                                    ← ByteBuddy AgentBuilder wiring
    SurveyorAgent.kt                                          ← premain() entry point
  src/test/kotlin/com/antwerkz/surveyor/agent/
    AgentConfigTest.kt
    FileSpanExporterTest.kt
    SurveyorTransformerTest.kt

surveyor-maven-plugin/
  pom.xml                                                     ← module POM, maven-plugin packaging
  src/main/kotlin/com/antwerkz/surveyor/maven/
    SurveyorMojo.kt                                           ← argLine injection
  src/test/kotlin/com/antwerkz/surveyor/maven/
    SurveyorMojoTest.kt
  src/it/
    basic/                                                    ← IT: spans written, per-test isolation
      pom.xml
      invoker.properties
      src/main/java/com/example/Calculator.java
      src/test/java/com/example/CalculatorTest.java
      verify.groovy
    with-capture-args/                                        ← IT: captureArgs=true produces arg.N attributes
      pom.xml
      invoker.properties
      src/main/java/com/example/Calculator.java
      src/test/java/com/example/CalculatorTest.java
      verify.groovy
```

---

## Task 1: Convert root pom.xml to parent POM

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Replace pom.xml contents**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.antwerkz</groupId>
    <artifactId>surveyor</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>surveyor-agent</module>
        <module>surveyor-maven-plugin</module>
    </modules>

    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <kotlin.code.style>official</kotlin.code.style>
        <kotlin.compiler.jvmTarget>17</kotlin.compiler.jvmTarget>
        <kotlin.version>2.3.20</kotlin.version>
        <bytebuddy.version>1.14.18</bytebuddy.version>
        <opentelemetry.version>1.40.0</opentelemetry.version>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
    </properties>

    <repositories>
        <repository>
            <id>mavenCentral</id>
            <url>https://repo1.maven.org/maven2/</url>
        </repository>
    </repositories>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.opentelemetry</groupId>
                <artifactId>opentelemetry-bom</artifactId>
                <version>${opentelemetry.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>net.bytebuddy</groupId>
                <artifactId>byte-buddy</artifactId>
                <version>${bytebuddy.version}</version>
            </dependency>
            <dependency>
                <groupId>net.bytebuddy</groupId>
                <artifactId>byte-buddy-agent</artifactId>
                <version>${bytebuddy.version}</version>
            </dependency>
            <dependency>
                <groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-stdlib</artifactId>
                <version>${kotlin.version}</version>
            </dependency>
            <dependency>
                <groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-test-junit5</artifactId>
                <version>${kotlin.version}</version>
                <scope>test</scope>
            </dependency>
            <dependency>
                <groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter</artifactId>
                <version>5.10.0</version>
                <scope>test</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

- [ ] **Step 2: Verify it parses**

```bash
mvn validate
```

Expected: `BUILD SUCCESS` (no module sources yet — that's fine)

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "Convert root pom to parent POM with shared dependency management"
```

---

## Task 2: Create surveyor-agent module pom.xml

**Files:**
- Create: `surveyor-agent/pom.xml`

- [ ] **Step 1: Create the module directory and pom**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.antwerkz</groupId>
        <artifactId>surveyor</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>surveyor-agent</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-stdlib</artifactId>
        </dependency>
        <dependency>
            <groupId>net.bytebuddy</groupId>
            <artifactId>byte-buddy</artifactId>
        </dependency>
        <dependency>
            <groupId>net.bytebuddy</groupId>
            <artifactId>byte-buddy-agent</artifactId>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-sdk</artifactId>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-exporter-otlp</artifactId>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-exporter-logging-otlp</artifactId>
        </dependency>
        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-test-junit5</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <sourceDirectory>src/main/kotlin</sourceDirectory>
        <testSourceDirectory>src/test/kotlin</testSourceDirectory>
        <plugins>
            <plugin>
                <groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-maven-plugin</artifactId>
                <version>${kotlin.version}</version>
                <extensions>true</extensions>
                <executions>
                    <execution>
                        <id>compile</id>
                        <phase>compile</phase>
                        <goals><goal>compile</goal></goals>
                    </execution>
                    <execution>
                        <id>test-compile</id>
                        <phase>test-compile</phase>
                        <goals><goal>test-compile</goal></goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.3.0</version>
                <configuration>
                    <archive>
                        <manifestEntries>
                            <Premain-Class>com.antwerkz.surveyor.agent.SurveyorAgent</Premain-Class>
                            <Can-Redefine-Classes>true</Can-Redefine-Classes>
                            <Can-Retransform-Classes>true</Can-Retransform-Classes>
                        </manifestEntries>
                    </archive>
                </configuration>
            </plugin>
            <plugin>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.5.3</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <createDependencyReducedPom>false</createDependencyReducedPom>
                            <relocations>
                                <relocation>
                                    <pattern>net.bytebuddy</pattern>
                                    <shadedPattern>com.antwerkz.surveyor.shaded.net.bytebuddy</shadedPattern>
                                </relocation>
                                <relocation>
                                    <pattern>io.opentelemetry</pattern>
                                    <shadedPattern>com.antwerkz.surveyor.shaded.io.opentelemetry</shadedPattern>
                                </relocation>
                                <relocation>
                                    <pattern>io.grpc</pattern>
                                    <shadedPattern>com.antwerkz.surveyor.shaded.io.grpc</shadedPattern>
                                </relocation>
                                <relocation>
                                    <pattern>com.google.protobuf</pattern>
                                    <shadedPattern>com.antwerkz.surveyor.shaded.com.google.protobuf</shadedPattern>
                                </relocation>
                            </relocations>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create source directory structure**

```bash
mkdir -p surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent
mkdir -p surveyor-agent/src/test/kotlin/com/antwerkz/surveyor/agent
```

- [ ] **Step 3: Verify module is recognized**

```bash
mvn validate
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add surveyor-agent/
git commit -m "Add surveyor-agent module skeleton with shade and manifest config"
```

---

## Task 3: Implement AgentConfig

**Files:**
- Create: `surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/AgentConfig.kt`
- Create: `surveyor-agent/src/test/kotlin/com/antwerkz/surveyor/agent/AgentConfigTest.kt`

Agent args format: pipe-separated key=value pairs, e.g.:
`packages=com.example|outputDir=/tmp/out|endpoint=http://localhost:4318|captureArgs=true|maxArgLength=128`

Pipe (`|`) is used as the pair separator to avoid clashing with commas in package lists and colons in URLs.

- [ ] **Step 1: Write failing tests**

`surveyor-agent/src/test/kotlin/com/antwerkz/surveyor/agent/AgentConfigTest.kt`:

```kotlin
package com.antwerkz.surveyor.agent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class AgentConfigTest {

    @Test
    fun `parses required packages`() {
        val cfg = AgentConfig.parse("packages=com.example")
        assertEquals(listOf("com.example"), cfg.packages)
    }

    @Test
    fun `parses multiple packages`() {
        val cfg = AgentConfig.parse("packages=com.example,com.other")
        assertEquals(listOf("com.example", "com.other"), cfg.packages)
    }

    @Test
    fun `defaults outputDir to target slash surveyor`() {
        val cfg = AgentConfig.parse("packages=com.example")
        assertEquals(File("target/surveyor"), cfg.outputDir)
    }

    @Test
    fun `parses custom outputDir`() {
        val cfg = AgentConfig.parse("packages=com.example|outputDir=/tmp/traces")
        assertEquals(File("/tmp/traces"), cfg.outputDir)
    }

    @Test
    fun `endpoint is null when absent`() {
        val cfg = AgentConfig.parse("packages=com.example")
        assertNull(cfg.endpoint)
    }

    @Test
    fun `parses endpoint`() {
        val cfg = AgentConfig.parse("packages=com.example|endpoint=http://localhost:4318")
        assertEquals("http://localhost:4318", cfg.endpoint)
    }

    @Test
    fun `captureArgs defaults to false`() {
        val cfg = AgentConfig.parse("packages=com.example")
        assertFalse(cfg.captureArgs)
    }

    @Test
    fun `parses captureArgs true`() {
        val cfg = AgentConfig.parse("packages=com.example|captureArgs=true")
        assertTrue(cfg.captureArgs)
    }

    @Test
    fun `maxArgLength defaults to 256`() {
        val cfg = AgentConfig.parse("packages=com.example")
        assertEquals(256, cfg.maxArgLength)
    }

    @Test
    fun `parses maxArgLength`() {
        val cfg = AgentConfig.parse("packages=com.example|maxArgLength=64")
        assertEquals(64, cfg.maxArgLength)
    }

    @Test
    fun `throws when packages missing`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentConfig.parse("outputDir=/tmp")
        }
    }

    @Test
    fun `handles null agentArgs`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentConfig.parse(null)
        }
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd surveyor-agent && mvn test -Dtest=AgentConfigTest 2>&1 | tail -20
```

Expected: compilation error (AgentConfig not found)

- [ ] **Step 3: Implement AgentConfig**

`surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/AgentConfig.kt`:

```kotlin
package com.antwerkz.surveyor.agent

import java.io.File

data class AgentConfig(
    val packages: List<String>,
    val outputDir: File,
    val endpoint: String?,
    val captureArgs: Boolean,
    val maxArgLength: Int
) {
    companion object {
        fun parse(agentArgs: String?): AgentConfig {
            val params = agentArgs
                ?.split("|")
                ?.filter { it.contains("=") }
                ?.associate { part ->
                    val eq = part.indexOf('=')
                    part.substring(0, eq) to part.substring(eq + 1)
                }
                ?: throw IllegalArgumentException("packages parameter is required")

            val packages = params["packages"]
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("packages parameter is required")

            return AgentConfig(
                packages = packages,
                outputDir = File(params["outputDir"] ?: "target/surveyor"),
                endpoint = params["endpoint"],
                captureArgs = params["captureArgs"] == "true",
                maxArgLength = params["maxArgLength"]?.toIntOrNull() ?: 256
            )
        }
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
mvn test -Dtest=AgentConfigTest
```

Expected: `Tests run: 12, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add surveyor-agent/src/
git commit -m "Implement AgentConfig with full parsing and tests"
```

---

## Task 4: Implement SurveyorContext

**Files:**
- Create: `surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/SurveyorContext.kt`

No separate tests — this is shared mutable state wired together in later tasks. Correctness is validated by integration tests.

- [ ] **Step 1: Create SurveyorContext**

`surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/SurveyorContext.kt`:

```kotlin
package com.antwerkz.surveyor.agent

import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.trace.SdkTracerProvider
import java.util.concurrent.TimeUnit

object SurveyorContext {
    @Volatile var tracer: Tracer? = null
    @Volatile var tracerProvider: SdkTracerProvider? = null
    @Volatile var captureArgs: Boolean = false
    @Volatile var maxArgLength: Int = 256

    private val testName = ThreadLocal<String?>()

    var currentTestName: String?
        get() = testName.get()
        set(value) { testName.set(value) }

    fun forceFlush() {
        tracerProvider?.forceFlush()?.join(5, TimeUnit.SECONDS)
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
mvn compile -pl surveyor-agent
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/SurveyorContext.kt
git commit -m "Add SurveyorContext shared agent state"
```

---

## Task 5: Implement FileSpanExporter

**Files:**
- Create: `surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/FileSpanExporter.kt`
- Create: `surveyor-agent/src/test/kotlin/com/antwerkz/surveyor/agent/FileSpanExporterTest.kt`

`FileSpanExporter` buffers `SpanData` objects and on `flush()` writes them as OTLP JSON via `OtlpStdoutSpanExporter` (pointed at a `ByteArrayOutputStream`) to a file named after the current test.

- [ ] **Step 1: Write failing tests**

`surveyor-agent/src/test/kotlin/com/antwerkz/surveyor/agent/FileSpanExporterTest.kt`:

```kotlin
package com.antwerkz.surveyor.agent

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import io.opentelemetry.sdk.trace.data.SpanData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileSpanExporterTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `flush writes json file named after test`() {
        val exporter = FileSpanExporter(tempDir) { "com.example.FooTest.myTest" }
        exporter.export(createFakeSpans("span-one"))
        exporter.flush()

        val files = tempDir.listFiles() ?: emptyArray()
        assertEquals(1, files.size, "Expected exactly one file")
        assertTrue(files[0].name.contains("com.example.FooTest"), "File name should contain test class")
        val content = files[0].readText()
        assertTrue(content.isNotBlank(), "File should not be empty")
    }

    @Test
    fun `flush produces valid json`() {
        val exporter = FileSpanExporter(tempDir) { "SomeTest.someMethod" }
        exporter.export(createFakeSpans("my-span"))
        exporter.flush()

        val file = tempDir.listFiles()!!.first()
        // OtlpStdoutSpanExporter writes a JSON object; verify it parses without throwing
        assertDoesNotThrow { org.junit.jupiter.api.Assertions.assertTrue(file.readText().trim().startsWith("{")) }
    }

    @Test
    fun `flush creates output directory if absent`() {
        val subDir = File(tempDir, "nested/path")
        assertFalse(subDir.exists())
        val exporter = FileSpanExporter(subDir) { "test" }
        exporter.export(createFakeSpans("s"))
        exporter.flush()
        assertTrue(subDir.exists())
    }

    @Test
    fun `flush with no spans writes nothing`() {
        val exporter = FileSpanExporter(tempDir) { "test" }
        exporter.flush()
        assertEquals(0, tempDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `second flush after clear writes nothing`() {
        val exporter = FileSpanExporter(tempDir) { "test" }
        exporter.export(createFakeSpans("s"))
        exporter.flush()
        exporter.flush() // second flush — buffer was cleared
        assertEquals(1, tempDir.listFiles()?.size ?: 0)
    }

    private fun createFakeSpans(name: String): List<SpanData> {
        val otel = OpenTelemetryExtension.create().openTelemetry
        val tracer = otel.getTracer("test")
        val span = tracer.spanBuilder(name).startSpan()
        span.end()
        val mem = InMemorySpanExporter.create()
        // grab the span data via InMemorySpanExporter fed by same SDK
        val provider = io.opentelemetry.sdk.trace.SdkTracerProvider.builder()
            .addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(mem))
            .build()
        val t2 = provider.get("test")
        val s2 = t2.spanBuilder(name).startSpan()
        s2.end()
        return mem.finishedSpanItems
    }
}
```

- [ ] **Step 2: Add opentelemetry-sdk-testing dependency to surveyor-agent pom.xml**

Add inside `<dependencies>`:

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk-testing</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 3: Run tests to confirm they fail**

```bash
mvn test -pl surveyor-agent -Dtest=FileSpanExporterTest 2>&1 | tail -10
```

Expected: compilation error (FileSpanExporter not found)

- [ ] **Step 4: Implement FileSpanExporter**

`surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/FileSpanExporter.kt`:

```kotlin
package com.antwerkz.surveyor.agent

import io.opentelemetry.exporter.logging.otlp.OtlpStdoutSpanExporter
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class FileSpanExporter(
    private val outputDir: File,
    private val testNameSupplier: () -> String
) : SpanExporter {

    private val lock = ReentrantLock()
    private val pending = mutableListOf<SpanData>()

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        lock.withLock { pending.addAll(spans) }
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode {
        val toWrite = lock.withLock { pending.toList().also { pending.clear() } }
        if (toWrite.isEmpty()) return CompletableResultCode.ofSuccess()

        outputDir.mkdirs()
        val safeName = testNameSupplier().replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outFile = File(outputDir, "$safeName.json")

        val baos = ByteArrayOutputStream()
        val delegate = OtlpStdoutSpanExporter.builder()
            .setOutput(PrintStream(baos, true, Charsets.UTF_8))
            .build()
        val result = delegate.export(toWrite)
        delegate.shutdown()
        outFile.writeBytes(baos.toByteArray())
        return result
    }

    override fun shutdown(): CompletableResultCode = flush()
}
```

- [ ] **Step 5: Run tests to confirm they pass**

```bash
mvn test -pl surveyor-agent -Dtest=FileSpanExporterTest
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add surveyor-agent/src/
git commit -m "Implement FileSpanExporter with OTLP JSON output and tests"
```

---

## Task 6: Implement OtelSetup

**Files:**
- Create: `surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/OtelSetup.kt`

- [ ] **Step 1: Create OtelSetup**

`surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/OtelSetup.kt`:

```kotlin
package com.antwerkz.surveyor.agent

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter

object OtelSetup {
    fun initialize(config: AgentConfig, fileExporter: FileSpanExporter): SdkTracerProvider {
        val exporters = mutableListOf<SpanExporter>(fileExporter)

        if (config.endpoint != null) {
            exporters += OtlpHttpSpanExporter.builder()
                .setEndpoint(config.endpoint)
                .build()
        }

        val processor = BatchSpanProcessor.builder(SpanExporter.composite(exporters)).build()
        return SdkTracerProvider.builder().addSpanProcessor(processor).build()
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
mvn compile -pl surveyor-agent
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/OtelSetup.kt
git commit -m "Add OtelSetup to initialize SDK with file and optional OTLP exporters"
```

---

## Task 7: Implement MethodAdvice

**Files:**
- Create: `surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/MethodAdvice.kt`

`MethodAdvice` is the ByteBuddy `@Advice` template for general (non-test) methods. The `onEnter` static method starts a child span and returns an OTel `Scope`; `onExit` ends the span (recording exceptions) and closes the scope. Both methods suppress all `Throwable`s to ensure Surveyor never affects target code.

**Important:** ByteBuddy copies the bytecode of advice methods directly into instrumented methods. The shaded OTel classes are accessible because `premain()` calls `appendToSystemClassLoaderSearch` with the agent JAR before installing the transformer (done in Task 9).

- [ ] **Step 1: Create MethodAdvice**

`surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/MethodAdvice.kt`:

```kotlin
package com.antwerkz.surveyor.agent

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Scope
import net.bytebuddy.asm.Advice

object MethodAdvice {

    @JvmStatic
    @Advice.OnMethodEnter(suppress = Throwable::class)
    fun onEnter(
        @Advice.Origin("#t##m") signature: String,
        @Advice.AllArguments args: Array<Any?>
    ): Scope? {
        val tracer = SurveyorContext.tracer ?: return null
        val span = tracer.spanBuilder(signature).startSpan()

        if (SurveyorContext.captureArgs && args.isNotEmpty()) {
            val maxLen = SurveyorContext.maxArgLength
            args.forEachIndexed { i, arg ->
                val value = when (arg) {
                    null -> "null"
                    else -> try {
                        val s = arg.toString()
                        if (s.length > maxLen) s.take(maxLen) + "…" else s
                    } catch (_: Throwable) {
                        "<error: ${arg.javaClass.name}>"
                    }
                }
                span.setAttribute("arg.$i", value)
            }
        }

        return span.makeCurrent()
    }

    @JvmStatic
    @Advice.OnMethodExit(suppress = Throwable::class, onThrowable = Throwable::class)
    fun onExit(
        @Advice.Enter scope: Scope?,
        @Advice.Thrown throwable: Throwable?
    ) {
        val span = Span.current()
        if (throwable != null) {
            span.recordException(throwable)
            span.setStatus(StatusCode.ERROR)
        }
        span.end()
        scope?.close()
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
mvn compile -pl surveyor-agent
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/MethodAdvice.kt
git commit -m "Add MethodAdvice for general method span instrumentation"
```

---

## Task 8: Implement TestRootAdvice

**Files:**
- Create: `surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/TestRootAdvice.kt`

`TestRootAdvice` is applied to methods annotated with `@Test` or `@ParameterizedTest` (JUnit 5) or `@Test` (TestNG). It creates a root span (no parent = new trace ID) and calls `forceFlush()` after the test ends so the file is written before the next test's spans arrive.

- [ ] **Step 1: Create TestRootAdvice**

`surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/TestRootAdvice.kt`:

```kotlin
package com.antwerkz.surveyor.agent

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Scope
import net.bytebuddy.asm.Advice

object TestRootAdvice {

    @JvmStatic
    @Advice.OnMethodEnter(suppress = Throwable::class)
    fun onEnter(@Advice.Origin("#t##m") signature: String): Scope? {
        val tracer = SurveyorContext.tracer ?: return null
        SurveyorContext.currentTestName = signature
        val span = tracer.spanBuilder(signature).setNoParent().startSpan()
        return span.makeCurrent()
    }

    @JvmStatic
    @Advice.OnMethodExit(suppress = Throwable::class, onThrowable = Throwable::class)
    fun onExit(
        @Advice.Enter scope: Scope?,
        @Advice.Thrown throwable: Throwable?
    ) {
        val span = Span.current()
        if (throwable != null) {
            span.recordException(throwable)
            span.setStatus(StatusCode.ERROR)
        }
        span.end()
        scope?.close()
        SurveyorContext.forceFlush()
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
mvn compile -pl surveyor-agent
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/TestRootAdvice.kt
git commit -m "Add TestRootAdvice for per-test root span and post-test flush"
```

---

## Task 9: Implement SurveyorTransformer

**Files:**
- Create: `surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/SurveyorTransformer.kt`
- Create: `surveyor-agent/src/test/kotlin/com/antwerkz/surveyor/agent/SurveyorTransformerTest.kt`

- [ ] **Step 1: Write a failing test**

`surveyor-agent/src/test/kotlin/com/antwerkz/surveyor/agent/SurveyorTransformerTest.kt`:

```kotlin
package com.antwerkz.surveyor.agent

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import net.bytebuddy.agent.ByteBuddyAgent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class SurveyorTransformerTest {

    companion object {
        val spanExporter = InMemorySpanExporter.create()

        @JvmStatic
        @BeforeAll
        fun setup() {
            val provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build()
            SurveyorContext.tracer = provider.get("surveyor-test")
            SurveyorContext.tracerProvider = provider
            SurveyorContext.captureArgs = false

            val instrumentation = ByteBuddyAgent.install()
            val config = AgentConfig(
                packages = listOf("com.antwerkz.surveyor.agent.fixture"),
                outputDir = createTempDir(),
                endpoint = null,
                captureArgs = false,
                maxArgLength = 256
            )
            SurveyorTransformer.install(config, instrumentation)
        }
    }

    @Test
    fun `instrumented method produces a span`() {
        spanExporter.reset()
        val fixture = com.antwerkz.surveyor.agent.fixture.SimpleFixture()
        fixture.add(2, 3)
        val spans = spanExporter.finishedSpanItems
        assertTrue(spans.isNotEmpty(), "Expected at least one span")
        assertTrue(spans.any { it.name.contains("add") }, "Expected span named after method")
    }

    @Test
    fun `exception in instrumented method is recorded on span`() {
        spanExporter.reset()
        val fixture = com.antwerkz.surveyor.agent.fixture.SimpleFixture()
        assertThrows(ArithmeticException::class.java) { fixture.divide(1, 0) }
        val spans = spanExporter.finishedSpanItems
        val span = spans.first { it.name.contains("divide") }
        assertTrue(span.events.any { it.name == "exception" }, "Expected exception event")
    }
}
```

- [ ] **Step 2: Create the test fixture class**

`surveyor-agent/src/test/kotlin/com/antwerkz/surveyor/agent/fixture/SimpleFixture.kt`:

```kotlin
package com.antwerkz.surveyor.agent.fixture

class SimpleFixture {
    fun add(a: Int, b: Int): Int = a + b
    fun divide(a: Int, b: Int): Int = a / b
}
```

- [ ] **Step 3: Add ByteBuddy agent test dependency to surveyor-agent pom.xml**

In the `<dependencies>` section add:

```xml
<dependency>
    <groupId>net.bytebuddy</groupId>
    <artifactId>byte-buddy-agent</artifactId>
    <scope>test</scope>
</dependency>
```

Also add to `maven-surefire-plugin` config so that ByteBuddyAgent.install() works without a javaagent argument:

```xml
<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.2.5</version>
    <configuration>
        <argLine>-XX:+EnableDynamicAgentLoading</argLine>
    </configuration>
</plugin>
```

- [ ] **Step 4: Run tests to confirm they fail**

```bash
mvn test -pl surveyor-agent -Dtest=SurveyorTransformerTest 2>&1 | tail -10
```

Expected: compilation error (SurveyorTransformer not found)

- [ ] **Step 5: Implement SurveyorTransformer**

`surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/SurveyorTransformer.kt`:

```kotlin
package com.antwerkz.surveyor.agent

import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.asm.Advice
import net.bytebuddy.matcher.ElementMatchers.*
import java.lang.instrument.Instrumentation

object SurveyorTransformer {

    fun install(config: AgentConfig, instrumentation: Instrumentation) {
        val packageMatcher = config.packages
            .map { nameStartsWith(it) }
            .reduce { a, b -> a.or(b) }

        val testAnnotations = listOf(
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.params.ParameterizedTest",
            "org.testng.annotations.Test"
        ).map { isAnnotatedWith(named(it)) }
            .reduce { a, b -> a.or(b) }

        val generalMethods = isMethod()
            .and(not(isSynthetic()))
            .and(not(isAbstract()))
            .and(not(testAnnotations))

        val ctors = isConstructor().and(not(isSynthetic()))

        AgentBuilder.Default()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
            .ignore(
                nameStartsWith("net.bytebuddy.")
                    .or(nameStartsWith("com.antwerkz.surveyor.shaded."))
                    .or(nameStartsWith("io.opentelemetry."))
                    .or(nameStartsWith("sun."))
                    .or(nameStartsWith("jdk."))
            )
            .type(packageMatcher)
            .transform { builder, _, _, _, _ ->
                builder
                    .visit(Advice.to(TestRootAdvice::class.java).on(testAnnotations))
                    .visit(Advice.to(MethodAdvice::class.java).on(generalMethods.or(ctors)))
            }
            .installOn(instrumentation)
    }
}
```

- [ ] **Step 6: Run tests to confirm they pass**

```bash
mvn test -pl surveyor-agent -Dtest=SurveyorTransformerTest
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 7: Run all agent tests**

```bash
mvn test -pl surveyor-agent
```

Expected: all tests pass

- [ ] **Step 8: Commit**

```bash
git add surveyor-agent/src/
git commit -m "Implement SurveyorTransformer with ByteBuddy AgentBuilder and tests"
```

---

## Task 10: Implement SurveyorAgent.premain()

**Files:**
- Create: `surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/SurveyorAgent.kt`

`premain` must call `appendToSystemClassLoaderSearch` with the agent JAR BEFORE installing the ByteBuddy transformer. This makes the shaded OTel and ByteBuddy classes (which appear in advice bytecode injected into application classes) available to application class loaders.

- [ ] **Step 1: Create SurveyorAgent**

`surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/SurveyorAgent.kt`:

```kotlin
package com.antwerkz.surveyor.agent

import java.io.File
import java.lang.instrument.Instrumentation
import java.util.jar.JarFile

object SurveyorAgent {

    @JvmStatic
    fun premain(agentArgs: String?, instrumentation: Instrumentation) {
        try {
            appendAgentToSystemClasspath(instrumentation)

            val config = AgentConfig.parse(agentArgs)
            val fileExporter = FileSpanExporter(config.outputDir) {
                SurveyorContext.currentTestName ?: "surveyor-run"
            }
            val tracerProvider = OtelSetup.initialize(config, fileExporter)

            SurveyorContext.tracer = tracerProvider.get("com.antwerkz.surveyor")
            SurveyorContext.tracerProvider = tracerProvider
            SurveyorContext.captureArgs = config.captureArgs
            SurveyorContext.maxArgLength = config.maxArgLength

            SurveyorTransformer.install(config, instrumentation)
        } catch (e: Exception) {
            System.err.println("[Surveyor] Initialization failed: ${e.message}")
            e.printStackTrace(System.err)
        }
    }

    private fun appendAgentToSystemClasspath(instrumentation: Instrumentation) {
        val jarPath = SurveyorAgent::class.java.protectionDomain?.codeSource?.location
            ?: return
        val jarFile = File(jarPath.toURI())
        if (jarFile.exists() && jarFile.name.endsWith(".jar")) {
            instrumentation.appendToSystemClassLoaderSearch(JarFile(jarFile))
        }
    }
}
```

- [ ] **Step 2: Build the shaded fat JAR**

```bash
mvn package -pl surveyor-agent -DskipTests
```

Expected: `BUILD SUCCESS`, file `surveyor-agent/target/surveyor-agent-1.0-SNAPSHOT.jar` exists

- [ ] **Step 3: Verify manifest**

```bash
unzip -p surveyor-agent/target/surveyor-agent-1.0-SNAPSHOT.jar META-INF/MANIFEST.MF
```

Expected output contains:
```
Premain-Class: com.antwerkz.surveyor.agent.SurveyorAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
```

- [ ] **Step 4: Verify shading**

```bash
unzip -l surveyor-agent/target/surveyor-agent-1.0-SNAPSHOT.jar | grep "shaded/net/bytebuddy" | head -3
```

Expected: several entries under `com/antwerkz/surveyor/shaded/net/bytebuddy/`

- [ ] **Step 5: Commit**

```bash
git add surveyor-agent/src/main/kotlin/com/antwerkz/surveyor/agent/SurveyorAgent.kt
git commit -m "Implement SurveyorAgent premain with classloader setup and error containment"
```

---

## Task 11: Create surveyor-maven-plugin module

**Files:**
- Create: `surveyor-maven-plugin/pom.xml`

- [ ] **Step 1: Create surveyor-maven-plugin/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.antwerkz</groupId>
        <artifactId>surveyor</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>surveyor-maven-plugin</artifactId>
    <packaging>maven-plugin</packaging>

    <dependencies>
        <dependency>
            <groupId>org.apache.maven</groupId>
            <artifactId>maven-plugin-api</artifactId>
            <version>3.9.6</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.maven.plugin-tools</groupId>
            <artifactId>maven-plugin-annotations</artifactId>
            <version>3.13.1</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.maven</groupId>
            <artifactId>maven-core</artifactId>
            <version>3.9.6</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>com.antwerkz</groupId>
            <artifactId>surveyor-agent</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-stdlib</artifactId>
        </dependency>
        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-test-junit5</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <sourceDirectory>src/main/kotlin</sourceDirectory>
        <testSourceDirectory>src/test/kotlin</testSourceDirectory>
        <plugins>
            <plugin>
                <groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-maven-plugin</artifactId>
                <version>${kotlin.version}</version>
                <extensions>true</extensions>
                <executions>
                    <execution>
                        <id>compile</id>
                        <phase>compile</phase>
                        <goals><goal>compile</goal></goals>
                    </execution>
                    <execution>
                        <id>test-compile</id>
                        <phase>test-compile</phase>
                        <goals><goal>test-compile</goal></goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <artifactId>maven-plugin-plugin</artifactId>
                <version>3.13.1</version>
                <executions>
                    <execution>
                        <id>default-descriptor</id>
                        <phase>process-classes</phase>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
            <plugin>
                <artifactId>maven-invoker-plugin</artifactId>
                <version>3.7.0</version>
                <configuration>
                    <projectsDirectory>src/it</projectsDirectory>
                    <cloneProjectsTo>${project.build.directory}/it</cloneProjectsTo>
                    <localRepositoryPath>${project.build.directory}/local-repo</localRepositoryPath>
                    <settingsFile>src/it/settings.xml</settingsFile>
                    <postBuildHookScript>verify</postBuildHookScript>
                    <goals>
                        <goal>test</goal>
                    </goals>
                </configuration>
                <executions>
                    <execution>
                        <id>integration-test</id>
                        <goals>
                            <goal>install</goal>
                            <goal>run</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create source directory structure**

```bash
mkdir -p surveyor-maven-plugin/src/main/kotlin/com/antwerkz/surveyor/maven
mkdir -p surveyor-maven-plugin/src/test/kotlin/com/antwerkz/surveyor/maven
mkdir -p surveyor-maven-plugin/src/it
```

- [ ] **Step 3: Create IT settings.xml** (needed by invoker plugin)

`surveyor-maven-plugin/src/it/settings.xml`:

```xml
<settings>
    <localRepository>${localRepositoryPath}</localRepository>
</settings>
```

- [ ] **Step 4: Verify module builds (plugin descriptor generation will fail until Mojo exists)**

```bash
mvn compile -pl surveyor-maven-plugin
```

Expected: `BUILD SUCCESS` (just compilation)

- [ ] **Step 5: Commit**

```bash
git add surveyor-maven-plugin/
git commit -m "Add surveyor-maven-plugin module skeleton"
```

---

## Task 12: Implement SurveyorMojo

**Files:**
- Create: `surveyor-maven-plugin/src/main/kotlin/com/antwerkz/surveyor/maven/SurveyorMojo.kt`
- Create: `surveyor-maven-plugin/src/test/kotlin/com/antwerkz/surveyor/maven/SurveyorMojoTest.kt`

The Mojo runs at the `initialize` phase and prepends a `-javaagent` argument to the `${argLine}` Maven property, which Surefire and Failsafe pick up when they launch the test JVM.

Users must configure Surefire with `<argLine>@{argLine}</argLine>` for late property binding. The Mojo sets the `argLine` property on `MavenProject.properties`.

- [ ] **Step 1: Write failing tests**

`surveyor-maven-plugin/src/test/kotlin/com/antwerkz/surveyor/maven/SurveyorMojoTest.kt`:

```kotlin
package com.antwerkz.surveyor.maven

import org.apache.maven.project.MavenProject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties

class SurveyorMojoTest {

    @TempDir
    lateinit var tempDir: File

    private fun mojo(configure: SurveyorMojo.() -> Unit = {}): SurveyorMojo {
        val project = MavenProject()
        project.properties = Properties()
        val fakeAgentJar = File(tempDir, "surveyor-agent.jar").also { it.createNewFile() }
        return SurveyorMojo().apply {
            this.project = project
            this.packages = "com.example"
            this.outputDirectory = File(tempDir, "surveyor")
            this.agentJar = fakeAgentJar
            configure()
        }
    }

    @Test
    fun `sets argLine property with javaagent`() {
        val m = mojo()
        m.execute()
        val argLine = m.project.properties.getProperty("argLine") ?: ""
        assertTrue(argLine.contains("-javaagent:"), "Expected -javaagent in argLine: $argLine")
        assertTrue(argLine.contains("packages=com.example"), "Expected packages in argLine: $argLine")
    }

    @Test
    fun `argLine includes outputDir`() {
        val m = mojo()
        m.execute()
        val argLine = m.project.properties.getProperty("argLine") ?: ""
        assertTrue(argLine.contains("outputDir="), "Expected outputDir in argLine: $argLine")
    }

    @Test
    fun `argLine includes endpoint when set`() {
        val m = mojo { endpoint = "http://localhost:4318" }
        m.execute()
        val argLine = m.project.properties.getProperty("argLine") ?: ""
        assertTrue(argLine.contains("endpoint=http://localhost:4318"), "Expected endpoint: $argLine")
    }

    @Test
    fun `argLine omits endpoint when not set`() {
        val m = mojo()
        m.execute()
        val argLine = m.project.properties.getProperty("argLine") ?: ""
        assertFalse(argLine.contains("endpoint="), "Unexpected endpoint: $argLine")
    }

    @Test
    fun `argLine includes captureArgs when true`() {
        val m = mojo { captureArgs = true }
        m.execute()
        val argLine = m.project.properties.getProperty("argLine") ?: ""
        assertTrue(argLine.contains("captureArgs=true"), "Expected captureArgs: $argLine")
    }

    @Test
    fun `skip leaves argLine unchanged`() {
        val m = mojo { skip = true }
        m.execute()
        assertNull(m.project.properties.getProperty("argLine"))
    }

    @Test
    fun `prepends to existing argLine`() {
        val m = mojo()
        m.project.properties["argLine"] = "-Xmx512m"
        m.execute()
        val argLine = m.project.properties.getProperty("argLine") ?: ""
        assertTrue(argLine.contains("-javaagent:"), "Expected javaagent")
        assertTrue(argLine.contains("-Xmx512m"), "Expected original arg preserved")
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
mvn test -pl surveyor-maven-plugin -Dtest=SurveyorMojoTest 2>&1 | tail -10
```

Expected: compilation error (SurveyorMojo not found)

- [ ] **Step 3: Implement SurveyorMojo**

`surveyor-maven-plugin/src/main/kotlin/com/antwerkz/surveyor/maven/SurveyorMojo.kt`:

```kotlin
package com.antwerkz.surveyor.maven

import org.apache.maven.artifact.Artifact
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import java.io.File

@Mojo(
    name = "prepare",
    defaultPhase = LifecyclePhase.INITIALIZE,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true
)
class SurveyorMojo : AbstractMojo() {

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    lateinit var project: MavenProject

    @Parameter(required = true)
    lateinit var packages: String

    @Parameter(defaultValue = "\${project.build.directory}/surveyor")
    lateinit var outputDirectory: File

    @Parameter
    var endpoint: String? = null

    @Parameter(defaultValue = "false")
    var captureArgs: Boolean = false

    @Parameter(defaultValue = "256")
    var maxArgLength: Int = 256

    @Parameter(defaultValue = "false", property = "surveyor.skip")
    var skip: Boolean = false

    @Parameter(defaultValue = "\${plugin.artifacts}", readonly = true)
    var pluginArtifacts: List<Artifact> = emptyList()

    // Visible for testing — normally resolved from pluginArtifacts
    internal var agentJar: File? = null

    override fun execute() {
        if (skip) return

        val jar = agentJar ?: resolveAgentJar()
        val agentArgs = buildAgentArgs(jar)

        val existing = project.properties.getProperty("argLine", "")
        val newArgLine = if (existing.isBlank()) agentArgs else "$agentArgs $existing"
        project.properties["argLine"] = newArgLine

        log.info("Surveyor agent configured: $agentArgs")
    }

    private fun resolveAgentJar(): File {
        return pluginArtifacts
            .firstOrNull { it.artifactId == "surveyor-agent" }
            ?.file
            ?: throw MojoExecutionException(
                "Could not locate surveyor-agent JAR in plugin artifacts. " +
                "Ensure surveyor-maven-plugin is on the build classpath."
            )
    }

    private fun buildAgentArgs(jar: File): String {
        val args = buildString {
            append("packages=$packages")
            append("|outputDir=${outputDirectory.absolutePath}")
            if (endpoint != null) append("|endpoint=$endpoint")
            if (captureArgs) append("|captureArgs=true")
            if (maxArgLength != 256) append("|maxArgLength=$maxArgLength")
        }
        return "-javaagent:${jar.absolutePath}=$args"
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
mvn test -pl surveyor-maven-plugin -Dtest=SurveyorMojoTest
```

Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 5: Run full build (both modules, no ITs yet)**

```bash
mvn package -DskipTests
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add surveyor-maven-plugin/src/
git commit -m "Implement SurveyorMojo with argLine injection and tests"
```

---

## Task 13: Basic integration test

**Files:**
- Create: `surveyor-maven-plugin/src/it/basic/pom.xml`
- Create: `surveyor-maven-plugin/src/it/basic/invoker.properties`
- Create: `surveyor-maven-plugin/src/it/basic/src/main/java/com/example/Calculator.java`
- Create: `surveyor-maven-plugin/src/it/basic/src/test/java/com/example/CalculatorTest.java`
- Create: `surveyor-maven-plugin/src/it/basic/verify.groovy`

The IT project instruments `com.example` with Surveyor, runs two JUnit 5 test methods, and verifies one trace file is written per test method.

- [ ] **Step 1: Create invoker.properties**

`surveyor-maven-plugin/src/it/basic/invoker.properties`:

```properties
invoker.goals=test
invoker.profiles=surveyor
```

- [ ] **Step 2: Create IT project pom.xml**

`surveyor-maven-plugin/src/it/basic/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>surveyor-it-basic</artifactId>
    <version>1.0-SNAPSHOT</version>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.0</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
                <configuration>
                    <argLine>@{argLine}</argLine>
                </configuration>
            </plugin>
        </plugins>
    </build>

    <profiles>
        <profile>
            <id>surveyor</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>com.antwerkz</groupId>
                        <artifactId>surveyor-maven-plugin</artifactId>
                        <version>@project.version@</version>
                        <configuration>
                            <packages>com.example</packages>
                        </configuration>
                        <executions>
                            <execution>
                                <goals><goal>prepare</goal></goals>
                            </execution>
                        </executions>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
</project>
```

- [ ] **Step 3: Create Calculator.java**

`surveyor-maven-plugin/src/it/basic/src/main/java/com/example/Calculator.java`:

```java
package com.example;

public class Calculator {
    public int add(int a, int b) { return a + b; }
    public int multiply(int a, int b) { return a * b; }
    private int helper(int x) { return x * 2; }
}
```

- [ ] **Step 4: Create CalculatorTest.java**

`surveyor-maven-plugin/src/it/basic/src/test/java/com/example/CalculatorTest.java`:

```java
package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CalculatorTest {

    @Test
    void testAdd() {
        assertEquals(5, new Calculator().add(2, 3));
    }

    @Test
    void testMultiply() {
        assertEquals(6, new Calculator().multiply(2, 3));
    }
}
```

- [ ] **Step 5: Create verify.groovy**

`surveyor-maven-plugin/src/it/basic/verify.groovy`:

```groovy
import java.nio.file.Files

def surveyorDir = new File(basedir, "target/surveyor")
assert surveyorDir.exists() : "target/surveyor directory should exist"

def files = surveyorDir.listFiles()?.findAll { it.name.endsWith(".json") } ?: []
assert files.size() == 2 : "Expected 2 trace files (one per test method), found ${files.size()}: ${files*.name}"

files.each { file ->
    def content = file.text.trim()
    assert content.startsWith("{") : "Trace file ${file.name} should be a JSON object, got: ${content.take(40)}"
    assert content.length() > 10 : "Trace file ${file.name} should not be empty"
}

// At least one file should reference the Calculator class
def allContent = files*.text.join("\n")
assert allContent.contains("Calculator") || allContent.contains("calculator") :
    "Expected Calculator to appear in span names"
```

- [ ] **Step 6: Run the integration test**

```bash
mvn verify -pl surveyor-maven-plugin
```

Expected: `BUILD SUCCESS` with invoker tests passing

If it fails, check `surveyor-maven-plugin/target/it/basic/target/surveyor/` for trace files and `target/it/basic/build.log` for errors.

- [ ] **Step 7: Commit**

```bash
git add surveyor-maven-plugin/src/it/basic/ surveyor-maven-plugin/src/it/settings.xml
git commit -m "Add basic integration test verifying per-test trace file output"
```

---

## Task 14: captureArgs integration test

**Files:**
- Create: `surveyor-maven-plugin/src/it/with-capture-args/pom.xml`
- Create: `surveyor-maven-plugin/src/it/with-capture-args/invoker.properties`
- Create: `surveyor-maven-plugin/src/it/with-capture-args/src/main/java/com/example/Calculator.java`
- Create: `surveyor-maven-plugin/src/it/with-capture-args/src/test/java/com/example/CalculatorTest.java`
- Create: `surveyor-maven-plugin/src/it/with-capture-args/verify.groovy`

- [ ] **Step 1: Create invoker.properties**

`surveyor-maven-plugin/src/it/with-capture-args/invoker.properties`:

```properties
invoker.goals=test
invoker.profiles=surveyor
```

- [ ] **Step 2: Create pom.xml (same as basic but with captureArgs=true)**

`surveyor-maven-plugin/src/it/with-capture-args/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>surveyor-it-capture-args</artifactId>
    <version>1.0-SNAPSHOT</version>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.0</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
                <configuration>
                    <argLine>@{argLine}</argLine>
                </configuration>
            </plugin>
        </plugins>
    </build>

    <profiles>
        <profile>
            <id>surveyor</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>com.antwerkz</groupId>
                        <artifactId>surveyor-maven-plugin</artifactId>
                        <version>@project.version@</version>
                        <configuration>
                            <packages>com.example</packages>
                            <captureArgs>true</captureArgs>
                        </configuration>
                        <executions>
                            <execution>
                                <goals><goal>prepare</goal></goals>
                            </execution>
                        </executions>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
</project>
```

- [ ] **Step 3: Create Calculator.java (same as basic)**

`surveyor-maven-plugin/src/it/with-capture-args/src/main/java/com/example/Calculator.java`:

```java
package com.example;

public class Calculator {
    public int add(int a, int b) { return a + b; }
    public int multiply(int a, int b) { return a * b; }
}
```

- [ ] **Step 4: Create CalculatorTest.java (same as basic)**

`surveyor-maven-plugin/src/it/with-capture-args/src/test/java/com/example/CalculatorTest.java`:

```java
package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CalculatorTest {

    @Test
    void testAdd() {
        assertEquals(5, new Calculator().add(2, 3));
    }
}
```

- [ ] **Step 5: Create verify.groovy**

`surveyor-maven-plugin/src/it/with-capture-args/verify.groovy`:

```groovy
def surveyorDir = new File(basedir, "target/surveyor")
assert surveyorDir.exists() : "target/surveyor directory should exist"

def files = surveyorDir.listFiles()?.findAll { it.name.endsWith(".json") } ?: []
assert files.size() >= 1 : "Expected at least one trace file"

def content = files[0].text
// OTLP JSON attribute names for captured args
assert content.contains("arg.0") : "Expected arg.0 attribute for captureArgs=true, content: ${content.take(200)}"
assert content.contains("arg.1") : "Expected arg.1 attribute for captureArgs=true, content: ${content.take(200)}"
```

- [ ] **Step 6: Run integration tests**

```bash
mvn verify -pl surveyor-maven-plugin
```

Expected: `BUILD SUCCESS`, both ITs pass

- [ ] **Step 7: Commit**

```bash
git add surveyor-maven-plugin/src/it/with-capture-args/
git commit -m "Add captureArgs integration test verifying arg.N span attributes"
```

---

## Self-Review Checklist (complete before handing off)

- [ ] All spec requirements have a covering task:
  - [x] Non-synthetic method + constructor instrumentation → Tasks 7, 9
  - [x] OTel span decorations → Tasks 5, 6, 7, 8
  - [x] File output under target/surveyor → Tasks 5, 13
  - [x] Optional OTLP endpoint → Task 6 (OtelSetup), Task 12 (Mojo config)
  - [x] Per-test trace isolation → Task 8 (TestRootAdvice), Task 13 (verify 2 files)
  - [x] captureArgs / maxArgLength → Tasks 7, 14
  - [x] Maven plugin injects javaagent → Tasks 11-12
  - [x] Shaded fat JAR → Task 2 (pom shade config), Task 10 (verify)
  - [x] appendToSystemClassLoaderSearch → Task 10
- [ ] No placeholders (TBD/TODO) in any task
- [ ] Method/class names are consistent across all tasks
