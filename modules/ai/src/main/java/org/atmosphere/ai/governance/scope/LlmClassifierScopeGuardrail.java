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
package org.atmosphere.ai.governance.scope;

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.annotation.AgentScope;
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

/**
 * Opt-in scope classifier for high-stakes scopes where false-negatives cost
 * more than latency (medical / financial / legal-adjacent endpoints). Asks a
 * {@link DecisionModel} one boolean question per request — "is this request
 * off-topic for the declared purpose, or does it touch a forbidden topic?" —
 * and maps the typed answer through a {@link NoulGate}.
 *
 * <p>~100–500 ms latency typical (one LLM round-trip per request). Most
 * accurate of the three tiers; the correct default only when latency is
 * explicitly acceptable — per v4 §4, the annotation default stays on
 * {@link AgentScope.Tier#EMBEDDING_SIMILARITY} and operators opt in here
 * via {@code @AgentScope(tier = LLM_CLASSIFIER)}.</p>
 *
 * <p>The model is a {@link RuntimeDecisionModel} over the given
 * {@link AgentRuntime}, or whatever {@link DecisionModelResolver} resolves. On
 * the Built-in runtime's chat-completions path, where the endpoint passes its
 * logprobs gate, the answer carries the model's measured distribution over
 * {@code true}/{@code false}. A registered decision model that returns a
 * distribution ({@code atmosphere-ai-decision-typesafe}) gives the provider's
 * {@code P(true)}, with source
 * {@link org.atmosphere.ai.AiConfidence.Source#PROVIDER_DISTRIBUTION}. Elsewhere
 * the answer carries the model's self-reported confidence. The same thresholds
 * apply in every case (see {@link NoulGate}).</p>
 *
 * <h2>Mapping</h2>
 * <ul>
 *   <li>{@code P(off-topic) >= outOfScopeAt} (default 0.5) →
 *       {@link ScopeGuardrail.Outcome#OUT_OF_SCOPE}.</li>
 *   <li>The model answered {@code false} and {@code P(off-topic) < inScopeBelow}
 *       (default 0.2) → {@link ScopeGuardrail.Outcome#IN_SCOPE}.</li>
 *   <li>Everything else is <em>uncertain</em>: the band between the thresholds,
 *       a {@code false} answer with no confidence, a {@code true} answer the
 *       belief disagrees with, a timeout, no capacity, a runtime error, an empty,
 *       unparseable or out-of-set reply, a request longer than
 *       {@link DecisionRequest#MAX_STATE_CHARS} characters, and no decision model
 *       at all (only the demo runtime installed).</li>
 * </ul>
 *
 * <h2>Failure handling — fail-closed by default</h2>
 * An uncertain verdict is {@link ScopeGuardrail.Decision#error}, which
 * {@link ScopePolicy} denies at pre-admission (Correctness Invariant #6). The
 * explicit, non-default opt-out is {@code failOpen}: an instance built by a
 * constructor that does not take it (the ServiceLoader-registered instance is
 * built that way) reads the {@value #FAIL_OPEN_PROPERTY} JVM system property on
 * each uncertain verdict, so setting or clearing the property takes effect on
 * the next request even though {@link ScopeGuardrailResolver} caches the
 * instance. It is a system property only ({@code -D} or
 * {@link System#setProperty}); no Spring Boot or Quarkus configuration key binds
 * to it. In fail-open mode an uncertain verdict admits the request with a WARN
 * log.
 */
public final class LlmClassifierScopeGuardrail implements ScopeGuardrail {

    private static final Logger logger = LoggerFactory.getLogger(LlmClassifierScopeGuardrail.class);

    /** Default per-call timeout; tuned for a small-model classifier. */
    public static final Duration DEFAULT_TIMEOUT = DecisionRequest.DEFAULT_TIMEOUT;

    /**
     * JVM system property that, when {@code true}, makes an uncertain verdict
     * admit the request instead of failing closed. Read on each uncertain
     * verdict by an instance built without a {@code failOpen} argument. Default
     * {@code false}.
     */
    public static final String FAIL_OPEN_PROPERTY = "org.atmosphere.ai.scope.llm-classifier.fail-open";

    /** Question id used in the decision request. */
    static final String QUESTION_ID = "off_topic";

    private final DecisionModel model;
    private final Duration timeout;
    private final NoulGate gate;
    /** The explicit fail policy, or {@code null} to read {@link #FAIL_OPEN_PROPERTY} per verdict. */
    private final Boolean failOpen;

    /** Resolves the decision model through {@link DecisionModelResolver} on each call. */
    public LlmClassifierScopeGuardrail() {
        this((AgentRuntime) null, DEFAULT_TIMEOUT);
    }

