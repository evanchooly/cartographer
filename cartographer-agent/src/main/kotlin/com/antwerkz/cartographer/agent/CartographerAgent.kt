package com.antwerkz.cartographer.agent

import java.io.File
import java.lang.instrument.Instrumentation
import java.util.jar.JarFile

object CartographerAgent {

    @JvmStatic
    fun premain(agentArgs: String?, instrumentation: Instrumentation) {
        try {
            appendAgentToSystemClasspath(instrumentation)

            val config = AgentConfig.parse(agentArgs)
            val fileExporter = FileSpanExporter(config.outputDir) {
                CartographerContext.currentTestName ?: "cartographer-run"
            }
            val tracerProvider = OtelSetup.initialize(config, fileExporter)

            CartographerContext.tracer = tracerProvider.get("com.antwerkz.cartographer")
            CartographerContext.tracerProvider = tracerProvider
            CartographerContext.captureArgs = config.captureArgs
            CartographerContext.maxArgLength = config.maxArgLength

            CartographerTransformer.install(config, instrumentation)
        } catch (e: Exception) {
            System.err.println("[Cartographer] Initialization failed: ${e.message}")
            e.printStackTrace(System.err)
        }
    }

    private fun appendAgentToSystemClasspath(instrumentation: Instrumentation) {
        val location = CartographerAgent::class.java.protectionDomain?.codeSource?.location ?: return
        val jarFile = File(location.toURI())
        if (jarFile.exists() && jarFile.name.endsWith(".jar")) {
            instrumentation.appendToSystemClassLoaderSearch(JarFile(jarFile))
        }
    }
}
