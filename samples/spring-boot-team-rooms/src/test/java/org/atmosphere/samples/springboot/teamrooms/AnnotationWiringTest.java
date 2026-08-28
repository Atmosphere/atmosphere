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

import java.lang.reflect.Field;

import org.atmosphere.config.service.DeliverTo;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.PathParam;
import org.atmosphere.config.service.Singleton;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the annotation wiring this sample exists to demonstrate.
 *
 * <p>The {@code @Singleton} assertions are the load-bearing ones. A templated
 * {@code @ManagedService} that is also {@code @Singleton} shares one instance across every
 * resolved path, and {@code ManagedServiceInterceptor.mapAnnotatedService} skips the
 * per-request instantiation for it — so the {@code @PathParam} field becomes whatever the
 * last request wrote. If someone "tidies up" by adding {@code @Singleton} to
 * {@link RoomChat}, this test is what stops it.</p>
 */
class AnnotationWiringTest {

    @Test
    void roomChatIsTemplatedSoEachRoomIsItsOwnBroadcaster() {
        ManagedService ms = RoomChat.class.getAnnotation(ManagedService.class);
        assertNotNull(ms, "RoomChat must be a @ManagedService");
        assertTrue(ms.path().contains("{room}"),
                "path must carry the {room} template, was: " + ms.path());
    }

    @Test
    void roomChatIsNotSingletonBecauseItHoldsAPathParam() {
        assertFalse(RoomChat.class.isAnnotationPresent(Singleton.class),
                "RoomChat holds a @PathParam field; @Singleton would share it across every room");
    }

    @Test
    void roomChatDeclaresThePathParamField() throws NoSuchFieldException {
        Field room = RoomChat.class.getDeclaredField("room");
        PathParam p = room.getAnnotation(PathParam.class);
        assertNotNull(p, "room field must be a @PathParam");
        assertEquals("room", p.value(), "must bind the {room} path segment by name");
    }

    @Test
    void announcementsIsSingletonAndHasNoPathTemplate() {
        assertTrue(Announcements.class.isAnnotationPresent(Singleton.class),
                "Announcements is stateless and pathless — @Singleton is correct here");
        ManagedService ms = Announcements.class.getAnnotation(ManagedService.class);
        assertNotNull(ms);
        assertFalse(ms.path().contains("{"),
                "a @Singleton service must not carry a path template, was: " + ms.path());
    }

    @Test
    void announcementsFansOutToEveryBroadcaster() throws NoSuchMethodException {
        DeliverTo d = Announcements.class
                .getDeclaredMethod("onAnnouncement", Message.class)
                .getAnnotation(DeliverTo.class);
        assertNotNull(d, "an announcement must be @DeliverTo-annotated or it only reaches this endpoint");
        assertEquals(DeliverTo.DELIVER_TO.ALL, d.value(),
                "BROADCASTER would keep the announcement inside /atmosphere/announcements, where nobody is");
    }
}
