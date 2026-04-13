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
package org.atmosphere.samples.springboot.skchat;

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
 * AI chat endpoint powered by Microsoft Semantic Kernel for Java.
 *
 * <p>When {@code atmosphere-semantic-kernel} is on the classpath and a
 * {@link com.microsoft.semantickernel.services.chatcompletion.ChatCompletionService}
 * bean is defined, the Atmosphere
 * {@code SemanticKernelAgentRuntime} is auto-discovered via the
 * {@code AgentRuntime} {@code ServiceLoader} SPI and its auto-configuration
 * wires the service in. SK's {@code getStreamingChatMessageContentsAsync}
 * returns a Reactor {@code Flux<StreamingChatContent<?>>} which the adapter
 * drains into Atmosphere {@code AiEvent}s on the wire.</p>
 *
 * <p>Configure the SK {@code OpenAIAsyncClient} endpoint/key through the
 * {@code llm.*} properties in {@code application.yml}.</p>
 */
@AiEndpoint(path = "/atmosphere/ai-chat",
        systemPrompt = "You are a concise, friendly assistant powered by "
                + "Microsoft Semantic Kernel for Java.",
        requires = {AiCapability.TEXT_STREAMING, AiCapability.SYSTEM_PROMPT},
        conversationMemory = true)
public class SkChat {

    private static final Logger logger = LoggerFactory.getLogger(SkChat.class);

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Client {} connected via Semantic Kernel runtime", resource.uuid());
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
