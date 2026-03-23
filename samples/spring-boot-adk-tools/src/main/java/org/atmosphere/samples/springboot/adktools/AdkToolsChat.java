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
package org.atmosphere.samples.springboot.adktools;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.adk.AdkEventAdapter;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.budget.StreamingTextBudgetManager;
import org.atmosphere.ai.cache.AiResponseCacheInspector;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI chat endpoint demonstrating Google ADK tool calling with Atmosphere's
 * streaming text budget management and response caching.
 *
 * <h3>Atmosphere features showcased</h3>
 * <ul>
 *   <li><b>{@link Ready @Ready}</b> — invoked when a client connects; logs the
 *       {@link Broadcaster} state and peer count</li>
 *   <li><b>{@link Disconnect @Disconnect}</b> — invoked when a client disconnects</li>
 *   <li><b>AtmosphereResource injection</b> — the 3-arg {@code @Prompt} signature
 *       injects the resource, providing access to the client UUID, Broadcaster,
 *       and request attributes</li>
 *   <li><b>conversationMemory</b> — enabled with default 20-message window so the
 *       ADK agent remembers context across turns</li>
 *   <li><b>Broadcaster access</b> — {@code resource.getBroadcaster()} is used to
 *       log connected peers and broadcast metadata</li>
 *   <li>{@link StreamingTextBudgetManager} — per-user streaming text budget with graceful degradation</li>
 *   <li>{@link AiResponseCacheInspector} — caches completed responses for replay</li>
 * </ul>
 *
 * <p>Ported from the official
 * <a href="https://github.com/google/adk-java/tree/main/tutorials/city-time-weather">
 * adk-java/tutorials/city-time-weather</a>.</p>
 */
@AiEndpoint(path = "/atmosphere/ai-chat",
        systemPrompt = "You are a helpful assistant with access to time and weather tools. "
                + "Use the tools to answer questions about the current time and weather in cities.",
        conversationMemory = true)
public class AdkToolsChat {

    private static final Logger logger = LoggerFactory.getLogger(AdkToolsChat.class);

    @Ready
    public void onReady(AtmosphereResource resource) {
        var broadcaster = resource.getBroadcaster();
        logger.info("Client {} connected (broadcaster: {}, peers: {})",
                resource.uuid(), broadcaster.getID(),
                broadcaster.getAtmosphereResources().size());
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("Client {} disconnected", event.getResource().uuid());
    }

    /**
     * Handles incoming prompts with full {@link AtmosphereResource} injection.
     *
     * <p>The resource provides access to:</p>
     * <ul>
     *   <li>{@code resource.uuid()} — unique client identifier for budget tracking</li>
     *   <li>{@code resource.getBroadcaster()} — the {@link Broadcaster} managing this
     *       endpoint's connected clients</li>
     *   <li>{@code resource.getRequest()} — the underlying HTTP request with headers
     *       and attributes</li>
     * </ul>
     */
    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        var broadcaster = resource.getBroadcaster();
        logger.info("Prompt from client {} (broadcaster: {}, peers: {}): {}",
                resource.uuid(), broadcaster.getID(),
                broadcaster.getAtmosphereResources().size(), message);

        var settings = AiConfig.get();
        if (settings == null || settings.client().apiKey() == null || settings.client().apiKey().isBlank()) {
            // Emit tool events so the frontend ToolActivity panel shows activity
            var toolName = DemoEventProducer.detectTool(message);
            if (toolName != null) {
                var toolArgs = DemoEventProducer.buildToolArgs(toolName, message);
                session.emit(new AiEvent.ToolStart(toolName, toolArgs));
                session.emit(new AiEvent.ToolResult(toolName, java.util.Map.of("status", "success")));
            }
            var events = DemoEventProducer.stream(message, resource.uuid());
            AdkEventAdapter.bridge(events, session);
            return;
        }

        session.stream(message);
    }
}
