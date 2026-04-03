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

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Abstract {@link AtmosphereHandler} providing POST/GET/DELETE dispatch, session
 * store with TTL-based eviction, and pending notification replay. Subclasses
 * override {@link #handlePost}, {@link #handleGet}, and {@link #handleDelete}
 * for protocol-specific behavior.
 *
 * @param <S> the session type (must extend {@link ProtocolSession})
 * @since 4.0.8
 */
public abstract class AbstractProtocolHandler<S extends ProtocolSession>
        implements AtmosphereHandler {

    private static final String APPLICATION_JSON = "application/json";
    private static final String TEXT_EVENT_STREAM = "text/event-stream";

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    private final Map<String, S> sessions = new ConcurrentHashMap<>();
    private final long sessionTtlMs;
    private final String sessionIdHeader;
    private final String sessionAttributeKey;
    private final ScheduledExecutorService cleaner;

    /**
     * @param sessionTtlMs       session idle TTL in milliseconds
     * @param sessionIdHeader    HTTP header name for session ID tracking
     * @param sessionAttributeKey request attribute key for storing the session
     * @param cleanerThreadName  name for the session cleaner thread
     */
    protected AbstractProtocolHandler(long sessionTtlMs, String sessionIdHeader,
                                      String sessionAttributeKey, String cleanerThreadName) {
        this.sessionTtlMs = sessionTtlMs;
        this.sessionIdHeader = sessionIdHeader;
        this.sessionAttributeKey = sessionAttributeKey;
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = Thread.ofPlatform().daemon().name(cleanerThreadName).unstarted(r);
            return t;
        });
        this.cleaner.scheduleAtFixedRate(this::evictExpiredSessions,
                sessionTtlMs, Math.max(sessionTtlMs / 2, 60_000L), TimeUnit.MILLISECONDS);
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        var method = resource.getRequest().getMethod();

        switch (method.toUpperCase()) {
            case "POST" -> handlePost(resource);
            case "GET" -> handleGet(resource);
            case "DELETE" -> handleDelete(resource);
            default -> {
                resource.getResponse().setStatus(405);
                resource.getResponse().getWriter().write("{\"error\":\"Method not allowed\"}");
            }
        }
    }

    /**
     * Handle POST requests (JSON-RPC messages).
     */
    protected abstract void handlePost(AtmosphereResource resource) throws IOException;

    /**
     * Handle GET requests (SSE notification streams).
     */
    protected abstract void handleGet(AtmosphereResource resource) throws IOException;

    /**
     * Handle DELETE requests (session termination).
     */
    protected abstract void handleDelete(AtmosphereResource resource) throws IOException;

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        var resource = event.getResource();
        if (event.isCancelled() || event.isClosedByClient() || event.isClosedByApplication()) {
            logger.debug("Connection closed: {}", resource.uuid());
            return;
        }

        var message = event.getMessage();
        if (message instanceof String text) {
            handleIncomingMessage(resource, text);
        } else if (message instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof String text) {
                    write(resource.getResponse(), text);
                }
            }
        }
    }

    /**
     * Handle an incoming message (WebSocket text frame or broadcast).
     * Subclasses can override for protocol-specific message processing.
     */
    protected void handleIncomingMessage(AtmosphereResource resource, String message)
            throws IOException {
        write(resource.getResponse(), message);
    }

    // ── Session management ──────────────────────────────────────────────

    /** Get the session store. */
    public Map<String, S> sessions() {
        return Collections.unmodifiableMap(sessions);
    }

    /** Restore a session from the request header, setting it as a request attribute. */
    protected S restoreSession(AtmosphereResource resource) {
        var id = resource.getRequest().getHeader(sessionIdHeader);
        if (id != null) {
            var session = sessions.get(id);
            if (session != null) {
                resource.getRequest().setAttribute(sessionAttributeKey, session);
                session.touch();
                resource.getResponse().setHeader(sessionIdHeader, session.sessionId());
                return session;
            }
        }
        return null;
    }

    /** Register a new session in the store. */
    protected void registerSession(S session, AtmosphereResponse response) {
        sessions.putIfAbsent(session.sessionId(), session);
        session.touch();
        response.setHeader(sessionIdHeader, session.sessionId());
    }

    /** Get the session from the request attribute. */
    @SuppressWarnings("unchecked")
    protected S getSessionFromRequest(AtmosphereResource resource) {
        return (S) resource.getRequest().getAttribute(sessionAttributeKey);
    }

    /** Set the session as a request attribute. */
    protected void setSessionOnRequest(AtmosphereResource resource, S session) {
        resource.getRequest().setAttribute(sessionAttributeKey, session);
    }

    /** Replay pending notifications for a session to the response. */
    protected void replayPending(S session, AtmosphereResponse response) throws IOException {
        var pending = session.drainPendingNotifications();
        for (var notification : pending) {
            response.getWriter().write("event: message\ndata: " + notification + "\n\n");
        }
        if (!pending.isEmpty()) {
            response.getWriter().flush();
            logger.debug("Replayed {} pending notifications for session {}",
                    pending.size(), session.sessionId());
        }
    }

    /** Remove a session by header ID. */
    protected S removeSessionByHeader(AtmosphereResource resource) {
        var id = resource.getRequest().getHeader(sessionIdHeader);
        if (id != null) {
            return sessions.remove(id);
        }
        return null;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Write a response as SSE or JSON depending on Accept header. */
    protected void writeResponse(AtmosphereResource resource, String jsonResponse)
            throws IOException {
        var response = resource.getResponse();
        var accept = resource.getRequest().getHeader("Accept");
        if (accept != null && accept.contains(TEXT_EVENT_STREAM)) {
            response.setStatus(200);
            response.setContentType(TEXT_EVENT_STREAM);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("event: message\ndata: " + jsonResponse + "\n\n");
            response.getWriter().flush();
        } else {
            response.setStatus(200);
            response.setContentType(APPLICATION_JSON);
            response.getWriter().write(jsonResponse);
            response.getWriter().flush();
        }
    }

    protected void write(AtmosphereResponse response, String data) throws IOException {
        response.getWriter().write(data);
        response.getWriter().flush();
    }

    protected String sessionIdHeader() {
        return sessionIdHeader;
    }

    protected String sessionAttributeKey() {
        return sessionAttributeKey;
    }

    @Override
    public void destroy() {
        cleaner.shutdownNow();
        sessions.clear();
        logger.debug("{} destroyed", getClass().getSimpleName());
    }

    private void evictExpiredSessions() {
        var it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getValue().isExpired(sessionTtlMs)) {
                it.remove();
                logger.info("Evicted expired session: {}", entry.getKey());
            }
        }
    }
}
