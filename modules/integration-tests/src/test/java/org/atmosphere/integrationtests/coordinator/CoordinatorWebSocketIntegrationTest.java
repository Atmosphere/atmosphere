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
package org.atmosphere.integrationtests.coordinator;

import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for {@code @Coordinator} with an embedded Jetty
 * server. Tests fleet delegation to headless agents, parallel execution, and
 * result synthesis over WebSocket transport.
 */
@Tag("coordinator")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CoordinatorWebSocketIntegrationTest {

    private EmbeddedAtmosphereServer server;
    private HttpClient httpClient;

    @BeforeAll
    public void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.coordinator")
                .withInitParam("org.atmosphere.annotation.packages",
                        "org.atmosphere.agent.processor,"
                        + "org.atmosphere.coordinator.processor,"
                        + "org.atmosphere.ai.processor");
        server.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterAll
    public void tearDown() throws Exception {
        if (httpClient != null) {
            httpClient.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Timeout(value = 30_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testCoordinatorDelegatesToFleet() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        // The coordinator synthesizes results from both workers
        var messageLatch = new MessageLatch(m ->
                m.contains("Alpha analysis") && m.contains("Beta summary"));

        var ws = connect("/atmosphere/agent/test-coordinator",
                received, openLatch, messageLatch);
        assertTrue(openLatch.await(5, TimeUnit.SECONDS), "WebSocket should connect");
        Thread.sleep(500);

        ws.sendText("quantum computing", true).join();
        assertTrue(messageLatch.await(15, TimeUnit.SECONDS),
                "Should receive synthesized results from both workers but got: " + received);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Timeout(value = 30_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testHeadlessAgentRespondsToDirect_A2A() throws Exception {
        // Test that headless worker-alpha responds directly to A2A JSON-RPC
        var rpcRequest = """
                {"jsonrpc":"2.0","id":1,"method":"message/send",
                 "params":{"message":{"role":"user",
                   "parts":[{"type":"text","text":"test topic"}],
                   "metadata":{"skillId":"analyze"}},
                  "arguments":{"topic":"test topic"}}}""";

        var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(server.getBaseUrl() + "/atmosphere/a2a/worker-alpha"))
                .POST(HttpRequest.BodyPublishers.ofString(rpcRequest))
                .header("Content-Type", "application/json")
                .build();

        var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Alpha analysis"),
                "A2A response should contain worker output but got: " + response.body());
    }

    @Timeout(value = 30_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testCoordinatorSynthesizesSequentialAndParallel() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        // Verify the coordinator's synthesis format
        var messageLatch = new MessageLatch(m ->
                m.contains("Coordinator synthesis"));

        var ws = connect("/atmosphere/agent/test-coordinator",
                received, openLatch, messageLatch);
        assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        ws.sendText("AI trends 2026", true).join();
        assertTrue(messageLatch.await(15, TimeUnit.SECONDS),
                "Should receive coordinator synthesis but got: " + received);

        // Verify both alpha and beta results are in the synthesis
        var synthesis = received.stream()
                .filter(m -> m.contains("Coordinator synthesis"))
                .findFirst();
        assertTrue(synthesis.isPresent());
        assertTrue(synthesis.get().contains("Alpha"),
                "Synthesis should include Alpha results");
        assertTrue(synthesis.get().contains("Beta"),
                "Synthesis should include Beta results");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Timeout(value = 30_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testMultipleClientsReceiveIndependentResponses() throws Exception {
        var received1 = new CopyOnWriteArrayList<String>();
        var received2 = new CopyOnWriteArrayList<String>();
        var open1 = new CountDownLatch(1);
        var open2 = new CountDownLatch(1);
        var latch1 = new MessageLatch(m -> m.contains("Alpha analysis"));
        var latch2 = new MessageLatch(m -> m.contains("Alpha analysis"));

        var ws1 = connect("/atmosphere/agent/test-coordinator",
                received1, open1, latch1);
        var ws2 = connect("/atmosphere/agent/test-coordinator",
                received2, open2, latch2);

        assertTrue(open1.await(5, TimeUnit.SECONDS));
        assertTrue(open2.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        // Both clients send prompts
        ws1.sendText("topic one", true).join();
        ws2.sendText("topic two", true).join();

        assertTrue(latch1.await(15, TimeUnit.SECONDS),
                "Client 1 should receive response");
        assertTrue(latch2.await(15, TimeUnit.SECONDS),
                "Client 2 should receive response");

        ws1.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    // --- Helpers ---

    private WebSocket connect(String path, CopyOnWriteArrayList<String> received,
                              CountDownLatch openLatch, MessageLatch messageLatch)
            throws Exception {
        return httpClient.newWebSocketBuilder()
                .buildAsync(buildWsUri(path),
                        new CollectingListener(received, openLatch, messageLatch))
                .join();
    }

    private URI buildWsUri(String path) {
        return URI.create(server.getWebSocketUrl() + path
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
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data,
                                          boolean last) {
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
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode,
                                           String reason) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
        }
    }
}
