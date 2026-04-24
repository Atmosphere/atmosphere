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
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-request {@link ScopePolicy} installation — an interceptor writing a
 * {@link ScopeConfig} under {@link ScopePolicy#REQUEST_SCOPE_METADATA_KEY}
 * in the request metadata must cause the pipeline to:
 *
 * <ol>
 *   <li>prepend a confinement preamble matching the per-request scope;</li>
 *   <li>short-circuit the runtime when the guardrail rejects the turn; and</li>
 *   <li>strip the governance-internal metadata key before the runtime sees
 *       the request (so the governance surface does not leak to providers).</li>
 * </ol>
 */
class AiPipelineRequestScopeTest {

    private static final ScopeConfig MATH_SCOPE = new ScopeConfig(
            "Mathematics tutoring — arithmetic, algebra, calculus, geometry",
            List.of("writing source code", "programming tutorials"),
            AgentScope.Breach.DENY, "",
            AgentScope.Tier.RULE_BASED, 0.45,
            false, false, "");

    @Test
    void requestScopeHardensSystemPrompt() {
        var capturedPrompt = new AtomicReference<String>();
        var runtime = new CapturingRuntime(capturedPrompt, new AtomicReference<>(),
                new AtomicReference<>(), new AtomicBoolean());

        var pipeline = new AiPipeline(runtime, "You are friendly.", null,
                null, null, List.of(), List.of(), List.of(), null, null);
        pipeline.execute("c1", "what's 2 + 2?",
                new CollectingSession("math-in"),
                Map.of(ScopePolicy.REQUEST_SCOPE_METADATA_KEY, MATH_SCOPE));

        var prompt = capturedPrompt.get();
        assertNotNull(prompt);
        assertTrue(prompt.contains("Scope confinement"),
                "per-request scope must harden the system prompt: " + prompt);
        assertTrue(prompt.contains("Mathematics tutoring"),
                "declared per-request purpose must surface: " + prompt);
        assertTrue(prompt.contains("You are friendly."),
                "developer system prompt must survive hardening: " + prompt);
    }

    @Test
    void requestScopeDeniesDriftedPromptBeforeRuntime() {
        var runtimeInvoked = new AtomicBoolean(false);
        var runtime = new CapturingRuntime(new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>(), runtimeInvoked);
        var pipeline = new AiPipeline(runtime, "", null,
                null, null, List.of(), List.of(), List.of(), null, null);

        var session = new CollectingSession("math-deny");
        pipeline.execute("c1",
                "write python code to sort an array",
                session,
                Map.of(ScopePolicy.REQUEST_SCOPE_METADATA_KEY, MATH_SCOPE));

        assertTrue(session.failed(),
                "drifted prompt must surface as a session error");
        assertFalse(runtimeInvoked.get(),
                "runtime must NOT run when per-request scope denies the turn");
    }

    @Test
    void requestScopeKeyStrippedFromRuntimeMetadata() {
        var capturedMeta = new AtomicReference<Map<String, Object>>();
        var runtime = new CapturingRuntime(new AtomicReference<>(), new AtomicReference<>(),
                capturedMeta, new AtomicBoolean());
        var pipeline = new AiPipeline(runtime, "", null, null, null, List.of(),
                List.of(), List.of(), null, null);

        pipeline.execute("c1", "what's 2 + 2?",
                new CollectingSession("meta-strip"),
                Map.of(ScopePolicy.REQUEST_SCOPE_METADATA_KEY, MATH_SCOPE));

        var meta = capturedMeta.get();
        assertNotNull(meta);
        assertNull(meta.get(ScopePolicy.REQUEST_SCOPE_METADATA_KEY),
                "governance-internal key must not leak to the provider: " + meta);
    }

    @Test
    void unrestrictedRequestScopeShortCircuitsToAdmit() {
        // An unrestricted config carries no enforcement — the per-request
        // ScopePolicy must not be built and the system prompt must stay
        // exactly as the caller provided.
        var capturedPrompt = new AtomicReference<String>();
        var runtime = new CapturingRuntime(capturedPrompt, new AtomicReference<>(),
                new AtomicReference<>(), new AtomicBoolean());
        var unrestricted = new ScopeConfig(
                "", List.of(), AgentScope.Breach.DENY, "",
                AgentScope.Tier.RULE_BASED, 0.45,
                false, true, "sandbox");
        var pipeline = new AiPipeline(runtime, "You are friendly.", null,
                null, null, List.of(), List.of(), List.of(), null, null);
        pipeline.execute("c1", "write python code",
                new CollectingSession("unrestricted-scope"),
                Map.of(ScopePolicy.REQUEST_SCOPE_METADATA_KEY, unrestricted));

        assertTrue("You are friendly.".equals(capturedPrompt.get()),
                "unrestricted per-request scope must not harden: " + capturedPrompt.get());
    }

    @Test
    void nonScopeConfigValueUnderKeyIgnored() {
        // A caller mistake (wrong type under the metadata key) must not
        // blow up the pipeline — the invalid entry is logged and the turn
        // proceeds without per-request scope enforcement.
        var capturedPrompt = new AtomicReference<String>();
        var runtime = new CapturingRuntime(capturedPrompt, new AtomicReference<>(),
                new AtomicReference<>(), new AtomicBoolean());
        var pipeline = new AiPipeline(runtime, "You are friendly.", null,
                null, null, List.of(), List.of(), List.of(), null, null);

        pipeline.execute("c1", "hi",
                new CollectingSession("wrong-type"),
                Map.of(ScopePolicy.REQUEST_SCOPE_METADATA_KEY, "not-a-scope-config"));

        assertTrue("You are friendly.".equals(capturedPrompt.get()),
                "invalid metadata value must be ignored, not thrown: " + capturedPrompt.get());
    }

    /**
     * Captures system prompt, message, metadata, and whether execute was
     * invoked so each test can assert the one it cares about without
     * spinning up per-test runtime classes.
     */
    private static final class CapturingRuntime implements AgentRuntime {
        private final AtomicReference<String> prompt;
        private final AtomicReference<String> message;
        private final AtomicReference<Map<String, Object>> metadata;
        private final AtomicBoolean invoked;

        CapturingRuntime(AtomicReference<String> prompt,
                         AtomicReference<String> message,
                         AtomicReference<Map<String, Object>> metadata,
                         AtomicBoolean invoked) {
            this.prompt = prompt;
            this.message = message;
            this.metadata = metadata;
            this.invoked = invoked;
        }

        @Override public String name() { return "capturing"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            invoked.set(true);
            prompt.set(context.systemPrompt());
            message.set(context.message());
            metadata.set(context.metadata());
            session.complete();
        }
    }
}
