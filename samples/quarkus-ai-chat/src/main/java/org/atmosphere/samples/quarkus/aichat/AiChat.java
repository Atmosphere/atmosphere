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
package org.atmosphere.samples.quarkus.aichat;

import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI chat endpoint backed by Quarkus LangChain4j.
 *
 * <p>The {@code atmosphere-quarkus-langchain4j} extension auto-wires the
 * Quarkus-supplied {@code StreamingChatModel} CDI bean into Atmosphere's
 * {@code LangChain4jAgentRuntime} on app startup, so this handler only
 * needs the {@link Prompt @Prompt} method — the rest is identical to the
 * Spring Boot AI chat sample, proving the SPI is platform-portable.</p>
 */
@AiEndpoint(
        path = "/atmosphere/ai-chat",
        requires = {AiCapability.TEXT_STREAMING})
@AgentScope(unrestricted = true,
        justification = "Quarkus LangChain4j bridge demo — intentionally accepts arbitrary "
                + "prompts to exercise the SPI auto-wiring path. Production deployments should "
                + "replace with a scoped @AgentScope declaring purpose + forbiddenTopics.")
public class AiChat {

    private static final Logger logger = LoggerFactory.getLogger(AiChat.class);

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Client {} connected", resource.uuid());
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("Client {} disconnected", event.getResource().uuid());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("Received prompt: {}", message);
        session.stream(message);
    }
}
