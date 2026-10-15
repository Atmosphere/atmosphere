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
import org.atmosphere.ai.governance.scope.ScopeConfig;
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
 * skill-consuming processor: explicit beats derived, and between two explicit
 * declarations the stricter wins. A skill's {@code scopeTier} opt-out yields to
 * a restrictive annotation; an explicit skill {@code scopeTier} and a
 * restrictive annotation are both enforced as the legs of one policy; then an
 * explicit {@code @AgentScope(unrestricted = true)} over a
 * {@code ## Guardrails}-derived scope, then an explicit restrictive
 * {@code @AgentScope} (with the skill's topic prohibitions unioned in), then the
 * derived scope. Every cell of the skill-by-annotation table is pinned here,
 * with what {@link ScopePolicyResolver#reportScopeOverrides} logs for it.
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
            - lottery
            """;

    /** Guardrails with one explicit topic ban and one instruction line, no scopeTier. */
    private static final String DERIVED_TOPIC_SKILL = """
            ---
            description: Order-status support for an online store
            ---
            # Support

            ## Guardrails
            - Never discuss politics
            - Never expose raw secret values to the user.
            """;

    /** An explicit scopeTier but no ## Guardrails section: no skill policy to keep. */
    private static final String EXPLICIT_TIER_NO_GUARDRAILS_SKILL = """
            ---
            scopeTier: rule_based
            ---
            # Assistant

            ## Skills
            - Answer questions
            """;

    /** The opt-out on a skill with no ## Guardrails section at all. */
    private static final String OPTED_OUT_NO_GUARDRAILS_SKILL = """
            ---
            scopeTier: none
            ---
            # Playground

            ## Skills
            - Answer questions
            """;

    /** Same tier and purpose as {@link SamePurposeDenyingAgent}: the two collapse. */
    private static final String SAME_PURPOSE_RULE_SKILL = """
            ---
            description: Dental clinic scheduling
            scopeTier: rule_based
            ---
            # Scheduler

            ## Guardrails
            - Never discuss pricing
            """;

    private static final String EMBEDDING_SKILL = """
            ---
            description: Order-status support for an online store
            scopeTier: embedding
            ---
            # Support

            ## Guardrails
            - Never discuss gambling
            """;

    private static final String ORDER_SUPPORT_RULE_SKILL = """
            ---
            description: Order support
            scopeTier: rule_based
            ---
            # Orders

            ## Guardrails
            - Never discuss gambling
            """;

    private static final String SEMANTIC_DENTAL_SKILL = """
            ---
            description: Dental care
            scopeTier: semantic
            ---
            # Dentist

            ## Guardrails
            - Never discuss gambling
            """;

    /** An explicit tier but no description: the skill's purpose is a placeholder. */
    private static final String PURPOSELESS_LLM_SKILL = """
            ---
            scopeTier: llm
            ---
            # Orders

            ## Guardrails
            - Never discuss gambling
            """;

    private static final String LLM_SKILL = """
            ---
            description: Order-status support for an online store
            scopeTier: llm
            ---
            # Support

            ## Guardrails
            - Never discuss gambling
            """;

    private static final String UNKNOWN_TIER_SKILL = """
            ---
            description: Order-status support for an online store
            scopeTier: embeding
            ---
            # Support

            ## Guardrails
            - Never discuss gambling
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
            onBreach = AgentScope.Breach.DENY, redirectMessage = "Annotation redirect",
            tier = AgentScope.Tier.RULE_BASED, similarityThreshold = 0.6)
    private static final class RestrictedAgent {
    }

    @AgentScope(purpose = "Annotation-declared purpose", forbiddenTopics = {"Gambling", "crypto"},
            onBreach = AgentScope.Breach.DENY, tier = AgentScope.Tier.RULE_BASED)
    private static final class CryptoRestrictedAgent {
    }

    @AgentScope(purpose = "Annotation-declared purpose", forbiddenTopics = "Politics",
            tier = AgentScope.Tier.RULE_BASED)
    private static final class PoliticsRestrictedAgent {
    }

    /** Every setting differs from what a skill-derived policy would use. */
    @AgentScope(purpose = "Strict annotation purpose", forbiddenTopics = "crypto",
            onBreach = AgentScope.Breach.DENY, tier = AgentScope.Tier.LLM_CLASSIFIER,
            similarityThreshold = 0.8, postResponseCheck = true)
    private static final class StrictAgent {
    }

    @AgentScope(purpose = "Dental clinic scheduling", tier = AgentScope.Tier.RULE_BASED,
            forbiddenTopics = "insurance", onBreach = AgentScope.Breach.DENY,
            similarityThreshold = 0.6)
    private static final class SamePurposeDenyingAgent {
    }

    @AgentScope(purpose = "order-status support for an online store",
            tier = AgentScope.Tier.EMBEDDING_SIMILARITY, similarityThreshold = 0.6)
    private static final class EmbeddingAgent {
    }

    @AgentScope(purpose = "Order support", tier = AgentScope.Tier.LLM_CLASSIFIER,
            redirectMessage = "Orders only.")
    private static final class LlmRedirectAgent {
    }

    @AgentScope(purpose = "Order support", tier = AgentScope.Tier.LLM_CLASSIFIER,
            onBreach = AgentScope.Breach.CUSTOM_MESSAGE, redirectMessage = "Orders only.")
    private static final class CustomMessageAgent {
    }

    @AgentScope(purpose = "Clinic billing", tier = AgentScope.Tier.EMBEDDING_SIMILARITY)
    private static final class BillingEmbeddingAgent {
    }

    @AgentScope(purpose = "Order support", tier = AgentScope.Tier.EMBEDDING_SIMILARITY)
    private static final class OrderEmbeddingAgent {
    }

    @AgentScope(purpose = "Order support", tier = AgentScope.Tier.RULE_BASED,
            postResponseCheck = true)
    private static final class PostCheckingAgent {
    }

    /** Restrictive but invalid: a blank purpose with unrestricted = false. */
    @AgentScope(forbiddenTopics = "gambling", tier = AgentScope.Tier.RULE_BASED)
    private static final class PurposelessAgent {
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

    private List<ILoggingEvent> errors() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }

    private List<ILoggingEvent> infos() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.INFO).toList();
    }

    private static org.atmosphere.ai.governance.scope.ScopePolicy resolve(String skill,
                                                                           Class<?> agentClass) {
        return resolve(skill, agentClass, "Assistant");
    }

    private static org.atmosphere.ai.governance.scope.ScopePolicy resolve(String skill,
                                                                           Class<?> agentClass,
                                                                           String description) {
        return ScopePolicyResolver.resolve(SkillFileParser.parse(skill), agentClass,
                "assistant", description, "/atmosphere/agent/assistant");
    }

    private static boolean report(String skill, Class<?> agentClass, String skillRef) {
        return report(skill, agentClass, "Assistant", skillRef);
    }

    private static boolean report(String skill, Class<?> agentClass, String description,
                                  String skillRef) {
        return ScopePolicyResolver.reportScopeOverrides(SkillFileParser.parse(skill), agentClass,
                "assistant", description, skillRef);
    }

    /** Each leg as "TIER purpose threshold", in enforcement order. */
    private static List<String> legs(org.atmosphere.ai.governance.scope.ScopePolicy policy) {
        return policy.legs().stream()
                .map(l -> l.tier() + " " + l.purpose() + " " + l.similarityThreshold())
                .toList();
    }

    /** Every leg carries the same shared settings; returns them from the first. */
    private static ScopeConfig shared(org.atmosphere.ai.governance.scope.ScopePolicy policy) {
        var first = policy.legs().get(0);
        for (var leg : policy.legs()) {
            assertEquals(first.forbiddenTopics(), leg.forbiddenTopics());
            assertEquals(first.onBreach(), leg.onBreach());
            assertEquals(first.redirectMessage(), leg.redirectMessage());
            assertEquals(first.postResponseCheck(), leg.postResponseCheck());
        }
        return first;
    }

    @Test
    void scopeTierOptOutBeatsAnUnrestrictedAnnotation() {
        assertNull(resolve(OPTED_OUT_SKILL, UnrestrictedAgent.class));
        assertFalse(report(OPTED_OUT_SKILL, UnrestrictedAgent.class, ""));
        assertTrue(appender.list.isEmpty(), appender.list.toString());
    }

    @Test
    void scopeTierOptOutAloneInstallsNothing() {
        assertNull(resolve(OPTED_OUT_SKILL, UnannotatedAgent.class));
        assertNull(resolve(OPTED_OUT_NO_GUARDRAILS_SKILL, UnannotatedAgent.class));
        assertFalse(report(OPTED_OUT_SKILL, UnannotatedAgent.class, ""));
        assertTrue(appender.list.isEmpty(), appender.list.toString());
    }

    @Test
    void restrictiveAnnotationBeatsTheScopeTierOptOutAsWritten() {
        var policy = resolve(OPTED_OUT_SKILL, RestrictedAgent.class);
        assertNotNull(policy, "a restrictive @AgentScope is stricter than scopeTier: none");
        assertEquals(1, policy.legs().size());
        var config = policy.config();
        assertEquals("Annotation-declared purpose", config.purpose());
        assertEquals(AgentScope.Breach.DENY, config.onBreach());
        assertEquals("Annotation redirect", config.redirectMessage());
        assertEquals(AgentScope.Tier.RULE_BASED, config.tier());
        assertEquals(0.6, config.similarityThreshold());
        assertEquals(List.of("gambling"), config.forbiddenTopics(),
                "the opted-out skill's ## Guardrails stay prompt-only: 'lottery' is not unioned");
        assertEquals("scope::" + RestrictedAgent.class.getSimpleName(), policy.name());
        assertEquals("annotation:" + RestrictedAgent.class.getName(), policy.source());
    }

    @Test
    void restrictiveAnnotationBeatingTheOptOutIsReported() {
        resolve(OPTED_OUT_SKILL, RestrictedAgent.class);
        assertTrue(appender.list.isEmpty(), "resolving logs nothing: " + appender.list);

        assertTrue(report(OPTED_OUT_SKILL, RestrictedAgent.class, "skill:playground"));
        var warnings = warnings();
        assertEquals(1, warnings.size(), "the overridden opt-out is reported: " + warnings);
        var message = warnings.get(0).getFormattedMessage();
        assertTrue(message.contains("'assistant'"), message);
        assertTrue(message.contains("skill:playground"), message);
        assertTrue(message.contains("scopeTier: none"), message);
        assertTrue(message.contains(RestrictedAgent.class.getName()), message);
        assertTrue(message.contains("Annotation-declared purpose"), message);
        assertTrue(message.contains("unrestricted = true"),
                "the WARN says how to run the agent unscoped: " + message);
    }

    @Test
    void restrictiveAnnotationBeatsTheOptOutWithoutGuardrailsToo() {
        var policy = resolve(OPTED_OUT_NO_GUARDRAILS_SKILL, RestrictedAgent.class);
        assertNotNull(policy);
        assertEquals("Annotation-declared purpose", policy.config().purpose());
        assertTrue(report(OPTED_OUT_NO_GUARDRAILS_SKILL, RestrictedAgent.class, ""));
        assertEquals(1, warnings().size(), warnings().toString());
        assertTrue(warnings().get(0).getFormattedMessage().contains("'Playground'"),
                warnings().get(0).getFormattedMessage());
    }

    @Test
    void invalidAnnotationBesideTheOptOutInstallsNothingAndIsReported() {
        assertNull(resolve(OPTED_OUT_SKILL, PurposelessAgent.class));
        assertTrue(appender.list.isEmpty(), "resolving logs nothing: " + appender.list);

        assertTrue(report(OPTED_OUT_SKILL, PurposelessAgent.class, "skill:playground"));
        assertEquals(1, errors().size(), errors().toString());
        var message = errors().get(0).getFormattedMessage();
        assertTrue(message.contains(PurposelessAgent.class.getName()), message);
        assertTrue(message.contains("scopeTier: none"), message);
        assertTrue(warnings().isEmpty(), warnings().toString());
    }

    // ── an explicit skill scopeTier and a restrictive @AgentScope: the stricter wins ──

    @Test
    void explicitSkillScopeTierAndAnnotationAreBothEnforcedAsLegs() {
        var policy = resolve(EXPLICIT_TIER_SKILL, StrictAgent.class);
        assertNotNull(policy);
        assertEquals(List.of(
                        "RULE_BASED Order-status support for an online store 0.45",
                        "LLM_CLASSIFIER Strict annotation purpose 0.8"),
                legs(policy), "each declaration keeps its own purpose, tier and threshold");
        var config = shared(policy);
        assertEquals(AgentScope.Breach.DENY, config.onBreach(), "DENY beats the skill's redirect");
        assertEquals(ScopeConfig.DEFAULT_REDIRECT_MESSAGE, config.redirectMessage());
        assertTrue(config.postResponseCheck(), "the annotation's post-response check covers every leg");
        assertEquals(List.of("gambling", "crypto"), config.forbiddenTopics());
        assertEquals("scope::assistant", policy.name());
        assertEquals("skill-guardrails:assistant+annotation:" + StrictAgent.class.getName(),
                policy.source());
    }

    @Test
    void mergingTwoPurposesIsReportedAsAWarning() {
        resolve(EXPLICIT_TIER_SKILL, StrictAgent.class);
        assertTrue(appender.list.isEmpty(), "resolving logs nothing: " + appender.list);

        assertTrue(report(EXPLICIT_TIER_SKILL, StrictAgent.class, "skill:support"));
        var warnings = warnings();
        assertEquals(1, warnings.size(), "the merge is reported once: " + appender.list);
        var message = warnings.get(0).getFormattedMessage();
        assertTrue(message.contains("skill:support"), message);
        assertTrue(message.contains("scopeTier: rule_based"), message);
        assertTrue(message.contains(StrictAgent.class.getName()), message);
        assertTrue(message.contains("RULE_BASED 'Order-status support for an online store'"), message);
        assertTrue(message.contains("LLM_CLASSIFIER 'Strict annotation purpose'"), message);
        assertTrue(message.contains("DENY"), message);
        assertTrue(message.contains("purposes differ"), message);
    }

    @Test
    void explicitSkillScopeTierUnionsTopicsAndTakesTheStricterBreach() {
        var policy = resolve(EXPLICIT_TIER_SKILL, CryptoRestrictedAgent.class);
        assertNotNull(policy);
        assertEquals(List.of(
                        "RULE_BASED Annotation-declared purpose 0.45",
                        "RULE_BASED Order-status support for an online store 0.45"),
                legs(policy), "on a tier tie the annotation's leg runs first");
        var config = shared(policy);
        assertEquals(AgentScope.Breach.DENY, config.onBreach());
        assertEquals(List.of("gambling", "crypto"), config.forbiddenTopics(),
                "the skill's topics first, then the annotation's, deduplicated ignoring case");
    }

    @Test
    void sameTierAndPurposeCollapseIntoOneLegWithTheHigherThreshold() {
        var policy = resolve(SAME_PURPOSE_RULE_SKILL, SamePurposeDenyingAgent.class);
        assertNotNull(policy);
        assertEquals(List.of("RULE_BASED Dental clinic scheduling 0.6"), legs(policy));
        var config = policy.config();
        assertEquals(List.of("pricing", "insurance"), config.forbiddenTopics());
        assertEquals(AgentScope.Breach.DENY, config.onBreach());

        assertTrue(report(SAME_PURPOSE_RULE_SKILL, SamePurposeDenyingAgent.class, ""));
        assertTrue(warnings().isEmpty(), "nothing in the merge needs attention: " + warnings());
        assertEquals(1, infos().size(), appender.list.toString());
        assertTrue(infos().get(0).getFormattedMessage().contains("collapsed"),
                infos().get(0).getFormattedMessage());
    }

    @Test
    void sameEmbeddingTierAndPurposeKeepTheStricterThreshold() {
        var policy = resolve(EMBEDDING_SKILL, EmbeddingAgent.class);
        assertNotNull(policy);
        assertEquals(List.of("EMBEDDING_SIMILARITY order-status support for an online store 0.6"),
                legs(policy), "purposes compared ignoring case; the annotation's spelling and "
                        + "max(0.45, 0.6) are kept");
    }

    @Test
    void theAnnotationRedirectIsTheOneRedirectOfEveryLeg() {
        var policy = resolve(ORDER_SUPPORT_RULE_SKILL, LlmRedirectAgent.class);
        assertNotNull(policy);
        assertEquals(List.of("RULE_BASED Order support 0.45", "LLM_CLASSIFIER Order support 0.45"),
                legs(policy), "the free rule leg runs before the LLM leg");
        var config = shared(policy);
        assertEquals("Orders only.", config.redirectMessage());
        assertEquals(AgentScope.Breach.POLITE_REDIRECT, config.onBreach());
        assertFalse(config.postResponseCheck());

        assertTrue(report(ORDER_SUPPORT_RULE_SKILL, LlmRedirectAgent.class, ""));
        assertTrue(warnings().isEmpty(), "same purpose, no post-response check: " + warnings());
        assertEquals(1, infos().size(), appender.list.toString());
    }

    @Test
    void customMessageBeatsAPoliteRedirect() {
        var policy = resolve(ORDER_SUPPORT_RULE_SKILL, CustomMessageAgent.class);
        assertNotNull(policy);
        assertEquals(AgentScope.Breach.CUSTOM_MESSAGE, shared(policy).onBreach());
    }

    @Test
    void differentPurposesAcrossEmbeddingTiersBecomeTwoLegsAndAWarning() {
        var policy = resolve(SEMANTIC_DENTAL_SKILL, BillingEmbeddingAgent.class);
        assertNotNull(policy);
        assertEquals(List.of("EMBEDDING_SIMILARITY Clinic billing 0.45",
                        "SEMANTIC_INTENT Dental care 0.45"),
                legs(policy), "EMBEDDING is never folded into SEMANTIC_INTENT");

        assertTrue(report(SEMANTIC_DENTAL_SKILL, BillingEmbeddingAgent.class, ""));
        assertEquals(1, warnings().size(), appender.list.toString());
        assertTrue(warnings().get(0).getFormattedMessage().contains("purposes differ"),
                warnings().get(0).getFormattedMessage());
    }

    @Test
    void aSkillWithoutAPurposeTakesTheAnnotationsPurpose() {
        var policy = resolve(PURPOSELESS_LLM_SKILL, OrderEmbeddingAgent.class, "");
        assertNotNull(policy);
        assertEquals(List.of("EMBEDDING_SIMILARITY Order support 0.45",
                        "LLM_CLASSIFIER Order support 0.45"),
                legs(policy), "no leg is built against the placeholder purpose");

        assertTrue(report(PURPOSELESS_LLM_SKILL, OrderEmbeddingAgent.class, "", ""));
        assertEquals(1, warnings().size(), appender.list.toString());
        assertTrue(warnings().get(0).getFormattedMessage().contains("declares no purpose"),
                warnings().get(0).getFormattedMessage());
    }

    @Test
    void anAgentDescriptionIsTheSkillPurposeSoNoPlaceholderIsReported() {
        var policy = resolve(PURPOSELESS_LLM_SKILL, OrderEmbeddingAgent.class, "Order support");
        assertEquals(List.of("EMBEDDING_SIMILARITY Order support 0.45",
                        "LLM_CLASSIFIER Order support 0.45"), legs(policy));
        report(PURPOSELESS_LLM_SKILL, OrderEmbeddingAgent.class, "Order support", "");
        assertTrue(warnings().isEmpty(), warnings().toString());
    }

    @Test
    void thePostResponseCheckCoversEveryLegAndWarnsWhenItAddsAnLlmCall() {
        var policy = resolve(LLM_SKILL, PostCheckingAgent.class);
        assertNotNull(policy);
        assertEquals(List.of("RULE_BASED Order support 0.45",
                        "LLM_CLASSIFIER Order-status support for an online store 0.45"),
                legs(policy));
        assertTrue(shared(policy).postResponseCheck());

        assertTrue(report(LLM_SKILL, PostCheckingAgent.class, ""));
        assertEquals(1, warnings().size(), appender.list.toString());
        assertTrue(warnings().get(0).getFormattedMessage().contains("post-response check now also "
                + "runs the LLM_CLASSIFIER leg"), warnings().get(0).getFormattedMessage());
    }

    @Test
    void invalidAnnotationBesideAnExplicitSkillScopeKeepsTheSkillPolicyPlusItsTopics() {
        var policy = resolve(EXPLICIT_TIER_SKILL, PurposelessAgent.class);
        assertNotNull(policy);
        assertEquals(List.of("RULE_BASED Order-status support for an online store 0.45"),
                legs(policy));
        assertEquals(List.of("gambling"), policy.config().forbiddenTopics());
        assertEquals("skill-guardrails:assistant", policy.source());

        assertTrue(report(EXPLICIT_TIER_SKILL, PurposelessAgent.class, "skill:support"));
        assertEquals(1, errors().size(), "the invalid annotation is reported in the explicit-tier "
                + "branch too: " + appender.list);
        assertTrue(errors().get(0).getFormattedMessage().contains(PurposelessAgent.class.getName()),
                errors().get(0).getFormattedMessage());
    }

    @Test
    void explicitSkillScopeTierAloneKeepsTheSkillPolicy() {
        var policy = resolve(EXPLICIT_TIER_SKILL, UnannotatedAgent.class);
        assertNotNull(policy);
        assertEquals(List.of("RULE_BASED Order-status support for an online store 0.45"),
                legs(policy));
        assertEquals(AgentScope.Breach.POLITE_REDIRECT, policy.config().onBreach());
        assertEquals("scope::assistant", policy.name());
        assertEquals("skill-guardrails:assistant", policy.source());
        assertFalse(report(EXPLICIT_TIER_SKILL, UnannotatedAgent.class, ""));
        assertTrue(appender.list.isEmpty(), appender.list.toString());
    }

    @Test
    void anUnknownScopeTierIsReportedAsAnErrorAndEnforcesTheEmbeddingTier() {
        var policy = resolve(UNKNOWN_TIER_SKILL, UnannotatedAgent.class);
        assertNotNull(policy, "a typo in scopeTier never leaves the agent unscoped");
        assertEquals(AgentScope.Tier.EMBEDDING_SIMILARITY, policy.config().tier());
        assertTrue(appender.list.isEmpty(), "resolving logs nothing: " + appender.list);

        assertTrue(report(UNKNOWN_TIER_SKILL, UnannotatedAgent.class, "skill:support"));
        assertEquals(1, errors().size(), appender.list.toString());
        assertTrue(errors().get(0).getFormattedMessage().contains("'embeding'"),
                errors().get(0).getFormattedMessage());
    }

    @Test
    void explicitSkillScopeTierBeatsUnrestrictedAnnotation() {
        var policy = resolve(EXPLICIT_TIER_SKILL, UnrestrictedAgent.class);
        assertNotNull(policy,
                "a skill that declares its scopeTier explicitly keeps its ## Guardrails policy "
                        + "over an unrestricted annotation");
        assertEquals("Order-status support for an online store", policy.config().purpose());
        assertEquals(AgentScope.Tier.RULE_BASED, policy.config().tier());
        assertFalse(policy.config().unrestricted());
        assertEquals(List.of("gambling"), policy.config().forbiddenTopics(),
                "an unrestricted annotation contributes no forbidden topic");
        assertTrue(appender.list.isEmpty(), "resolving logs nothing: " + appender.list);
        assertTrue(report(EXPLICIT_TIER_SKILL, UnrestrictedAgent.class, "skill:support"));
        assertEquals(1, warnings().size(), "the overridden unrestricted declaration is reported: "
                + appender.list);
        var message = warnings().get(0).getFormattedMessage();
        assertTrue(message.contains("unrestricted = true"), message);
        assertTrue(message.contains(UnrestrictedAgent.class.getName()), message);
        assertTrue(message.contains("skill:support"), message);
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
        assertTrue(ScopePolicyResolver.reportScopeOverrides(
                skill, UnrestrictedAgent.class, "assistant", "Assistant", "skill:assistant"));
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
        ScopePolicyResolver.reportScopeOverrides(
                SkillFileParser.parse(DERIVED_ONLY_SKILL), UnrestrictedAgent.class, "assistant", "Assistant", "");
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
        assertFalse(ScopePolicyResolver.reportScopeOverrides(
                SkillFileParser.parse(DERIVED_ONLY_SKILL), UnjustifiedUnrestrictedAgent.class,
                "assistant", "Assistant", ""));
    }

    @Test
    void explicitSkillScopeTierWithoutGuardrailsLeavesTheAnnotationInCharge() {
        var policy = resolve(EXPLICIT_TIER_NO_GUARDRAILS_SKILL, RestrictedAgent.class);
        assertNotNull(policy);
        assertEquals("Annotation-declared purpose", policy.config().purpose(),
                "a scopeTier with no ## Guardrails has no skill policy to keep");
        assertEquals(List.of("gambling"), policy.config().forbiddenTopics());
        assertEquals(1, policy.legs().size());

        assertTrue(report(EXPLICIT_TIER_NO_GUARDRAILS_SKILL, RestrictedAgent.class, ""));
        assertEquals(1, warnings().size(), appender.list.toString());
        assertTrue(warnings().get(0).getFormattedMessage().contains("no ## Guardrails"),
                warnings().get(0).getFormattedMessage());
    }

    @Test
    void explicitSkillScopeTierWithoutGuardrailsAloneIsReported() {
        assertNull(resolve(EXPLICIT_TIER_NO_GUARDRAILS_SKILL, UnannotatedAgent.class));
        assertTrue(appender.list.isEmpty(), "resolving logs nothing: " + appender.list);
        assertTrue(report(EXPLICIT_TIER_NO_GUARDRAILS_SKILL, UnannotatedAgent.class, ""));
        assertEquals(1, warnings().size(), "the scopeTier has nothing to enforce: " + appender.list);
    }

    @Test
    void explicitSkillScopeTierWithoutGuardrailsKeepsTheOtherAnnotationsAsWritten() {
        var unrestricted = resolve(EXPLICIT_TIER_NO_GUARDRAILS_SKILL, UnrestrictedAgent.class);
        assertNotNull(unrestricted);
        assertTrue(unrestricted.config().unrestricted());
        assertNull(resolve(EXPLICIT_TIER_NO_GUARDRAILS_SKILL, PurposelessAgent.class),
                "an invalid annotation with nothing to fall back to installs nothing");
    }

    @Test
    void restrictiveAnnotationBeatsGuardrailsDerivedScope() {
        var policy = resolve(DERIVED_TOPIC_SKILL, RestrictedAgent.class);
        assertNotNull(policy);
        assertEquals("Annotation-declared purpose", policy.config().purpose(),
                "an explicit restrictive @AgentScope beats a scope ## Guardrails alone derive");
        assertEquals(AgentScope.Breach.DENY, policy.config().onBreach());
        assertEquals("Annotation redirect", policy.config().redirectMessage());
        assertEquals(AgentScope.Tier.RULE_BASED, policy.config().tier());
        assertEquals(0.6, policy.config().similarityThreshold());
        assertEquals("annotation:" + RestrictedAgent.class.getName(), policy.source());
        assertEquals("scope::" + RestrictedAgent.class.getSimpleName(), policy.name());
    }

    @Test
    void restrictiveAnnotationUnionsTheSkillTopicProhibitions() {
        var policy = resolve(DERIVED_TOPIC_SKILL, RestrictedAgent.class);
        assertNotNull(policy);
        assertEquals(List.of("gambling", "politics"), policy.config().forbiddenTopics(),
                "the skill's explicit topic prohibitions join the annotation's forbidden "
                        + "topics; its instruction lines stay prompt-only");
    }

    @Test
    void unionDeduplicatesTopicsIgnoringCase() {
        var policy = resolve(DERIVED_TOPIC_SKILL, PoliticsRestrictedAgent.class);
        assertNotNull(policy);
        assertEquals(List.of("Politics"), policy.config().forbiddenTopics(),
                "a topic both declare is kept once, in the winning declaration's spelling");
    }

    @Test
    void invalidRestrictiveAnnotationFailsClosedToTheDerivedScope() {
        var policy = resolve(DERIVED_TOPIC_SKILL, PurposelessAgent.class);
        assertNotNull(policy, "an invalid annotation must not leave the agent unscoped "
                + "when its skill's guardrails derive a scope");
        assertEquals("Order-status support for an online store", policy.config().purpose());
        assertEquals(List.of("politics", "gambling"), policy.config().forbiddenTopics(),
                "the invalid annotation's topic bans still narrow the derived scope");
    }

    @Test
    void invalidRestrictiveAnnotationIsReportedOncePerRegistrationNotPerResolution() {
        // A processor resolves once per invocation path (web chain, pipeline).
        resolve(DERIVED_TOPIC_SKILL, PurposelessAgent.class);
        resolve(DERIVED_TOPIC_SKILL, PurposelessAgent.class);
        assertTrue(errors().isEmpty(), "resolving must not log the invalid annotation: "
                + errors());

        assertTrue(ScopePolicyResolver.reportScopeOverrides(
                SkillFileParser.parse(DERIVED_TOPIC_SKILL), PurposelessAgent.class, "assistant", "Assistant", "skill:support"));
        var errors = errors();
        assertEquals(1, errors.size(), "the invalid annotation is reported once: " + errors);
        var message = errors.get(0).getFormattedMessage();
        assertTrue(message.contains(PurposelessAgent.class.getName()), message);
        assertTrue(message.contains("'assistant'"), message);
        assertTrue(message.contains("skill:support"), message);
    }

    @Test
    void validRestrictiveAnnotationReportsNothing() {
        assertFalse(ScopePolicyResolver.reportScopeOverrides(
                SkillFileParser.parse(DERIVED_TOPIC_SKILL), RestrictedAgent.class, "assistant", "Assistant", "skill:support"));
        assertTrue(appender.list.isEmpty(), appender.list.toString());
    }

    @Test
    void noAnnotationKeepsTheGuardrailsDerivedScope() {
        var policy = resolve(DERIVED_TOPIC_SKILL, UnannotatedAgent.class);
        assertNotNull(policy);
        assertEquals("Order-status support for an online store", policy.config().purpose());
        assertEquals(List.of("politics"), policy.config().forbiddenTopics());
        assertEquals("skill-guardrails:assistant", policy.source());
    }

    @Test
    void restrictiveAnnotationWithoutGuardrailsIsUnchanged() {
        var policy = resolve(NO_GUARDRAILS_SKILL, RestrictedAgent.class);
        assertNotNull(policy);
        assertEquals("Annotation-declared purpose", policy.config().purpose());
        assertEquals(List.of("gambling"), policy.config().forbiddenTopics());
    }

    @Test
    void unrestrictedAnnotationWithoutGuardrailsIsUnchanged() {
        var policy = resolve(NO_GUARDRAILS_SKILL, UnrestrictedAgent.class);
        assertNotNull(policy, "with no ## Guardrails the annotation is honored as before");
        assertTrue(policy.config().unrestricted());
        assertFalse(ScopePolicyResolver.reportScopeOverrides(
                SkillFileParser.parse(NO_GUARDRAILS_SKILL), UnrestrictedAgent.class, "assistant", "Assistant", ""));
        assertTrue(warnings().isEmpty());
    }

    @Test
    void invalidAnnotationWithoutGuardrailsIsReportedByTheAnnotationFallbackOnly() {
        assertNull(resolve(NO_GUARDRAILS_SKILL, PurposelessAgent.class));
        assertFalse(report(NO_GUARDRAILS_SKILL, PurposelessAgent.class, ""),
                "the annotation fallback already logs this one at ERROR");
        assertTrue(errors().isEmpty(), errors().toString());
    }

    @Test
    void noSkillFileFallsThroughToTheAnnotation() {
        assertNotNull(ScopePolicyResolver.resolve(null, RestrictedAgent.class,
                "assistant", "Assistant", "/atmosphere/agent/assistant"));
        assertNull(ScopePolicyResolver.resolve(null, UnannotatedAgent.class,
                "assistant", "Assistant", "/atmosphere/agent/assistant"));
        assertFalse(ScopePolicyResolver.reportScopeOverrides(
                null, UnrestrictedAgent.class, "assistant", "Assistant", ""));
    }
}
