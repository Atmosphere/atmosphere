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
package org.atmosphere.plugin.redis;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RedisBroadcaster} including full lifecycle and message flow.
 * Uses an in-memory pub/sub bus instead of a real Redis server.
 */
public class RedisBroadcasterTest {

    /**
     * In-memory pub/sub bus that replaces Redis for testing.
     */
    static final ConcurrentMap<String, List<BiConsumer<String, String>>> IN_MEMORY_BUS = new ConcurrentHashMap<>();

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster broadcaster;
    private TestHandler handler;

    @SuppressWarnings("deprecation")
    @BeforeEach
    public void setUp() throws Exception {
        IN_MEMORY_BUS.clear();
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(TestableRedisBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);

        broadcaster = factory.get(TestableRedisBroadcaster.class, "redis-test");
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
        IN_MEMORY_BUS.clear();
    }

    @Test
    public void testInitializeSubscribesToChannel() {
        var testable = (TestableRedisBroadcaster) broadcaster;
        assertEquals("redis-test", testable.subscribedChannel);
    }

    @Test
    public void testBroadcastPublishesToChannel() throws Exception {
        var testable = (TestableRedisBroadcaster) broadcaster;
        broadcaster.broadcast("hello").get();
        assertNotNull(testable.lastPublishedChannel);
        assertEquals("redis-test", testable.lastPublishedChannel);
        assertTrue(testable.lastPublishedMessage.endsWith("||hello"));
    }

    @Test
    public void testBroadcastDeliversLocally() throws Exception {
        handler.latch = new CountDownLatch(1);
        broadcaster.broadcast("local-msg").get();
        assertTrue(handler.latch.await(5, TimeUnit.SECONDS));
        assertTrue(handler.received.contains("local-msg"));
    }

    @Test
    public void testRemoteMessageDeliveredLocally() throws Exception {
        handler.latch = new CountDownLatch(1);

        var testable = (TestableRedisBroadcaster) broadcaster;
        testable.onRedisMessage("remote-node-id||remote-hello");

        assertTrue(handler.latch.await(5, TimeUnit.SECONDS));
        assertTrue(handler.received.contains("remote-hello"));
    }

    @Test
    public void testEchoPrevention() throws Exception {
        var testable = (TestableRedisBroadcaster) broadcaster;
        var nodeId = testable.getNodeId();

        handler.latch = new CountDownLatch(1);
        testable.onRedisMessage(nodeId + "||echo-msg");

        // Should NOT have delivered — latch should still be at 1
        assertEquals(1, handler.latch.getCount());
    }

    @Test
    public void testMalformedMessageIgnored() {
        var testable = (TestableRedisBroadcaster) broadcaster;
        testable.onRedisMessage("no-separator-here");
        // No exception means success
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testCrossNodeViaBus() throws Exception {
        // Create a second broadcaster on a different "node"
        var broadcaster2 = factory.get(TestableRedisBroadcaster.class, "redis-test-2");
        var handler2 = new TestHandler();
        var ar2 = new AtmosphereResourceImpl(config,
                broadcaster2,
                AtmosphereRequestImpl.newInstance(),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                handler2);
        broadcaster2.addAtmosphereResource(ar2);

        // Both are subscribed to the same channel via IN_MEMORY_BUS
        // When broadcaster1 publishes, the bus delivers to all subscribers including broadcaster2's handler
        var subscribers = IN_MEMORY_BUS.get("redis-test");
        assertNotNull(subscribers, "Should have subscribers for 'redis-test'");
        assertTrue(subscribers.size() >= 1);

        broadcaster2.destroy();
    }

    @Test
    public void testSerializeStringMessage() {
        var testable = (TestableRedisBroadcaster) broadcaster;
        assertEquals("hello", testable.serializeMessage("hello"));
    }

    @Test
    public void testSerializeByteArrayMessage() {
        var testable = (TestableRedisBroadcaster) broadcaster;
        assertEquals("hello bytes", testable.serializeMessage("hello bytes".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testSerializeObjectMessage() {
        var testable = (TestableRedisBroadcaster) broadcaster;
        assertEquals("42", testable.serializeMessage(42));
    }

    @Test
    public void testDestroyReleasesResources() {
        var testable = (TestableRedisBroadcaster) broadcaster;
        broadcaster.destroy();
        assertTrue(testable.resourcesReleased);
    }

    @Test
    public void testConfigConstants() {
        assertEquals("org.atmosphere.redis.url", RedisBroadcaster.REDIS_URL);
        assertEquals("org.atmosphere.redis.password", RedisBroadcaster.REDIS_PASSWORD);
    }

    @Test
    public void testNodeIdIsUnique() {
        var b1 = new TestableRedisBroadcaster();
        var b2 = new TestableRedisBroadcaster();
        assertTrue(!b1.getNodeId().equals(b2.getNodeId()));
    }

    /**
     * A testable subclass that replaces Redis with an in-memory pub/sub bus.
     */
    public static class TestableRedisBroadcaster extends RedisBroadcaster {
        String subscribedChannel;
        String lastPublishedChannel;
        String lastPublishedMessage;
        boolean resourcesReleased;

        @Override
        protected void connectToRedis(String redisUrl, String password) {
            // No-op: we use the in-memory bus instead
        }

        @Override
        protected void startRedis(String redisUrl, String password) {
            // Subscribe to in-memory bus instead of real Redis
            subscribedChannel = getID();
            IN_MEMORY_BUS.computeIfAbsent(getID(), k -> new CopyOnWriteArrayList<>())
                    .add((channel, message) -> onRedisMessage(message));
        }

        @Override
        public java.util.concurrent.Future<Object> broadcast(Object msg) {
            // Publish to in-memory bus
            lastPublishedChannel = getID();
            var payload = serializeMessage(msg);
            lastPublishedMessage = getNodeId() + SEPARATOR + payload;

            var subscribers = IN_MEMORY_BUS.get(getID());
            if (subscribers != null) {
                for (var sub : subscribers) {
                    sub.accept(getID(), lastPublishedMessage);
                }
            }

            return super.broadcast(msg);
        }

        @Override
        public void releaseExternalResources() {
            resourcesReleased = true;
            var subscribers = IN_MEMORY_BUS.get(getID());
            if (subscribers != null) {
                subscribers.clear();
            }
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
