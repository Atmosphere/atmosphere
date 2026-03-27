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
package org.atmosphere.integrationtests.agent.structured;

import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E test for structured output via {@code @Agent(responseAs = CityInfo.class)}.
 * Two concurrent WebSocket clients connect and both receive structured entity events.
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AgentStructuredOutputTest {

    private EmbeddedAtmosphereServer server;
    private HttpClient httpClient;

    @BeforeAll
    public void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.agent.structured")
                .withInitParam("org.atmosphere.annotation.packages",
                        "org.atmosphere.agent.processor,org.atmosphere.ai.processor");
        server.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterAll
    public void tearDown() throws Exception {
        httpClient.close();
        server.close();
    }

    @Timeout(value = 15_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void agentReturnsStructuredCityInfo() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var entityLatch = new MessageLatch(m -> m.contains("Montreal")
                || m.contains("entity-complete"));

        var ws = connect(received, openLatch, entityLatch);
        assertTrue(openLatch.await(5, TimeUnit.SECONDS), "WebSocket should connect");
        Thread.sleep(500);

        ws.sendText("Tell me about Montreal", true).join();
        assertTrue(entityLatch.await(10, TimeUnit.SECONDS),
                "Should receive Montreal data but got: " + received);

        assertTrue(received.stream().anyMatch(m -> m.contains("Montreal")),
                "Response should contain 'Montreal' but got: " + received);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Timeout(value = 15_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void twoConcurrentAgentClients() throws Exception {
        var received1 = new CopyOnWriteArrayList<String>();
        var received2 = new CopyOnWriteArrayList<String>();
        var openLatch1 = new CountDownLatch(1);
        var openLatch2 = new CountDownLatch(1);
        var latch1 = new MessageLatch(m -> m.contains("Montreal"));
        var latch2 = new MessageLatch(m -> m.contains("Montreal"));

        var ws1 = connect(received1, openLatch1, latch1);
        var ws2 = connect(received2, openLatch2, latch2);

        assertTrue(openLatch1.await(5, TimeUnit.SECONDS), "Client 1 should connect");
        assertTrue(openLatch2.await(5, TimeUnit.SECONDS), "Client 2 should connect");
        Thread.sleep(500);

        ws1.sendText("City info please", true).join();
        ws2.sendText("City info please", true).join();

        assertTrue(latch1.await(10, TimeUnit.SECONDS),
                "Client 1 should receive Montreal but got: " + received1);
        assertTrue(latch2.await(10, TimeUnit.SECONDS),
                "Client 2 should receive Montreal but got: " + received2);

        ws1.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    private WebSocket connect(CopyOnWriteArrayList<String> received,
                              CountDownLatch openLatch,
                              MessageLatch messageLatch) throws Exception {
        return httpClient.newWebSocketBuilder()
                .buildAsync(buildWsUri(),
                        new CollectingListener(received, openLatch, messageLatch))
                .join();
    }

    private URI buildWsUri() {
        return URI.create(server.getWebSocketUrl() + "/atmosphere/agent/city-info"
                + "?X-Atmosphere-tracking-id=" + UUID.randomUUID()
                + "&X-Atmosphere-Transport=websocket"
                + "&X-Atmosphere-Framework=5.0.0");
    }

    static class MessageLatch {
        private final Predicate<String> matcher;
        private final CountDownLatch latch;

        MessageLatch(Predicate<String> matcher) {
            this.matcher = matcher;
            this.latch = new CountDownLatch(1);
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
    }
}
