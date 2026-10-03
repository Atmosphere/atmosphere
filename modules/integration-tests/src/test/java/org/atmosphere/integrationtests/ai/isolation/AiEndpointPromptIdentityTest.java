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

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;
import org.atmosphere.interceptor.AuthInterceptor;
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
import java.security.Principal;
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
 * A prompt runs only on a connection of its sender's identity, over real HTTP and
 * WebSocket with the framework's {@link AuthInterceptor}. A tracking id is no
 * proof of who holds a connection: any client may name one, and a connection
 * opened under an id replaces the earlier one in the resource factory. Bob's
 * prompt naming Alice's connection must not run on it, as Alice, with the reply
 * sent to her; nor may Alice's prompt run on a connection Bob opened under her id.
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AiEndpointPromptIdentityTest {

    private EmbeddedAtmosphereServer server;
    private HttpClient httpClient;

    @BeforeAll
    public void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.ai.isolation")
                .withInitParam("org.atmosphere.annotation.packages", "org.atmosphere.ai.processor")
                .withInitParam(ApplicationConfig.ATMOSPHERE_INTERCEPTORS, AuthInterceptor.class.getName())
                .withInitParam(ApplicationConfig.AUTH_TOKEN_VALIDATOR, NamedTokenValidator.class.getName());
        server.start();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        // The framework initialises on the first request.
        httpClient.send(HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "/")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    @AfterAll
    public void tearDown() throws Exception {
        // Polls are still suspended by design; close() would wait for them.
        httpClient.shutdownNow();
        server.close();
    }

    @BeforeEach
    public void reset() {
        PromptIsolationTestEndpoint.INVOCATIONS.clear();
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void promptNamingAnotherUsersConnectionNeverRunsOnIt() throws Exception {
        var aliceId = "alice-" + UUID.randomUUID();
        var alicePoll = poll(aliceId, "alice");
        awaitRegistered(aliceId);

        var bobPost = post(aliceId, "bob", "bob-prompt");
        assertEquals(503, bobPost.get(10, TimeUnit.SECONDS).statusCode(),
                "Bob's prompt naming Alice's connection must be refused");
        Thread.sleep(300);
        assertTrue(PromptIsolationTestEndpoint.INVOCATIONS.isEmpty(),
                "Bob's prompt must not run, got " + PromptIsolationTestEndpoint.INVOCATIONS);
        assertFalse(alicePoll.isDone(), "Alice must not be sent Bob's prompt or its answer");

        // Alice's own prompt still runs on her connection.
        assertEquals(200, post(aliceId, "alice", "alice-prompt").get(10, TimeUnit.SECONDS).statusCode());
        awaitTrue(() -> !PromptIsolationTestEndpoint.INVOCATIONS.isEmpty(), "Alice's prompt must run");
        assertEquals(List.of(aliceId + "|alice-prompt"), PromptIsolationTestEndpoint.INVOCATIONS);
        // A long-polling reply is handed over whole: Alice's waiting poll returns
        // to fetch it, and her next poll carries every frame of her run.
        alicePoll.get(10, TimeUnit.SECONDS);
        var reply = poll(aliceId, "alice").get(10, TimeUnit.SECONDS).body();
        assertTrue(reply.contains("X-Atmosphere-Run-Id"), "Alice must be sent her run's frames: " + reply);
        assertTrue(reply.contains("reply-to:alice-prompt"), "Alice must be sent her whole reply: " + reply);
        assertTrue(reply.contains("\"complete\""), "Alice's reply must end with its terminal frame: " + reply);
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void connectionOpenedUnderAnotherUsersIdNeverRunsTheirPrompt() throws Exception {
        var aliceId = "alice-" + UUID.randomUUID();
        poll(aliceId, "alice");
        awaitRegistered(aliceId);
        // Bob opens a poll under Alice's id: the factory now lists his under it.
        var bobPoll = poll(aliceId, "bob");
        awaitTrue(() -> server.getFramework().atmosphereFactory().findResource(aliceId)
                        .map(r -> r.getRequest().getAttribute(FrameworkConfig.AUTH_PRINCIPAL)
                                instanceof Principal p && "bob".equals(p.getName()))
                        .orElse(false),
                "Bob's poll must replace Alice's under her id");

        var alicePost = post(aliceId, "alice", "alice-prompt");
        assertEquals(503, alicePost.get(10, TimeUnit.SECONDS).statusCode(),
                "Alice's prompt must not run on Bob's connection");
        Thread.sleep(300);
        assertTrue(PromptIsolationTestEndpoint.INVOCATIONS.isEmpty(),
                "Alice's prompt must not run, got " + PromptIsolationTestEndpoint.INVOCATIONS);
        assertFalse(bobPoll.isDone(), "Bob must not be sent Alice's prompt or its answer");
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void signedInWebSocketPromptRunsOnItsOwnSocket() throws Exception {
        var ws = "ws-" + UUID.randomUUID();
        var socket = httpClient.newWebSocketBuilder()
                .buildAsync(uri(ws, "websocket", "alice", server.getWebSocketUrl()), new WebSocket.Listener() { })
                .get(5, TimeUnit.SECONDS);
        awaitRegistered(ws);

        // The frame carries no token: it is authenticated by its socket's handshake.
        socket.sendText("ws-prompt", true).get(5, TimeUnit.SECONDS);

        awaitTrue(() -> !PromptIsolationTestEndpoint.INVOCATIONS.isEmpty(), "the WebSocket prompt must run");
        assertEquals(List.of(ws + "|ws-prompt"), PromptIsolationTestEndpoint.INVOCATIONS);
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void signedInSsePromptRunsOnItsOwnConnection() throws Exception {
        var sse = "sse-" + UUID.randomUUID();
        httpClient.sendAsync(HttpRequest.newBuilder(uri(sse, "sse", "alice", server.getBaseUrl())).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        awaitRegistered(sse);

        var response = httpClient.send(HttpRequest.newBuilder(uri(sse, "sse", "alice", server.getBaseUrl()))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("sse-prompt"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        awaitTrue(() -> !PromptIsolationTestEndpoint.INVOCATIONS.isEmpty(), "the SSE prompt must run");
        assertEquals(List.of(sse + "|sse-prompt"), PromptIsolationTestEndpoint.INVOCATIONS);
    }

    private CompletableFuture<HttpResponse<String>> poll(String trackingId, String user) {
        return httpClient.sendAsync(HttpRequest.newBuilder(uri(trackingId, "long-polling", user,
                server.getBaseUrl())).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> post(String trackingId, String user, String prompt) {
        return httpClient.sendAsync(HttpRequest.newBuilder(uri(trackingId, "long-polling", user,
                        server.getBaseUrl()))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(prompt))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(String trackingId, String transport, String user, String base) {
        return URI.create(base + PromptIsolationTestEndpoint.PATH
                + "?X-Atmosphere-tracking-id=" + trackingId
                + "&X-Atmosphere-Transport=" + transport
                + "&X-Atmosphere-Framework=5.0.0"
                + "&X-Atmosphere-Auth=" + NamedTokenValidator.PREFIX + user);
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
