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
package org.atmosphere.kafka;

import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Unit tests for {@link KafkaBroadcaster}.
 * <p>
 * These tests validate serialization, topic name sanitization, and configuration constants
 * without requiring a live Kafka broker.
 *
 * @author Jeanfrancois Arcand
 */
public class KafkaBroadcasterTest {

    @Test
    public void testDefaultConstructor() {
        var broadcaster = new KafkaBroadcaster();
        assertNotNull(broadcaster);
    }

    @Test
    public void testConfigConstants() {
        assertEquals(KafkaBroadcaster.KAFKA_BOOTSTRAP_SERVERS, "org.atmosphere.kafka.bootstrap.servers");
        assertEquals(KafkaBroadcaster.KAFKA_TOPIC_PREFIX, "org.atmosphere.kafka.topic.prefix");
        assertEquals(KafkaBroadcaster.KAFKA_GROUP_ID, "org.atmosphere.kafka.group.id");
    }

    @Test
    public void testSerializeStringMessage() throws Exception {
        var broadcaster = new KafkaBroadcaster();
        var method = KafkaBroadcaster.class.getDeclaredMethod("serializeMessage", Object.class);
        method.setAccessible(true);

        var result = (byte[]) method.invoke(broadcaster, "hello");
        assertEquals(new String(result, StandardCharsets.UTF_8), "hello");
    }

    @Test
    public void testSerializeByteArrayMessage() throws Exception {
        var broadcaster = new KafkaBroadcaster();
        var method = KafkaBroadcaster.class.getDeclaredMethod("serializeMessage", Object.class);
        method.setAccessible(true);

        var bytes = "hello bytes".getBytes(StandardCharsets.UTF_8);
        var result = (byte[]) method.invoke(broadcaster, (Object) bytes);
        assertEquals(result, bytes);
    }

    @Test
    public void testSerializeObjectMessage() throws Exception {
        var broadcaster = new KafkaBroadcaster();
        var method = KafkaBroadcaster.class.getDeclaredMethod("serializeMessage", Object.class);
        method.setAccessible(true);

        var result = (byte[]) method.invoke(broadcaster, 42);
        assertEquals(new String(result, StandardCharsets.UTF_8), "42");
    }

    @Test
    public void testSanitizeTopicName() throws Exception {
        var broadcaster = new KafkaBroadcaster();
        var method = KafkaBroadcaster.class.getDeclaredMethod("sanitizeTopicName", String.class);
        method.setAccessible(true);

        // Normal name
        assertEquals(method.invoke(broadcaster, "my-broadcaster"), "my-broadcaster");

        // Name with slashes and special chars
        assertEquals(method.invoke(broadcaster, "/chat/room1"), "_chat_room1");

        // Name with dots
        assertEquals(method.invoke(broadcaster, "chat.room.1"), "chat.room.1");

        // Name with spaces
        assertEquals(method.invoke(broadcaster, "chat room"), "chat_room");
    }

    @Test
    public void testExtractNodeIdFromHeader() throws Exception {
        var broadcaster = new KafkaBroadcaster();
        var method = KafkaBroadcaster.class.getDeclaredMethod("extractNodeId",
                org.apache.kafka.common.header.Headers.class);
        method.setAccessible(true);

        // Create headers with a node ID
        var headers = new org.apache.kafka.common.header.internals.RecordHeaders();
        headers.add("atmosphere-node-id", "test-node".getBytes(StandardCharsets.UTF_8));

        var result = method.invoke(broadcaster, headers);
        assertEquals(result, "test-node");
    }

    @Test
    public void testExtractNodeIdMissingHeader() throws Exception {
        var broadcaster = new KafkaBroadcaster();
        var method = KafkaBroadcaster.class.getDeclaredMethod("extractNodeId",
                org.apache.kafka.common.header.Headers.class);
        method.setAccessible(true);

        var headers = new org.apache.kafka.common.header.internals.RecordHeaders();
        var result = method.invoke(broadcaster, headers);
        assertEquals(result, null);
    }
}
