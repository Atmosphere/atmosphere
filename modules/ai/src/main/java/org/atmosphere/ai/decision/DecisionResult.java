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

import org.atmosphere.ai.ConfidenceRoute;
import org.atmosphere.ai.ConfidenceRouting;
import org.atmosphere.ai.TokenUsage;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The answers to one {@link DecisionRequest}.
 *
 * @param model   the model that answered
 * @param answers question id → answer, in request order; a {@link DecisionModel}
 *                puts every requested id here (see its contract)
 * @param usage   token usage summed over every question, when the backend reported any
 * @param elapsed wall time of the request
 */
public record DecisionResult(String model, Map<String, Answer> answers,
                             Optional<TokenUsage> usage, Duration elapsed) {

    public DecisionResult {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(answers, "answers");
        var copy = new LinkedHashMap<String, Answer>();
        for (var entry : answers.entrySet()) {
            var answer = Objects.requireNonNull(entry.getValue(), "answer " + entry.getKey());
            if (!answer.id().equals(entry.getKey())) {
                throw new IllegalArgumentException("answer keyed '" + entry.getKey()
                        + "' carries id '" + answer.id() + "'");
            }
            copy.put(entry.getKey(), answer);
        }
        answers = Collections.unmodifiableMap(copy);
        usage = usage == null ? Optional.empty() : usage;
        elapsed = elapsed == null ? Duration.ZERO : elapsed;
    }

    /**
     * The answer to question {@code id} when it has type {@code type}. A
     * {@link Answer.Failed} is returned only when {@code type} admits it, so
     * {@code answer(id, Answer.Noul.class)} is empty for a failed question.
     */
    public <A extends Answer> Optional<A> answer(String id, Class<A> type) {
        var answer = answers.get(id);
        return type.isInstance(answer) ? Optional.of(type.cast(answer)) : Optional.empty();
    }

    /**
     * Route question {@code id} on its confidence. Delegates to
     * {@link ConfidenceRouting#route(org.atmosphere.ai.AiConfidence)}; a missing
     * or {@link Answer.Failed} answer, or one with an unknown confidence, takes
     * {@link ConfidenceRouting#unknownRoute()} ({@link ConfidenceRoute#ESCALATE}
     * by default) — an answer nobody measured never acts.
     */
    public ConfidenceRoute route(String id, ConfidenceRouting routing) {
        Objects.requireNonNull(routing, "routing");
        var answer = answers.get(id);
        if (answer == null || answer instanceof Answer.Failed) {
            return routing.unknownRoute();
        }
        return routing.route(answer.confidence());
    }
}
