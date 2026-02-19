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
package org.atmosphere.integrationtests;

import org.atmosphere.cpr.ApplicationConfig;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertTrue;

/**
 * Integration tests for Kafka-based clustering with real Kafka via Testcontainers.
 * Two embedded Jetty nodes share one Kafka broker. Messages broadcast on node A
 * must arrive on node B.
 *
 * Requires Docker — tests are skipped if Docker is unavailable.
 */
@Test(groups = "kafka")
public class KafkaClusteringTest {

    private static final boolean DOCKER_AVAILABLE = isDockerAvailable();

    private org.testcontainers.kafka.KafkaContainer kafka;
    private EmbeddedAtmosphereServer nodeA;
    private EmbeddedAtmosphereServer nodeB;
    private HttpClient httpClient;

    @SuppressWarnings("resource") // closed in tearDown()
    @BeforeClass
    public void setUp() throws Exception {
        if (!DOCKER_AVAILABLE) {
            return;
        }

        kafka = new org.testcontainers.kafka.KafkaContainer("apache/kafka:3.8.1");
        kafka.start();

        var bootstrapServers = kafka.getBootstrapServers();

        nodeA = new EmbeddedAtmosphereServer()
                .withInitParam(ApplicationConfig.BROADCASTER_CLASS,
                        "org.atmosphere.kafka.KafkaBroadcaster")
                .withInitParam("org.atmosphere.kafka.bootstrap.servers", bootstrapServers);
        nodeA.start();

        nodeB = new EmbeddedAtmosphereServer()
                .withInitParam(ApplicationConfig.BROADCASTER_CLASS,
                        "org.atmosphere.kafka.KafkaBroadcaster")
                .withInitParam("org.atmosphere.kafka.bootstrap.servers", bootstrapServers);
        nodeB.start();

        httpClient = HttpClient.newHttpClient();
    }

    @AfterClass
    public void tearDown() throws Exception {
        if (httpClient != null) httpClient.close();
        if (nodeA != null) nodeA.close();
        if (nodeB != null) nodeB.close();
        if (kafka != null) kafka.stop();
    }

