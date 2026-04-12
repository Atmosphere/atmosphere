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
package org.atmosphere.ai.spring;

import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolParameter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SpringAiToolBridge}.
 */
public class SpringAiToolBridgeTest {

    @Test
    public void testBuildInputSchemaEmpty() {
        var schema = SpringAiToolBridge.buildInputSchema(List.of());
        assertEquals("{\"type\":\"object\",\"properties\":{},\"required\":[]}", schema);
    }

    @Test
    public void testBuildInputSchemaWithParams() {
        var params = List.of(
                new ToolParameter("city", "The city name", "string", true),
                new ToolParameter("units", "Temperature units", "string", false)
        );
        var schema = SpringAiToolBridge.buildInputSchema(params);

        assertTrue(schema.contains("\"city\""));
        assertTrue(schema.contains("\"The city name\""));
        assertTrue(schema.contains("\"required\":[\"city\"]"));
        assertFalse(schema.contains("\"units\"") && schema.contains("\"required\":[\"city\",\"units\"]"));
    }

    @Test
    public void testParseJsonArgsEmpty() {
        assertEquals(Map.of(), SpringAiToolBridge.parseJsonArgs(null));
        assertEquals(Map.of(), SpringAiToolBridge.parseJsonArgs(""));
        assertEquals(Map.of(), SpringAiToolBridge.parseJsonArgs("{}"));
    }

    @Test
    public void testParseJsonArgsStringValues() {
        var result = SpringAiToolBridge.parseJsonArgs("{\"name\":\"Alice\",\"city\":\"Paris\"}");
        assertEquals("Alice", result.get("name"));
        assertEquals("Paris", result.get("city"));
    }

    @Test
    public void testParseJsonArgsNumericValues() {
        var result = SpringAiToolBridge.parseJsonArgs("{\"count\":42,\"ratio\":3.14}");
        assertEquals(42L, result.get("count"));
        assertEquals(3.14, result.get("ratio"));
    }

    @Test
    public void testParseJsonArgsBooleanValues() {
        var result = SpringAiToolBridge.parseJsonArgs("{\"active\":true,\"deleted\":false}");
        assertEquals(true, result.get("active"));
        assertEquals(false, result.get("deleted"));
    }

    @Test
    public void testParseJsonArgsNullValues() {
        var result = SpringAiToolBridge.parseJsonArgs("{\"value\":null}");
        assertTrue(result.containsKey("value"));
        assertNull(result.get("value"));
    }

    @Test
    public void testToToolCallbacks() {
        var tools = List.of(
                ToolDefinition.builder("greet", "Say hello")
                        .parameter("name", "Name to greet", "string")
                        .executor(args -> "Hello " + args.get("name"))
                        .build()
        );

        var callbacks = SpringAiToolBridge.toToolCallbacks(tools, null, null, List.of(), null);
        assertEquals(1, callbacks.size());

        var callback = callbacks.get(0);
        assertEquals("greet", callback.getToolDefinition().name());
        assertEquals("Say hello", callback.getToolDefinition().description());

        // Execute the callback
        var result = callback.call("{\"name\":\"World\"}");
        assertEquals("Hello World", result);
    }

    @Test
    public void testToolCallbackHandlesException() {
        var tools = List.of(
                ToolDefinition.builder("fail", "Always fails")
                        .executor(args -> { throw new RuntimeException("boom"); })
                        .build()
        );

        var callbacks = SpringAiToolBridge.toToolCallbacks(tools, null, null, List.of(), null);
        var result = callbacks.get(0).call("{}");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("boom"));
    }
}
