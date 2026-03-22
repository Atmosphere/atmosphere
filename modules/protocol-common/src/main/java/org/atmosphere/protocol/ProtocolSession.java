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
package org.atmosphere.protocol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-connection protocol session state with pending notification queue, TTL-based
 * expiration, and generic subscriptions. Shared across JSON-RPC protocols (MCP, A2A).
 *
 * <p>Subclasses add protocol-specific fields (e.g., MCP client info, A2A active tasks).</p>
 */
public class ProtocolSession {

    /** Default maximum number of pending notifications to buffer. */
    public static final int DEFAULT_MAX_PENDING = 100;

    /** Default session idle TTL in milliseconds (30 minutes). */
    public static final long DEFAULT_TTL_MS = 30 * 60 * 1000L;

    private final String sessionId = UUID.randomUUID().toString();
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    private volatile long lastAccessedAt = System.currentTimeMillis();
    private final int maxPending;
    private final Deque<String> pendingNotifications;
    private final Set<String> subscriptions = new HashSet<>();

    public ProtocolSession() {
        this(DEFAULT_MAX_PENDING);
    }

    public ProtocolSession(int maxPending) {
        this.maxPending = maxPending;
        this.pendingNotifications = new ArrayDeque<>(Math.min(maxPending, 128));
    }

    public String sessionId() {
        return sessionId;
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    // ── Session TTL ─────────────────────────────────────────────────────

    /** Update the last-accessed timestamp to now. */
    public void touch() {
        this.lastAccessedAt = System.currentTimeMillis();
    }

    /** Returns the last-accessed timestamp (epoch millis). */
    public long lastAccessedAt() {
        return lastAccessedAt;
    }

    /** Returns true if the session has been idle longer than the given TTL. */
    public boolean isExpired(long ttlMs) {
        return System.currentTimeMillis() - lastAccessedAt > ttlMs;
    }

    // ── Pending notification queue ──────────────────────────────────────

    /**
     * Enqueue a notification for later replay. If the queue is full,
     * the oldest notification is dropped.
     */
    public void addPendingNotification(String notification) {
        lock.lock();
        try {
            if (pendingNotifications.size() >= maxPending) {
                pendingNotifications.pollFirst();
            }
            pendingNotifications.addLast(notification);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Drain and return all pending notifications, clearing the queue.
     */
    public List<String> drainPendingNotifications() {
        lock.lock();
        try {
            var list = new ArrayList<>(pendingNotifications);
            pendingNotifications.clear();
            return list;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the number of buffered pending notifications. */
    public int pendingCount() {
        lock.lock();
        try {
            return pendingNotifications.size();
        } finally {
            lock.unlock();
        }
    }

    // ── Subscriptions ───────────────────────────────────────────────────

    /** Subscribe to notifications for the given key. */
    public void addSubscription(String key) {
        lock.lock();
        try {
            subscriptions.add(key);
        } finally {
            lock.unlock();
        }
    }

    /** Unsubscribe from notifications for the given key. */
    public void removeSubscription(String key) {
        lock.lock();
        try {
            subscriptions.remove(key);
        } finally {
            lock.unlock();
        }
    }

    /** Returns true if this session is subscribed to the given key. */
    public boolean isSubscribed(String key) {
        lock.lock();
        try {
            return subscriptions.contains(key);
        } finally {
            lock.unlock();
        }
    }

    /** Returns an unmodifiable view of all subscribed keys. */
    public Set<String> subscriptions() {
        lock.lock();
        try {
            return Collections.unmodifiableSet(new HashSet<>(subscriptions));
        } finally {
            lock.unlock();
        }
    }
}
