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
package org.atmosphere.room.protocol;

import java.util.Map;

/**
 * Protocol messages exchanged between clients and the server for
 * room operations. This sealed hierarchy enables exhaustive pattern
 * matching in Java 21.
 *
 * @since 4.0
 */
public sealed interface RoomProtocolMessage {

    /**
     * The target room name.
     */
    String room();

    /**
     * Join a room with optional member metadata.
     *
     * @param room     the room to join
     * @param memberId application-level member identifier
     * @param metadata optional key-value pairs
     */
    record Join(String room, String memberId, Map<String, Object> metadata) implements RoomProtocolMessage {
        public Join {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /**
     * Leave a room.
     *
     * @param room the room to leave
     */
    record Leave(String room) implements RoomProtocolMessage {
    }

    /**
     * Broadcast a message to all room members.
     *
     * @param room the target room
     * @param data the message payload
     */
    record Broadcast(String room, Object data) implements RoomProtocolMessage {
    }

    /**
     * Send a direct message to a specific member by member ID.
     *
     * @param room     the room context
     * @param targetId the target member ID
     * @param data     the message payload
     */
    record Direct(String room, String targetId, Object data) implements RoomProtocolMessage {
    }
}
