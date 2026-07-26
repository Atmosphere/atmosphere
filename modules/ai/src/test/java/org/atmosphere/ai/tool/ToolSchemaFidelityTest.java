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

import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the structural fidelity of the tool schema the model receives.
 *
 * <p>Before this, {@link ToolParameter} carried only
 * {@code name/description/type/required}: a Java enum parameter degraded to
 * {@code type:"object"} with no value list, a {@code List<String>} degraded to
 * {@code "object"} with no {@code items}, and a record parameter exposed no
 * {@code properties} — so every runtime advertised an under-specified contract
 * the model could not satisfy reliably.</p>
 */
class ToolSchemaFidelityTest {

    enum Unit { CELSIUS, FAHRENHEIT }

    record GeoPoint(double lat, double lon) { }

    record Filter(String key, Unit unit, List<String> tags) { }

    @SuppressWarnings("unused")
    static class Tools {
        @AiTool(name = "convert", description = "Convert a temperature")
        public String convert(@Param(value = "unit", description = "Target unit") Unit unit,
                              @Param(value = "tags", description = "Tags") List<String> tags,
                              @Param(value = "at", description = "Location") GeoPoint at,
                              @Param(value = "name", description = "Name") String name) {
            return "ok";
        }

        @AiTool(name = "search", description = "Search with a structured filter")
        public String search(@Param(value = "filter", description = "Filter") Filter filter) {
            return "ok";
        }
    }

    private static ToolDefinition tool(String name) {
        var registry = new DefaultToolRegistry();
        registry.register(new Tools());
        return registry.getTool(name).orElseThrow();
    }

    private static ToolParameter param(ToolDefinition tool, String name) {
        return tool.parameters().stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no parameter '" + name + "'"));
    }

    // --- reflection-derived parameter model ---

    @Test
    void enumParameterCarriesItsValueSet() {
        var unit = param(tool("convert"), "unit");
        assertEquals("string", unit.type(), "an enum is a closed string set, not an object");
        assertEquals(List.of("CELSIUS", "FAHRENHEIT"), unit.enumValues());
    }

    @Test
    void collectionParameterCarriesItsElementType() {
        var tags = param(tool("convert"), "tags");
        assertEquals("array", tags.type());
        assertEquals("string", tags.items().type());
    }

    @Test
    void recordParameterCarriesItsProperties() {
        var at = param(tool("convert"), "at");
        assertEquals("object", at.type());
        assertEquals(List.of("lat", "lon"), at.properties().stream().map(ToolParameter::name).toList());
        assertEquals("number", at.properties().get(0).type());
    }

    @Test
    void nestedRecordFacetsRecurse() {
        var filter = param(tool("search"), "filter");
        assertEquals("object", filter.type());
        var unit = filter.properties().stream()
                .filter(p -> p.name().equals("unit")).findFirst().orElseThrow();
        assertEquals(List.of("CELSIUS", "FAHRENHEIT"), unit.enumValues(),
                "an enum nested inside a record must keep its value set");
        var tags = filter.properties().stream()
                .filter(p -> p.name().equals("tags")).findFirst().orElseThrow();
        assertEquals("array", tags.type());
        assertEquals("string", tags.items().type());
    }

    @Test
    void plainScalarParameterCarriesNoFacets() {
        var name = param(tool("convert"), "name");
        assertEquals("string", name.type());
        assertTrue(name.enumValues().isEmpty());
        assertNull(name.items());
        assertTrue(name.properties().isEmpty());
    }

    // --- JSON Schema emission ---

    @Test
    void emittedJsonSchemaShowsEnumValues() {
        var schema = ToolBridgeUtils.buildJsonSchemaString(tool("convert").parameters());
        assertTrue(schema.contains("\"enum\":[\"CELSIUS\",\"FAHRENHEIT\"]"),
                "emitted schema must list the enum's permitted values: " + schema);
    }

    @Test
    void emittedJsonSchemaShowsArrayItemType() {
        var schema = ToolBridgeUtils.buildJsonSchemaString(tool("convert").parameters());
        // The element is emitted as a full nested schema node, so an array of
        // objects keeps its element contract; the leading type is what pins the
        // element kind here.
        assertTrue(schema.contains("\"items\":{\"type\":\"string\""),
                "emitted schema must declare the array element type: " + schema);
    }

    @Test
    void emittedJsonSchemaShowsNestedObjectProperties() {
        var schema = ToolBridgeUtils.buildJsonSchemaString(tool("convert").parameters());
        assertTrue(schema.contains("\"lat\""), "nested record property missing: " + schema);
        assertTrue(schema.contains("\"lon\""), "nested record property missing: " + schema);
    }

    @Test
    void flatParametersEmitTheHistoricalShape() {
        // A parameter with no facets must serialize exactly as before, so
        // provider schemas are unchanged for the overwhelming common case.
        var schema = ToolBridgeUtils.buildJsonSchemaString(
                List.of(new ToolParameter("city", "The city", "string", true)));
        assertEquals("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\","
                + "\"description\":\"The city\"}},\"required\":[\"city\"]}", schema);
    }

    @Test
    void parameterSchemaMapMirrorsTheEmittedString() {
        var map = ToolBridgeUtils.parameterSchemaMap(param(tool("convert"), "unit"));
        assertEquals("string", map.get("type"));
        assertEquals(List.of("CELSIUS", "FAHRENHEIT"), map.get("enum"));
    }

    // --- inbound validation against the richer schema ---

    @Test
    void enumValueOutsideTheSetIsRejected() {
        var errors = ToolArgumentValidator.validate(tool("convert"),
                Map.of("unit", "KELVIN", "tags", List.of("a"),
                        "at", Map.of("lat", 1.0, "lon", 2.0), "name", "x"));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.getFirst().contains("unit"), errors.toString());
    }

    @Test
    void enumValueInsideTheSetIsAccepted() {
        var errors = ToolArgumentValidator.validate(tool("convert"),
                Map.of("unit", "CELSIUS", "tags", List.of("a"),
                        "at", Map.of("lat", 1.0, "lon", 2.0), "name", "x"));
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void wrongArrayElementTypeIsRejected() {
        var errors = ToolArgumentValidator.validate(tool("convert"),
                Map.of("unit", "CELSIUS", "tags", List.of(1, 2),
                        "at", Map.of("lat", 1.0, "lon", 2.0), "name", "x"));
        assertFalse(errors.isEmpty(), "an integer element in a string array must be reported");
        assertTrue(errors.getFirst().contains("tags[0]"), errors.toString());
    }

    @Test
    void missingNestedRequiredPropertyIsReportedWithItsPath() {
        var errors = ToolArgumentValidator.validate(tool("convert"),
                Map.of("unit", "CELSIUS", "tags", List.of("a"),
                        "at", Map.of("lat", 1.0), "name", "x"));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.getFirst().contains("at.lon"), errors.toString());
    }
}
