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
package org.atmosphere.mcp;

import tools.jackson.databind.ObjectMapper;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.mcp.runtime.McpAuthorization;
import org.atmosphere.mcp.runtime.McpProtectedResourceHandler;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the OAuth resource-server glue (MCP authorization spec; RFC 9728 /
 * RFC 6750): opt-in/default-deny configuration, the Protected Resource Metadata
 * document, the {@code WWW-Authenticate} challenge, and the metadata handler.
 */
public class McpAuthorizationTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static AtmosphereConfig configWith(String resource, String servers, String scopes) {
        var config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(McpAuthorization.RESOURCE)).thenReturn(resource);
        when(config.getInitParameter(McpAuthorization.AUTHORIZATION_SERVERS)).thenReturn(servers);
        when(config.getInitParameter(McpAuthorization.SCOPES)).thenReturn(scopes);
        return config;
    }

    @Test
    public void testDisabledByDefault() {
        // No config / no params → authorization is off (back-compat open endpoint).
        assertFalse(McpAuthorization.from(null).enabled());
        assertFalse(McpAuthorization.from(mock(AtmosphereConfig.class)).enabled());
        // Resource without an authorization server is not a valid config → disabled.
        assertFalse(McpAuthorization.from(configWith("https://mcp.example.com/mcp", null, null)).enabled());
    }

    @Test
    public void testEnabledMetadataShape() {
        var auth = McpAuthorization.from(
                configWith("https://mcp.example.com/mcp", "https://auth.example.com", null));
        assertTrue(auth.enabled());

        var prm = auth.protectedResourceMetadata();
        assertEquals("https://mcp.example.com/mcp", prm.get("resource"));
        assertEquals(java.util.List.of("https://auth.example.com"), prm.get("authorization_servers"));
        assertEquals(java.util.List.of("header"), prm.get("bearer_methods_supported"));
        // metadataUrl defaults to <resource>/.well-known/oauth-protected-resource
        assertEquals("https://mcp.example.com/mcp/.well-known/oauth-protected-resource", auth.metadataUrl());
    }

    @Test
    public void testWwwAuthenticateChallenge() {
        var auth = McpAuthorization.from(
                configWith("https://mcp.example.com/mcp", "https://auth.example.com", "mcp:read,mcp:write"));
        var challenge = auth.wwwAuthenticate();
        assertTrue(challenge.startsWith("Bearer resource_metadata=\""), challenge);
        assertTrue(challenge.contains("/.well-known/oauth-protected-resource"), challenge);
        // Scopes surface both in the challenge and in scopes_supported (RFC 6750 §3).
        assertTrue(challenge.contains("scope=\"mcp:read mcp:write\""), challenge);
        assertEquals(java.util.List.of("mcp:read", "mcp:write"),
                auth.protectedResourceMetadata().get("scopes_supported"));
    }

    @Test
    public void testProtectedResourceHandlerServesMetadata() throws Exception {
        var auth = McpAuthorization.from(
                configWith("https://mcp.example.com/mcp", "https://auth.example.com", null));
        var handler = new McpProtectedResourceHandler(auth);

        var output = new StringWriter();
        var resource = mock(AtmosphereResource.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.getResponse()).thenReturn(response);
        when(response.getWriter()).thenReturn(new PrintWriter(output));

        handler.onRequest(resource);

        var node = mapper.readTree(output.toString());
        assertEquals("https://mcp.example.com/mcp", node.get("resource").stringValue());
        assertEquals("https://auth.example.com",
                node.get("authorization_servers").get(0).stringValue());
    }
}
