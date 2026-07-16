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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves the admission {@link ScopePolicy} an agent-bearing class mandates,
 * with explicit precedence shared by every processor that consumes skill files
 * ({@code @Agent}, {@code @Coordinator}): a skill-level {@code scopeTier: none}
 * opt-out beats everything (the skill is the agent's own artifact); a
 * {@code ## Guardrails}-derived policy wins next ({@link #fromSkillGuardrails});
 * otherwise an {@link AgentScope} annotation on the class is honored — including
 * the {@code unrestricted = true, justification = "..."} opt-out, which makes an
 * intentionally open agent an <em>explicit, lintable declaration</em> instead of
 * a silent gap (Correctness Invariant #5 — a declared posture must match runtime
 * behavior, so the annotation must be enforced, not just present).
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
     * Resolve the class's scope policy: skill-level {@code scopeTier: none}
     * opt-out first, then the {@code ## Guardrails}-derived policy, then the
     * {@link AgentScope} annotation fallback. A {@code null} or absent skill
     * file falls straight through to the annotation.
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
        if (skillFile != null) {
            var tierHint = tierHint(skillFile);
            if (SCOPE_TIER_OPT_OUT.contains(tierHint)) {
                return null;    // explicit skill-level opt-out — no annotation fallback
            }
            var skillPolicy = fromSkillGuardrails(skillFile, agentName, agentDescription);
            if (skillPolicy != null) {
                return skillPolicy;
            }
        }
        var scopeAnnotation = agentClass == null
                ? null : agentClass.getAnnotation(AgentScope.class);
        if (scopeAnnotation == null) {
            return null;
        }
        return ScopePolicyBuilder.build(scopeAnnotation, agentClass, annotationPath)
                .orElse(null);
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
        var config = new ScopeConfig(purpose, forbiddenTopicsFrom(guardrails),
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
