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
import org.atmosphere.ai.ConfidenceRoute;
import org.atmosphere.ai.ConfidenceRouting;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DecisionResult} routing: an answer nobody measured never acts. */
class DecisionResultTest {

    private static final ConfidenceRouting ROUTING = ConfidenceRouting.of(0.9, 0.5);

    @Test
    void routesOnTheAnswerConfidence() {
        var result = result(
                new Answer.Noul("act", true, OptionalDouble.empty(), AiConfidence.reported(0.95)),
                new Answer.Noul("confirm", true, OptionalDouble.empty(), AiConfidence.reported(0.6)),
                new Answer.Noul("escalate", true, OptionalDouble.empty(), AiConfidence.reported(0.2)),
                new Answer.Noul("unknown", true, OptionalDouble.empty(),
                        AiConfidence.unknown(AiConfidence.Source.MODEL_REPORTED_FIELD)),
                new Answer.Failed("failed", Answer.Failed.Reason.TIMEOUT, "late"));
        assertEquals(ConfidenceRoute.ACT, result.route("act", ROUTING));
        assertEquals(ConfidenceRoute.CONFIRM, result.route("confirm", ROUTING));
        assertEquals(ConfidenceRoute.ESCALATE, result.route("escalate", ROUTING));
        assertEquals(ConfidenceRoute.ESCALATE, result.route("unknown", ROUTING));
        assertEquals(ConfidenceRoute.ESCALATE, result.route("failed", ROUTING));
        assertEquals(ConfidenceRoute.ESCALATE, result.route("missing", ROUTING));
    }

    @Test
    void failedRoutesToTheUnknownRouteEvenWhenItIsNotEscalate() {
        var result = result(new Answer.Failed("failed", Answer.Failed.Reason.ERROR, "boom"));
        assertEquals(ConfidenceRoute.CONFIRM,
                result.route("failed", ROUTING.withUnknownRoute(ConfidenceRoute.CONFIRM)));
    }

    @Test
    void failedConfidenceIsAlwaysUnknown() {
        var failed = new Answer.Failed("f", Answer.Failed.Reason.CAPACITY, null);
        assertTrue(failed.confidence().aggregate().isEmpty());
        assertEquals("", failed.detail());
    }

    @Test
    void typedLookupAndOrder() {
        var result = result(
                new Answer.Choice("b", "x", Map.of(), AiConfidence.reported(0.5)),
                new Answer.Failed("a", Answer.Failed.Reason.ERROR, "boom"));
        assertEquals(List.of("b", "a"), List.copyOf(result.answers().keySet()));
        assertTrue(result.answer("b", Answer.Choice.class).isPresent());
        assertFalse(result.answer("b", Answer.Noul.class).isPresent());
        assertFalse(result.answer("a", Answer.Noul.class).isPresent());
        assertTrue(result.answer("a", Answer.Failed.class).isPresent());
        assertThrows(UnsupportedOperationException.class, () -> result.answers().clear());
    }

    @Test
    void rejectsAnAnswerFiledUnderAnotherId() {
        var answers = Map.<String, Answer>of("x",
                new Answer.Failed("y", Answer.Failed.Reason.ERROR, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionResult("m", answers, Optional.empty(), Duration.ZERO));
    }

    private static DecisionResult result(Answer... answers) {
        var map = new LinkedHashMap<String, Answer>();
        for (var answer : answers) {
            map.put(answer.id(), answer);
        }
        return new DecisionResult("test", map, Optional.empty(), Duration.ZERO);
    }
}
