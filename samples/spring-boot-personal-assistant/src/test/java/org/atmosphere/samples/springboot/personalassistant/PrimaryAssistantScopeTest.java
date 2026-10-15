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
package org.atmosphere.samples.springboot.personalassistant;

import org.atmosphere.agent.skill.ScopePolicyResolver;
import org.atmosphere.agent.skill.SkillFileParser;
import org.atmosphere.ai.PromptLoader;
import org.atmosphere.coordinator.annotation.Coordinator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The primary assistant declares {@code @AgentScope(unrestricted = true)} and its
 * skill file carries a {@code ## Guardrails} section with no {@code scopeTier}.
 * The explicit annotation must beat the scope those guardrails alone would derive,
 * so no embedding admission check runs on every turn.
 */
class PrimaryAssistantScopeTest {

    @Test
    void unrestrictedAnnotationLeavesThePrimaryAssistantWithoutAScopePolicy() {
        var coordinator = PrimaryAssistant.class.getAnnotation(Coordinator.class);
        // The same load the coordinator processor performs for a skill: reference.
        var skillFile = SkillFileParser.parse(PromptLoader.resolveRaw(coordinator.skillFile()));
        assertFalse(skillFile.listItems("Guardrails").isEmpty(),
                "precondition: the real primary-assistant skill carries ## Guardrails");
        assertNotNull(ScopePolicyResolver.fromSkillGuardrails(skillFile, coordinator.name(),
                        coordinator.description()),
                "precondition: those guardrails alone derive a scope policy");

        assertNull(ScopePolicyResolver.resolve(skillFile, PrimaryAssistant.class,
                        coordinator.name(), coordinator.description(),
                        "/atmosphere/agent/" + coordinator.name()),
                "the explicit @AgentScope(unrestricted = true) on PrimaryAssistant must "
                        + "override the Guardrails-derived scope: no admission policy");
    }
}
