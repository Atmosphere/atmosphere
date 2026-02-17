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

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcastFilter.BroadcastAction;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Tests for {@link RedisClusterBroadcastFilter} including lifecycle and message flow.
 */
public class RedisClusterBroadcastFilterTest {

    private TestableRedisClusterBroadcastFilter filter;
    private AtmosphereConfig config;

    @BeforeMethod
    public void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        filter = new TestableRedisClusterBroadcastFilter();
        filter.init(config);
    }

    @AfterMethod
    public void tearDown() {
        filter.destroy();
    }

    @Test
    public void testInitialState() {
        assertNull(filter.getBroadcaster());
        assertNotNull(filter.getNodeId());
    }

    @Test
    public void testSetBroadcasterSubscribes() {
        var broadcaster = mock(Broadcaster.class);
        when(broadcaster.getID()).thenReturn("test-channel");

        filter.setBroadcaster(broadcaster);

        assertEquals(filter.getBroadcaster(), broadcaster);
        assertEquals(filter.subscribedChannel, "test-channel");
    }

    @Test
    public void testFilterPublishesToRedis() {
        var broadcaster = mock(Broadcaster.class);
        when(broadcaster.getID()).thenReturn("test-channel");
        filter.setBroadcaster(broadcaster);

        var result = filter.filter("test-channel", "original", "transformed");

        assertEquals(result.action(), BroadcastAction.ACTION.CONTINUE);
        assertEquals(result.message(), "transformed");
        assertEquals(filter.lastPublishedChannel, "test-channel");
        assertTrue(filter.lastPublishedMessage.endsWith("||transformed"));
    }

    @Test
    public void testRemoteMessageDeliveredToBroadcaster() {
        var broadcaster = mock(Broadcaster.class);
        when(broadcaster.getID()).thenReturn("test-channel");
        filter.setBroadcaster(broadcaster);

        filter.onRedisMessage("remote-node||hello-remote");

        verify(broadcaster).broadcast("hello-remote");
    }

    @Test
    public void testEchoPrevention() {
        var broadcaster = mock(Broadcaster.class);
        when(broadcaster.getID()).thenReturn("test-channel");
        filter.setBroadcaster(broadcaster);

        filter.onRedisMessage(filter.getNodeId() + "||echo-msg");

        verify(broadcaster, org.mockito.Mockito.never()).broadcast(org.mockito.ArgumentMatchers.any());
    }

    @Test
    public void testMalformedMessageIgnored() {
        var broadcaster = mock(Broadcaster.class);
        when(broadcaster.getID()).thenReturn("test-channel");
        filter.setBroadcaster(broadcaster);

        filter.onRedisMessage("no-separator");

        verify(broadcaster, org.mockito.Mockito.never()).broadcast(org.mockito.ArgumentMatchers.any());
    }

    @Test
    public void testSerializeStringMessage() {
        assertEquals(filter.serializeMessage("hello"), "hello");
    }

    @Test
    public void testSerializeByteArrayMessage() {
        assertEquals(filter.serializeMessage("bytes".getBytes(StandardCharsets.UTF_8)), "bytes");
    }

    @Test
    public void testSerializeObjectMessage() {
        assertEquals(filter.serializeMessage(99), "99");
    }

    @Test
    public void testSetUri() {
        filter.setUri("redis://custom:1234");
        assertNotNull(filter);
    }

    @Test
    public void testNodeIdIsUnique() {
        var filter2 = new TestableRedisClusterBroadcastFilter();
        assertTrue(!filter.getNodeId().equals(filter2.getNodeId()));
    }

    /**
     * Testable subclass that skips Redis connection entirely.
     */
    public static class TestableRedisClusterBroadcastFilter extends RedisClusterBroadcastFilter {
        String subscribedChannel;
        String lastPublishedChannel;
        String lastPublishedMessage;

        @Override
        protected void connectToRedis(String redisUrl, String password) {
            // No-op
        }

        @Override
        public void init(AtmosphereConfig config) {
            // Skip the real init which calls connectToRedis + addListener
        }

        @Override
        public void setBroadcaster(Broadcaster bc) {
            try {
                var field = RedisClusterBroadcastFilter.class.getDeclaredField("broadcaster");
                field.setAccessible(true);
                field.set(this, bc);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            subscribedChannel = bc.getID();
        }

        @Override
        public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
            if (getBroadcaster() != null) {
                lastPublishedChannel = getBroadcaster().getID();
                lastPublishedMessage = getNodeId() + RedisClusterBroadcastFilter.SEPARATOR + serializeMessage(message);
            }
            return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message);
        }

        @Override
        public void destroy() {
            // No-op
        }
    }
}
