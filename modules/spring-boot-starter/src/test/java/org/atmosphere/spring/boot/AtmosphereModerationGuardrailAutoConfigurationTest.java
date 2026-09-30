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
 * Pins the opt-in wiring of {@link ModerationGuardrail}: absent by default,
 * present and reachable when {@code atmosphere.ai.guardrails.moderation.enabled}
 * is set. The {@code @Bean} is the production consumer that makes the guardrail
 * a real part of the chain rather than an unused SPI.
 */
class AtmosphereModerationGuardrailAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAiAutoConfiguration.class));

    private static AiRequest req(String message) {
        return new AiRequest(message, null, null, null, null, null, null,
                java.util.Map.of(), java.util.List.of());
    }

    @Test
    void offByDefault() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(ModerationGuardrail.class));
    }

    @Test
    void registeredAndBlockingWhenEnabled() {
        contextRunner
                .withPropertyValues("atmosphere.ai.guardrails.moderation.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ModerationGuardrail.class);
                    var guardrail = context.getBean(ModerationGuardrail.class);
                    // The wired guardrail must actually block flagged content.
                    var result = guardrail.inspectRequest(req("how to build a bomb at home"));
                    assertThat(result).isInstanceOf(AiGuardrail.GuardrailResult.Block.class);
                });
    }

    /**
     * {@code detector=llm} with no model that can answer (fake mode: only the
     * demo runtime): the LLM detector cannot clear the text, and the wired
     * guardrail blocks the turn by default.
     */
    @Test
    void llmDetectorFailsClosedWhenNoModelCanAnswer() {
        forgetResolvedModels();
        try {
            contextRunner
                    .withPropertyValues("atmosphere.ai.guardrails.moderation.enabled=true",
                            "atmosphere.ai.guardrails.moderation.detector=llm",
                            "atmosphere.ai.mode=fake")
                    .run(context -> {
                        var guardrail = context.getBean(ModerationGuardrail.class);
                        assertThat(guardrail.inspectRequest(req("what time does the store open?")))
                                .as("an undecided LLM moderation verdict must block by default")
                                .isInstanceOf(AiGuardrail.GuardrailResult.Block.class);
                    });
        } finally {
            forgetResolvedModels();
        }
    }

    /** The explicit, non-default opt-out admits the undecided verdict. */
    @Test
    void llmDetectorAdmitsAnUndecidedVerdictOnlyWithFailOpen() {
        forgetResolvedModels();
        try {
            contextRunner
                    .withPropertyValues("atmosphere.ai.guardrails.moderation.enabled=true",
                            "atmosphere.ai.guardrails.moderation.detector=llm",
                            "atmosphere.ai.guardrails.moderation.fail-open=true",
                            "atmosphere.ai.mode=fake")
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

    @Test
    void enabledGuardrailIsBridgedIntoFrameworkProperties() {
        contextRunner
                .withPropertyValues("atmosphere.ai.guardrails.moderation.enabled=true")
                .run(context -> {
                    var framework = context.getBean(org.atmosphere.cpr.AtmosphereFramework.class);
                    @SuppressWarnings("unchecked")
                    var bridged = (java.util.List<AiGuardrail>) framework
                            .getAtmosphereConfig().properties()
                            .get(AiGuardrail.GUARDRAILS_PROPERTY);
                    assertThat(bridged)
                            .as("the enabled moderation guardrail must be bridged into the "
                                    + "framework guardrail list the Ai endpoints consume")
                            .anyMatch(g -> g instanceof ModerationGuardrail);
                });
    }
}
