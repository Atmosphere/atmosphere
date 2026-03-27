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
package org.atmosphere.samples.springboot.aichat;

import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI chat endpoint using the built-in OpenAI-compatible client.
 *
 * <p>Demonstrates core Atmosphere annotations with {@code @AiEndpoint}:</p>
 * <ul>
 *   <li>{@link Ready @Ready} — invoked when a client connects and is suspended</li>
 *   <li>{@link Disconnect @Disconnect} — invoked when a client disconnects</li>
 *   <li>{@link Prompt @Prompt} — handles incoming user messages</li>
 * </ul>
 *
 * <p>In demo mode (no API key configured), falls back to simulated streaming.</p>
 */
@AiEndpoint(path = "/atmosphere/ai-chat",
        systemPromptResource = "prompts/system-prompt.md",
        requires = {AiCapability.TEXT_STREAMING, AiCapability.SYSTEM_PROMPT},
        conversationMemory = true)
public class AiChat {

    private static final Logger logger = LoggerFactory.getLogger(AiChat.class);

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Client {} connected (broadcaster: {})",
                resource.uuid(), resource.getBroadcaster().getID());
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("Client {} disconnected", event.getResource().uuid());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("Received prompt: {}", message);

        var settings = AiConfig.get();
        if (settings == null || settings.apiKey() == null || settings.apiKey().isBlank()) {
            DemoResponseProducer.stream(message, session);
            return;
        }

        session.stream(message);
    }
}
