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
import org.atmosphere.ai.decision.DecisionModelResolver;
import org.atmosphere.ai.decision.DecisionModelResolverTestAccess;
import org.atmosphere.ai.decision.ScriptedDecisionRuntime;
import org.atmosphere.ai.decision.TestDecisionModels;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every terminal path of the LLM scope tier, driven through the real
 * {@link ScopePolicy} admission path: a verdict the classifier could not
 * obtain must deny, never admit.
 */
class LlmClassifierScopeGuardrailFailClosedTest {

    private static final AiRequest REQUEST = new AiRequest("reverse a linked list in python");

    private static final ScopeConfig CONFIG = new ScopeConfig("customer support for orders and billing",
            List.of(), AgentScope.Breach.DENY, "", AgentScope.Tier.LLM_CLASSIFIER, 0.45, false, false, "");

    @Test
    void emptyReplyDenies() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> s.complete());
        assertDenied(new LlmClassifierScopeGuardrail(runtime), "an empty reply");
    }

    @Test
    void unparseableReplyDenies() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) ->
                ScriptedDecisionRuntime.reply(s, "I am not sure whether this is in scope."));
        assertDenied(new LlmClassifierScopeGuardrail(runtime), "an unparseable reply");
    }

    @Test
    void timeoutDenies() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> new CountDownLatch(1).await());
        assertDenied(new LlmClassifierScopeGuardrail(runtime, Duration.ofMillis(200)), "a timeout");
    }

    @Test
    void errorReportedOnTheSessionDenies() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> s.error(new IllegalStateException("model 503")));
        assertDenied(new LlmClassifierScopeGuardrail(runtime), "a runtime error reported on the session");
    }

    @Test
    void errorThrownByTheRuntimeDenies() {
        assertDenied(new LlmClassifierScopeGuardrail(new ThrowingRuntime()), "a runtime error thrown on dispatch");
    }

    /**
     * Only the demo runtime installed (no reachable model): the demo's canned
     * reply is not a verdict.
     */
    @Test
    void noReachableModelDenies() {
        DecisionModelResolverTestAccess.forceDemoOnly();
        try {
            assertDenied(new LlmClassifierScopeGuardrail(), "no reachable model");
        } finally {
            DecisionModelResolverTestAccess.restore();
        }
    }

    /**
     * The production wiring — {@code @AgentScope(tier = LLM_CLASSIFIER)}, a
     * skill file's guardrails and per-request scope metadata all resolve the
     * ServiceLoader-registered guardrail through {@link ScopeGuardrailResolver}
     * — denies when no model can answer, and asks the resolved
     * {@link org.atmosphere.ai.decision.DecisionModel} when one can.
     */
    @Test
    void productionWiringFailsClosedAndAsksTheResolvedDecisionModel() {
        DecisionModelResolverTestAccess.forceDemoOnly();
        ScopeGuardrailResolver.reset();
        try {
            var policy = ScopePolicyBuilder.build(CONFIG, "scope::test", "test");
            assertEquals(AgentScope.Tier.LLM_CLASSIFIER, policy.config().tier());
            assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(PolicyContext.preAdmission(REQUEST)),
                    "no reachable model must deny admission on the annotation / skill path");
            var metadata = new HashMap<String, Object>(Map.of(ScopePolicy.REQUEST_SCOPE_METADATA_KEY, CONFIG));
            var perRequest = ScopePolicyInstaller.extract(metadata);
            assertInstanceOf(PolicyDecision.Deny.class, perRequest.evaluate(PolicyContext.preAdmission(REQUEST)),
                    "no reachable model must deny admission on the per-request scope path");

            TestDecisionModels.Preferred.available = true;
            DecisionModelResolver.reset();
            TestDecisionModels.Preferred.behaviour = r -> TestDecisionModels.measured(r, 0.9);
            var denied = assertInstanceOf(PolicyDecision.Deny.class,
                    policy.evaluate(PolicyContext.preAdmission(REQUEST)));
            assertTrue(denied.reason().contains("off-topic"), denied.reason());
            TestDecisionModels.Preferred.behaviour = r -> TestDecisionModels.measured(r, 0.02);
            assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(PolicyContext.preAdmission(REQUEST)));
        } finally {
            TestDecisionModels.reset();
            DecisionModelResolverTestAccess.restore();
            ScopeGuardrailResolver.reset();
        }
    }

    /** The explicit, non-default opt-out: only the system property makes the wired guardrail admit. */
    @Test
    void productionWiringAdmitsAnUncertainVerdictOnlyWithTheFailOpenProperty() {
        DecisionModelResolverTestAccess.forceDemoOnly();
        System.setProperty(LlmClassifierScopeGuardrail.FAIL_OPEN_PROPERTY, "true");
        ScopeGuardrailResolver.reset();
        try {
            var policy = ScopePolicyBuilder.build(CONFIG, "scope::test", "test");
            assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(PolicyContext.preAdmission(REQUEST)));
        } finally {
            System.clearProperty(LlmClassifierScopeGuardrail.FAIL_OPEN_PROPERTY);
            DecisionModelResolverTestAccess.restore();
            ScopeGuardrailResolver.reset();
        }
    }

    /**
     * The post-response check keeps its documented posture: bytes are already
     * on the wire, so an uncertain verdict there admits (logged), while a
     * flagged one still denies.
     */
    @Test
    void postResponseCheckAdmitsAnUncertainVerdict() {
        var config = new ScopeConfig(CONFIG.purpose(), List.of(), AgentScope.Breach.DENY, "",
                AgentScope.Tier.LLM_CLASSIFIER, 0.45, true, false, "");
        var empty = new ScriptedDecisionRuntime((ctx, s) -> s.complete());
        var policy = new ScopePolicy("scope::test", "test", "1.0", config, new LlmClassifierScopeGuardrail(empty));
        assertInstanceOf(PolicyDecision.Admit.class,
                policy.evaluate(PolicyContext.postResponse(REQUEST, "some streamed answer")));
    }

    private static void assertDenied(LlmClassifierScopeGuardrail guardrail, String path) {
        assertEquals(ScopeGuardrail.Outcome.ERROR, guardrail.evaluate(REQUEST, CONFIG).outcome(),
                path + " must not classify the request");
        var policy = new ScopePolicy("scope::test", "test", "1.0", CONFIG, guardrail);
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(PolicyContext.preAdmission(REQUEST)),
                path + " must deny admission");
    }

    /** Throws from the dispatch itself instead of reporting on the session. */
    private static final class ThrowingRuntime implements AgentRuntime {
        @Override public String name() { return "throwing"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() { return Set.of(AiCapability.TEXT_STREAMING); }
        @Override public void execute(AgentExecutionContext context, StreamingSession session) {
            throw new IllegalStateException("model 503");
        }
    }
}
