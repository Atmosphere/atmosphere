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
package org.atmosphere.ai;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of {@link StreamingSession} that writes
 * JSON-encoded streaming messages directly to an {@link AtmosphereResource}.
 *
 * <p>By default, messages are delivered only to the originating resource via
 * {@code broadcaster.broadcast(msg, Set.of(resource))}. This ensures each
 * client receives only its own AI response while still passing through
 * the broadcaster's filter/cache chain.</p>
 *
 * <p>When constructed in room-broadcast mode (used by
 * {@code @AiEndpoint(broadcastReply = true)} for shared rooms), the reply is
 * instead delivered to every subscriber on the resource's (per-path)
 * broadcaster via {@code broadcaster.broadcast(msg)} — one reply fans out to
 * the whole room. The prompt is still dispatched to a single {@code @Prompt}
 * handler upstream, so the model runs exactly once regardless of room size.
 * For a broadcaster-only session with no originating resource, use
 * {@link BroadcasterStreamingSession}.</p>
 *
 * <p>Wire protocol:</p>
 * <pre>
 * {"type":"streaming-text","data":"Hello","sessionId":"abc-123","seq":1}
 * {"type":"progress","data":"Thinking...","sessionId":"abc-123","seq":2}
 * {"type":"metadata","key":"model","value":"gpt-4","sessionId":"abc-123","seq":3}
 * {"type":"complete","sessionId":"abc-123","seq":4}
 * {"type":"complete","data":"Full response","sessionId":"abc-123","seq":5}
 * {"type":"error","data":"Connection failed","sessionId":"abc-123","seq":6}
 * </pre>
 */
public final class DefaultStreamingSession implements StreamingSession {

