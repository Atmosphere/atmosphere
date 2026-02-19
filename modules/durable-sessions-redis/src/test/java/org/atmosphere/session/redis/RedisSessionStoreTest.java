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
package org.atmosphere.session.redis;

import org.atmosphere.session.DurableSession;
import org.testcontainers.containers.GenericContainer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Integration tests for {@link RedisSessionStore} using Testcontainers.
 *
 * <p>Requires Docker. Tests are skipped if Docker is unavailable.</p>
 */
@Test(groups = "redis")
public class RedisSessionStoreTest {

    private static final boolean DOCKER_AVAILABLE = isDockerAvailable();

    private GenericContainer<?> redis;
    private String redisUri;

    /** Default store with a long TTL for most tests. */
    private RedisSessionStore store;

    @BeforeClass
    public void setUp() {
        if (!DOCKER_AVAILABLE) {
            return;
        }

        redis = new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(6379);
        redis.start();

        redisUri = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
        store = new RedisSessionStore(redisUri, Duration.ofHours(24));
    }

    @AfterMethod
    public void cleanUp() {
        if (!DOCKER_AVAILABLE || store == null) {
            return;
        }
        // Remove all test sessions between tests to avoid interference.
        // We restore and remove known tokens; alternatively flush via a
        // fresh Lettuce connection, but removing by token keeps it simple.
        for (int i = 0; i < 150; i++) {
            store.remove("tok-" + i);
        }
        store.remove("tok-old");
        store.remove("tok-fresh");
        store.remove("tok-expiry");
    }

