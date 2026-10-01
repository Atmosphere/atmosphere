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
package org.atmosphere.ai.guardrails;

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.decision.Answer;
import org.atmosphere.ai.decision.DecisionModel;
import org.atmosphere.ai.decision.DecisionModelResolver;
import org.atmosphere.ai.decision.DecisionRequest;
import org.atmosphere.ai.decision.NoulGate;
import org.atmosphere.ai.decision.Question;
import org.atmosphere.ai.decision.RuntimeDecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;

/**
 * LLM moderation detector. Asks a {@link DecisionModel} one boolean question
 * per {@link ModerationCategory} — "does this text contain hate?", "…
 * violence?" — in a single {@link DecisionRequest}, and maps each typed answer
 * through a {@link NoulGate}, the same mapping and default thresholds as the
 * {@code LlmClassifierInjectionClassifier} / {@link
 * org.atmosphere.ai.governance.scope.LlmClassifierScopeGuardrail} tiers.
 *
 * <p>The model is a {@link RuntimeDecisionModel} over the given
 * {@link AgentRuntime}, or whatever {@link DecisionModelResolver} resolves, so
 * every runtime adapter participates with no provider-specific moderation API.
 * On the Built-in runtime's chat-completions path, where the endpoint passes its
 * logprobs gate, each answer carries the model's measured distribution over
 * {@code true}/{@code false} and the category score is that measured
 * probability; elsewhere the model's self-reported confidence is used, and a
 * flagged category with no confidence carries no score.</p>
 *
 * <h2>Cost</h2>
 * One inspection is one isolated decision call per asked
 * {@link ModerationCategory} — all six from {@link #detect(String)}, only the
 * guardrail's blocked categories from {@link ModerationGuardrail} — run in
 * parallel under one deadline, bounded by the decision model's concurrency
 * limit; a call that gets no slot before the deadline is a {@code CAPACITY}
 * failure, and fails closed. The resolved fallback model is one
 * {@link RuntimeDecisionModel} shared with the LLM injection and scope tiers, with
 * {@link RuntimeDecisionModel#DEFAULT_MAX_CONCURRENCY} questions in flight unless
 * {@link DecisionModelResolver#MAX_CONCURRENCY_PROPERTY} raises it: once
 * concurrent turns fill it past the deadline, benign turns are blocked. Size it
 * with that property, register a {@link DecisionModel}, or give this detector its
 * own {@link RuntimeDecisionModel}. Because {@link ModerationGuardrail}
 * can run a detector on every streamed response chunk, wire an LLM detector
 * with {@link ModerationGuardrail.Scope#REQUEST} (one inspection per turn on the
 * user input) unless response-side model moderation is specifically required.
 *
 * <h2>Failure handling — fail-closed by default</h2>
 * A category is cleared only when the model answered {@code false} with a belief
 * below the clear threshold. Every other outcome for a category — the band
 * between the thresholds, a {@code false} answer with no confidence, a timeout,
 * no capacity, a runtime error, an empty, unparseable or out-of-set reply —
 * leaves it uncertain. A result with an uncertain category is
 * {@link ModerationResult#errored() errored}, names the uncertain categories in
 * {@link ModerationResult#undecided()} and still lists the flagged ones. Text
 * longer than {@link DecisionRequest#MAX_STATE_CHARS} characters, the absence of
 * any decision model (only the demo runtime installed) and a model that throws
 * are errors of the whole detector. By default {@link ModerationGuardrail}
 * blocks on an error of the whole detector and on an uncertain category it
 * blocks; {@link ModerationGuardrail#failOpen()} is the explicit, non-default opt-out,
 * and even then a flagged blocked category still blocks.
 */
public final class LlmModerationDetector implements ModerationDetector {

    private static final Logger logger = LoggerFactory.getLogger(LlmModerationDetector.class);

    /** Default per-inspection timeout; tuned for a small-model classifier. */
    public static final Duration DEFAULT_TIMEOUT = DecisionRequest.DEFAULT_TIMEOUT;

    /** One question per category, keyed by question id, in category order. */
    static final SequencedMap<String, Question> QUESTIONS;

    /** Question id → category. */
    private static final Map<String, ModerationCategory> CATEGORIES;

    static {
        var questions = new LinkedHashMap<String, Question>();
        var categories = new LinkedHashMap<String, ModerationCategory>();
        for (var category : ModerationCategory.values()) {
            var id = category.name().toLowerCase(Locale.ROOT);
            questions.put(id, question(category));
            categories.put(id, category);
        }
        QUESTIONS = Collections.unmodifiableSequencedMap(questions);
        CATEGORIES = Map.copyOf(categories);
    }

    private final DecisionModel model;
    private final Duration timeout;
    private final NoulGate gate;

    /** Resolves the decision model through {@link DecisionModelResolver} on each call. */
    public LlmModerationDetector() {
        this((AgentRuntime) null, DEFAULT_TIMEOUT);
    }

    /** Over {@code runtime} ({@code null} resolves through {@link DecisionModelResolver}). */
    public LlmModerationDetector(AgentRuntime runtime) {
        this(runtime, DEFAULT_TIMEOUT);
    }

