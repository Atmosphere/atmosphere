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
package org.atmosphere.samples.springboot.springaichat;

import jakarta.inject.Inject;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.spring.SpringAiStreamingAdapter;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

/**
 * Atmosphere managed service that streams Spring AI responses over WebSocket.
 *
 * <p>This sample demonstrates the {@link SpringAiStreamingAdapter} — the bridge
 * between Spring AI's {@link ChatClient} and Atmosphere's {@link org.atmosphere.ai.StreamingSession}.
 * Users keep their Spring AI code (ChatClient, Advisors) and get real-time
 * WebSocket push for free.</p>
 */
@ManagedService(path = "/atmosphere/spring-ai-chat", atmosphereConfig = {
        MAX_INACTIVE + "=120000"
})
public class SpringAiChat {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiChat.class);

    private static final boolean DEMO_MODE;

    static {
        var apiKey = System.getenv("OPENAI_API_KEY");
        DEMO_MODE = apiKey == null || apiKey.isBlank() || "demo".equals(apiKey);
    }

    @Inject
    private AtmosphereResource resource;

    @Inject
    private AtmosphereResourceEvent event;

    @Ready
    public void onReady() {
        logger.info("Client {} connected to Spring AI chat", resource.uuid());
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
        logger.info("Received from {}: {}", resource.uuid(), userMessage);

        var session = StreamingSessions.start(resource);

        if (DEMO_MODE) {
            Thread.startVirtualThread(() -> DemoResponseProducer.stream(userMessage, session));
            return;
        }

        // Use Spring AI's ChatClient via the SpringAiStreamingAdapter.
        // The ChatClient bean is auto-configured by spring-ai-starter-model-openai
        // and the adapter is auto-configured by atmosphere-spring-ai.
        Thread.startVirtualThread(() -> {
            var chatClient = SpringBeanAccessor.getBean(ChatClient.Builder.class).build();
            var adapter = SpringBeanAccessor.getBean(SpringAiStreamingAdapter.class);
            adapter.stream(chatClient, userMessage, session);
        });
    }
}
