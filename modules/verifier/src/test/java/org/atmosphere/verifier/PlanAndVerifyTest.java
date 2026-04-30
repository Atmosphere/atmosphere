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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.checks.AllowlistVerifier;
import org.atmosphere.verifier.checks.WellFormednessVerifier;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.spi.PlanVerifier;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanAndVerifyTest {

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
    void runExecutesVerifiedPlanAndReturnsEnv() {
        Map<String, Map<String, Object>> captures = new LinkedHashMap<>();
        ToolRegistry registry = PlanFixtures.fakeRegistry(captures);
        var pv = new PlanAndVerify(
                stubRuntimeReturning(OK_PLAN_JSON),
                registry,
                PlanFixtures.policyAllowing(PlanFixtures.FETCH, PlanFixtures.SUMMARIZE),
                List.of(new AllowlistVerifier(), new WellFormednessVerifier()));

        Map<String, Object> env = pv.run("fetch and summarize", Map.of());

        // Both bindings landed
        assertEquals("result-of-" + PlanFixtures.FETCH, env.get("emails"));
        assertEquals("result-of-" + PlanFixtures.SUMMARIZE, env.get("summary"));
        // The SymRef was resolved before dispatch
        assertEquals("result-of-" + PlanFixtures.FETCH,
                captures.get(PlanFixtures.SUMMARIZE).get("input"));
    }

    @Test
    void verifierViolationAbortsBeforeAnyToolFires() {
        Map<String, Map<String, Object>> captures = new LinkedHashMap<>();
        ToolRegistry registry = PlanFixtures.fakeRegistry(captures);
        var pv = new PlanAndVerify(
                stubRuntimeReturning(UNKNOWN_TOOL_JSON),
                registry,
                // delete_database is NOT in the policy
                PlanFixtures.policyAllowing(PlanFixtures.FETCH, PlanFixtures.SUMMARIZE),
                List.of(new AllowlistVerifier(), new WellFormednessVerifier()));

        PlanVerificationException ex = assertThrows(
                PlanVerificationException.class,
                () -> pv.run("drop the db", Map.of()));

        // No tool was dispatched — this is the headline guarantee
        assertTrue(captures.isEmpty(),
                () -> "tool fired despite verification failure: " + captures.keySet());

        assertNotNull(ex.workflow());
        assertEquals(1, ex.result().violations().size());
        Violation v = ex.result().violations().get(0);
        assertEquals("allowlist", v.category());
        assertTrue(v.message().contains("delete_database"),
                () -> "violation message lacks tool name: " + v.message());
        assertTrue(ex.getMessage().contains("delete_database"),
                () -> "exception message lacks tool name: " + ex.getMessage());
    }

    @Test
    void planExposesAstWithoutVerifying() {
        var pv = new PlanAndVerify(
                stubRuntimeReturning(OK_PLAN_JSON),
                PlanFixtures.fakeRegistry(null),
                PlanFixtures.policyAllowing(PlanFixtures.FETCH, PlanFixtures.SUMMARIZE),
                List.of());

        Workflow wf = pv.plan("anything");

        assertEquals("fetch and summarize", wf.goal());
        assertEquals(2, wf.steps().size());
    }

    @Test
    void verifyAggregatesEveryViolationAcrossChain() {
        // Two stub verifiers, each emitting one violation, return both.
        var v1 = stubVerifier("first", 10,
                Violation.of("first", "v1 fired"));
        var v2 = stubVerifier("second", 20,
                Violation.of("second", "v2 fired"));
        var pv = new PlanAndVerify(
                stubRuntimeReturning(OK_PLAN_JSON),
                PlanFixtures.fakeRegistry(null),
                PlanFixtures.policyAllowing(PlanFixtures.FETCH, PlanFixtures.SUMMARIZE),
                List.of(v1, v2));

        VerificationResult result = pv.verify(pv.plan("anything"));

        assertEquals(2, result.violations().size());
        assertEquals("first", result.violations().get(0).category());
        assertEquals("second", result.violations().get(1).category());
    }

    @Test
    void verifierChainIsSortedByPriority() {
        var lowPriority = stubVerifier("low", 200, null);
        var highPriority = stubVerifier("high", 10, null);
        // Insert in reverse priority order — constructor must sort them
        var pv = new PlanAndVerify(
                stubRuntimeReturning(OK_PLAN_JSON),
                PlanFixtures.fakeRegistry(null),
                PlanFixtures.policyAllowing(),
                List.of(lowPriority, highPriority));

        List<PlanVerifier> chain = pv.verifiers();
        assertEquals("high", chain.get(0).name());
        assertEquals("low", chain.get(1).name());
    }

    @Test
    void runtimeReceivesPlanModeSystemPromptAndEmptyToolList() {
        AtomicReference<AgentExecutionContext> seen = new AtomicReference<>();
        AgentRuntime runtime = capturingRuntime(seen, OK_PLAN_JSON);
        var pv = new PlanAndVerify(
                runtime,
                PlanFixtures.fakeRegistry(null),
                PlanFixtures.policyAllowing(PlanFixtures.FETCH, PlanFixtures.SUMMARIZE),
                List.of());

        pv.plan("user goal");

        AgentExecutionContext ctx = seen.get();
        assertNotNull(ctx);
        // tools list is intentionally empty in plan mode — the runtime must
        // not fall back to ReAct-style tool calling
        assertTrue(ctx.tools().isEmpty(), "plan-mode context leaked tools to runtime");
        assertEquals("user goal", ctx.message());
        assertNotNull(ctx.systemPrompt());
        assertTrue(ctx.systemPrompt().contains("planning agent"),
                () -> "system prompt missing plan-mode marker: " + ctx.systemPrompt());
    }

    @Test
    void withDefaultsDiscoversServiceLoaderVerifiers() {
        var pv = PlanAndVerify.withDefaults(
                stubRuntimeReturning(OK_PLAN_JSON),
                PlanFixtures.fakeRegistry(null),
                PlanFixtures.policyAllowing(PlanFixtures.FETCH, PlanFixtures.SUMMARIZE));

        // Both Phase 1 verifiers are registered via META-INF/services
        Set<String> names = pv.verifiers().stream()
                .map(PlanVerifier::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertTrue(names.contains("allowlist"),
                () -> "missing allowlist verifier; found: " + names);
        assertTrue(names.contains("well-formed"),
                () -> "missing well-formed verifier; found: " + names);
    }

    // ---- test helpers --------------------------------------------------

    /**
     * Build an {@link AgentRuntime} that returns a fixed string from
     * {@code generate(...)} regardless of input. The runtime is otherwise
     * a no-op — every other method returns trivially.
     */
    private static AgentRuntime stubRuntimeReturning(String response) {
        return capturingRuntime(new AtomicReference<>(), response);
    }

    /**
     * Capturing runtime — records the {@link AgentExecutionContext} it was
     * called with into {@code seen} and returns the configured response.
     */
    private static AgentRuntime capturingRuntime(AtomicReference<AgentExecutionContext> seen,
                                                 String response) {
        return new StubAgentRuntime(ctx -> {
            seen.set(ctx);
            return response;
        });
    }

    private static PlanVerifier stubVerifier(String name, int priority, Violation v) {
        VerificationResult result = v == null
                ? VerificationResult.ok()
                : VerificationResult.of(v);
        return new PlanVerifier() {
            @Override public String name() { return name; }
            @Override public int priority() { return priority; }
            @Override public VerificationResult verify(Workflow w, Policy p, ToolRegistry r) {
                return result;
            }
        };
    }

    /**
     * Stub that overrides only {@code generate(AgentExecutionContext, Duration)}
     * (the synchronous overload PlanAndVerify uses) — the streaming
     * {@code execute} entry point is unreachable in these tests so it
     * throws to surface an accidental wiring change loudly.
     */
    private static final class StubAgentRuntime implements AgentRuntime {
        private final Function<AgentExecutionContext, String> handler;

        StubAgentRuntime(Function<AgentExecutionContext, String> handler) {
            this.handler = handler;
        }

        @Override public String name() { return "stub"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { /* no-op */ }
        @Override public Set<AiCapability> capabilities() {
            return Collections.emptySet();
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            throw new UnsupportedOperationException(
                    "StubAgentRuntime does not implement streaming — "
                            + "PlanAndVerify must call generate() in plan mode");
        }

        @Override
        public String generate(AgentExecutionContext context) {
            return handler.apply(context);
        }

        @Override
        public String generate(AgentExecutionContext context, Duration timeout) {
            return handler.apply(context);
        }
    }
}
