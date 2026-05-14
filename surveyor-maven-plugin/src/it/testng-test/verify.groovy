def surveyorDir = new File(basedir, "target/surveyor")
assert surveyorDir.exists() : "target/surveyor directory should exist"

def traceFiles = surveyorDir.listFiles { f -> f.name.endsWith(".json") && !f.name.startsWith("surveyor-run") }
assert traceFiles != null && traceFiles.length == 2 :
    "Expected 2 trace files (one per @Test), found: ${traceFiles?.length ?: 0}"

traceFiles.each { f ->
    assert f.length() > 0 : "Trace file ${f.name} should be non-empty"
}

true
