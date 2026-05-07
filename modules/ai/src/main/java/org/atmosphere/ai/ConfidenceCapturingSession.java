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
package org.atmosphere.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Framework-level decorator that implements the
 * {@link AiCapability#CONFIDENCE_SCORES} capability via the
 * model-reported-field path. Mirrors the regex-extraction approach
 * already used by {@code ConfidenceThresholdGuardrail} so the two
 * primitives share a single parsing contract.
 *
 * <p>Behaviour:</p>
 * <ol>
 *   <li>The pipeline appends the elicitation cue to the system prompt
 *       before dispatching to the runtime — this is done in
 *       {@link AiPipeline}, not here.</li>
 *   <li>Every {@link #send(String)} call appends to an in-memory accumulator.</li>
 *   <li>On {@link #complete()} / {@link #complete(String)} the accumulator
 *       is scanned for {@code "<fieldName>": <number>}. When found and
 *       parseable in {@code [0, 1]}, an {@link AiConfidence} with
 *       {@link AiConfidence.Source#MODEL_REPORTED_FIELD} fires through
 *       {@link StreamingSession#confidence(AiConfidence)} BEFORE the
 *       delegate's {@code complete()} is invoked, so observers see the
 *       confidence event ahead of the terminal frame.</li>
 *   <li>When no field is present or the value cannot be parsed,
 *       {@link AiConfidence#unknown(AiConfidence.Source) unknown(MODEL_REPORTED_FIELD)}
 *       fires instead — lets consumers distinguish "model did not comply"
 *       from "elicitation was never installed."</li>
 * </ol>
 *
 * <p>Runtimes that natively expose logprobs can call
 * {@link StreamingSession#confidence(AiConfidence)} themselves with
 * {@link AiConfidence.Source#LOGPROBS_NATIVE}; when they do, this
 * decorator detects the prior call (via the {@link #explicit} flag set
 * on the override) and skips its own emission so callers never see two
 * confidence events for one response.</p>
 */
class ConfidenceCapturingSession extends DelegatingStreamingSession {

    private static final Logger logger = LoggerFactory.getLogger(ConfidenceCapturingSession.class);

    private final Pattern fieldPattern;
    private final StringBuilder accumulator = new StringBuilder();
    private volatile boolean explicit;
    private volatile boolean fired;

    ConfidenceCapturingSession(StreamingSession delegate, AiConfidenceElicitation elicitation) {
        super(delegate);
        Objects.requireNonNull(elicitation, "elicitation");
        // Same regex shape as ConfidenceThresholdGuardrail — tolerates
        // whitespace, integers, and negative values (we reject negatives
        // post-parse). The elicitation reference is captured only here
        // because the cue is appended to the system prompt by AiPipeline,
        // not by this decorator.
        this.fieldPattern = Pattern.compile(
                "\"" + Pattern.quote(elicitation.fieldName())
                        + "\"\\s*:\\s*(-?[0-9]*\\.?[0-9]+)");
    }

    @Override
    public void send(String text) {
        if (text != null) {
            accumulator.append(text);
        }
        delegate.send(text);
    }

    @Override
    public void confidence(AiConfidence confidence) {
        // Runtime emitted directly (typically LOGPROBS_NATIVE) — record so
        // we don't double-fire on complete() with our parsed value.
        explicit = true;
        delegate.confidence(confidence);
    }

    @Override
    public void complete() {
        emitParsedConfidence();
        delegate.complete();
    }

    @Override
    public void complete(String summary) {
        if (summary != null) {
            // The summary form may carry the final response text without
            // streaming through send(); fold it into the accumulator so
            // the parser sees it.
            accumulator.append(summary);
        }
        emitParsedConfidence();
        delegate.complete(summary);
    }

    private void emitParsedConfidence() {
        if (explicit || fired) {
            return;
        }
        fired = true;
        var text = accumulator.toString();
        if (text.isEmpty()) {
            delegate.confidence(AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD));
            return;
        }
        var matcher = fieldPattern.matcher(text);
        if (!matcher.find()) {
            delegate.confidence(AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD));
            return;
        }
        var raw = matcher.group(1);
        try {
            var value = Double.parseDouble(raw);
            if (value < 0.0 || value > 1.0) {
                logger.debug("ConfidenceCapturingSession out-of-range value '{}' — emitting unknown", raw);
                delegate.confidence(AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD));
                return;
            }
            delegate.confidence(AiConfidence.reported(value));
        } catch (NumberFormatException e) {
            logger.debug("ConfidenceCapturingSession could not parse '{}'", raw);
            delegate.confidence(AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD));
        }
    }
}
