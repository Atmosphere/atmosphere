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
package org.atmosphere.integrationtests.ai.room.uncached;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.integrationtests.ai.room.RoomTurns;

/**
 * A {@code broadcastReply} room with no broadcaster cache: a member that
 * reconnects with the run id of a turn is written what it missed of it by the
 * run's replay only. Each prompt is a turn {@link RoomTurns} streams.
 */
@AiEndpoint(path = UncachedRoomTestEndpoint.PATH, broadcastReply = true)
public class UncachedRoomTestEndpoint {

    public static final String PATH = "/atmosphere/room-uncached";

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        RoomTurns.answer(message, session);
    }
}
