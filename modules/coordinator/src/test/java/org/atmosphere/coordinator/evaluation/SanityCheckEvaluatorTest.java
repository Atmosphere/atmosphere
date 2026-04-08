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
package org.atmosphere.coordinator.evaluation;

import org.atmosphere.coordinator.fleet.AgentCall;
import org.atmosphere.coordinator.fleet.AgentResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityResultEvaluatorTest {

    private final QualityResultEvaluator evaluator = new QualityResultEvaluator();
    private final AgentCall call = new AgentCall("agent", "skill", Map.of());

    @Test
    void failedCallScoresZero() {
        var result = AgentResult.failure("agent", "skill", "timeout", Duration.ZERO);
        var eval = evaluator.evaluate(result, call);
        assertFalse(eval.passed());
        assertEquals(0.0, eval.score());
        assertTrue(eval.reason().contains("failed"));
    }

    @Test
    void emptyResponseFails() {
        var result = new AgentResult("agent", "skill", "", Map.of(), Duration.ZERO, true);
        var eval = evaluator.evaluate(result, call);
        assertFalse(eval.passed());
        assertEquals(0.0, eval.score());
    }

    @Test
    void nullResponseFails() {
        var result = new AgentResult("agent", "skill", null, Map.of(), Duration.ZERO, true);
        var eval = evaluator.evaluate(result, call);
        assertFalse(eval.passed());
    }

    @Test
    void tooShortResponseFails() {
        var result = new AgentResult("agent", "skill", "ok", Map.of(), Duration.ZERO, true);
        var eval = evaluator.evaluate(result, call);
        assertFalse(eval.passed());
        assertTrue(eval.reason().contains("Too short"));
    }

    @Test
    void adequateResponsePasses() {
        var result = new AgentResult("agent", "skill",
                "The market analysis shows strong growth potential in the AI fitness sector.",
                Map.of(), Duration.ZERO, true);
        var eval = evaluator.evaluate(result, call);
        assertTrue(eval.passed());
        assertTrue(eval.score() > 0.5);
    }

    @Test
    void errorIndicatorsPenalizeScore() {
        var result = new AgentResult("agent", "skill",
                "The analysis failed due to a timeout error in the research pipeline.",
                Map.of(), Duration.ZERO, true);
        var eval = evaluator.evaluate(result, call);
        // Contains "failed", "timeout", "error" — three penalties
        assertTrue(eval.score() < 0.5);
    }

    @Test
    void structuredResponseGetsBonus() {
        var result = new AgentResult("agent", "skill",
                "The market is growing. Revenue projections are strong. Recommend entry.",
                Map.of(), Duration.ZERO, true);
        var eval = evaluator.evaluate(result, call);
        assertTrue(eval.passed());
        assertTrue(eval.reason().contains("structured"));
    }

    @Test
    void metadataIncludesWordCount() {
        var result = new AgentResult("agent", "skill",
                "This is a test response with enough words to pass the minimum threshold easily.",
                Map.of(), Duration.ZERO, true);
        var eval = evaluator.evaluate(result, call);
        assertTrue(eval.metadata().containsKey("wordCount"));
    }

    @Test
    void customMinWords() {
        var strict = new QualityResultEvaluator(50, 0.3);
        var result = new AgentResult("agent", "skill",
                "Short but valid response for normal evaluator.",
                Map.of(), Duration.ZERO, true);

        assertTrue(evaluator.evaluate(result, call).passed());
        assertFalse(strict.evaluate(result, call).passed());
    }

    @Test
    void nameReturnsQuality() {
        assertEquals("quality", evaluator.name());
    }
}
