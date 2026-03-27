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
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link StreamingSession} that broadcasts streaming texts to all subscribers of a
 * {@link Broadcaster} topic, without requiring a specific {@link org.atmosphere.cpr.AtmosphereResource}.
 *
 * <p>This is the bridge between MCP tool calls and real-time browser clients:
 * an AI agent calls an MCP tool, the tool streams LLM streaming texts through this session,
 * and all WebSocket/SSE/gRPC clients subscribed to the broadcaster receive them.</p>
 *
 * <p>Uses the same wire protocol as {@link DefaultStreamingSession}.</p>
 */
final class BroadcasterStreamingSession implements StreamingSession {

    private static final Logger logger = LoggerFactory.getLogger(BroadcasterStreamingSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String sessionId;
    private final Broadcaster broadcaster;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong(0);

    BroadcasterStreamingSession(String sessionId, Broadcaster broadcaster) {
        this.sessionId = sessionId;
        this.broadcaster = broadcaster;
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
            broadcast(buildMessage("complete", null));
        }
    }

    @Override
    public void complete(String summary) {
        if (closed.compareAndSet(false, true)) {
            broadcast(buildMessage("complete", summary));
        }
    }

    @Override
    public void error(Throwable t) {
        if (closed.compareAndSet(false, true)) {
            var message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            broadcast(buildMessage("error", message));
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void emit(AiEvent event) {
        if (closed.get()) {
            logger.warn("Attempted to emit event on closed session {}", sessionId);
            return;
        }
        switch (event) {
            case AiEvent.Complete c -> {
                if (closed.compareAndSet(false, true)) {
                    broadcast(buildEventMessage(event));
                }
            }
            case AiEvent.Error err -> {
                if (closed.compareAndSet(false, true)) {
                    broadcast(buildEventMessage(event));
                }
            }
            default -> broadcast(buildEventMessage(event));
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
        try {
            broadcaster.broadcast(new RawMessage(json));
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
