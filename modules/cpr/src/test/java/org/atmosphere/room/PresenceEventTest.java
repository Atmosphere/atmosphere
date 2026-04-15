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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.atmosphere.cpr.AtmosphereResource;

import org.junit.jupiter.api.Test;

class PresenceEventTest {

    @Test
    void fullConstructorSetsAllFields() {
        Room room = mock(Room.class);
        AtmosphereResource resource = mock(AtmosphereResource.class);
        RoomMember member = new RoomMember("alice");

        PresenceEvent event = new PresenceEvent(PresenceEvent.Type.JOIN, room, resource, member);

        assertEquals(PresenceEvent.Type.JOIN, event.type());
        assertSame(room, event.room());
        assertSame(resource, event.member());
        assertSame(member, event.memberInfo());
    }

    @Test
    void convenienceConstructorSetsNullMemberInfo() {
        Room room = mock(Room.class);
        AtmosphereResource resource = mock(AtmosphereResource.class);

        PresenceEvent event = new PresenceEvent(PresenceEvent.Type.LEAVE, room, resource);

        assertEquals(PresenceEvent.Type.LEAVE, event.type());
        assertSame(resource, event.member());
        assertNull(event.memberInfo());
    }

    @Test
    void virtualConstructorSetsNullResource() {
        Room room = mock(Room.class);
        RoomMember member = new RoomMember("bot-1");

        PresenceEvent event = new PresenceEvent(PresenceEvent.Type.JOIN, room, member);

        assertNull(event.member());
        assertSame(member, event.memberInfo());
    }

    @Test
    void isVirtualReturnsTrueForVirtualMember() {
        Room room = mock(Room.class);
        RoomMember member = new RoomMember("assistant");

        PresenceEvent event = new PresenceEvent(PresenceEvent.Type.JOIN, room, member);

        assertTrue(event.isVirtual());
    }

    @Test
    void isVirtualReturnsFalseForRealResource() {
        Room room = mock(Room.class);
        AtmosphereResource resource = mock(AtmosphereResource.class);

        PresenceEvent event = new PresenceEvent(PresenceEvent.Type.JOIN, room, resource);

        assertFalse(event.isVirtual());
    }

    @Test
    void isVirtualReturnsFalseWhenBothResourceAndMemberInfoPresent() {
        Room room = mock(Room.class);
        AtmosphereResource resource = mock(AtmosphereResource.class);
        RoomMember member = new RoomMember("user1");

        PresenceEvent event = new PresenceEvent(PresenceEvent.Type.JOIN, room, resource, member);

        assertFalse(event.isVirtual());
    }

    @Test
    void isVirtualReturnsFalseWhenBothNull() {
        Room room = mock(Room.class);

        PresenceEvent event = new PresenceEvent(PresenceEvent.Type.LEAVE, room, null, null);

        assertFalse(event.isVirtual());
    }

    @Test
    void typeEnumHasJoinAndLeave() {
        PresenceEvent.Type[] values = PresenceEvent.Type.values();
        assertEquals(2, values.length);
        assertEquals(PresenceEvent.Type.JOIN, PresenceEvent.Type.valueOf("JOIN"));
        assertEquals(PresenceEvent.Type.LEAVE, PresenceEvent.Type.valueOf("LEAVE"));
    }
}
