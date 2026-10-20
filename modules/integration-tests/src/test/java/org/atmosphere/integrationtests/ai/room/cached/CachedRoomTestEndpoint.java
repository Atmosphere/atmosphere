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
package org.atmosphere.integrationtests.ai.room.cached;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.integrationtests.ai.room.RoomTurns;

/**
 * A {@code broadcastReply} room whose broadcaster caches what a member that
 * lost its connection misses ({@link UUIDBroadcasterCache}). Each prompt is a
 * turn {@link RoomTurns} streams.
 */
@AiEndpoint(path = CachedRoomTestEndpoint.PATH, broadcastReply = true, streamCache = UUIDBroadcasterCache.class)
public class CachedRoomTestEndpoint {

    public static final String PATH = "/atmosphere/room-cached";

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        RoomTurns.answer(message, session);
    }
}
