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
 * {@link AiConfidence.Source#LOGPROBS_NATIVE} (the mean token probability of
 * the whole response, a fluency measure) or
 * {@link AiConfidence.Source#DECISION_LOGPROBS} (how concentrated the model
 * was on one value of a decision field) — see {@link AiConfidence} for what
 * each source measures.</p>
 *
 * <h2>Decision field</h2>
 * <p>{@link #withDecisionField(String)} designates one top-level enum or
 * boolean property of the structured response type as <em>the decision</em>.
 * The Built-in runtime then also requests {@code top_logprobs}, locates the
 * tokens that carry that property's value, and reports
 * {@link AiConfidence.Source#DECISION_LOGPROBS}: how sure the model was of the
 * value it emitted, from its distribution over the allowed values, instead of
 * how fluent the whole text was. The designation is honoured only on the
 * Built-in chat-completions path in structured-output mode; a decision turn
 * against {@code api.openai.com} is therefore sent through chat completions
 * even when its conversation id would otherwise select the Responses API.
 * Every other runtime and a free-text response keep their existing source and
 * ignore it.</p>
 *
 * @param fieldName       the JSON field the model is asked to emit
 *                        (default {@code "confidence"})
 * @param systemPromptCue text appended to the system prompt;
 *                        {@code null} means use the default cue
 * @param decisionField   top-level enum/boolean property of the structured
 *                        response whose value is the decision to score, or
 *                        {@code null} for none (the default)
 */
public record AiConfidenceElicitation(String fieldName, String systemPromptCue,
                                      String decisionField) {

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
        if (decisionField != null && decisionField.isBlank()) {
            throw new IllegalArgumentException("decisionField must be null or non-blank");
        }
    }

    /** Two-component form (no decision field), kept so existing callers compile unchanged. */
    public AiConfidenceElicitation(String fieldName, String systemPromptCue) {
        this(fieldName, systemPromptCue, null);
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

    /**
     * Same elicitation, designating {@code field} as the decision whose value
     * distribution is scored (see the class Javadoc, <em>Decision field</em>).
     *
     * @param field a top-level enum or boolean property of the structured
     *              response type
     */
    public AiConfidenceElicitation withDecisionField(String field) {
        Objects.requireNonNull(field, "field");
        return new AiConfidenceElicitation(fieldName, systemPromptCue, field);
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
