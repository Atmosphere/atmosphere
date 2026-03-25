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
package org.atmosphere.integrationtests.agent;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for {@code @Agent} with an embedded Jetty server.
 * Tests command routing, /help auto-generation, confirmation flow, and LLM fallback
 * over WebSocket transport.
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AgentWebSocketIntegrationTest {

    private EmbeddedAtmosphereServer server;
    private HttpClient httpClient;

    @BeforeAll
    public void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.agent")
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
    public void testPingCommand() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var messageLatch = new MessageLatch(m -> m.contains("pong"));

        var ws = connect("/atmosphere/agent/test-agent", received, openLatch, messageLatch);
        assertTrue(openLatch.await(5, TimeUnit.SECONDS), "WebSocket should connect");
        Thread.sleep(500);

        ws.sendText("/ping", true).join();
        assertTrue(messageLatch.await(5, TimeUnit.SECONDS),
                "Should receive 'pong' but got: " + received);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Timeout(value = 15_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testEchoCommand() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var messageLatch = new MessageLatch(m -> m.contains("echo: hello world"));

        var ws = connect("/atmosphere/agent/test-agent", received, openLatch, messageLatch);
        assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        ws.sendText("/echo hello world", true).join();
        assertTrue(messageLatch.await(5, TimeUnit.SECONDS),
                "Should receive 'echo: hello world' but got: " + received);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Timeout(value = 15_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testHelpCommand() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var messageLatch = new MessageLatch(m -> m.contains("/ping") && m.contains("/echo"));

        var ws = connect("/atmosphere/agent/test-agent", received, openLatch, messageLatch);
        assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        ws.sendText("/help", true).join();
        assertTrue(messageLatch.await(5, TimeUnit.SECONDS),
                "Help should list /ping and /echo but got: " + received);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Timeout(value = 15_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testConfirmationFlow() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var confirmLatch = new MessageLatch(m -> m.contains("dangerous"));

        var ws = connect("/atmosphere/agent/test-agent", received, openLatch, confirmLatch);
        assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        // Send dangerous command — should get confirmation prompt
        ws.sendText("/danger", true).join();
        assertTrue(confirmLatch.await(5, TimeUnit.SECONDS),
                "Should receive confirmation prompt but got: " + received);

        // Confirm — response goes only to the requesting client (unicast)
        var executeLatch = new MessageLatch(m -> m.contains("Danger executed!"));
        received.clear();
        var ws2 = connect("/atmosphere/agent/test-agent", received, new CountDownLatch(1), executeLatch);
        // Re-register latch on same ws connection since responses are unicast
        ws.sendText("yes", true).join();

        // The confirmation response goes to ws (sender), so wait a moment and check received
        Thread.sleep(2000);
        assertTrue(received.stream().anyMatch(m -> m.contains("Danger executed!")),
                "Should receive execution result but got: " + received);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Timeout(value = 15_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testNonCommandFallsToLlm() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        // The test agent's @Prompt handler echoes with "Agent received: "
        var messageLatch = new MessageLatch(m -> m.contains("Agent received:"));

        var ws = connect("/atmosphere/agent/test-agent", received, openLatch, messageLatch);
        assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        ws.sendText("What is the weather?", true).join();
        assertTrue(messageLatch.await(10, TimeUnit.SECONDS),
                "Non-command should reach LLM pipeline but got: " + received);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Timeout(value = 15_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testUnknownCommandFallsToLlm() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var messageLatch = new MessageLatch(m -> m.contains("Agent received:"));

        var ws = connect("/atmosphere/agent/test-agent", received, openLatch, messageLatch);
        assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        // Unknown /command falls through to LLM
        ws.sendText("/unknown-command", true).join();
        assertTrue(messageLatch.await(10, TimeUnit.SECONDS),
                "Unknown command should fall to LLM but got: " + received);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    private WebSocket connect(String path, CopyOnWriteArrayList<String> received,
                              CountDownLatch openLatch, MessageLatch messageLatch) throws Exception {
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
