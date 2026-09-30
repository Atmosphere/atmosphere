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
package org.atmosphere.ai.decision;

import java.util.Locale;

/**
 * Turns the answer to a {@link Question.Noul} whose {@code true} value is the
 * outcome a safety check acts on (off-topic, a moderation category) into one of
 * three verdicts: {@link Outcome#FLAGGED}, {@link Outcome#CLEAR} or
 * {@link Outcome#UNCERTAIN}. It applies the same mapping, with the same default
 * thresholds, as the {@code LLM_CLASSIFIER} injection tier
 * ({@code org.atmosphere.ai.governance.rag.LlmClassifierInjectionClassifier}).
 *
 * <ul>
 *   <li>Measured {@code p = P(true)} ({@link Answer.Noul#probabilityTrue()}):
 *       {@code p >= flaggedAt} is FLAGGED with confidence {@code p};
 *       {@code p < clearBelow} is CLEAR with confidence {@code 1 - p}; the band
 *       between is UNCERTAIN.</li>
 *   <li>Not measured: the self-reported confidence {@code c} reads as the same
 *       belief, {@code P(true) = c} for a {@code true} answer and {@code 1 - c}
 *       for a {@code false} one, and the same two thresholds apply. A
 *       {@code false} answer with no usable confidence is UNCERTAIN; a
 *       {@code true} answer with none is FLAGGED with confidence {@code NaN}.</li>
 *   <li>CLEAR also requires the answer itself to be {@code false}: a {@code true}
 *       answer whose belief is below {@code clearBelow} is UNCERTAIN.</li>
 *   <li>A missing answer, an {@link Answer.Failed} (timeout, no capacity,
 *       backend error, unparseable or out-of-set reply) or an answer of another
 *       type is UNCERTAIN.</li>
 * </ul>
 * Nothing is CLEAR unless the model answered {@code false} and its belief is
 * below {@code clearBelow}; the caller decides what UNCERTAIN means, and a
 * security check treats it as a failure (Correctness Invariant #6). Every reason
 * names the confidence source.
 *
 * @param flaggedAt  {@code P(true)} at or above which the answer is FLAGGED
 * @param clearBelow {@code P(true)} below which a {@code false} answer is CLEAR
 */
public record NoulGate(double flaggedAt, double clearBelow) {

    /** Default {@code P(true)} at or above which the answer is flagged. */
    public static final double DEFAULT_FLAGGED_AT = 0.5;

    /** Default {@code P(true)} below which a {@code false} answer is clear. */
    public static final double DEFAULT_CLEAR_BELOW = 0.2;

    /** The default thresholds. */
    public static final NoulGate DEFAULTS = new NoulGate(DEFAULT_FLAGGED_AT, DEFAULT_CLEAR_BELOW);

    public NoulGate {
        if (!(clearBelow >= 0.0 && clearBelow <= flaggedAt && flaggedAt <= 1.0)) {
            throw new IllegalArgumentException(
                    "thresholds must satisfy 0 <= clearBelow <= flaggedAt <= 1, got clearBelow="
                            + clearBelow + ", flaggedAt=" + flaggedAt);
        }
    }

    /** The three verdicts. */
    public enum Outcome {
        /** The belief in {@code true} reached {@code flaggedAt}. */
        FLAGGED,
        /** The model answered {@code false} and its belief is below {@code clearBelow}. */
        CLEAR,
        /** Anything else: no answer, a failed answer, or a belief nobody can act on. */
        UNCERTAIN
    }

    /**
     * A verdict.
     *
     * @param outcome    the verdict
     * @param confidence for FLAGGED the belief in {@code true} ({@code NaN} when the
     *                   model gave none), for CLEAR {@code 1 - P(true)}, {@code NaN}
     *                   for UNCERTAIN
     * @param reason     how the verdict was reached, naming the confidence source
     */
    public record Verdict(Outcome outcome, double confidence, String reason) {
    }

    /**
     * Judge one answer.
     *
     * @param answer the answer to the question, {@code null} when none came back
     * @param label  what {@code true} means, used in the reason ({@code "off-topic"})
     * @return the verdict
     */
    public Verdict judge(Answer answer, String label) {
        return switch (answer) {
            case null -> uncertain("no answer");
            case Answer.Failed failed -> uncertain(failed.reason().name().toLowerCase(Locale.ROOT)
                    + ": " + failed.detail());
            case Answer.Noul noul -> judge(noul, label);
            default -> uncertain("unexpected answer type " + answer.getClass().getSimpleName());
        };
    }

    private Verdict judge(Answer.Noul noul, String label) {
        var source = noul.confidence().source().name();
        if (noul.probabilityTrue().isPresent()) {
            var p = noul.probabilityTrue().getAsDouble();
            return threshold(noul.value(), p, String.format(Locale.ROOT,
                    "P(%s)=%.3f with answer=%s [%s]", label, p, noul.value(), source));
        }
        var aggregate = noul.confidence().aggregate();
        if (aggregate.isEmpty()) {
            if (noul.value()) {
                return new Verdict(Outcome.FLAGGED, Double.NaN,
                        label + " with answer=true, confidence=unknown [" + source + "]");
            }
            return uncertain("answered false with no confidence [" + source + "]");
        }
        var reported = aggregate.getAsDouble();
        var p = noul.value() ? reported : 1.0 - reported;
        return threshold(noul.value(), p, String.format(Locale.ROOT,
                "P(%s)=%.3f from answer=%s confidence=%.3f [%s]", label, p, noul.value(), reported, source));
    }

    private Verdict threshold(boolean answeredTrue, double p, String belief) {
        if (p >= flaggedAt) {
            return new Verdict(Outcome.FLAGGED, p, belief);
        }
        if (p < clearBelow) {
            if (answeredTrue) {
                return uncertain("the answer is true but " + belief);
            }
            return new Verdict(Outcome.CLEAR, 1.0 - p, belief);
        }
        return uncertain(belief);
    }

    private static Verdict uncertain(String reason) {
        return new Verdict(Outcome.UNCERTAIN, Double.NaN, reason);
    }
}
