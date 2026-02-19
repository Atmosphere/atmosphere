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

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * SPI for persisting and restoring durable sessions.
 *
 * <p>Implementations store session state (rooms, broadcasters, metadata)
 * so that clients can reconnect after a server restart and resume where
 * they left off.</p>
 *
 * <p>Two built-in implementations are provided:</p>
 * <ul>
 *   <li>{@code SqliteSessionStore} — embedded, zero-config, single-node</li>
 *   <li>{@code RedisSessionStore} — clustered, shared across nodes</li>
 * </ul>
 */
public interface SessionStore {

    /**
     * Persist a session. If a session with the same token already exists,
     * it is replaced.
     */
    void save(DurableSession session);

    /**
     * Restore a session by its token.
     *
     * @return the session if found and not expired
     */
    Optional<DurableSession> restore(String token);

    /**
     * Remove a session by its token.
     */
    void remove(String token);

    /**
     * Update the last-seen timestamp for a session.
     */
    void touch(String token);

    /**
     * Find and remove all sessions that have not been seen within the
     * given TTL.
     *
     * @param ttl maximum duration since last activity
     * @return the expired sessions that were removed
     */
    List<DurableSession> removeExpired(Duration ttl);

    /**
     * Close the store and release resources.
     */
    default void close() {
    }
}
