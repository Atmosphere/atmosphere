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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * One typed question a {@link DecisionModel} answers about the request state.
 */
public sealed interface Question permits Question.Choice, Question.Score, Question.Noul {

    /** What to decide, in natural language. Never blank. */
    String instructions();

    /**
     * Pick exactly one option. Answered by {@link Answer.Choice}.
     *
     * @param instructions what to decide
     * @param options      {@value #MIN_OPTIONS}..{@value #MAX_OPTIONS} unique,
     *                     non-blank option keys in presentation order, each with
     *                     a description ({@code ""} or {@code null} for none)
     */
    record Choice(String instructions, SequencedMap<String, String> options) implements Question {

        /** Fewest options a choice may offer. */
        public static final int MIN_OPTIONS = 2;

        /** Most options a choice may offer. */
        public static final int MAX_OPTIONS = 255;

        public Choice {
            instructions = requireText(instructions, "instructions");
            Objects.requireNonNull(options, "options");
            if (options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
                throw new IllegalArgumentException("a choice needs " + MIN_OPTIONS + ".."
                        + MAX_OPTIONS + " options, got " + options.size());
            }
            var copy = new LinkedHashMap<String, String>();
            for (var entry : options.entrySet()) {
                var key = requireText(entry.getKey(), "option key");
                copy.put(key, entry.getValue() == null ? "" : entry.getValue());
            }
            options = Collections.unmodifiableSequencedMap(copy);
        }
    }

    /**
     * Place the state on an ordered rubric. Level {@code i} (zero-based) is
     * answered as {@code i}. Answered by {@link Answer.Score}.
     *
     * @param instructions what to score
     * @param levels       {@value #MIN_LEVELS}..{@value #MAX_LEVELS} non-blank level
     *                     descriptions, lowest first
     */
    record Score(String instructions, List<String> levels) implements Question {

        /** Fewest levels a rubric may have. */
        public static final int MIN_LEVELS = 2;

        /** Most levels a rubric may have. */
        public static final int MAX_LEVELS = 10;

        public Score {
            instructions = requireText(instructions, "instructions");
            Objects.requireNonNull(levels, "levels");
            if (levels.size() < MIN_LEVELS || levels.size() > MAX_LEVELS) {
                throw new IllegalArgumentException("a score needs " + MIN_LEVELS + ".."
                        + MAX_LEVELS + " levels, got " + levels.size());
            }
            for (var level : levels) {
                requireText(level, "level");
            }
            levels = List.copyOf(levels);
        }
    }

    /**
     * Decide a boolean. Answered by {@link Answer.Noul}.
     *
     * @param instructions what to decide
     * @param whenTrue     optional criterion for {@code true} ({@code null} for none)
     * @param whenFalse    optional criterion for {@code false} ({@code null} for none)
     */
    record Noul(String instructions, String whenTrue, String whenFalse) implements Question {

        public Noul {
            instructions = requireText(instructions, "instructions");
            whenTrue = whenTrue == null || whenTrue.isBlank() ? null : whenTrue;
            whenFalse = whenFalse == null || whenFalse.isBlank() ? null : whenFalse;
        }
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return value;
    }
}
