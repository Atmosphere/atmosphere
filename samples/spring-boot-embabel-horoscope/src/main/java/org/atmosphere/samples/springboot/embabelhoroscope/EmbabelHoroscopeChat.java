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
package org.atmosphere.samples.springboot.embabelhoroscope;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.filter.ContentSafetyFilter;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI chat endpoint demonstrating Embabel's multi-step agent pipeline with
 * Atmosphere's real-time streaming and content safety.
 *
 * <h3>Atmosphere features showcased</h3>
 * <ul>
 *   <li><b>conversationMemory</b> — enabled so the horoscope agent remembers
 *       the user's zodiac sign and previous readings across turns</li>
 *   <li><b>{@link Ready @Ready}</b> — invoked when a client connects; logs the
 *       {@link Broadcaster} state and peer count</li>
 *   <li><b>{@link Disconnect @Disconnect}</b> — invoked when a client disconnects</li>
 *   <li><b>AtmosphereResource injection</b> — the 3-arg {@code @Prompt} method
 *       receives the resource for client identification and Broadcaster access</li>
 *   <li><b>Broadcaster</b> — {@code resource.getBroadcaster()} used to track
 *       connected clients and broadcast progress updates</li>
 *   <li>{@link ContentSafetyFilter} — blocks harmful content</li>
 *   <li>Step-by-step progress updates — shows each agent action as it executes</li>
 * </ul>
 *
 * <p>The horoscope agent performs a multi-step workflow:</p>
 * <ol>
 *   <li>Extract the person's zodiac sign from their query</li>
 *   <li>Find relevant celestial events / news</li>
 *   <li>Generate a personalized horoscope writeup</li>
 * </ol>
 *
 * <p>Ported from the official
 * <a href="https://github.com/embabel/embabel-agent-examples/tree/main/examples-java/horoscope">
 * embabel-agent-examples/horoscope</a>.</p>
 */
@AiEndpoint(path = "/atmosphere/embabel-horoscope",
        systemPrompt = "You are a mystical horoscope agent. "
                + "Extract the zodiac sign, find relevant celestial events, "
                + "and create a personalized horoscope.",
        conversationMemory = true,
        maxHistoryMessages = 10)
public class EmbabelHoroscopeChat {

    private static final Logger logger = LoggerFactory.getLogger(EmbabelHoroscopeChat.class);

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
     * Handles incoming prompts with {@link AtmosphereResource} injection.
     *
     * <p>The resource provides access to:</p>
     * <ul>
     *   <li>{@code resource.uuid()} — unique client identifier</li>
     *   <li>{@code resource.getBroadcaster()} — the {@link Broadcaster} for this
     *       endpoint, useful for broadcasting progress to all connected clients</li>
     * </ul>
     */
    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        var broadcaster = resource.getBroadcaster();
        logger.info("Prompt from {} (broadcaster: {}, peers: {}): {}",
                resource.uuid(), broadcaster.getID(),
                broadcaster.getAtmosphereResources().size(), message);

        var settings = AiConfig.get();
        if (settings == null || settings.client().apiKey() == null || settings.client().apiKey().isBlank()) {
            DemoResponseProducer.stream(message, session, resource.uuid());
            return;
        }

        session.stream(message);
    }
}
