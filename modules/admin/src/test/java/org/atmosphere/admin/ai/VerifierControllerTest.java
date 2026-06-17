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
package org.atmosphere.admin.ai;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.ai.tool.ToolResult;
import org.atmosphere.verifier.PlanAndVerify;
import org.atmosphere.verifier.checks.AllowlistVerifier;
import org.atmosphere.verifier.checks.WellFormednessVerifier;
import org.atmosphere.verifier.policy.Policy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral contract for {@link VerifierController}: the read-only
 * {@link VerifierController#dryCheck(String)} must verify a plan and surface
 * its violations <em>without ever executing</em>, while
 * {@link VerifierController#check(String)} executes a clean plan. The
 * execution-recording {@link RecordingRegistry} is the proof: dryCheck must
 * leave it empty on every path; check must populate it on a clean plan.
 */
class VerifierControllerTest {

    private static final String OK_PLAN_JSON = """
            {
              "goal": "fetch and summarize",
              "steps": [
                {
                  "label": "fetch",
                  "toolName": "fetch_emails",
                  "arguments": { "folder": "inbox" },
                  "resultBinding": "emails"
                },
                {
                  "label": "summarize",
                  "toolName": "summarize",
                  "arguments": { "input": "@emails" },
                  "resultBinding": "summary"
                }
              ]
            }
            """;

    private static final String UNKNOWN_TOOL_JSON = """
            {
              "goal": "drop the database",
              "steps": [
                {
                  "label": "danger",
                  "toolName": "delete_database",
                  "arguments": {},
                  "resultBinding": null
                }
              ]
            }
            """;

    @Test
    void dryCheckRefusedPlanReturnsViolationsWithoutExecuting() {
        var executed = new LinkedHashMap<String, Map<String, Object>>();
        var controller = controllerFor(UNKNOWN_TOOL_JSON, executed);

        var result = controller.dryCheck("drop the db");

        assertEquals("refused", result.get("status"));
        assertTrue(executed.isEmpty(),
                () -> "dryCheck executed a tool on a refused plan: " + executed.keySet());
        var violations = (List<?>) result.get("violations");
        assertFalse(violations.isEmpty(), "a refused plan must surface its violations");
        assertFalse(result.containsKey("env"),
                "dryCheck must never produce an executed environment");
    }

    @Test
    void dryCheckCleanPlanVerifiesButStillDoesNotExecute() {
        var executed = new LinkedHashMap<String, Map<String, Object>>();
        var controller = controllerFor(OK_PLAN_JSON, executed);

        var result = controller.dryCheck("fetch and summarize");

        assertEquals("verified", result.get("status"));
        assertTrue(((List<?>) result.get("violations")).isEmpty(),
                "a clean plan must report no violations");
        // The headline guarantee: verifying a *passing* plan must not run it.
        assertTrue(executed.isEmpty(),
                () -> "dryCheck executed a verified plan — that defeats its purpose: "
                        + executed.keySet());
        assertFalse(result.containsKey("env"),
                "dryCheck must never produce an executed environment");
    }

    @Test
    void checkCleanPlanExecutesProvingDryCheckDiffersFromCheck() {
        var executed = new LinkedHashMap<String, Map<String, Object>>();
        var controller = controllerFor(OK_PLAN_JSON, executed);

        var result = controller.check("fetch and summarize");

        assertEquals("executed", result.get("status"));
        assertTrue(result.containsKey("env"), "check must return the executed environment");
        assertTrue(executed.containsKey("fetch_emails") && executed.containsKey("summarize"),
                () -> "check must execute the verified plan's tools: " + executed.keySet());
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private static VerifierController controllerFor(String plannerJson,
                                                    Map<String, Map<String, Object>> executed) {
        var pv = new PlanAndVerify(
                runtimeReturning(plannerJson),
                new RecordingRegistry(executed),
                Policy.allowlist("test-policy", "fetch_emails", "summarize"),
                List.of(new AllowlistVerifier(), new WellFormednessVerifier()));
        return new VerifierController(pv, null);
    }

    private static AgentRuntime runtimeReturning(String json) {
        return new StubAgentRuntime(json);
    }

    /**
     * AgentRuntime that returns a fixed planner JSON from {@code generate(...)}.
     * Streaming is unreachable in plan mode, so {@code execute} throws to
     * surface an accidental wiring change loudly.
     */
    private static final class StubAgentRuntime implements AgentRuntime {
        private final String response;

        StubAgentRuntime(String response) {
            this.response = response;
        }

        @Override public String name() { return "stub"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { /* no-op */ }
        @Override public Set<AiCapability> capabilities() { return Collections.emptySet(); }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            throw new UnsupportedOperationException(
                    "StubAgentRuntime does not stream — plan mode must call generate()");
        }

        @Override
        public String generate(AgentExecutionContext context) {
            return response;
        }

        @Override
        public String generate(AgentExecutionContext context, Duration timeout) {
            return response;
        }
    }

    /**
     * Tool registry pre-populated with the two fixture tools (so the allowlist
     * verifier's registry-existence check passes for the OK plan) that records
     * every {@code execute} call into the supplied map. An empty map after a
     * call means no tool fired.
     */
    private static final class RecordingRegistry implements ToolRegistry {
        private final Map<String, ToolDefinition> tools = new HashMap<>();
        private final Map<String, Map<String, Object>> executed;

        RecordingRegistry(Map<String, Map<String, Object>> executed) {
            this.executed = executed;
            register(stub("fetch_emails"));
            register(stub("summarize"));
        }

        private static ToolDefinition stub(String name) {
            return ToolDefinition.builder(name, "stub for tests")
                    .executor(args -> "result-of-" + name)
                    .build();
        }

        @Override public void register(ToolDefinition tool) { tools.put(tool.name(), tool); }

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

        @Override public Collection<ToolDefinition> allTools() { return tools.values(); }

        @Override public boolean unregister(String name) { return tools.remove(name) != null; }

        @Override
        public ToolResult execute(String toolName, Map<String, Object> arguments) {
            executed.put(toolName, new LinkedHashMap<>(arguments));
            return ToolResult.success(toolName, "result-of-" + toolName);
        }

        @Override
        public ToolResult execute(String toolName, Map<String, Object> arguments,
                                  StreamingSession session) {
            return execute(toolName, arguments);
        }
    }
}
