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
package org.atmosphere.integrationtests.ai.isolation;

import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt isolation on one {@code @AiEndpoint} path, over real HTTP and WebSocket.
 * Client A posts a prompt after its poll completed and before its next poll
 * arrived: its connection is not registered in that gap. The prompt used to
 * fan out to every subscriber of the path, so client B's {@code @Prompt} ran
 * on A's prompt and B received the answer. It must reach only A. The SSE and
 * WebSocket paths, which never had that gap, still dispatch to their sender only.
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AiEndpointPromptIsolationTest {

    private EmbeddedAtmosphereServer server;
    private HttpClient httpClient;

    @BeforeAll
    public void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.ai.isolation")
                .withInitParam("org.atmosphere.annotation.packages", "org.atmosphere.ai.processor");
        server.start();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        // The framework initialises on the first request.
        httpClient.send(HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "/")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    @AfterAll
    public void tearDown() throws Exception {
        // B's polls are still suspended by design; close() would wait for them.
        httpClient.shutdownNow();
        server.close();
    }

    @BeforeEach
    public void reset() {
        PromptIsolationTestEndpoint.INVOCATIONS.clear();
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void promptPostedBetweenTwoPollsReachesOnlyItsSender() throws Exception {
        var a = "client-A-" + UUID.randomUUID();
        var b = "client-B-" + UUID.randomUUID();

        var firstPollA = poll(a);
        var pollB = poll(b);
        awaitRegistered(a);
        awaitRegistered(b);

        // A's poll completes (as a reply frame completes it): the server unregisters
        // the connection until A polls again.
        server.getFramework().atmosphereFactory().findResource(a).orElseThrow().resume();
        firstPollA.get(5, TimeUnit.SECONDS);
        awaitTrue(() -> server.getFramework().atmosphereFactory().findResource(a).isEmpty(),
                "A's completed poll must be unregistered");

        // A posts in the gap, then polls again.
        var post = post(a, "only-for-A");
        Thread.sleep(300);
        assertTrue(PromptIsolationTestEndpoint.INVOCATIONS.isEmpty(),
                "no @Prompt may answer before A's next poll, got " + PromptIsolationTestEndpoint.INVOCATIONS);
        var secondPollA = poll(a);

        assertEquals(200, post.get(10, TimeUnit.SECONDS).statusCode());
        // The prompt was dispatched to A's next poll.
        secondPollA.get(10, TimeUnit.SECONDS);

        awaitTrue(() -> !PromptIsolationTestEndpoint.INVOCATIONS.isEmpty(), "A's prompt must run");
        Thread.sleep(300);
        assertEquals(List.of(a + "|only-for-A"), PromptIsolationTestEndpoint.INVOCATIONS,
                "the prompt must run once, on A's connection only");
        assertFalse(pollB.isDone(), "B must never be sent A's prompt or its answer");
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void promptWithoutAnyTrackingIdIsRejectedWith400() throws Exception {
        var b = "client-B-" + UUID.randomUUID();
        var pollB = poll(b);
        awaitRegistered(b);

        var response = httpClient.send(HttpRequest.newBuilder(URI.create(server.getBaseUrl()
                        + PromptIsolationTestEndpoint.PATH
                        + "?X-Atmosphere-Transport=long-polling&X-Atmosphere-Framework=5.0.0"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("anonymous"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        Thread.sleep(300);
        assertTrue(PromptIsolationTestEndpoint.INVOCATIONS.isEmpty(),
                "an unidentified prompt must not run anywhere, got " + PromptIsolationTestEndpoint.INVOCATIONS);
        assertFalse(pollB.isDone(), "B must not be sent anything");
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void promptForATrackingIdTheServerNeverSawIsRejectedWith503() throws Exception {
        var b = "client-B-" + UUID.randomUUID();
        var pollB = poll(b);
        awaitRegistered(b);

        var response = post("never-polled-" + UUID.randomUUID(), "stray").get(10, TimeUnit.SECONDS);

        assertEquals(503, response.statusCode());
        assertEquals("1", response.headers().firstValue("Retry-After").orElse(null));
        Thread.sleep(300);
        assertTrue(PromptIsolationTestEndpoint.INVOCATIONS.isEmpty(),
                "a stray prompt must not run anywhere, got " + PromptIsolationTestEndpoint.INVOCATIONS);
        assertFalse(pollB.isDone(), "B must not be sent anything");
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void ssePromptStillReachesOnlyItsSender() throws Exception {
        var sse = "sse-" + UUID.randomUUID();
        var b = "client-B-" + UUID.randomUUID();
        httpClient.sendAsync(HttpRequest.newBuilder(uri(sse, "sse")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        var pollB = poll(b);
        awaitRegistered(sse);
        awaitRegistered(b);

        var response = httpClient.send(HttpRequest.newBuilder(uri(sse, "sse"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("sse-prompt"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        awaitTrue(() -> !PromptIsolationTestEndpoint.INVOCATIONS.isEmpty(), "the SSE prompt must run");
        Thread.sleep(300);
        assertEquals(List.of(sse + "|sse-prompt"), PromptIsolationTestEndpoint.INVOCATIONS);
        assertFalse(pollB.isDone(), "B must not be sent the SSE client's prompt");
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void webSocketPromptStillReachesOnlyItsSender() throws Exception {
        var ws = "ws-" + UUID.randomUUID();
        var b = "client-B-" + UUID.randomUUID();
        var pollB = poll(b);
        awaitRegistered(b);
        var socket = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(server.getWebSocketUrl() + PromptIsolationTestEndpoint.PATH
                        + "?X-Atmosphere-tracking-id=" + ws
                        + "&X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0"),
                        new WebSocket.Listener() { })
                .get(5, TimeUnit.SECONDS);
        awaitRegistered(ws);

        socket.sendText("ws-prompt", true).get(5, TimeUnit.SECONDS);

        awaitTrue(() -> !PromptIsolationTestEndpoint.INVOCATIONS.isEmpty(), "the WebSocket prompt must run");
        Thread.sleep(300);
        assertEquals(List.of(ws + "|ws-prompt"), PromptIsolationTestEndpoint.INVOCATIONS);
        assertFalse(pollB.isDone(), "B must not be sent the WebSocket client's prompt");
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    private CompletableFuture<HttpResponse<String>> poll(String trackingId) {
        return httpClient.sendAsync(HttpRequest.newBuilder(uri(trackingId)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> post(String trackingId, String prompt) {
        return httpClient.sendAsync(HttpRequest.newBuilder(uri(trackingId))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(prompt))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String trackingId) {
        return uri(trackingId, "long-polling");
    }

    private URI uri(String trackingId, String transport) {
        return URI.create(server.getBaseUrl() + PromptIsolationTestEndpoint.PATH
                + "?X-Atmosphere-tracking-id=" + trackingId
                + "&X-Atmosphere-Transport=" + transport
                + "&X-Atmosphere-Framework=5.0.0");
    }

    private void awaitRegistered(String trackingId) throws InterruptedException {
        awaitTrue(() -> server.getFramework().atmosphereFactory().findResource(trackingId).isPresent(),
                trackingId + " must be registered");
    }

    private static void awaitTrue(BooleanSupplier condition, String message) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(message);
            }
            Thread.sleep(20);
        }
    }
}
