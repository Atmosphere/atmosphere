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
package org.atmosphere.mcp.client;

import com.sun.net.httpserver.HttpServer;
import org.atmosphere.ai.extensibility.McpTrustProvider;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (registre#24): {@code McpTrustProvider} was an SPI no MCP
 * invocation path consulted — the client had no per-user credential seam.
 * {@link McpToolSource#connectForUser} now resolves through the provider,
 * refuses to connect without a credential (fail closed, Invariant #6), and
 * sends the resolved credential as a Bearer header on the wire.
 */
class McpConnectForUserTest {

    private static McpTrustProvider provider(String credential) {
        return new McpTrustProvider() {
            @Override
            public Optional<String> resolve(String userId, String mcpServerId) {
                return Optional.ofNullable(credential);
            }

            @Override
            public String name() {
                return "test";
            }
        };
    }

    @Test
    void missingCredentialRefusesToConnectBeforeAnyNetworkIo() {
        // Unroutable endpoint: reaching the network would surface a connect
        // failure, not the IllegalStateException asserted here.
        var endpoint = URI.create("http://127.0.0.1:1/mcp");

        var e = assertThrows(IllegalStateException.class, () ->
                McpToolSource.connectForUser(endpoint, "alice", "github",
                        provider(null), McpClientOptions.defaults()));

        assertTrue(e.getMessage().contains("has not authorized"), e.getMessage());
        assertTrue(e.getMessage().contains("github"), e.getMessage());
    }

    @Test
    void trustProviderNoneResolvesNothingSoConnectFailsClosed() {
        var endpoint = URI.create("http://127.0.0.1:1/mcp");

        assertThrows(IllegalStateException.class, () ->
                McpToolSource.connectForUser(endpoint, "alice", "github",
                        McpTrustProvider.NONE, McpClientOptions.defaults()));
    }

    @Test
    void resolvedCredentialArrivesAsABearerHeaderOnTheWire() throws Exception {
        var seenAuth = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            var auth = exchange.getRequestHeaders().getFirst("Authorization");
            seenAuth.add(auth == null ? "<absent>" : auth);
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            var endpoint = URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/mcp");

            // The server rejects the handshake, so connect fails — but the
            // request it captured must already carry the user's credential.
            assertThrows(RuntimeException.class, () ->
                    McpToolSource.connectForUser(endpoint, "alice", "github",
                            provider("tok-123"), McpClientOptions.defaults()));

            assertTrue(!seenAuth.isEmpty(), "the client must have reached the server");
            assertEquals("Bearer tok-123", seenAuth.get(0),
                    "the resolved credential must ride the Authorization header");
        } finally {
            server.stop(0);
        }
    }
}
