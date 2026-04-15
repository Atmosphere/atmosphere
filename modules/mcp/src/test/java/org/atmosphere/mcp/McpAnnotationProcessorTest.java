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

import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpPrompt;
import org.atmosphere.mcp.annotation.McpResource;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.registry.McpRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for MCP annotation contracts ({@link McpTool}, {@link McpResource},
 * {@link McpPrompt}, {@link McpParam}) and their processing via
 * {@link McpRegistry#scan(Object)}.
 */
public class McpAnnotationProcessorTest {

    private McpRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new McpRegistry();
    }

    // ── @McpTool annotation contract ──

    @Test
    void mcpToolRetainedAtRuntime() {
        var retention = McpTool.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void mcpToolTargetsMethods() {
        var target = McpTool.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(new ElementType[]{ElementType.METHOD}, target.value());
    }

    @Test
    void mcpToolIsDocumented() {
        assertNotNull(McpTool.class.getAnnotation(Documented.class));
    }

    @Test
    void mcpToolDefaultDescriptionEmpty() throws Exception {
        var method = MinimalProvider.class.getMethod("minimalTool");
        var annotation = method.getAnnotation(McpTool.class);
        assertEquals("", annotation.description());
    }

    // ── @McpResource annotation contract ──

    @Test
    void mcpResourceRetainedAtRuntime() {
        var retention = McpResource.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void mcpResourceTargetsMethods() {
        var target = McpResource.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(new ElementType[]{ElementType.METHOD}, target.value());
    }

    @Test
    void mcpResourceIsDocumented() {
        assertNotNull(McpResource.class.getAnnotation(Documented.class));
    }

    @Test
    void mcpResourceDefaultMimeType() throws Exception {
        var method = MinimalProvider.class.getMethod("minimalResource");
        var annotation = method.getAnnotation(McpResource.class);
        assertEquals("text/plain", annotation.mimeType());
        assertEquals("", annotation.name());
        assertEquals("", annotation.description());
    }

    // ── @McpPrompt annotation contract ──

    @Test
    void mcpPromptRetainedAtRuntime() {
        var retention = McpPrompt.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void mcpPromptTargetsMethods() {
        var target = McpPrompt.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(new ElementType[]{ElementType.METHOD}, target.value());
    }

    @Test
    void mcpPromptIsDocumented() {
        assertNotNull(McpPrompt.class.getAnnotation(Documented.class));
    }

    @Test
    void mcpPromptDefaultDescriptionEmpty() throws Exception {
        var method = MinimalProvider.class.getMethod("minimalPrompt");
        var annotation = method.getAnnotation(McpPrompt.class);
        assertEquals("", annotation.description());
    }

    // ── @McpParam annotation contract ──

    @Test
    void mcpParamRetainedAtRuntime() {
        var retention = McpParam.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void mcpParamTargetsParameters() {
        var target = McpParam.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(new ElementType[]{ElementType.PARAMETER}, target.value());
    }

    @Test
    void mcpParamIsDocumented() {
        assertNotNull(McpParam.class.getAnnotation(Documented.class));
    }

    @Test
    void mcpParamDefaultValues() throws Exception {
        var method = FullProvider.class.getMethod("search", String.class);
        var param = method.getParameters()[0].getAnnotation(McpParam.class);
        assertNotNull(param);
        assertEquals("query", param.name());
        assertEquals("", param.description());
        assertTrue(param.required());
    }

    @Test
    void mcpParamCustomValues() throws Exception {
        var method = FullProvider.class.getMethod("searchWithLimit",
                String.class, int.class);
        var params = method.getParameters();

        var query = params[0].getAnnotation(McpParam.class);
        assertEquals("query", query.name());
        assertEquals("Search query", query.description());
        assertTrue(query.required());

        var limit = params[1].getAnnotation(McpParam.class);
        assertEquals("limit", limit.name());
        assertEquals("Max results", limit.description());
        assertFalse(limit.required());
    }

    // ── Annotation scanning via McpRegistry ──

    @Test
    void scanDiscoversTool() {
        registry.scan(new FullProvider());
        var tool = registry.tool("full_search");
        assertTrue(tool.isPresent());
        assertEquals("Search everything", tool.get().description());
    }

    @Test
    void scanDiscoversResource() {
        registry.scan(new FullProvider());
        var resource = registry.resource("atmosphere://config");
        assertTrue(resource.isPresent());
        assertEquals("Config", resource.get().name());
        assertEquals("application/json", resource.get().mimeType());
    }

    @Test
    void scanDiscoversPrompt() {
        registry.scan(new FullProvider());
        var prompt = registry.prompt("greet");
        assertTrue(prompt.isPresent());
        assertEquals("Greeting prompt", prompt.get().description());
    }

    @Test
    void scanDiscoversParamsOnTool() {
        registry.scan(new FullProvider());
        var tool = registry.tool("full_search").orElseThrow();
        assertEquals(1, tool.params().size());
        assertEquals("query", tool.params().get(0).name());
    }

    @Test
    void scanDiscoversMultipleAnnotationsOnSameProvider() {
        registry.scan(new FullProvider());

        assertEquals(2, registry.tools().size());
        assertEquals(1, registry.resources().size());
        assertEquals(1, registry.prompts().size());
    }

    @Test
    void scannedToolParamTypes() {
        registry.scan(new FullProvider());
        var tool = registry.tool("full_search_limited").orElseThrow();
        assertEquals(2, tool.params().size());

        var queryParam = tool.params().get(0);
        assertEquals(String.class, queryParam.type());
        assertTrue(queryParam.required());

        var limitParam = tool.params().get(1);
        assertEquals(int.class, limitParam.type());
        assertFalse(limitParam.required());
    }

    @Test
    void scannedToolIsNotDynamic() {
        registry.scan(new FullProvider());
        assertFalse(registry.tool("full_search").orElseThrow().isDynamic());
    }

    @Test
    void scanMultipleProviders() {
        registry.scan(new FullProvider());
        registry.scan(new MinimalProvider());

        assertEquals(3, registry.tools().size());
        assertEquals(2, registry.resources().size());
        assertEquals(2, registry.prompts().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputSchemaForScannedTool() {
        registry.scan(new FullProvider());
        var tool = registry.tool("full_search_limited").orElseThrow();

        var schema = McpRegistry.inputSchema(tool);
        assertEquals("object", schema.get("type"));

        var properties = (Map<String, Object>) schema.get("properties");
        assertEquals(2, properties.size());
        assertTrue(properties.containsKey("query"));
        assertTrue(properties.containsKey("limit"));

        var required = (List<String>) schema.get("required");
        assertTrue(required.contains("query"));
        assertFalse(required.contains("limit"));
    }

    // ---- Test fixture classes ----

    @SuppressWarnings("unused")
    public static class FullProvider {

        @McpTool(name = "full_search", description = "Search everything")
        public String search(@McpParam(name = "query") String query) {
            return "result for " + query;
        }

        @McpTool(name = "full_search_limited", description = "Search with limit")
        public String searchWithLimit(
                @McpParam(name = "query", description = "Search query") String query,
                @McpParam(name = "limit", description = "Max results",
                          required = false) int limit) {
            return "results";
        }

        @McpResource(uri = "atmosphere://config", name = "Config",
                      description = "Configuration resource",
                      mimeType = "application/json")
        public String readConfig() {
            return "{}";
        }

        @McpPrompt(name = "greet", description = "Greeting prompt")
        public List<Map<String, Object>> greet(
                @McpParam(name = "name", description = "Name") String name) {
            return List.of(Map.of("role", "user", "content",
                    Map.of("type", "text", "text", "Hello " + name)));
        }
    }

    @SuppressWarnings("unused")
    public static class MinimalProvider {
        @McpTool(name = "minimal_tool")
        public String minimalTool() {
            return "ok";
        }

        @McpResource(uri = "atmosphere://minimal")
        public String minimalResource() {
            return "data";
        }

        @McpPrompt(name = "minimal_prompt")
        public String minimalPrompt() {
            return "prompt";
        }
    }
}
