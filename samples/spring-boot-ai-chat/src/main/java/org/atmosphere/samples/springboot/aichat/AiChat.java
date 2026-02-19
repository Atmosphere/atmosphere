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

import jakarta.inject.Inject;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.PromptLoader;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_CACHE;
import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

/**
 * Atmosphere managed service that handles AI chat over WebSocket.
 * When a user sends a message, it streams the LLM response back
 * token-by-token through the Atmosphere broadcaster.
 */
@ManagedService(path = "/atmosphere/ai-chat", atmosphereConfig = {
        MAX_INACTIVE + "=120000",
        BROADCASTER_CACHE + "=org.atmosphere.cache.DefaultBroadcasterCache"
})
public class AiChat {

    private static final Logger logger = LoggerFactory.getLogger(AiChat.class);
    private static final String SYSTEM_PROMPT = PromptLoader.load("prompts/system-prompt.md");

    @Inject
    private AtmosphereResource resource;

    @Inject
    private AtmosphereResourceEvent event;

    @Ready
    public void onReady() {
        logger.info("Client {} connected to AI chat", resource.uuid());
    }

    @Disconnect
    public void onDisconnect() {
        if (event.isCancelled()) {
            logger.info("Client {} unexpectedly disconnected", event.getResource().uuid());
        } else {
            logger.info("Client {} disconnected", event.getResource().uuid());
        }
    }

    @org.atmosphere.config.service.Message
    public void onMessage(String userMessage) {
        logger.info("Received prompt from {}: {}", resource.uuid(), userMessage);

        var settings = AiConfig.get();
        var session = StreamingSessions.start(resource);

        var request = ChatCompletionRequest.builder(settings.model())
                .system(SYSTEM_PROMPT)
                .user(userMessage)
                .build();

        // Stream on a virtual thread to avoid blocking the Atmosphere thread pool
        Thread.startVirtualThread(() -> settings.client().streamChatCompletion(request, session));
    }
}
