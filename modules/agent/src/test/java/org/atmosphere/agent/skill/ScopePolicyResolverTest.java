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
package org.atmosphere.agent.skill;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.atmosphere.ai.annotation.AgentScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the scope precedence {@link ScopePolicyResolver} applies for every
 * skill-consuming processor: a skill's {@code scopeTier} opt-out, then an explicit
 * skill {@code scopeTier}, then an explicit {@code @AgentScope(unrestricted = true)}
 * over a {@code ## Guardrails}-derived scope, then the derived scope, then the
 * annotation.
 */
class ScopePolicyResolverTest {

    private static final String JUSTIFICATION = "Open demo agent, scoped by its crew";

    /** Guardrails with instruction lines only — no topic ban, no scopeTier. */
    private static final String DERIVED_ONLY_SKILL = """
            # Assistant

            ## Guardrails
            - Never expose raw secret values to the user.
            """;

    private static final String EXPLICIT_TIER_SKILL = """
            ---
            description: Order-status support for an online store
            scopeTier: rule_based
            ---
            # Support

            ## Guardrails
            - Never discuss gambling
            """;

    private static final String OPTED_OUT_SKILL = """
            ---
            scopeTier: none
            ---
            # Playground

            ## Guardrails
            - gambling
            """;

    private static final String NO_GUARDRAILS_SKILL = """
            # Assistant

            ## Skills
            - Answer questions
            """;

    @AgentScope(unrestricted = true, justification = JUSTIFICATION)
    private static final class UnrestrictedAgent {
    }

    @AgentScope(unrestricted = true)
    private static final class UnjustifiedUnrestrictedAgent {
    }

    @AgentScope(purpose = "Annotation-declared purpose", forbiddenTopics = "gambling",
            tier = AgentScope.Tier.RULE_BASED)
    private static final class RestrictedAgent {
    }

    private static final class UnannotatedAgent {
    }

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        logger().addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger().detachAppender(appender);
        appender.stop();
    }

    private static ch.qos.logback.classic.Logger logger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ScopePolicyResolver.class);
    }

    private List<ILoggingEvent> warnings() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    private static org.atmosphere.ai.governance.scope.ScopePolicy resolve(String skill,
                                                                           Class<?> agentClass) {
        return ScopePolicyResolver.resolve(SkillFileParser.parse(skill), agentClass,
                "assistant", "Assistant", "/atmosphere/agent/assistant");
    }

    @Test
    void scopeTierOptOutBeatsEverything() {
        assertNull(resolve(OPTED_OUT_SKILL, UnrestrictedAgent.class));
        assertNull(resolve(OPTED_OUT_SKILL, RestrictedAgent.class),
                "scopeTier: none must not fall back to the annotation");
        assertFalse(ScopePolicyResolver.warnIfUnrestrictedOverridesGuardrails(
                SkillFileParser.parse(OPTED_OUT_SKILL), UnrestrictedAgent.class, "assistant", ""));
        assertTrue(warnings().isEmpty());
    }

    @Test
    void explicitSkillScopeTierBeatsUnrestrictedAnnotation() {
        var policy = resolve(EXPLICIT_TIER_SKILL, UnrestrictedAgent.class);
        assertNotNull(policy,
                "a skill that declares its scopeTier explicitly keeps its ## Guardrails policy "
                        + "over an unrestricted annotation (two explicit declarations: restrictive)");
        assertEquals("Order-status support for an online store", policy.config().purpose());
        assertEquals(AgentScope.Tier.RULE_BASED, policy.config().tier());
        assertFalse(policy.config().unrestricted());
        assertFalse(ScopePolicyResolver.warnIfUnrestrictedOverridesGuardrails(
                SkillFileParser.parse(EXPLICIT_TIER_SKILL), UnrestrictedAgent.class,
                "assistant", "skill:support"));
        assertTrue(warnings().isEmpty());
    }

    @Test
    void unrestrictedAnnotationOverridesGuardrailsDerivedScope() {
        assertNotNull(ScopePolicyResolver.fromSkillGuardrails(
                        SkillFileParser.parse(DERIVED_ONLY_SKILL), "assistant", "Assistant"),
                "precondition: the Guardrails section alone derives a scope policy");
        assertNull(resolve(DERIVED_ONLY_SKILL, UnrestrictedAgent.class),
                "an explicit @AgentScope(unrestricted = true) must override a scope "
                        + "derived from ## Guardrails alone: no admission policy");
    }

    @Test
    void overrideLogsOneWarningNamingAgentSkillAndJustification() {
        var skill = SkillFileParser.parse(DERIVED_ONLY_SKILL);
        assertTrue(ScopePolicyResolver.warnIfUnrestrictedOverridesGuardrails(
                skill, UnrestrictedAgent.class, "assistant", "skill:assistant"));
        // Resolving (once per invocation path) adds no further warning.
        resolve(DERIVED_ONLY_SKILL, UnrestrictedAgent.class);
        resolve(DERIVED_ONLY_SKILL, UnrestrictedAgent.class);

        var warnings = warnings();
        assertEquals(1, warnings.size(), "exactly one WARN per registration: " + warnings);
        var message = warnings.get(0).getFormattedMessage();
        assertTrue(message.contains("'assistant'"), message);
        assertTrue(message.contains("skill:assistant"), message);
        assertTrue(message.contains(JUSTIFICATION), message);
    }

    @Test
    void overrideWarningNamesAnAutoDiscoveredSkillByItsTitle() {
        ScopePolicyResolver.warnIfUnrestrictedOverridesGuardrails(
                SkillFileParser.parse(DERIVED_ONLY_SKILL), UnrestrictedAgent.class, "assistant", "");
        assertEquals(1, warnings().size());
        assertTrue(warnings().get(0).getFormattedMessage().contains("'Assistant'"),
                warnings().get(0).getFormattedMessage());
    }

    @Test
    void unjustifiedUnrestrictedAnnotationDoesNotOverride() {
        var policy = resolve(DERIVED_ONLY_SKILL, UnjustifiedUnrestrictedAgent.class);
        assertNotNull(policy, "an unjustified unrestricted annotation is invalid and must "
                + "fail closed to the Guardrails-derived policy");
        assertFalse(policy.config().unrestricted());
        assertFalse(ScopePolicyResolver.warnIfUnrestrictedOverridesGuardrails(
                SkillFileParser.parse(DERIVED_ONLY_SKILL), UnjustifiedUnrestrictedAgent.class,
                "assistant", ""));
    }

    @Test
    void guardrailsDerivedScopeStillBeatsRestrictedAnnotation() {
        var policy = resolve(EXPLICIT_TIER_SKILL.replace("scopeTier: rule_based\n", ""),
                RestrictedAgent.class);
        assertNotNull(policy);
        assertEquals("Order-status support for an online store", policy.config().purpose(),
                "without an unrestricted annotation the derived policy keeps winning");
    }

    @Test
    void unrestrictedAnnotationWithoutGuardrailsIsUnchanged() {
        var policy = resolve(NO_GUARDRAILS_SKILL, UnrestrictedAgent.class);
        assertNotNull(policy, "with no ## Guardrails the annotation is honored as before");
        assertTrue(policy.config().unrestricted());
        assertFalse(ScopePolicyResolver.warnIfUnrestrictedOverridesGuardrails(
                SkillFileParser.parse(NO_GUARDRAILS_SKILL), UnrestrictedAgent.class,
                "assistant", ""));
        assertTrue(warnings().isEmpty());
    }

    @Test
    void noSkillFileFallsThroughToTheAnnotation() {
        assertNotNull(ScopePolicyResolver.resolve(null, RestrictedAgent.class,
                "assistant", "Assistant", "/atmosphere/agent/assistant"));
        assertNull(ScopePolicyResolver.resolve(null, UnannotatedAgent.class,
                "assistant", "Assistant", "/atmosphere/agent/assistant"));
        assertFalse(ScopePolicyResolver.warnIfUnrestrictedOverridesGuardrails(
                null, UnrestrictedAgent.class, "assistant", ""));
    }
}
