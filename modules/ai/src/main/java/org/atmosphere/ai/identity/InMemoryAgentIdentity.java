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
package org.atmosphere.ai.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory default implementation of {@link AgentIdentity}. Persistent
 * variants (SQLite, Redis) are pluggable via downstream modules; this
 * implementation is the starting point that any application can use
 * without extra dependencies.
 *
 * <p>Audit event cap is configurable at construction; when exceeded, the
 * oldest entries are evicted from each per-user deque.</p>
 */
public final class InMemoryAgentIdentity implements AgentIdentity {

    /** Default per-user audit retention. */
    public static final int DEFAULT_AUDIT_LIMIT = 1_000;

    private final CredentialStore credentialStore;
    private final int auditLimit;
    private final Clock clock;

    private final Map<String, PermissionMode> modes = new ConcurrentHashMap<>();
    private final Map<String, Deque<AuditEvent>> auditLog = new ConcurrentHashMap<>();
    private final Map<String, SessionShare> shares = new ConcurrentHashMap<>();

    public InMemoryAgentIdentity(CredentialStore credentialStore) {
        this(credentialStore, DEFAULT_AUDIT_LIMIT, Clock.systemUTC());
    }

    public InMemoryAgentIdentity(CredentialStore credentialStore, int auditLimit, Clock clock) {
        if (auditLimit < 1) {
            throw new IllegalArgumentException("auditLimit must be >= 1, got " + auditLimit);
        }
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
        this.auditLimit = auditLimit;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PermissionMode permissionMode(String userId) {
        return modes.getOrDefault(requireUser(userId), PermissionMode.DEFAULT);
    }

    @Override
    public void setPermissionMode(String userId, PermissionMode mode) {
        modes.put(requireUser(userId), Objects.requireNonNull(mode, "mode"));
    }

    @Override
    public CredentialStore credentialStore() {
        return credentialStore;
    }

    @Override
    public void recordAudit(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        var deque = auditLog.computeIfAbsent(requireUser(event.userId()), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addFirst(event);
            while (deque.size() > auditLimit) {
                deque.pollLast();
            }
        }
    }

    @Override
    public List<AuditEvent> audit(String userId, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0, got " + limit);
        }
        var deque = auditLog.get(requireUser(userId));
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            var out = new ArrayList<AuditEvent>(Math.min(limit, deque.size()));
            for (var event : deque) {
                if (out.size() >= limit) {
                    break;
                }
                out.add(event);
            }
            return List.copyOf(out);
        }
    }

    @Override
    public SessionShare createShare(String userId, String sessionId, Duration ttl) {
        requireUser(userId);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive, got " + ttl);
        }
        var now = Instant.now(clock);
        var share = new SessionShare(
                "share-" + UUID.randomUUID(),
                userId,
                sessionId,
                now,
                now.plus(ttl));
        shares.put(share.token(), share);
        recordAudit(new AuditEvent(
                UUID.randomUUID().toString(),
                userId,
                "session.share.create",
                "sessionId=" + sessionId + " ttl=" + ttl,
                now));
        return share;
    }

    @Override
    public void revokeShare(String shareToken) {
        var existing = shares.remove(shareToken);
        if (existing != null) {
            recordAudit(new AuditEvent(
                    UUID.randomUUID().toString(),
                    existing.userId(),
                    "session.share.revoke",
                    "token=" + shareToken,
                    Instant.now(clock)));
        }
    }

    @Override
    public Optional<SessionShare> lookupShare(String shareToken) {
        var share = shares.get(shareToken);
        if (share == null) {
            return Optional.empty();
        }
        if (!share.isActive(Instant.now(clock))) {
            shares.remove(shareToken);
            return Optional.empty();
        }
        return Optional.of(share);
    }

    private static String requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        return userId;
    }
}
