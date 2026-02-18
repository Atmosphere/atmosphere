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
 * Default implementation of {@link StreamingSession} that broadcasts
 * JSON-encoded streaming messages through an Atmosphere {@link Broadcaster}.
 *
 * <p>Wire protocol:</p>
 * <pre>
 * {"type":"token","data":"Hello","sessionId":"abc-123","seq":1}
 * {"type":"progress","data":"Thinking...","sessionId":"abc-123","seq":2}
 * {"type":"metadata","key":"model","value":"gpt-4","sessionId":"abc-123","seq":3}
 * {"type":"complete","sessionId":"abc-123","seq":4}
 * {"type":"complete","data":"Full response","sessionId":"abc-123","seq":5}
 * {"type":"error","data":"Connection failed","sessionId":"abc-123","seq":6}
 * </pre>
 */
final class DefaultStreamingSession implements StreamingSession {

    private static final Logger logger = LoggerFactory.getLogger(DefaultStreamingSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String sessionId;
    private final Broadcaster broadcaster;
    private final String resourceUuid;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong(0);

    DefaultStreamingSession(String sessionId, Broadcaster broadcaster, String resourceUuid) {
        this.sessionId = sessionId;
        this.broadcaster = broadcaster;
        this.resourceUuid = resourceUuid;
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

    String resourceUuid() {
        return resourceUuid;
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
        broadcaster.broadcast(json);
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
