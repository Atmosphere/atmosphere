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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopePostResponseCheckTest {

    @Test
    void admitsWhenPostResponseCheckDisabled() {
        var policy = build(false, AgentScope.Breach.DENY);
        var decision = policy.evaluate(PolicyContext.postResponse(
                new AiRequest("hi"),
                "here is python code: def foo(): return 42"));
        assertInstanceOf(PolicyDecision.Admit.class, decision,
                "postResponseCheck=false must admit even off-topic responses");
    }

    @Test
    void deniesDriftedResponseWhenCheckEnabled() {
        var policy = build(true, AgentScope.Breach.DENY);
        // The rule-based guardrail catches "python code" in the response.
        var decision = policy.evaluate(PolicyContext.postResponse(
                new AiRequest("hi"),
                "here is python code: def foo(): return 42"));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, decision);
        assertTrue(deny.reason().startsWith("post-response:"),
                "deny reason must prefix with post-response: " + deny.reason());
    }

    @Test
    void politeRedirectBreachDowngradesToDenyOnResponsePath() {
        // Response bytes are already on the wire — Transform can't help.
        var policy = build(true, AgentScope.Breach.POLITE_REDIRECT);
        var decision = policy.evaluate(PolicyContext.postResponse(
                new AiRequest("hi"),
                "here is python code: def foo(): return 42"));
        assertInstanceOf(PolicyDecision.Deny.class, decision,
                "POLITE_REDIRECT breach on response phase must degrade to Deny");
    }

    @Test
    void inScopeResponseAdmits() {
        var policy = build(true, AgentScope.Breach.DENY);
        var decision = policy.evaluate(PolicyContext.postResponse(
                new AiRequest("where is my order?"),
                "Your order ships tomorrow from our warehouse in Omaha."));
        assertInstanceOf(PolicyDecision.Admit.class, decision);
    }

    @Test
    void emptyResponseAdmits() {
        var policy = build(true, AgentScope.Breach.DENY);
        var decision = policy.evaluate(PolicyContext.postResponse(
                new AiRequest("hi"), ""));
        assertInstanceOf(PolicyDecision.Admit.class, decision);
    }

    @Test
    void throwingGuardrailFailsOpenOnResponse() {
        // Post-response fails OPEN (unlike pre-admission fail-closed) — bytes
        // are already flowing, denying after the fact is no defense.
        var config = new ScopeConfig(
                "orders only", List.of(), AgentScope.Breach.DENY, "",
                AgentScope.Tier.RULE_BASED, 0.45, true, false, "");
        var policy = new ScopePolicy("boom", "code:test", "1.0", config,
                new ThrowingGuardrail());
        var decision = policy.evaluate(PolicyContext.postResponse(
                new AiRequest("hi"), "anything"));
        assertInstanceOf(PolicyDecision.Admit.class, decision);
    }

    @Test
    void unrestrictedConfigSkipsPostResponseCheck() {
        var config = new ScopeConfig(
                "", List.of(), AgentScope.Breach.DENY, "",
                AgentScope.Tier.RULE_BASED, 0.45, true, true, "playground");
        var policy = new ScopePolicy("playground", "code:test", "1.0", config,
                new RuleBasedScopeGuardrail());
        var decision = policy.evaluate(PolicyContext.postResponse(
                new AiRequest("hi"),
                "here is python code to sort an array"));
        assertInstanceOf(PolicyDecision.Admit.class, decision);
    }

    private static ScopePolicy build(boolean postResponseCheck, AgentScope.Breach breach) {
        var config = new ScopeConfig(
                "Customer support — orders, billing, account",
                List.of(), breach,
                "I can only help with orders.",
                AgentScope.Tier.RULE_BASED, 0.45,
                postResponseCheck, false, "");
        return new ScopePolicy("scope::Support", "code:test", "1.0",
                config, new RuleBasedScopeGuardrail());
    }

    private static final class ThrowingGuardrail implements ScopeGuardrail {
        @Override public AgentScope.Tier tier() { return AgentScope.Tier.RULE_BASED; }
        @Override public Decision evaluate(AiRequest request, ScopeConfig config) {
            throw new RuntimeException("boom");
        }
    }
}
