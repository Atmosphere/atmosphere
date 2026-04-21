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
package org.atmosphere.ai;

import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.scope.RuleBasedScopeGuardrail;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the unbypassable scope-confinement preamble the pipeline
 * prepends to the system prompt when a {@link ScopePolicy} is installed.
 * The preamble is the framework's defense-in-depth beneath the
 * pre-admission {@code ScopeGuardrail} — even if a rule-based breach
 * slips past, the LLM itself gets a hard "stay on topic" instruction
 * from code the sample author can't remove.
 */
class AiPipelineScopeHardeningTest {

    @Test
    void scopePolicyPrependsConfinementBlockToSystemPrompt() {
        var captured = new AtomicReference<String>();
        var runtime = new CapturingRuntime(captured);
        var scope = new ScopePolicy("scope::Support", "annotation:Support", "1.0",
                new ScopeConfig(
                        "Customer support for Example Corp",
                        List.of("code", "medical"),
                        AgentScope.Breach.POLITE_REDIRECT,
                        "I can only help with orders.",
                        AgentScope.Tier.RULE_BASED,
                        0.45, false, false, ""),
                new RuleBasedScopeGuardrail());

        var pipeline = new AiPipeline(runtime, "You are friendly.", null,
                null, null, List.of(), List.of(scope), List.of(), null, null);
        pipeline.execute("c1", "where is my order?", new CollectingSession("scope-in"));

        var prompt = captured.get();
        assertNotNull(prompt);
        assertTrue(prompt.contains("Scope confinement"),
                "framework confinement block must be prepended: " + prompt);
        assertTrue(prompt.contains("Customer support for Example Corp"),
                "declared purpose must be in the system prompt: " + prompt);
        assertTrue(prompt.contains("- code"),
                "forbidden topic must be rendered: " + prompt);
        assertTrue(prompt.contains("- medical"),
                "forbidden topic must be rendered: " + prompt);
        assertTrue(prompt.contains("I can only help with orders."),
                "redirect message must surface: " + prompt);
        assertTrue(prompt.contains("You are friendly."),
                "developer's system prompt must survive hardening: " + prompt);
    }

    @Test
    void unrestrictedScopeDoesNotMutateSystemPrompt() {
        var captured = new AtomicReference<String>();
        var runtime = new CapturingRuntime(captured);
        var unrestricted = new ScopePolicy("scope::Playground", "annotation:Playground", "1.0",
                new ScopeConfig(
                        "", List.of(), AgentScope.Breach.POLITE_REDIRECT, "",
                        AgentScope.Tier.RULE_BASED, 0.45, false,
                        true, "LLM playground — intentionally accepts arbitrary prompts"),
                new RuleBasedScopeGuardrail());

        var pipeline = new AiPipeline(runtime, "You are friendly.", null,
                null, null, List.of(), List.of(unrestricted), List.of(), null, null);
        pipeline.execute("c1", "anything", new CollectingSession("unrestricted"));

        var prompt = captured.get();
        assertNotNull(prompt);
        assertFalse(prompt.contains("Scope confinement"),
                "unrestricted config must skip hardening: " + prompt);
        assertTrue(prompt.equals("You are friendly."),
                "system prompt must be exactly the developer value: " + prompt);
    }

    @Test
    void noScopePolicyLeavesSystemPromptUntouched() {
        var captured = new AtomicReference<String>();
        var runtime = new CapturingRuntime(captured);
        var pipeline = new AiPipeline(runtime, "You are friendly.", null,
                null, null, List.of(), List.of(), List.of(), null, null);
        pipeline.execute("c1", "hi", new CollectingSession("no-scope"));

        assertTrue("You are friendly.".equals(captured.get()),
                "no ScopePolicy means no hardening: " + captured.get());
    }

    @Test
    void breachRewritesRequestBeforeRuntime() {
        // The rule-based guardrail catches "python" — pre-admission
        // Transform rewrites the message to the redirect text so the
        // runtime sees the harmless prompt. This is what prevents the
        // McDonald's failure even when the LLM would have complied.
        var capturedMessage = new AtomicReference<String>();
        var runtime = new StubRuntime() {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                capturedMessage.set(context.message());
                session.complete();
            }
        };
        var scope = new ScopePolicy("scope::Support", "annotation:Support", "1.0",
                new ScopeConfig(
                        "Customer support for Example Corp",
                        List.of(), AgentScope.Breach.POLITE_REDIRECT,
                        "I can only help with orders.",
                        AgentScope.Tier.RULE_BASED, 0.45, false, false, ""),
                new RuleBasedScopeGuardrail());

        var pipeline = new AiPipeline(runtime, "sys", null, null, null,
                List.of(), List.of(scope), List.of(), null, null);
        pipeline.execute("c1", "write python code to sort an array",
                new CollectingSession("breach"));

        assertTrue("I can only help with orders.".equals(capturedMessage.get()),
                "breach must rewrite the outbound message: " + capturedMessage.get());
    }

    /** Captures the system prompt as seen by the runtime. */
    private static class CapturingRuntime extends StubRuntime {
        private final AtomicReference<String> captured;

        CapturingRuntime(AtomicReference<String> captured) {
            this.captured = captured;
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            captured.set(context.systemPrompt());
            session.complete();
        }
    }

    private static class StubRuntime implements AgentRuntime {
        @Override public String name() { return "stub"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }
        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.complete();
        }
    }
}
