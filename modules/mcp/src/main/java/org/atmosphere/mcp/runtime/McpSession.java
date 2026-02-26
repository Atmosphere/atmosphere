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
package org.atmosphere.mcp.runtime;

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

/**
 * Tracks per-connection MCP session state (initialization, client capabilities).
 * Each session has a unique ID returned to clients via the {@code Mcp-Session-Id} header.
 *
 * <p>Sessions support a bounded pending-notification queue so that messages sent
 * while the client is temporarily disconnected can be replayed on reconnect.
 */
public final class McpSession {

    /** Attribute key used to store the session on AtmosphereResource. */
    public static final String ATTRIBUTE_KEY = "org.atmosphere.mcp.session";

    /** HTTP header used for session tracking (Streamable HTTP transport). */
    public static final String SESSION_ID_HEADER = "Mcp-Session-Id";

    /** Default maximum number of pending notifications to buffer. */
    public static final int DEFAULT_MAX_PENDING = 100;

    /** Default session idle TTL in milliseconds (30 minutes). */
    public static final long DEFAULT_TTL_MS = 30 * 60 * 1000L;

    private final String sessionId = UUID.randomUUID().toString();
    private volatile boolean initialized;
    private volatile Map<String, Object> clientCapabilities = Map.of();
    private volatile String clientName;
    private volatile String clientVersion;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    private volatile long lastAccessedAt = System.currentTimeMillis();
    private final int maxPending;
    private final Deque<String> pendingNotifications;
    private final Set<String> subscriptions = new HashSet<>();

    public McpSession() {
        this(DEFAULT_MAX_PENDING);
    }

    public McpSession(int maxPending) {
        this.maxPending = maxPending;
        this.pendingNotifications = new ArrayDeque<>(Math.min(maxPending, 128));
    }

    public String sessionId() {
        return sessionId;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void markInitialized() {
        this.initialized = true;
    }

    public Map<String, Object> clientCapabilities() {
        return clientCapabilities;
    }

    public void setClientInfo(String name, String version, Map<String, Object> capabilities) {
        this.clientName = name;
        this.clientVersion = version;
        this.clientCapabilities = capabilities != null ? capabilities : Map.of();
    }

    public String clientName() {
        return clientName;
    }

    public String clientVersion() {
        return clientVersion;
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
    public synchronized void addPendingNotification(String notification) {
        if (pendingNotifications.size() >= maxPending) {
            pendingNotifications.pollFirst();
        }
        pendingNotifications.addLast(notification);
    }

    /**
     * Drain and return all pending notifications, clearing the queue.
     */
    public synchronized List<String> drainPendingNotifications() {
        var list = new ArrayList<>(pendingNotifications);
        pendingNotifications.clear();
        return list;
    }

    /** Returns the number of buffered pending notifications. */
    public synchronized int pendingCount() {
        return pendingNotifications.size();
    }

    // ── Resource subscriptions ──────────────────────────────────────────

    /** Subscribe to notifications for the given resource URI. */
    public synchronized void addSubscription(String uri) {
        subscriptions.add(uri);
    }

    /** Unsubscribe from notifications for the given resource URI. */
    public synchronized void removeSubscription(String uri) {
        subscriptions.remove(uri);
    }

    /** Returns true if this session is subscribed to the given resource URI. */
    public synchronized boolean isSubscribed(String uri) {
        return subscriptions.contains(uri);
    }

    /** Returns an unmodifiable view of all subscribed resource URIs. */
    public synchronized Set<String> subscriptions() {
        return Collections.unmodifiableSet(new HashSet<>(subscriptions));
    }
}
