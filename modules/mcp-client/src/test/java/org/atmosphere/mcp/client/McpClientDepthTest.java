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

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the MCP client depth additions (P0.3): per-server tool
 * filtering/renaming and collision-free multi-server aggregation. The logic is
 * unit-tested without a live server; the wire round-trip lives in the e2e suite.
 */
class McpClientDepthTest {

    // --- McpClientOptions: filtering + renaming --------------------------

    @Test
    void filterAllowsOnlyNamedTools() {
        var opts = McpClientOptions.builder().includeTools(Set.of("search")).build();
        assertTrue(opts.includes("search"));
        assertFalse(opts.includes("delete_everything"));
    }

    @Test
    void prefixRenamesDisplayName() {
        var opts = McpClientOptions.builder().toolNamePrefix("weather_").build();
        assertEquals("weather_lookup", opts.displayName("lookup"));
    }

    @Test
    void nameMapperOverridesPrefix() {
        var opts = McpClientOptions.builder()
                .toolNamePrefix("ignored_")
                .nameMapper(n -> "wx__" + n)
                .build();
        assertEquals("wx__lookup", opts.displayName("lookup"));
    }

    @Test
    void defaultsAllowAllWithNoRename() {
        var opts = McpClientOptions.defaults();
        assertTrue(opts.includes("anything"));
        assertEquals("anything", opts.displayName("anything"));
    }

    // --- rename is display-only: executor calls the ORIGINAL name --------

    @Test
    void renamePreservesOriginalNameOnTheWire() throws Exception {
        var schema = new McpSchema.JsonSchema(
                "object", Map.of("q", Map.of("type", "string")),
                List.of("q"), Boolean.FALSE, null, null);
        var tool = new McpSchema.Tool("search", null, "Search the web", schema, null, null, null);

        var client = mock(McpSyncClient.class);
        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("hit")), Boolean.FALSE, null, null));

        var original = toDefinition(tool, client, "srv");
        var renamed = McpToolSource.rename(original, "web_search");

        assertEquals("web_search", renamed.name(), "model sees the renamed tool");
        renamed.executor().execute(Map.of("q", "atmosphere"));

        var captor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        org.mockito.Mockito.verify(client).callTool(captor.capture());
        assertEquals("search", captor.getValue().name(),
                "the executor must call the server's ORIGINAL tool name, not the alias");
    }

    @Test
    void renameIsNoOpWhenNameUnchanged() {
        var def = ToolDefinition.builder("x", "desc").executor(a -> "ok").build();
        assertEquals(def, McpToolSource.rename(def, "x"));
        assertEquals(def, McpToolSource.rename(def, null));
    }

    // --- McpServerRegistry: collision-free aggregation -------------------

    @Test
    void aggregateIsFirstWinsOnCollision() {
        var a = ToolDefinition.builder("search", "from A").executor(x -> "a").build();
        var b = ToolDefinition.builder("search", "from B").executor(x -> "b").build();
        var c = ToolDefinition.builder("calendar", "from B").executor(x -> "c").build();

        var merged = McpServerRegistry.aggregate(List.of(List.of(a), List.of(b, c)));

        assertEquals(2, merged.size(), "duplicate 'search' collapses to one");
        var search = merged.stream().filter(d -> d.name().equals("search")).findFirst().orElseThrow();
        assertEquals("from A", search.description(), "first source wins on collision");
        assertTrue(merged.stream().anyMatch(d -> d.name().equals("calendar")));
    }

    @Test
    void prefixedSourcesDoNotCollide() {
        var a = ToolDefinition.builder("weather_search", "A").executor(x -> "a").build();
        var b = ToolDefinition.builder("cal_search", "B").executor(x -> "b").build();

        var merged = McpServerRegistry.aggregate(List.of(List.of(a), List.of(b)));

        assertEquals(2, merged.size(), "prefixing avoids the collision entirely");
    }

    @Test
    void aggregatePreservesSourceOrder() {
        var a = ToolDefinition.builder("a", "A").executor(x -> "a").build();
        var b = ToolDefinition.builder("b", "B").executor(x -> "b").build();
        var merged = McpServerRegistry.aggregate(List.of(List.of(a), List.of(b)));
        assertEquals(List.of("a", "b"), merged.stream().map(ToolDefinition::name).toList());
    }

    private static ToolDefinition toDefinition(McpSchema.Tool tool, McpSyncClient client,
                                               String label) throws Exception {
        Method translate = McpToolSource.class.getDeclaredMethod(
                "toDefinition", McpSchema.Tool.class, McpSyncClient.class,
                String.class, McpToolMetrics.class);
        translate.setAccessible(true);
        return (ToolDefinition) translate.invoke(null, tool, client, label, new McpToolMetrics());
    }
}
