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

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.util.ExecutorsFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KafkaBroadcaster} including full lifecycle and message flow.
 * Uses a testable subclass that bypasses real Kafka client creation.
 */
public class KafkaBroadcasterTest {

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster broadcaster;
    private TestHandler handler;

    @SuppressWarnings("deprecation")
    @BeforeEach
    public void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(TestableKafkaBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);

        broadcaster = factory.get(TestableKafkaBroadcaster.class, "kafka-test");
        handler = new TestHandler();

        var ar = new AtmosphereResourceImpl(config,
                broadcaster,
                AtmosphereRequestImpl.newInstance(),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                handler);

        broadcaster.addAtmosphereResource(ar);
    }

    @AfterEach
    public void tearDown() throws Exception {
        broadcaster.destroy();
        config.getBroadcasterFactory().destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    public void testTopicNameGenerated() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        assertEquals("atmosphere.kafka-test", testable.getTopicName());
    }

    @Test
    public void testBroadcastPublishesToKafka() throws Exception {
        broadcaster.broadcast("hello-kafka").get();

        var testable = (TestableKafkaBroadcaster) broadcaster;
        assertNotNull(testable.lastPublishedPayload);
        assertEquals("hello-kafka", new String(testable.lastPublishedPayload, StandardCharsets.UTF_8));
        assertEquals("atmosphere.kafka-test", testable.lastPublishedTopic);
    }

    @Test
    public void testBroadcastIncludesNodeIdHeader() throws Exception {
        broadcaster.broadcast("with-header").get();

        var testable = (TestableKafkaBroadcaster) broadcaster;
        assertNotNull(testable.lastPublishedNodeId);
        assertEquals(testable.getNodeId(), testable.lastPublishedNodeId);
    }

    @Test
    public void testBroadcastDeliversLocally() throws Exception {
        handler.latch = new CountDownLatch(1);
        broadcaster.broadcast("local-kafka-msg").get();
        assertTrue(handler.latch.await(5, TimeUnit.SECONDS));
        assertTrue(handler.received.contains("local-kafka-msg"));
    }

    @Test
    public void testDestroyReleasesResources() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        broadcaster.destroy();
        assertTrue(testable.resourcesReleased);
    }

    @Test
    public void testSerializeStringMessage() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        var result = new String(testable.serializeMessage("hello"), StandardCharsets.UTF_8);
        assertEquals("hello", result);
    }

    @Test
    public void testSerializeByteArrayMessage() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        var bytes = "raw bytes".getBytes(StandardCharsets.UTF_8);
        var result = testable.serializeMessage(bytes);
        assertEquals(bytes, result);
    }

    @Test
    public void testSerializeObjectMessage() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        var result = new String(testable.serializeMessage(42), StandardCharsets.UTF_8);
        assertEquals("42", result);
    }

    @Test
    public void testSanitizeTopicName() {
        assertEquals("my-broadcaster", KafkaBroadcaster.sanitizeTopicName("my-broadcaster"));
        assertEquals("_chat_room1", KafkaBroadcaster.sanitizeTopicName("/chat/room1"));
        assertEquals("chat.room.1", KafkaBroadcaster.sanitizeTopicName("chat.room.1"));
        assertEquals("chat_room", KafkaBroadcaster.sanitizeTopicName("chat room"));
    }

    @Test
    public void testExtractNodeIdPresent() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        var headers = new RecordHeaders();
        headers.add(KafkaBroadcaster.NODE_ID_HEADER, "test-node".getBytes(StandardCharsets.UTF_8));
        assertEquals("test-node", testable.extractNodeId(headers));
    }

    @Test
    public void testExtractNodeIdMissing() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        var headers = new RecordHeaders();
        assertEquals(null, testable.extractNodeId(headers));
    }

    @Test
    public void testConfigConstants() {
        assertEquals("org.atmosphere.kafka.bootstrap.servers", KafkaBroadcaster.KAFKA_BOOTSTRAP_SERVERS);
        assertEquals("org.atmosphere.kafka.topic.prefix", KafkaBroadcaster.KAFKA_TOPIC_PREFIX);
        assertEquals("org.atmosphere.kafka.group.id", KafkaBroadcaster.KAFKA_GROUP_ID);
    }

    @Test
    public void testNodeIdIsUnique() {
        var b1 = new TestableKafkaBroadcaster();
        var b2 = new TestableKafkaBroadcaster();
        assertTrue(!b1.getNodeId().equals(b2.getNodeId()));
    }

    // --- New tests below ---

    @Test
    public void testSanitizeTopicNameWithSpecialCharacters() {
        // Test various special characters that should be replaced with underscores
        assertEquals("room____", KafkaBroadcaster.sanitizeTopicName("room@#$%"));
        assertEquals("a_b_c", KafkaBroadcaster.sanitizeTopicName("a+b=c"));
        assertEquals("", KafkaBroadcaster.sanitizeTopicName(""));
        assertEquals("already_valid-name.1", KafkaBroadcaster.sanitizeTopicName("already_valid-name.1"));
    }

    @Test
    public void testSanitizeTopicNameWithUnicode() {
        // Unicode characters should be replaced with underscores
        assertEquals("chat-___", KafkaBroadcaster.sanitizeTopicName("chat-\u00e9\u00e8\u00ea"));
    }

    @Test
    public void testSerializeEmptyStringMessage() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        var result = new String(testable.serializeMessage(""), StandardCharsets.UTF_8);
        assertEquals("", result);
    }

    @Test
    public void testSerializeNullToStringMessage() {
        // Objects use toString(), so test a custom object
        var testable = (TestableKafkaBroadcaster) broadcaster;
        var obj = new Object() {
            @Override
            public String toString() {
                return "custom-object";
            }
        };
        var result = new String(testable.serializeMessage(obj), StandardCharsets.UTF_8);
        assertEquals("custom-object", result);
    }

    @Test
    public void testSerializeEmptyByteArray() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        var bytes = new byte[0];
        var result = testable.serializeMessage(bytes);
        assertEquals(0, result.length);
    }

    @Test
    public void testSerializeMessageWithUtf8Characters() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        var utf8Msg = "Hello \u4e16\u754c \ud83c\udf0d";
        var result = testable.serializeMessage(utf8Msg);
        assertEquals(utf8Msg, new String(result, StandardCharsets.UTF_8));
    }

    @Test
    public void testExtractNodeIdWithMultipleHeaders() {
        // When multiple headers with the same key exist, lastHeader should be used
        var testable = (TestableKafkaBroadcaster) broadcaster;
        var headers = new RecordHeaders();
        headers.add(KafkaBroadcaster.NODE_ID_HEADER, "node-1".getBytes(StandardCharsets.UTF_8));
        headers.add(KafkaBroadcaster.NODE_ID_HEADER, "node-2".getBytes(StandardCharsets.UTF_8));
        assertEquals("node-2", testable.extractNodeId(headers));
    }

    @Test
    public void testExtractNodeIdWithEmptyValue() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        var headers = new RecordHeaders();
        headers.add(KafkaBroadcaster.NODE_ID_HEADER, "".getBytes(StandardCharsets.UTF_8));
        assertEquals("", testable.extractNodeId(headers));
    }

    @Test
    public void testNodeIdHeaderConstant() {
        assertEquals("atmosphere-node-id", KafkaBroadcaster.NODE_ID_HEADER);
    }

    @Test
    public void testNodeIdIsNotNull() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        assertNotNull(testable.getNodeId());
        assertFalse(testable.getNodeId().isEmpty());
    }

    @Test
    public void testMultipleBroadcastsPublishMultipleTimes() throws Exception {
        var testable = (TestableKafkaBroadcaster) broadcaster;

        broadcaster.broadcast("msg-1").get();
        assertEquals("msg-1", new String(testable.lastPublishedPayload, StandardCharsets.UTF_8));

        broadcaster.broadcast("msg-2").get();
        assertEquals("msg-2", new String(testable.lastPublishedPayload, StandardCharsets.UTF_8));

        broadcaster.broadcast("msg-3").get();
        assertEquals("msg-3", new String(testable.lastPublishedPayload, StandardCharsets.UTF_8));
    }

    @Test
    public void testBroadcastReturnsNonNullFuture() {
        Future<Object> future = broadcaster.broadcast("any-msg");
        assertNotNull(future);
    }

    @Test
    public void testTopicNameIncludesPrefix() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        // Default prefix is "atmosphere." and broadcaster ID is "kafka-test"
        assertTrue(testable.getTopicName().startsWith("atmosphere."));
    }

    @Test
    public void testPublishToKafkaCapturesAllFields() throws Exception {
        var testable = (TestableKafkaBroadcaster) broadcaster;

        broadcaster.broadcast("verify-all-fields").get();

        assertNotNull(testable.lastPublishedTopic, "Topic should be captured");
        assertNotNull(testable.lastPublishedPayload, "Payload should be captured");
        assertNotNull(testable.lastPublishedNodeId, "Node ID should be captured");
        assertEquals("atmosphere.kafka-test", testable.lastPublishedTopic);
        assertEquals(testable.getNodeId(), testable.lastPublishedNodeId);
    }

    @Test
    public void testBroadcastByteArrayMessage() throws Exception {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        var bytes = "binary-data".getBytes(StandardCharsets.UTF_8);
        broadcaster.broadcast(bytes).get();
        assertEquals(bytes, testable.lastPublishedPayload);
    }

    /**
     * Testable subclass that bypasses real Kafka client creation.
     * Captures published messages for verification.
     */
    public static class TestableKafkaBroadcaster extends KafkaBroadcaster {
        String lastPublishedTopic;
        byte[] lastPublishedPayload;
        String lastPublishedNodeId;
        boolean resourcesReleased;

        @Override
        protected KafkaProducer<String, byte[]> createProducer(String bootstrapServers) {
            return null;
        }

        @Override
        protected KafkaConsumer<String, byte[]> createConsumer(String bootstrapServers, String groupId) {
            return null;
        }

        @Override
        protected void startKafka(String bootstrapServers, String groupId) {
            // No-op: skip creating real Kafka clients and consumer thread
        }

        @Override
        void publishToKafka(Object msg) {
            lastPublishedTopic = getTopicName();
            lastPublishedPayload = serializeMessage(msg);
            lastPublishedNodeId = getNodeId();
        }

        @Override
        public void releaseExternalResources() {
            resourcesReleased = true;
        }
    }

    /**
     * AtmosphereHandler that captures received messages.
     */
    public static class TestHandler implements AtmosphereHandler {
        final List<String> received = new ArrayList<>();
        CountDownLatch latch;

        @Override
        public void onRequest(AtmosphereResource resource) throws IOException {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) throws IOException {
            if (event.getMessage() != null) {
                received.add(event.getMessage().toString());
                if (latch != null) latch.countDown();
            }
        }

        @Override
        public void destroy() {
        }
    }
}
