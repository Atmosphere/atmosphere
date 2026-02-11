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
package org.atmosphere.samples.springboot.chat;

import java.io.IOException;

import jakarta.inject.Inject;

import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Heartbeat;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.BroadcasterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

@ManagedService(path = "/atmosphere/chat", atmosphereConfig = MAX_INACTIVE + "=120000")
public class Chat {

    private final Logger logger = LoggerFactory.getLogger(Chat.class);

    @Inject
    private BroadcasterFactory factory;

    @Inject
    private AtmosphereResource r;

    @Inject
    private AtmosphereResourceEvent event;

    @Heartbeat
    public void onHeartbeat(final AtmosphereResourceEvent event) {
        logger.trace("Heartbeat send by {}", event.getResource());
    }

    @Ready
    public void onReady() {
        logger.info("Browser {} connected", r.uuid());
        logger.info("BroadcasterFactory used {}", factory.getClass().getName());
    }

    @Disconnect
    public void onDisconnect() {
        if (event.isCancelled()) {
            logger.info("Browser {} unexpectedly disconnected", event.getResource().uuid());
        } else if (event.isClosedByClient()) {
            logger.info("Browser {} closed the connection", event.getResource().uuid());
        }
    }

    @org.atmosphere.config.service.Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) throws IOException {
        logger.info("{} just sent {}", message.getAuthor(), message.getMessage());
        return message;
    }
}
