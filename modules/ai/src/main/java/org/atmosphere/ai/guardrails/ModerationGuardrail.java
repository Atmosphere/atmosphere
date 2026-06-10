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

import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * Content-safety guardrail. Runs a pluggable {@link ModerationDetector} over the
 * user request (pre-LLM) and/or the model response (during streaming) and blocks
 * the turn when the content is flagged for a configured
 * {@link ModerationCategory category}.
 *
 * <p>This is the moderation tier on the existing fail-closed guardrail pipeline:
 * the same {@code AiGuardrail} SPI that ships PII, cost, confidence, and
 * output-length guardrails. The detector is the only moving part — by default
 * a zero-dependency {@link RuleBasedModerationDetector}; swap in
 * {@link LlmModerationDetector} or a provider moderation endpoint for
 * context-aware classification.</p>
 *
 * <h2>Fail-closed by default</h2>
 * When the detector cannot complete (timeout, runtime error) the guardrail
 * <strong>blocks</strong> the turn. This is mandated by the Security correctness
 * invariant ("integrity verification MUST fail closed by default"). A moderation
 * outage therefore degrades to refusing traffic, not to silently letting
 * unmoderated content through. Call {@link #failOpen()} to make the opposite
 * (non-default, explicit) choice.
 *
 * <h2>Request vs response scope</h2>
 * The default {@link Scope#BOTH} inspects the request once and the response on
 * the guardrail-check cadence. The default {@link RuleBasedModerationDetector} is
 * cheap enough for both. When wiring an {@link LlmModerationDetector}, prefer
 * {@link Scope#REQUEST} so the model is consulted once per turn rather than on
 * every streamed chunk.
 *
 * <p>Like every response-path guardrail, a response-side block is an
 * <em>early termination</em>: bytes already streamed to the client cannot be
 * recalled, but the stream is halted and the hit is surfaced to the client and
 * the audit log.</p>
 *
 * <p>Thread-safe and shareable across concurrent requests (no mutable state).</p>
 */
public final class ModerationGuardrail implements AiGuardrail {

    private static final Logger logger = LoggerFactory.getLogger(ModerationGuardrail.class);

    /** Where the guardrail applies moderation. */
    public enum Scope {
        /** Inspect only the user request (pre-LLM). */
        REQUEST,
        /** Inspect only the model response (during streaming). */
        RESPONSE,
        /** Inspect both request and response (default). */
        BOTH;

        boolean includesRequest() {
            return this == REQUEST || this == BOTH;
        }

        boolean includesResponse() {
            return this == RESPONSE || this == BOTH;
        }
    }

    private final ModerationDetector detector;
    private final Set<ModerationCategory> blockedCategories;
    private final Scope scope;
    private final boolean blockOnError;

    /**
     * Build with the zero-dependency rule-based detector, all categories
     * blocked, {@link Scope#BOTH}, fail-closed.
     */
    public ModerationGuardrail() {
        this(new RuleBasedModerationDetector(), EnumSet.allOf(ModerationCategory.class),
                Scope.BOTH, true);
    }

    /**
     * Build with an explicit detector, all categories blocked, {@link Scope#BOTH},
     * fail-closed.
     */
    public ModerationGuardrail(ModerationDetector detector) {
        this(detector, EnumSet.allOf(ModerationCategory.class), Scope.BOTH, true);
    }

    public ModerationGuardrail(ModerationDetector detector,
                               Set<ModerationCategory> blockedCategories,
                               Scope scope,
                               boolean blockOnError) {
        if (detector == null) {
            throw new IllegalArgumentException("detector must not be null");
        }
        this.detector = detector;
        this.blockedCategories = blockedCategories == null || blockedCategories.isEmpty()
                ? EnumSet.allOf(ModerationCategory.class)
                : EnumSet.copyOf(blockedCategories);
        this.scope = scope == null ? Scope.BOTH : scope;
        this.blockOnError = blockOnError;
    }

    /** Restrict the categories this guardrail blocks on. */
    public ModerationGuardrail blocking(ModerationCategory... categories) {
        return new ModerationGuardrail(detector,
                categories == null || categories.length == 0
                        ? EnumSet.allOf(ModerationCategory.class)
                        : EnumSet.copyOf(java.util.Arrays.asList(categories)),
                scope, blockOnError);
    }

    /** Restrict where the guardrail applies. */
    public ModerationGuardrail scope(Scope newScope) {
        return new ModerationGuardrail(detector, blockedCategories, newScope, blockOnError);
    }

    /**
     * Switch to fail-<em>open</em>: a detector error admits the turn instead of
     * blocking it. Explicit, non-default — use only when moderation
     * availability must not gate traffic.
     */
    public ModerationGuardrail failOpen() {
        return new ModerationGuardrail(detector, blockedCategories, scope, false);
    }

    @Override
    public GuardrailResult inspectRequest(AiRequest request) {
        if (!scope.includesRequest() || request == null || request.message() == null) {
            return GuardrailResult.pass();
        }
        return evaluate(request.message(), "request");
    }

    @Override
    public GuardrailResult inspectResponse(String accumulatedResponse) {
        if (!scope.includesResponse()) {
            return GuardrailResult.pass();
        }
        return evaluate(accumulatedResponse, "response");
    }

    private GuardrailResult evaluate(String text, String surface) {
        ModerationDetector.ModerationResult result;
        try {
            result = detector.detect(text);
        } catch (RuntimeException e) {
            // A detector that throws is an availability failure, identical in
            // posture to ModerationResult.error — apply the fail policy.
            logger.error("ModerationDetector {} threw on {} path",
                    detector.getClass().getSimpleName(), surface, e);
            result = ModerationDetector.ModerationResult.error(e.getMessage());
        }

        if (result.errored()) {
            if (blockOnError) {
                logger.warn("Moderation detector unavailable on {} path ({}) — "
                        + "blocking (fail-closed)", surface, result.detail());
                return GuardrailResult.block("moderation unavailable: " + result.detail());
            }
            logger.warn("Moderation detector unavailable on {} path ({}) — "
                    + "admitting (fail-open mode)", surface, result.detail());
            return GuardrailResult.pass();
        }

        var matched = EnumSet.noneOf(ModerationCategory.class);
        for (var category : result.flagged()) {
            if (blockedCategories.contains(category)) {
                matched.add(category);
            }
        }
        if (matched.isEmpty()) {
            return GuardrailResult.pass();
        }
        logger.warn("Moderation blocked {} for categories {} ({})",
                surface, matched, result.detail());
        return GuardrailResult.block(surface + " flagged for moderation categories: " + matched);
    }
}
