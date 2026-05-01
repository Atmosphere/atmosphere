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

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpProtocolHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the version-negotiation behavior of the MCP initialize handshake
 * (per spec §lifecycle): the server echoes the client-requested revision
 * when it knows it, otherwise responds with its own latest. Also pins the
 * 2025-06-18 batch-rejection contract.
 */
public class McpVersionNegotiationTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private McpProtocolHandler handler;
    private AtmosphereResource resource;

    @BeforeEach
    public void setUp() {
        var registry = new McpRegistry();
        handler = new McpProtocolHandler("test-server", "1.0.0", registry, mock(AtmosphereConfig.class));
        resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("test-uuid-version");
        var attrs = new java.util.HashMap<String, Object>();
        org.mockito.Mockito.doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(request).setAttribute(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        when(request.getAttribute(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> attrs.get(inv.getArgument(0, String.class)));
    }

    @Test
    public void serverEchoesClientRequestedLatestVersion() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                    "protocolVersion":"2025-11-25",
                    "clientInfo":{"name":"newclient","version":"1.0"},
                    "capabilities":{}
                }}""";
        var response = handler.handleMessage(resource, request);
        var result = mapper.readTree(response).get("result");
        assertEquals("2025-11-25", result.get("protocolVersion").stringValue());
    }

    @Test
    public void serverEchoesClientRequestedOlderVersion() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                    "protocolVersion":"2024-11-05",
                    "clientInfo":{"name":"oldclient","version":"0.1"},
                    "capabilities":{}
                }}""";
        var response = handler.handleMessage(resource, request);
        var result = mapper.readTree(response).get("result");
        assertEquals("2024-11-05", result.get("protocolVersion").stringValue(),
                "server must honor older client revisions when within SUPPORTED_VERSIONS");
    }

    @Test
    public void serverFallsBackToLatestForUnknownClientVersion() throws Exception {
        var request = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                    "protocolVersion":"9999-99-99",
                    "clientInfo":{"name":"timetraveler","version":"1.0"},
                    "capabilities":{}
                }}""";
        var response = handler.handleMessage(resource, request);
        var result = mapper.readTree(response).get("result");
        assertEquals(McpProtocolHandler.PROTOCOL_VERSION,
                result.get("protocolVersion").stringValue(),
                "unknown client revision should fall back to server's latest");
    }

    @Test
    public void supportedVersionsListIsOrderedNewestFirst() {
        var versions = McpProtocolHandler.SUPPORTED_VERSIONS;
        assertFalse(versions.isEmpty());
        assertEquals(McpProtocolHandler.PROTOCOL_VERSION, versions.get(0),
                "PROTOCOL_VERSION must be the head of SUPPORTED_VERSIONS");
        assertTrue(versions.contains("2025-11-25"));
        assertTrue(versions.contains("2025-06-18"));
        assertTrue(versions.contains("2025-03-26"));
    }

    @Test
    public void rejectsBatchRequest() throws Exception {
        // MCP 2025-06-18 dropped JSON-RPC batching. Server MUST return
        // INVALID_REQUEST for array-shaped requests rather than fan out.
        var batch = """
                [{"jsonrpc":"2.0","id":1,"method":"ping"},
                 {"jsonrpc":"2.0","id":2,"method":"ping"}]""";
        var response = handler.handleMessage(resource, batch);
        assertNotNull(response);
        var node = mapper.readTree(response);
        assertNotNull(node.get("error"), "batch must produce an error envelope");
        assertEquals(-32600, node.get("error").get("code").asInt());
        assertTrue(node.get("error").get("message").stringValue().contains("batching"));
    }
}
