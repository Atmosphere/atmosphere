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
package org.atmosphere.room.protocol;

import org.atmosphere.room.RoomMember;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON encoder/decoder for the room protocol, backed by Jackson 3
 * ({@code tools.jackson.databind}). Jackson is the project's single
 * JSON dependency; the legacy {@code org.json:json} backend was
 * retired to avoid the periodic CVE churn that library carries.
 *
 * <h3>Wire format (client → server)</h3>
 * <pre>{@code
 * { "type": "join",      "room": "lobby", "memberId": "alice", "metadata": { "avatar": "..." } }
 * { "type": "leave",     "room": "lobby" }
 * { "type": "broadcast", "room": "lobby", "data": "hello" }
 * { "type": "direct",    "room": "lobby", "targetId": "bob", "data": "hi bob" }
 * }</pre>
 *
 * <h3>Wire format (server → client)</h3>
 * <pre>{@code
 * { "type": "join_ack",  "room": "lobby", "members": [...] }
 * { "type": "presence",  "room": "lobby", "action": "join|leave", "memberId": "alice", "metadata": {...} }
 * { "type": "message",   "room": "lobby", "from": "alice", "data": "hello" }
 * { "type": "error",     "room": "lobby", "data": "Unauthorized" }
 * }</pre>
 *
 * @since 4.0
 */
public final class RoomProtocolCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private RoomProtocolCodec() {
    }

    /**
     * Decode a JSON string into a protocol message.
     *
     * @param json the raw JSON string
     * @return the decoded message
     * @throws IllegalArgumentException if the JSON is malformed or has an unknown type
     */
    public static RoomProtocolMessage decode(String json) {
        JsonNode obj;
        try {
            obj = MAPPER.readTree(json);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Malformed room protocol JSON: " + ex.getMessage(), ex);
        }
        String type = requireText(obj, "type");
        String room = requireText(obj, "room");

        return switch (type) {
            case "join" -> new RoomProtocolMessage.Join(
                    room,
                    optString(obj, "memberId"),
                    toMap(obj.get("metadata"))
            );
            case "leave" -> new RoomProtocolMessage.Leave(room);
            case "broadcast" -> new RoomProtocolMessage.Broadcast(room, nodeToValue(obj.get("data")));
            case "direct" -> new RoomProtocolMessage.Direct(
                    room,
                    requireText(obj, "targetId"),
                    nodeToValue(obj.get("data"))
            );
            case "typing" -> new RoomProtocolMessage.Typing(room, optBoolean(obj, "typing", true));
            default -> throw new IllegalArgumentException("Unknown room protocol type: " + type);
        };
    }

    /**
     * Encode a join acknowledgement with the current member list.
     */
    public static String encodeJoinAck(String room, Collection<RoomMember> members) {
        ObjectNode obj = MAPPER.createObjectNode();
        obj.put("type", "join_ack");
        obj.put("room", room);
        ArrayNode arr = obj.putArray("members");
        for (RoomMember m : members) {
            ObjectNode memberObj = arr.addObject();
            memberObj.put("id", m.id());
            memberObj.set("metadata", MAPPER.valueToTree(m.metadata()));
        }
        return obj.toString();
    }

    /**
     * Encode a presence event (join or leave) for broadcast to other members.
     */
    public static String encodePresence(String room, String action, RoomMember member) {
        ObjectNode obj = MAPPER.createObjectNode();
        obj.put("type", "presence");
        obj.put("room", room);
        obj.put("action", action);
        if (member != null) {
            obj.put("memberId", member.id());
            obj.set("metadata", MAPPER.valueToTree(member.metadata()));
        }
        return obj.toString();
    }

    /**
     * Encode a room message for delivery to clients.
     */
    public static String encodeMessage(String room, String fromMemberId, Object data) {
        ObjectNode obj = MAPPER.createObjectNode();
        obj.put("type", "message");
        obj.put("room", room);
        if (fromMemberId != null) {
            obj.put("from", fromMemberId);
        }
        obj.set("data", MAPPER.valueToTree(data));
        return obj.toString();
    }

    /**
     * Encode an error response.
     */
    public static String encodeError(String room, String message) {
        ObjectNode obj = MAPPER.createObjectNode();
        obj.put("type", "error");
        obj.put("room", room);
        obj.put("data", message);
        return obj.toString();
    }

    /**
     * Encode a typing indicator for broadcast to other room members.
     */
    public static String encodeTyping(String room, String memberId, boolean typing) {
        ObjectNode obj = MAPPER.createObjectNode();
        obj.put("type", "typing");
        obj.put("room", room);
        if (memberId != null) {
            obj.put("memberId", memberId);
        }
        obj.put("typing", typing);
        return obj.toString();
    }

    private static String requireText(JsonNode obj, String key) {
        JsonNode node = obj.get(key);
        if (node == null || node.isNull() || !node.isString()) {
            throw new IllegalArgumentException(
                    "Room protocol message missing required string field '" + key + "'");
        }
        return node.stringValue();
    }

    private static String optString(JsonNode obj, String key) {
        JsonNode node = obj.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isString() ? node.stringValue() : node.toString();
    }

    private static boolean optBoolean(JsonNode obj, String key, boolean defaultValue) {
        JsonNode node = obj.get(key);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        return node.isBoolean() ? node.booleanValue() : defaultValue;
    }

    private static Map<String, Object> toMap(JsonNode obj) {
        if (obj == null || !obj.isObject()) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (var entry : obj.properties()) {
            map.put(entry.getKey(), nodeToValue(entry.getValue()));
        }
        return map;
    }

    /**
     * Convert a {@link JsonNode} into the closest plain-Java value: {@code String},
     * {@code Number}, {@code Boolean}, {@code null}, {@code Map<String, Object>}
     * for objects, or {@code List<Object>} for arrays. Mirrors the surface
     * the legacy {@code org.json} backend exposed via {@code obj.get(key)}.
     */
    private static Object nodeToValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isString()) {
            return node.stringValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isInt() || node.isLong()) {
            return node.longValue();
        }
        if (node.isFloat() || node.isDouble() || node.isBigDecimal()) {
            return node.doubleValue();
        }
        if (node.isObject()) {
            return toMap(node);
        }
        if (node.isArray()) {
            var list = new java.util.ArrayList<Object>(node.size());
            for (JsonNode child : node) {
                list.add(nodeToValue(child));
            }
            return list;
        }
        return node.toString();
    }
}
