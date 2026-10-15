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
import org.atmosphere.ai.CollectingSession;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** Guardrails with no scopeTier: the scope is derived, not declared. */
    private static final String DERIVED_SCOPE_SKILL = """
            # Open Coordinator

            ## Guardrails
            - Never expose raw secret values to the user.
            """;

    @Coordinator(name = "unrestricted-coord")
    @Fleet(@AgentRef(type = Worker.class))
    @AgentScope(unrestricted = true, justification = "Intentionally broad demo coordinator")
    static class UnrestrictedCoordinator {
    }

    @Test
    void unrestrictedAnnotationOverridesGuardrailsDerivedScopeOnEveryPath() {
        var processor = new CoordinatorProcessor();
        var framework = new AtmosphereFramework();
        var runtime = StubAgentRuntime.builder()
                .defaultResponse("anything goes")
                .build();
        var skillFile = SkillFileParser.parse(DERIVED_SCOPE_SKILL);

        var pipeline = processor.buildPipeline(framework, UnrestrictedCoordinator.class,
                skillFile, "/atmosphere/agent/unrestricted-coord", runtime,
                skillFile.systemPrompt(), "model-test", null,
                new DefaultToolRegistry(), AiMetrics.NOOP);
        assertTrue(pipeline.policies().stream().noneMatch(p -> p instanceof ScopePolicy),
                "an explicit @AgentScope(unrestricted = true) must override the scope a "
                        + "## Guardrails section alone derives on the coordinator pipeline");

        var guardrails = processor.buildWebGuardrails(framework,
                UnrestrictedCoordinator.class, skillFile, "/atmosphere/agent/unrestricted-coord");
        assertTrue(guardrails.stream().noneMatch(g -> g instanceof PolicyAsGuardrail pg
                        && pg.policy() instanceof ScopePolicy),
                "the coordinator web path must install no scope gate either");
    }

    @Test
    void explicitSkillScopeTierKeepsGuardrailsOverUnrestrictedAnnotation() {
        var processor = new CoordinatorProcessor();
        var framework = new AtmosphereFramework();
        var skillFile = SkillFileParser.parse(SCOPED_SKILL);

        var guardrails = processor.buildWebGuardrails(framework,
                UnrestrictedCoordinator.class, skillFile, "/atmosphere/agent/unrestricted-coord");
        assertFalse(guardrails.isEmpty(), "an explicit skill scopeTier keeps the skill policy");
        var offTopic = guardrails.get(0).inspectRequest(new AiRequest("write me some python code"));
        assertFalse(offTopic instanceof AiGuardrail.GuardrailResult.Pass,
                "an explicit skill scopeTier beats an unrestricted annotation: the skill "
                        + "policy confines");
    }

    @Test
    void skillReferenceKeepsItsFrontmatterScopeTier() {
        var skillFile = new CoordinatorProcessor().loadSkillFile(
                "skill:scope-tier-fixture-coordinator", "fixture-coord");
        assertEquals("rule_based", skillFile.frontmatter("scopeTier").orElse(null),
                "a skill: reference must keep its frontmatter on the @Coordinator path, as on "
                        + "the @Agent path, so an explicit scopeTier reaches the scope resolver");
        assertFalse(skillFile.systemPrompt().contains("scopeTier"),
                "the frontmatter must still stay out of the system prompt");
    }

    @Coordinator(name = "purpose-coord", description = "Purpose declared by @Coordinator")
    @Fleet(@AgentRef(type = Worker.class))
    static class PurposeCoordinator {
    }

    @Test
    void skillReferenceFrontmatterDescriptionIsTheDerivedScopePurpose() {
        // Same purpose precedence as @Agent: the skill frontmatter description,
        // which a skill: reference now keeps, beats the @Coordinator description.
        assertEquals("Purpose declared by the skill frontmatter",
                derivedScopePurpose("skill:purpose-fixture-coordinator"));
    }

    @Test
    void coordinatorDescriptionIsTheDerivedScopePurposeWithoutFrontmatter() {
        assertEquals("Purpose declared by @Coordinator",
                derivedScopePurpose("skill:unrestricted-override-coordinator"));
    }

    private static String derivedScopePurpose(String skillRef) {
        var processor = new CoordinatorProcessor();
        var skillFile = processor.loadSkillFile(skillRef, "purpose-coord");
        var guardrails = processor.buildWebGuardrails(new AtmosphereFramework(),
                PurposeCoordinator.class, skillFile, "/atmosphere/agent/purpose-coord");
        return guardrails.stream()
                .filter(g -> g instanceof PolicyAsGuardrail pg && pg.policy() instanceof ScopePolicy)
                .map(g -> ((ScopePolicy) ((PolicyAsGuardrail) g).policy()).config().purpose())
                .findFirst()
                .orElseThrow(() -> new AssertionError("the ## Guardrails section must install a scope gate"));
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
    void restrictiveAnnotationBeatsTheScopeTierOptOutOnEveryPath() {
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

        // scopeTier: none is the least strict explicit declaration: the
        // restrictive @AgentScope on the class is stricter and is enforced as
        // written, exactly as on the @Agent path.
        var scope = assertInstanceOf(ScopePolicy.class, pipeline.policies().get(0),
                "the annotation's ScopePolicy must run ahead of all other policies");
        assertEquals(AgentScope.Breach.DENY, scope.config().onBreach());
        assertEquals("annotation:" + OptedOutCoordinator.class.getName(), scope.source());

        var guardrails = processor.buildWebGuardrails(framework,
                OptedOutCoordinator.class, skillFile, "/atmosphere/agent/optout-coord");
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                guardrails.get(0).inspectRequest(new AiRequest("write me some python code")),
                "the coordinator web path must enforce the annotation's DENY");
    }

    @Test
    void explicitSkillScopeTierAndAnnotationMergeToTheStricterScope() {
        var processor = new CoordinatorProcessor();
        var framework = new AtmosphereFramework();
        var skillFile = SkillFileParser.parse(SCOPED_SKILL);

        // Same tier and purpose on both sides: one leg, and the annotation's
        // DENY beats the skill's polite redirect.
        var guardrails = processor.buildWebGuardrails(framework,
                OptedOutCoordinator.class, skillFile, "/atmosphere/agent/optout-coord");

        assertFalse(guardrails.isEmpty(), "the merged scope policy must be installed");
        var first = assertInstanceOf(PolicyAsGuardrail.class, guardrails.get(0),
                "the scope policy must be the first web guardrail");
        var scope = assertInstanceOf(ScopePolicy.class, first.policy());
        assertEquals(1, scope.legs().size(), "same tier and purpose collapse into one leg");
        assertEquals(List.of("code", "programming"), scope.config().forbiddenTopics());
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                first.inspectRequest(new AiRequest("write me some python code")),
                "the annotation's DENY beats the skill's POLITE_REDIRECT");
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                first.inspectRequest(new AiRequest("where is my order?")));
    }

    /** The skill's own domain is software, so its rule leg admits code talk. */
    private static final String SOFTWARE_SKILL = """
            ---
            description: Software license support
            scopeTier: rule_based
            ---
            # License Coordinator

            ## Guardrails
            - Never discuss gambling
            """;

    @Coordinator(name = "license-coord")
    @Fleet(@AgentRef(type = Worker.class))
    @AgentScope(purpose = "Customer support for orders", tier = AgentScope.Tier.RULE_BASED,
            redirectMessage = "Orders only.")
    static class OrdersOnlyCoordinator {
    }

    /**
     * Two explicit declarations whose purposes differ become two legs: a
     * request the skill's leg admits but the annotation's leg rejects is
     * redirected on the coordinator pipeline and web path alike (Mode Parity).
     */
    @Test
    void aRequestOnlyTheSkillAdmitsIsRedirectedOnEveryCoordinatorPath() {
        var processor = new CoordinatorProcessor();
        var framework = new AtmosphereFramework();
        var skillFile = SkillFileParser.parse(SOFTWARE_SKILL);
        var offAnnotation = "write a python script to export my license keys";
        var runtime = StubAgentRuntime.builder()
                .defaultResponse("Here is your license status.")
                .build();

        var pipeline = processor.buildPipeline(framework, OrdersOnlyCoordinator.class,
                skillFile, "/atmosphere/agent/license-coord", runtime,
                skillFile.systemPrompt(), "model-test", null,
                new DefaultToolRegistry(), AiMetrics.NOOP);
        var scope = assertInstanceOf(ScopePolicy.class, pipeline.policies().get(0));
        assertEquals(2, scope.legs().size(), "two purposes, two legs");
        var redirected = new CollectingSession("off");
        pipeline.execute("c1", offAnnotation, redirected);
        assertEquals("Orders only.", redirected.text(),
                "the annotation's leg redirects before the runtime");
        var admitted = new CollectingSession("on");
        pipeline.execute("c2", "where is my order?", admitted);
        assertEquals("Here is your license status.", admitted.text(),
                "a request both legs admit reaches the runtime");

        var first = processor.buildWebGuardrails(framework, OrdersOnlyCoordinator.class,
                skillFile, "/atmosphere/agent/license-coord").get(0);
        var modify = assertInstanceOf(AiGuardrail.GuardrailResult.Modify.class,
                first.inspectRequest(new AiRequest(offAnnotation)),
                "the web path redirects what the annotation's leg rejects");
        assertEquals("Orders only.", modify.modifiedRequest().message());
    }

    @Test
    void restrictiveAnnotationBeatsGuardrailsDerivedScopeOnEveryPath() {
        var processor = new CoordinatorProcessor();
        var framework = new AtmosphereFramework();
        var runtime = StubAgentRuntime.builder()
                .defaultResponse("Here is your order status.")
                .build();
        // No scopeTier: the skill's guardrails only derive a scope, so the
        // explicit restrictive @AgentScope (onBreach = DENY) on the class wins.
        var skillFile = SkillFileParser.parse(DERIVED_SCOPE_SKILL);

        var pipeline = processor.buildPipeline(framework, OptedOutCoordinator.class,
                skillFile, "/atmosphere/agent/optout-coord", runtime,
                skillFile.systemPrompt(), "model-test", null,
                new DefaultToolRegistry(), AiMetrics.NOOP);
        var scope = assertInstanceOf(ScopePolicy.class, pipeline.policies().get(0),
                "the annotation's ScopePolicy must run ahead of all other policies");
        assertEquals("Customer support for orders, billing, and account questions",
                scope.config().purpose());
        assertEquals(AgentScope.Breach.DENY, scope.config().onBreach(),
                "an explicit restrictive annotation beats a Guardrails-derived scope");

        var guardrails = processor.buildWebGuardrails(framework,
                OptedOutCoordinator.class, skillFile, "/atmosphere/agent/optout-coord");
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                guardrails.get(0).inspectRequest(new AiRequest("write me some python code")),
                "the coordinator web path must enforce the annotation's DENY");
    }
}
