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
package org.atmosphere.admin.evals;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.test.LlmJudge;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scores through the real {@link LlmJudge} (canned judge runtime) — proving the
 * admin bridge drives the ai-test judge end to end, on both scoring paths.
 */
class LlmJudgeLiveScorerTest {

    /** Judge runtime returning a canned JSON response. */
    private static AgentRuntime cannedJudge(String response) {
        return new AgentRuntime() {
            @Override public String name() { return "canned-judge"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }

            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                session.send(response);
                session.complete();
            }
        };
    }

    private static EvalCase caseWithReference(String reference) {
        return new EvalCase("c1", "What time is it?", reference, "manual", List.of(), Instant.now());
    }

    @Test
    void liveScoringAveragesQualityScoresAgainstThreshold() {
        var scorer = new LlmJudgeLiveScorer(new LlmJudge(
                cannedJudge("{\"relevance\": 0.9, \"coherence\": 0.8, \"safety\": 1.0}"), "judge-1"));

        var verdict = scorer.score("What time is it?", "It's 3pm.");

        assertTrue(verdict.passed(), "mean 0.9 must clear the default 0.7 bar");
        assertEquals(0.9, verdict.score(), 1e-9);
        assertTrue(verdict.notes().contains("relevance=0.9"));
    }

    @Test
    void liveScoringFailsBelowThreshold() {
        var scorer = new LlmJudgeLiveScorer(new LlmJudge(
                cannedJudge("{\"relevance\": 0.2, \"coherence\": 0.3, \"safety\": 0.4}"), "judge-1"));
        assertFalse(scorer.score("q", "bad answer").passed());
    }

    @Test
    void referenceCaseUsesIntentVerdict() {
        var passing = new LlmJudgeLiveScorer(new LlmJudge(
                cannedJudge("{\"verdict\": true}"), "judge-1"));
        var verdict = passing.score(caseWithReference("3pm"), "It's 3pm.");
        assertTrue(verdict.passed());
        assertEquals(1.0, verdict.score(), 1e-9);

        var failing = new LlmJudgeLiveScorer(new LlmJudge(
                cannedJudge("{\"verdict\": false}"), "judge-1"));
        var rejected = failing.score(caseWithReference("3pm"), "I like cats.");
        assertFalse(rejected.passed());
        assertEquals(0.0, rejected.score(), 1e-9);
    }

    @Test
    void referencelessCaseFallsBackToQualityRubric() {
        var scorer = new LlmJudgeLiveScorer(new LlmJudge(
                cannedJudge("{\"relevance\": 1.0, \"coherence\": 1.0, \"safety\": 1.0}"), "judge-1"));
        var verdict = scorer.score(caseWithReference(""), "It's 3pm.");
        assertTrue(verdict.passed());
        assertEquals(1.0, verdict.score(), 1e-9);
    }

    @Test
    void judgeFailureSurfacesAsIllegalStateNotAssertionError() {
        AgentRuntime failing = new AgentRuntime() {
            @Override public String name() { return "down-judge"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }

            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                session.error(new RuntimeException("judge LLM unavailable"));
            }
        };
        var scorer = new LlmJudgeLiveScorer(new LlmJudge(failing, "judge-1"));

        // Production contract: a RuntimeException, so SampledLiveScorer can
        // swallow it and EvalRunner records a failed-case row.
        assertThrows(IllegalStateException.class, () -> scorer.score("q", "a"));
        assertThrows(IllegalStateException.class,
                () -> scorer.score(caseWithReference("3pm"), "a"));
    }

    @Test
    void sampledLiveScorerSkipsSampleWhenJudgeFails() {
        AgentRuntime failing = cannedJudge(""); // blank judge response → LlmJudge failure
        var store = new InMemoryEvalRunStore();
        var sampled = new SampledLiveScorer(1.0,
                new LlmJudgeLiveScorer(new LlmJudge(failing, "judge-1")),
                store, "live", "judge-1", () -> 0.0);

        assertTrue(sampled.observe("q", "a").isEmpty(),
                "a judge failure must skip the sample, not break the request path");
        assertTrue(store.list().isEmpty());
    }
}
