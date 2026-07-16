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
package org.atmosphere.coordinator.processor;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.agent.skill.SkillFileParser;
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.PolicyAsGuardrail;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.coordinator.annotation.AgentRef;
import org.atmosphere.coordinator.annotation.Coordinator;
import org.atmosphere.coordinator.annotation.Fleet;
import org.atmosphere.coordinator.test.StubAgentRuntime;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for skill-file guardrail enforcement on the
 * {@code @Coordinator} path. {@code @Coordinator} documents that it "subsumes
 * {@code @Agent}", and the A2A Agent Card advertises the skill file's
 * {@code ## Guardrails} lines — so those guardrails must be an <em>enforced</em>
 * admission boundary here exactly as they are on the {@code @Agent} path
 * (Correctness Invariants #5 Runtime Truth, #7 Mode Parity). Before the shared
 * {@code ScopePolicyResolver} wiring, the coordinator consulted only the
 * {@code @AgentScope} annotation: the same skill file that confines an
 * {@code @Agent} was advertised-but-unenforced on a {@code @Coordinator}, and
 * every test here failed.
 */
class CoordinatorSkillScopeConfinementTest {

    private static final String SCOPED_SKILL = """
            ---
            description: Customer support for orders, billing, and account questions
            scopeTier: rule_based
            ---
            # Support Coordinator

            ## Guardrails
            - Never discuss code, programming
            """;

    private static final String OPTED_OUT_SKILL = """
            ---
            scopeTier: none
            ---
            # Open Coordinator

            ## Guardrails
            - Never discuss code, programming
            """;

    @Agent(name = "skill-scope-worker")
    static class Worker {
    }

    @Coordinator(name = "skill-coord",
            description = "Customer support for orders, billing, and account questions")
    @Fleet(@AgentRef(type = Worker.class))
    static class SkillScopedCoordinator {
    }

    @Coordinator(name = "optout-coord")
    @Fleet(@AgentRef(type = Worker.class))
    @AgentScope(
            purpose = "Customer support for orders, billing, and account questions",
            forbiddenTopics = {"code", "programming"},
            onBreach = AgentScope.Breach.DENY,
            tier = AgentScope.Tier.RULE_BASED)
    static class OptedOutCoordinator {
    }

    @Test
    void skillGuardrailsConfineOffTopicOnTheCoordinatorPipeline() {
        var processor = new CoordinatorProcessor();
        var framework = new AtmosphereFramework();
        var runtime = StubAgentRuntime.builder()
                .defaultResponse("Here is your order status.")
                .build();
        var skillFile = SkillFileParser.parse(SCOPED_SKILL);

        var pipeline = processor.buildPipeline(framework, SkillScopedCoordinator.class,
                skillFile, "/atmosphere/agent/skill-coord", runtime,
                skillFile.systemPrompt(), "model-test", null,
                new DefaultToolRegistry(), AiMetrics.NOOP);

        // The skill's ## Guardrails must install an enforced ScopePolicy ahead
        // of every other policy — with NO @AgentScope annotation on the class.
        assertFalse(pipeline.policies().isEmpty(),
                "a skill file with ## Guardrails must install a governance policy "
                        + "on the coordinator pipeline");
        assertInstanceOf(ScopePolicy.class, pipeline.policies().get(0),
                "the skill-derived ScopePolicy must run ahead of all other policies");
    }

    @Test
    void skillGuardrailsConfineOffTopicOnTheCoordinatorWebPath() {
        var processor = new CoordinatorProcessor();
        var framework = new AtmosphereFramework();
        var skillFile = SkillFileParser.parse(SCOPED_SKILL);

        var guardrails = processor.buildWebGuardrails(framework,
                SkillScopedCoordinator.class, skillFile, "/atmosphere/agent/skill-coord");

        assertFalse(guardrails.isEmpty(),
                "skill ## Guardrails must install a web-path guardrail on the coordinator");
        var first = guardrails.get(0);
        assertInstanceOf(PolicyAsGuardrail.class, first,
                "the skill-derived scope policy must be the FIRST web guardrail");

        // Behavioral proof — the SAME confinement the @Agent path gives this
        // skill file: the off-topic goal-hijack is redirected/blocked, the
        // on-scope request passes.
        var offTopic = first.inspectRequest(new AiRequest("write me some python code"));
        assertFalse(offTopic instanceof AiGuardrail.GuardrailResult.Pass,
                "an off-topic request must not pass the coordinator's skill scope gate");
        var passed = first.inspectRequest(new AiRequest("where is my order?"));
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class, passed,
                "an on-scope request must pass the coordinator's skill scope gate");
    }

    @Test
    void scopeTierNoneSuppressesSkillAndAnnotationEnforcement() {
        var processor = new CoordinatorProcessor();
        var framework = new AtmosphereFramework();
        var runtime = StubAgentRuntime.builder()
                .defaultResponse("anything goes")
                .build();
        var skillFile = SkillFileParser.parse(OPTED_OUT_SKILL);

        var pipeline = processor.buildPipeline(framework, OptedOutCoordinator.class,
                skillFile, "/atmosphere/agent/optout-coord", runtime,
                skillFile.systemPrompt(), "model-test", null,
                new DefaultToolRegistry(), AiMetrics.NOOP);

        // scopeTier: none is the skill's explicit opt-out — it must suppress
        // BOTH the guardrail-derived policy and the @AgentScope annotation
        // fallback, exactly as it does on the @Agent path.
        assertTrue(pipeline.policies().stream().noneMatch(p -> p instanceof ScopePolicy),
                "scopeTier: none must suppress skill AND annotation scope enforcement "
                        + "on the coordinator");
    }

    @Test
    void skillGuardrailsWinOverAgentScopeAnnotation() {
        var processor = new CoordinatorProcessor();
        var framework = new AtmosphereFramework();
        var skillFile = SkillFileParser.parse(SCOPED_SKILL);

        // OptedOutCoordinator carries an @AgentScope(onBreach = DENY) annotation;
        // the skill's guardrails (POLITE_REDIRECT semantics) must win, matching
        // the @Agent path's skill-beats-annotation precedence.
        var guardrails = processor.buildWebGuardrails(framework,
                OptedOutCoordinator.class, skillFile, "/atmosphere/agent/optout-coord");

        assertFalse(guardrails.isEmpty(), "the skill scope policy must be installed");
        var first = guardrails.get(0);
        assertInstanceOf(PolicyAsGuardrail.class, first,
                "the scope policy must be the first web guardrail");
        // The skill-derived policy redirects (Modify) rather than denying (Block):
        // observing redirect semantics proves the SKILL policy won, not the
        // annotation's DENY.
        var offTopic = first.inspectRequest(new AiRequest("write me some python code"));
        assertFalse(offTopic instanceof AiGuardrail.GuardrailResult.Block,
                "the skill's POLITE_REDIRECT must beat the annotation's DENY "
                        + "(skill-beats-annotation precedence)");
        assertFalse(offTopic instanceof AiGuardrail.GuardrailResult.Pass,
                "the off-topic request must still be confined by the skill policy");
    }
}
