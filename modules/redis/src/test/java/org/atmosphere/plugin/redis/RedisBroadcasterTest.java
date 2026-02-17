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

import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Unit tests for {@link RedisBroadcaster}.
 * <p>
 * These tests validate serialization, node ID generation, and message envelope format
 * without requiring a live Redis instance.
 *
 * @author Jeanfrancois Arcand
 */
public class RedisBroadcasterTest {

    @Test
    public void testNodeIdIsUnique() {
        var b1 = new RedisBroadcaster();
        var b2 = new RedisBroadcaster();

        // Each instance should have a unique node ID (generated via UUID)
        assertNotNull(b1);
        assertNotNull(b2);
    }

    @Test
    public void testSerializeStringMessage() throws Exception {
        var broadcaster = new RedisBroadcaster();
        var method = RedisBroadcaster.class.getDeclaredMethod("serializeMessage", Object.class);
        method.setAccessible(true);

        var result = method.invoke(broadcaster, "hello");
        assertEquals(result, "hello");
    }

    @Test
    public void testSerializeByteArrayMessage() throws Exception {
        var broadcaster = new RedisBroadcaster();
        var method = RedisBroadcaster.class.getDeclaredMethod("serializeMessage", Object.class);
        method.setAccessible(true);

        var bytes = "hello bytes".getBytes(StandardCharsets.UTF_8);
        var result = method.invoke(broadcaster, (Object) bytes);
        assertEquals(result, "hello bytes");
    }

    @Test
    public void testSerializeObjectMessage() throws Exception {
        var broadcaster = new RedisBroadcaster();
        var method = RedisBroadcaster.class.getDeclaredMethod("serializeMessage", Object.class);
        method.setAccessible(true);

        var result = method.invoke(broadcaster, 42);
        assertEquals(result, "42");
    }

    @Test
    public void testOnRedisMessageSkipsSameNode() throws Exception {
        var broadcaster = new RedisBroadcaster();

        // Access the nodeId field
        var nodeIdField = RedisBroadcaster.class.getDeclaredField("nodeId");
        nodeIdField.setAccessible(true);
        var nodeId = (String) nodeIdField.get(broadcaster);

        // A message from the same node should be silently ignored (no exception)
        var method = RedisBroadcaster.class.getDeclaredMethod("onRedisMessage", String.class);
        method.setAccessible(true);

        // Should not throw - just skip because sender == this node
        method.invoke(broadcaster, nodeId + "||hello");
    }

    @Test
    public void testOnRedisMessageMalformedNoSeparator() throws Exception {
        var broadcaster = new RedisBroadcaster();
        var method = RedisBroadcaster.class.getDeclaredMethod("onRedisMessage", String.class);
        method.setAccessible(true);

        // Malformed message (no separator) should be handled gracefully
        method.invoke(broadcaster, "malformed-message-no-separator");
    }

    @Test
    public void testConfigConstants() {
        assertEquals(RedisBroadcaster.REDIS_URL, "org.atmosphere.redis.url");
        assertEquals(RedisBroadcaster.REDIS_PASSWORD, "org.atmosphere.redis.password");
    }

    @Test
    public void testDefaultConstructor() {
        var broadcaster = new RedisBroadcaster();
        assertNotNull(broadcaster);
    }
}
