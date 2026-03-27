/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.ai.embabel

import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import org.atmosphere.ai.AiCapability
import org.atmosphere.ai.AiConfig
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.AgentRuntime
import org.atmosphere.ai.StreamingSession
import org.slf4j.LoggerFactory

/**
 * [AgentRuntime] implementation backed by the Embabel Agent Platform.
 *
 * Auto-detected when `embabel-agent-api` is on the classpath.
 * The [AgentPlatform] and agent name must be configured via [setAgentPlatform]
 * and [setAgentName] — typically done by Spring auto-configuration.
 */
class EmbabelAgentRuntime : AgentRuntime {

    companion object {
        private val logger = LoggerFactory.getLogger(EmbabelAgentRuntime::class.java)

        @Volatile
        private var agentPlatform: AgentPlatform? = null

        @Volatile
        private var agentName: String = "chat-assistant"

        @JvmStatic
        fun setAgentPlatform(platform: AgentPlatform) {
            agentPlatform = platform
        }

        @JvmStatic
        fun setAgentName(name: String) {
            agentName = name
        }
    }

    override fun name(): String = "embabel"

    override fun isAvailable(): Boolean {
        return try {
            Class.forName("com.embabel.agent.core.AgentPlatform")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    override fun priority(): Int = 100

    override fun configure(settings: AiConfig.LlmSettings) {
        if (agentPlatform != null) {
            return
        }
        logger.info(
            "Embabel adapter active but requires an AgentPlatform bean with deployed @Agent classes. " +
                "Auto-configuration from credentials alone is not supported. " +
                "Ensure embabel-agent-spring-boot-starter is on the classpath."
        )
    }

    override fun execute(context: AgentExecutionContext, session: StreamingSession) {
        val platform = agentPlatform
            ?: throw IllegalStateException(
                "EmbabelAgentRuntime: AgentPlatform not configured. " +
                    "Call EmbabelAgentRuntime.setAgentPlatform() or use Spring auto-configuration."
            )

        val targetAgent = context.agentId() ?: agentName

        session.progress("Starting agent: $targetAgent...")

        val agent = platform.agents().firstOrNull { it.name == targetAgent }
            ?: throw IllegalStateException(
                "Agent '$targetAgent' not deployed on the platform"
            )

        val channel = AtmosphereOutputChannel(session)
        try {
            val options = ProcessOptions.DEFAULT.withOutputChannel(channel)
            platform.runAgentFrom(agent, options, mapOf("userMessage" to context.message()))
        } catch (e: Exception) {
            logger.error("Agent execution failed", e)
            session.error(e)
            return
        }
        session.complete()
    }

    override fun capabilities(): Set<AiCapability> = setOf(
        AiCapability.TEXT_STREAMING,
        AiCapability.AGENT_ORCHESTRATION,
        AiCapability.SYSTEM_PROMPT
    )
}
