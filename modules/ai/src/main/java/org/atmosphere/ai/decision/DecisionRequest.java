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

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.regex.Pattern;

/**
 * The state to judge and the questions to answer about it. Bounded on every
 * axis (Correctness Invariant #3): at most {@value #MAX_QUESTIONS} questions and
 * {@value #MAX_STATE_CHARS} characters of state.
 *
 * @param state     the material the questions are about; treated as data, never
 *                  as instructions to the model
 * @param questions question id → question, in the order answers are returned;
 *                  ids match {@code [A-Za-z0-9_-]{1,64}}
 * @param timeout   bound on the whole request ({@code null} means
 *                  {@link #DEFAULT_TIMEOUT}); must be positive
 */
public record DecisionRequest(String state, SequencedMap<String, Question> questions, Duration timeout) {

    /** Most questions one request may carry. */
    public static final int MAX_QUESTIONS = 64;

    /** Most characters of state one request may carry (the same bound as the tool-output screen). */
    public static final int MAX_STATE_CHARS = 256 * 1024;

    /** Default bound on a request; the same as the LLM injection classifier's historical default. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public DecisionRequest {
        Objects.requireNonNull(state, "state");
        if (state.length() > MAX_STATE_CHARS) {
            throw new IllegalArgumentException("state exceeds " + MAX_STATE_CHARS
                    + " characters: " + state.length());
        }
        Objects.requireNonNull(questions, "questions");
        if (questions.isEmpty() || questions.size() > MAX_QUESTIONS) {
            throw new IllegalArgumentException("a request needs 1.." + MAX_QUESTIONS
                    + " questions, got " + questions.size());
        }
        var copy = new LinkedHashMap<String, Question>();
        for (var entry : questions.entrySet()) {
            var id = entry.getKey();
            if (id == null || !ID.matcher(id).matches()) {
                throw new IllegalArgumentException("question id must match [A-Za-z0-9_-]{1,64}, got '"
                        + id + "'");
            }
            copy.put(id, Objects.requireNonNull(entry.getValue(), "question " + id));
        }
        questions = Collections.unmodifiableSequencedMap(copy);
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeout);
        }
    }

    /**
     * A single-question request with the {@link #DEFAULT_TIMEOUT}.
     *
     * @param state    the material to judge
     * @param id       the question id
     * @param question the question
     * @return the request
     */
    public static DecisionRequest of(String state, String id, Question question) {
        var questions = new LinkedHashMap<String, Question>();
        questions.put(id, question);
        return new DecisionRequest(state, questions, DEFAULT_TIMEOUT);
    }

    /** Same state and questions with a different bound. */
    public DecisionRequest withTimeout(Duration timeout) {
        return new DecisionRequest(state, questions, timeout);
    }
}
