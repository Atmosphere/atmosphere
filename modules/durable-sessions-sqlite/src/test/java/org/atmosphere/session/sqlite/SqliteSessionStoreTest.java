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
package org.atmosphere.session.sqlite;

import org.atmosphere.session.DurableSession;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SqliteSessionStoreTest {

    private SqliteSessionStore store;

    @BeforeEach
    public void setUp() {
        store = SqliteSessionStore.inMemory();
    }

    @AfterEach
    public void tearDown() {
        store.close();
    }

    @Test
    public void testSaveAndRestore() {
        var session = DurableSession.create("tok-1", "res-1");
        store.save(session);

        var restored = store.restore("tok-1");
        assertTrue(restored.isPresent());
        assertEquals("tok-1", restored.get().token());
        assertEquals("res-1", restored.get().resourceId());
    }

    @Test
    public void testRestoreNonExistent() {
        assertTrue(store.restore("no-such-token").isEmpty());
    }

    @Test
    public void testSaveOverwrite() {
        store.save(DurableSession.create("tok-1", "res-1"));
        store.save(DurableSession.create("tok-1", "res-1").withResourceId("res-2"));

        var restored = store.restore("tok-1").get();
        assertEquals("res-2", restored.resourceId());
    }

    @Test
    public void testRemove() {
        store.save(DurableSession.create("tok-1", "res-1"));
        store.remove("tok-1");

        assertTrue(store.restore("tok-1").isEmpty());
    }

    @Test
    public void testRemoveNonExistent() {
        store.remove("no-such-token");
    }

    @Test
    public void testTouch() {
        var session = DurableSession.create("tok-1", "res-1");
        store.save(session);

        var originalLastSeen = store.restore("tok-1").get().lastSeen();
        store.touch("tok-1");

        var updatedLastSeen = store.restore("tok-1").get().lastSeen();
        assertFalse(updatedLastSeen.isBefore(originalLastSeen));
    }

    @Test
    public void testRemoveExpired() {
        var old = new DurableSession("tok-old", "res-1", Set.of(), Set.of(),
                Map.of(), Instant.now().minus(Duration.ofHours(2)),
                Instant.now().minus(Duration.ofHours(2)));
        var fresh = DurableSession.create("tok-fresh", "res-2");

        store.save(old);
        store.save(fresh);

        var expired = store.removeExpired(Duration.ofHours(1));

        assertEquals(1, expired.size());
        assertEquals("tok-old", expired.get(0).token());
        assertTrue(store.restore("tok-old").isEmpty());
        assertTrue(store.restore("tok-fresh").isPresent());
    }

    @Test
    public void testRemoveExpiredReturnsEmpty() {
        store.save(DurableSession.create("tok-1", "res-1"));

        var expired = store.removeExpired(Duration.ofHours(1));
        assertTrue(expired.isEmpty());
    }

    @Test
    public void testRoomsSerialization() {
        var session = DurableSession.create("tok-1", "res-1")
                .withRooms(Set.of("room-a", "room-b", "room with spaces"));
        store.save(session);

        var restored = store.restore("tok-1").get();
        assertEquals(Set.of("room-a", "room-b", "room with spaces"), restored.rooms());
    }

    @Test
    public void testBroadcastersSerialization() {
        var session = DurableSession.create("tok-1", "res-1")
                .withBroadcasters(Set.of("/chat", "/notifications", "/path/with/slashes"));
        store.save(session);

        var restored = store.restore("tok-1").get();
        assertEquals(Set.of("/chat", "/notifications", "/path/with/slashes"), restored.broadcasters());
    }

    @Test
    public void testMetadataSerialization() {
        var session = DurableSession.create("tok-1", "res-1")
                .withMetadata(Map.of("user", "alice", "role", "admin", "key,with,commas", "value"));
        store.save(session);

        var restored = store.restore("tok-1").get();
        assertEquals("alice", restored.metadata().get("user"));
        assertEquals("admin", restored.metadata().get("role"));
        assertEquals("value", restored.metadata().get("key,with,commas"));
    }

    @Test
    public void testEmptyCollections() {
        var session = DurableSession.create("tok-1", "res-1");
        store.save(session);

        var restored = store.restore("tok-1").get();
        assertTrue(restored.rooms().isEmpty());
        assertTrue(restored.broadcasters().isEmpty());
        assertTrue(restored.metadata().isEmpty());
    }

    @Test
    public void testTimestampsPreserved() {
        var now = Instant.now();
        var session = new DurableSession("tok-1", "res-1", Set.of(), Set.of(),
                Map.of(), now, now);
        store.save(session);

        var restored = store.restore("tok-1").get();
        // Millisecond precision (SQLite stores millis)
        assertEquals(now.toEpochMilli(), restored.createdAt().toEpochMilli());
        assertEquals(now.toEpochMilli(), restored.lastSeen().toEpochMilli());
    }

    @Test
    public void testCloseIsIdempotent() {
        store.close();
        store.close();
    }

    @Test
    public void testMultipleSessions() {
        for (int i = 0; i < 100; i++) {
            store.save(DurableSession.create("tok-" + i, "res-" + i));
        }

        for (int i = 0; i < 100; i++) {
            assertTrue(store.restore("tok-" + i).isPresent());
        }
    }
}
