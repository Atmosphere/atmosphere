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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link SessionStore} for testing and development.
 *
 * <p>Sessions are lost on server restart — for persistence across
 * restarts, use {@code SqliteSessionStore} or {@code RedisSessionStore}.</p>
 */
public class InMemorySessionStore implements SessionStore {

    private final ConcurrentHashMap<String, DurableSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(DurableSession session) {
        sessions.put(session.token(), session);
    }

    @Override
    public Optional<DurableSession> restore(String token) {
        return Optional.ofNullable(sessions.get(token));
    }

    @Override
    public void remove(String token) {
        sessions.remove(token);
    }

    @Override
    public void touch(String token) {
        sessions.computeIfPresent(token, (k, s) ->
                s.withResourceId(s.resourceId()));
    }

    @Override
    public List<DurableSession> removeExpired(Duration ttl) {
        var cutoff = Instant.now().minus(ttl);
        var expired = new ArrayList<DurableSession>();
        sessions.entrySet().removeIf(e -> {
            if (e.getValue().lastSeen().isBefore(cutoff)) {
                expired.add(e.getValue());
                return true;
            }
            return false;
        });
        return expired;
    }
}
