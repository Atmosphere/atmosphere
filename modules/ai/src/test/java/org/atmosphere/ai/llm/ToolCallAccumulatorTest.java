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
package org.atmosphere.ai.llm;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallAccumulatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testBasicAccumulation() {
        var acc = new ToolCallAccumulator();
        acc.setId("call_123");
        acc.setFunctionName("get_weather");
        acc.appendArguments("{\"city\":");
        acc.appendArguments("\"Montreal\"}");

        assertEquals("call_123", acc.id());
        assertEquals("get_weather", acc.functionName());
        assertEquals("{\"city\":\"Montreal\"}", acc.arguments());
    }

    @Test
    void testEmptyArguments() {
        var acc = new ToolCallAccumulator();
        acc.setId("call_456");
        acc.setFunctionName("no_args_tool");

        assertEquals("call_456", acc.id());
        assertEquals("no_args_tool", acc.functionName());
        assertEquals("", acc.arguments());
    }

    @Test
    void testMultipleArgumentChunks() {
        var acc = new ToolCallAccumulator();
        acc.appendArguments("{");
        acc.appendArguments("\"name\"");
        acc.appendArguments(":");
        acc.appendArguments("\"test\"");
        acc.appendArguments(",");
        acc.appendArguments("\"count\"");
        acc.appendArguments(":");
        acc.appendArguments("42");
        acc.appendArguments("}");

        assertEquals("{\"name\":\"test\",\"count\":42}", acc.arguments());
    }

    @Test
    void testOverwriteFields() {
        var acc = new ToolCallAccumulator();
        acc.setId("call_1");
        acc.setFunctionName("func_1");

        acc.setId("call_2");
        acc.setFunctionName("func_2");

        assertEquals("call_2", acc.id());
        assertEquals("func_2", acc.functionName());
    }

    @Test
    void testArgumentsAsMapValidJson() {
        var acc = new ToolCallAccumulator();
        acc.appendArguments("{\"city\":");
        acc.appendArguments("\"Montreal\",\"units\":\"metric\"}");

        Map<String, Object> parsed = acc.argumentsAsMap(MAPPER);
        assertEquals(2, parsed.size());
        assertEquals("Montreal", parsed.get("city"));
        assertEquals("metric", parsed.get("units"));
    }

    @Test
    void testArgumentsAsMapBlankIsEmpty() {
        var acc = new ToolCallAccumulator();
        // No argument fragments accumulated (a no-args tool call).
        assertEquals(Map.of(), acc.argumentsAsMap(MAPPER));

        var whitespace = new ToolCallAccumulator();
        whitespace.appendArguments("   ");
        assertEquals(Map.of(), whitespace.argumentsAsMap(MAPPER));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testArgumentsAsMapNestedObject() {
        var acc = new ToolCallAccumulator();
        acc.appendArguments("{\"filter\":{\"status\":\"open\",\"limit\":5},");
        acc.appendArguments("\"name\":\"search\"}");

        Map<String, Object> parsed = acc.argumentsAsMap(MAPPER);
        assertEquals("search", parsed.get("name"));
        // Nested JSON object parses to a nested Map.
        assertInstanceOf(Map.class, parsed.get("filter"));
        Map<String, Object> nested = (Map<String, Object>) parsed.get("filter");
        assertEquals("open", nested.get("status"));
        assertEquals(5, nested.get("limit"));
    }

    @Test
    void testArgumentsAsMapMalformedJsonFallsBackToRaw() {
        var acc = new ToolCallAccumulator();
        // Truncated / malformed JSON — never a valid object.
        acc.appendArguments("{\"city\":\"Montreal");

        Map<String, Object> parsed = acc.argumentsAsMap(MAPPER);
        assertEquals(Map.of("__raw", "{\"city\":\"Montreal"), parsed);
    }
}
