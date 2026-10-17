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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;

/**
 * Parsed representation of an AI streaming wire protocol message.
 *
 * <p>The wire protocol produced by {@code DefaultStreamingSession} and
 * {@code BroadcasterStreamingSession} uses JSON messages with these fields:</p>
 * <pre>
 * {"type":"streaming-text","data":"Hello","sessionId":"abc-123","seq":1}
 * {"type":"metadata","key":"model","value":"gpt-4","sessionId":"abc-123","seq":3}
 * {"type":"complete","sessionId":"abc-123","seq":4}
 * {"type":"error","data":"Connection failed","sessionId":"abc-123","seq":6}
 * {"type":"cancelled","data":"disconnect","sessionId":"abc-123","seq":7}
 * {"type":"prompt","text":"What is 2+2?","from":"&lt;prompter room member id&gt;","sessionId":"abc-123","seq":1}
 * </pre>
 *
 * <p>A {@code prompt} frame echoes a room member's prompt to an
 * {@code @AiEndpoint(broadcastReply = true)} room: the prompt is in {@code text}
 * (never {@code data}, which a client that does not know the type renders as
 * reply text) and {@code from} is the room member id of the member who sent it
 * ({@link org.atmosphere.ai.RoomMemberIds}), never its tracking id.</p>
 *
 * @param type      message type: "streaming-text", "progress", "metadata", "complete", "error",
 *                  "cancelled" or "prompt"
 * @param data      the streaming text data, progress message, error description, or the cancel
 *                  reason code of a "cancelled" frame (null for bare "complete")
 * @param sessionId the streaming session identifier
 * @param seq       monotonically increasing sequence number within a session
 * @param key       metadata key (only for "metadata" messages, null otherwise)
 * @param value     metadata value (only for "metadata" messages, null otherwise)
 * @param text      the prompt of a "prompt" frame, null otherwise
 * @param from      the room member id of the member who sent the prompt of a
 *                  "prompt" frame, null otherwise
 */
public record AiStreamMessage(
        String type,
        String data,
        String sessionId,
        long seq,
        String key,
        Object value,
        String text,
        String from
) {

    /** The type of the frame that echoes a room member's prompt to the room. */
    public static final String PROMPT_TYPE = "prompt";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A message of any type but {@code prompt}: it carries no {@code text} or {@code from}. */
    public AiStreamMessage(String type, String data, String sessionId, long seq, String key, Object value) {
        this(type, data, sessionId, seq, key, value, null, null);
    }

    /**
     * The {@code prompt} frame echoing {@code text}, sent by the room member {@code from}.
     *
     * @param text      the prompt
     * @param from      the room member id of the member who sent it
     * @param sessionId the session of the turn the prompt starts
     * @param seq       the frame's sequence number in that session
     * @return the frame
     */
    public static AiStreamMessage prompt(String text, String from, String sessionId, long seq) {
        return new AiStreamMessage(PROMPT_TYPE, null, sessionId, seq, null, null, text, from);
    }

    /**
     * Parse a JSON string into an {@code AiStreamMessage}.
     *
     * @param json the JSON string from the wire protocol
     * @return the parsed message, or {@code null} if the JSON does not contain a valid "type" field
     * @throws JacksonException if the JSON is malformed
     */
    public static AiStreamMessage parse(String json) throws JacksonException {
        var node = MAPPER.readTree(json);

        var typeNode = node.get("type");
        if (typeNode == null || typeNode.isNull()) {
            return null;
        }

        var type = typeNode.stringValue();
        var data = node.has("data") && !node.get("data").isNull() ? node.get("data").stringValue() : null;
        var sessionId = node.has("sessionId") ? node.get("sessionId").stringValue() : null;
        var seq = node.has("seq") ? node.get("seq").asLong() : 0L;
        var key = node.has("key") ? node.get("key").stringValue() : null;
        var text = node.has("text") && !node.get("text").isNull() ? node.get("text").stringValue() : null;
        var from = node.has("from") && !node.get("from").isNull() ? node.get("from").stringValue() : null;

        Object value = null;
        if (node.has("value")) {
            var valueNode = node.get("value");
            value = extractValue(valueNode);
        }

        return new AiStreamMessage(type, data, sessionId, seq, key, value, text, from);
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
        if (text != null) {
            map.put("text", text);
        }
        if (from != null) {
            map.put("from", from);
        }
        if (sessionId != null) {
            map.put("sessionId", sessionId);
        }
        if (seq > 0) {
            map.put("seq", seq);
        }
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize AiStreamMessage", e);
        }
    }

    public boolean isStreamingText() {
        return "streaming-text".equals(type);
    }

    public boolean isComplete() {
        return "complete".equals(type);
    }

    public boolean isError() {
        return "error".equals(type);
    }

    public boolean isCancelled() {
        return org.atmosphere.ai.RunCancelledException.FRAME_TYPE.equals(type);
    }

    public boolean isMetadata() {
        return "metadata".equals(type);
    }

    public boolean isProgress() {
        return "progress".equals(type);
    }

    /** Whether this frame echoes a room member's prompt. */
    public boolean isPrompt() {
        return PROMPT_TYPE.equals(type);
    }

    /**
     * Create a new message with the data field replaced.
     *
     * @param newData the new data value
     * @return a copy of this message with updated data
     */
    public AiStreamMessage withData(String newData) {
        return new AiStreamMessage(type, newData, sessionId, seq, key, value, text, from);
    }

    /**
     * Create a new message with the sequence number replaced.
     *
     * @param newSeq the new sequence number
     * @return a copy of this message with updated seq
     */
    public AiStreamMessage withSeq(long newSeq) {
        return new AiStreamMessage(type, data, sessionId, newSeq, key, value, text, from);
    }

    /**
     * Create a new message with the prompt text replaced.
     *
     * @param newText the new prompt text
     * @return a copy of this message with updated text
     */
    public AiStreamMessage withText(String newText) {
        return new AiStreamMessage(type, data, sessionId, seq, key, value, newText, from);
    }

    private static Object extractValue(JsonNode valueNode) {
        if (valueNode.isNull()) {
            return null;
        } else if (valueNode.isString()) {
            return valueNode.stringValue();
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