    /** Over {@code runtime} ({@code null} resolves through {@link DecisionModelResolver}). */
    public LlmModerationDetector(AgentRuntime runtime, Duration timeout) {
        this(runtime != null ? new RuntimeDecisionModel(runtime) : null, timeout, NoulGate.DEFAULTS);
    }

    /**
     * @param model   the decision model; {@code null} resolves through
     *                {@link DecisionModelResolver} on each call
     * @param timeout bound per inspection, covering every category
     *                ({@code null} means {@link #DEFAULT_TIMEOUT})
     * @param gate    the thresholds on {@code P(category)} ({@code null} means
     *                {@link NoulGate#DEFAULTS})
     */
    public LlmModerationDetector(DecisionModel model, Duration timeout, NoulGate gate) {
        this.model = model;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        this.gate = gate == null ? NoulGate.DEFAULTS : gate;
    }

    @Override
    public ModerationResult detect(String text) {
        return detect(text, null);
    }

    /** Asks only {@code categories} ({@code null} or empty asks every category). */
    @Override
    public ModerationResult detect(String text, Set<ModerationCategory> categories) {
        if (text == null || text.isBlank()) {
            return ModerationResult.clean();
        }
        var effective = model != null ? model : DecisionModelResolver.resolve().orElse(null);
        if (effective == null) {
            logger.warn("No DecisionModel can answer (only the demo runtime is available); "
                    + "LlmModerationDetector cannot clear the text. Install a runtime module with a "
                    + "reachable model or fall back to RuleBasedModerationDetector.");
            return ModerationResult.error("LLM moderation: no decision model available");
        }
        if (text.length() > DecisionRequest.MAX_STATE_CHARS) {
            return ModerationResult.error("LLM moderation: text of " + text.length()
                    + " chars exceeds the " + DecisionRequest.MAX_STATE_CHARS + "-char decision state bound");
        }
        var asked = questions(categories);
        Map<String, Answer> answers;
        try {
            answers = effective.decide(new DecisionRequest(text, asked, timeout)).answers();
        } catch (RuntimeException e) {
            logger.error("LLM moderation call failed ({}): {}", effective.name(), e.toString());
            return ModerationResult.error("LLM moderation error: " + e.getMessage());
        }
        var flagged = EnumSet.noneOf(ModerationCategory.class);
        var scores = new EnumMap<ModerationCategory, Double>(ModerationCategory.class);
        var uncertain = new EnumMap<ModerationCategory, String>(ModerationCategory.class);
        var reasons = new StringBuilder();
        for (var id : asked.keySet()) {
            var category = CATEGORIES.get(id);
            var verdict = gate.judge(answers.get(id), category.label());
            switch (verdict.outcome()) {
                case FLAGGED -> {
                    flagged.add(category);
                    if (!Double.isNaN(verdict.confidence())) {
                        scores.put(category, verdict.confidence());
                    }
                    reasons.append(reasons.isEmpty() ? "" : "; ").append(verdict.reason());
                }
                case UNCERTAIN -> uncertain.put(category, verdict.reason());
                case CLEAR -> {
                    // Affirmatively cleared: nothing to report.
                }
            }
        }
        if (!uncertain.isEmpty()) {
            var detail = new StringBuilder("LLM moderation could not clear ").append(uncertain.keySet())
                    .append(": ").append(uncertain.values());
            if (!flagged.isEmpty()) {
                detail.append("; flagged ").append(flagged).append(": ").append(reasons);
            }
            logger.debug("{}", detail);
            return ModerationResult.undecided(flagged, scores, uncertain.keySet(), detail.toString());
        }
        if (flagged.isEmpty()) {
            return ModerationResult.clean();
        }
        return ModerationResult.flagged(flagged, scores,
                "LLM classifier flagged " + flagged + ": " + reasons);
    }

    /** The questions for {@code categories}, in category order; every category when none is given. */
    private static SequencedMap<String, Question> questions(Set<ModerationCategory> categories) {
        if (categories == null || categories.isEmpty()
                || categories.size() == ModerationCategory.values().length) {
            return QUESTIONS;
        }
        var asked = new LinkedHashMap<String, Question>();
        QUESTIONS.forEach((id, question) -> {
            if (categories.contains(CATEGORIES.get(id))) {
                asked.put(id, question);
            }
        });
        return asked;
    }

    private static Question.Noul question(ModerationCategory category) {
        var definition = switch (category) {
            case HATE -> "hateful content targeting a protected class";
            case HARASSMENT -> "harassing, bullying, or threatening language aimed at an individual";
            case SELF_HARM -> "content that encourages or describes self-harm or suicide";
            case SEXUAL -> "sexually explicit content";
            case VIOLENCE -> "content that depicts, threatens, or instructs real-world violence";
            case ILLICIT -> "instructions or solicitation for illegal or illicit activity";
        };
        return new Question.Noul(
                "You are a content-moderation classifier. Decide whether the TEXT (the STATE) falls "
                        + "into the moderation category '" + category.label() + "': " + definition + ".",
                "the text contains " + definition,
                "the text contains nothing in the '" + category.label() + "' category");
    }
}
