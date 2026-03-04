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
package org.atmosphere.ai.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ToolDefinition} and {@link ToolParameter}.
 */
public class ToolDefinitionTest {

    @Test
    public void testBuilderCreatesDefinition() {
        var tool = ToolDefinition.builder("search", "Search the web")
                .parameter("query", "Search query", "string")
                .parameter("limit", "Max results", "integer", false)
                .returnType("object")
                .executor(args -> "results")
                .build();

        assertEquals("search", tool.name());
        assertEquals("Search the web", tool.description());
        assertEquals(2, tool.parameters().size());
        assertEquals("object", tool.returnType());
    }

    @Test
    public void testParametersAreImmutable() {
        var tool = ToolDefinition.builder("test", "Test tool")
                .parameter("p1", "Param 1", "string")
                .executor(args -> null)
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> tool.parameters().add(new ToolParameter("p2", "extra", "string", true)));
    }

    @Test
    public void testNullNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDefinition(null, "desc", List.of(), "string", args -> null));
    }

    @Test
    public void testBlankNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDefinition("  ", "desc", List.of(), "string", args -> null));
    }

    @Test
    public void testNullDescriptionThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDefinition("tool", null, List.of(), "string", args -> null));
    }

    @Test
    public void testBuilderWithoutExecutorThrows() {
        var builder = ToolDefinition.builder("tool", "desc");
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    public void testDefaultReturnType() {
        var tool = ToolDefinition.builder("tool", "desc")
                .executor(args -> null)
                .build();
        assertEquals("string", tool.returnType());
    }

    @Test
    public void testToolParameterJsonSchemaTypes() {
        assertEquals("string", ToolParameter.jsonSchemaType(String.class));
        assertEquals("integer", ToolParameter.jsonSchemaType(int.class));
        assertEquals("integer", ToolParameter.jsonSchemaType(Integer.class));
        assertEquals("integer", ToolParameter.jsonSchemaType(long.class));
        assertEquals("integer", ToolParameter.jsonSchemaType(Long.class));
        assertEquals("number", ToolParameter.jsonSchemaType(double.class));
        assertEquals("number", ToolParameter.jsonSchemaType(Double.class));
        assertEquals("number", ToolParameter.jsonSchemaType(float.class));
        assertEquals("number", ToolParameter.jsonSchemaType(Float.class));
        assertEquals("boolean", ToolParameter.jsonSchemaType(boolean.class));
        assertEquals("boolean", ToolParameter.jsonSchemaType(Boolean.class));
        assertEquals("object", ToolParameter.jsonSchemaType(Object.class));
    }

    @Test
    public void testToolResultSuccess() {
        var result = ToolResult.success("my_tool", "42");
        assertTrue(result.success());
        assertEquals("my_tool", result.toolName());
        assertEquals("42", result.result());
        assertNull(result.error());
    }

    @Test
    public void testToolResultFailure() {
        var result = ToolResult.failure("my_tool", "something broke");
        assertFalse(result.success());
        assertEquals("my_tool", result.toolName());
        assertNull(result.result());
        assertEquals("something broke", result.error());
    }
}
