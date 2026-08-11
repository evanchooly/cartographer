import java.nio.file.*

def cartographerDir = new File(basedir, "target/cartographer")
assert cartographerDir.exists() : "target/cartographer directory should exist"

def traceFiles = cartographerDir.listFiles { f -> f.name.endsWith(".json") && !f.name.startsWith("cartographer-run") }
assert traceFiles != null && traceFiles.length == 2 :
    "Expected 2 trace files (one per @Test), found: ${traceFiles?.length ?: 0}"

traceFiles.each { f ->
    assert f.length() > 0 : "Trace file ${f.name} should be non-empty"
}

true