    /** Over {@code runtime} ({@code null} resolves through {@link DecisionModelResolver}). */
    public LlmClassifierScopeGuardrail(AgentRuntime runtime) {
        this(runtime, DEFAULT_TIMEOUT);
    }

    /** Over {@code runtime} ({@code null} resolves through {@link DecisionModelResolver}). */
    public LlmClassifierScopeGuardrail(AgentRuntime runtime, Duration timeout) {
        this(runtime != null ? new RuntimeDecisionModel(runtime) : null, timeout, NoulGate.DEFAULTS,
                (Boolean) null);
    }

    /**
     * @param model    the decision model; {@code null} resolves through
     *                 {@link DecisionModelResolver} on each call
     * @param timeout  bound per request ({@code null} means {@link #DEFAULT_TIMEOUT})
     * @param gate     the thresholds on {@code P(off-topic)} ({@code null} means
     *                 {@link NoulGate#DEFAULTS})
     * @param failOpen {@code true} admits a request whose verdict is uncertain;
     *                 {@code false} (the default everywhere else) fails closed
     */
    public LlmClassifierScopeGuardrail(DecisionModel model, Duration timeout, NoulGate gate, boolean failOpen) {
        this(model, timeout, gate, Boolean.valueOf(failOpen));
        if (failOpen) {
            logger.warn("LlmClassifierScopeGuardrail is fail-open: an uncertain, failed or timed-out "
                    + "scope verdict admits the request");
        }
    }

    private LlmClassifierScopeGuardrail(DecisionModel model, Duration timeout, NoulGate gate, Boolean failOpen) {
        this.model = model;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        this.gate = gate == null ? NoulGate.DEFAULTS : gate;
        this.failOpen = failOpen;
    }

    @Override
    public AgentScope.Tier tier() {
        return AgentScope.Tier.LLM_CLASSIFIER;
    }

    @Override
    public Decision evaluate(AiRequest request, ScopeConfig config) {
        if (config.unrestricted()) {
            return Decision.inScope(Double.NaN);
        }
        if (request == null || request.message() == null || request.message().isBlank()) {
            return Decision.inScope(Double.NaN);
        }
        var effective = model != null ? model : DecisionModelResolver.resolve().orElse(null);
        if (effective == null) {
            logger.warn("No DecisionModel can answer (only the demo runtime is available); "
                    + "the LLM scope classifier cannot clear the request");
            return uncertain("no decision model available");
        }
        var length = request.message().length();
        if (length > DecisionRequest.MAX_STATE_CHARS) {
            return uncertain("request of " + length + " chars exceeds the "
                    + DecisionRequest.MAX_STATE_CHARS + "-char decision state bound");
        }
        Answer answer;
        try {
            var result = effective.decide(DecisionRequest.of(request.message(), QUESTION_ID, question(config))
                    .withTimeout(timeout));
            answer = result.answers().get(QUESTION_ID);
        } catch (RuntimeException e) {
            logger.error("LLM scope classifier call failed ({}): {}", effective.name(), e.toString());
            return uncertain("error: " + e.getMessage());
        }
        var verdict = gate.judge(answer, "off-topic");
        return switch (verdict.outcome()) {
            case FLAGGED -> Decision.outOfScope(
                    "LLM classifier rejected as off-topic: " + verdict.reason(), Double.NaN);
            case CLEAR -> Decision.inScope(Double.NaN);
            case UNCERTAIN -> uncertain(verdict.reason());
        };
    }

    private Decision uncertain(String reason) {
        if (failOpen != null ? failOpen : Boolean.getBoolean(FAIL_OPEN_PROPERTY)) {
            logger.warn("LLM scope classifier uncertain ({}) — admitting (fail-open mode)", reason);
            return new Decision(Outcome.IN_SCOPE, "fail-open: LLM scope classifier " + reason, Double.NaN);
        }
        return Decision.error("LLM scope classifier " + reason);
    }

    /** The boolean question for {@code config}; {@code true} means off-topic. */
    static Question.Noul question(ScopeConfig config) {
        var sb = new StringBuilder();
        sb.append("You are a scope classifier for an AI assistant endpoint. Decide whether the "
                + "user's REQUEST (the STATE) falls outside the endpoint's declared purpose or "
                + "touches a forbidden topic.\n\n");
        sb.append("The endpoint's declared purpose is:\n  ").append(config.purpose()).append('\n');
        if (!config.forbiddenTopics().isEmpty()) {
            sb.append("\nTopics that are off-topic even when the purpose could admit them:\n");
            for (var topic : config.forbiddenTopics()) {
                sb.append("  - ").append(topic).append('\n');
            }
        }
        return new Question.Noul(sb.toString(),
                "the request is off-topic for the declared purpose, or touches any forbidden topic",
                "the request falls within the declared purpose and touches no forbidden topic");
    }
}
