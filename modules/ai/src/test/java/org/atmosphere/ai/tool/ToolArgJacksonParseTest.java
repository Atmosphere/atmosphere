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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Jackson-backed decoding of model-supplied tool arguments.
 *
 * <p>The previous hand-rolled tokenizer left {@code \n} / {@code \"} /
 * {@code \\uXXXX} escapes undecoded in string values and handed nested objects
 * and arrays downstream as raw text spans, so a tool receiving an escaped or
 * structured argument silently got corrupted data on the Built-in, LangChain4j,
 * Spring AI, and Alibaba paths (Correctness Invariant #4).</p>
 */
class ToolArgJacksonParseTest {

    @Test
    void decodesStandardStringEscapes() {
        var args = ToolBridgeUtils.parseJsonArgs(
                "{\"msg\":\"line1\\nline2\\ttab \\\"quoted\\\" back\\\\slash\"}");
        assertEquals("line1\nline2\ttab \"quoted\" back\\slash", args.get("msg"),
                "escape sequences must be decoded, not passed through literally");
    }

    @Test
    void decodesUnicodeEscapes() {
        var args = ToolBridgeUtils.parseJsonArgs(
                "{\"city\":\"Z\\u00fcrich\",\"emoji\":\"\\ud83d\\ude80\"}");
        assertEquals("Zürich", args.get("city"));
        assertEquals("🚀", args.get("emoji"));
    }

    @Test
    void nestedObjectBecomesAMapNotARawSpan() {
        var args = ToolBridgeUtils.parseJsonArgs(
                "{\"filter\":{\"key\":\"value\",\"n\":42,\"deep\":{\"flag\":true}}}");
        var filter = assertInstanceOf(Map.class, args.get("filter"),
                "a nested object must decode to a Map, not a raw JSON string");
        assertEquals("value", filter.get("key"));
        assertEquals(42L, filter.get("n"));
        var deep = assertInstanceOf(Map.class, filter.get("deep"));
        assertEquals(true, deep.get("flag"));
    }

    @Test
    void nestedArrayBecomesAListNotARawSpan() {
        var args = ToolBridgeUtils.parseJsonArgs("{\"items\":[1,2,3],\"names\":[\"a\",\"b\"]}");
        assertEquals(List.of(1L, 2L, 3L), args.get("items"));
        assertEquals(List.of("a", "b"), args.get("names"));
    }

    @Test
    void arrayOfObjectsDecodesElementwise() {
        var args = ToolBridgeUtils.parseJsonArgs("{\"rows\":[{\"a\":1},{\"a\":2}],\"count\":2}");
        var rows = assertInstanceOf(List.class, args.get("rows"));
        assertEquals(2, rows.size());
        assertEquals(1L, assertInstanceOf(Map.class, rows.get(0)).get("a"));
        assertEquals(2L, assertInstanceOf(Map.class, rows.get(1)).get("a"));
        assertEquals(2L, args.get("count"));
    }

    @Test
    void exponentAndNegativeNumbersParse() {
        var args = ToolBridgeUtils.parseJsonArgs(
                "{\"big\":1.5e3,\"small\":-2.5E-2,\"neg\":-7,\"zero\":0}");
        assertEquals(1500.0, args.get("big"));
        assertEquals(-0.025, args.get("small"));
        assertEquals(-7L, args.get("neg"));
        assertEquals(0L, args.get("zero"));
    }

    @Test
    void simpleScalarsKeepTheirHistoricalJavaTypes() {
        // Byte-identical behaviour for already-correct simple args: integral
        // values stay Long (not Integer), decimals stay Double.
        var args = ToolBridgeUtils.parseJsonArgs(
                "{\"name\":\"Alice\",\"age\":30,\"score\":9.5,\"active\":true,\"tag\":null}");
        assertEquals("Alice", args.get("name"));
        assertEquals(30L, args.get("age"));
        assertEquals(9.5, args.get("score"));
        assertEquals(true, args.get("active"));
        assertTrue(args.containsKey("tag"));
        assertNull(args.get("tag"));
    }

    @Test
    void bracketsInsideStringsDoNotTerminateTheValue() {
        var args = ToolBridgeUtils.parseJsonArgs(
                "{\"payload\":{\"msg\":\"hello}world],\\\"still\\\"\"}}");
        var payload = assertInstanceOf(Map.class, args.get("payload"));
        assertEquals("hello}world],\"still\"", payload.get("msg"));
    }

    @Test
    void emptyAndBlankInputsYieldAnEmptyMap() {
        assertEquals(Map.of(), ToolBridgeUtils.parseJsonArgs(null));
        assertEquals(Map.of(), ToolBridgeUtils.parseJsonArgs(""));
        assertEquals(Map.of(), ToolBridgeUtils.parseJsonArgs("   "));
        assertEquals(Map.of(), ToolBridgeUtils.parseJsonArgs("{}"));
    }

    @Test
    void malformedInputSalvagesFlatPairsInsteadOfThrowing() {
        // An unquoted token is not valid JSON; the bridge must not throw out
        // to the runtime — the validator turns the residue into a tool error.
        var args = ToolBridgeUtils.parseJsonArgs("{\"count\":abc123}");
        assertEquals("abc123", args.get("count"));
    }

    @Test
    void truncatedInputDoesNotThrow() {
        var args = ToolBridgeUtils.parseJsonArgs("{\"name\":\"alice\",\"items\":[1,2");
        assertEquals("alice", args.get("name"),
                "the salvageable prefix must survive a truncated payload");
    }

    @Test
    void malformedInputNeverEscapesAsAnException() {
        for (var malformed : List.of("{", "}", "{\"a\"", "{\"a\":}", "not json at all",
                "{\"a\":\"unterminated", "[1,2,3]")) {
            var args = ToolBridgeUtils.parseJsonArgs(malformed);
            assertFalse(args == null, "parse must return a map for input: " + malformed);
        }
    }
}
