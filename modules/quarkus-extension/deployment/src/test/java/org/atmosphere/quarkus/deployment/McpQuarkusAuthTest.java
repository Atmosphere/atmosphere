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
package org.atmosphere.quarkus.deployment;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import io.quarkus.maven.dependency.ArtifactDependency;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@code @Agent}-based MCP — and its OAuth resource-server authorization —
 * register and serve on <b>Quarkus</b>. This is the first test exercising an MCP
 * {@code @Agent} end-to-end on the Quarkus container; it would fail without the
 * {@code @Agent} entry in {@code AtmosphereProcessor}'s annotation scan and the
 * indexing of the optional {@code atmosphere-agent}/{@code atmosphere-mcp} jars.
 *
 * <p>{@code atmosphere-agent} (+ {@code atmosphere-mcp}) is forced onto the
 * synthetic app; {@link EchoMcpAgent} registers the MCP endpoint and the
 * {@code org.atmosphere.mcp.auth.*} init-parameters turn on the resource server
 * validated by {@link QuarkusDemoValidator}. A token-less {@code tools/call} is
 * challenged with {@code 401} + {@code WWW-Authenticate}; a validly-bearered call
 * succeeds.</p>
 */
public class McpQuarkusAuthTest {

    private static List<Dependency> mcpDeps() {
        var version = System.getProperty("atmosphere.project.version");
        // agent pulls atmosphere-mcp only as an optional dep, so force both.
        return List.of(
                new ArtifactDependency("org.atmosphere", "atmosphere-agent", null, "jar", version),
                new ArtifactDependency("org.atmosphere", "atmosphere-mcp", null, "jar", version));
    }

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .setForcedDependencies(mcpDeps())
            .withApplicationRoot(jar -> jar.addClasses(
                    McpQuarkusAuthTest.class, EchoMcpAgent.class, QuarkusDemoValidator.class))
            .overrideConfigKey("quarkus.atmosphere.packages", "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.atmosphere.init-params.\"org.atmosphere.mcp.auth.resource\"",
                    "https://api.example.com/mcp")
            .overrideConfigKey("quarkus.atmosphere.init-params.\"org.atmosphere.mcp.auth.authorizationServers\"",
                    "https://auth.example.com")
            .overrideConfigKey("quarkus.atmosphere.init-params.\"org.atmosphere.auth.tokenValidator\"",
                    "org.atmosphere.quarkus.deployment.QuarkusDemoValidator");

    private static final String TOOLS_CALL = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
              "name":"echo","arguments":{"text":"hi"}}}""";

    @TestHTTPResource("/atmosphere/mcp")
    URL mcpUrl;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    public void tokenlessRequestIsChallengedOnQuarkus() throws Exception {
        var response = call(null);
        assertEquals(401, response.statusCode(),
                "Quarkus must challenge an unauthenticated MCP tools/call");
        var challenge = response.headers().firstValue("WWW-Authenticate").orElse(null);
        assertNotNull(challenge, "401 must carry WWW-Authenticate (RFC 9728)");
        assertTrue(challenge.contains("resource_metadata="),
                "challenge must point at the PRM: " + challenge);
    }

    @Test
    public void validTokenIsAcceptedOnQuarkus() throws Exception {
        var response = call("Bearer " + QuarkusDemoValidator.GOOD_TOKEN);
        assertEquals(200, response.statusCode(), "a valid bearer token must be accepted on Quarkus");
        assertTrue(response.body().contains("Echo: hi"),
                "the echo tool must execute once authenticated: " + response.body());
    }

    private HttpResponse<String> call(String authorization) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl.toString()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(TOOLS_CALL));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
