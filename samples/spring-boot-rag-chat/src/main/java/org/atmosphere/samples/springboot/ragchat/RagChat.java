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
package org.atmosphere.samples.springboot.ragchat;

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
 * RAG-enabled AI chat endpoint.
 *
 * <p>Demonstrates context-augmented generation using Atmosphere's
 * {@code @AiEndpoint} with registered {@link org.atmosphere.ai.ContextProvider}
 * instances. The context providers (configured in {@link VectorStoreConfig})
 * automatically enrich user messages with relevant documents from the
 * knowledge base before the LLM call.</p>
 *
 * <p>The RAG pipeline is handled automatically by the framework:</p>
 * <ol>
 *   <li>User sends a message</li>
 *   <li>Context providers retrieve relevant documents</li>
 *   <li>Documents are appended to the prompt</li>
 *   <li>LLM generates a response grounded in the retrieved context</li>
 *   <li>Response streams back to the client</li>
 * </ol>
 */
@AiEndpoint(path = "/atmosphere/rag-chat",
        systemPromptResource = "prompts/rag-system-prompt.md")
public class RagChat {

    private static final Logger logger = LoggerFactory.getLogger(RagChat.class);

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
        logger.info("Received RAG prompt: {}", message);

        var settings = AiConfig.get();
        if (settings == null || settings.client().apiKey() == null || settings.client().apiKey().isBlank()) {
            DemoResponseProducer.stream(message, session);
            return;
        }

        session.stream(message);
    }
}
