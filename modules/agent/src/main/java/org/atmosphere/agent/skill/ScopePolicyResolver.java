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

import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.atmosphere.ai.governance.scope.ScopePolicyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves the admission {@link ScopePolicy} an agent-bearing class mandates,
 * with explicit precedence shared by every processor that consumes skill files
 * ({@code @Agent}, {@code @Coordinator}). An explicit declaration beats a derived
 * one, and two explicit declarations resolve restrictive. A skill's
 * {@code ## Guardrails} section alone only <em>derives</em> a scope; a skill
 * frontmatter {@code scopeTier} and a class {@link AgentScope} annotation are
 * <em>explicit</em> declarations:
 * <ol>
 *   <li>a skill-level {@code scopeTier: none} (or {@code off} / {@code disabled})
 *       opt-out beats everything — the skill is the agent's own artifact;</li>
 *   <li>a skill with {@code ## Guardrails} whose frontmatter declares any other
 *       {@code scopeTier} has made its scope explicit, so that policy
 *       ({@link #fromSkillGuardrails}) is installed — its purpose, tier and
 *       breach mode — with the forbidden topics of a restrictive
 *       {@code @AgentScope} on the class unioned into its own;</li>
 *   <li>otherwise an explicit {@code @AgentScope(unrestricted = true,
 *       justification = "...")} on the class overrides the scope a
 *       {@code ## Guardrails} section alone would derive: no admission policy is
 *       installed, and the processor logs it once at registration
 *       ({@link #warnIfUnrestrictedOverridesGuardrails});</li>
 *   <li>otherwise an explicit restrictive {@code @AgentScope} on the class beats
 *       the derived scope: the annotation's policy is installed — its purpose,
 *       tier, threshold, breach mode and redirect — with the skill's explicit
 *       topic prohibitions ({@link #forbiddenTopicsFrom}) unioned into its
 *       forbidden topics;</li>
 *   <li>otherwise the {@code ## Guardrails}-derived policy is installed, and a
 *       class whose skill declares no guardrails falls back to its
 *       {@link AgentScope} annotation — including the {@code unrestricted = true}
 *       opt-out, which makes an intentionally open agent an <em>explicit,
 *       lintable declaration</em> instead of a silent gap (Correctness
 *       Invariant #5 — a declared posture must match runtime behavior, so the
 *       annotation must be enforced, not just present).</li>
 * </ol>
 *
 * <p>Unions keep the installed policy's topics first, then add the other
 * declaration's topics not already present, compared ignoring case.</p>
 *
 * <p>An unrestricted annotation without a justification, or a restrictive one
 * without a purpose, is invalid and never overrides a skill's guardrails: the
 * override fails closed to the derived policy.</p>
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
     * class: skill-level {@code scopeTier: none} opt-out first, then an explicit
     * skill {@code scopeTier} keeping the {@code ## Guardrails}-derived policy
     * (plus a restrictive annotation's forbidden topics), then an explicit
     * {@code @AgentScope(unrestricted = true)} overriding a derived one, then an
     * explicit restrictive {@code @AgentScope} (plus the skill's topic
     * prohibitions), then the {@code ## Guardrails}-derived policy, then the
     * {@link AgentScope} annotation fallback. A {@code null} skill file, or one
     * without {@code ## Guardrails}, falls straight through to the annotation.
     * Logs nothing about the unrestricted override, so a processor may resolve
     * more than once per registration; it reports the override once through
     * {@link #warnIfUnrestrictedOverridesGuardrails}.
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
        var scopeAnnotation = agentClass == null
                ? null : agentClass.getAnnotation(AgentScope.class);
        if (skillFile != null) {
            var tierHint = tierHint(skillFile);
            if (SCOPE_TIER_OPT_OUT.contains(tierHint)) {
                return null;    // explicit skill-level opt-out — no annotation fallback
            }
            var guardrails = skillFile.listItems("Guardrails");
            if (!guardrails.isEmpty()) {
                if (!tierHint.isEmpty()) {
                    // Explicit skill scope: it wins, and a restrictive annotation's
                    // forbidden topics still apply (two explicit declarations).
                    return fromSkillGuardrails(skillFile, agentName, agentDescription,
                            isRestrictive(scopeAnnotation)
                                    ? List.of(scopeAnnotation.forbiddenTopics()) : List.of());
                }
                if (unrestrictedOverridesGuardrails(skillFile, scopeAnnotation)) {
                    return null;    // explicit unrestricted annotation beats a derived scope
                }
                if (isRestrictive(scopeAnnotation)) {
                    var annotationPolicy = fromAnnotation(scopeAnnotation, agentClass,
                            annotationPath, forbiddenTopicsFrom(guardrails));
                    if (annotationPolicy != null) {
                        return annotationPolicy;    // explicit annotation beats a derived scope
                    }
                }
                return fromSkillGuardrails(skillFile, agentName, agentDescription);
            }
        }
        if (scopeAnnotation == null) {
            return null;
        }
        return ScopePolicyBuilder.build(scopeAnnotation, agentClass, annotationPath)
                .orElse(null);
    }

    /** Whether the class declares a restrictive (not {@code unrestricted}) {@link AgentScope}. */
    private static boolean isRestrictive(AgentScope scopeAnnotation) {
        return scopeAnnotation != null && !scopeAnnotation.unrestricted();
    }

    /**
     * The policy a restrictive {@link AgentScope} declares, with the skill's
     * topic prohibitions unioned into its forbidden topics, or {@code null} when
     * the annotation is invalid — logged, so the caller fails closed to the
     * {@code ## Guardrails}-derived policy instead of leaving the agent unscoped.
     */
    private static ScopePolicy fromAnnotation(AgentScope scopeAnnotation, Class<?> agentClass,
                                              String annotationPath, List<String> skillTopics) {
        ScopeConfig declared;
        try {
            declared = ScopeConfig.fromAnnotation(scopeAnnotation);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid @AgentScope on {} ({}) — the scope its skill's ## Guardrails "
                            + "derive is enforced instead; fix the annotation",
                    agentClass.getName(), annotationPath, e);
            return null;
        }
        return ScopePolicyBuilder.build(withForbiddenTopics(declared,
                        union(declared.forbiddenTopics(), skillTopics)),
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
     * Log one WARN when the class's {@code @AgentScope(unrestricted = true)}
     * overrides the scope its skill's {@code ## Guardrails} section would derive,
     * naming the agent, the skill and the annotation's justification. A processor
     * calls this once per registration, beside {@link #resolve}, so the operator
     * sees that the skill's guardrails stay prompt-only for this agent.
     *
     * @param skillFile  the parsed skill file, or {@code null} when the class
     *                   declares none
     * @param agentClass the annotated class, consulted for {@link AgentScope}
     * @param agentName  agent name for the log line
     * @param skillRef   the skill reference the class declares; blank for an
     *                   auto-discovered skill, which the log names by its title
     * @return {@code true} when the annotation overrides the derived scope
     */
    public static boolean warnIfUnrestrictedOverridesGuardrails(SkillFileParser skillFile,
                                                                Class<?> agentClass,
                                                                String agentName,
                                                                String skillRef) {
        if (skillFile == null || agentClass == null) {
            return false;
        }
        var scopeAnnotation = agentClass.getAnnotation(AgentScope.class);
        if (!unrestrictedOverridesGuardrails(skillFile, scopeAnnotation)) {
            return false;
        }
        var skillName = skillRef != null && !skillRef.isBlank()
                ? skillRef : firstNonBlank(skillFile.title(), "(auto-discovered)");
        logger.warn("Agent '{}' declares @AgentScope(unrestricted = true) — it overrides the "
                        + "admission scope skill '{}' would derive from its ## Guardrails, which "
                        + "stay prompt-only (set scopeTier in the skill frontmatter to keep "
                        + "enforcing them). Justification: {}",
                agentName, skillName, scopeAnnotation.justification());
        return true;
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
        return fromSkillGuardrails(skillFile, agentName, agentDescription, List.of());
    }

    /**
     * {@link #fromSkillGuardrails(SkillFileParser, String, String)} with
     * {@code annotationTopics} — a restrictive {@link AgentScope}'s forbidden
     * topics — unioned after the skill's own.
     */
    private static ScopePolicy fromSkillGuardrails(SkillFileParser skillFile, String agentName,
                                                   String agentDescription,
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
        var purpose = firstNonBlank(
                skillFile.frontmatter("description").orElse(null),
                agentDescription,
                DEFAULT_SCOPE_PURPOSE);
        var config = new ScopeConfig(purpose,
                union(forbiddenTopicsFrom(guardrails), annotationTopics),
                AgentScope.Breach.POLITE_REDIRECT, null, resolveScopeTier(tierHint),
                DEFAULT_SCOPE_SIMILARITY_THRESHOLD, false, false, "");
        return ScopePolicyBuilder.build(config,
                "scope::" + agentName, "skill-guardrails:" + agentName);
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
     * {@link AgentScope.Tier#EMBEDDING_SIMILARITY}; the opt-out values
     * ({@code none} / {@code off} / {@code disabled}) are handled by the caller
     * before this resolves.
     */
    private static AgentScope.Tier resolveScopeTier(String hint) {
        return switch (hint) {
            case "" -> AgentScope.Tier.EMBEDDING_SIMILARITY;
            case "rule_based", "rule-based", "rulebased" -> AgentScope.Tier.RULE_BASED;
            case "embedding", "embedding_similarity", "embedding-similarity" ->
                    AgentScope.Tier.EMBEDDING_SIMILARITY;
            case "semantic", "semantic_intent", "semantic-intent" ->
                    AgentScope.Tier.SEMANTIC_INTENT;
            case "llm", "llm_classifier", "llm-classifier" -> AgentScope.Tier.LLM_CLASSIFIER;
            default -> {
                logger.warn("Unknown scopeTier '{}' in skill frontmatter — defaulting to "
                        + "EMBEDDING_SIMILARITY", hint);
                yield AgentScope.Tier.EMBEDDING_SIMILARITY;
            }
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
