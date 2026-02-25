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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.cpr.Broadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link StreamingSession} that broadcasts tokens to all subscribers of a
 * {@link Broadcaster} topic, without requiring a specific {@link org.atmosphere.cpr.AtmosphereResource}.
 *
 * <p>This is the bridge between MCP tool calls and real-time browser clients:
 * an AI agent calls an MCP tool, the tool streams LLM tokens through this session,
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
    public void send(String token) {
        if (closed.get()) {
            logger.warn("Attempted to send token on closed session {}", sessionId);
            return;
        }
        broadcast(buildMessage("token", token));
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
            broadcaster.broadcast(json);
        } catch (Exception e) {
            logger.warn("Failed to broadcast from session {}: {}", sessionId, e.getMessage());
        }
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize streaming message", e);
            return "{}";
        }
    }
}
