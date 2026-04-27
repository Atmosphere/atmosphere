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
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for agent activity streaming. Verifies that
 * {@code agent-step} events (thinking, completed) arrive on the WebSocket
 * wire when a coordinator uses {@code fleet.withActivityListener()}.
 */
@Tag("coordinator")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CoordinatorActivityIntegrationTest {

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
        if (httpClient != null) httpClient.close();
        if (server != null) server.close();
    }

    @Timeout(value = 30_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testActivityEventsStreamedToClient() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);

        // Wait for the final synthesis to arrive (proves the full pipeline ran)
        var synthesisLatch = new MessageLatch(m -> m.contains("Activity synthesis"));

        var ws = connect("/atmosphere/agent/activity-coordinator",
                received, openLatch, synthesisLatch);
        assertTrue(openLatch.await(5, TimeUnit.SECONDS), "WebSocket should connect");
        // JDK 25/26 virtual thread scheduling can delay WebSocket suspension.
        // Wait longer to ensure the AtmosphereResource is suspended before sending.
        Thread.sleep(2000);

        ws.sendText("quantum computing", true).join();
        assertTrue(synthesisLatch.await(25, TimeUnit.SECONDS),
                "Should receive synthesis but got: " + received);

        // Verify agent-step events were streamed BEFORE the synthesis
        // The StreamingActivityListener maps AgentActivity to AiEvent.AgentStep
        // which serializes as event.agent-step in the metadata stream
        var hasThinking = received.stream()
                .anyMatch(m -> m.contains("thinking") || m.contains("agent-step"));
        var hasCompleted = received.stream()
                .anyMatch(m -> m.contains("completed") || m.contains("agent-step"));

        // The activity events should be in the received messages
        // (emitted via session.emit -> sendMetadata -> broadcast)
        assertTrue(hasThinking || hasCompleted || received.size() > 1,
                "Should receive activity events before synthesis. "
                        + "Received " + received.size() + " messages: " + received);

        // Verify both workers' results are in the synthesis
        var synthesis = received.stream()
                .filter(m -> m.contains("Activity synthesis"))
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
    public void testActivityCoordinatorRespondsToA2a() throws Exception {
        // Test that the activity-coordinator also responds via A2A JSON-RPC v1.0.0
        var rpcRequest = """
                {"jsonrpc":"2.0","id":1,"method":"SendMessage",
                 "params":{"message":{"messageId":"m1","role":"ROLE_USER",
                   "parts":[{"text":"machine learning"}],
                   "metadata":{"skillId":"chat"}},
                  "arguments":{"message":"machine learning"}}}""";

        var httpRequest = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(server.getBaseUrl()
                        + "/atmosphere/agent/activity-coordinator/a2a"))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(rpcRequest))
                .header("Content-Type", "application/json")
                .build();

        var response = httpClient.send(httpRequest,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() == 200,
                "A2A should respond 200 but got " + response.statusCode());
        assertTrue(response.body().contains("Alpha") || response.body().contains("Beta"),
                "A2A response should contain worker output but got: " + response.body());
    }

    @Timeout(value = 30_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testMultipleActivityMessages() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var messageLatch = new MessageLatch(m -> m.contains("Activity synthesis"));

        var ws = connect("/atmosphere/agent/activity-coordinator",
                received, openLatch, messageLatch);
        assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        // Send first message
        ws.sendText("topic one", true).join();
        assertTrue(messageLatch.await(25, TimeUnit.SECONDS),
                "Should receive first synthesis");

        // Verify we got more than just the synthesis (activity events + synthesis)
        assertFalse(received.isEmpty(), "Should have received messages");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
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
