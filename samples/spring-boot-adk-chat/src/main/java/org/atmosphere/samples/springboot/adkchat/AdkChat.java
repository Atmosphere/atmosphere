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

import jakarta.inject.Inject;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.adk.AdkEventAdapter;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Heartbeat;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

/**
 * Atmosphere managed service that bridges ADK agent responses to WebSocket clients.
 *
 * <p>When a user sends a message, a simulated ADK event stream is created and
 * bridged to all connected clients via {@link AdkEventAdapter}. Each streaming
 * token is broadcast through the Broadcaster so every browser sees the
 * conversation in real-time — just like the spring-boot-chat sample.</p>
 */
@ManagedService(path = "/atmosphere/adk-chat", atmosphereConfig = {
        MAX_INACTIVE + "=120000"
})
public class AdkChat {

    private static final Logger logger = LoggerFactory.getLogger(AdkChat.class);

    @Inject
    private AtmosphereResource resource;

    @Inject
    private AtmosphereResourceEvent event;

    @Heartbeat
    public void onHeartbeat(final AtmosphereResourceEvent event) {
        logger.trace("Heartbeat from {}", event.getResource());
    }

    @Ready
    public void onReady() {
        logger.info("Client {} connected to ADK chat", resource.uuid());
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

        // Broadcast streaming tokens to ALL connected clients via the Broadcaster,
        // same pattern as the spring-boot-chat sample.
        var session = StreamingSessions.start(resource);

        var events = DemoEventProducer.stream(userMessage);
        AdkEventAdapter.bridge(events, session);
    }
}
