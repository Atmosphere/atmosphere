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
 * <p>Messages are delivered only to the originating resource via
 * {@code broadcaster.broadcast(msg, Set.of(resource))}. This ensures each
 * client receives only its own AI response while still passing through
 * the broadcaster's filter/cache chain. For broadcast-to-all semantics,
 * use {@link BroadcasterStreamingSession}.</p>
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

    private final String sessionId;
    private final AtmosphereResource resource;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong(0);

    DefaultStreamingSession(String sessionId, AtmosphereResource resource) {
        this.sessionId = sessionId;
        this.resource = resource;
        SESSION_RESOURCES.put(sessionId, resource);
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
        SESSION_RESOURCES.entrySet().removeIf(e -> e.getValue().uuid().equals(resource.uuid()));
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
            broadcast(buildMessage("complete", null));
        }
    }

    @Override
    public void complete(String summary) {
        if (closed.compareAndSet(false, true)) {
            SESSION_RESOURCES.remove(sessionId);
            broadcast(buildMessage("complete", summary));
        }
    }

    @Override
    public void error(Throwable t) {
        if (closed.compareAndSet(false, true)) {
            SESSION_RESOURCES.remove(sessionId);
            logger.error("Streaming session {} error", sessionId, t);
            var message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            broadcast(buildMessage("error", message));
        }
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
                    broadcast(buildEventMessage(event));
                }
                return;
            }
            case AiEvent.Error err -> {
                if (closed.compareAndSet(false, true)) {
                    SESSION_RESOURCES.remove(sessionId);
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
        // Wrap in RawMessage so ManagedAtmosphereHandler.onStateChange()
        // delivers the JSON as-is without re-invoking @Message handlers.
        // Deliver only to the originating resource (unicast) while still
        // passing through the broadcaster's filter/cache chain.
        try {
            resource.getBroadcaster().broadcast(new RawMessage(json), Set.of(resource));
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
