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
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpPrompt;
import org.atmosphere.mcp.annotation.McpResource;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.registry.McpRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRegistryTest {

    private McpRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new McpRegistry();
    }

    @Test
    void emptyRegistryHasNoTools() {
        assertTrue(registry.tools().isEmpty());
    }

    @Test
    void emptyRegistryHasNoResources() {
        assertTrue(registry.resources().isEmpty());
    }

    @Test
    void emptyRegistryHasNoPrompts() {
        assertTrue(registry.prompts().isEmpty());
    }

    @Test
    void registerAndLookupTool() {
        registry.registerTool("echo", "Echo tool",
                List.of(new McpRegistry.ParamEntry("message", "The message", true, String.class)),
                args -> "echoed: " + args.get("message"));

        var tool = registry.tool("echo");
        assertTrue(tool.isPresent());
        assertEquals("echo", tool.get().name());
        assertEquals("Echo tool", tool.get().description());
        assertEquals(1, tool.get().params().size());
        assertTrue(tool.get().isDynamic());
    }

    @Test
    void registeredToolAppearsInToolsList() {
        registry.registerTool("t1", "Tool 1", args -> "ok");
        registry.registerTool("t2", "Tool 2", args -> "ok");

        assertEquals(2, registry.tools().size());
    }

    @Test
    void removeToolRemovesIt() {
        registry.registerTool("temp", "Temp", args -> "ok");
        assertTrue(registry.tool("temp").isPresent());

        assertTrue(registry.removeTool("temp"));
        assertTrue(registry.tool("temp").isEmpty());
    }

    @Test
    void removeNonexistentToolReturnsFalse() {
        assertFalse(registry.removeTool("nonexistent"));
    }

    @Test
    void registerAndLookupResource() {
        registry.registerResource("file:///data.txt", "data", "Data file",
                "text/plain", args -> "content");

        var resource = registry.resource("file:///data.txt");
        assertTrue(resource.isPresent());
        assertEquals("data", resource.get().name());
        assertEquals("text/plain", resource.get().mimeType());
        assertTrue(resource.get().isDynamic());
    }

    @Test
    void removeResourceRemovesIt() {
        registry.registerResource("file:///tmp", "tmp", "Tmp", "text/plain", args -> "ok");
        assertTrue(registry.resource("file:///tmp").isPresent());

        assertTrue(registry.removeResource("file:///tmp"));
        assertTrue(registry.resource("file:///tmp").isEmpty());
    }

    @Test
    void removeNonexistentResourceReturnsFalse() {
        assertFalse(registry.removeResource("file:///missing"));
    }

    @Test
    void registerAndLookupPrompt() {
        registry.registerPrompt("greeting", "Greeting prompt",
                List.of(new McpRegistry.ParamEntry("name", "Name", true, String.class)),
                args -> List.of(Map.of("role", "user", "content",
                        Map.of("type", "text", "text", "Hello " + args.get("name")))));

        var prompt = registry.prompt("greeting");
        assertTrue(prompt.isPresent());
        assertEquals("greeting", prompt.get().name());
        assertTrue(prompt.get().isDynamic());
    }

    @Test
    void removePromptRemovesIt() {
        registry.registerPrompt("tmp", "Tmp", args -> List.of());
        assertTrue(registry.prompt("tmp").isPresent());

        assertTrue(registry.removePrompt("tmp"));
        assertTrue(registry.prompt("tmp").isEmpty());
    }

    @Test
    void removeNonexistentPromptReturnsFalse() {
        assertFalse(registry.removePrompt("nonexistent"));
    }

    @Test
    void toolLookupReturnsEmptyForUnknown() {
        assertTrue(registry.tool("nonexistent").isEmpty());
    }

    @Test
    void resourceLookupReturnsEmptyForUnknown() {
        assertTrue(registry.resource("unknown://uri").isEmpty());
    }

    @Test
    void promptLookupReturnsEmptyForUnknown() {
        assertTrue(registry.prompt("nonexistent").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputSchemaGeneratesCorrectStructure() {
        var params = List.of(
                new McpRegistry.ParamEntry("name", "User name", true, String.class),
                new McpRegistry.ParamEntry("age", "User age", false, Integer.class)
        );
        registry.registerTool("test", "Test", params, args -> "ok");
        var tool = registry.tool("test").orElseThrow();

        var schema = McpRegistry.inputSchema(tool);
        assertEquals("object", schema.get("type"));

        var properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("name"));
        assertTrue(properties.containsKey("age"));

        var required = (List<String>) schema.get("required");
        assertTrue(required.contains("name"));
        assertFalse(required.contains("age"));
    }

    @Test
    void scanDiscoverAnnotatedMethods() {
        var handler = new AnnotatedHandler();
        registry.scan(handler);

        assertTrue(registry.tool("greet").isPresent());
        assertTrue(registry.resource("file:///hello").isPresent());
        assertTrue(registry.prompt("askName").isPresent());
    }

    @Test
    void scannedToolIsNotDynamic() {
        registry.scan(new AnnotatedHandler());
        assertFalse(registry.tool("greet").orElseThrow().isDynamic());
    }

    @Test
    void isInjectableTypeRecognizesFrameworkTypes() {
        assertTrue(McpRegistry.isInjectableType(AtmosphereConfig.class));
        assertTrue(McpRegistry.isInjectableType(Broadcaster.class));
        assertTrue(McpRegistry.isInjectableType(BroadcasterFactory.class));
        assertTrue(McpRegistry.isInjectableType(AtmosphereFramework.class));
        assertFalse(McpRegistry.isInjectableType(AtmosphereResource.class));
        assertFalse(McpRegistry.isInjectableType(String.class));
        assertFalse(McpRegistry.isInjectableType(Integer.class));
    }

    @Test
    void paramEntryRecord() {
        var param = new McpRegistry.ParamEntry("x", "X value", true, Double.class);
        assertEquals("x", param.name());
        assertEquals("X value", param.description());
        assertTrue(param.required());
        assertEquals(Double.class, param.type());
    }

    @Test
    void registerToolWithNoParams() {
        registry.registerTool("simple", "Simple tool", args -> "done");
        var tool = registry.tool("simple").orElseThrow();
        assertTrue(tool.params().isEmpty());
    }

    @Test
    void registerPromptWithNoParams() {
        registry.registerPrompt("basic", "Basic prompt", args -> List.of());
        var prompt = registry.prompt("basic").orElseThrow();
        assertTrue(prompt.params().isEmpty());
    }

    // Inner class with MCP annotations for scan() testing
    @SuppressWarnings("unused")
    static class AnnotatedHandler {

        @McpTool(name = "greet", description = "Greet someone")
        public String greet(@McpParam(name = "name", description = "Name") String name) {
            return "Hello " + name;
        }

        @McpResource(uri = "file:///hello", name = "hello", description = "Hello resource")
        public String readHello() {
            return "hello content";
        }

        @McpPrompt(name = "askName", description = "Ask for a name")
        public List<Map<String, Object>> askName() {
            return List.of(Map.of("role", "user", "content",
                    Map.of("type", "text", "text", "What is your name?")));
        }
    }
}
