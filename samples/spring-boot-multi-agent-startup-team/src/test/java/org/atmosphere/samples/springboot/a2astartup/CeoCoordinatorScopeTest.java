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
import org.atmosphere.ai.PromptLoader;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.coordinator.annotation.Coordinator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CEO coordinator declares a restrictive {@code @AgentScope} (startup-advisory
 * purpose, five forbidden topics) and loads the {@code startup-ceo} skill, whose
 * {@code ## Guardrails} section is instruction-only and sets no {@code scopeTier}.
 * The explicit annotation must beat the scope those guardrails alone would derive,
 * so the admission policy installed is the one the class declares.
 */
class CeoCoordinatorScopeTest {

    @Test
    void annotationScopeIsTheInstalledAdmissionPolicy() {
        var coordinator = CeoCoordinator.class.getAnnotation(Coordinator.class);
        var annotation = CeoCoordinator.class.getAnnotation(AgentScope.class);
        // The same load the coordinator processor performs for a skill: reference.
        var skillFile = SkillFileParser.parse(PromptLoader.resolveRaw(coordinator.skillFile()));
        var guardrails = skillFile.listItems("Guardrails");
        assertFalse(guardrails.isEmpty(),
                "precondition: the real startup-ceo skill carries ## Guardrails");
        assertTrue(skillFile.frontmatter("scopeTier").isEmpty()
                        && skillFile.frontmatter("scope-tier").isEmpty(),
                "precondition: the skill declares no scopeTier, so its scope is only derived");
        assertTrue(ScopePolicyResolver.forbiddenTopicsFrom(guardrails).isEmpty(),
                "precondition: the skill's guardrails are instructions, not topic bans");

        var policy = ScopePolicyResolver.resolve(skillFile, CeoCoordinator.class,
                coordinator.name(), coordinator.description(),
                "/atmosphere/agent/" + coordinator.name());

        assertNotNull(policy, "the CEO coordinator must install an admission scope policy");
        assertEquals(annotation.purpose(), policy.config().purpose(),
                "the explicit @AgentScope on CeoCoordinator must beat the scope its skill's "
                        + "## Guardrails would derive");
        assertEquals(List.of("code", "programming", "medical advice", "legal advice",
                        "personal therapy"), policy.config().forbiddenTopics(),
                "the annotation's forbidden topics must be enforced");
        assertEquals(AgentScope.Tier.RULE_BASED, policy.config().tier());
        assertEquals(AgentScope.Breach.POLITE_REDIRECT, policy.config().onBreach());
        assertEquals(annotation.redirectMessage(), policy.config().redirectMessage());
    }
}
