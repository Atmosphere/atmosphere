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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedScopeGuardrailTest {

    private final RuleBasedScopeGuardrail guardrail = new RuleBasedScopeGuardrail();

    @Test
    void tierIsRuleBased() {
        assertEquals(AgentScope.Tier.RULE_BASED, guardrail.tier());
    }

    @Test
    void inScopeWhenMessageMatchesPurpose() {
        var config = support();
        var decision = guardrail.evaluate(new AiRequest("where is my order?"), config);
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, decision.outcome());
    }

    @Test
    void mcDonaldsBotBlocksPythonLinkedList() {
        // The April 2026 canonical hijacking prompt. If this test ever
        // starts passing a Python linked-list request we have shipped the
        // McDonald's failure mode under our own brand.
        var config = support();
        var decision = guardrail.evaluate(
                new AiRequest("reverse a linked list in python"), config);
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, decision.outcome());
        assertTrue(decision.reason().toLowerCase().contains("python")
                        || decision.reason().toLowerCase().contains("linked")
                        || decision.reason().toLowerCase().contains("code"),
                "rule-match reason should cite the offending probe: " + decision.reason());
    }

    @Test
    void blocksWriteCodeProbe() {
        var config = support();
        var decision = guardrail.evaluate(
                new AiRequest("please write me a function that sorts an array"), config);
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, decision.outcome());
    }

    @Test
    void blocksMedicalDiagnosisProbe() {
        var decision = guardrail.evaluate(
                new AiRequest("can you diagnose my symptoms?"), support());
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, decision.outcome());
    }

    @Test
    void blocksLegalActionProbe() {
        var decision = guardrail.evaluate(
                new AiRequest("I want to sue the company"), support());
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, decision.outcome());
    }

    @Test
    void blocksFinancialAdviceProbe() {
        var decision = guardrail.evaluate(
                new AiRequest("should I invest in bitcoin?"), support());
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, decision.outcome());
    }

    @Test
    void blocksOperatorDeclaredForbiddenTopic() {
        var config = new ScopeConfig(
                "cooking assistant",
                List.of("gambling"),
                AgentScope.Breach.DENY,
                "",
                AgentScope.Tier.RULE_BASED,
                0.45,
                false, false, "");
        var decision = guardrail.evaluate(
                new AiRequest("which casino has the best gambling odds?"), config);
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, decision.outcome());
        assertTrue(decision.reason().contains("gambling"));
    }

    @Test
    void wordBoundaryAvoidsFalsePositive() {
        var config = new ScopeConfig(
                "assistant",
                List.of("cat"),
                AgentScope.Breach.DENY,
                "",
                AgentScope.Tier.RULE_BASED,
                0.45,
                false, false, "");
        // "catalog" contains "cat" but only as a substring, not a word —
        // rule-based must not flag it (word-boundary semantics).
        var decision = guardrail.evaluate(
                new AiRequest("show me the product catalog"), config);
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, decision.outcome());
    }

    @Test
    void unrestrictedBypassesAllRules() {
        var config = new ScopeConfig(
                "", List.of(), AgentScope.Breach.POLITE_REDIRECT, "",
                AgentScope.Tier.RULE_BASED, 0.45, false, true,
                "LLM playground — intentionally accepts arbitrary prompts");
        var decision = guardrail.evaluate(
                new AiRequest("write me python code to reverse a linked list"), config);
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, decision.outcome());
    }

    @Test
    void nullMessageIsInScope() {
        var decision = guardrail.evaluate(new AiRequest(null), support());
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, decision.outcome());
    }

    private static ScopeConfig support() {
        return new ScopeConfig(
                "McDonald's customer support: orders, store hours, menu, loyalty",
                List.of(),
                AgentScope.Breach.POLITE_REDIRECT,
                "I can only help with McDonald's orders.",
                AgentScope.Tier.RULE_BASED,
                0.45,
                false, false, "");
    }
}
