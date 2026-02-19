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

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Snapshot of a durable session that can be persisted and restored
 * across server restarts.
 *
 * @param token      unique session token sent to the client for reconnection
 * @param resourceId the {@code AtmosphereResource} UUID at the time of save
 * @param rooms      set of room names the resource had joined
 * @param broadcasters set of broadcaster IDs the resource was subscribed to
 * @param metadata   application-defined key-value pairs
 * @param createdAt  when the session was first created
 * @param lastSeen   last time the client was active
 */
public record DurableSession(
        String token,
        String resourceId,
        Set<String> rooms,
        Set<String> broadcasters,
        Map<String, String> metadata,
        Instant createdAt,
        Instant lastSeen
) {

    /**
     * Create a new session with the current timestamp.
     */
    public static DurableSession create(String token, String resourceId) {
        var now = Instant.now();
        return new DurableSession(token, resourceId, Set.of(), Set.of(),
                Map.of(), now, now);
    }

    /**
     * Return a copy with updated rooms.
     */
    public DurableSession withRooms(Set<String> rooms) {
        return new DurableSession(token, resourceId, Set.copyOf(rooms), broadcasters,
                metadata, createdAt, Instant.now());
    }

    /**
     * Return a copy with updated broadcasters.
     */
    public DurableSession withBroadcasters(Set<String> broadcasters) {
        return new DurableSession(token, resourceId, rooms, Set.copyOf(broadcasters),
                metadata, createdAt, Instant.now());
    }

    /**
     * Return a copy with updated metadata.
     */
    public DurableSession withMetadata(Map<String, String> metadata) {
        return new DurableSession(token, resourceId, rooms, broadcasters,
                Map.copyOf(metadata), createdAt, Instant.now());
    }

    /**
     * Return a copy with refreshed lastSeen timestamp and new resource ID.
     */
    public DurableSession withResourceId(String newResourceId) {
        return new DurableSession(token, newResourceId, rooms, broadcasters,
                metadata, createdAt, Instant.now());
    }
}
