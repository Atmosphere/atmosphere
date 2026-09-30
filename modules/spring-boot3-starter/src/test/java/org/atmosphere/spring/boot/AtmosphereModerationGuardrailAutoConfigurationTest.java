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
package org.atmosphere.spring.boot;

import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.decision.DecisionModelResolver;
import org.atmosphere.ai.guardrails.ModerationGuardrail;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code detector=llm} posture of the Spring Boot 3 moderation bean:
 * an LLM verdict the detector cannot obtain blocks the turn by default, and
 * only {@code atmosphere.ai.guardrails.moderation.fail-open=true} admits it.
 */
class AtmosphereModerationGuardrailAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAiAutoConfiguration.class))
            .withPropertyValues("atmosphere.ai.guardrails.moderation.enabled=true",
                    "atmosphere.ai.guardrails.moderation.detector=llm",
                    // Fake mode: only the demo runtime, so no model can answer.
                    "atmosphere.ai.mode=fake");

    private static AiRequest req(String message) {
        return new AiRequest(message, null, null, null, null, null, null,
                java.util.Map.of(), java.util.List.of());
    }

    @Test
    void llmDetectorFailsClosedWhenNoModelCanAnswer() {
        forgetResolvedModels();
        try {
            contextRunner.run(context -> {
                var guardrail = context.getBean(ModerationGuardrail.class);
                assertThat(guardrail.inspectRequest(req("what time does the store open?")))
                        .as("an undecided LLM moderation verdict must block by default")
                        .isInstanceOf(AiGuardrail.GuardrailResult.Block.class);
            });
        } finally {
            forgetResolvedModels();
        }
    }

    @Test
    void llmDetectorAdmitsAnUndecidedVerdictOnlyWithFailOpen() {
        forgetResolvedModels();
        try {
            contextRunner
                    .withPropertyValues("atmosphere.ai.guardrails.moderation.fail-open=true")
                    .run(context -> {
                        var guardrail = context.getBean(ModerationGuardrail.class);
                        assertThat(guardrail.inspectRequest(req("what time does the store open?")))
                                .isInstanceOf(AiGuardrail.GuardrailResult.Pass.class);
                    });
        } finally {
            forgetResolvedModels();
        }
    }

    /**
     * Drop any decision model or explicit client an earlier test in this JVM
     * resolved, so the fake-mode settings the context writes are what resolve.
     */
    private static void forgetResolvedModels() {
        AgentRuntimeResolver.clearExplicitClientBinding();
        AgentRuntimeResolver.reset();
        DecisionModelResolver.reset();
    }
}
