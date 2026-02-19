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

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.testng.Assert.*;

public class InMemorySessionStoreTest {

    private InMemorySessionStore store;

    @BeforeMethod
    public void setUp() {
        store = new InMemorySessionStore();
    }

    @Test
    public void testSaveAndRestore() {
        var session = DurableSession.create("tok-1", "res-1");
        store.save(session);

        var restored = store.restore("tok-1");
        assertTrue(restored.isPresent());
        assertEquals(restored.get().token(), "tok-1");
        assertEquals(restored.get().resourceId(), "res-1");
    }

    @Test
    public void testRestoreNonExistent() {
        var restored = store.restore("no-such-token");
        assertTrue(restored.isEmpty());
    }

    @Test
    public void testSaveOverwrite() {
        var session1 = DurableSession.create("tok-1", "res-1");
        store.save(session1);

        var session2 = session1.withResourceId("res-2");
        store.save(session2);

        var restored = store.restore("tok-1");
        assertTrue(restored.isPresent());
        assertEquals(restored.get().resourceId(), "res-2");
    }

    @Test
    public void testRemove() {
        store.save(DurableSession.create("tok-1", "res-1"));
        store.remove("tok-1");

        assertTrue(store.restore("tok-1").isEmpty());
    }

    @Test
    public void testRemoveNonExistent() {
        // Should not throw
        store.remove("no-such-token");
    }

    @Test
    public void testTouch() {
        var session = DurableSession.create("tok-1", "res-1");
        store.save(session);
        var original = store.restore("tok-1").get().lastSeen();

        store.touch("tok-1");

        var updated = store.restore("tok-1").get().lastSeen();
        assertFalse(updated.isBefore(original));
    }

    @Test
    public void testRemoveExpired() {
        // Create a session with old lastSeen
        var old = new DurableSession("tok-old", "res-1", Set.of(), Set.of(),
                Map.of(), Instant.now().minus(Duration.ofHours(2)),
                Instant.now().minus(Duration.ofHours(2)));
        var fresh = DurableSession.create("tok-fresh", "res-2");

        store.save(old);
        store.save(fresh);

        var expired = store.removeExpired(Duration.ofHours(1));

        assertEquals(expired.size(), 1);
        assertEquals(expired.get(0).token(), "tok-old");
        assertTrue(store.restore("tok-old").isEmpty());
        assertTrue(store.restore("tok-fresh").isPresent());
    }

    @Test
    public void testRemoveExpiredNone() {
        store.save(DurableSession.create("tok-1", "res-1"));

        var expired = store.removeExpired(Duration.ofHours(1));
        assertTrue(expired.isEmpty());
    }

    @Test
    public void testSaveWithRoomsAndBroadcasters() {
        var session = DurableSession.create("tok-1", "res-1")
                .withRooms(Set.of("chat", "lobby"))
                .withBroadcasters(Set.of("/chat", "/lobby"))
                .withMetadata(Map.of("user", "alice"));
        store.save(session);

        var restored = store.restore("tok-1").get();
        assertEquals(restored.rooms(), Set.of("chat", "lobby"));
        assertEquals(restored.broadcasters(), Set.of("/chat", "/lobby"));
        assertEquals(restored.metadata(), Map.of("user", "alice"));
    }
}
