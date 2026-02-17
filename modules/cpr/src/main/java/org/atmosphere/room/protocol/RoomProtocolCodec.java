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
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * JSON encoder/decoder for the room protocol, using {@code org.json}
 * (already a dependency of atmosphere-runtime via {@code SimpleRestInterceptor}).
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
        var obj = new JSONObject(json);
        var type = obj.getString("type");
        var room = obj.getString("room");

        return switch (type) {
            case "join" -> new RoomProtocolMessage.Join(
                    room,
                    obj.optString("memberId", null),
                    toMap(obj.optJSONObject("metadata"))
            );
            case "leave" -> new RoomProtocolMessage.Leave(room);
            case "broadcast" -> new RoomProtocolMessage.Broadcast(room, obj.get("data"));
            case "direct" -> new RoomProtocolMessage.Direct(
                    room,
                    obj.getString("targetId"),
                    obj.get("data")
            );
            default -> throw new IllegalArgumentException("Unknown room protocol type: " + type);
        };
    }

    /**
     * Encode a join acknowledgement with the current member list.
     */
    public static String encodeJoinAck(String room, Collection<RoomMember> members) {
        var obj = new JSONObject();
        obj.put("type", "join_ack");
        obj.put("room", room);
        var arr = new JSONArray();
        for (var m : members) {
            var memberObj = new JSONObject();
            memberObj.put("id", m.id());
            memberObj.put("metadata", new JSONObject(m.metadata()));
            arr.put(memberObj);
        }
        obj.put("members", arr);
        return obj.toString();
    }

    /**
     * Encode a presence event (join or leave) for broadcast to other members.
     */
    public static String encodePresence(String room, String action, RoomMember member) {
        var obj = new JSONObject();
        obj.put("type", "presence");
        obj.put("room", room);
        obj.put("action", action);
        if (member != null) {
            obj.put("memberId", member.id());
            obj.put("metadata", new JSONObject(member.metadata()));
        }
        return obj.toString();
    }

    /**
     * Encode a room message for delivery to clients.
     */
    public static String encodeMessage(String room, String fromMemberId, Object data) {
        var obj = new JSONObject();
        obj.put("type", "message");
        obj.put("room", room);
        if (fromMemberId != null) {
            obj.put("from", fromMemberId);
        }
        obj.put("data", data);
        return obj.toString();
    }

    /**
     * Encode an error response.
     */
    public static String encodeError(String room, String message) {
        var obj = new JSONObject();
        obj.put("type", "error");
        obj.put("room", room);
        obj.put("data", message);
        return obj.toString();
    }

    private static Map<String, Object> toMap(JSONObject json) {
        if (json == null) {
            return null;
        }
        var map = new HashMap<String, Object>();
        for (var key : json.keySet()) {
            map.put(key, json.get(key));
        }
        return map;
    }
}
