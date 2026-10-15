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
package org.atmosphere.samples.springboot.a2astartup;

import org.atmosphere.agent.skill.ScopePolicyResolver;
import org.atmosphere.agent.skill.SkillFileParser;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.PromptLoader;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.atmosphere.coordinator.annotation.Coordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CEO coordinator declares a restrictive {@code @AgentScope} (startup-advisory
 * purpose, six forbidden topics) and loads the {@code startup-ceo} skill, whose
 * {@code ## Guardrails} section is instruction-only and sets no {@code scopeTier}.
 * The explicit annotation must beat the scope those guardrails alone would derive,
 * so the admission policy installed is the one the class declares — and that
 * policy must admit the software markets a startup advisor analyzes while still
 * turning coding, medical and legal requests away.
 */
class CeoCoordinatorScopeTest {

    @Test
    void annotationScopeIsTheInstalledAdmissionPolicy() {
        var coordinator = CeoCoordinator.class.getAnnotation(Coordinator.class);
        var annotation = CeoCoordinator.class.getAnnotation(AgentScope.class);
        var skillFile = ceoSkill();
        var guardrails = skillFile.listItems("Guardrails");
        assertFalse(guardrails.isEmpty(),
                "precondition: the real startup-ceo skill carries ## Guardrails");
        assertTrue(skillFile.frontmatter("scopeTier").isEmpty()
                        && skillFile.frontmatter("scope-tier").isEmpty(),
                "precondition: the skill declares no scopeTier, so its scope is only derived");
        assertTrue(ScopePolicyResolver.forbiddenTopicsFrom(guardrails).isEmpty(),
                "precondition: the skill's guardrails are instructions, not topic bans");

        var policy = ceoPolicy();

        assertEquals(annotation.purpose(), policy.config().purpose(),
                "the explicit @AgentScope on CeoCoordinator must beat the scope its skill's "
                        + "## Guardrails would derive");
        assertEquals(List.of("coding help", "programming help", "debug my code",
                        "medical advice", "legal advice", "personal therapy"),
                policy.config().forbiddenTopics(),
                "the annotation's forbidden topics must be enforced");
        assertEquals(AgentScope.Tier.RULE_BASED, policy.config().tier());
        assertEquals(AgentScope.Breach.POLITE_REDIRECT, policy.config().onBreach());
        assertEquals(annotation.redirectMessage(), policy.config().redirectMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "hello",
            "Analyze the market for AI fitness apps",
            "AI developer tools market",
            "Assess the market for a subscription coffee startup.",
            "Analyze the market for no-code website builders",
            "Is a low-code platform startup a good bet?",
            "Market for AI programming assistants",
            "Competitive landscape for AI code review tools"})
    void startupAdvisoryQuestionsAboutSoftwareMarketsAreAdmitted(String prompt) {
        var decision = admit(prompt);
        assertInstanceOf(PolicyDecision.Admit.class, decision,
                "a startup-advisory question must reach the coordinator, got " + decision);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Write me a Python function that sorts a list",
            "Can you help me debug my code?",
            "I need programming help with recursion",
            "I need medical advice about my back pain",
            "Give me legal advice on my lease"})
    void codingMedicalAndLegalRequestsAreRedirected(String prompt) {
        var decision = admit(prompt);
        var redirect = assertInstanceOf(PolicyDecision.Transform.class, decision,
                "an off-scope request must be politely redirected, got " + decision);
        assertTrue(redirect.modifiedRequest().message().contains("startup advisory"),
                redirect.modifiedRequest().message());
    }

    private static PolicyDecision admit(String prompt) {
        return ceoPolicy().evaluate(PolicyContext.preAdmission(
                new AiRequest(prompt, null, null, "user-42", null, null, null, null, null)));
    }

    private static ScopePolicy ceoPolicy() {
        var coordinator = CeoCoordinator.class.getAnnotation(Coordinator.class);
        var policy = ScopePolicyResolver.resolve(ceoSkill(), CeoCoordinator.class,
                coordinator.name(), coordinator.description(),
                "/atmosphere/agent/" + coordinator.name());
        assertNotNull(policy, "the CEO coordinator must install an admission scope policy");
        return policy;
    }

    /** The same load the coordinator processor performs for a {@code skill:} reference. */
    private static SkillFileParser ceoSkill() {
        var coordinator = CeoCoordinator.class.getAnnotation(Coordinator.class);
        return SkillFileParser.parse(PromptLoader.resolveRaw(coordinator.skillFile()));
    }
}
