import java.nio.file.*

def surveyorDir = new File(basedir, "target/surveyor")
assert surveyorDir.exists() : "target/surveyor directory should exist"

def traceFiles = surveyorDir.listFiles { f -> f.name.endsWith(".pb") && !f.name.startsWith("surveyor-run") }
assert traceFiles != null && traceFiles.length == 1 :
    "Expected 1 trace file (one @Test), found: ${traceFiles?.length ?: 0}"

def pbFile = traceFiles[0]
assert pbFile.length() > 0 : "Trace file should be non-empty"

// Parse the protobuf binary and verify arg.0 and arg.1 attributes exist
// The OTLP protobuf format encodes strings as UTF-8, so we can search for them
def bytes = pbFile.bytes
def content = new String(bytes, "ISO-8859-1")
assert content.contains("arg.0") : "Trace should contain arg.0 attribute"
assert content.contains("arg.1") : "Trace should contain arg.1 attribute"
assert content.contains("World") : "Trace should contain arg.0 value 'World'"

true
