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
package org.atmosphere.room;

import java.util.Map;

/**
 * A non-connection-based room participant — for example, an AI agent, bot,
 * or server-side service that can receive room messages and respond.
 *
 * <p>Unlike {@link org.atmosphere.cpr.AtmosphereResource}, a virtual member
 * has no underlying WebSocket or HTTP connection. It participates in the room
 * purely through the {@link #onMessage} callback.</p>
 *
 * <pre>{@code
 * room.joinVirtual(new LlmRoomMember("assistant", model, settings));
 * room.broadcast("What is the weather?");
 * // The LLM virtual member receives the message and streams a response
 * // back to all human members via room.broadcast()
 * }</pre>
 *
 * @since 4.0
 * @see Room#joinVirtual(VirtualRoomMember)
 */
public interface VirtualRoomMember {

    /**
     * Stable identifier for this virtual member (e.g., "assistant", "bot-1").
     */
    String id();

    /**
     * Called when a message is broadcast in the room. The virtual member can
     * respond by broadcasting back into the room.
     *
     * <p>Implementations should be thread-safe — this method may be called
     * from multiple threads concurrently.</p>
     *
     * @param room     the room where the message was broadcast
     * @param senderId UUID of the sender (resource UUID or virtual member id)
     * @param message  the broadcast message
     */
    void onMessage(Room room, String senderId, Object message);

    /**
     * Optional metadata for presence events (e.g., display name, avatar).
     *
     * @return metadata map, never null
     */
    default Map<String, Object> metadata() {
        return Map.of();
    }
}
