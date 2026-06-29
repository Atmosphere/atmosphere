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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-node delivery test for {@link RedisBroadcaster} using a real Redis backplane
 * (Testcontainers). This is the proof behind the clustering claim: a message broadcast
 * through a broadcaster on one node is relayed over Redis pub/sub and delivered to a
 * subscriber attached to a <em>separate</em> broadcaster instance on another node.
 *
 * <p>Each "node" is an independent {@link RedisBroadcaster} instance with its own
 * {@link AtmosphereFramework}, {@link DefaultBroadcasterFactory} and its own pair of
 * Redis pub/sub connections — i.e. it does not share any in-JVM broadcaster state with
 * the other node. The only thing the two nodes share is the Redis server and the channel
 * name (the broadcaster ID). The relay therefore travels node A → Redis → node B over a
 * real TCP pub/sub connection, exactly as it would between two JVMs in a cluster.
 *
 * <p>Unlike {@link RedisBroadcasterTest}, which substitutes an in-memory bus for Redis,
 * this test exercises the production {@link RedisBroadcaster} (no overrides) against a
 * genuine Redis instance.
 *
 * <p>Requires Docker. Tests are skipped (aborted as assumptions) if Docker is unavailable.</p>
 *
 * @author Jeanfrancois Arcand
 */
