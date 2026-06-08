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

import tools.jackson.databind.ObjectMapper;
import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for the stateless MCP {@code 2026-07-28} dialect
 * over Streamable HTTP against a live embedded server.
 *
 * <p>These prove the headline statelessness claim — "runs behind a plain
 * round-robin load balancer with no session affinity" — at the transport level,
 * not just in unit tests: a {@code tools/call} carrying the protocol version in
 * {@code params._meta} succeeds with <b>no</b> {@code Mcp-Session-Id} (no session
 * is created), and two independent calls with no session header both succeed —
 * i.e. either call could have landed on any backend instance.</p>
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class McpStatelessHttpIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A stateless {@code tools/call} for {@code echo}; carries the 2026-07-28
     *  protocol version + clientInfo in {@code params._meta} and no session. */
    private static String echoCall(int id, String text) {
        return """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{
                  "name":"echo","arguments":{"text":"%s"},
                  "_meta":{
                    "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                    "io.modelcontextprotocol/clientInfo":{"name":"stateless-client","version":"1.0.0"}
                  }
                }}""".formatted(id, text);
    }

    private EmbeddedAtmosphereServer server;
    private HttpClient httpClient;

    @SuppressWarnings("resource") // closed in tearDown()
    @BeforeAll
    public void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.mcp")
                .withInitParam("org.atmosphere.annotation.packages", "org.atmosphere.agent.processor");
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
    public void testStatelessToolsCallCreatesNoSession() throws Exception {
        var response = post(echoCall(1, "hello stateless"));

        assertEquals(200, response.statusCode());
        // The defining stateless property: no session is minted.
        assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty(),
                "Stateless 2026-07-28 tools/call must NOT create an Mcp-Session-Id");

        var body = MAPPER.readTree(response.body());
        var result = body.get("result");
        assertNotNull(result, "Stateless tools/call must return a result");
        assertFalse(result.get("isError").asBoolean());
        assertEquals("Echo: hello stateless",
                result.get("content").get(0).get("text").stringValue());
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testStatelessRoundRobinTwoIndependentCalls() throws Exception {
        // No initialize, no session header on EITHER call — simulating two
        // requests landing on different round-robin backends. Both must succeed
        // independently, which is only possible with no session affinity.
        var first = post(echoCall(1, "first"));
        var second = post(echoCall(2, "second"));

        assertEquals(200, first.statusCode());
        assertEquals(200, second.statusCode());
        assertTrue(first.headers().firstValue("Mcp-Session-Id").isEmpty());
        assertTrue(second.headers().firstValue("Mcp-Session-Id").isEmpty());

        assertEquals("Echo: first",
                MAPPER.readTree(first.body()).get("result").get("content").get(0).get("text").stringValue());
        assertEquals("Echo: second",
                MAPPER.readTree(second.body()).get("result").get("content").get(0).get("text").stringValue());
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testServerDiscoverReturnsCapabilitiesWithoutSession() throws Exception {
        // server/discover is the stateless entry point — reachable before the
        // client has pinned a version, with no session.
        var response = post("""
                {"jsonrpc":"2.0","id":1,"method":"server/discover","params":{}}""");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty(),
                "server/discover must not create a session");
        var body = MAPPER.readTree(response.body());
        assertNotNull(body.get("result"), "server/discover must return a result");
        assertTrue(body.get("result").has("capabilities"),
                "server/discover result must advertise capabilities");
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testMcpMethodHeaderMismatchRejected() throws Exception {
        // SEP-2243: when the routing header is present it must agree with the
        // body method, so a load balancer can dispatch on headers alone.
        var request = HttpRequest.newBuilder()
                .uri(URI.create(server.getBaseUrl() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Mcp-Method", "resources/read") // disagrees with tools/call
                .POST(HttpRequest.BodyPublishers.ofString(echoCall(1, "x")))
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode(),
                "Mcp-Method header disagreeing with the body method must be rejected");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** POST a JSON-RPC body with no {@code Mcp-Session-Id} header. */
    private HttpResponse<String> post(String json) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(server.getBaseUrl() + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
