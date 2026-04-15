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
import org.atmosphere.ai.annotation.RequiresApproval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DefaultToolRegistry} focusing on annotation-based
 * registration with multiple tools, argument type conversion,
 * {@code @RequiresApproval} metadata, and concurrent access.
 */
public class AgentToolRegistryTest {

    private DefaultToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultToolRegistry();
    }

    // ── Annotation-based registration with multiple tools ──

    @Test
    void registerMultipleToolsFromSingleProvider() {
        registry.register(new MultiToolProvider());

        assertTrue(registry.getTool("add").isPresent());
        assertTrue(registry.getTool("subtract").isPresent());
        assertEquals(2, registry.allTools().size());
    }

    @Test
    void annotatedToolParametersDiscovered() {
        registry.register(new MultiToolProvider());

        var addTool = registry.getTool("add").orElseThrow();
        assertEquals(2, addTool.parameters().size());
        assertEquals("a", addTool.parameters().get(0).name());
        assertEquals("First number", addTool.parameters().get(0).description());
        assertEquals("integer", addTool.parameters().get(0).type());
    }

    @Test
    void executeAnnotatedToolWithTypeConversion() {
        registry.register(new MultiToolProvider());

        var result = registry.execute("add", Map.of("a", "5", "b", "3"));
        assertTrue(result.success());
        assertEquals("8", result.result());
    }

    @Test
    void executeAnnotatedSubtract() {
        registry.register(new MultiToolProvider());

        var result = registry.execute("subtract", Map.of("a", "10", "b", "4"));
        assertTrue(result.success());
        assertEquals("6", result.result());
    }

    // ── Argument type conversion ──

    @Test
    void convertDoubleArgument() {
        registry.register(new TypeConversionTools());

        var result = registry.execute("format_double",
                Map.of("value", "3.14"));
        assertTrue(result.success());
        assertEquals("3.14", result.result());
    }

    @Test
    void convertBooleanArgument() {
        registry.register(new TypeConversionTools());

        var result = registry.execute("check_flag",
                Map.of("flag", "true"));
        assertTrue(result.success());
        assertEquals("true", result.result());
    }

    @Test
    void convertLongArgument() {
        registry.register(new TypeConversionTools());

        var result = registry.execute("big_number",
                Map.of("value", "9999999999"));
        assertTrue(result.success());
        assertEquals("9999999999", result.result());
    }

    @Test
    void missingArgumentCausesExecutionFailure() {
        registry.register(new TypeConversionTools());

        var result = registry.execute("format_double", Map.of());
        assertFalse(result.success());
        assertNotNull(result.error());
    }

    // ── @RequiresApproval metadata ──

    @Test
    void approvalAnnotationDiscoveredOnTool() {
        registry.register(new ApprovalToolProvider());

        var tool = registry.getTool("delete_item").orElseThrow();
        assertTrue(tool.requiresApproval());
        assertEquals("Are you sure you want to delete?", tool.approvalMessage());
        assertEquals(60, tool.approvalTimeout());
    }

    @Test
    void toolWithoutApprovalHasNullMessage() {
        registry.register(new MultiToolProvider());

        var tool = registry.getTool("add").orElseThrow();
        assertFalse(tool.requiresApproval());
    }

    // ── getTools subset selection ──

    @Test
    void getToolsReturnsOnlyMatching() {
        registry.register(new MultiToolProvider());
        registry.register(ToolDefinition.builder("extra", "Extra")
                .executor(args -> "x").build());

        var selected = registry.getTools(List.of("add", "extra"));
        assertEquals(2, selected.size());
        var names = selected.stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toSet());
        assertEquals(Set.of("add", "extra"), names);
    }

    @Test
    void getToolsSkipsMissing() {
        registry.register(new MultiToolProvider());

        var selected = registry.getTools(List.of("add", "nonexistent"));
        assertEquals(1, selected.size());
    }

    @Test
    void getToolsWithEmptyListReturnsEmpty() {
        registry.register(new MultiToolProvider());

        var selected = registry.getTools(List.of());
        assertTrue(selected.isEmpty());
    }

    // ── Unregister ──

    @Test
    void unregisterAnnotatedTool() {
        registry.register(new MultiToolProvider());

        assertTrue(registry.unregister("add"));
        assertTrue(registry.getTool("add").isEmpty());
        assertTrue(registry.getTool("subtract").isPresent());
        assertEquals(1, registry.allTools().size());
    }

    @Test
    void unregisterNonexistentReturnsFalse() {
        assertFalse(registry.unregister("ghost"));
    }

    // ── Execute error paths ──

    @Test
    void executeToolThatThrowsReturnsFailure() {
        registry.register(ToolDefinition.builder("boom", "Explodes")
                .executor(args -> { throw new RuntimeException("kaboom"); })
                .build());

        var result = registry.execute("boom", Map.of());
        assertFalse(result.success());
        assertEquals("kaboom", result.error());
        assertEquals("boom", result.toolName());
    }

    @Test
    void executeToolReturningNullRecordsNullString() {
        registry.register(ToolDefinition.builder("nil", "Returns null")
                .executor(args -> null)
                .build());

        var result = registry.execute("nil", Map.of());
        assertTrue(result.success());
        assertEquals("null", result.result());
    }

    // ── allTools returns immutable copy ──

    @Test
    void allToolsReturnsSnapshot() {
        registry.register(ToolDefinition.builder("a", "A")
                .executor(args -> "a").build());

        var snapshot = registry.allTools();
        registry.register(ToolDefinition.builder("b", "B")
                .executor(args -> "b").build());

        assertEquals(1, snapshot.size());
        assertEquals(2, registry.allTools().size());
    }

    // ── Duplicate registration of annotated provider ──

    @Test
    void registerSameProviderTwiceThrows() {
        var provider = new MultiToolProvider();
        registry.register(provider);
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(provider));
    }

    // ── Return type from annotated method ──

    @Test
    void annotatedToolReturnTypeDetected() {
        registry.register(new MultiToolProvider());

        var tool = registry.getTool("add").orElseThrow();
        assertNotNull(tool.returnType());
        assertEquals("integer", tool.returnType());
    }

    // ---- Test fixture classes ----

    @SuppressWarnings("unused")
    static class MultiToolProvider {
        @AiTool(name = "add", description = "Add two numbers")
        public int add(@Param(value = "a", description = "First number") int a,
                       @Param(value = "b", description = "Second number") int b) {
            return a + b;
        }

        @AiTool(name = "subtract", description = "Subtract two numbers")
        public int subtract(@Param("a") int a, @Param("b") int b) {
            return a - b;
        }
    }

    @SuppressWarnings("unused")
    static class TypeConversionTools {
        @AiTool(name = "format_double", description = "Format a double")
        public double formatDouble(@Param("value") double value) {
            return value;
        }

        @AiTool(name = "check_flag", description = "Check a boolean flag")
        public boolean checkFlag(@Param("flag") boolean flag) {
            return flag;
        }

        @AiTool(name = "big_number", description = "Handle a long value")
        public long bigNumber(@Param("value") long value) {
            return value;
        }
    }

    @SuppressWarnings("unused")
    static class ApprovalToolProvider {
        @AiTool(name = "delete_item", description = "Delete an item")
        @RequiresApproval(value = "Are you sure you want to delete?",
                          timeoutSeconds = 60)
        public String deleteItem(@Param("id") String id) {
            return "deleted: " + id;
        }
    }
}
