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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for WebSocket transport with a live embedded Jetty server.
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class WebSocketTransportTest {

    private EmbeddedAtmosphereServer server;
    private HttpClient httpClient;

    @BeforeAll
    public void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer();
        server.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterAll
    public void tearDown() throws Exception {
        httpClient.close();
        server.close();
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testWebSocketConnectAndEcho() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        // Wait for a non-padding message containing our test string
        var messageLatch = new MessageLatch(m -> m.contains("hello world"));

        var ws = httpClient.newWebSocketBuilder()
                .buildAsync(buildWsUri("/echo"),
                        new CollectingListener(received, openLatch, messageLatch))
                .join();
        assertTrue(openLatch.await(5, TimeUnit.SECONDS), "WebSocket should connect");

        // Wait for protocol handshake/padding to complete
        Thread.sleep(500);

        ws.sendText("hello world", true).join();

        assertTrue(messageLatch.await(5, TimeUnit.SECONDS),
                "Should receive 'hello world' but got: " + received);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testMultipleClientsReceiveBroadcast() throws Exception {
        int clientCount = 5;
        var clients = new java.util.ArrayList<WebSocket>();
        var allReceived = new CopyOnWriteArrayList<String>();
        var broadcastLatch = new MessageLatch(m -> m.contains("broadcast-test"), clientCount);

        for (int i = 0; i < clientCount; i++) {
            var openLatch = new CountDownLatch(1);
            var ws = httpClient.newWebSocketBuilder()
                    .buildAsync(buildWsUri("/echo"),
                            new CollectingListener(allReceived, openLatch, broadcastLatch))
                    .join();
            assertTrue(openLatch.await(5, TimeUnit.SECONDS), "Client " + i + " should connect");
            clients.add(ws);
        }

        // Let protocol handshakes settle
        Thread.sleep(1000);

        clients.getFirst().sendText("broadcast-test", true).join();

        assertTrue(broadcastLatch.await(5, TimeUnit.SECONDS),
                "All " + clientCount + " clients should receive the broadcast, got: " + allReceived);

        for (var ws : clients) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testBroadcasterIsolation() throws Exception {
        var echoReceived = new CopyOnWriteArrayList<String>();
        var room2Received = new CopyOnWriteArrayList<String>();
        var echoLatch = new MessageLatch(m -> m.contains("echo-only-message"));
        var openLatch1 = new CountDownLatch(1);
        var openLatch2 = new CountDownLatch(1);

        var wsEcho = httpClient.newWebSocketBuilder()
                .buildAsync(buildWsUri("/echo"),
                        new CollectingListener(echoReceived, openLatch1, echoLatch))
                .join();
        assertTrue(openLatch1.await(5, TimeUnit.SECONDS));

        var wsRoom2 = httpClient.newWebSocketBuilder()
                .buildAsync(buildWsUri("/room2"),
                        new CollectingListener(room2Received, openLatch2, new MessageLatch(m -> false)))
                .join();
        assertTrue(openLatch2.await(5, TimeUnit.SECONDS));

        Thread.sleep(1000);

        wsEcho.sendText("echo-only-message", true).join();
        assertTrue(echoLatch.await(5, TimeUnit.SECONDS), "Echo client should receive message");

        Thread.sleep(1000);
        assertTrue(room2Received.stream().noneMatch(m -> m.contains("echo-only-message")),
                "Room2 should NOT receive echo message, but got: " + room2Received);

        wsEcho.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        wsRoom2.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testClientDisconnect() throws Exception {
        var openLatch = new CountDownLatch(1);
        var received = new CopyOnWriteArrayList<String>();

        var ws = httpClient.newWebSocketBuilder()
                .buildAsync(buildWsUri("/echo"),
                        new CollectingListener(received, openLatch, new MessageLatch(m -> false)))
                .join();
        assertTrue(openLatch.await(5, TimeUnit.SECONDS));

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    private URI buildWsUri(String path) {
        return URI.create(server.getWebSocketUrl() + path
                + "?X-Atmosphere-tracking-id=" + UUID.randomUUID()
                + "&X-Atmosphere-Transport=websocket"
                + "&X-Atmosphere-Framework=5.0.0");
    }

    /**
     * Latch that counts down only when received messages match a predicate.
     * Ignores protocol padding (whitespace-only messages).
     */
    static class MessageLatch {
        private final Predicate<String> matcher;
        private final CountDownLatch latch;

        MessageLatch(Predicate<String> matcher) {
            this(matcher, 1);
        }

        MessageLatch(Predicate<String> matcher, int count) {
            this.matcher = matcher;
            this.latch = new CountDownLatch(count);
        }

        void onMessage(String msg) {
            if (matcher.test(msg)) {
                latch.countDown();
            }
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
    }

    /**
     * WebSocket listener that collects received text messages, filtering out padding.
     */
    static class CollectingListener implements WebSocket.Listener {
        private final CopyOnWriteArrayList<String> received;
        private final CountDownLatch openLatch;
        private final MessageLatch messageLatch;
        private final StringBuilder buffer = new StringBuilder();

        CollectingListener(CopyOnWriteArrayList<String> received,
                          CountDownLatch openLatch, MessageLatch messageLatch) {
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
                    messageLatch.onMessage(msg);
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
