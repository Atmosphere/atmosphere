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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the basic (non-approval) paths of {@link ToolExecutionHelper}:
 * the 3-arg {@code executeAndFormat}, the 2-arg validation overload,
 * and the {@code toToolMap} utility.
 */
class ToolExecutionHelperTest {

    @Test
    void executeAndFormatReturnsResultToString() {
        var result = ToolExecutionHelper.executeAndFormat(
                "greet", args -> "Hello, " + args.get("name"), Map.of("name", "World"));
        assertEquals("Hello, World", result);
    }

    @Test
    void executeAndFormatReturnsNullStringForNullResult() {
        var result = ToolExecutionHelper.executeAndFormat(
                "noop", args -> null, Map.of());
        assertEquals("null", result);
    }

    @Test
    void executeAndFormatReturnsJsonErrorOnException() {
        var result = ToolExecutionHelper.executeAndFormat(
                "fail", args -> { throw new RuntimeException("boom"); }, Map.of());
        assertTrue(result.contains("\"error\""));
        assertTrue(result.contains("boom"));
    }

    @Test
    void executeAndFormatEscapesQuotesInErrorMessage() {
        var result = ToolExecutionHelper.executeAndFormat(
                "esc", args -> { throw new RuntimeException("say \"hi\""); }, Map.of());
        // The JSON must be valid — escaped quotes, not raw ones
        assertFalse(result.contains("say \"hi\""));
        assertTrue(result.contains("error"));
    }

    @Test
    void executeAndFormatReturnsNumericResult() {
        var result = ToolExecutionHelper.executeAndFormat(
                "add", args -> 42, Map.of());
        assertEquals("42", result);
    }

    @Test
    void executeAndFormatWithToolDefinitionDelegatesToExecutor() {
        var tool = ToolDefinition.builder("echo", "Echoes input")
                .parameter("msg", "The message", "string")
                .executor(args -> args.get("msg"))
                .build();
        var result = ToolExecutionHelper.executeAndFormat(tool, Map.of("msg", "hello"));
        assertEquals("hello", result);
    }

    @Test
    void executeAndFormatWithToolDefinitionReturnsValidationErrorForMissingRequired() {
        var tool = ToolDefinition.builder("lookup", "Looks up a key")
                .parameter("key", "The key to look up", "string", true)
                .executor(args -> "found")
                .build();
        // Omit the required "key" parameter
        var result = ToolExecutionHelper.executeAndFormat(tool, Map.of());
        assertTrue(result.contains("invalid_arguments"));
        assertTrue(result.contains("lookup"));
    }

    @Test
    void toToolMapBuildsMapFromList() {
        var tool1 = ToolDefinition.builder("a", "Tool A").executor(args -> "a").build();
        var tool2 = ToolDefinition.builder("b", "Tool B").executor(args -> "b").build();
        var map = ToolExecutionHelper.toToolMap(List.of(tool1, tool2));
        assertEquals(2, map.size());
        assertNotNull(map.get("a"));
        assertNotNull(map.get("b"));
        assertEquals("Tool A", map.get("a").description());
    }

    @Test
    void toToolMapReturnsEmptyMapForEmptyList() {
        var map = ToolExecutionHelper.toToolMap(List.of());
        assertTrue(map.isEmpty());
    }

    @Test
    void executeAndFormatWithToolPassesArgsToExecutor() {
        var tool = ToolDefinition.builder("concat", "Concatenates two strings")
                .parameter("a", "First", "string")
                .parameter("b", "Second", "string")
                .executor(args -> args.get("a").toString() + args.get("b"))
                .build();
        var result = ToolExecutionHelper.executeAndFormat(tool, Map.of("a", "foo", "b", "bar"));
        assertEquals("foobar", result);
    }

    /**
     * Pins the PermissionMode outer gate — the blocker that {@code PermissionMode}
     * was referenced only in documentation before. An explicit {@code DENY_ALL}
     * on the injectables map must reject the call without reaching the
     * executor, regardless of whether the tool carries {@code @RequiresApproval}.
     */
    @Test
    void permissionModeDenyAllRejectsEvenPermissiveTools() {
        var invoked = new boolean[]{false};
        var tool = ToolDefinition.builder("safe", "Not gated by @RequiresApproval")
                .parameter("note", "Arbitrary string", "string")
                .executor(args -> { invoked[0] = true; return "ok"; })
                .build();
        var session = new DefaultToolRegistryTest.StubSession("sess-mode");
        var result = ToolExecutionHelper.executeWithApproval(
                "safe", tool, Map.of("note", "hi"),
                session, null, null,
                Map.of(org.atmosphere.ai.identity.PermissionMode.class,
                        org.atmosphere.ai.identity.PermissionMode.DENY_ALL));
        assertFalse(invoked[0], "DENY_ALL must skip the executor");
        assertTrue(result.contains("DENY_ALL"), "response must name the mode: " + result);
    }

    @Test
    void permissionModeBypassSkipsApprovalForGatedTool() {
        var tool = ToolDefinition.builder("gated", "Gated tool with @RequiresApproval")
                .parameter("x", "x", "string")
                .executor(args -> "ran")
                .requiresApproval("Are you sure?")
                .build();
        var session = new DefaultToolRegistryTest.StubSession("sess-bypass");
        // BYPASS short-circuits the approval gate. strategy=null would fail
        // closed under DEFAULT; with BYPASS the call still runs.
        var result = ToolExecutionHelper.executeWithApproval(
                "gated", tool, Map.of("x", "v"),
                session, null, null,
                Map.of(org.atmosphere.ai.identity.PermissionMode.class,
                        org.atmosphere.ai.identity.PermissionMode.BYPASS));
        assertEquals("ran", result);
    }
}