    private static final Logger logger = LoggerFactory.getLogger(DefaultStreamingSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ConcurrentHashMap<String, AtmosphereResource> SESSION_RESOURCES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DefaultStreamingSession> SESSION_INSTANCES = new ConcurrentHashMap<>();

    private final String sessionId;
    private final AtmosphereResource resource;
    private final boolean broadcastToRoom;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean errored = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong(0);
    /**
     * Last outbound-activity timestamp (epoch millis), refreshed on every
     * broadcast so {@link #sweepExpired} never reaps a session that is still
     * streaming. Package-private volatile so tests can age a session without
     * a clock indirection on this hot path.
     */
    volatile long lastActivityMillis = System.currentTimeMillis();

    DefaultStreamingSession(String sessionId, AtmosphereResource resource) {
        this(sessionId, resource, false);
    }

    /**
     * @param broadcastToRoom when {@code true}, the reply fans out to every
     *                        subscriber on the resource's broadcaster (the
     *                        room) instead of only the originating resource
     */
    DefaultStreamingSession(String sessionId, AtmosphereResource resource, boolean broadcastToRoom) {
        this.sessionId = sessionId;
        this.resource = resource;
        this.broadcastToRoom = broadcastToRoom;
        SESSION_RESOURCES.put(sessionId, resource);
        SESSION_INSTANCES.put(sessionId, this);
        StreamingSessionSweeper.ensureStarted();
    }

    /**
     * Look up the {@link AtmosphereResource} for a given session ID.
     * Used by broadcast filters to deliver deferred messages via unicast.
     *
     * @param sessionId the streaming session identifier
     * @return the resource, or empty if no active session with that ID
     */
    public static Optional<AtmosphereResource> resourceForSession(String sessionId) {
        return Optional.ofNullable(SESSION_RESOURCES.get(sessionId));
    }

    /**
     * Remove all sessions associated with a disconnecting resource.
     * Called from {@code AiEndpointHandler} when a client disconnects
     * before the streaming session completes.
     */
    public static void cleanupResource(AtmosphereResource resource) {
        cleanupResource(resource.uuid());
    }

    /**
     * Remove all sessions associated with a disconnecting resource by its
     * UUID. Used by the recycled-disconnect path where the container has
     * already stripped the {@link AtmosphereResource} from the event but the
     * event still carries the UUID it was created with.
     *
     * @param resourceUuid the atmosphere resource UUID (may be null; no-op)
     */
    public static void cleanupResource(String resourceUuid) {
        if (resourceUuid == null) {
            return;
        }
        SESSION_RESOURCES.entrySet().removeIf(e -> {
            if (e.getValue().uuid().equals(resourceUuid)) {
                var session = SESSION_INSTANCES.remove(e.getKey());
                if (session != null) {
                    session.closed.set(true);
                }
                return true;
            }
            return false;
        });
    }

    /**
     * Remove sessions whose last outbound activity is older than
     * {@code ttlMs}. Invoked by {@link StreamingSessionSweeper} so orphaned
     * sessions — a runtime that never fires a terminal event, a disconnect
     * the container never delivered — cannot grow the process-global maps
     * without bound (Correctness Invariant #3). Reaped sessions are marked
     * closed so late writes from a stuck runtime are dropped at the wire
     * layer instead of resurrecting the entry.
     *
     * @param ttlMs the idle TTL in milliseconds
     * @return the number of sessions removed
     */
    static int sweepExpired(long ttlMs) {
        var cutoff = System.currentTimeMillis() - ttlMs;
        var removed = 0;
        for (var entry : SESSION_INSTANCES.entrySet()) {
            var session = entry.getValue();
            if (session.lastActivityMillis < cutoff
                    && SESSION_INSTANCES.remove(entry.getKey(), session)) {
                SESSION_RESOURCES.remove(entry.getKey());
                session.closed.set(true);
                removed++;
                logger.debug("Swept idle streaming session {}", entry.getKey());
            }
        }
        return removed;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public void send(String text) {
        if (closed.get()) {
            logger.warn("Attempted to send streaming text on closed session {}", sessionId);
            return;
        }
        broadcast(buildMessage("streaming-text", text));
    }

    @Override
    public void sendMetadata(String key, Object value) {
        if (closed.get()) {
            return;
        }
        var msg = new LinkedHashMap<String, Object>();
        msg.put("type", "metadata");
        msg.put("key", key);
        msg.put("value", value);
        msg.put("sessionId", sessionId);
        msg.put("seq", sequence.incrementAndGet());
        broadcast(toJson(msg));
    }

    @Override
    public void progress(String message) {
        if (closed.get()) {
            return;
        }
        broadcast(buildMessage("progress", message));
    }

    @Override
    public void complete() {
        if (closed.compareAndSet(false, true)) {
            SESSION_RESOURCES.remove(sessionId);
            SESSION_INSTANCES.remove(sessionId);
            broadcast(buildMessage("complete", null));
        }
    }

    @Override
    public void complete(String summary) {
        if (closed.compareAndSet(false, true)) {
            SESSION_RESOURCES.remove(sessionId);
            SESSION_INSTANCES.remove(sessionId);
            broadcast(buildMessage("complete", summary));
        }
    }

    @Override
    public void error(Throwable t) {
        errored.set(true);
        if (closed.compareAndSet(false, true)) {
            SESSION_RESOURCES.remove(sessionId);
            SESSION_INSTANCES.remove(sessionId);
            logger.error("Streaming session {} error", sessionId, t);
            var message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            broadcast(buildMessage("error", message));
        }
    }

    @Override
    public boolean hasErrored() {
        return errored.get();
    }

    @Override
    public void close() {
        complete();
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void emit(AiEvent event) {
        // Terminal events must transition the closed state
        switch (event) {
            case AiEvent.Complete c -> {
                if (closed.compareAndSet(false, true)) {
                    SESSION_RESOURCES.remove(sessionId);
                    SESSION_INSTANCES.remove(sessionId);
                    broadcast(buildEventMessage(event));
                }
                return;
            }
            case AiEvent.Error err -> {
                if (closed.compareAndSet(false, true)) {
                    SESSION_RESOURCES.remove(sessionId);
                    SESSION_INSTANCES.remove(sessionId);
                    logger.error("Streaming session {} error: {}", sessionId, err.message());
                    broadcast(buildMessage("error", err.message()));
                }
                return;
            }
            default -> {
                if (closed.get()) {
                    logger.warn("Attempted to emit event on closed session {}", sessionId);
                    return;
                }
                broadcast(buildEventMessage(event));
            }
        }
    }

    @Override
    public void sendContent(Content content) {
        if (closed.get()) {
            return;
        }
        switch (content) {
            case Content.Text text -> send(text.text());
            case Content.Image image -> {
                var msg = new LinkedHashMap<String, Object>();
                msg.put("type", "content");
                msg.put("contentType", "image");
                msg.put("mimeType", image.mimeType());
                msg.put("data", image.dataBase64());
                msg.put("sessionId", sessionId);
                msg.put("seq", sequence.incrementAndGet());
                broadcast(toJson(msg));
            }
            case Content.File file -> {
                var msg = new LinkedHashMap<String, Object>();
                msg.put("type", "content");
                msg.put("contentType", "file");
                msg.put("mimeType", file.mimeType());
                msg.put("fileName", file.fileName());
                msg.put("data", file.dataBase64());
                msg.put("sessionId", sessionId);
                msg.put("seq", sequence.incrementAndGet());
                broadcast(toJson(msg));
            }
            case Content.Audio audio -> {
                // Phase 4: audio variant for multi-modal parts.
                var msg = new LinkedHashMap<String, Object>();
                msg.put("type", "content");
                msg.put("contentType", "audio");
                msg.put("mimeType", audio.mimeType());
                msg.put("data", audio.dataBase64());
                msg.put("sessionId", sessionId);
                msg.put("seq", sequence.incrementAndGet());
                broadcast(toJson(msg));
            }
        }
    }

    String resourceUuid() {
        return resource.uuid();
    }

    private String buildEventMessage(AiEvent event) {
        var msg = new LinkedHashMap<String, Object>();
        msg.put("event", event.eventType());
        msg.put("data", event);
        msg.put("sessionId", sessionId);
        msg.put("seq", sequence.incrementAndGet());
        return toJson(msg);
    }

    private String buildMessage(String type, String data) {
        var msg = new LinkedHashMap<String, Object>();
        msg.put("type", type);
        if (data != null) {
            msg.put("data", data);
        }
        msg.put("sessionId", sessionId);
        msg.put("seq", sequence.incrementAndGet());
        return toJson(msg);
    }

    private void broadcast(String json) {
        // Every outbound frame refreshes the TTL clock so the sweeper never
        // reaps a session that is actively streaming.
        lastActivityMillis = System.currentTimeMillis();
        // Wrap in RawMessage so ManagedAtmosphereHandler.onStateChange()
        // delivers the JSON as-is without re-invoking @Message handlers.
        try {
            var broadcaster = resource.getBroadcaster();
            if (broadcastToRoom) {
                // Room fan-out (@AiEndpoint.broadcastReply=true): deliver the
                // single reply to every subscriber on the per-path broadcaster,
                // not just the originating resource. The prompt was dispatched
                // to one @Prompt handler upstream, so the model ran exactly once.
                broadcaster.broadcast(new RawMessage(json));
            } else {
                // Default: deliver only to the originating resource (unicast)
                // while still passing through the broadcaster's filter/cache chain.
                broadcaster.broadcast(new RawMessage(json), Set.of(resource));
            }
        } catch (Exception e) {
            logger.warn("Failed to broadcast from session {}: {}", sessionId, e.getMessage());
        }
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JacksonException e) {
            logger.error("Failed to serialize streaming message", e);
            return "{}";
        }
    }
}
