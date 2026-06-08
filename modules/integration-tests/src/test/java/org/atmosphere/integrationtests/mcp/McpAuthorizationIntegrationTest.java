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
 * End-to-end test of MCP OAuth resource-server authorization against a live
 * embedded server. Proves the full RFC 9728 flow with a real (if trivial)
 * {@link FixedTokenValidator} wired via the {@code org.atmosphere.auth.tokenValidator}
 * init-parameter — the same production loading path:
 *
 * <ul>
 *   <li>no token → {@code 401} + a {@code WWW-Authenticate} pointing at the PRM,</li>
 *   <li>an invalid bearer token → {@code 401},</li>
 *   <li>a valid bearer token → {@code 200} and the tool executes,</li>
 *   <li>the Protected Resource Metadata is served at the well-known path.</li>
 * </ul>
 *
 * <p>This is the test that was missing when 4.0.51 advertised "OAuth resource
 * server" — it proves a token is actually validated end-to-end.</p>
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class McpAuthorizationIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TOOLS_CALL = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
              "name":"echo","arguments":{"text":"hi"}}}""";

    private EmbeddedAtmosphereServer server;
    private HttpClient httpClient;

    @SuppressWarnings("resource") // closed in tearDown()
    @BeforeAll
    public void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.mcp")
                .withInitParam("org.atmosphere.annotation.packages", "org.atmosphere.agent.processor")
                // Enable MCP OAuth resource-server authorization (RFC 9728) ...
                .withInitParam("org.atmosphere.mcp.auth.resource", "https://api.example.com/mcp")
                .withInitParam("org.atmosphere.mcp.auth.authorizationServers", "https://auth.example.com")
                .withInitParam("org.atmosphere.mcp.auth.scopes", "mcp:tools")
                // ... validated locally by a TokenValidator (no servlet security filter).
                .withInitParam("org.atmosphere.auth.tokenValidator",
                        "org.atmosphere.integrationtests.mcp.FixedTokenValidator");
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
    public void testNoTokenIsChallengedWith401AndWwwAuthenticate() throws Exception {
        var response = post(TOOLS_CALL, null);

        assertEquals(401, response.statusCode(), "Unauthenticated tools/call must be 401");
        var challenge = response.headers().firstValue("WWW-Authenticate").orElse(null);
        assertNotNull(challenge, "401 must carry a WWW-Authenticate challenge (RFC 9728)");
        assertTrue(challenge.contains("resource_metadata="),
                "Challenge must point the client at the Protected Resource Metadata: " + challenge);
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testInvalidTokenIsRejectedWith401() throws Exception {
        var response = post(TOOLS_CALL, "Bearer not-a-real-token");
        assertEquals(401, response.statusCode(), "An invalid bearer token must be rejected with 401");
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testValidTokenIsAcceptedAndToolExecutes() throws Exception {
        var response = post(TOOLS_CALL, "Bearer " + FixedTokenValidator.GOOD_TOKEN);

        assertEquals(200, response.statusCode(), "A valid bearer token must be accepted");
        var body = MAPPER.readTree(response.body());
        var result = body.get("result");
        assertNotNull(result, "Authenticated tools/call must return a result");
        assertFalse(result.get("isError").asBoolean());
        assertEquals("Echo: hi", result.get("content").get(0).get("text").stringValue());
    }

    @Timeout(value = 10_000, unit = TimeUnit.MILLISECONDS)
    @Test
    public void testProtectedResourceMetadataIsServed() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(server.getBaseUrl() + "/.well-known/oauth-protected-resource"))
                .GET()
                .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "PRM must be served at the well-known path");
        var prm = MAPPER.readTree(response.body());
        assertEquals("https://api.example.com/mcp", prm.get("resource").stringValue());
        assertTrue(prm.get("authorization_servers").isArray()
                && !prm.get("authorization_servers").isEmpty(),
                "PRM must advertise at least one authorization server");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private HttpResponse<String> post(String json, String authorization) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(server.getBaseUrl() + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
