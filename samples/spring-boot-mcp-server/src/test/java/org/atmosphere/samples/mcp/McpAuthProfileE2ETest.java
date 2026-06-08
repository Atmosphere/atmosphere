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
package org.atmosphere.samples.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the sample under the {@code auth} profile (which enables the MCP OAuth
 * resource server with the {@link DemoHmacTokenValidator}) on a real port and
 * proves the end-to-end flow over HTTP: a token-less {@code tools/call} is
 * challenged with {@code 401} + {@code WWW-Authenticate}, and a request bearing
 * a validly-signed token succeeds. This is the running-sample proof that the
 * "OAuth resource server" claim is true, not just advertised.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "atmosphere.admin.enabled=false" })
@ActiveProfiles("auth")
class McpAuthProfileE2ETest {

    private static final String TOOLS_CALL = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
              "name":"atmosphere_version","arguments":{}}}""";

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void tokenlessRequestIsChallenged() throws Exception {
        var response = call(null);
        assertEquals(401, response.statusCode(), "auth profile must challenge an unauthenticated tools/call");
        var challenge = response.headers().firstValue("WWW-Authenticate").orElse(null);
        assertNotNull(challenge, "401 must carry WWW-Authenticate (RFC 9728)");
        assertTrue(challenge.contains("resource_metadata="), "challenge must point at the PRM: " + challenge);
    }

    @Test
    void validlySignedTokenIsAccepted() throws Exception {
        var response = call("Bearer " + DemoHmacTokenValidator.mint("alice"));
        assertEquals(200, response.statusCode(), "a validly-signed bearer token must be accepted");
        assertTrue(response.body().contains("\"result\""), "expected a JSON-RPC result: " + response.body());
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        // Right subject, wrong signature → must not authenticate.
        var response = call("Bearer alice.not-the-real-signature");
        assertEquals(401, response.statusCode(), "a token with an invalid signature must be rejected");
    }

    private HttpResponse<String> call(String authorization) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/atmosphere/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(TOOLS_CALL));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
