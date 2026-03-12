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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ToolBridgeUtils}.
 */
public class ToolBridgeUtilsTest {

    // --- parseJsonArgs tests ---

    @Test
    public void testParseJsonArgsNull() {
        assertEquals(Map.of(), ToolBridgeUtils.parseJsonArgs(null));
    }

    @Test
    public void testParseJsonArgsBlank() {
        assertEquals(Map.of(), ToolBridgeUtils.parseJsonArgs(""));
        assertEquals(Map.of(), ToolBridgeUtils.parseJsonArgs("   "));
    }

    @Test
    public void testParseJsonArgsEmptyObject() {
        assertEquals(Map.of(), ToolBridgeUtils.parseJsonArgs("{}"));
        assertEquals(Map.of(), ToolBridgeUtils.parseJsonArgs("  {}  "));
    }

    @Test
    public void testParseJsonArgsStringValues() {
        var result = ToolBridgeUtils.parseJsonArgs("{\"name\":\"Alice\",\"city\":\"Paris\"}");
        assertEquals("Alice", result.get("name"));
        assertEquals("Paris", result.get("city"));
    }

    @Test
    public void testParseJsonArgsNumericValues() {
        var result = ToolBridgeUtils.parseJsonArgs("{\"count\":42,\"ratio\":3.14}");
        assertEquals(42L, result.get("count"));
        assertEquals(3.14, result.get("ratio"));
    }

    @Test
    public void testParseJsonArgsBooleanValues() {
        var result = ToolBridgeUtils.parseJsonArgs("{\"active\":true,\"deleted\":false}");
        assertEquals(true, result.get("active"));
        assertEquals(false, result.get("deleted"));
    }

    @Test
    public void testParseJsonArgsNullValues() {
        var result = ToolBridgeUtils.parseJsonArgs("{\"value\":null}");
        assertTrue(result.containsKey("value"));
        assertNull(result.get("value"));
    }

    @Test
    public void testParseJsonArgsEscapedStringValues() {
        var result = ToolBridgeUtils.parseJsonArgs("{\"msg\":\"He said \\\"hello\\\"\"}");
        assertEquals("He said \\\"hello\\\"", result.get("msg"));
    }

    @Test
    public void testParseJsonArgsMixedTypes() {
        var result = ToolBridgeUtils.parseJsonArgs(
                "{\"name\":\"Bob\",\"age\":30,\"active\":true,\"score\":9.5,\"tag\":null}");
        assertEquals("Bob", result.get("name"));
        assertEquals(30L, result.get("age"));
        assertEquals(true, result.get("active"));
        assertEquals(9.5, result.get("score"));
        assertNull(result.get("tag"));
    }

    // --- escapeJson tests ---

    @Test
    public void testEscapeJsonNull() {
        assertEquals("", ToolBridgeUtils.escapeJson(null));
    }

    @Test
    public void testEscapeJsonNoSpecialChars() {
        assertEquals("hello world", ToolBridgeUtils.escapeJson("hello world"));
    }

    @Test
    public void testEscapeJsonQuotes() {
        assertEquals("say \\\"hi\\\"", ToolBridgeUtils.escapeJson("say \"hi\""));
    }

    @Test
    public void testEscapeJsonBackslash() {
        assertEquals("path\\\\to\\\\file", ToolBridgeUtils.escapeJson("path\\to\\file"));
    }

    @Test
    public void testEscapeJsonNewlineAndTab() {
        assertEquals("line1\\nline2\\ttab", ToolBridgeUtils.escapeJson("line1\nline2\ttab"));
    }

    @Test
    public void testEscapeJsonCarriageReturn() {
        assertEquals("a\\rb", ToolBridgeUtils.escapeJson("a\rb"));
    }

    // --- buildJsonSchemaString tests ---

    @Test
    public void testBuildJsonSchemaStringEmpty() {
        var schema = ToolBridgeUtils.buildJsonSchemaString(List.of());
        assertEquals("{\"type\":\"object\",\"properties\":{},\"required\":[]}", schema);
    }

    @Test
    public void testBuildJsonSchemaStringWithParams() {
        var params = List.of(
                new ToolParameter("city", "The city name", "string", true),
                new ToolParameter("units", "Temperature units", "string", false)
        );
        var schema = ToolBridgeUtils.buildJsonSchemaString(params);

        assertTrue(schema.contains("\"city\""));
        assertTrue(schema.contains("\"The city name\""));
        assertTrue(schema.contains("\"required\":[\"city\"]"));
        // "units" should NOT be in the required array
        assertFalse(schema.contains("\"required\":[\"city\",\"units\"]"));
    }

    @Test
    public void testBuildJsonSchemaStringAllRequired() {
        var params = List.of(
                new ToolParameter("a", "Param A", "string", true),
                new ToolParameter("b", "Param B", "integer", true)
        );
        var schema = ToolBridgeUtils.buildJsonSchemaString(params);

        assertTrue(schema.contains("\"a\""));
        assertTrue(schema.contains("\"b\""));
        assertTrue(schema.contains("\"required\":[\"a\",\"b\"]"));
    }

    @Test
    public void testBuildJsonSchemaStringEscapesDescription() {
        var params = List.of(
                new ToolParameter("query", "The user's \"search\" query", "string", true)
        );
        var schema = ToolBridgeUtils.buildJsonSchemaString(params);

        // Escaped quotes in description
        assertTrue(schema.contains("\\\"search\\\""));
    }

    // --- findUnescapedQuote tests ---

    @Test
    public void testFindUnescapedQuoteSimple() {
        assertEquals(5, ToolBridgeUtils.findUnescapedQuote("hello\"world", 0));
    }

    @Test
    public void testFindUnescapedQuoteSkipsEscaped() {
        // "hello\"world" - the escaped quote at index 5-6 should be skipped
        assertEquals(8, ToolBridgeUtils.findUnescapedQuote("hello\\\"w\"orld", 0));
    }

    @Test
    public void testFindUnescapedQuoteNotFound() {
        assertEquals(5, ToolBridgeUtils.findUnescapedQuote("hello", 0));
    }
}
