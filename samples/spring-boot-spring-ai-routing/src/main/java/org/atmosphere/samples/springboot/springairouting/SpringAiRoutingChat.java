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
package org.atmosphere.samples.springboot.springairouting;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.filter.ContentSafetyFilter;
import org.atmosphere.ai.filter.CostMeteringFilter;
import org.atmosphere.ai.routing.RoutingLlmClient;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.PathParam;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI chat endpoint demonstrating prompt routing with Spring AI and Atmosphere.
 *
 * <h3>Atmosphere features showcased</h3>
 * <ul>
 *   <li><b>Path template</b> — {@code /atmosphere/spring-ai-routing/{topic}} creates
 *       topic-scoped Broadcasters (e.g. {@code /atmosphere/spring-ai-routing/code},
 *       {@code /atmosphere/spring-ai-routing/creative})</li>
 *   <li><b>{@link PathParam @PathParam}</b> — the {@code topic} field is automatically
 *       injected from the URL path</li>
 *   <li><b>{@link Ready @Ready}</b> — invoked when a client connects; logs the topic
 *       and {@link Broadcaster} state</li>
 *   <li><b>{@link Disconnect @Disconnect}</b> — invoked when a client disconnects</li>
 *   <li><b>AtmosphereResource injection</b> — access to the client's Broadcaster,
 *       UUID, and the {@code {topic}} path parameter</li>
 *   <li><b>conversationMemory</b> — enabled so multi-turn routing context is preserved</li>
 *   <li>{@link RoutingLlmClient} — routes prompts to different models based on content</li>
 *   <li>{@link ContentSafetyFilter} — blocks harmful content before it reaches users</li>
 *   <li>{@link CostMeteringFilter} — tracks streaming text usage per session</li>
 * </ul>
 *
 * <p>Ported from the official
 * <a href="https://github.com/spring-projects/spring-ai-examples/tree/main/agentic-patterns/routing-workflow">
 * spring-ai-examples/agentic-patterns/routing-workflow</a>.</p>
 */
@AiEndpoint(path = "/atmosphere/spring-ai-routing/{topic}",
        systemPrompt = "You are a helpful assistant that routes questions to specialized models. "
                + "Code questions go to a code-specialized model, creative writing goes to a creative model, "
                + "and general questions use the default model.",
        conversationMemory = true,
        maxHistoryMessages = 20)
public class SpringAiRoutingChat {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiRoutingChat.class);

    @PathParam("topic")
    private String topic;

    @Ready
    public void onReady(AtmosphereResource resource) {
        var broadcaster = resource.getBroadcaster();
        logger.info("[topic={}] Client {} connected (broadcaster: {}, peers: {})",
                topic, resource.uuid(), broadcaster.getID(),
                broadcaster.getAtmosphereResources().size());
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("[topic={}] Client {} disconnected",
                topic, event.getResource().uuid());
    }

    /**
     * Handles incoming prompts. The {@code {topic}} path parameter is available
     * via the {@link PathParam @PathParam}-injected field. This allows the routing
     * logic to consider the topic context when selecting a model.
     */
    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        var broadcaster = resource.getBroadcaster();
        logger.info("[topic={}] Prompt from {} (broadcaster: {}): {}",
                topic, resource.uuid(), broadcaster.getID(), message);

        var settings = AiConfig.get();
        if (settings == null || settings.client().apiKey() == null || settings.client().apiKey().isBlank()) {
            DemoResponseProducer.stream(message, session, topic, resource.uuid());
            return;
        }

        session.stream(message);
    }
}
