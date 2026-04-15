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
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ToolArgumentValidatorTest {

    private static ToolDefinition tool(ToolParameter... params) {
        return new ToolDefinition(
                "test_tool", "A test tool", List.of(params), "string",
                args -> "ok", null, 0);
    }

    @Test
    void validArgumentsProduceNoErrors() {
        var td = tool(new ToolParameter("name", "User name", "string", true));
        var errors = ToolArgumentValidator.validate(td, Map.of("name", "Alice"));
        assertEquals(0, errors.size());
    }

    @Test
    void missingRequiredParameterReportsError() {
        var td = tool(new ToolParameter("name", "User name", "string", true));
        var errors = ToolArgumentValidator.validate(td, Map.of());
        assertEquals(1, errors.size());
        assertNotNull(errors.getFirst());
        assertFalse(errors.getFirst().isEmpty());
    }

    @Test
    void missingOptionalParameterIsAccepted() {
        var td = tool(new ToolParameter("tag", "Optional tag", "string", false));
        var errors = ToolArgumentValidator.validate(td, Map.of());
        assertEquals(0, errors.size());
    }

    @Test
    void nullArgumentsMapTreatedAsEmpty() {
        var td = tool(new ToolParameter("name", "User name", "string", true));
        var errors = ToolArgumentValidator.validate(td, null);
        assertEquals(1, errors.size());
    }

    @Test
    void stringTypeAcceptsString() {
        var td = tool(new ToolParameter("s", "desc", "string", true));
        var errors = ToolArgumentValidator.validate(td, Map.of("s", "hello"));
        assertEquals(0, errors.size());
    }

    @Test
    void stringTypeRejectsInteger() {
        var td = tool(new ToolParameter("s", "desc", "string", true));
        var errors = ToolArgumentValidator.validate(td, Map.of("s", 42));
        assertEquals(1, errors.size());
    }

    @Test
    void integerTypeAcceptsInt() {
        var td = tool(new ToolParameter("n", "desc", "integer", true));
        var errors = ToolArgumentValidator.validate(td, Map.of("n", 42));
        assertEquals(0, errors.size());
    }

    @Test
    void integerTypeAcceptsLong() {
        var td = tool(new ToolParameter("n", "desc", "integer", true));
        var errors = ToolArgumentValidator.validate(td, Map.of("n", 100L));
        assertEquals(0, errors.size());
    }

    @Test
    void integerTypeRejectsString() {
        var td = tool(new ToolParameter("n", "desc", "integer", true));
        var errors = ToolArgumentValidator.validate(td, Map.of("n", "forty-two"));
        assertEquals(1, errors.size());
    }

    @Test
    void numberTypeAcceptsDouble() {
        var td = tool(new ToolParameter("n", "desc", "number", true));
        var errors = ToolArgumentValidator.validate(td, Map.of("n", 3.14));
        assertEquals(0, errors.size());
    }

    @Test
    void numberTypeAcceptsInteger() {
        var td = tool(new ToolParameter("n", "desc", "number", true));
        var errors = ToolArgumentValidator.validate(td, Map.of("n", 42));
        assertEquals(0, errors.size());
    }

    @Test
    void booleanTypeAcceptsBoolean() {
        var td = tool(new ToolParameter("b", "desc", "boolean", true));
        var errors = ToolArgumentValidator.validate(td, Map.of("b", true));
        assertEquals(0, errors.size());
    }

    @Test
    void booleanTypeRejectsString() {
        var td = tool(new ToolParameter("b", "desc", "boolean", true));
        var errors = ToolArgumentValidator.validate(td, Map.of("b", "true"));
        assertEquals(1, errors.size());
    }

    @Test
    void unknownTypePassesThrough() {
        var td = tool(new ToolParameter("o", "desc", "object", true));
        var errors = ToolArgumentValidator.validate(td, Map.of("o", Map.of("a", 1)));
        assertEquals(0, errors.size());
    }

    @Test
    void multipleErrorsReported() {
        var td = tool(
                new ToolParameter("name", "desc", "string", true),
                new ToolParameter("age", "desc", "integer", true));
        var errors = ToolArgumentValidator.validate(td, Map.of());
        assertEquals(2, errors.size());
    }

    @Test
    void noParametersAlwaysValid() {
        var td = tool();
        var errors = ToolArgumentValidator.validate(td, Map.of("extra", "ignored"));
        assertEquals(0, errors.size());
    }

    @Test
    void integerTypeAcceptsShort() {
        var td = tool(new ToolParameter("n", "desc", "integer", true));
        var errors = ToolArgumentValidator.validate(td, Map.of("n", (short) 5));
        assertEquals(0, errors.size());
    }

    @Test
    void integerTypeAcceptsByte() {
        var td = tool(new ToolParameter("n", "desc", "integer", true));
        var errors = ToolArgumentValidator.validate(td, Map.of("n", (byte) 3));
        assertEquals(0, errors.size());
    }
}
