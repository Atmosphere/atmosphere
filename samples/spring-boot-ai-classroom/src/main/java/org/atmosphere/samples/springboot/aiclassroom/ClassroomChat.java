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
package org.atmosphere.samples.springboot.aiclassroom;

import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Message;
import org.atmosphere.config.service.PathParam;
import org.atmosphere.config.service.Ready;
import org.atmosphere.config.service.RoomService;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Student-to-student chat for one classroom, alongside the AI stream.
 *
 * <p>{@link AiClassroom} carries the tutor: a student asks, and everyone in the
 * room watches the answer stream. This carries the students talking to each
 * other — and it is a {@code @RoomService} rather than another
 * {@code @ManagedService} because a classroom wants what a room provides and a
 * bare broadcaster does not: membership, presence, and replayable history.</p>
 *
 * <p>History is the reason this is worth a separate channel. A student who joins
 * late gets the last {@value #HISTORY} messages replayed, so the conversation
 * they walked into makes sense. A broadcaster keeps nothing.</p>
 *
 * <p>The path is templated, so {@code /classroom/math/chat} and
 * {@code /classroom/history/chat} are genuinely separate rooms with separate
 * membership and separate history — the framework resolves one room per path
 * value as clients arrive.</p>
 */
@RoomService(path = "/atmosphere/classroom/{room}/chat", maxHistory = ClassroomChat.HISTORY)
public class ClassroomChat {

    /** Messages replayed to a student who joins an in-progress conversation. */
    public static final int HISTORY = 50;

    private static final Logger logger = LoggerFactory.getLogger(ClassroomChat.class);

    @PathParam("room")
    private String room;

    @Ready
    public void onJoin(AtmosphereResource resource) {
        logger.info("Student {} joined the chat for room '{}'", resource.uuid(), room);
    }

    /**
     * Relay a student's message to the rest of the room.
     *
     * <p>Returning the message broadcasts it to every member — the room handles
     * fan-out and records it in history for whoever arrives next.</p>
     *
     * @param message what the student typed
     * @return the same message, broadcast to the room
     */
    @Message
    public String onMessage(String message) {
        logger.info("Chat in room '{}': {}", room, message);
        return message;
    }

    @Disconnect
    public void onLeave(AtmosphereResourceEvent event) {
        var resource = event.getResource();
        if (resource == null) {
            logger.debug("Ignoring chat disconnect for room '{}' — no resource on the event", room);
            return;
        }
        logger.info("Student {} left the chat for room '{}'", resource.uuid(), room);
    }
}