    @Test(timeOut = 30_000)
    public void testCrossNodeBroadcast() throws Exception {
        if (!DOCKER_AVAILABLE) {
            throw new org.testng.SkipException("Docker not available");
        }

        var receivedOnB = new CopyOnWriteArrayList<String>();
        var latchB = new CountDownLatch(1);
        var openLatchA = new CountDownLatch(1);
        var openLatchB = new CountDownLatch(1);

        // Connect client to node B (receiver)
        var wsB = httpClient.newWebSocketBuilder()
                .buildAsync(buildWsUri(nodeB, "/echo"),
                        new CollectingListener(receivedOnB, openLatchB, latchB))
                .join();
        assertTrue(openLatchB.await(5, TimeUnit.SECONDS), "Client B should connect");

        // Connect client to node A (sender)
        var receivedOnA = new CopyOnWriteArrayList<String>();
        var wsA = httpClient.newWebSocketBuilder()
                .buildAsync(buildWsUri(nodeA, "/echo"),
                        new CollectingListener(receivedOnA, openLatchA, new CountDownLatch(1)))
                .join();
        assertTrue(openLatchA.await(5, TimeUnit.SECONDS), "Client A should connect");

        // Let connections and Kafka consumers settle (Kafka takes longer than Redis)
        Thread.sleep(5000);
        receivedOnA.clear();
        receivedOnB.clear();

        // Send on node A
        wsA.sendText("cross-node-kafka", true).join();

        assertTrue(latchB.await(15, TimeUnit.SECONDS),
                "Node B should receive cross-node broadcast via Kafka, got: " + receivedOnB);
        assertTrue(receivedOnB.stream().anyMatch(m -> m.contains("cross-node-kafka")),
                "Node B should contain 'cross-node-kafka' but got: " + receivedOnB);

        wsA.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        wsB.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test(timeOut = 30_000)
    public void testTopicIsolation() throws Exception {
        if (!DOCKER_AVAILABLE) {
            throw new org.testng.SkipException("Docker not available");
        }

        var receivedOnEcho = new CopyOnWriteArrayList<String>();
        var receivedOnRoom2 = new CopyOnWriteArrayList<String>();
        var echoLatch = new CountDownLatch(1);
        var room2Latch = new CountDownLatch(1);
        var openLatch1 = new CountDownLatch(1);
        var openLatch2 = new CountDownLatch(1);

        // Connect to /echo on node A
        var wsEcho = httpClient.newWebSocketBuilder()
                .buildAsync(buildWsUri(nodeA, "/echo"),
                        new CollectingListener(receivedOnEcho, openLatch1, echoLatch))
                .join();
        assertTrue(openLatch1.await(5, TimeUnit.SECONDS));

        // Connect to /room2 on node B
        var wsRoom2 = httpClient.newWebSocketBuilder()
                .buildAsync(buildWsUri(nodeB, "/room2"),
                        new CollectingListener(receivedOnRoom2, openLatch2, room2Latch))
                .join();
        assertTrue(openLatch2.await(5, TimeUnit.SECONDS));

        Thread.sleep(3000);
        receivedOnEcho.clear();
        receivedOnRoom2.clear();

        // Send to /echo on node A
        wsEcho.sendText("echo-only-kafka", true).join();
        assertTrue(echoLatch.await(10, TimeUnit.SECONDS));

        // Wait and verify /room2 did NOT receive it
        Thread.sleep(3000);
        assertTrue(receivedOnRoom2.stream().noneMatch(m -> m.contains("echo-only-kafka")),
                "Room2 should NOT receive echo message via Kafka, but got: " + receivedOnRoom2);

        wsEcho.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        wsRoom2.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test(timeOut = 30_000)
    public void testEchoPrevention() throws Exception {
        if (!DOCKER_AVAILABLE) {
            throw new org.testng.SkipException("Docker not available");
        }

        var receivedOnA = new CopyOnWriteArrayList<String>();
        var echoLatch = new CountDownLatch(1);
        var openLatch = new CountDownLatch(1);

        var wsA = httpClient.newWebSocketBuilder()
                .buildAsync(buildWsUri(nodeA, "/echo"),
                        new CollectingListener(receivedOnA, openLatch, echoLatch))
                .join();
        assertTrue(openLatch.await(5, TimeUnit.SECONDS));

        Thread.sleep(3000);
        receivedOnA.clear();

        wsA.sendText("kafka-echo-test", true).join();
        assertTrue(echoLatch.await(10, TimeUnit.SECONDS));

        // Wait for potential duplicate
        Thread.sleep(5000);
        long count = receivedOnA.stream().filter(m -> m.contains("kafka-echo-test")).count();
        assertTrue(count == 1,
                "Message should arrive exactly once (no Kafka echo), but arrived " + count + " times: " + receivedOnA);

        wsA.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    private URI buildWsUri(EmbeddedAtmosphereServer node, String path) {
        return URI.create(node.getWebSocketUrl() + path
                + "?X-Atmosphere-tracking-id=" + UUID.randomUUID()
                + "&X-Atmosphere-Transport=websocket"
                + "&X-Atmosphere-Framework=5.0.0");
    }

    private static boolean isDockerAvailable() {
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    private static class CollectingListener implements WebSocket.Listener {
        private final CopyOnWriteArrayList<String> received;
        private final CountDownLatch openLatch;
        private final CountDownLatch messageLatch;
        private final StringBuilder buffer = new StringBuilder();

        CollectingListener(CopyOnWriteArrayList<String> received,
                          CountDownLatch openLatch, CountDownLatch messageLatch) {
            this.received = received;
            this.openLatch = openLatch;
            this.messageLatch = messageLatch;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            openLatch.countDown();
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                var msg = buffer.toString();
                buffer.setLength(0);
                if (!msg.isBlank()) {
                    received.add(msg);
                    messageLatch.countDown();
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
        }
    }
}
