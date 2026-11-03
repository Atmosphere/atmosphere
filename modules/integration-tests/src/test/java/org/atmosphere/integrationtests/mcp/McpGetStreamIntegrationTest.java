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
package org.atmosphere.integrationtests.mcp;

import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;
import org.atmosphere.mcp.runtime.McpHandler;
import org.atmosphere.mcp.runtime.McpSession;
import org.atmosphere.mcp.bridge.SseEventReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Streamable HTTP GET stream of a session, on a live embedded server: a
 * server-initiated request reaches a stream that is already open, and once
 * the client drops that stream the next one is queued for its reconnect
 * instead of being written into a dead connection.
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class McpGetStreamIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(10);

    private EmbeddedAtmosphereServer server;
    private HttpClient http;

    @SuppressWarnings("resource") // closed in tearDown()
    @BeforeAll
    public void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.mcp")
                .withInitParam("org.atmosphere.annotation.packages", "org.atmosphere.agent.processor");
        server.start();
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterAll
    public void tearDown() throws Exception {
        http.close();
        server.close();
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void aServerRequestReachesTheAlreadyOpenStream() throws Exception {
        var sessionId = initialize();
        var session = sessions().get(sessionId);
        try (var stream = openStream(sessionId)) {
            awaitTrue(session::hasStream, "the GET stream must be attached to the session");

            var call = http.sendAsync(post(sessionId, """
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"confirm","arguments":{"question":"Go?"}}}"""),
                    HttpResponse.BodyHandlers.ofString());

            var event = new SseEventReader(stream).next().orElseThrow();
            var request = MAPPER.readTree(event);
            assertEquals("elicitation/create", request.path("method").asString());

            http.send(post(sessionId, MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0",
                    "id", request.path("id").asString(),
                    "result", Map.of("action", "decline")))), HttpResponse.BodyHandlers.discarding());
            var body = call.get(WAIT.toSeconds(), TimeUnit.SECONDS).body();
            assertTrue(body.contains("action=decline"), body);
        }
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void aMessageAfterTheClientDropsItsStreamIsQueuedForTheReconnect() throws Exception {
        var sessionId = initialize();
        var session = sessions().get(sessionId);
        var stream = openStream(sessionId);
        awaitTrue(session::hasStream, "the GET stream must be attached to the session");

        stream.close();
        // Either the container reports the disconnect and the stream is
        // detached, or the next write fails and detaches it: both must leave
        // the message queued, never written into the dead connection.
        awaitTrue(() -> {
            if (session.hasStream()) {
                session.sendOrQueue("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/probe\"}");
            }
            return !session.hasStream();
        }, "a dropped GET stream must be detached");
        session.drainPendingNotifications();
        session.sendOrQueue("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/after-drop\"}");
        assertEquals(1, session.pendingCount(), "a message with no stream open is queued");

        try (var reconnected = openStream(sessionId)) {
            var replayed = MAPPER.readTree(new SseEventReader(reconnected).next().orElseThrow());
            assertEquals("notifications/after-drop", replayed.path("method").asString());
        }
    }

    private String initialize() throws Exception {
        var response = http.send(post(null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                 "capabilities":{"elicitation":{}},"clientInfo":{"name":"get-stream-it","version":"1"}}}"""),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(200, response.statusCode());
        return response.headers().firstValue(McpSession.SESSION_ID_HEADER).orElseThrow();
    }

    private InputStream openStream(String sessionId) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "/mcp"))
                .header("Accept", "text/event-stream")
                .header(McpSession.SESSION_ID_HEADER, sessionId)
                .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private HttpRequest post(String sessionId, String body) {
        var builder = HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            builder.header(McpSession.SESSION_ID_HEADER, sessionId);
        }
        return builder.build();
    }

    private Map<String, McpSession> sessions() {
        for (var wrapper : server.getFramework().getAtmosphereHandlers().values()) {
            if (wrapper.atmosphereHandler() instanceof McpHandler handler) {
                return handler.sessions();
            }
        }
        throw new IllegalStateException("no McpHandler registered");
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(what + " (not within " + WAIT + ")");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        }
    }
}
