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

import java.util.Map;
import java.util.Objects;

/**
 * Configuration for the framework-level {@code ConfidenceCapturingSession}
 * decorator — the universal-fallback path for the
 * {@link AiCapability#CONFIDENCE_SCORES} capability.
 *
 * <p>When an elicitation is in scope, {@link AiPipeline} (a) appends a
 * short instruction to the system prompt asking the model to emit a
 * {@code "confidence": 0.x} field somewhere in its response, and
 * (b) installs a session decorator that parses the field on
 * {@code complete()} and fires
 * {@link StreamingSession#confidence(AiConfidence)} with
 * {@link AiConfidence.Source#MODEL_REPORTED_FIELD}.</p>
 *
 * <p>This is the "model-reported" source — works on every runtime that
 * honors {@link AiCapability#SYSTEM_PROMPT}. Quality of the signal
 * depends on the model's confidence calibration. Runtimes that natively
 * expose token-level logprobs can additionally call
 * {@link StreamingSession#confidence(AiConfidence)} with
 * {@link AiConfidence.Source#LOGPROBS_NATIVE} for a richer signal.</p>
 *
 * @param fieldName       the JSON field the model is asked to emit
 *                        (default {@code "confidence"})
 * @param systemPromptCue text appended to the system prompt;
 *                        {@code null} means use the default cue
 */
public record AiConfidenceElicitation(String fieldName, String systemPromptCue) {

    /** Metadata key for threading a per-request elicitation through the pipeline. */
    public static final String METADATA_KEY = "ai.confidence.elicitation";

    /** Default field name. */
    public static final String DEFAULT_FIELD = "confidence";

    /** Default system-prompt cue. Phrased to be self-contained and tolerant
     * of structured-output schemas — the model can fold the field into its
     * existing JSON response or emit it as a postscript. */
    public static final String DEFAULT_CUE =
            "After answering, append a JSON object with a single field "
                    + "\"" + DEFAULT_FIELD + "\" whose value is your confidence "
                    + "in the answer as a number in [0.0, 1.0]. "
                    + "Example: {\"" + DEFAULT_FIELD + "\": 0.83}.";

    public AiConfidenceElicitation {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName must not be blank");
        }
        // systemPromptCue is allowed to be null — the decorator falls back
        // to the default cue keyed off fieldName.
    }

    /** Default elicitation: asks the model to emit a {@code "confidence"} field. */
    public static AiConfidenceElicitation defaults() {
        return new AiConfidenceElicitation(DEFAULT_FIELD, DEFAULT_CUE);
    }

    /** Customise the field name; the default cue is regenerated to match. */
    public static AiConfidenceElicitation withField(String fieldName) {
        Objects.requireNonNull(fieldName, "fieldName");
        return new AiConfidenceElicitation(
                fieldName,
                "After answering, append a JSON object with a single field "
                        + "\"" + fieldName + "\" whose value is your confidence "
                        + "in the answer as a number in [0.0, 1.0]. "
                        + "Example: {\"" + fieldName + "\": 0.83}.");
    }

    /** Effective cue — {@link #systemPromptCue()} if non-null, else
     * the {@link #DEFAULT_CUE} regenerated for {@link #fieldName()}. */
    public String effectiveCue() {
        return systemPromptCue != null ? systemPromptCue : withField(fieldName).systemPromptCue();
    }

    /** Extract an elicitation from request metadata; {@code null} when absent. */
    public static AiConfidenceElicitation from(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        var v = metadata.get(METADATA_KEY);
        return v instanceof AiConfidenceElicitation e ? e : null;
    }

    /** Same as {@link #from(Map)} but reads from an
     * {@link AgentExecutionContext}. */
    public static AiConfidenceElicitation from(AgentExecutionContext context) {
        Objects.requireNonNull(context, "context");
        return from(context.metadata());
    }
}
