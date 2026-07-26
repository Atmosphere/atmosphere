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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolParameterTest {

    @Test
    void recordFields() {
        var p = new ToolParameter("city", "City name",
                "string", true);
        assertEquals("city", p.name());
        assertEquals("City name", p.description());
        assertEquals("string", p.type());
        assertTrue(p.required());
    }

    @Test
    void optionalParameter() {
        var p = new ToolParameter("limit", "Max results",
                "integer", false);
        assertFalse(p.required());
    }

    @Test
    void jsonSchemaTypeString() {
        assertEquals("string",
                ToolParameter.jsonSchemaType(String.class));
    }

    @Test
    void jsonSchemaTypeCharSequence() {
        assertEquals("string",
                ToolParameter.jsonSchemaType(CharSequence.class));
    }

    @Test
    void jsonSchemaTypeInt() {
        assertEquals("integer",
                ToolParameter.jsonSchemaType(int.class));
        assertEquals("integer",
                ToolParameter.jsonSchemaType(Integer.class));
    }

    @Test
    void jsonSchemaTypeLong() {
        assertEquals("integer",
                ToolParameter.jsonSchemaType(long.class));
        assertEquals("integer",
                ToolParameter.jsonSchemaType(Long.class));
    }

    @Test
    void jsonSchemaTypeFloat() {
        assertEquals("number",
                ToolParameter.jsonSchemaType(float.class));
        assertEquals("number",
                ToolParameter.jsonSchemaType(Float.class));
    }

    @Test
    void jsonSchemaTypeDouble() {
        assertEquals("number",
                ToolParameter.jsonSchemaType(double.class));
        assertEquals("number",
                ToolParameter.jsonSchemaType(Double.class));
    }

    @Test
    void jsonSchemaTypeBoolean() {
        assertEquals("boolean",
                ToolParameter.jsonSchemaType(boolean.class));
        assertEquals("boolean",
                ToolParameter.jsonSchemaType(Boolean.class));
    }

    @Test
    void jsonSchemaTypeObjectFallback() {
        assertEquals("object",
                ToolParameter.jsonSchemaType(Map.class));
        assertEquals("object",
                ToolParameter.jsonSchemaType(Object.class));
    }

    @Test
    void jsonSchemaTypeCollectionsAndArraysAreArrays() {
        // A list described as "object" told the model the wrong shape — it is
        // a JSON Schema array, and its element type rides in items().
        assertEquals("array", ToolParameter.jsonSchemaType(List.class));
        assertEquals("array", ToolParameter.jsonSchemaType(java.util.Set.class));
        assertEquals("array", ToolParameter.jsonSchemaType(String[].class));
    }

    @Test
    void jsonSchemaTypeEnumIsAConstrainedString() {
        // An enum is a string whose allowed values are enumerated; describing
        // it as "object" left the model guessing at both shape and values.
        assertEquals("string", ToolParameter.jsonSchemaType(Season.class));
    }

    @Test
    void richParametersCarryEnumItemsAndProperties() {
        var season = ToolParameter.ofEnum("season", "Which season", true,
                List.of("SPRING", "SUMMER"));
        assertTrue(season.hasEnumValues());
        assertEquals(List.of("SPRING", "SUMMER"), season.enumValues());

        var tags = ToolParameter.ofArray("tags", "Tags", false,
                new ToolParameter("item", "", "string", true));
        assertEquals("array", tags.type());
        assertEquals("string", tags.items().type());

        var origin = ToolParameter.ofObject("origin", "Origin", true,
                List.of(new ToolParameter("city", "", "string", true)));
        assertTrue(origin.hasProperties());
        assertEquals("city", origin.properties().get(0).name());
    }

    @Test
    void flatConstructorStaysEmptyOnTheRichFields() {
        // Source compatibility: the 4-arg form still yields a plain node.
        var flat = new ToolParameter("q", "Query", "string", true);
        assertFalse(flat.hasEnumValues());
        assertFalse(flat.hasProperties());
        assertNull(flat.items());
    }

    private enum Season { SPRING, SUMMER }
}
