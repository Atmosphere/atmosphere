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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Bounds and defensive copies of {@link DecisionRequest} and {@link Question}
 * (Correctness Invariant #3: every input fed by external state is bounded).
 */
class DecisionRequestTest {

    private static final Question NOUL = new Question.Noul("is it?", null, null);

    @Test
    void rejectsZeroAndTooManyQuestions() {
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionRequest("s", new LinkedHashMap<>(), null));
        var questions = new LinkedHashMap<String, Question>();
        for (var i = 0; i < DecisionRequest.MAX_QUESTIONS; i++) {
            questions.put("q" + i, NOUL);
        }
        assertDoesNotThrow(() -> new DecisionRequest("s", questions, null));
        questions.put("one-too-many", NOUL);
        assertThrows(IllegalArgumentException.class, () -> new DecisionRequest("s", questions, null));
    }

    @Test
    void rejectsBadIds() {
        for (var id : List.of("", "has space", "slash/", "x".repeat(65), "é")) {
            assertThrows(IllegalArgumentException.class, () -> DecisionRequest.of("s", id, NOUL), id);
        }
        assertDoesNotThrow(() -> DecisionRequest.of("s", "Ok_id-9", NOUL));
        assertDoesNotThrow(() -> DecisionRequest.of("s", "x".repeat(64), NOUL));
    }

    @Test
    void rejectsOversizedStateAndBadTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> DecisionRequest.of("x".repeat(DecisionRequest.MAX_STATE_CHARS + 1), "q", NOUL));
        assertDoesNotThrow(() -> DecisionRequest.of("x".repeat(DecisionRequest.MAX_STATE_CHARS), "q", NOUL));
        assertThrows(NullPointerException.class, () -> DecisionRequest.of(null, "q", NOUL));
        var request = DecisionRequest.of("s", "q", NOUL);
        assertThrows(IllegalArgumentException.class, () -> request.withTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> request.withTimeout(Duration.ofMillis(-1)));
        assertEquals(DecisionRequest.DEFAULT_TIMEOUT, request.withTimeout(null).timeout());
    }

    @Test
    void choiceOptionBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Question.Choice("pick", options(1)));
        assertDoesNotThrow(() -> new Question.Choice("pick", options(2)));
        assertDoesNotThrow(() -> new Question.Choice("pick", options(255)));
        assertThrows(IllegalArgumentException.class, () -> new Question.Choice("pick", options(256)));
        var blankKey = options(2);
        blankKey.put(" ", "blank");
        assertThrows(IllegalArgumentException.class, () -> new Question.Choice("pick", blankKey));
        assertThrows(IllegalArgumentException.class, () -> new Question.Choice(" ", options(2)));
    }

    @Test
    void scoreLevelBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Question.Score("rate", levels(1)));
        assertDoesNotThrow(() -> new Question.Score("rate", levels(2)));
        assertDoesNotThrow(() -> new Question.Score("rate", levels(10)));
        assertThrows(IllegalArgumentException.class, () -> new Question.Score("rate", levels(11)));
        assertThrows(IllegalArgumentException.class, () -> new Question.Score("rate", List.of("low", "")));
    }

    @Test
    void collectionsAreCopiedUnmodifiableAndKeepOrder() {
        var options = options(3);
        var choice = new Question.Choice("pick", options);
        options.put("late", "added after construction");
        assertEquals(List.of("o0", "o1", "o2"), List.copyOf(choice.options().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> choice.options().put("x", "y"));

        var levels = new ArrayList<>(levels(3));
        var score = new Question.Score("rate", levels);
        levels.add("late");
        assertEquals(3, score.levels().size());

        var questions = new LinkedHashMap<String, Question>();
        questions.put("z", NOUL);
        questions.put("a", choice);
        questions.put("m", score);
        var request = new DecisionRequest("s", questions, null);
        questions.remove("z");
        assertEquals(List.of("z", "a", "m"), List.copyOf(request.questions().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> request.questions().remove("z"));
    }

    @Test
    void noulBlankCriteriaAreAbsent() {
        var noul = new Question.Noul("is it?", " ", "");
        assertEquals(null, noul.whenTrue());
        assertEquals(null, noul.whenFalse());
    }

    private static SequencedMap<String, String> options(int n) {
        var options = new LinkedHashMap<String, String>();
        for (var i = 0; i < n; i++) {
            options.put("o" + i, "option " + i);
        }
        return options;
    }

    private static List<String> levels(int n) {
        var levels = new ArrayList<String>();
        for (var i = 0; i < n; i++) {
            levels.add("level " + i);
        }
        return levels;
    }
}
