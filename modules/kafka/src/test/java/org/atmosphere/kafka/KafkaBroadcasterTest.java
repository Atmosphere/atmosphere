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
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

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
    @BeforeMethod
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

    @AfterMethod
    public void tearDown() throws Exception {
        broadcaster.destroy();
        config.getBroadcasterFactory().destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    public void testTopicNameGenerated() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        assertEquals(testable.getTopicName(), "atmosphere.kafka-test");
    }

    @Test
    public void testBroadcastPublishesToKafka() throws Exception {
        broadcaster.broadcast("hello-kafka").get();

        var testable = (TestableKafkaBroadcaster) broadcaster;
        assertNotNull(testable.lastPublishedPayload);
        assertEquals(new String(testable.lastPublishedPayload, StandardCharsets.UTF_8), "hello-kafka");
        assertEquals(testable.lastPublishedTopic, "atmosphere.kafka-test");
    }

    @Test
    public void testBroadcastIncludesNodeIdHeader() throws Exception {
        broadcaster.broadcast("with-header").get();

        var testable = (TestableKafkaBroadcaster) broadcaster;
        assertNotNull(testable.lastPublishedNodeId);
        assertEquals(testable.lastPublishedNodeId, testable.getNodeId());
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
        assertEquals(result, "hello");
    }

    @Test
    public void testSerializeByteArrayMessage() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        var bytes = "raw bytes".getBytes(StandardCharsets.UTF_8);
        var result = testable.serializeMessage(bytes);
        assertEquals(result, bytes);
    }

    @Test
    public void testSerializeObjectMessage() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        var result = new String(testable.serializeMessage(42), StandardCharsets.UTF_8);
        assertEquals(result, "42");
    }

    @Test
    public void testSanitizeTopicName() {
        assertEquals(KafkaBroadcaster.sanitizeTopicName("my-broadcaster"), "my-broadcaster");
        assertEquals(KafkaBroadcaster.sanitizeTopicName("/chat/room1"), "_chat_room1");
        assertEquals(KafkaBroadcaster.sanitizeTopicName("chat.room.1"), "chat.room.1");
        assertEquals(KafkaBroadcaster.sanitizeTopicName("chat room"), "chat_room");
    }

    @Test
    public void testExtractNodeIdPresent() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        var headers = new RecordHeaders();
        headers.add(KafkaBroadcaster.NODE_ID_HEADER, "test-node".getBytes(StandardCharsets.UTF_8));
        assertEquals(testable.extractNodeId(headers), "test-node");
    }

    @Test
    public void testExtractNodeIdMissing() {
        var testable = (TestableKafkaBroadcaster) broadcaster;
        var headers = new RecordHeaders();
        assertEquals(testable.extractNodeId(headers), null);
    }

    @Test
    public void testConfigConstants() {
        assertEquals(KafkaBroadcaster.KAFKA_BOOTSTRAP_SERVERS, "org.atmosphere.kafka.bootstrap.servers");
        assertEquals(KafkaBroadcaster.KAFKA_TOPIC_PREFIX, "org.atmosphere.kafka.topic.prefix");
        assertEquals(KafkaBroadcaster.KAFKA_GROUP_ID, "org.atmosphere.kafka.group.id");
    }

    @Test
    public void testNodeIdIsUnique() {
        var b1 = new TestableKafkaBroadcaster();
        var b2 = new TestableKafkaBroadcaster();
        assertTrue(!b1.getNodeId().equals(b2.getNodeId()));
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