@Tag("redis")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RedisBroadcasterClusterRelayTest {

    private static final Logger logger = LoggerFactory.getLogger(RedisBroadcasterClusterRelayTest.class);

    private static final boolean DOCKER_AVAILABLE = isDockerAvailable();

    private GenericContainer<?> redis;
    private String redisUri;

    @SuppressWarnings("resource") // container stopped in tearDown()
    @BeforeAll
    public void setUp() {
        if (!DOCKER_AVAILABLE) {
            return;
        }
        redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        redis.start();
        redisUri = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
    }

    @AfterAll
    public void tearDown() {
        if (redis != null) {
            redis.stop();
        }
    }

    /**
     * Broadcast a message through node A's broadcaster; assert that a subscriber attached
     * to node B's <em>separate</em> broadcaster (sharing the same Redis channel) receives
     * the exact payload. This is the cross-node relay proof.
     */
    @Test
    @Timeout(60)
    public void testStreamBroadcastOnNodeAReachesSubscriberOnNodeB() throws Exception {
        skipIfNoDocker();

        // Same channel (broadcaster ID) => both nodes attach to the same Redis pub/sub channel.
        var channel = "agent-stream-" + UUID.randomUUID();

        Node nodeA = null;
        Node nodeB = null;
        try {
            nodeA = newNode(channel);
            nodeB = newNode(channel);

            // Sanity: the two nodes are genuinely distinct instances with distinct node IDs.
            assertNotSame(nodeA.broadcaster, nodeB.broadcaster, "Nodes must be separate broadcaster instances");
            assertNotEquals(((RedisBroadcaster) nodeA.broadcaster).getNodeId(),
                    ((RedisBroadcaster) nodeB.broadcaster).getNodeId(),
                    "Nodes must have distinct node IDs");

            var payload = "stream-chunk:" + UUID.randomUUID();

            nodeB.handler.latch = new CountDownLatch(1);
            nodeA.handler.latch = new CountDownLatch(1);

            // Broadcast on node A only.
            nodeA.broadcaster.broadcast(payload).get(10, TimeUnit.SECONDS);

            // Node B's subscriber must receive it — purely via the Redis relay, since B shares
            // no in-JVM broadcaster state with A.
            assertTrue(nodeB.handler.latch.await(30, TimeUnit.SECONDS),
                    "Node B's subscriber should receive the message broadcast on node A via Redis");
            assertTrue(nodeB.handler.received.contains(payload),
                    "Node B should have received the exact payload broadcast on node A");

            // Node A delivers locally too (sanity / mode parity), and Redis echo-prevention means
            // A does not re-deliver its own published message.
            assertTrue(nodeA.handler.latch.await(30, TimeUnit.SECONDS),
                    "Node A should deliver the message to its own local subscriber");
            assertTrue(nodeA.handler.received.contains(payload),
                    "Node A should have delivered the exact payload to its local subscriber");
        } finally {
            destroyNode(nodeA);
            destroyNode(nodeB);
        }
    }

    /**
     * The relay is symmetric: a message broadcast on node B must reach a subscriber on node A.
     */
    @Test
    @Timeout(60)
    public void testRelayIsBidirectional() throws Exception {
        skipIfNoDocker();

        var channel = "agent-stream-" + UUID.randomUUID();

        Node nodeA = null;
        Node nodeB = null;
        try {
            nodeA = newNode(channel);
            nodeB = newNode(channel);

            var payload = "reverse-chunk:" + UUID.randomUUID();

            nodeA.handler.latch = new CountDownLatch(1);

            // Broadcast on node B this time.
            nodeB.broadcaster.broadcast(payload).get(10, TimeUnit.SECONDS);

            assertTrue(nodeA.handler.latch.await(30, TimeUnit.SECONDS),
                    "Node A's subscriber should receive the message broadcast on node B via Redis");
            assertTrue(nodeA.handler.received.contains(payload),
                    "Node A should have received the exact payload broadcast on node B");
        } finally {
            destroyNode(nodeA);
            destroyNode(nodeB);
        }
    }

    // --- node construction ---

    /**
     * Build an independent "node": a fresh framework + factory + a real {@link RedisBroadcaster}
     * pointed at the Testcontainers Redis, with a recording subscriber attached.
     */
    @SuppressWarnings("deprecation") // DefaultBroadcasterFactory + AtmosphereResourceImpl(...) ctor are the documented test seams, also used by RedisBroadcasterTest
    private Node newNode(String channel) {
        var framework = new AtmosphereFramework();
        framework.addInitParameter(RedisBroadcaster.REDIS_URL, redisUri);
        framework.init();

        var config = framework.getAtmosphereConfig();
        var factory = new DefaultBroadcasterFactory();
        factory.configure(RedisBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);

        // Same ID across nodes => same Redis channel.
        var broadcaster = factory.get(RedisBroadcaster.class, channel);

        var handler = new RecordingHandler();
        var ar = new AtmosphereResourceImpl(config,
                broadcaster,
                AtmosphereRequestImpl.newInstance(),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                handler);
        broadcaster.addAtmosphereResource(ar);

        return new Node(framework, config, factory, broadcaster, handler);
    }

    private static void destroyNode(Node node) {
        if (node == null) {
            return;
        }
        try {
            node.broadcaster.destroy();
        } catch (Exception e) {
            logger.trace("Best-effort broadcaster cleanup failed", e);
        }
        try {
            node.factory.destroy();
        } catch (Exception e) {
            logger.trace("Best-effort factory cleanup failed", e);
        }
        try {
            ExecutorsFactory.reset(node.config);
        } catch (Exception e) {
            logger.trace("Best-effort executors reset failed", e);
        }
        try {
            node.framework.destroy();
        } catch (Exception e) {
            logger.trace("Best-effort framework cleanup failed", e);
        }
    }

    /**
     * One simulated cluster node: an independent framework/factory/broadcaster with a
     * recording subscriber.
     */
    private record Node(AtmosphereFramework framework,
                        AtmosphereConfig config,
                        DefaultBroadcasterFactory factory,
                        Broadcaster broadcaster,
                        RecordingHandler handler) {
    }

    /**
     * Captures messages delivered to a node's local subscriber.
     */
    private static final class RecordingHandler implements AtmosphereHandler {
        final List<String> received = new CopyOnWriteArrayList<>();
        volatile CountDownLatch latch;

        @Override
        public void onRequest(AtmosphereResource resource) throws IOException {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) throws IOException {
            var message = event.getMessage();
            if (message != null) {
                received.add(message.toString());
                var l = latch;
                if (l != null) {
                    l.countDown();
                }
            }
        }

        @Override
        public void destroy() {
        }
    }

    // --- helpers ---

    private static void skipIfNoDocker() {
        if (!DOCKER_AVAILABLE) {
            org.junit.jupiter.api.Assumptions.abort("Docker not available");
        }
    }

    private static boolean isDockerAvailable() {
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }
}
