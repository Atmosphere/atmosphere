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
package org.atmosphere.ai.langchain4j;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.AiSupport;
import org.atmosphere.ai.StreamingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

/**
 * {@link AiSupport} implementation backed by LangChain4j's
 * {@link StreamingChatLanguageModel}.
 *
 * <p>Auto-detected when {@code langchain4j-core} is on the classpath.
 * The model must be configured via {@link #setModel} — typically done
 * by application configuration or Spring auto-configuration.</p>
 */
public class LangChain4jAiSupport implements AiSupport {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jAiSupport.class);

    private static volatile StreamingChatLanguageModel model;

    @Override
    public String name() {
        return "langchain4j";
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("dev.langchain4j.model.chat.StreamingChatLanguageModel");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        if (model != null) {
            return;
        }

        var apiKey = settings.client().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }

        try {
            Class.forName("dev.langchain4j.model.openai.OpenAiStreamingChatModel");
        } catch (ClassNotFoundException e) {
            logger.info("langchain4j-open-ai not on classpath; add it or call setModel() manually");
            return;
        }

        setModel(dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
                .baseUrl(settings.baseUrl())
                .apiKey(apiKey)
                .modelName(settings.model())
                .build());
        logger.info("LangChain4j auto-configured: model={}, endpoint={}", settings.model(), settings.baseUrl());
    }

    /**
     * Set the {@link StreamingChatLanguageModel} to use for streaming.
     */
    public static void setModel(StreamingChatLanguageModel streamingModel) {
        model = streamingModel;
    }

    @Override
    public void stream(AiRequest request, StreamingSession session) {
        var streamingModel = model;
        if (streamingModel == null) {
            var settings = AiConfig.get();
            if (settings == null) {
                settings = AiConfig.fromEnvironment();
            }
            configure(settings);
            streamingModel = model;
        }
        if (streamingModel == null) {
            throw new IllegalStateException(
                    "LangChain4jAiSupport: StreamingChatLanguageModel not configured. "
                            + "Call LangChain4jAiSupport.setModel() or use Spring auto-configuration.");
        }

        session.progress("Connecting to AI model...");

        var messages = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
        if (request.systemPrompt() != null && !request.systemPrompt().isEmpty()) {
            messages.add(SystemMessage.from(request.systemPrompt()));
        }
        messages.add(UserMessage.from(request.message()));

        var chatRequest = ChatRequest.builder()
                .messages(messages)
                .build();

        var handler = new AtmosphereStreamingResponseHandler(session);
        streamingModel.chat(chatRequest, handler);
    }
}
