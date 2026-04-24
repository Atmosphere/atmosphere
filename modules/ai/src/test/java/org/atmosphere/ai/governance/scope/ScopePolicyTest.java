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

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopePolicyTest {

    @Test
    void admitsInScopeRequests() {
        var policy = policy(AgentScope.Breach.POLITE_REDIRECT);
        var decision = policy.evaluate(PolicyContext.preAdmission(
                new AiRequest("where is my order?")));
        assertInstanceOf(PolicyDecision.Admit.class, decision);
    }

    @Test
    void politeRedirectRewritesMessage() {
        var policy = policy(AgentScope.Breach.POLITE_REDIRECT);
        var decision = policy.evaluate(PolicyContext.preAdmission(
                new AiRequest("write python code")));
        var transform = assertInstanceOf(PolicyDecision.Transform.class, decision);
        assertEquals("I can only help with orders.", transform.modifiedRequest().message());
        assertEquals("I can only help with orders.",
                transform.modifiedRequest().metadata().get(ScopePolicy.REDIRECT_METADATA_KEY));
    }

    @Test
    void denyBreachProducesDeny() {
        var policy = policy(AgentScope.Breach.DENY);
        var decision = policy.evaluate(PolicyContext.preAdmission(
                new AiRequest("please diagnose my symptoms")));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, decision);
        assertTrue(deny.reason().toLowerCase().contains("diagnose")
                        || deny.reason().toLowerCase().contains("hijacking"),
                "deny reason should reference the matched probe: " + deny.reason());
    }

    @Test
    void postResponsePhaseIsAdmitted() {
        // Post-response check is wired in a follow-up commit; for now the
        // policy must pass through response-phase evaluation unchanged so
        // chained guardrails still see the response.
        var policy = policy(AgentScope.Breach.DENY);
        var decision = policy.evaluate(PolicyContext.postResponse(
                new AiRequest("ok"), "here is your order status"));
        assertInstanceOf(PolicyDecision.Admit.class, decision);
    }

    @Test
    void unrestrictedConfigAlwaysAdmits() {
        var config = new ScopeConfig(
                "", List.of(), AgentScope.Breach.DENY, "",
                AgentScope.Tier.RULE_BASED, 0.45, false, true,
                "LLM playground");
        var policy = new ScopePolicy("playground", "code:test", "1.0",
                config, new RuleBasedScopeGuardrail());
        var decision = policy.evaluate(PolicyContext.preAdmission(
                new AiRequest("write python code")));
        assertInstanceOf(PolicyDecision.Admit.class, decision);
    }

    @Test
    void throwingGuardrailFailsClosed() {
        var policy = new ScopePolicy("boom", "code:test", "1.0",
                new ScopeConfig("x", List.of(), AgentScope.Breach.DENY, "",
                        AgentScope.Tier.RULE_BASED, 0.45, false, false, ""),
                new ThrowingGuardrail());
        var decision = policy.evaluate(PolicyContext.preAdmission(new AiRequest("hi")));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, decision);
        assertTrue(deny.reason().toLowerCase().contains("scope check failed"),
                "fail-closed should surface via deny reason: " + deny.reason());
    }

    @Test
    void tierMismatchRejected() {
        var config = new ScopeConfig(
                "x", List.of(), AgentScope.Breach.DENY, "",
                AgentScope.Tier.EMBEDDING_SIMILARITY, 0.45, false, false, "");
        assertThrows(IllegalArgumentException.class, () ->
                new ScopePolicy("x", "code:test", "1.0",
                        config, new RuleBasedScopeGuardrail()));
    }

    @Test
    void rejectsBlankPurposeOnRestrictedConfig() {
        assertThrows(IllegalArgumentException.class, () -> new ScopeConfig(
                "", List.of(), AgentScope.Breach.DENY, "",
                AgentScope.Tier.RULE_BASED, 0.45, false, false, ""));
    }

    @Test
    void rejectsUnrestrictedWithoutJustification() {
        assertThrows(IllegalArgumentException.class, () -> new ScopeConfig(
                "", List.of(), AgentScope.Breach.DENY, "",
                AgentScope.Tier.RULE_BASED, 0.45, false, true, ""));
    }

    @Test
    void configFromAnnotationRoundtrips() {
        @AgentScope(
                purpose = "orders only",
                forbiddenTopics = {"code"},
                onBreach = AgentScope.Breach.DENY,
                redirectMessage = "stay on topic",
                tier = AgentScope.Tier.RULE_BASED,
                similarityThreshold = 0.6,
                postResponseCheck = true)
        class Annotated { }

        var annotation = Annotated.class.getAnnotation(AgentScope.class);
        var config = ScopeConfig.fromAnnotation(annotation);
        assertEquals("orders only", config.purpose());
        assertEquals(List.of("code"), config.forbiddenTopics());
        assertEquals(AgentScope.Breach.DENY, config.onBreach());
        assertEquals("stay on topic", config.redirectMessage());
        assertEquals(AgentScope.Tier.RULE_BASED, config.tier());
        assertEquals(0.6, config.similarityThreshold());
        assertTrue(config.postResponseCheck());
    }

    private static ScopePolicy policy(AgentScope.Breach breach) {
        var config = new ScopeConfig(
                "Customer support — orders, billing, account",
                List.of(),
                breach,
                "I can only help with orders.",
                AgentScope.Tier.RULE_BASED,
                0.45,
                false, false, "");
        return new ScopePolicy("support-scope", "code:test", "1.0",
                config, new RuleBasedScopeGuardrail());
    }

    private static final class ThrowingGuardrail implements ScopeGuardrail {
        @Override public AgentScope.Tier tier() { return AgentScope.Tier.RULE_BASED; }
        @Override public Decision evaluate(AiRequest request, ScopeConfig config) {
            throw new RuntimeException("boom");
        }
    }
}
