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

import static org.testng.Assert.*;

import java.util.Map;

import org.testng.annotations.Test;

public class RoomMemberTest {

    @Test
    public void testRecordConstruction() {
        var member = new RoomMember("alice", Map.of("avatar", "pic.jpg"));
        assertEquals(member.id(), "alice");
        assertEquals(member.metadata().get("avatar"), "pic.jpg");
    }

    @Test
    public void testConvenienceConstructor() {
        var member = new RoomMember("bob");
        assertEquals(member.id(), "bob");
        assertTrue(member.metadata().isEmpty());
    }

    @Test(expectedExceptions = NullPointerException.class)
    public void testNullIdThrows() {
        new RoomMember(null);
    }

    @Test
    public void testNullMetadataBecomesEmpty() {
        var member = new RoomMember("alice", null);
        assertNotNull(member.metadata());
        assertTrue(member.metadata().isEmpty());
    }

    @Test
    public void testMetadataIsImmutable() {
        var member = new RoomMember("alice", Map.of("key", "value"));
        assertThrows(UnsupportedOperationException.class, () -> member.metadata().put("new", "val"));
    }

    @Test
    public void testEquality() {
        var m1 = new RoomMember("alice", Map.of("k", "v"));
        var m2 = new RoomMember("alice", Map.of("k", "v"));
        assertEquals(m1, m2);
        assertEquals(m1.hashCode(), m2.hashCode());
    }

    @Test
    public void testInequality() {
        var m1 = new RoomMember("alice");
        var m2 = new RoomMember("bob");
        assertNotEquals(m1, m2);
    }
}
