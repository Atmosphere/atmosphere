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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Zero-shot LLM moderation detector. Sends a single classification prompt to the
 * installed {@link AgentRuntime} asking which {@link ModerationCategory
 * categories} (if any) the text falls into, mirroring the
 * {@code LlmClassifierInjectionClassifier} / {@code LlmClassifierScopeGuardrail}
 * idiom so every runtime adapter (Built-in, Spring AI, LangChain4j, ADK, Embabel,
 * Koog, Semantic Kernel, …) participates identically with no provider-specific
 * moderation API.
 *
 * <p>This is the accurate, context-aware tier — one model round-trip per
 * inspection (~100–500&nbsp;ms). Because {@link ModerationGuardrail} can run a
 * detector on every streamed response chunk, prefer wiring an LLM detector with
 * {@link ModerationGuardrail.Scope#REQUEST} (one call per turn on the user input)
 * unless response-side model moderation is specifically required.</p>
 *
 * <h2>Failure handling</h2>
 * Timeout / runtime error → {@link ModerationResult#error(String)}. The guardrail
 * maps that to its fail policy — fail-closed (block) by default, per the
 * Security correctness invariant. A blank or unparseable reply is treated as
 * {@link ModerationResult#clean()} (the model declined to flag anything), which
 * is a successful — not errored — outcome.
 */
public final class LlmModerationDetector implements ModerationDetector {

    private static final Logger logger = LoggerFactory.getLogger(LlmModerationDetector.class);

    /** Default per-call timeout; tuned for a small-model classifier. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final AgentRuntime runtime;
    private final Duration timeout;

    /** Default constructor — resolves the highest-priority available {@link AgentRuntime}. */
    public LlmModerationDetector() {
        this(null, DEFAULT_TIMEOUT);
    }

    /** Explicit-runtime constructor — for tests and bare-JVM wiring. */
    public LlmModerationDetector(AgentRuntime runtime) {
        this(runtime, DEFAULT_TIMEOUT);
    }

    public LlmModerationDetector(AgentRuntime runtime, Duration timeout) {
        this.runtime = runtime;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    @Override
    public ModerationResult detect(String text) {
        if (text == null || text.isBlank()) {
            return ModerationResult.clean();
        }
        var effectiveRuntime = runtime != null ? runtime : AgentRuntimeResolver.resolve();
        if (effectiveRuntime == null) {
            logger.warn("No AgentRuntime available — LlmModerationDetector cannot classify. "
                    + "Install a runtime module or fall back to RuleBasedModerationDetector.");
            return ModerationResult.error("no AgentRuntime available");
        }

        String response;
        try {
            response = effectiveRuntime.generate(buildClassificationContext(text), timeout);
        } catch (RuntimeException e) {
            logger.error("LLM moderation call failed ({}): {}",
                    effectiveRuntime.name(), e.getMessage());
            return ModerationResult.error("moderation runtime error: " + e.getMessage());
        }

        if (response == null || response.isBlank()) {
            logger.debug("LLM moderation returned empty response — treating as clean");
            return ModerationResult.clean();
        }
        return parse(response);
    }

    /** Parse a model reply of the form {@code "violence, illicit"} or {@code "NONE"}. */
    static ModerationResult parse(String response) {
        var trimmed = response.trim();
        var lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("none") || lower.equals("safe") || lower.startsWith("no,")
                || lower.equals("no")) {
            return ModerationResult.clean();
        }
        var scores = new EnumMap<ModerationCategory, Double>(ModerationCategory.class);
        for (var token : trimmed.split("[,;\\n]")) {
            ModerationCategory.fromToken(token)
                    .ifPresent(category -> scores.put(category, 0.9));
        }
        if (scores.isEmpty()) {
            // The model said something we couldn't map to a category. Biasing
            // toward clean here (rather than error) matches the injection
            // classifier's "ambiguous → trust the cheaper tier" posture; the
            // rule-based tier and the request-path checks back this up.
            logger.debug("LLM moderation reply not mappable to a category: {}",
                    truncate(trimmed));
            return ModerationResult.clean();
        }
        return ModerationResult.flagged(scores.keySet(), scores,
                "LLM classifier flagged: " + scores.keySet());
    }

    private static AgentExecutionContext buildClassificationContext(String text) {
        var categories = java.util.Arrays.stream(ModerationCategory.values())
                .map(ModerationCategory::label).toList();
        var systemPrompt = "You are a content-moderation classifier. Classify the TEXT against "
                + "these categories: " + categories + ".\n\n"
                + "Respond with a comma-separated list of every category that applies, using the "
                + "exact category labels above. If none apply, respond with exactly: NONE.\n"
                + "Do not explain, do not apologize, do not add any other words.";
        var message = "TEXT:\n" + text;
        return new AgentExecutionContext(
                message, systemPrompt, null,
                null, "moderation-classifier", null, "moderation-classifier",
                List.of(), null, null,
                List.of(), Map.of(), List.of(),
                String.class, null);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        var t = s.length() > 120 ? s.substring(0, 120) + "…" : s;
        return t.replace("\n", " ").replace("\r", " ");
    }
}
