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

import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.atmosphere.ai.governance.scope.ScopePolicyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves the admission {@link ScopePolicy} an agent-bearing class mandates,
 * with explicit precedence shared by every processor that consumes skill files
 * ({@code @Agent}, {@code @Coordinator}). A skill's {@code ## Guardrails} section
 * alone only <em>derives</em> a scope; a skill frontmatter {@code scopeTier} and
 * a class {@link AgentScope} annotation are <em>explicit</em> declarations. An
 * explicit declaration beats a derived one. When both the skill and the
 * annotation are explicit, the stricter wins: a request is admitted only when
 * every restrictive declaration admits it. None of these cases is silent:
 * {@link #reportScopeOverrides} logs them at registration.
 * <ol>
 *   <li>a skill-level {@code scopeTier: none} (or {@code off} / {@code disabled})
 *       opt-out is the least strict explicit declaration: with no restrictive
 *       {@code @AgentScope} on the class no admission policy is installed,
 *       whether or not the skill has {@code ## Guardrails}; a valid restrictive
 *       {@code @AgentScope} is stricter, so its policy is installed as written,
 *       and the skill's {@code ## Guardrails} stay prompt-only;</li>
 *   <li>a skill with {@code ## Guardrails} whose frontmatter declares any other
 *       {@code scopeTier} has made its scope explicit. Alone, or beside an
 *       {@code @AgentScope(unrestricted = true)} (which it overrides), that
 *       policy ({@link #fromSkillGuardrails}) is installed. Beside a valid
 *       restrictive {@code @AgentScope}, both are enforced by one multi-leg
 *       policy ({@link ScopePolicy#legs()}): one leg keeps the skill's purpose,
 *       tier and threshold, the other the annotation's, and a request must pass
 *       both, cheapest tier first. The settings that have an order merge to the
 *       stricter value and are shared by every leg: the union of both
 *       declarations' forbidden topics, {@code DENY} over a redirect, the
 *       annotation's redirect message, and the post-response check when either
 *       declaration asks for it. Two legs of the same tier and the same purpose
 *       collapse into one with the higher threshold; a skill that declares no
 *       purpose takes the annotation's;</li>
 *   <li>otherwise an explicit {@code @AgentScope(unrestricted = true,
 *       justification = "...")} on the class overrides the scope a
 *       {@code ## Guardrails} section alone would derive: no admission policy is
 *       installed, and the processor logs it once at registration
 *       ({@link #reportScopeOverrides});</li>
 *   <li>otherwise an explicit restrictive {@code @AgentScope} on the class beats
 *       the derived scope: the annotation's policy is installed — its purpose,
 *       tier, threshold, breach mode and redirect — with the skill's explicit
 *       topic prohibitions ({@link #forbiddenTopicsFrom}) unioned into its
 *       forbidden topics;</li>
 *   <li>otherwise the {@code ## Guardrails}-derived policy is installed, and a
 *       class whose skill declares no guardrails (and no {@code scopeTier}
 *       opt-out) falls back to its
 *       {@link AgentScope} annotation — including the {@code unrestricted = true}
 *       opt-out, which makes an intentionally open agent an <em>explicit,
 *       lintable declaration</em> instead of a silent gap (Correctness
 *       Invariant #5 — a declared posture must match runtime behavior, so the
 *       annotation must be enforced, not just present).</li>
 * </ol>
 *
 * <p>Unions keep the installed policy's topics first (the skill's, when both
 * declarations are explicit), then add the other declaration's topics not
 * already present, compared ignoring case.</p>
 *
 * <p>An unrestricted annotation without a justification, or a restrictive one
 * without a purpose, is invalid and never overrides a skill's guardrails: the
 * override fails closed to the skill's policy, which still takes the invalid
 * restrictive annotation's forbidden topics (a topic ban can only narrow).</p>
 *
 * <p>Living in one place keeps the precedence identical across every annotation
 * that loads a skill file (Correctness Invariant #7 — Mode Parity): a skill's
 * guardrails are an <em>enforced</em> admission boundary wherever the file is
 * accepted, never prose the LLM may ignore on one path but not another.</p>
 */
public final class ScopePolicyResolver {

    private static final Logger logger = LoggerFactory.getLogger(ScopePolicyResolver.class);

    /**
     * Default cosine-similarity admission threshold for a skill-derived
     * {@link ScopePolicy} — mirrors {@code @AgentScope.similarityThreshold()}'s
     * default so a {@code ## Guardrails} scope behaves identically to the
     * annotation when no explicit hint is given.
     */
    private static final double DEFAULT_SCOPE_SIMILARITY_THRESHOLD = 0.45;

    /** Fallback purpose when a skill / agent declares no description. */
    private static final String DEFAULT_SCOPE_PURPOSE = "the agent's stated purpose";

    /** Frontmatter {@code scopeTier} values that disable admission enforcement. */
    private static final Set<String> SCOPE_TIER_OPT_OUT = Set.of("none", "off", "disabled");

    /**
     * Explicit prohibition forms whose object is an enforceable topic:
     * "Never discuss X", "Do not mention Y", "Off-limits: a, b", "Forbidden
     * topics: c". Deliberately verb-specific — "Never diagnose" is response
     * guidance about how to answer, not a topic ban on the agent's own domain.
     */
    private static final Pattern TOPIC_PROHIBITION = Pattern.compile(
            "(?i)^(?:(?:never|do\\s+not|don't)\\s+"
                    + "(?:discuss|mention|talk\\s+about|answer\\s+questions\\s+about|engage\\s+(?:in|on|with))"
                    + "|(?:off[-\\s]?limits|forbidden)(?:\\s+topics?)?\\s*:?"
                    + "|no\\s+discussion\\s+of)\\s+(.+)$");

    /** Leading words that mark a guardrail line as response guidance, not a topic label. */
    private static final Pattern INSTRUCTION_LEAD = Pattern.compile(
            "(?i)^(?:always|never|do|don't|be|if|when|avoid|refuse|state|recommend|use|only|"
                    + "must|should|provide|direct|suggest|keep|stay|remember|include|explain)\\b");

    private ScopePolicyResolver() {
    }

    /**
     * Resolve the class's scope policy with the precedence documented on this
     * class: a skill-level {@code scopeTier: none} opt-out installs nothing
     * unless a valid restrictive {@code @AgentScope} is declared, which is then
     * installed as written; an explicit skill {@code scopeTier} keeps the
     * {@code ## Guardrails}-derived policy, merged with a valid restrictive
     * annotation into one multi-leg policy that admits only what both admit;
     * then an explicit {@code @AgentScope(unrestricted = true)} overriding a
     * derived scope, then an explicit restrictive {@code @AgentScope} (plus the
     * skill's topic prohibitions), then the {@code ## Guardrails}-derived
     * policy, then the {@link AgentScope} annotation fallback. A {@code null}
     * skill file, or one without {@code ## Guardrails} that does not opt out
     * with {@code scopeTier: none}, falls straight through to the annotation.
     * Logs nothing, so a processor may resolve more than once per registration;
     * it reports overrides, merges and an invalid annotation once through
     * {@link #reportScopeOverrides}.
     *
     * @param skillFile      the parsed skill file, or {@code null} when the
     *                       class declares none
     * @param agentClass     the annotated class, consulted for {@link AgentScope}
     * @param agentName      agent name, used in policy naming and log lines
     * @param agentDescription description used as the scope purpose when the
     *                       skill frontmatter declares none
     * @param annotationPath registration path passed to
     *                       {@link ScopePolicyBuilder#build(AgentScope, Class, String)}
     *                       for the annotation fallback
     * @return the enforced policy, or {@code null} when the class declares no scope
     */
    public static ScopePolicy resolve(SkillFileParser skillFile, Class<?> agentClass,
                                      String agentName, String agentDescription,
                                      String annotationPath) {
        return resolve(null, skillFile, agentClass, agentName, agentDescription, annotationPath);
    }

    /**
     * {@link #resolve(SkillFileParser, Class, String, String, String)} with the
     * scope guardrails of one application
     * ({@link ScopePolicyBuilder#build(AiEnvironment, ScopeConfig, String, String)}),
     * so the policy screens with that application's embedding runtime or
     * decision model. The {@code @Agent} and {@code @Coordinator} processors
     * pass theirs.
     *
     * @param environment      the application's environment; {@code null}
     *                         resolves the guardrails for {@link AiEnvironment#current()}
     * @param skillFile        the parsed skill file, or {@code null}
     * @param agentClass       the annotated class, consulted for {@link AgentScope}
     * @param agentName        agent name, used in policy naming and log lines
     * @param agentDescription description used as the scope purpose when the
     *                         skill frontmatter declares none
     * @param annotationPath   registration path for the annotation fallback
     * @return the enforced policy, or {@code null} when the class declares no scope
     */
    public static ScopePolicy resolve(AiEnvironment environment, SkillFileParser skillFile,
                                      Class<?> agentClass, String agentName,
                                      String agentDescription, String annotationPath) {
        var scopeAnnotation = agentClass == null
                ? null : agentClass.getAnnotation(AgentScope.class);
        if (skillFile != null) {
            var tierHint = tierHint(skillFile);
            if (SCOPE_TIER_OPT_OUT.contains(tierHint)) {
                // The opt-out is the least strict explicit declaration: a valid
                // restrictive annotation beats it and is enforced as written.
                var annotationConfig = restrictiveConfig(scopeAnnotation);
                return annotationConfig == null
                        ? null : annotationPolicy(environment, annotationConfig, agentClass);
            }
            var guardrails = skillFile.listItems("Guardrails");
            if (!guardrails.isEmpty()) {
                if (!tierHint.isEmpty()) {
                    // Two explicit declarations: the stricter wins, leg by leg.
                    var merge = merge(skillFile, scopeAnnotation, agentDescription);
                    if (merge != null) {
                        return ScopePolicyBuilder.build(environment, merge.legs(), "scope::" + agentName,
                                "skill-guardrails:" + agentName
                                        + "+annotation:" + agentClass.getName());
                    }
                    return fromSkillGuardrails(environment, skillFile, agentName, agentDescription,
                            isRestrictive(scopeAnnotation)
                                    ? List.of(scopeAnnotation.forbiddenTopics()) : List.of());
                }
                if (unrestrictedOverridesGuardrails(skillFile, scopeAnnotation)) {
                    return null;    // explicit unrestricted annotation beats a derived scope
                }
                if (isRestrictive(scopeAnnotation)) {
                    var annotationConfig = restrictiveConfig(scopeAnnotation);
                    if (annotationConfig != null) {
                        // explicit annotation beats a derived scope
                        return annotationPolicy(environment, withForbiddenTopics(annotationConfig,
                                union(annotationConfig.forbiddenTopics(),
                                        forbiddenTopicsFrom(guardrails))), agentClass);
                    }
                    // An invalid annotation fails closed to the derived scope,
                    // which still takes its topic bans (they can only narrow).
                    return fromSkillGuardrails(environment, skillFile, agentName, agentDescription,
                            List.of(scopeAnnotation.forbiddenTopics()));
                }
                return fromSkillGuardrails(environment, skillFile, agentName, agentDescription,
                        List.of());
            }
        }
        if (scopeAnnotation == null) {
            return null;
        }
        return ScopePolicyBuilder.build(environment, scopeAnnotation, agentClass, annotationPath)
                .orElse(null);
    }

    /** Whether the class declares a restrictive (not {@code unrestricted}) {@link AgentScope}. */
    private static boolean isRestrictive(AgentScope scopeAnnotation) {
        return scopeAnnotation != null && !scopeAnnotation.unrestricted();
    }

    /**
     * The config a restrictive {@link AgentScope} declares, or {@code null} when
     * the annotation is absent, unrestricted or invalid (a blank purpose). The
     * invalid annotation is reported at ERROR once per registration by
     * {@link #reportScopeOverrides}, not here: a processor resolves once per
     * invocation path.
     */
    private static ScopeConfig restrictiveConfig(AgentScope scopeAnnotation) {
        if (!isRestrictive(scopeAnnotation)) {
            return null;
        }
        try {
            return ScopeConfig.fromAnnotation(scopeAnnotation);
        } catch (IllegalArgumentException e) {
            logger.debug("Invalid restrictive @AgentScope: {}", e.getMessage());
            return null;
        }
    }

    /** The policy a valid restrictive {@link AgentScope} declares, named after the class. */
    private static ScopePolicy annotationPolicy(AiEnvironment environment, ScopeConfig config,
                                                Class<?> agentClass) {
        return ScopePolicyBuilder.build(environment, config,
                "scope::" + agentClass.getSimpleName(), "annotation:" + agentClass.getName());
    }

    /** {@code config} with its forbidden topics replaced by {@code topics}. */
    private static ScopeConfig withForbiddenTopics(ScopeConfig config, List<String> topics) {
        return new ScopeConfig(config.purpose(), topics, config.onBreach(),
                config.redirectMessage(), config.tier(), config.similarityThreshold(),
                config.postResponseCheck(), config.unrestricted(), config.justification());
    }

    /**
     * {@code primary} followed by each {@code additional} topic not already
     * present, compared ignoring case and surrounding blanks; blank topics are
     * dropped.
     */
    private static List<String> union(List<String> primary, List<String> additional) {
        var seen = new HashSet<String>();
        var topics = new ArrayList<String>();
        for (var list : List.of(primary, additional)) {
            for (var topic : list) {
                if (topic == null || topic.isBlank()) {
                    continue;
                }
                if (seen.add(topic.strip().toLowerCase(Locale.ROOT))) {
                    topics.add(topic);
                }
            }
        }
        return List.copyOf(topics);
    }

    /**
     * How an explicit skill scope (a {@code scopeTier} with {@code ## Guardrails})
     * and a valid restrictive {@link AgentScope} merge into the legs of one
     * policy.
     *
     * @param legs                the legs to enforce, the annotation's first
     * @param skill               the skill's own scope config
     * @param annotation          the annotation's own scope config
     * @param collapsed           the two declarations shared a tier and a
     *                            purpose and became one leg
     * @param placeholderReplaced the skill declared no purpose, so its leg took
     *                            the annotation's
     * @param purposesDiffer      the legs confine to two different purposes
     * @param postAddedToLlmLeg   the post-response check one declaration asked
     *                            for now also runs an LLM-classifier leg whose
     *                            declaration did not ask for it
     */
    private record Merge(List<ScopeConfig> legs, ScopeConfig skill, ScopeConfig annotation,
                         boolean collapsed, boolean placeholderReplaced, boolean purposesDiffer,
                         boolean postAddedToLlmLeg) {
    }

    /**
     * Merge the skill's explicit scope with the class's restrictive
     * {@link AgentScope}, or {@code null} when the annotation is absent,
     * unrestricted or invalid. The ordered settings take the stricter value
     * and are shared by every leg; purpose, tier and threshold stay per leg,
     * because a tier or a threshold only means something against its own
     * purpose. Logs nothing.
     */
    private static Merge merge(SkillFileParser skillFile, AgentScope scopeAnnotation,
                               String agentDescription) {
        var annotation = restrictiveConfig(scopeAnnotation);
        if (annotation == null) {
            return null;
        }
        var skillScope = skillScope(skillFile, agentDescription, List.of());
        var skill = skillScope.config();
        var topics = union(skill.forbiddenTopics(), annotation.forbiddenTopics());
        var breach = stricter(skill.onBreach(), annotation.onBreach());
        // The skill declares no redirect, so the annotation's is the one text.
        var redirect = annotation.redirectMessage();
        var post = skill.postResponseCheck() || annotation.postResponseCheck();
        var skillPurpose = skillScope.placeholderPurpose() ? annotation.purpose() : skill.purpose();
        var samePurpose = normalized(skillPurpose).equals(normalized(annotation.purpose()));
        if (samePurpose && skill.tier() == annotation.tier()) {
            var leg = new ScopeConfig(annotation.purpose(), topics, breach, redirect,
                    annotation.tier(),
                    Math.max(skill.similarityThreshold(), annotation.similarityThreshold()),
                    post, false, "");
            return new Merge(List.of(leg), skill, annotation, true,
                    skillScope.placeholderPurpose(), false, false);
        }
        var annotationLeg = new ScopeConfig(annotation.purpose(), topics, breach, redirect,
                annotation.tier(), annotation.similarityThreshold(), post, false, "");
        var skillLeg = new ScopeConfig(skillPurpose, topics, breach, redirect,
                skill.tier(), skill.similarityThreshold(), post, false, "");
        var postAddedToLlmLeg = post
                && (skill.tier() == AgentScope.Tier.LLM_CLASSIFIER && !skill.postResponseCheck()
                    || annotation.tier() == AgentScope.Tier.LLM_CLASSIFIER
                        && !annotation.postResponseCheck());
        // The annotation's leg first: on a tier tie it runs first (the policy
        // sorts the legs by tier and keeps this order within a tier).
        return new Merge(List.of(annotationLeg, skillLeg), skill, annotation, false,
                skillScope.placeholderPurpose(), !samePurpose, postAddedToLlmLeg);
    }

    /** {@code DENY} over any redirect; {@code CUSTOM_MESSAGE} over {@code POLITE_REDIRECT}. */
    private static AgentScope.Breach stricter(AgentScope.Breach a, AgentScope.Breach b) {
        if (a == AgentScope.Breach.DENY || b == AgentScope.Breach.DENY) {
            return AgentScope.Breach.DENY;
        }
        if (a == AgentScope.Breach.CUSTOM_MESSAGE || b == AgentScope.Breach.CUSTOM_MESSAGE) {
            return AgentScope.Breach.CUSTOM_MESSAGE;
        }
        return AgentScope.Breach.POLITE_REDIRECT;
    }

    private static String normalized(String purpose) {
        return purpose.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Report, once per registration, how the class's {@link AgentScope} and its
     * skill's scope declarations resolved when that is not what either one says
     * on its own. {@link #resolve} logs nothing, because a processor resolves
     * once per invocation path; the processor calls this once, beside it:
     * <ul>
     *   <li>one ERROR when the skill's {@code scopeTier} is not a known tier,
     *       naming the token: {@code EMBEDDING_SIMILARITY} is enforced
     *       instead when the skill has {@code ## Guardrails}; without them the
     *       ERROR says no skill scope is enforced;</li>
     *   <li>one WARN when a restrictive {@code @AgentScope} beats the skill's
     *       {@code scopeTier: none} opt-out and is enforced as written;</li>
     *   <li>one WARN when the skill declares a {@code scopeTier} but no
     *       {@code ## Guardrails}, so the tier has no skill scope to
     *       enforce;</li>
     *   <li>one line when the skill's explicit scope and a restrictive
     *       {@code @AgentScope} merge into one multi-leg policy, listing each
     *       leg's tier and purpose, the shared breach mode, whether the legs
     *       collapsed and the post-response check: INFO, or WARN when the legs
     *       confine to different purposes, the skill's leg took the
     *       annotation's purpose, or the merge turns the post-response check on
     *       for an LLM-classifier leg;</li>
     *   <li>one WARN when the skill's explicit scope overrides
     *       {@code @AgentScope(unrestricted = true)};</li>
     *   <li>one WARN when {@code @AgentScope(unrestricted = true)} overrides the
     *       scope the skill's {@code ## Guardrails} section would derive, naming
     *       the agent, the skill and the annotation's justification, so the
     *       operator sees that those guardrails stay prompt-only;</li>
     *   <li>one ERROR when a restrictive {@code @AgentScope} is invalid (a blank
     *       purpose) beside a skill that has {@code ## Guardrails} or opts out,
     *       naming the class and what is enforced instead. Without either, the
     *       annotation fallback reports it
     *       ({@link ScopePolicyBuilder#build(AgentScope, Class, String)}).</li>
     * </ul>
     *
     * @param skillFile        the parsed skill file, or {@code null} when the
     *                         class declares none
     * @param agentClass       the annotated class, consulted for {@link AgentScope}
     * @param agentName        agent name for the log line
     * @param agentDescription the description {@link #resolve} is given, which
     *                         decides whether the skill's scope has a purpose
     * @param skillRef         the skill reference the class declares; blank for
     *                         an auto-discovered skill, which the log names by
     *                         its title
     * @return {@code true} when something was reported
     */
    public static boolean reportScopeOverrides(SkillFileParser skillFile, Class<?> agentClass,
                                               String agentName, String agentDescription,
                                               String skillRef) {
        if (skillFile == null || agentClass == null) {
            return false;
        }
        var scopeAnnotation = agentClass.getAnnotation(AgentScope.class);
        var skillName = skillRef != null && !skillRef.isBlank()
                ? skillRef : firstNonBlank(skillFile.title(), "(auto-discovered)");
        var tierHint = tierHint(skillFile);
        var optOut = SCOPE_TIER_OPT_OUT.contains(tierHint);
        var hasGuardrails = !skillFile.listItems("Guardrails").isEmpty();
        var reported = false;
        if (!optOut && knownTier(tierHint).isEmpty()) {
            // Without ## Guardrails resolve() builds no skill scope, so no tier runs.
            logger.error("Agent '{}': skill '{}' declares an unknown scopeTier '{}' — {} "
                            + "(use rule_based, embedding, semantic, llm, or none to opt out)",
                    agentName, skillName, tierHint, hasGuardrails
                            ? "EMBEDDING_SIMILARITY is enforced instead"
                            : "the skill has no ## Guardrails, so no skill scope is enforced");
            reported = true;
        }
        var annotationConfig = restrictiveConfig(scopeAnnotation);
        if (isRestrictive(scopeAnnotation) && annotationConfig == null && (optOut || hasGuardrails)) {
            reportInvalidAnnotation(scopeAnnotation, agentClass, agentName, skillName, tierHint,
                    optOut, hasGuardrails);
            reported = true;
        }
        if (optOut) {
            if (annotationConfig != null) {
                logger.warn("Agent '{}': skill '{}' declares scopeTier: {}, but the restrictive "
                                + "@AgentScope on {} is stricter and is enforced as written "
                                + "(purpose '{}', tier {}, forbidden topics {}); the skill's "
                                + "## Guardrails stay prompt-only. To run the agent unscoped, declare "
                                + "@AgentScope(unrestricted = true, justification = \"...\") instead",
                        agentName, skillName, tierHint, agentClass.getName(),
                        annotationConfig.purpose(), annotationConfig.tier(),
                        annotationConfig.forbiddenTopics());
                reported = true;
            }
            return reported;
        }
        if (!tierHint.isEmpty()) {
            if (!hasGuardrails) {
                logger.warn("Agent '{}': skill '{}' declares scopeTier: {} but no ## Guardrails, "
                                + "so the tier has no skill scope to enforce; the @AgentScope on {}, "
                                + "if any, applies as written (add ## Guardrails to the skill or "
                                + "remove scopeTier)",
                        agentName, skillName, tierHint, agentClass.getName());
                return true;
            }
            var merge = merge(skillFile, scopeAnnotation, agentDescription);
            if (merge != null) {
                reportMerge(merge, agentClass, agentName, skillName, tierHint);
                return true;
            }
            if (scopeAnnotation != null && scopeAnnotation.unrestricted()) {
                logger.warn("Agent '{}': skill '{}' declares scopeTier: {}, so the scope its "
                                + "## Guardrails declare is enforced and overrides "
                                + "@AgentScope(unrestricted = true) on {} (remove scopeTier from "
                                + "the skill to run the agent unscoped)",
                        agentName, skillName, tierHint, agentClass.getName());
                return true;
            }
            return reported;
        }
        if (unrestrictedOverridesGuardrails(skillFile, scopeAnnotation)) {
            logger.warn("Agent '{}' declares @AgentScope(unrestricted = true) — it overrides the "
                            + "admission scope skill '{}' would derive from its ## Guardrails, "
                            + "which stay prompt-only (set scopeTier in the skill frontmatter to "
                            + "keep enforcing them). Justification: {}",
                    agentName, skillName, scopeAnnotation.justification());
            return true;
        }
        return reported;
    }

    private static void reportInvalidAnnotation(AgentScope scopeAnnotation, Class<?> agentClass,
                                                String agentName, String skillName,
                                                String tierHint, boolean optOut,
                                                boolean hasGuardrails) {
        String reason;
        try {
            ScopeConfig.fromAnnotation(scopeAnnotation);
            reason = "invalid";
        } catch (IllegalArgumentException e) {
            reason = e.getMessage();
        }
        if (optOut) {
            logger.error("Agent '{}': invalid @AgentScope on {} ({}) — skill '{}' declares "
                            + "scopeTier: {}, so no admission scope policy runs; fix the annotation",
                    agentName, agentClass.getName(), reason, skillName, tierHint);
        } else if (hasGuardrails) {
            logger.error("Agent '{}': invalid @AgentScope on {} ({}) — the scope skill '{}' "
                            + "declares in its ## Guardrails is enforced instead, plus the "
                            + "annotation's forbidden topics {}; fix the annotation",
                    agentName, agentClass.getName(), reason, skillName,
                    List.of(scopeAnnotation.forbiddenTopics()));
        }
    }

    private static void reportMerge(Merge merge, Class<?> agentClass, String agentName,
                                    String skillName, String tierHint) {
        var legs = new ArrayList<String>(merge.legs().size());
        for (var leg : merge.legs().stream()
                .sorted(Comparator.comparingInt(l -> l.tier().ordinal())).toList()) {
            legs.add(leg.tier() + " '" + leg.purpose() + "'");
        }
        var shared = merge.legs().get(0);
        var notes = new ArrayList<String>();
        if (merge.purposesDiffer()) {
            notes.add("the two purposes differ, so only requests within both are admitted");
        }
        if (merge.placeholderReplaced()) {
            notes.add("the skill declares no purpose, so its leg uses the annotation's");
        }
        if (merge.postAddedToLlmLeg()) {
            notes.add("the post-response check now also runs the LLM_CLASSIFIER leg on every "
                    + "response (one more model call per response)");
        }
        var message = "Agent '{}': skill '{}' declares scopeTier: {} and {} declares a "
                + "restrictive @AgentScope; both are enforced, a request must pass every leg: "
                + "legs {}{}, breach mode {}, forbidden topics {}, post-response check {}{}";
        Object[] args = {agentName, skillName, tierHint, agentClass.getName(), legs,
                merge.collapsed() ? " (same tier and purpose, collapsed into one)" : "",
                shared.onBreach(), shared.forbiddenTopics(),
                shared.postResponseCheck() ? "on" : "off",
                notes.isEmpty() ? "" : " — " + String.join("; ", notes)};
        if (notes.isEmpty()) {
            logger.info(message, args);
        } else {
            logger.warn(message, args);
        }
    }

    /**
     * Whether an explicit, justified {@code @AgentScope(unrestricted = true)}
     * overrides the scope the skill's {@code ## Guardrails} section would derive:
     * the skill must carry guardrails but no explicit {@code scopeTier}. An
     * unjustified annotation is invalid and does not override (fails closed).
     */
    private static boolean unrestrictedOverridesGuardrails(SkillFileParser skillFile,
                                                           AgentScope scopeAnnotation) {
        return scopeAnnotation != null
                && scopeAnnotation.unrestricted()
                && !scopeAnnotation.justification().isBlank()
                && tierHint(skillFile).isEmpty()
                && !skillFile.listItems("Guardrails").isEmpty();
    }

    /**
     * Build the {@link ScopePolicy} a skill file mandates via its
     * {@code ## Guardrails} section, or {@code null} when the skill declares
     * none (or opts out with {@code scopeTier: none}). This is the wiring that
     * makes a skill's guardrails an <em>enforced</em> admission boundary rather
     * than prose the LLM may ignore: the section's presence pins the agent to its
     * declared purpose (skill or agent description), explicit topic prohibitions
     * become enforced forbidden topics, and the enforcement tier comes from an
     * optional {@code scopeTier} / {@code scope-tier} frontmatter hint (default
     * {@link AgentScope.Tier#EMBEDDING_SIMILARITY}, which catches paraphrased
     * breaches without a per-request LLM call).
     *
     * <p>Only topic-declaration lines are enforced as forbidden topics: an
     * explicit prohibition ("Never discuss gambling", "Off-limits: politics,
     * religion") or a short bare topic label ("gambling"). Instruction-style
     * lines ("Never diagnose — only provide general guidance", "Be empathetic")
     * are response guidance — they ship in the system prompt but are NOT
     * admission topics. Treating them as topics turned an agent's own domain
     * questions away: a dental patient's pain-relief message embeds closer to
     * "Never diagnose…" than to the purpose and was silently redirected.</p>
     */
    public static ScopePolicy fromSkillGuardrails(SkillFileParser skillFile, String agentName,
                                                  String agentDescription) {
        return fromSkillGuardrails(null, skillFile, agentName, agentDescription, List.of());
    }

    /**
     * {@link #fromSkillGuardrails(SkillFileParser, String, String)} with the
     * scope guardrail of one application.
     *
     * @param environment      the application's environment; {@code null}
     *                         resolves the guardrail for {@link AiEnvironment#current()}
     * @param skillFile        the parsed skill file
     * @param agentName        agent name, used in policy naming
     * @param agentDescription description used as the scope purpose when the
     *                         skill frontmatter declares none
     * @return the enforced policy, or {@code null} when the skill declares no
     *         {@code ## Guardrails} or opts out
     */
    public static ScopePolicy fromSkillGuardrails(AiEnvironment environment, SkillFileParser skillFile,
                                                  String agentName, String agentDescription) {
        return fromSkillGuardrails(environment, skillFile, agentName, agentDescription, List.of());
    }

    /**
     * {@link #fromSkillGuardrails(SkillFileParser, String, String)} with
     * {@code annotationTopics} — a restrictive {@link AgentScope}'s forbidden
     * topics — unioned after the skill's own.
     */
    private static ScopePolicy fromSkillGuardrails(AiEnvironment environment, SkillFileParser skillFile,
                                                   String agentName, String agentDescription,
                                                   List<String> annotationTopics) {
        var guardrails = skillFile.listItems("Guardrails");
        if (guardrails.isEmpty()) {
            return null;
        }
        var tierHint = tierHint(skillFile);
        if (SCOPE_TIER_OPT_OUT.contains(tierHint)) {
            logger.info("Agent '{}' skill declares scopeTier: {} — guardrails stay "
                    + "prompt-only, no admission scope policy installed", agentName, tierHint);
            return null;
        }
        return ScopePolicyBuilder.build(environment,
                skillScope(skillFile, agentDescription, annotationTopics).config(),
                "scope::" + agentName, "skill-guardrails:" + agentName);
    }

    /**
     * The scope a skill's {@code ## Guardrails} declare, before a guardrail is
     * resolved for it.
     *
     * @param config             the scope config
     * @param placeholderPurpose neither the skill frontmatter nor the agent
     *                           declares a description, so the purpose is a
     *                           placeholder
     */
    private record SkillScope(ScopeConfig config, boolean placeholderPurpose) {
    }

    /**
     * The skill's scope config: the frontmatter or agent description as the
     * purpose, the explicit topic prohibitions (plus {@code extraTopics}) as
     * forbidden topics, and the {@code scopeTier} hint as the tier, with a
     * polite redirect and the default threshold.
     */
    private static SkillScope skillScope(SkillFileParser skillFile, String agentDescription,
                                         List<String> extraTopics) {
        var described = firstNonBlank(
                skillFile.frontmatter("description").orElse(null),
                agentDescription);
        var purpose = described.isEmpty() ? DEFAULT_SCOPE_PURPOSE : described;
        var config = new ScopeConfig(purpose,
                union(forbiddenTopicsFrom(skillFile.listItems("Guardrails")), extraTopics),
                AgentScope.Breach.POLITE_REDIRECT, null, resolveScopeTier(tierHint(skillFile)),
                DEFAULT_SCOPE_SIMILARITY_THRESHOLD, false, false, "");
        return new SkillScope(config, described.isEmpty());
    }

    /**
     * Extracts the enforceable forbidden topics from {@code ## Guardrails} lines:
     * explicit prohibitions contribute their object(s), short bare labels
     * contribute themselves, and everything else is response guidance left to
     * the system prompt.
     */
    public static List<String> forbiddenTopicsFrom(List<String> guardrails) {
        var topics = new ArrayList<String>();
        for (var line : guardrails) {
            if (line == null || line.isBlank()) {
                continue;
            }
            var trimmed = line.trim();
            var prohibition = TOPIC_PROHIBITION.matcher(trimmed);
            if (prohibition.matches()) {
                for (var topic : prohibition.group(1).split("\\s*(?:,|;|\\band\\b)\\s*")) {
                    addTopic(topics, topic);
                }
                continue;
            }
            if (isBareTopicLabel(trimmed)) {
                addTopic(topics, trimmed);
            }
        }
        return List.copyOf(topics);
    }

    /** The (lowercased, trimmed) {@code scopeTier} / {@code scope-tier} frontmatter hint. */
    private static String tierHint(SkillFileParser skillFile) {
        return skillFile.frontmatter("scopeTier")
                .or(() -> skillFile.frontmatter("scope-tier"))
                .orElse("").trim().toLowerCase(Locale.ROOT);
    }

    private static void addTopic(List<String> topics, String candidate) {
        var topic = candidate.strip();
        while (topic.endsWith(".")) {
            topic = topic.substring(0, topic.length() - 1).strip();
        }
        if (!topic.isEmpty()) {
            topics.add(topic);
        }
    }

    /**
     * A short noun-phrase line ("gambling", "competitor pricing") is a topic
     * label; anything with sentence punctuation, an instruction lead-in, or more
     * than four words reads as guidance prose.
     */
    private static boolean isBareTopicLabel(String line) {
        if (INSTRUCTION_LEAD.matcher(line).find()) {
            return false;
        }
        if (line.contains("--") || line.chars().anyMatch(
                c -> c == '.' || c == ';' || c == ':' || c == '!' || c == '?' || c == '—')) {
            return false;
        }
        return line.split("\\s+").length <= 4;
    }

    /**
     * Resolve the enforcement tier from the (lowercased, trimmed) frontmatter
     * hint. Unknown or absent values fall back to
     * {@link AgentScope.Tier#EMBEDDING_SIMILARITY}, so a typo never leaves the
     * agent unscoped; {@link #reportScopeOverrides} reports an unknown value at
     * ERROR once per registration. The opt-out values ({@code none} /
     * {@code off} / {@code disabled}) are handled by the caller before this
     * resolves.
     */
    private static AgentScope.Tier resolveScopeTier(String hint) {
        return knownTier(hint).orElse(AgentScope.Tier.EMBEDDING_SIMILARITY);
    }

    /**
     * The tier a (lowercased, trimmed) frontmatter hint names; an absent hint
     * names the default {@link AgentScope.Tier#EMBEDDING_SIMILARITY}, an unknown
     * one names none.
     */
    private static Optional<AgentScope.Tier> knownTier(String hint) {
        return switch (hint) {
            case "" -> Optional.of(AgentScope.Tier.EMBEDDING_SIMILARITY);
            case "rule_based", "rule-based", "rulebased" -> Optional.of(AgentScope.Tier.RULE_BASED);
            case "embedding", "embedding_similarity", "embedding-similarity" ->
                    Optional.of(AgentScope.Tier.EMBEDDING_SIMILARITY);
            case "semantic", "semantic_intent", "semantic-intent" ->
                    Optional.of(AgentScope.Tier.SEMANTIC_INTENT);
            case "llm", "llm_classifier", "llm-classifier" ->
                    Optional.of(AgentScope.Tier.LLM_CLASSIFIER);
            default -> Optional.empty();
        };
    }

    /** First non-blank candidate, or {@code ""} when all are blank. */
    private static String firstNonBlank(String... candidates) {
        for (var candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }
}
