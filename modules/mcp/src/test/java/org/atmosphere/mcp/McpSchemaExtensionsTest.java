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
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpProtocolHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the wire-shape of MCP 2025-06-18 / 2025-11-25 schema extensions:
 * {@code title}, {@code icons}, {@code _meta} on tools/resources/prompts;
 * {@code structuredContent} on tools/call results.
 */
public class McpSchemaExtensionsTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static class TitledTool {
        @McpTool(name = "search",
                title = "Search the knowledge base",
                description = "FTS over indexed docs",
                iconUrl = "https://cdn.example/search.svg")
        public String search(@McpParam(name = "q") String q) {
            return "results for " + q;
        }

        @McpTool(name = "stats", description = "Returns server stats as JSON")
        public Map<String, Object> stats() {
            return Map.of("uptime", 12345L, "active", 7);
        }
    }

    private McpProtocolHandler handler;
    private AtmosphereResource resource;
    private McpRegistry registry;

    @BeforeEach
    public void setUp() {
        registry = new McpRegistry();
        registry.scan(new TitledTool());
        handler = new McpProtocolHandler("test", "1.0", registry, mock(AtmosphereConfig.class));
        resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("test-schema");
    }

    @Test
    public void toolsListIncludesTitleAndIcons() throws Exception {
        var response = handler.handleMessage(resource,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        var tools = mapper.readTree(response).get("result").get("tools");
        var search = findTool(tools, "search");
        assertNotNull(search, "search tool must be in list");
        assertEquals("Search the knowledge base", search.get("title").stringValue(),
                "title should mirror the @McpTool(title=...) value");
        var icons = search.get("icons");
        assertNotNull(icons, "icons array required");
        assertEquals(1, icons.size());
        assertEquals("https://cdn.example/search.svg",
                icons.get(0).get("src").stringValue());
    }

    @Test
    public void toolWithoutTitleOmitsField() throws Exception {
        var response = handler.handleMessage(resource,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        var tools = mapper.readTree(response).get("result").get("tools");
        var stats = findTool(tools, "stats");
        assertNotNull(stats);
        assertNull(stats.get("title"), "absent title must be omitted, not empty-string");
        assertNull(stats.get("icons"), "absent icon must be omitted");
    }

    @Test
    public void programmaticMetadataAttachable() throws Exception {
        // For tools registered via registerTool() (no annotations) the registry
        // lets you attach the same metadata sidecar after the fact.
        registry.setToolMetadata("stats", new McpRegistry.EntryMetadata(
                "Server Stats",
                "https://cdn.example/stats.svg",
                Map.of("category", "ops", "stable", true)));

        var response = handler.handleMessage(resource,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        var stats = findTool(mapper.readTree(response).get("result").get("tools"), "stats");
        assertEquals("Server Stats", stats.get("title").stringValue());
        assertEquals("https://cdn.example/stats.svg",
                stats.get("icons").get(0).get("src").stringValue());
        assertEquals("ops", stats.get("_meta").get("category").stringValue());
    }

    @Test
    public void structuredContentEmittedForTypedReturns() throws Exception {
        // 06-18 added structuredContent. A tool returning Map/List/typed value
        // should emit both the legacy text content (stringified JSON) AND the
        // structuredContent for clients that consume it directly.
        var response = handler.handleMessage(resource, """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"stats","arguments":{}
                }}""");
        var result = mapper.readTree(response).get("result");

        assertNotNull(result.get("structuredContent"),
                "tool returning typed object must surface structuredContent");
        assertEquals(12345, result.get("structuredContent").get("uptime").asInt());
        assertEquals(7, result.get("structuredContent").get("active").asInt());

        // Legacy text-content fallback retained for older clients
        var content = result.get("content");
        assertNotNull(content);
        assertEquals(1, content.size());
        var textBlock = content.get(0);
        assertEquals("text", textBlock.get("type").stringValue());
        assertTrue(textBlock.get("text").stringValue().contains("12345"));

        assertFalse(result.get("isError").asBoolean());
    }

    @Test
    public void plainStringReturnDoesNotEmitStructuredContent() throws Exception {
        var response = handler.handleMessage(resource, """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"search","arguments":{"q":"foo"}
                }}""");
        var result = mapper.readTree(response).get("result");
        assertNull(result.get("structuredContent"),
                "plain String returns should not populate structuredContent");
        assertEquals("results for foo",
                result.get("content").get(0).get("text").stringValue());
    }

    private static tools.jackson.databind.JsonNode findTool(tools.jackson.databind.JsonNode tools, String name) {
        for (var t : tools) {
            if (name.equals(t.get("name").stringValue())) {
                return t;
            }
        }
        return null;
    }

    @SuppressWarnings("unused") // referenced indirectly via registry types
    private static final List<?> KEEP_IMPORTS = List.of();
}
