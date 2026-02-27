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
package org.atmosphere.ai.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;

/**
 * Parsed representation of an AI streaming wire protocol message.
 *
 * <p>The wire protocol produced by {@code DefaultStreamingSession} and
 * {@code BroadcasterStreamingSession} uses JSON messages with these fields:</p>
 * <pre>
 * {"type":"token","data":"Hello","sessionId":"abc-123","seq":1}
 * {"type":"metadata","key":"model","value":"gpt-4","sessionId":"abc-123","seq":3}
 * {"type":"complete","sessionId":"abc-123","seq":4}
 * {"type":"error","data":"Connection failed","sessionId":"abc-123","seq":6}
 * </pre>
 *
 * @param type      message type: "token", "progress", "metadata", "complete", or "error"
 * @param data      the token text, progress message, or error description (null for bare "complete")
 * @param sessionId the streaming session identifier
 * @param seq       monotonically increasing sequence number within a session
 * @param key       metadata key (only for "metadata" messages, null otherwise)
 * @param value     metadata value (only for "metadata" messages, null otherwise)
 */
public record AiStreamMessage(
        String type,
        String data,
        String sessionId,
        long seq,
        String key,
        Object value
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parse a JSON string into an {@code AiStreamMessage}.
     *
     * @param json the JSON string from the wire protocol
     * @return the parsed message, or {@code null} if the JSON does not contain a valid "type" field
     * @throws JsonProcessingException if the JSON is malformed
     */
    public static AiStreamMessage parse(String json) throws JsonProcessingException {
        var node = MAPPER.readTree(json);

        var typeNode = node.get("type");
        if (typeNode == null || typeNode.isNull()) {
            return null;
        }

        var type = typeNode.asText();
        var data = node.has("data") && !node.get("data").isNull() ? node.get("data").asText() : null;
        var sessionId = node.has("sessionId") ? node.get("sessionId").asText() : null;
        var seq = node.has("seq") ? node.get("seq").asLong() : 0L;
        var key = node.has("key") ? node.get("key").asText() : null;

        Object value = null;
        if (node.has("value")) {
            var valueNode = node.get("value");
            value = extractValue(valueNode);
        }

        return new AiStreamMessage(type, data, sessionId, seq, key, value);
    }

    /**
     * Serialize this message back to a JSON string.
     *
     * @return JSON representation of this message
     */
    public String toJson() {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", type);
        if (data != null) {
            map.put("data", data);
        }
        if (key != null) {
            map.put("key", key);
        }
        if (value != null) {
            map.put("value", value);
        }
        if (sessionId != null) {
            map.put("sessionId", sessionId);
        }
        if (seq > 0) {
            map.put("seq", seq);
        }
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize AiStreamMessage", e);
        }
    }

    public boolean isToken() {
        return "token".equals(type);
    }

    public boolean isComplete() {
        return "complete".equals(type);
    }

    public boolean isError() {
        return "error".equals(type);
    }

    public boolean isMetadata() {
        return "metadata".equals(type);
    }

    public boolean isProgress() {
        return "progress".equals(type);
    }

    /**
     * Create a new message with the data field replaced.
     *
     * @param newData the new data value
     * @return a copy of this message with updated data
     */
    public AiStreamMessage withData(String newData) {
        return new AiStreamMessage(type, newData, sessionId, seq, key, value);
    }

    private static Object extractValue(JsonNode valueNode) {
        if (valueNode.isNull()) {
            return null;
        } else if (valueNode.isTextual()) {
            return valueNode.asText();
        } else if (valueNode.isInt()) {
            return valueNode.asInt();
        } else if (valueNode.isLong()) {
            return valueNode.asLong();
        } else if (valueNode.isDouble() || valueNode.isFloat()) {
            return valueNode.asDouble();
        } else if (valueNode.isBoolean()) {
            return valueNode.asBoolean();
        } else if (valueNode.isArray()) {
            var list = new java.util.ArrayList<>();
            for (var element : valueNode) {
                list.add(extractValue(element));
            }
            return list;
        } else {
            return valueNode.toString();
        }
    }
}
