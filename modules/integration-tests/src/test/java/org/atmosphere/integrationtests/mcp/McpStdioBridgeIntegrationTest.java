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
import org.atmosphere.mcp.bridge.McpStdioBridge;
import org.atmosphere.mcp.runtime.McpHandler;
import org.atmosphere.mcp.runtime.McpSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link McpStdioBridge} against a live embedded Jetty server, the way
 * a stdio MCP client (Claude Desktop, an {@code .mcpb} bundle) runs it: JSON-RPC
 * lines in on stdin, lines out on stdout.
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class McpStdioBridgeIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(10);

    private EmbeddedAtmosphereServer server;
    private EmbeddedAtmosphereServer securedServer;
    private final List<Bridge> bridges = new ArrayList<>();

    @SuppressWarnings("resource") // closed in tearDown()
    @BeforeAll
    public void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.mcp")
                .withInitParam("org.atmosphere.annotation.packages", "org.atmosphere.agent.processor");
        server.start();
        securedServer = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.mcp")
                .withInitParam("org.atmosphere.annotation.packages", "org.atmosphere.agent.processor")
                .withInitParam("org.atmosphere.mcp.auth.resource", "https://api.example.com/mcp")
                .withInitParam("org.atmosphere.mcp.auth.authorizationServers", "https://auth.example.com")
                .withInitParam("org.atmosphere.auth.tokenValidator",
                        "org.atmosphere.integrationtests.mcp.FixedTokenValidator");
        securedServer.start();
    }

    @AfterEach
    public void closeBridges() {
        bridges.forEach(Bridge::close);
        bridges.clear();
    }

    @AfterAll
    public void tearDown() throws Exception {
        server.close();
        securedServer.close();
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void relaysACallAndEndsTheSessionWhenStdinEnds() throws Exception {
        var bridge = start(McpStdioBridge.Options.of(endpoint(server)));
        bridge.initialize(false);

        bridge.send("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}""");
        var result = bridge.awaitResponse(2);
        assertEquals("Echo: hi", result.path("result").path("content").get(0).path("text").asString());

        var sessionId = bridge.bridge.sessionId();
        assertNotNull(sessionId);
        assertTrue(sessions(server).containsKey(sessionId), "the bridge's session is live on the server");

        bridge.endInput();
        assertFalse(sessions(server).containsKey(sessionId),
                "stdin ending must end the session with DELETE, not leave it to the TTL");
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void relaysAnElicitationIssuedWhileTheGetStreamIsAlreadyOpen() throws Exception {
        var bridge = start(McpStdioBridge.Options.of(endpoint(server)));
        bridge.initialize(true);
        var session = sessions(server).get(bridge.bridge.sessionId());
        awaitTrue(session::hasStream, "the bridge must hold the session's GET stream open");

        bridge.send("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"confirm","arguments":{"question":"Proceed?"}}}""");
        var elicitation = bridge.await(m -> "elicitation/create".equals(m.path("method").asString("")));
        assertEquals("Proceed?", elicitation.path("params").path("message").asString());

        bridge.send(MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0",
                "id", MAPPER.treeToValue(elicitation.get("id"), Object.class),
                "result", Map.of("action", "accept", "content", Map.of("ok", true)))));
        var result = bridge.awaitResponse(3);
        assertEquals("action=accept ok=true",
                result.path("result").path("content").get(0).path("text").asString());
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void aSlowCallDoesNotHoldUpTheNextOne() throws Exception {
        var bridge = start(McpStdioBridge.Options.of(endpoint(server)));
        bridge.initialize(false);

        bridge.send("""
                {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"slow","arguments":{"millis":3000}}}""");
        bridge.send("""
                {"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"echo","arguments":{"text":"fast"}}}""");

        var first = bridge.await(m -> m.has("id"));
        assertEquals(11, first.path("id").asInt(), "the quick call must answer while the slow one runs");
        assertEquals("slept 3000",
                bridge.awaitResponse(10).path("result").path("content").get(0).path("text").asString());
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void aTimedOutCallIsAnsweredWithAnErrorAndTheBridgeKeepsGoing() throws Exception {
        var bridge = start(McpStdioBridge.Options.of(endpoint(server))
                .withRequestTimeout(Duration.ofMillis(500))
                .withDrainTimeout(Duration.ofSeconds(1)));
        bridge.initialize(false);

        bridge.send("""
                {"jsonrpc":"2.0","id":20,"method":"tools/call","params":{"name":"slow","arguments":{"millis":5000}}}""");
        var timedOut = bridge.awaitResponse(20);
        assertEquals(McpStdioBridge.TRANSPORT_ERROR, timedOut.path("error").path("code").asInt());
        assertTrue(timedOut.path("error").path("message").asString().contains("did not answer"),
                timedOut.toString());

        bridge.send("""
                {"jsonrpc":"2.0","id":21,"method":"tools/call","params":{"name":"echo","arguments":{"text":"still here"}}}""");
        assertEquals("Echo: still here",
                bridge.awaitResponse(21).path("result").path("content").get(0).path("text").asString());
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void anUnauthorizedRequestIsAnsweredWithAnErrorAndTheBridgeKeepsGoing() throws Exception {
        var bridge = start(McpStdioBridge.Options.of(endpoint(securedServer)));

        bridge.send(initialize(1, false));
        var refused = bridge.awaitResponse(1);
        assertEquals(McpStdioBridge.TRANSPORT_ERROR, refused.path("error").path("code").asInt());
        var message = refused.path("error").path("message").asString();
        assertTrue(message.contains("401") && message.contains(McpStdioBridge.ENV_TOKEN), message);

        bridge.send("""
                {"jsonrpc":"2.0","id":2,"method":"ping"}""");
        assertEquals(McpStdioBridge.TRANSPORT_ERROR, bridge.awaitResponse(2).path("error").path("code").asInt(),
                "the bridge must still answer after an HTTP error");
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void theBearerTokenReachesASecuredEndpoint() throws Exception {
        var bridge = start(McpStdioBridge.Options.of(endpoint(securedServer))
                .withBearerToken(FixedTokenValidator.GOOD_TOKEN));
        bridge.initialize(false);

        bridge.send("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"text":"authorized"}}}""");
        assertEquals("Echo: authorized",
                bridge.awaitResponse(2).path("result").path("content").get(0).path("text").asString());
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void anUnreachableEndpointAndABadLineAreAnsweredWithErrors() throws Exception {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        var bridge = start(McpStdioBridge.Options.of("http://127.0.0.1:" + closedPort + "/mcp"));

        bridge.send("not json");
        var parseError = bridge.await(m -> m.has("error"));
        assertEquals(-32700, parseError.path("error").path("code").asInt());
        assertTrue(parseError.get("id").isNull());

        bridge.send("""
                {"jsonrpc":"2.0","id":7,"method":"ping"}""");
        var unreachable = bridge.awaitResponse(7);
        assertTrue(unreachable.path("error").path("message").asString().contains("unreachable"),
                unreachable.toString());
    }

    // ── Harness ─────────────────────────────────────────────────────────

    private static void awaitTrue(BooleanSupplier condition, String what) {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(what + " (not within " + WAIT + ")");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
    }

    private static String endpoint(EmbeddedAtmosphereServer target) {
        return target.getBaseUrl() + "/mcp";
    }

    private static Map<String, McpSession> sessions(EmbeddedAtmosphereServer target) {
        for (var wrapper : target.getFramework().getAtmosphereHandlers().values()) {
            if (wrapper.atmosphereHandler() instanceof McpHandler handler) {
                return handler.sessions();
            }
        }
        throw new IllegalStateException("no McpHandler registered");
    }

    private static String initialize(int id, boolean elicitation) {
        var capabilities = elicitation ? "{\"elicitation\":{}}" : "{}";
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":" + capabilities + ","
                + "\"clientInfo\":{\"name\":\"bridge-it\",\"version\":\"1.0.0\"}}}";
    }

    private Bridge start(McpStdioBridge.Options options) {
        var bridge = new Bridge(options);
        bridges.add(bridge);
        return bridge;
    }

    /** A bridge on in-memory stdin/stdout, run on its own thread. */
    private static final class Bridge implements AutoCloseable {
        private final QueueInputStream stdin = new QueueInputStream();
        private final BlockingQueue<JsonNode> stdout = new LinkedBlockingQueue<>();
        private final List<JsonNode> seen = new ArrayList<>();
        private final McpStdioBridge bridge;
        private final Thread runner;

        Bridge(McpStdioBridge.Options options) {
            bridge = new McpStdioBridge(options, stdin, new LineOutputStream(stdout));
            runner = Thread.ofPlatform().name("bridge-under-test").start(() -> {
                try {
                    bridge.run();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        void initialize(boolean elicitation) throws InterruptedException {
            send(McpStdioBridgeIntegrationTest.initialize(1, elicitation));
            var response = awaitResponse(1);
            assertTrue(response.has("result"), response.toString());
            send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        }

        void send(String line) {
            stdin.offer((line + "\n").getBytes(StandardCharsets.UTF_8));
        }

        JsonNode awaitResponse(int id) throws InterruptedException {
            return await(m -> m.has("id") && !m.has("method") && m.path("id").asInt(-1) == id);
        }

        JsonNode await(Predicate<JsonNode> match) throws InterruptedException {
            for (var it = seen.iterator(); it.hasNext(); ) {
                var message = it.next();
                if (match.test(message)) {
                    it.remove();
                    return message;
                }
            }
            var deadline = System.nanoTime() + WAIT.toNanos();
            while (true) {
                var message = stdout.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (message == null) {
                    throw new AssertionError("no matching stdout line within " + WAIT + "; saw " + seen);
                }
                if (match.test(message)) {
                    return message;
                }
                seen.add(message);
            }
        }

        void endInput() throws InterruptedException {
            stdin.end();
            runner.join(WAIT.toMillis());
            assertFalse(runner.isAlive(), "run() must return once stdin ends");
        }

        @Override
        public void close() {
            stdin.end();
            bridge.close();
        }
    }

    /** stdin: blocks for the next chunk the test offers; {@link #end()} is EOF. */
    private static final class QueueInputStream extends InputStream {
        private static final byte[] EOF = new byte[0];
        private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private byte[] current = new byte[0];
        private int position;
        private boolean ended;

        void offer(byte[] chunk) {
            chunks.add(chunk);
        }

        void end() {
            chunks.add(EOF);
        }

        @Override
        public int read() throws IOException {
            var one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            while (!ended && position >= current.length) {
                try {
                    current = chunks.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
                position = 0;
                ended = current == EOF;
            }
            if (ended) {
                return -1;
            }
            var n = Math.min(length, current.length - position);
            System.arraycopy(current, position, buffer, offset, n);
            position += n;
            return n;
        }
    }

    /** stdout: every complete line, parsed, lands in the queue; a line that is not JSON fails the test. */
    private static final class LineOutputStream extends OutputStream {
        private final BlockingQueue<JsonNode> lines;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        LineOutputStream(BlockingQueue<JsonNode> lines) {
            this.lines = lines;
        }

        @Override
        public synchronized void write(int b) {
            if (b != '\n') {
                line.write(b);
                return;
            }
            var text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            lines.add(MAPPER.readTree(text));
        }
    }
}
