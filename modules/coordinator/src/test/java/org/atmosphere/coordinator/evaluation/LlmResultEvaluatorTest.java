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

class LlmResultEvaluatorTest {

    @Test
    void parsesFencedJsonAndPreservesJudgeArtifacts() {
        var result = result();
        var call = call();
        var prompt = LlmResultEvaluator.buildJudgePrompt(result, call);

        var evaluation = LlmResultEvaluator.parseScore(
                """
                ```json
                {"score": 4, "reason": "too vague"}
                ```
                """,
                result,
                call,
                prompt,
                "gpt-test");

        assertFalse(evaluation.passed());
        assertEquals(0.4, evaluation.score());
        assertEquals("too vague", evaluation.reason());
        assertEquals(4.0, evaluation.metadata().get("rawScore"));
        assertEquals("json", evaluation.metadata().get("scoreSource"));
        assertEquals(prompt, evaluation.metadata().get("judgePrompt"));
        assertEquals("research", evaluation.metadata().get("agent"));
        assertEquals("summarize", evaluation.metadata().get("skill"));
        assertEquals("gpt-test", evaluation.metadata().get("model"));
        assertTrue(evaluation.metadata().get("judgeResponse").toString().contains("```json"));
    }

    @Test
    void bareScoreMetadataIdentifiesScoreSource() {
        var result = result();
        var call = call();

        var evaluation = LlmResultEvaluator.parseScore("8/10", result, call, "prompt", "gpt-test");

        assertTrue(evaluation.passed());
        assertEquals(0.8, evaluation.score());
        assertEquals("bare", evaluation.metadata().get("scoreSource"));
        assertEquals(8.0, evaluation.metadata().get("rawScore"));
    }

    @Test
    void unparseableResponseReturnsFallbackWithArtifacts() {
        var result = result();
        var call = call();

        var evaluation = LlmResultEvaluator.parseScore("not a score", result, call, "prompt", null);

        assertTrue(evaluation.passed());
        assertEquals(0.7, evaluation.score());
        assertEquals("fallback", evaluation.metadata().get("scoreSource"));
        assertEquals("", evaluation.metadata().get("model"));
        assertEquals("not a score", evaluation.metadata().get("judgeResponse"));
    }

    private static AgentResult result() {
        return new AgentResult("research", "summarize", "answer", Map.of(), Duration.ZERO, true);
    }

    private static AgentCall call() {
        return new AgentCall("research", "summarize", Map.of("topic", "Atmosphere"));
    }
}
