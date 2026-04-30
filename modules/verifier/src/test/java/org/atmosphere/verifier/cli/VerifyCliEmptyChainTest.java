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
package org.atmosphere.verifier.cli;

import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.ai.tool.ToolResult;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.policy.Policy;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the fail-closed behavior of {@link VerifyCli#runChain} when the
 * service-loader supplier returns no providers — the packaging-defect
 * case a CLI consumer would otherwise consume as silent OK.
 *
 * <p>Lives in {@code org.atmosphere.verifier.cli} so it can call the
 * package-private testable seam directly without touching the JVM's
 * {@link java.util.ServiceLoader}.</p>
 */
class VerifyCliEmptyChainTest {

    @Test
    void emptyDiscoveryProducesChainEmptyViolation() {
        var policy = Policy.allowlist("p", "fetch_emails");
        var workflow = new Workflow("g", List.of(
                new WorkflowStep("s", new ToolCallNode(
                        "fetch_emails", Map.of(), "out"))));

        var result = VerifyCli.runChain(workflow, policy,
                new EmptyRegistry(), List::of);

        assertEquals(1, result.violations().size());
        var v = result.violations().get(0);
        assertEquals("chain-empty", v.category());
        assertTrue(v.message().contains("No PlanVerifier"),
                () -> "expected fail-closed diagnostic; got: " + v.message());
        assertTrue(v.message().contains("META-INF/services"),
                () -> "expected service-discovery diagnostic; got: " + v.message());
    }

    @Test
    void nullDiscoveryProducesChainEmptyViolation() {
        // Defensive — a custom supplier could return null; treat it
        // identically to empty.
        var policy = Policy.allowlist("p", "fetch_emails");
        var workflow = new Workflow("g", List.of());

        var result = VerifyCli.runChain(workflow, policy,
                new EmptyRegistry(), () -> null);

        assertEquals(1, result.violations().size());
        assertEquals("chain-empty", result.violations().get(0).category());
    }

    /** Minimal stub — the runChain fail-closed path never reaches the registry. */
    private static final class EmptyRegistry implements ToolRegistry {
        private final Map<String, ToolDefinition> tools = new HashMap<>();
        @Override public void register(ToolDefinition tool) { tools.put(tool.name(), tool); }
        @Override public void register(Object toolProvider) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<ToolDefinition> getTool(String name) {
            return Optional.ofNullable(tools.get(name));
        }
        @Override public Collection<ToolDefinition> getTools(Collection<String> names) {
            return tools.values().stream().filter(t -> names.contains(t.name())).toList();
        }
        @Override public Collection<ToolDefinition> allTools() { return tools.values(); }
        @Override public boolean unregister(String name) { return tools.remove(name) != null; }
        @Override public ToolResult execute(String toolName, Map<String, Object> arguments) {
            throw new UnsupportedOperationException();
        }
        @Override public ToolResult execute(String toolName, Map<String, Object> arguments,
                                            StreamingSession session) {
            throw new UnsupportedOperationException();
        }
    }
}
