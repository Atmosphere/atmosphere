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
package org.atmosphere.verifier;

import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.ai.tool.ToolResult;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.verifier.ast.SymRef;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.policy.Policy;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared fixtures for verifier and executor tests. Centralising the
 * canonical "fetch then summarize" plan keeps every test class building
 * the same shape — mutations applied per test (unknown tool, forward
 * ref, failing dispatcher) stay focused on the one delta they assert.
 */
final class PlanFixtures {

    private PlanFixtures() {
    }

    static final String FETCH = "fetch_emails";
    static final String SUMMARIZE = "summarize";
    static final String SEND = "send_email";

    /** {@code [fetch_emails -> emails] → [summarize(@emails) -> summary]} — a well-formed plan. */
    static Workflow okPlan() {
        return new Workflow(
                "Fetch and summarize",
                List.of(
                        new WorkflowStep("fetch", new ToolCallNode(
                                FETCH, Map.of("folder", "inbox"), "emails")),
                        new WorkflowStep("summarize", new ToolCallNode(
                                SUMMARIZE, Map.of("input", new SymRef("emails")), "summary"))
                ));
    }

    /** Names {@code delete_database}, which the standard policy doesn't allow. */
    static Workflow unknownToolPlan() {
        return new Workflow(
                "Drop the database",
                List.of(
                        new WorkflowStep("danger", new ToolCallNode(
                                "delete_database", Map.of(), "rip"))
                ));
    }

    /** References {@code emails} before any step has bound it. */
    static Workflow forwardRefPlan() {
        return new Workflow(
                "Summarize before fetching",
                List.of(
                        new WorkflowStep("summarize", new ToolCallNode(
                                SUMMARIZE, Map.of("input", new SymRef("emails")), "summary")),
                        new WorkflowStep("fetch", new ToolCallNode(
                                FETCH, Map.of("folder", "inbox"), "emails"))
                ));
    }

    /** Standard allowlist: fetch + summarize permitted; send_email pinned in some tests. */
    static Policy policyAllowing(String... tools) {
        return Policy.allowlist("test-policy", tools);
    }

    /**
     * Stub registry that records every {@code execute} call into the
     * supplied capture map and returns deterministic JSON-string results.
     * Tests can pass {@code null} as the captures map when they don't care.
     */
    static ToolRegistry fakeRegistry(Map<String, Map<String, Object>> captures) {
        return new FakeRegistry(captures, null);
    }

    /**
     * Stub registry whose {@code execute} throws on the named tool. Other
     * tools return successfully; their args are recorded into {@code captures}.
     */
    static ToolRegistry failingRegistry(String failingTool,
                                        Map<String, Map<String, Object>> captures) {
        return new FakeRegistry(captures, failingTool);
    }

    /**
     * Registry pre-populated with stub {@link ToolDefinition}s for the three
     * fixture tools. Used by {@link AllowlistVerifier} to verify both the
     * allowlist hit AND the registry-existence half of the check.
     */
    static ToolRegistry registryWithFixtureTools() {
        var registry = new InMemoryRegistry();
        registry.register(stubTool(FETCH));
        registry.register(stubTool(SUMMARIZE));
        registry.register(stubTool(SEND));
        return registry;
    }

    /** Same as {@link #registryWithFixtureTools()} but omits one tool. */
    static ToolRegistry registryMissing(String omittedTool) {
        var registry = new InMemoryRegistry();
        for (String t : List.of(FETCH, SUMMARIZE, SEND)) {
            if (!t.equals(omittedTool)) {
                registry.register(stubTool(t));
            }
        }
        return registry;
    }

    private static ToolDefinition stubTool(String name) {
        return ToolDefinition.builder(name, "stub for tests")
                .executor(args -> "stub-" + name)
                .build();
    }

    /**
     * Minimal mutable registry used in tests. Tracks tools by name and
     * delegates execute to deterministic stub returns or to an injected
     * failure trigger.
     */
    private static final class FakeRegistry implements ToolRegistry {
        private final Map<String, ToolDefinition> tools = new HashMap<>();
        private final Map<String, Map<String, Object>> captures;
        private final String failingTool;

        FakeRegistry(Map<String, Map<String, Object>> captures, String failingTool) {
            this.captures = captures;
            this.failingTool = failingTool;
            // Pre-register the three fixture tools so every test starts
            // from the same known state.
            register(stubTool(FETCH));
            register(stubTool(SUMMARIZE));
            register(stubTool(SEND));
        }

        @Override
        public void register(ToolDefinition tool) {
            tools.put(tool.name(), tool);
        }

        @Override
        public void register(Object toolProvider) {
            throw new UnsupportedOperationException("fixture registry");
        }

        @Override
        public Optional<ToolDefinition> getTool(String name) {
            return Optional.ofNullable(tools.get(name));
        }

        @Override
        public Collection<ToolDefinition> getTools(Collection<String> names) {
            return names.stream().map(tools::get).filter(t -> t != null).toList();
        }

        @Override
        public Collection<ToolDefinition> allTools() {
            return tools.values();
        }

        @Override
        public boolean unregister(String name) {
            return tools.remove(name) != null;
        }

        @Override
        public ToolResult execute(String toolName, Map<String, Object> arguments) {
            if (captures != null) {
                captures.put(toolName, new LinkedHashMap<>(arguments));
            }
            if (toolName.equals(failingTool)) {
                return ToolResult.failure(toolName, "boom");
            }
            return ToolResult.success(toolName, "result-of-" + toolName);
        }

        @Override
        public ToolResult execute(String toolName, Map<String, Object> arguments,
                                  StreamingSession session) {
            return execute(toolName, arguments);
        }
    }

    /** Tiny registry used when only the metadata side of the API matters. */
    private static final class InMemoryRegistry implements ToolRegistry {
        private final Map<String, ToolDefinition> tools = new HashMap<>();

        @Override
        public void register(ToolDefinition tool) {
            tools.put(tool.name(), tool);
        }

        @Override
        public void register(Object toolProvider) {
            throw new UnsupportedOperationException("metadata-only");
        }

        @Override
        public Optional<ToolDefinition> getTool(String name) {
            return Optional.ofNullable(tools.get(name));
        }

        @Override
        public Collection<ToolDefinition> getTools(Collection<String> names) {
            return names.stream().map(tools::get).filter(t -> t != null).toList();
        }

        @Override
        public Collection<ToolDefinition> allTools() {
            return tools.values();
        }

        @Override
        public boolean unregister(String name) {
            return tools.remove(name) != null;
        }

        @Override
        public ToolResult execute(String toolName, Map<String, Object> arguments) {
            throw new UnsupportedOperationException("metadata-only");
        }
    }

}
