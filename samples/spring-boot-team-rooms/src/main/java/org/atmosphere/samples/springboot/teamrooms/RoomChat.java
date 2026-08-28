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
package org.atmosphere.samples.springboot.teamrooms;

import jakarta.inject.Inject;

import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Get;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Message;
import org.atmosphere.config.service.PathParam;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One endpoint, every room. The {@code {room}} segment is a path template, so
 * {@code /atmosphere/rooms/build} and {@code /atmosphere/rooms/incident} are separate
 * broadcasters with separate membership and separate replay history — without a line
 * of routing code.
 *
 * <p>Deliberately <strong>not</strong> {@code @Singleton}: a templated service without
 * {@code @Singleton} gets one instance per resolved path, which is what makes the
 * {@link PathParam} field below safe to hold as state. Marking this class
 * {@code @Singleton} would share one instance across every room and every connection,
 * and {@code room} would be whatever the last request happened to write. See
 * {@link Announcements} for the case where {@code @Singleton} is the right answer.</p>
 */
@ManagedService(path = "/atmosphere/rooms/{room}")
public class RoomChat {

    private static final Logger logger = LoggerFactory.getLogger(RoomChat.class);

    @PathParam("room")
    private String room;

    @Inject
    private AtmosphereResource resource;

    @Inject
    private AtmosphereResourceEvent event;

    @Ready
    public void onReady() {
        logger.info("{} joined room {}", resource.uuid(), room);
    }

    /**
     * Answers a plain HTTP GET on the same path the WebSocket uses — the room name it
     * echoes is the resolved path parameter, which is the cheapest proof that
     * {@code @PathParam} actually resolved.
     */
    @Get
    public void onGet(AtmosphereResource r) {
        logger.debug("GET on room {} from {}", room, r.uuid());
    }

    @Message(encoders = JacksonEncoder.class, decoders = JacksonDecoder.class)
    public org.atmosphere.samples.springboot.teamrooms.Message onMessage(
            org.atmosphere.samples.springboot.teamrooms.Message message) {
        // The client never decides which room it posted to — the path did.
        org.atmosphere.samples.springboot.teamrooms.Message stamped = message.stamped(room);
        logger.info("[{}] {}: {}", stamped.room(), stamped.author(), stamped.text());
        return stamped;
    }

    @Disconnect
    public void onDisconnect() {
        if (event.isCancelled()) {
            logger.info("{} dropped from room {}", event.getResource().uuid(), room);
        } else if (event.isClosedByClient()) {
            logger.info("{} left room {}", event.getResource().uuid(), room);
        }
    }
}
