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
package org.atmosphere.samples.springboot.adkchat;

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
 * AI chat endpoint powered by Google ADK (auto-detected via classpath).
 *
 * <p>Demonstrates core Atmosphere annotations with {@code @AiEndpoint}:</p>
 * <ul>
 *   <li>{@link Ready @Ready} — invoked when a client connects and is suspended</li>
 *   <li>{@link Disconnect @Disconnect} — invoked when a client disconnects</li>
 *   <li>{@link Prompt @Prompt} — handles incoming user messages</li>
 * </ul>
 *
 * <p>In demo mode (no ADK Runner configured), falls back to simulated ADK events
 * via {@link DemoEventProducer}.</p>
 */
@AiEndpoint(path = "/atmosphere/ai-chat",
        systemPrompt = "You are a helpful assistant.")
public class AdkChat {

    private static final Logger logger = LoggerFactory.getLogger(AdkChat.class);

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
        if (settings == null || settings.client().apiKey() == null || settings.client().apiKey().isBlank()) {
            // Stream demo response directly via session.send() — bypasses ADK bridge
            // in demo mode for reliable delivery in all environments.
            // In real mode (API key set), session.stream() uses the full ADK pipeline.
            DemoResponseProducer.stream(message, session);
            return;
        }

        session.stream(message);
    }
}
