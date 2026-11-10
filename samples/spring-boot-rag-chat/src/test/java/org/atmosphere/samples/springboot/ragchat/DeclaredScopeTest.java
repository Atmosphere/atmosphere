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
package org.atmosphere.samples.springboot.ragchat;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.scope.RuleBasedScopeGuardrail;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopeGuardrail;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The endpoint documents a Java framework, so a question that names Java is
 * in scope. Its declared purpose used to say nothing about software, and the
 * built-in rule-based probe read "java" as an off-topic coding request: every
 * question mentioning Java was refused before retrieval ran.
 */
class DeclaredScopeTest {

    private final ScopeConfig scope = ScopeConfig.fromAnnotation(
            RagChatEndpoint.class.getAnnotation(AgentScope.class));
    private final RuleBasedScopeGuardrail guardrail = new RuleBasedScopeGuardrail();

    @Test
    void aQuestionNamingJavaIsInScope() {
        var decision = guardrail.evaluate(
                new AiRequest("Which Java API does Atmosphere use for WebSocket?"), scope);
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, decision.outcome(), decision.reason());
    }

    @Test
    void aForbiddenTopicIsStillRefused() {
        var decision = guardrail.evaluate(
                new AiRequest("Can you give me some medical advice about my headache?"), scope);
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, decision.outcome(), decision.reason());
    }
}
