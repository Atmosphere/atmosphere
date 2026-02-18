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
package org.atmosphere.samples.springboot.embabelchat;

import jakarta.inject.Inject;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_CACHE;
import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

/**
 * Atmosphere managed service for Embabel agent chat.
 * Delegates to {@link AgentRunner} which uses the Embabel {@code AgentPlatform}
 * to run agents and stream events to the browser.
 */
@ManagedService(path = "/atmosphere/embabel-chat", atmosphereConfig = {
        MAX_INACTIVE + "=120000",
        BROADCASTER_CACHE + "=org.atmosphere.cache.DefaultBroadcasterCache"
})
public class EmbabelChat {

    private static final Logger logger = LoggerFactory.getLogger(EmbabelChat.class);

    @Inject
    private AtmosphereResource resource;

    @Inject
    private AtmosphereResourceEvent event;

    @Inject
    private AgentRunner agentRunner;

    @Ready
    public void onReady() {
        logger.info("Client {} connected to Embabel agent chat", resource.uuid());
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
        logger.info("Received prompt from {}: {}", resource.uuid(), userMessage);
        agentRunner.run(userMessage, resource);
    }
}
