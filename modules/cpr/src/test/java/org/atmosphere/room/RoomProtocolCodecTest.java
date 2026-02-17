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

import static org.testng.Assert.*;

import java.util.List;
import java.util.Map;

import org.atmosphere.room.protocol.RoomProtocolCodec;
import org.atmosphere.room.protocol.RoomProtocolMessage;
import org.json.JSONObject;
import org.testng.annotations.Test;

public class RoomProtocolCodecTest {

    // --- Decode tests ---

    @Test
    public void testDecodeJoin() {
        var json = """
                {"type":"join","room":"lobby","memberId":"alice","metadata":{"avatar":"pic.jpg"}}""";
        var msg = RoomProtocolCodec.decode(json);

        assertInstanceOf(msg, RoomProtocolMessage.Join.class);
        var join = (RoomProtocolMessage.Join) msg;
        assertEquals(join.room(), "lobby");
        assertEquals(join.memberId(), "alice");
        assertEquals(join.metadata().get("avatar"), "pic.jpg");
    }

    @Test
    public void testDecodeJoinMinimal() {
        var json = """
                {"type":"join","room":"lobby"}""";
        var msg = RoomProtocolCodec.decode(json);

        assertInstanceOf(msg, RoomProtocolMessage.Join.class);
        var join = (RoomProtocolMessage.Join) msg;
        assertEquals(join.room(), "lobby");
        assertNull(join.memberId());
        assertTrue(join.metadata().isEmpty());
    }

    @Test
    public void testDecodeLeave() {
        var json = """
                {"type":"leave","room":"lobby"}""";
        var msg = RoomProtocolCodec.decode(json);

        assertInstanceOf(msg, RoomProtocolMessage.Leave.class);
        assertEquals(msg.room(), "lobby");
    }

    @Test
    public void testDecodeBroadcast() {
        var json = """
                {"type":"broadcast","room":"lobby","data":"hello world"}""";
        var msg = RoomProtocolCodec.decode(json);

        assertInstanceOf(msg, RoomProtocolMessage.Broadcast.class);
        var broadcast = (RoomProtocolMessage.Broadcast) msg;
        assertEquals(broadcast.room(), "lobby");
        assertEquals(broadcast.data(), "hello world");
    }

    @Test
    public void testDecodeDirect() {
        var json = """
                {"type":"direct","room":"lobby","targetId":"bob","data":"hi bob"}""";
        var msg = RoomProtocolCodec.decode(json);

        assertInstanceOf(msg, RoomProtocolMessage.Direct.class);
        var direct = (RoomProtocolMessage.Direct) msg;
        assertEquals(direct.room(), "lobby");
        assertEquals(direct.targetId(), "bob");
        assertEquals(direct.data(), "hi bob");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testDecodeUnknownType() {
        RoomProtocolCodec.decode("""
                {"type":"unknown","room":"lobby"}""");
    }

    @Test(expectedExceptions = org.json.JSONException.class)
    public void testDecodeMalformedJson() {
        RoomProtocolCodec.decode("not json");
    }

    @Test(expectedExceptions = org.json.JSONException.class)
    public void testDecodeMissingType() {
        RoomProtocolCodec.decode("""
                {"room":"lobby"}""");
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

        assertEquals(obj.getString("type"), "join_ack");
        assertEquals(obj.getString("room"), "lobby");
        assertEquals(obj.getJSONArray("members").length(), 2);

        var first = obj.getJSONArray("members").getJSONObject(0);
        assertEquals(first.getString("id"), "alice");
        assertEquals(first.getJSONObject("metadata").getString("avatar"), "a.jpg");
    }

    @Test
    public void testEncodePresence() {
        var member = new RoomMember("alice", Map.of("display", "Alice"));
        var json = RoomProtocolCodec.encodePresence("lobby", "join", member);
        var obj = new JSONObject(json);

        assertEquals(obj.getString("type"), "presence");
        assertEquals(obj.getString("room"), "lobby");
        assertEquals(obj.getString("action"), "join");
        assertEquals(obj.getString("memberId"), "alice");
    }

    @Test
    public void testEncodePresenceNullMember() {
        var json = RoomProtocolCodec.encodePresence("lobby", "leave", null);
        var obj = new JSONObject(json);

        assertEquals(obj.getString("type"), "presence");
        assertFalse(obj.has("memberId"));
    }

    @Test
    public void testEncodeMessage() {
        var json = RoomProtocolCodec.encodeMessage("lobby", "alice", "hello");
        var obj = new JSONObject(json);

        assertEquals(obj.getString("type"), "message");
        assertEquals(obj.getString("room"), "lobby");
        assertEquals(obj.getString("from"), "alice");
        assertEquals(obj.getString("data"), "hello");
    }

    @Test
    public void testEncodeMessageNullFrom() {
        var json = RoomProtocolCodec.encodeMessage("lobby", null, "hello");
        var obj = new JSONObject(json);

        assertEquals(obj.getString("type"), "message");
        assertFalse(obj.has("from"));
    }

    @Test
    public void testEncodeError() {
        var json = RoomProtocolCodec.encodeError("lobby", "Unauthorized");
        var obj = new JSONObject(json);

        assertEquals(obj.getString("type"), "error");
        assertEquals(obj.getString("room"), "lobby");
        assertEquals(obj.getString("data"), "Unauthorized");
    }

    // --- Helpers ---

    private void assertInstanceOf(Object obj, Class<?> clazz) {
        assertTrue(clazz.isInstance(obj), "Expected " + clazz.getSimpleName() + " but got " + obj.getClass().getSimpleName());
    }
}
