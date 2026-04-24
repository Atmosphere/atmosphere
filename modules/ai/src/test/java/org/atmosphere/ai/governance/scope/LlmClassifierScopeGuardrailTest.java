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
package org.atmosphere.ai.governance.scope;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmClassifierScopeGuardrailTest {

    @Test
    void tierIsLlmClassifier() {
        assertEquals(AgentScope.Tier.LLM_CLASSIFIER,
                new LlmClassifierScopeGuardrail(null).tier());
    }

    @Test
    void yesResponseAdmits() {
        var runtime = new CannedRuntime("YES");
        var decision = new LlmClassifierScopeGuardrail(runtime)
                .evaluate(new AiRequest("where is my order?"), supportConfig());
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, decision.outcome());
    }

    @Test
    void noResponseRejects() {
        var runtime = new CannedRuntime("NO");
        var decision = new LlmClassifierScopeGuardrail(runtime)
                .evaluate(new AiRequest("reverse a linked list in python"), supportConfig());
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, decision.outcome());
        assertTrue(decision.reason().toLowerCase().contains("off-topic")
                        || decision.reason().toLowerCase().contains("rejected"),
                "reason should cite classifier verdict: " + decision.reason());
    }

    @Test
    void markdownWrappedResponseParsesCorrectly() {
        // Real LLMs sometimes emit **YES** or "YES." or "YES!"
        assertEquals(LlmClassifierScopeGuardrail.Verdict.YES,
                LlmClassifierScopeGuardrail.parseFirstWord("**YES**"));
        assertEquals(LlmClassifierScopeGuardrail.Verdict.YES,
                LlmClassifierScopeGuardrail.parseFirstWord("YES."));
        assertEquals(LlmClassifierScopeGuardrail.Verdict.NO,
                LlmClassifierScopeGuardrail.parseFirstWord("NO - this is off-topic"));
        assertEquals(LlmClassifierScopeGuardrail.Verdict.NO,
                LlmClassifierScopeGuardrail.parseFirstWord("  *no*  "));
        // "Not sure" starts with "not" but should NOT map to NO
        assertEquals(LlmClassifierScopeGuardrail.Verdict.AMBIGUOUS,
                LlmClassifierScopeGuardrail.parseFirstWord("not sure"));
        assertEquals(LlmClassifierScopeGuardrail.Verdict.AMBIGUOUS,
                LlmClassifierScopeGuardrail.parseFirstWord("I cannot determine"));
    }

    @Test
    void ambiguousResponseAdmitsWithDebugLog() {
        var runtime = new CannedRuntime("I am not sure whether this is in scope.");
        var decision = new LlmClassifierScopeGuardrail(runtime)
                .evaluate(new AiRequest("something"), supportConfig());
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, decision.outcome(),
                "ambiguous verdicts fall through to admit");
    }

    @Test
    void emptyResponseAdmits() {
        var runtime = new CannedRuntime("");
        var decision = new LlmClassifierScopeGuardrail(runtime)
                .evaluate(new AiRequest("hi"), supportConfig());
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, decision.outcome());
    }

    @Test
    void runtimeFailureReportsError() {
        var runtime = new ThrowingRuntime();
        var decision = new LlmClassifierScopeGuardrail(runtime)
                .evaluate(new AiRequest("hi"), supportConfig());
        assertEquals(ScopeGuardrail.Outcome.ERROR, decision.outcome());
        assertTrue(decision.reason().toLowerCase().contains("classifier"),
                "error reason should mention the classifier: " + decision.reason());
    }

    @Test
    void unrestrictedBypassesClassifier() {
        var runtime = new CannedRuntime("NO");  // would reject if called
        var config = new ScopeConfig(
                "", List.of(), AgentScope.Breach.DENY, "",
                AgentScope.Tier.LLM_CLASSIFIER, 0.45, false,
                true, "LLM playground");
        var decision = new LlmClassifierScopeGuardrail(runtime)
                .evaluate(new AiRequest("anything"), config);
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, decision.outcome());
    }

    @Test
    void noRuntimeAdmitsWithWarning() {
        // Explicit null — the resolver returns no runtime; the guardrail
        // admits with a warning rather than failing every request hard.
        var decision = new LlmClassifierScopeGuardrail((AgentRuntime) null)
                .evaluate(new AiRequest("hi"), supportConfig());
        // If the resolver happens to find one (non-deterministic in this
        // repo given the ServiceLoader setup), the test tolerates both
        // IN_SCOPE outcomes since the documented behavior is "admit when
        // runtime is absent OR when verdict is yes."
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, decision.outcome());
    }

    @Test
    void classifierSeesPurposeAndForbiddenTopicsInSystemPrompt() {
        var capturedSystemPrompt = new AtomicReference<String>();
        var runtime = new CapturingRuntime(capturedSystemPrompt, "YES");
        var config = new ScopeConfig(
                "order support",
                List.of("medical", "legal"),
                AgentScope.Breach.DENY, "",
                AgentScope.Tier.LLM_CLASSIFIER, 0.45, false, false, "");
        new LlmClassifierScopeGuardrail(runtime).evaluate(new AiRequest("hi"), config);

        var systemPrompt = capturedSystemPrompt.get();
        assertTrue(systemPrompt.contains("order support"),
                "purpose must be in classifier system prompt: " + systemPrompt);
        assertTrue(systemPrompt.contains("- medical"),
                "forbidden topic must surface: " + systemPrompt);
        assertTrue(systemPrompt.contains("- legal"),
                "forbidden topic must surface: " + systemPrompt);
        assertTrue(systemPrompt.contains("YES") || systemPrompt.contains("NO"),
                "classifier must be instructed to answer YES/NO: " + systemPrompt);
    }

    private static ScopeConfig supportConfig() {
        return new ScopeConfig(
                "customer support for orders and billing",
                List.of(),
                AgentScope.Breach.DENY, "",
                AgentScope.Tier.LLM_CLASSIFIER, 0.45, false, false, "");
    }

    // --- stub runtimes ---------------------------------------------------

    private static class CannedRuntime implements AgentRuntime {
        private final String response;

        CannedRuntime(String response) { this.response = response; }

        @Override public String name() { return "canned"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.send(response);
            session.complete();
        }
    }

    private static class CapturingRuntime extends CannedRuntime {
        private final AtomicReference<String> capture;

        CapturingRuntime(AtomicReference<String> capture, String response) {
            super(response);
            this.capture = capture;
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            capture.set(context.systemPrompt());
            super.execute(context, session);
        }
    }

    private static class ThrowingRuntime implements AgentRuntime {
        @Override public String name() { return "boom"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() { return Set.of(); }
        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            throw new RuntimeException("boom");
        }
    }
}
