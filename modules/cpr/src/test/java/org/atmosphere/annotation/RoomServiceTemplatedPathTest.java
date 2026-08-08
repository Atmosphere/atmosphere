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
package org.atmosphere.annotation;

import org.atmosphere.config.service.RoomService;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.room.RoomManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that a templated {@code @RoomService} path names one room per path value.
 *
 * <p>The annotation documents {@code path = "/chat/{roomId}"}, but the processor
 * passed that string to {@code RoomManager.room(name)} unchanged — and that method
 * uses the name verbatim. Every {@code roomId} therefore shared a single room
 * literally named {@code /chat/&#123;roomId&#125;}: two classrooms saw each other's
 * members, presence and history. The advertised example did not do what it reads
 * as doing.</p>
 *
 * <p>Rooms under a templated path are now created per request as clients arrive,
 * so the assertion here is the inverse of the bug: after processing, no room
 * exists whose name still contains the template.</p>
 */
class RoomServiceTemplatedPathTest {

    @RoomService(path = "/classroom/{room}/chat", maxHistory = 25)
    public static class TemplatedRoom {
    }

    @RoomService(path = "/lobby", maxHistory = 10)
    public static class FixedRoom {
    }

    @SuppressWarnings("unchecked") // Processor<Object> is invoked with a concrete class literal
    @Test
    void aTemplatedPathDoesNotCreateARoomNamedAfterTheTemplate() throws Exception {
        var framework = new AtmosphereFramework();
        var manager = RoomManager.getOrCreate(framework);

        new RoomServiceProcessor().handle(framework, (Class<Object>) (Class<?>) TemplatedRoom.class);

        assertFalse(manager.exists("/classroom/{room}/chat"),
                "a room named after the raw template means every path value shares one "
                        + "room — the exact bug this pins. Rooms for a templated path are "
                        + "created per request, not at registration");
        assertTrue(manager.all().stream().noneMatch(r -> r.name().contains("{")),
                "no room should carry an unexpanded template in its name: "
                        + manager.all().stream().map(r -> r.name()).toList());
    }

    @SuppressWarnings("unchecked") // Processor<Object> is invoked with a concrete class literal
    @Test
    void aFixedPathStillCreatesItsRoomUpFront() throws Exception {
        var framework = new AtmosphereFramework();
        var manager = RoomManager.getOrCreate(framework);

        new RoomServiceProcessor().handle(framework, (Class<Object>) (Class<?>) FixedRoom.class);

        assertTrue(manager.exists("/lobby"),
                "a path with no template names exactly one room, and creating it at "
                        + "registration is what lets history be configured before the "
                        + "first client arrives");
    }
}
