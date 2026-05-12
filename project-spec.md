Surveyor is a JVM agent that runs as part of a test run. It is intended to be used primarily, though not necessarily exclusively,
as part of the run of a single test. This agent will instrument every non-synthetic method (including constructors) in any class under
the configured package with OTel span decorations. As part of the test run, these decorations will emit span data to be collected.
By default, this data will be collected in a file under target/surveyor/ but the location can be configured. Optionally, this data
can simultaneously be fed to an OTel-compatible collector endpoint if desired. The collected data can then be visualized in a tool
such as Jaeger to see the call graph of the test run and the relative timing of each method call. This can be used to identify
bottlenecks in the code and to understand the flow of execution through the code. The primary motivation for this tool is to identify
the code paths executed during a particular test run to identify areas in the code that are being revisited when perhaps they should
not be due to caching, memoization, etc. Other uses, of course, could be imagined and are welcome. This tool is not intended to be
used in production, but rather as a development and testing aid to help identify areas of the code that could be optimized or
refactored for better performance.

This tool is primarily driven by a maven plugin much in the spirit of jacoco and similar tools.

The maven plugin will take the root level package and any class loaded under that package will be instrumented with span decorations.
The actual instrumentation work is done by a JVM agent (java agent). The maven plugin's role is to configure the test-running JVM
with that java agent, in the same way that the jacoco maven plugin injects its agent via the -javaagent JVM argument.

Internal to the agent will be an OTel SDK setup which can be configured in the standard OTel fashion. The optional endpoint for
exporting spans should use OTLP (HTTP or gRPC) so that it is compatible with Jaeger, Grafana, Datadog, and other OTel-compatible
backends. The agent will also be responsible for writing the collected data to a file in the target/surveyor/ directory (or other
configured location) for later visualization. The file format should be whatever format Jaeger expects to ingest (e.g., OTLP JSON
or Jaeger's native JSON format). If a standard file-based OTel exporter already exists in the OTel project that meets this need,
it should be used rather than writing a custom one; otherwise a custom exporter is acceptable.

Test trace isolation: ideally, each JUnit/TestNG test method produces its own individual trace, allowing all tests in a run to be
captured as separate traces. If that is not feasible, the tool may be invoked with a single test argument so that one trace covers
the full run of that test.

The maven plugin will be configured to run during the test phase of the maven lifecycle. It will not ship with a fixed profile name;
the activation profile is something each adopting project configures for itself. The plugin will be designed to be easy to configure
and use, with clear documentation and examples provided to help users get started with it.
