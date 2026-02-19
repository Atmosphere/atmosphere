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
package org.atmosphere.samples.springboot.langchain4jchat;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import jakarta.inject.Inject;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.PromptLoader;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.langchain4j.LangChain4jStreamingAdapter;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_CACHE;
import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

/**
 * Atmosphere managed service that handles AI chat via LangChain4j streaming.
 * Uses {@link LangChain4jStreamingAdapter} to bridge LangChain4j's callback-based
 * streaming to Atmosphere's real-time transport.
 */
@ManagedService(path = "/atmosphere/langchain4j-chat", atmosphereConfig = {
        MAX_INACTIVE + "=120000",
        BROADCASTER_CACHE + "=org.atmosphere.cache.DefaultBroadcasterCache"
})
public class LangChain4jChat {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jChat.class);
    private static final String SYSTEM_PROMPT = PromptLoader.load("prompts/system-prompt.md");

    private final LangChain4jStreamingAdapter adapter = new LangChain4jStreamingAdapter();

    @Inject
    private AtmosphereResource resource;

    @Inject
    private AtmosphereResourceEvent event;

    @Ready
    public void onReady() {
        logger.info("Client {} connected to LangChain4j chat", resource.uuid());
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

        var chatRequest = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(SYSTEM_PROMPT),
                        UserMessage.from(userMessage)
                ))
                .build();

        // Stream on a virtual thread to avoid blocking the Atmosphere thread pool
        Thread.startVirtualThread(() -> {
            try {
                // Get the model from Spring context via static accessor
                var model = getStreamingModel(settings);
                adapter.stream(model, chatRequest, session);
            } catch (Exception e) {
                logger.error("Failed to stream LangChain4j response", e);
                session.error(e);
            }
        });
    }

    private StreamingChatLanguageModel getStreamingModel(AiConfig.LlmSettings settings) {
        return dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
                .baseUrl(settings.baseUrl())
                .apiKey(settings.client().apiKey())
                .modelName(settings.model())
                .build();
    }
}
