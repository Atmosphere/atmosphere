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
package org.atmosphere.samples.springboot.embabelchat

import org.atmosphere.ai.AiCapability
import org.atmosphere.ai.AiConfig
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.annotation.AgentScope
import org.atmosphere.ai.annotation.AiEndpoint
import org.atmosphere.ai.annotation.Prompt
import org.atmosphere.config.service.Disconnect
import org.atmosphere.config.service.Ready
import org.atmosphere.cpr.AtmosphereResource
import org.atmosphere.cpr.AtmosphereResourceEvent
import org.slf4j.LoggerFactory

/**
 * AI chat endpoint powered by Embabel's GOAP AgentPlatform.
 *
 * When `embabel-agent-starter-platform` is on the classpath, the
 * `EmbabelAgentRuntime` SPI is auto-detected. Embabel handles agent
 * planning, action selection, and LLM streaming; Atmosphere handles
 * the real-time WebSocket transport to the browser.
 */
@AiEndpoint(
    path = "/atmosphere/ai-chat",
    requires = [AiCapability.TEXT_STREAMING],
    conversationMemory = true,
)
@AgentScope(
    unrestricted = true,
    justification = "Embabel runtime demo — accepts arbitrary prompts to showcase " +
        "Embabel AgentRuntime integration.",
)
open class EmbabelChat {

    private val logger = LoggerFactory.getLogger(EmbabelChat::class.java)

    @Ready
    fun onReady(resource: AtmosphereResource) {
        logger.info("Client {} connected via Embabel runtime", resource.uuid())
    }

    @Disconnect
    fun onDisconnect(event: AtmosphereResourceEvent) {
        logger.info("Client {} disconnected", event.resource.uuid())
    }

    @Prompt
    fun onPrompt(message: String, session: StreamingSession) {
        logger.info("Received prompt: {}", message)

        val settings = AiConfig.get()
        if (settings == null || settings.apiKey().isNullOrBlank()) {
            DemoResponseProducer.stream(message, session)
            return
        }

        session.stream(message)
    }
}
