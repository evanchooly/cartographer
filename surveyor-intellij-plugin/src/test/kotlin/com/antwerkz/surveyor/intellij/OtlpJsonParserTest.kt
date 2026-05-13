package com.antwerkz.surveyor.intellij

import com.antwerkz.surveyor.intellij.model.SpanNode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OtlpJsonParserTest {

    private fun resource(name: String) =
        File(javaClass.getResource("/$name")!!.toURI())

    @Test
    fun `parses root span from single-span trace`() {
        val roots = OtlpJsonParser.parse(resource("single-span-trace.json"))
        assertEquals(1, roots.size)
        val root = roots[0]
        assertEquals("com.example.CalculatorTest.<init>", root.name)
        assertEquals(0, root.depth)
        assertTrue(root.children.isEmpty())
        assertTrue(root.durationMs > 0)
    }

    @Test
    fun `parses tree from greeter trace`() {
        val roots = OtlpJsonParser.parse(resource("greeter-trace.json"))
        assertEquals(1, roots.size)
        val root = roots[0]
        assertEquals("com.example.GreeterTest.testGreet", root.name)
        assertEquals(0, root.depth)
        assertEquals(2, root.children.size)
    }

    @Test
    fun `children are sorted by start time`() {
        val roots = OtlpJsonParser.parse(resource("greeter-trace.json"))
        val children = roots[0].children
        assertTrue(children[0].startNano <= children[1].startNano)
    }

    @Test
    fun `child spans have depth 1`() {
        val roots = OtlpJsonParser.parse(resource("greeter-trace.json"))
        roots[0].children.forEach { assertEquals(1, it.depth) }
    }

    @Test
    fun `extracts attributes from greet span`() {
        val roots = OtlpJsonParser.parse(resource("greeter-trace.json"))
        val greet = roots[0].children.first { it.name.endsWith(".greet") }
        assertEquals("World", greet.attributes["arg.0"])
        assertEquals("2", greet.attributes["arg.1"])
    }

    @Test
    fun `computes durationMs correctly`() {
        val roots = OtlpJsonParser.parse(resource("single-span-trace.json"))
        val span = roots[0]
        val expected = (1778565909654857234L - 1778565909654849675L) / 1_000_000.0
        assertEquals(expected, span.durationMs, 0.001)
    }

    @Test
    fun `returns empty list for empty resourceSpans`() {
        val tmp = File.createTempFile("surveyor-test", ".json").also {
            it.writeText("""{"resourceSpans":[]}""")
            it.deleteOnExit()
        }
        val roots = OtlpJsonParser.parse(tmp)
        assertTrue(roots.isEmpty())
    }
}
