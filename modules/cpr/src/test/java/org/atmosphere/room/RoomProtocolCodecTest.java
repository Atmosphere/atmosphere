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
package org.atmosphere.room;

import java.util.List;
import java.util.Map;

import org.atmosphere.room.protocol.RoomProtocolCodec;
import org.atmosphere.room.protocol.RoomProtocolMessage;
import org.json.JSONObject;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RoomProtocolCodecTest {

    // --- Decode tests ---

    @Test
    public void testDecodeJoin() {
        var json = """
                {"type":"join","room":"lobby","memberId":"alice","metadata":{"avatar":"pic.jpg"}}""";
        var msg = RoomProtocolCodec.decode(json);

        assertInstanceOf(msg, RoomProtocolMessage.Join.class);
        var join = (RoomProtocolMessage.Join) msg;
        assertEquals("lobby", join.room());
        assertEquals("alice", join.memberId());
        assertEquals("pic.jpg", join.metadata().get("avatar"));
    }

    @Test
    public void testDecodeJoinMinimal() {
        var json = """
                {"type":"join","room":"lobby"}""";
        var msg = RoomProtocolCodec.decode(json);

        assertInstanceOf(msg, RoomProtocolMessage.Join.class);
        var join = (RoomProtocolMessage.Join) msg;
        assertEquals("lobby", join.room());
        assertNull(join.memberId());
        assertTrue(join.metadata().isEmpty());
    }

    @Test
    public void testDecodeLeave() {
        var json = """
                {"type":"leave","room":"lobby"}""";
        var msg = RoomProtocolCodec.decode(json);

        assertInstanceOf(msg, RoomProtocolMessage.Leave.class);
        assertEquals("lobby", msg.room());
    }

    @Test
    public void testDecodeBroadcast() {
        var json = """
                {"type":"broadcast","room":"lobby","data":"hello world"}""";
        var msg = RoomProtocolCodec.decode(json);

        assertInstanceOf(msg, RoomProtocolMessage.Broadcast.class);
        var broadcast = (RoomProtocolMessage.Broadcast) msg;
        assertEquals("lobby", broadcast.room());
        assertEquals("hello world", broadcast.data());
    }

    @Test
    public void testDecodeDirect() {
        var json = """
                {"type":"direct","room":"lobby","targetId":"bob","data":"hi bob"}""";
        var msg = RoomProtocolCodec.decode(json);

        assertInstanceOf(msg, RoomProtocolMessage.Direct.class);
        var direct = (RoomProtocolMessage.Direct) msg;
        assertEquals("lobby", direct.room());
        assertEquals("bob", direct.targetId());
        assertEquals("hi bob", direct.data());
    }

    @Test
    public void testDecodeUnknownType() {
            assertThrows(IllegalArgumentException.class, () -> {
            RoomProtocolCodec.decode("""
                    {"type":"unknown","room":"lobby"}""");
            });
    }

    @Test
    public void testDecodeMalformedJson() {
            assertThrows(org.json.JSONException.class, () -> {
            RoomProtocolCodec.decode("not json");
            });
    }

    @Test
    public void testDecodeMissingType() {
            assertThrows(org.json.JSONException.class, () -> {
            RoomProtocolCodec.decode("""
                    {"room":"lobby"}""");
            });
    }

    // --- Encode tests ---

    @Test
    public void testEncodeJoinAck() {
        var members = List.of(
                new RoomMember("alice", Map.of("avatar", "a.jpg")),
                new RoomMember("bob")
        );
        var json = RoomProtocolCodec.encodeJoinAck("lobby", members);
        var obj = new JSONObject(json);

        assertEquals("join_ack", obj.getString("type"));
        assertEquals("lobby", obj.getString("room"));
        assertEquals(2, obj.getJSONArray("members").length());

        var first = obj.getJSONArray("members").getJSONObject(0);
        assertEquals("alice", first.getString("id"));
        assertEquals("a.jpg", first.getJSONObject("metadata").getString("avatar"));
    }

    @Test
    public void testEncodePresence() {
        var member = new RoomMember("alice", Map.of("display", "Alice"));
        var json = RoomProtocolCodec.encodePresence("lobby", "join", member);
        var obj = new JSONObject(json);

        assertEquals("presence", obj.getString("type"));
        assertEquals("lobby", obj.getString("room"));
        assertEquals("join", obj.getString("action"));
        assertEquals("alice", obj.getString("memberId"));
    }

    @Test
    public void testEncodePresenceNullMember() {
        var json = RoomProtocolCodec.encodePresence("lobby", "leave", null);
        var obj = new JSONObject(json);

        assertEquals("presence", obj.getString("type"));
        assertFalse(obj.has("memberId"));
    }

    @Test
    public void testEncodeMessage() {
        var json = RoomProtocolCodec.encodeMessage("lobby", "alice", "hello");
        var obj = new JSONObject(json);

        assertEquals("message", obj.getString("type"));
        assertEquals("lobby", obj.getString("room"));
        assertEquals("alice", obj.getString("from"));
        assertEquals("hello", obj.getString("data"));
    }

    @Test
    public void testEncodeMessageNullFrom() {
        var json = RoomProtocolCodec.encodeMessage("lobby", null, "hello");
        var obj = new JSONObject(json);

        assertEquals("message", obj.getString("type"));
        assertFalse(obj.has("from"));
    }

    @Test
    public void testEncodeError() {
        var json = RoomProtocolCodec.encodeError("lobby", "Unauthorized");
        var obj = new JSONObject(json);

        assertEquals("error", obj.getString("type"));
        assertEquals("lobby", obj.getString("room"));
        assertEquals("Unauthorized", obj.getString("data"));
    }

    // --- Helpers ---

    private void assertInstanceOf(Object obj, Class<?> clazz) {
        assertTrue(clazz.isInstance(obj), "Expected " + clazz.getSimpleName() + " but got " + obj.getClass().getSimpleName());
    }
}
