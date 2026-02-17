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

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.room.Room;
import org.atmosphere.room.RoomManager;
import org.atmosphere.room.RoomProtocolInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * Demonstrates Atmosphere 4.0 Rooms, Presence &amp; Protocol API.
 *
 * <p>Creates a {@link RoomManager}, registers the
 * {@link RoomProtocolInterceptor} that bridges the atmosphere.js room
 * protocol to the server-side Room API, and pre-provisions a
 * {@code "lobby"} room with message history.</p>
 */
@Configuration
public class RoomsConfig {

    private static final Logger logger = LoggerFactory.getLogger(RoomsConfig.class);

    private final AtmosphereFramework framework;

    public RoomsConfig(AtmosphereFramework framework) {
        this.framework = framework;
    }

    @Bean
    public RoomManager roomManager() {
        return RoomManager.getOrCreate(framework);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void setupRooms() {
        // Register the protocol interceptor so clients can send
        // join/leave/broadcast/direct JSON messages
        var interceptor = new RoomProtocolInterceptor();
        interceptor.configure(framework.getAtmosphereConfig());
        framework.interceptor(interceptor);

        RoomManager manager = roomManager();
        Room lobby = manager.room("lobby");

        // Enable message history — new joiners get the last 50 messages
        lobby.enableHistory(50);

        // Log presence events with member info
        lobby.onPresence(event -> {
            var memberInfo = event.memberInfo();
            var memberId = memberInfo != null ? memberInfo.id() : event.member().uuid();
            logger.info("Room '{}': {} {} (members: {})",
                    event.room().name(),
                    memberId,
                    event.type(),
                    event.room().size());
        });

        logger.info("Atmosphere Rooms ready — lobby room created with history, {} total",
                manager.count());
    }
}
