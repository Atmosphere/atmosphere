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
package org.atmosphere.session;

import org.testng.annotations.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.testng.Assert.*;

public class DurableSessionTest {

    @Test
    public void testCreate() {
        var before = Instant.now();
        var session = DurableSession.create("tok-1", "res-1");

        assertEquals(session.token(), "tok-1");
        assertEquals(session.resourceId(), "res-1");
        assertTrue(session.rooms().isEmpty());
        assertTrue(session.broadcasters().isEmpty());
        assertTrue(session.metadata().isEmpty());
        assertFalse(session.createdAt().isBefore(before));
        assertFalse(session.lastSeen().isBefore(before));
    }

    @Test
    public void testWithRooms() {
        var session = DurableSession.create("tok-1", "res-1");
        var updated = session.withRooms(Set.of("chat", "lobby"));

        assertEquals(updated.rooms(), Set.of("chat", "lobby"));
        assertEquals(updated.token(), "tok-1");
        assertEquals(updated.resourceId(), "res-1");
        // Original is unchanged (immutable record)
        assertTrue(session.rooms().isEmpty());
    }

    @Test
    public void testWithBroadcasters() {
        var session = DurableSession.create("tok-1", "res-1");
        var updated = session.withBroadcasters(Set.of("/chat", "/notifications"));

        assertEquals(updated.broadcasters(), Set.of("/chat", "/notifications"));
        assertTrue(session.broadcasters().isEmpty());
    }

    @Test
    public void testWithMetadata() {
        var session = DurableSession.create("tok-1", "res-1");
        var updated = session.withMetadata(Map.of("user", "alice", "role", "admin"));

        assertEquals(updated.metadata(), Map.of("user", "alice", "role", "admin"));
        assertTrue(session.metadata().isEmpty());
    }

    @Test
    public void testWithResourceId() {
        var session = DurableSession.create("tok-1", "res-1");
        var updated = session.withResourceId("res-2");

        assertEquals(updated.resourceId(), "res-2");
        assertEquals(updated.token(), "tok-1");
        assertEquals(session.resourceId(), "res-1");
    }

    @Test
    public void testLastSeenUpdatesOnWith() throws InterruptedException {
        var session = DurableSession.create("tok-1", "res-1");
        var originalLastSeen = session.lastSeen();
        Thread.sleep(10);
        var updated = session.withRooms(Set.of("room"));

        assertFalse(updated.lastSeen().isBefore(originalLastSeen));
    }
}
