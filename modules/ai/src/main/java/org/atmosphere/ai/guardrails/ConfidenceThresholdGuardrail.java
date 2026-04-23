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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * Response-side guardrail that blocks the turn when the LLM's
 * self-reported confidence falls below a threshold. Expects the response
 * to carry a numeric {@code confidence} field somewhere in its JSON —
 * structured-output consumers typically already emit one.
 *
 * <h2>How it reads the field</h2>
 * Scans the accumulated response text for a regex-shaped
 * {@code "confidence": <number>} token. Configurable via
 * {@link #withFieldName(String)}. When no match is found the guardrail
 * passes by default (operators who want strict enforcement pair this
 * with structured-output mode that guarantees the field).
 *
 * <h2>Why "block" not "escalate"</h2>
 * Blocking is the composable primitive — the admission seam raises a
 * {@link SecurityException} with the reason, and the
 * {@code PolicyAdmissionGate} / {@code @RequiresApproval} plumbing handles
 * the human-review workflow. This guardrail should not know about the
 * approval-routing layer (that's a protocol concern).
 *
 * <p>Tier 6.5 — pair with {@code @RequiresApproval} on the @Prompt method
 * to auto-escalate low-confidence turns to a human.</p>
 */
public final class ConfidenceThresholdGuardrail implements AiGuardrail {

    private static final Logger logger = LoggerFactory.getLogger(
            ConfidenceThresholdGuardrail.class);

    /** Default threshold — pair with Tier 6.5 per the v5 roadmap: 0.7. */
    public static final double DEFAULT_THRESHOLD = 0.7;

    private final double threshold;
    private final Pattern fieldPattern;

    public ConfidenceThresholdGuardrail() {
        this(DEFAULT_THRESHOLD, "confidence");
    }

    public ConfidenceThresholdGuardrail(double threshold) {
        this(threshold, "confidence");
    }

    public ConfidenceThresholdGuardrail(double threshold, String fieldName) {
        if (threshold <= 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException(
                    "threshold must be in (0, 1], got: " + threshold);
        }
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName must not be blank");
        }
        this.threshold = threshold;
        // Matches "<field>": 0.83 or "<field>":1 — tolerates whitespace
        // and integer-valued scores.
        this.fieldPattern = Pattern.compile(
                "\"" + Pattern.quote(fieldName)
                        + "\"\\s*:\\s*(-?[0-9]*\\.?[0-9]+)");
    }

    public ConfidenceThresholdGuardrail withFieldName(String fieldName) {
        return new ConfidenceThresholdGuardrail(threshold, fieldName);
    }

    @Override
    public GuardrailResult inspectResponse(String accumulatedResponse) {
        if (accumulatedResponse == null || accumulatedResponse.isEmpty()) {
            return GuardrailResult.pass();
        }
        var matcher = fieldPattern.matcher(accumulatedResponse);
        if (!matcher.find()) {
            // No confidence field present — pass. Operators who need
            // strict enforcement use structured-output mode with a
            // confidence-required schema.
            return GuardrailResult.pass();
        }
        try {
            var value = Double.parseDouble(matcher.group(1));
            if (value < threshold) {
                logger.info("ConfidenceThresholdGuardrail blocking response — "
                        + "confidence {} below threshold {}", value, threshold);
                return GuardrailResult.block(
                        "response confidence " + value + " below threshold " + threshold
                                + " — escalate for human review");
            }
            return GuardrailResult.pass();
        } catch (NumberFormatException e) {
            logger.debug("ConfidenceThresholdGuardrail could not parse confidence value '{}'",
                    matcher.group(1));
            return GuardrailResult.pass();
        }
    }
}
