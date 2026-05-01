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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RoomProtocolCodecTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

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
        // Jackson 3 raises JacksonException on parse failure; the codec
        // wraps it in IllegalArgumentException for the boundary contract.
        assertThrows(IllegalArgumentException.class, () -> {
            RoomProtocolCodec.decode("not json");
        });
    }

    @Test
    public void testDecodeMissingType() {
        assertThrows(IllegalArgumentException.class, () -> {
            RoomProtocolCodec.decode("""
                    {"room":"lobby"}""");
        });
    }

    // --- Encode tests ---

    @Test
    public void testEncodeJoinAck() throws Exception {
        var members = List.of(
                new RoomMember("alice", Map.of("avatar", "a.jpg")),
                new RoomMember("bob")
        );
        var json = RoomProtocolCodec.encodeJoinAck("lobby", members);
        var obj = MAPPER.readTree(json);

        assertEquals("join_ack", obj.get("type").stringValue());
        assertEquals("lobby", obj.get("room").stringValue());
        assertEquals(2, obj.get("members").size());

        var first = obj.get("members").get(0);
        assertEquals("alice", first.get("id").stringValue());
        assertEquals("a.jpg", first.get("metadata").get("avatar").stringValue());
    }

    @Test
    public void testEncodePresence() throws Exception {
        var member = new RoomMember("alice", Map.of("display", "Alice"));
        var json = RoomProtocolCodec.encodePresence("lobby", "join", member);
        var obj = MAPPER.readTree(json);

        assertEquals("presence", obj.get("type").stringValue());
        assertEquals("lobby", obj.get("room").stringValue());
        assertEquals("join", obj.get("action").stringValue());
        assertEquals("alice", obj.get("memberId").stringValue());
    }

    @Test
    public void testEncodePresenceNullMember() throws Exception {
        var json = RoomProtocolCodec.encodePresence("lobby", "leave", null);
        var obj = MAPPER.readTree(json);

        assertEquals("presence", obj.get("type").stringValue());
        assertFalse(hasField(obj, "memberId"));
    }

    @Test
    public void testEncodeMessage() throws Exception {
        var json = RoomProtocolCodec.encodeMessage("lobby", "alice", "hello");
        var obj = MAPPER.readTree(json);

        assertEquals("message", obj.get("type").stringValue());
        assertEquals("lobby", obj.get("room").stringValue());
        assertEquals("alice", obj.get("from").stringValue());
        assertEquals("hello", obj.get("data").stringValue());
    }

    @Test
    public void testEncodeMessageNullFrom() throws Exception {
        var json = RoomProtocolCodec.encodeMessage("lobby", null, "hello");
        var obj = MAPPER.readTree(json);

        assertEquals("message", obj.get("type").stringValue());
        assertFalse(hasField(obj, "from"));
    }

    @Test
    public void testEncodeError() throws Exception {
        var json = RoomProtocolCodec.encodeError("lobby", "Unauthorized");
        var obj = MAPPER.readTree(json);

        assertEquals("error", obj.get("type").stringValue());
        assertEquals("lobby", obj.get("room").stringValue());
        assertEquals("Unauthorized", obj.get("data").stringValue());
    }

    // --- Helpers ---

    private void assertInstanceOf(Object obj, Class<?> clazz) {
        assertTrue(clazz.isInstance(obj), "Expected " + clazz.getSimpleName() + " but got " + obj.getClass().getSimpleName());
    }

    private static boolean hasField(JsonNode node, String key) {
        return node.get(key) != null;
    }
}
