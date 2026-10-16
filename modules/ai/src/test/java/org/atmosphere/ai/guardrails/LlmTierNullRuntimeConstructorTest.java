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
package org.atmosphere.ai.guardrails;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.decision.DecisionModelResolver;
import org.atmosphere.ai.decision.TestDecisionModels;
import org.atmosphere.ai.governance.rag.InjectionClassifier;
import org.atmosphere.ai.governance.rag.LlmClassifierInjectionClassifier;
import org.atmosphere.ai.governance.scope.LlmClassifierScopeGuardrail;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopeGuardrail;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-argument {@code (AgentRuntime)} constructor of each LLM tier is
 * documented to take {@code null}, which resolves the decision model through
 * {@link DecisionModelResolver}: a bare {@code null} literal must keep
 * compiling (no other one-argument constructor may make it ambiguous) and keep
 * asking the resolved model.
 */
class LlmTierNullRuntimeConstructorTest {

    @Test
    void nullRuntimeResolvesTheDecisionModelThroughTheResolver() {
        var asked = new AtomicInteger();
        TestDecisionModels.reset();
        TestDecisionModels.Preferred.available = true;
        TestDecisionModels.Preferred.behaviour = request -> {
            asked.incrementAndGet();
            return TestDecisionModels.measured(request, 1.0);
        };
        DecisionModelResolver.reset();
        try {
            var moderation = new LlmModerationDetector(null).detect("some text to moderate");
            assertTrue(moderation.isFlagged() && !moderation.errored(),
                    "the resolved model answered every category true: " + moderation);

            var scope = new LlmClassifierScopeGuardrail(null).evaluate(new AiRequest("reverse a linked list"),
                    new ScopeConfig("customer support for orders and billing", List.of(),
                            AgentScope.Breach.DENY, "", AgentScope.Tier.LLM_CLASSIFIER, 0.45, false, false, ""));
            assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, scope.outcome(), scope.reason());

            var injection = new LlmClassifierInjectionClassifier(null).evaluate(
                    new ContextProvider.Document("Parcels ship in two days.", "faq", 1.0));
            assertEquals(InjectionClassifier.Outcome.INJECTED, injection.outcome(), injection.reason());

            assertEquals(3, asked.get(), "each tier must have asked the resolved decision model once");
        } finally {
            TestDecisionModels.reset();
        }
    }
}
