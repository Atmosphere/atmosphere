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
package org.atmosphere.samples.springboot.aitools;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.PathParam;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI chat endpoint demonstrating Atmosphere's framework-agnostic {@code @AiTool} pipeline.
 *
 * <h3>Key difference from the LangChain4j tools sample</h3>
 * <p>The {@code spring-boot-langchain4j-tools} sample uses LangChain4j's native
 * {@code @Tool} annotation, which locks you into LangChain4j. This sample uses
 * Atmosphere's {@code @AiTool} annotation via the {@code tools} attribute, making
 * the tools portable across all supported AI backends.</p>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>{@code @AiEndpoint(tools = AssistantTools.class)} tells the framework to
 *       scan {@code AssistantTools} for {@code @AiTool}-annotated methods</li>
 *   <li>Tools are registered in the global {@code ToolRegistry}</li>
 *   <li>When {@code session.stream()} is called, tools are attached to the
 *       {@code AiRequest} and bridged to the active backend's native format</li>
 *   <li>The backend handles the tool call loop (Spring AI and ADK automatically,
 *       LangChain4j via {@code ToolAwareStreamingResponseHandler})</li>
 * </ol>
 */
@AiEndpoint(path = "/atmosphere/langchain4j-tools/{room}",
        systemPromptResource = "prompts/system-prompt.md",
        conversationMemory = true,
        maxHistoryMessages = 30,
        tools = AssistantTools.class)
public class AiToolsChat {

    private static final Logger logger = LoggerFactory.getLogger(AiToolsChat.class);

    @PathParam("room")
    private String room;

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("[room={}] Client {} connected (peers: {})",
                room, resource.uuid(),
                resource.getBroadcaster().getAtmosphereResources().size());
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("[room={}] Client {} disconnected",
                room, event.getResource().uuid());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        logger.info("[room={}] Prompt from {}: {}", room, resource.uuid(), message);

        var settings = AiConfig.get();
        if (settings == null || settings.client().apiKey() == null || settings.client().apiKey().isBlank()) {
            DemoResponseProducer.stream(message, session, room);
            return;
        }

        session.stream(message);
    }
}
