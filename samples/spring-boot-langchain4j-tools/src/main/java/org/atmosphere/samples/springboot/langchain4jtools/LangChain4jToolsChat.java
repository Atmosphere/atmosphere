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
package org.atmosphere.samples.springboot.langchain4jtools;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.filter.CostMeteringFilter;
import org.atmosphere.ai.filter.PiiRedactionFilter;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.PathParam;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI chat endpoint demonstrating LangChain4j tool calling with Atmosphere's
 * real-time filter pipeline.
 *
 * <h3>Atmosphere features showcased</h3>
 * <ul>
 *   <li><b>Path template</b> — {@code /atmosphere/langchain4j-tools/{room}} creates
 *       per-room Broadcasters so clients in different rooms are isolated</li>
 *   <li><b>{@link PathParam @PathParam}</b> — the {@code room} field is automatically
 *       injected from the URL path via the unified injection framework</li>
 *   <li><b>{@link Ready @Ready}</b> — invoked when a client connects; logs the room
 *       and broadcaster state</li>
 *   <li><b>{@link Disconnect @Disconnect}</b> — invoked when a client disconnects</li>
 *   <li><b>AtmosphereResource injection</b> — the 3-arg {@code @Prompt} method
 *       receives the resource, giving access to the client UUID, request attributes,
 *       and the room's {@link Broadcaster}</li>
 *   <li><b>conversationMemory</b> — enabled with 30-message sliding window so the
 *       LLM remembers previous turns within a session</li>
 *   <li>{@link PiiRedactionFilter} — auto-redacts emails, phone numbers, SSNs</li>
 *   <li>{@link CostMeteringFilter} — tracks streaming text usage per session</li>
 * </ul>
 *
 * <p>Ported from the official
 * <a href="https://github.com/langchain4j/langchain4j-examples/tree/main/spring-boot-example">
 * langchain4j-examples/spring-boot-example</a>.</p>
 */
@AiEndpoint(path = "/atmosphere/ai-chat",
        systemPromptResource = "prompts/system-prompt.md",
        conversationMemory = true,
        maxHistoryMessages = 30)
public class LangChain4jToolsChat {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jToolsChat.class);

    @PathParam("room")
    private String room;

    @Ready
    public void onReady(AtmosphereResource resource) {
        var broadcaster = resource.getBroadcaster();
        logger.info("[room={}] Client {} connected (broadcaster: {}, peers: {})",
                room, resource.uuid(), broadcaster.getID(),
                broadcaster.getAtmosphereResources().size());
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("[room={}] Client {} disconnected",
                room, event.getResource().uuid());
    }

    /**
     * Handles incoming prompts. The 3-arg signature injects the
     * {@link AtmosphereResource}, providing access to:
     * <ul>
     *   <li>{@code resource.uuid()} — unique client identifier</li>
     *   <li>{@code resource.getBroadcaster()} — the per-room {@link Broadcaster}</li>
     *   <li>{@code room} — the {@link PathParam @PathParam}-injected room name</li>
     * </ul>
     */
    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        logger.info("[room={}] Prompt from client {}: {}", room, resource.uuid(), message);

        var settings = AiConfig.get();
        if (settings == null || settings.client().apiKey() == null || settings.client().apiKey().isBlank()) {
            DemoResponseProducer.stream(message, session, room, resource.uuid());
            return;
        }

        session.stream(message);
    }
}
