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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.annotation.AgentScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Opt-in scope classifier for high-stakes scopes where false-negatives cost
 * more than latency (medical / financial / legal-adjacent endpoints). Sends
 * a short yes/no classification prompt to the resolved {@link AgentRuntime}
 * and classifies based on the first token of the response.
 *
 * <p>~100–500 ms latency typical (one LLM round-trip per request). Most
 * accurate of the three tiers; the correct default only when latency is
 * explicitly acceptable — per v4 §4, the annotation default stays on
 * {@link AgentScope.Tier#EMBEDDING_SIMILARITY} and operators opt in here
 * via {@code @AgentScope(tier = LLM_CLASSIFIER)}.</p>
 *
 * <h2>Prompt shape</h2>
 * The classifier uses a zero-shot yes/no prompt — no few-shot examples,
 * no chain-of-thought. Any serious refusal-tuned model can answer this
 * reliably; adding examples would bias the classifier and make
 * threshold-free operation fragile.
 *
 * <h2>Failure handling</h2>
 * Timeout / runtime error → {@link ScopeGuardrail.Decision#error} (fail-closed
 * at the {@link ScopePolicy} layer). Unparseable response (neither "yes"
 * nor "no" as the first word) → IN_SCOPE with a DEBUG log — the default
 * posture is to trust the embedding-similarity tier below us rather than
 * over-reject on an LLM quirk. Operators who need stricter behaviour layer
 * a {@code DENY} breach on top of the rule-based tier.
 */
public final class LlmClassifierScopeGuardrail implements ScopeGuardrail {

    private static final Logger logger = LoggerFactory.getLogger(LlmClassifierScopeGuardrail.class);

    /** Default per-call timeout; tuned for a small-model classifier. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final AgentRuntime runtime;
    private final Duration timeout;

    /** Default constructor — resolves the highest-priority available {@link AgentRuntime}. */
    public LlmClassifierScopeGuardrail() {
        this(null, DEFAULT_TIMEOUT);
    }

    /** Explicit-runtime constructor — for tests and bare-JVM wiring. */
    public LlmClassifierScopeGuardrail(AgentRuntime runtime) {
        this(runtime, DEFAULT_TIMEOUT);
    }

    public LlmClassifierScopeGuardrail(AgentRuntime runtime, Duration timeout) {
        this.runtime = runtime;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
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

        var effectiveRuntime = runtime != null ? runtime : AgentRuntimeResolver.resolve();
        if (effectiveRuntime == null) {
            logger.warn("No AgentRuntime available — LlmClassifierScopeGuardrail admits all requests. "
                    + "Install a runtime module or switch the tier to RULE_BASED / EMBEDDING_SIMILARITY.");
            return Decision.inScope(Double.NaN);
        }

        var classification = buildClassificationContext(config, request.message());
        String response;
        try {
            response = effectiveRuntime.generate(classification, timeout);
        } catch (RuntimeException e) {
            logger.error("LLM classifier call failed ({}): {}", effectiveRuntime.name(), e.getMessage());
            return Decision.error("classifier runtime error: " + e.getMessage());
        }

        if (response == null || response.isBlank()) {
            logger.warn("LLM classifier returned empty response — admitting by default");
            return Decision.inScope(Double.NaN);
        }

        var verdict = parseFirstWord(response);
        return switch (verdict) {
            case YES -> Decision.inScope(Double.NaN);
            case NO -> Decision.outOfScope(
                    "LLM classifier rejected as off-topic (response: "
                            + truncate(response.trim()) + ")",
                    Double.NaN);
            case AMBIGUOUS -> {
                logger.debug("LLM classifier returned ambiguous response: {} — admitting",
                        truncate(response));
                yield Decision.inScope(Double.NaN);
            }
        };
    }

    private static AgentExecutionContext buildClassificationContext(ScopeConfig config,
                                                                     String userMessage) {
        var systemPrompt = buildSystemPrompt(config);
        var message = "REQUEST:\n" + userMessage;
        return new AgentExecutionContext(
                message, systemPrompt, null,
                null, "scope-classifier", null, "scope-classifier",
                List.of(), null, null,
                List.of(), Map.of(), List.of(),
                String.class, null);
    }

    private static String buildSystemPrompt(ScopeConfig config) {
        var sb = new StringBuilder();
        sb.append("You are a binary scope classifier. Respond with EXACTLY one word: "
                + "YES or NO.\n\n");
        sb.append("The endpoint's declared purpose is:\n  ").append(config.purpose()).append("\n\n");
        if (!config.forbiddenTopics().isEmpty()) {
            sb.append("Topics that MUST be classified as NO even when the purpose could admit them:\n");
            for (var topic : config.forbiddenTopics()) {
                sb.append("  - ").append(topic).append('\n');
            }
            sb.append('\n');
        }
        sb.append("Answer YES if the user's REQUEST falls within the declared purpose and "
                + "does not touch any forbidden topic.\n");
        sb.append("Answer NO if the request is off-topic or touches any forbidden topic.\n");
        sb.append("Respond with a single word: YES or NO.\n");
        sb.append("Do not explain, do not apologize, do not elaborate.");
        return sb.toString();
    }

    /** Classify the first word of the response. */
    static Verdict parseFirstWord(String response) {
        if (response == null) return Verdict.AMBIGUOUS;
        var trimmed = response.trim();
        if (trimmed.isEmpty()) return Verdict.AMBIGUOUS;
        // Strip markdown emphasis / punctuation surrounding the first word.
        // e.g. "**YES**", "YES.", "Yes!", "*no*"
        var cleaned = trimmed.toLowerCase(Locale.ROOT).replaceAll("^[^a-z]+", "");
        if (cleaned.startsWith("yes")) return Verdict.YES;
        if (cleaned.startsWith("no") && !cleaned.startsWith("not")) {
            return Verdict.NO;
        }
        return Verdict.AMBIGUOUS;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        var t = s.length() > 120 ? s.substring(0, 120) + "…" : s;
        return t.replace("\n", " ").replace("\r", " ");
    }

    enum Verdict { YES, NO, AMBIGUOUS }
}
