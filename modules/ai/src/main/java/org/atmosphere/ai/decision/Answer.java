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

import org.atmosphere.ai.AiConfidence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * A {@link DecisionModel}'s answer to one {@link Question}.
 *
 * <p>{@link #confidence()} is the existing {@link AiConfidence}, so
 * {@link org.atmosphere.ai.ConfidenceRouting#route(AiConfidence)} gates an answer
 * unchanged. Its {@link AiConfidence#source()} says how the value was derived:
 * {@link AiConfidence.Source#DECISION_LOGPROBS} when the model's distribution
 * over the allowed values was observed, {@link AiConfidence.Source#MODEL_REPORTED_FIELD}
 * when the model stated a number. Probability maps (and
 * {@link Noul#probabilityTrue()}) are filled only from an observed distribution
 * and are empty otherwise — never inferred from a self-reported number
 * (Correctness Invariant #5).</p>
 */
public sealed interface Answer permits Answer.Choice, Answer.Score, Answer.Noul, Answer.Failed {

    /** The question id this answers. */
    String id();

    /** How sure the model was, and how that was measured. */
    AiConfidence confidence();

    /**
     * Answer to a {@link Question.Choice}.
     *
     * @param id            the question id
     * @param choice        the chosen option key
     * @param probabilities option key → probability from an observed
     *                      distribution; empty when none was observed
     * @param confidence    the confidence
     */
    record Choice(String id, String choice, Map<String, Double> probabilities,
                  AiConfidence confidence) implements Answer {
        public Choice {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(choice, "choice");
            Objects.requireNonNull(confidence, "confidence");
            probabilities = probabilities == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(probabilities));
        }
    }

    /**
     * Answer to a {@link Question.Score}.
     *
     * @param id            the question id
     * @param score         the expected level {@code Σ i·p(i)} when a
     *                      distribution was observed, otherwise the chosen level
     * @param probabilities level → probability from an observed distribution;
     *                      empty when none was observed
     * @param confidence    the confidence
     */
    record Score(String id, double score, Map<Integer, Double> probabilities,
                 AiConfidence confidence) implements Answer {
        public Score {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(confidence, "confidence");
            probabilities = probabilities == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(probabilities));
        }
    }

    /**
     * Answer to a {@link Question.Noul}.
     *
     * @param id              the question id
     * @param value           the decided value
     * @param probabilityTrue {@code p(true)} from an observed distribution;
     *                        empty when none was observed
     * @param confidence      the confidence
     */
    record Noul(String id, boolean value, OptionalDouble probabilityTrue,
                AiConfidence confidence) implements Answer {
        public Noul {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(confidence, "confidence");
            probabilityTrue = probabilityTrue == null ? OptionalDouble.empty() : probabilityTrue;
        }
    }

    /**
     * The question could not be answered. Its confidence is always unknown, so
     * {@link org.atmosphere.ai.ConfidenceRouting} routes it to its unknown route
     * ({@link org.atmosphere.ai.ConfidenceRoute#ESCALATE} by default).
     *
     * @param id     the question id
     * @param reason why
     * @param detail human-readable detail for logs and audit
     */
    record Failed(String id, Reason reason, String detail) implements Answer {

        /** Why a question was not answered. */
        public enum Reason {
            /** The request's deadline passed before the answer arrived. */
            TIMEOUT,
            /** No capacity to start the question before the deadline. */
            CAPACITY,
            /** The backend failed. */
            ERROR,
            /** The reply was not a parseable answer. */
            UNPARSEABLE,
            /** The reply parsed but its value is outside the allowed set. */
            INVALID_ANSWER
        }

        public Failed {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(reason, "reason");
            detail = detail == null ? "" : detail;
        }

        @Override
        public AiConfidence confidence() {
            return AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD);
        }
    }
}
