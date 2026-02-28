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
import org.atmosphere.ai.AiConfig
import org.atmosphere.ai.AiRequest
import org.atmosphere.ai.AiSupport
import org.atmosphere.ai.StreamingSession
import org.slf4j.LoggerFactory

/**
 * [AiSupport] implementation backed by the Embabel Agent Platform.
 *
 * Auto-detected when `embabel-agent-api` is on the classpath.
 * The [AgentPlatform] and agent name must be configured via [setAgentPlatform]
 * and [setAgentName] — typically done by Spring auto-configuration.
 */
class EmbabelAiSupport : AiSupport {

    companion object {
        private val logger = LoggerFactory.getLogger(EmbabelAiSupport::class.java)

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
        // AgentPlatform is configured externally
    }

    override fun stream(request: AiRequest, session: StreamingSession) {
        val platform = agentPlatform
            ?: throw IllegalStateException(
                "EmbabelAiSupport: AgentPlatform not configured. " +
                    "Call EmbabelAiSupport.setAgentPlatform() or use Spring auto-configuration."
            )

        val targetAgent = request.hints()["agentName"]?.toString() ?: agentName

        session.progress("Starting agent: $targetAgent...")

        val agent = platform.agents().firstOrNull { it.name == targetAgent }
            ?: throw IllegalStateException(
                "Agent '$targetAgent' not deployed on the platform"
            )

        val channel = AtmosphereOutputChannel(session)
        try {
            val options = ProcessOptions.DEFAULT.withOutputChannel(channel)
            platform.runAgentFrom(agent, options, mapOf("userMessage" to request.message()))
        } catch (e: Exception) {
            logger.error("Agent execution failed", e)
            session.error(e)
            return
        }
        session.complete()
    }
}
