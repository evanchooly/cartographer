package com.antwerkz.cartographer.agent

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
    fun `defaults outputDir to target slash cartographer`() {
        val cfg = AgentConfig.parse("packages=com.example")
        assertEquals(File("target/cartographer"), cfg.outputDir)
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
