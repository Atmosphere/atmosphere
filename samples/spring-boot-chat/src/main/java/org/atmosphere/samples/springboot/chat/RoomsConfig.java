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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * Demonstrates Atmosphere 4.0 Rooms &amp; Presence API.
 *
 * <p>Creates a {@link RoomManager} and pre-provisions the default
 * {@code "lobby"} room. The {@link ChatRooms} handler listens for
 * presence events and logs join/leave activity.</p>
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
        return RoomManager.create(framework);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void setupRooms() {
        RoomManager manager = roomManager();
        Room lobby = manager.room("lobby");
        lobby.onPresence(event -> logger.info("Room '{}': {} {}",
                event.room().name(),
                event.member().uuid(),
                event.type()));
        logger.info("Atmosphere Rooms ready — lobby room created, {} total", manager.count());
    }
}
