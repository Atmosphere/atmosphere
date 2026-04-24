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
package org.atmosphere.ai.governance;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.scope.RuleBasedScopeGuardrail;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring test: every {@code ScopePolicy.evaluate()} call must emit one
 * similarity sample through the installed {@link GovernanceMetrics}. The
 * sample carries {@code policy}, {@code tier}, and {@code decision} so
 * operators can slice the histogram by any of them. Drifts here break the
 * admin-console histogram view that ships in a follow-up commit — keep the
 * expected key set in lock-step with {@link GovernanceMetrics}.
 */
class GovernanceMetricsTest {

    private CapturingMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new CapturingMetrics();
        GovernanceMetricsHolder.install(metrics);
    }

    @AfterEach
    void tearDown() {
        GovernanceMetricsHolder.reset();
    }

    @Test
    void admitEmitsOneSampleWithAdmitDecision() {
        var policy = scopePolicy();
        var decision = policy.evaluate(PolicyContext.preAdmission(
                new AiRequest("where is my order?")));
        assertTrue(decision instanceof PolicyDecision.Admit);
        assertEquals(1, metrics.samples.size());
        var sample = metrics.samples.peek();
        assertEquals("scope::Support", sample.policyName);
        assertEquals(AgentScope.Tier.RULE_BASED, sample.tier);
        assertEquals("admit", sample.decision);
    }

    @Test
    void denyEmitsOneSampleWithDenyDecision() {
        var policy = scopePolicy(AgentScope.Breach.DENY);
        var decision = policy.evaluate(PolicyContext.preAdmission(
                new AiRequest("write python code to sort an array")));
        assertTrue(decision instanceof PolicyDecision.Deny);
        assertEquals(1, metrics.samples.size());
        assertEquals("deny", metrics.samples.peek().decision);
    }

    @Test
    void transformEmitsOneSampleWithTransformDecision() {
        var policy = scopePolicy(AgentScope.Breach.POLITE_REDIRECT);
        var decision = policy.evaluate(PolicyContext.preAdmission(
                new AiRequest("write python code to sort an array")));
        assertTrue(decision instanceof PolicyDecision.Transform);
        assertEquals(1, metrics.samples.size());
        assertEquals("transform", metrics.samples.peek().decision);
    }

    @Test
    void postResponseEmitsSampleForInScopeResponseToo() {
        var policy = postResponseScope(AgentScope.Breach.DENY);
        var decision = policy.evaluate(PolicyContext.postResponse(
                new AiRequest("where is my order?"),
                "Your order ships tomorrow from the Omaha warehouse."));
        assertTrue(decision instanceof PolicyDecision.Admit);
        assertEquals(1, metrics.samples.size(),
                "post-response IN_SCOPE must still record the sample — operators "
                        + "need the full histogram to calibrate thresholds, not just the outliers");
        assertEquals("admit", metrics.samples.peek().decision);
    }

    @Test
    void postResponseDriftEmitsSampleWithDenyDecision() {
        var policy = postResponseScope(AgentScope.Breach.DENY);
        var decision = policy.evaluate(PolicyContext.postResponse(
                new AiRequest("where is my order?"),
                "here is python code: def foo(): return 42"));
        assertTrue(decision instanceof PolicyDecision.Deny);
        assertEquals(1, metrics.samples.size());
        assertEquals("deny", metrics.samples.peek().decision);
    }

    @Test
    void unrestrictedScopeEmitsNothing() {
        // Unrestricted policies short-circuit to admit without running the
        // guardrail; there is no similarity score to emit.
        var config = new ScopeConfig(
                "", List.of(), AgentScope.Breach.DENY, "",
                AgentScope.Tier.RULE_BASED, 0.45, false, true, "playground");
        var policy = new ScopePolicy("playground", "code:test", "1.0", config,
                new RuleBasedScopeGuardrail());
        policy.evaluate(PolicyContext.preAdmission(new AiRequest("anything")));
        assertEquals(0, metrics.samples.size());
    }

    private static ScopePolicy scopePolicy() {
        return scopePolicy(AgentScope.Breach.DENY);
    }

    private static ScopePolicy scopePolicy(AgentScope.Breach breach) {
        var config = new ScopeConfig(
                "Customer support — orders, billing, account",
                List.of(), breach, "I can only help with orders.",
                AgentScope.Tier.RULE_BASED, 0.45, false, false, "");
        return new ScopePolicy("scope::Support", "code:test", "1.0",
                config, new RuleBasedScopeGuardrail());
    }

    private static ScopePolicy postResponseScope(AgentScope.Breach breach) {
        var config = new ScopeConfig(
                "Customer support — orders, billing, account",
                List.of(), breach, "",
                AgentScope.Tier.RULE_BASED, 0.45, true, false, "");
        return new ScopePolicy("scope::Support", "code:test", "1.0",
                config, new RuleBasedScopeGuardrail());
    }

    private static final class CapturingMetrics implements GovernanceMetrics {
        final ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();

        @Override
        public void recordSimilarity(String policyName, AgentScope.Tier tier,
                                      String decision, double similarity) {
            samples.add(new Sample(policyName, tier, decision, similarity));
        }

        @Override
        public void recordEvaluationLatency(String policyName, String decision,
                                             double evaluationMs) { }
    }

    private record Sample(String policyName, AgentScope.Tier tier, String decision,
                          double similarity) { }
}
