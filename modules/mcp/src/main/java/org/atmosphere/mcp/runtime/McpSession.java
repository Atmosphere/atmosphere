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

import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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
    private volatile String protocolVersion;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    private volatile long lastAccessedAt = System.currentTimeMillis();
    private final int maxPending;
    private final Deque<String> pendingNotifications;
    private final ReentrantLock pendingLock = new ReentrantLock();
    private final Set<String> subscriptions = new HashSet<>();
    private final ReentrantLock subscriptionLock = new ReentrantLock();

    /**
     * In-flight server-initiated requests (e.g. {@code elicitation/create})
     * waiting for client responses. Keyed by JSON-RPC request id. The
     * receiving side completes these when a response envelope arrives over
     * the same session. Keeping this on the session instead of the
     * handler is what lets the Streamable-HTTP transport's
     * disconnect/reconnect flow stay correct — pending requests survive
     * the SSE GET tear-down/reopen cycle.
     */
    private final Map<String, CompletableFuture<JsonNode>> pendingServerRequests = new ConcurrentHashMap<>();

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

    /**
     * Negotiated MCP protocol revision for this session, set during the
     * initialize handshake. Used by the transport layer to enforce the
     * {@code MCP-Protocol-Version} header on subsequent requests
     * (required by spec 2025-06-18 onward).
     */
    public String protocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String version) {
        this.protocolVersion = version;
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
        pendingLock.lock();
        try {
            if (pendingNotifications.size() >= maxPending) {
                pendingNotifications.pollFirst();
            }
            pendingNotifications.addLast(notification);
        } finally {
            pendingLock.unlock();
        }
    }

    /**
     * Drain and return all pending notifications, clearing the queue.
     */
    public List<String> drainPendingNotifications() {
        pendingLock.lock();
        try {
            var list = new ArrayList<>(pendingNotifications);
            pendingNotifications.clear();
            return list;
        } finally {
            pendingLock.unlock();
        }
    }

    // ── Server-initiated requests (elicitation, future: tasks) ──────────

    /**
     * Register a future that will be completed when the client responds to
     * a server-initiated request (e.g., {@code elicitation/create}). The
     * caller is responsible for actually serializing and sending the
     * request via {@link #addPendingNotification}; this method just
     * reserves the response slot.
     */
    public void registerServerRequest(String requestId, CompletableFuture<JsonNode> future) {
        pendingServerRequests.put(requestId, future);
    }

    /**
     * Complete an in-flight server-initiated request by id, removing it from
     * the registry. Returns {@code true} if a matching pending request was
     * found and completed; {@code false} when the id is unknown (response
     * arrived after timeout, double-response, etc.).
     */
    public boolean completeServerRequest(String requestId, JsonNode response) {
        var future = pendingServerRequests.remove(requestId);
        if (future == null) {
            return false;
        }
        future.complete(response);
        return true;
    }

    /**
     * Cancel a pending server-initiated request without a response (e.g.,
     * timeout). Idempotent.
     */
    public boolean cancelServerRequest(String requestId, Throwable cause) {
        var future = pendingServerRequests.remove(requestId);
        if (future == null) {
            return false;
        }
        future.completeExceptionally(cause);
        return true;
    }

    /** Visible-for-testing snapshot of in-flight server-initiated request ids. */
    public Set<String> pendingServerRequestIds() {
        return Collections.unmodifiableSet(pendingServerRequests.keySet());
    }

    /** Returns the number of buffered pending notifications. */
    public int pendingCount() {
        pendingLock.lock();
        try {
            return pendingNotifications.size();
        } finally {
            pendingLock.unlock();
        }
    }

    // ── Resource subscriptions ──────────────────────────────────────────

    /** Subscribe to notifications for the given resource URI. */
    public void addSubscription(String uri) {
        subscriptionLock.lock();
        try {
            subscriptions.add(uri);
        } finally {
            subscriptionLock.unlock();
        }
    }

    /** Unsubscribe from notifications for the given resource URI. */
    public void removeSubscription(String uri) {
        subscriptionLock.lock();
        try {
            subscriptions.remove(uri);
        } finally {
            subscriptionLock.unlock();
        }
    }

    /** Returns true if this session is subscribed to the given resource URI. */
    public boolean isSubscribed(String uri) {
        subscriptionLock.lock();
        try {
            return subscriptions.contains(uri);
        } finally {
            subscriptionLock.unlock();
        }
    }

    /** Returns an unmodifiable view of all subscribed resource URIs. */
    public Set<String> subscriptions() {
        subscriptionLock.lock();
        try {
            return Collections.unmodifiableSet(new HashSet<>(subscriptions));
        } finally {
            subscriptionLock.unlock();
        }
    }
}
