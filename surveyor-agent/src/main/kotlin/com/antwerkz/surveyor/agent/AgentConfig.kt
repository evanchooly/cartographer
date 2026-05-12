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