    @AfterClass
    public void tearDown() {
        if (store != null) {
            store.close();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    // --- save / restore ---

    @Test
    public void testSaveAndRestore() {
        skipIfNoDocker();

        var session = DurableSession.create("tok-1", "res-1");
        store.save(session);

        var restored = store.restore("tok-1");
        assertTrue(restored.isPresent());
        assertEquals(restored.get().token(), "tok-1");
        assertEquals(restored.get().resourceId(), "res-1");
    }

    @Test
    public void testRestoreNonExistent() {
        skipIfNoDocker();

        assertTrue(store.restore("no-such-token").isEmpty());
    }

    // --- overwrite ---

    @Test
    public void testSaveOverwrite() {
        skipIfNoDocker();

        store.save(DurableSession.create("tok-1", "res-1"));
        store.save(DurableSession.create("tok-1", "res-1").withResourceId("res-2"));

        var restored = store.restore("tok-1").get();
        assertEquals(restored.resourceId(), "res-2");
    }

    // --- remove ---

    @Test
    public void testRemove() {
        skipIfNoDocker();

        store.save(DurableSession.create("tok-1", "res-1"));
        store.remove("tok-1");

        assertTrue(store.restore("tok-1").isEmpty());
    }

    @Test
    public void testRemoveNonExistent() {
        skipIfNoDocker();

        // Should not throw
        store.remove("no-such-token");
    }

    // --- touch (Lua script) ---

    @Test
    public void testTouch() throws Exception {
        skipIfNoDocker();

        var session = DurableSession.create("tok-1", "res-1");
        store.save(session);

        var originalLastSeen = store.restore("tok-1").get().lastSeen();

        // Small sleep to ensure Instant.now() in touch() produces a later timestamp
        Thread.sleep(50);
        store.touch("tok-1");

        var updatedLastSeen = store.restore("tok-1").get().lastSeen();
        assertFalse(updatedLastSeen.isBefore(originalLastSeen),
                "lastSeen should be updated after touch; original=" + originalLastSeen
                        + ", updated=" + updatedLastSeen);
    }

    @Test
    public void testTouchNonExistent() {
        skipIfNoDocker();

        // Should not throw when touching a missing session
        store.touch("no-such-token");
    }

    @Test
    public void testTouchPreservesOtherFields() {
        skipIfNoDocker();

        var session = DurableSession.create("tok-1", "res-1")
                .withRooms(Set.of("room-a"))
                .withBroadcasters(Set.of("/chat"))
                .withMetadata(Map.of("user", "alice"));
        store.save(session);

        store.touch("tok-1");

        var restored = store.restore("tok-1").get();
        assertEquals(restored.token(), "tok-1");
        assertEquals(restored.resourceId(), "res-1");
        assertEquals(restored.rooms(), Set.of("room-a"));
        assertEquals(restored.broadcasters(), Set.of("/chat"));
        assertEquals(restored.metadata().get("user"), "alice");
    }

    // --- expiry / removeExpired ---

    @Test(timeOut = 15_000)
    public void testRemoveExpiredCleansUpIndex() throws Exception {
        skipIfNoDocker();

        // Use a separate store with a very short TTL so Redis auto-expires the key
        var shortTtlStore = new RedisSessionStore(redisUri, Duration.ofSeconds(2));
        try {
            shortTtlStore.save(DurableSession.create("tok-expiry", "res-1"));

            // Verify it exists
            assertTrue(shortTtlStore.restore("tok-expiry").isPresent());

            // Wait for Redis TTL to expire the key
            Thread.sleep(3000);

            // The key should be gone now (expired by Redis)
            assertTrue(shortTtlStore.restore("tok-expiry").isEmpty());

            // removeExpired should clean up the index and return stub sessions
            var expired = shortTtlStore.removeExpired(Duration.ofSeconds(1));
            assertTrue(expired.stream().anyMatch(s -> "tok-expiry".equals(s.token())),
                    "Expected tok-expiry in expired list, got: " + expired);
        } finally {
            shortTtlStore.close();
        }
    }

    @Test
    public void testRemoveExpiredReturnsEmptyWhenNothingExpired() {
        skipIfNoDocker();

        store.save(DurableSession.create("tok-1", "res-1"));

        var expired = store.removeExpired(Duration.ofHours(1));
        assertTrue(expired.isEmpty());
    }

    // --- serialization: rooms ---

    @Test
    public void testRoomsSerialization() {
        skipIfNoDocker();

        var session = DurableSession.create("tok-1", "res-1")
                .withRooms(Set.of("room-a", "room-b", "room with spaces"));
        store.save(session);

        var restored = store.restore("tok-1").get();
        assertEquals(restored.rooms(), Set.of("room-a", "room-b", "room with spaces"));
    }

    // --- serialization: broadcasters ---

    @Test
    public void testBroadcastersSerialization() {
        skipIfNoDocker();

        var session = DurableSession.create("tok-1", "res-1")
                .withBroadcasters(Set.of("/chat", "/notifications", "/path/with/slashes"));
        store.save(session);

        var restored = store.restore("tok-1").get();
        assertEquals(restored.broadcasters(), Set.of("/chat", "/notifications", "/path/with/slashes"));
    }

    // --- serialization: metadata ---

    @Test
    public void testMetadataSerialization() {
        skipIfNoDocker();

        var session = DurableSession.create("tok-1", "res-1")
                .withMetadata(Map.of("user", "alice", "role", "admin", "key,with,commas", "value"));
        store.save(session);

        var restored = store.restore("tok-1").get();
        assertEquals(restored.metadata().get("user"), "alice");
        assertEquals(restored.metadata().get("role"), "admin");
        assertEquals(restored.metadata().get("key,with,commas"), "value");
    }

    // --- serialization: empty collections ---

    @Test
    public void testEmptyCollections() {
        skipIfNoDocker();

        var session = DurableSession.create("tok-1", "res-1");
        store.save(session);

        var restored = store.restore("tok-1").get();
        assertTrue(restored.rooms().isEmpty());
        assertTrue(restored.broadcasters().isEmpty());
        assertTrue(restored.metadata().isEmpty());
    }

    // --- serialization: timestamps ---

    @Test
    public void testTimestampsPreserved() {
        skipIfNoDocker();

        var now = Instant.now();
        var session = new DurableSession("tok-1", "res-1", Set.of(), Set.of(),
                Map.of(), now, now);
        store.save(session);

        var restored = store.restore("tok-1").get();
        // Millisecond precision (JSON stores epoch millis)
        assertEquals(restored.createdAt().toEpochMilli(), now.toEpochMilli());
        assertEquals(restored.lastSeen().toEpochMilli(), now.toEpochMilli());
    }

    // --- close idempotency ---

    @Test
    public void testCloseIsIdempotent() {
        skipIfNoDocker();

        // Create a separate store just for this test so we don't break other tests
        var tempStore = new RedisSessionStore(redisUri, Duration.ofHours(1));
        tempStore.close();
        tempStore.close(); // second close should not throw
    }

    // --- multiple sessions ---

    @Test
    public void testMultipleSessions() {
        skipIfNoDocker();

        for (int i = 0; i < 100; i++) {
            store.save(DurableSession.create("tok-" + i, "res-" + i));
        }

        for (int i = 0; i < 100; i++) {
            var restored = store.restore("tok-" + i);
            assertTrue(restored.isPresent(), "Session tok-" + i + " should exist");
            assertEquals(restored.get().resourceId(), "res-" + i);
        }
    }

    // --- full round-trip with all fields ---

    @Test
    public void testFullRoundTrip() {
        skipIfNoDocker();

        var now = Instant.now();
        var session = new DurableSession(
                "tok-1", "res-1",
                Set.of("room-x", "room-y"),
                Set.of("/broadcast/a", "/broadcast/b"),
                Map.of("user", "bob", "theme", "dark"),
                now, now);
        store.save(session);

        var restored = store.restore("tok-1").get();
        assertEquals(restored.token(), "tok-1");
        assertEquals(restored.resourceId(), "res-1");
        assertEquals(restored.rooms(), Set.of("room-x", "room-y"));
        assertEquals(restored.broadcasters(), Set.of("/broadcast/a", "/broadcast/b"));
        assertEquals(restored.metadata(), Map.of("user", "bob", "theme", "dark"));
        assertEquals(restored.createdAt().toEpochMilli(), now.toEpochMilli());
        assertEquals(restored.lastSeen().toEpochMilli(), now.toEpochMilli());
    }

    // --- helper ---

    private static void skipIfNoDocker() {
        if (!DOCKER_AVAILABLE) {
            throw new org.testng.SkipException("Docker not available");
        }
    }

    private static boolean isDockerAvailable() {
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }
}
