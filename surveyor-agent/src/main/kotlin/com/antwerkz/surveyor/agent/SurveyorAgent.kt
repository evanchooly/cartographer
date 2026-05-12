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
        val location = SurveyorAgent::class.java.protectionDomain?.codeSource?.location ?: return
        val jarFile = File(location.toURI())
        if (jarFile.exists() && jarFile.name.endsWith(".jar")) {
            instrumentation.appendToSystemClassLoaderSearch(JarFile(jarFile))
        }
    }
}
