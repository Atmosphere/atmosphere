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

import org.atmosphere.ai.test.LlmJudge;

import java.util.Objects;

/**
 * LLM-as-judge scorer bridging the admin eval surfaces to
 * {@code atmosphere-ai-test}'s {@link LlmJudge} — the shipped implementation of
 * both {@link SampledLiveScorer.LiveScorer} (online scoring of live turns) and
 * {@link EvalRunner.CaseScorer} (dataset replay).
 *
 * <p>Scoring paths:</p>
 * <ul>
 *   <li><strong>Reference-aware</strong> — a dataset case with a non-blank
 *       reference is graded via {@link LlmJudge#judgeIntent}: the judge decides
 *       whether the fresh response is consistent with the recorded reference
 *       answer (score 1.0/0.0).</li>
 *   <li><strong>Rubric</strong> — live turns and reference-less cases are graded
 *       via {@link LlmJudge#judgeQuality}; the verdict passes when the mean of
 *       relevance/coherence/safety reaches {@code passThreshold}.</li>
 * </ul>
 *
 * <p>{@link LlmJudge} reports failures as {@link AssertionError} (it grew up in
 * test suites). This bridge rethrows them as {@link IllegalStateException} so
 * production consumers keep their contracts: {@link SampledLiveScorer#observe}
 * logs and skips the sample, and {@link EvalRunner} records a failed-case row.</p>
 */
public final class LlmJudgeLiveScorer
        implements SampledLiveScorer.LiveScorer, EvalRunner.CaseScorer {

    /** Default rubric pass bar: mean quality score required to pass. */
    public static final double DEFAULT_PASS_THRESHOLD = 0.7;

    private final LlmJudge judge;
    private final double passThreshold;

    public LlmJudgeLiveScorer(LlmJudge judge) {
        this(judge, DEFAULT_PASS_THRESHOLD);
    }

    public LlmJudgeLiveScorer(LlmJudge judge, double passThreshold) {
        this.judge = Objects.requireNonNull(judge, "judge");
        if (passThreshold < 0.0 || passThreshold > 1.0) {
            throw new IllegalArgumentException("passThreshold must be within [0.0, 1.0]");
        }
        this.passThreshold = passThreshold;
    }

    /** Rubric scoring for a live turn (no reference available). */
    @Override
    public SampledLiveScorer.Verdict score(String prompt, String response) {
        return quality(prompt, response);
    }

    /** Reference-aware scoring for a replayed dataset case. */
    @Override
    public SampledLiveScorer.Verdict score(EvalCase evalCase, String agentResponse) {
        if (evalCase.reference().isBlank()) {
            return quality(evalCase.prompt(), agentResponse);
        }
        try {
            var run = judge.judgeIntent(evalCase.prompt(), agentResponse,
                    "The response is consistent with this reference answer: "
                            + evalCase.reference());
            var passed = Boolean.TRUE.equals(run.verdict());
            return new SampledLiveScorer.Verdict(passed, passed ? 1.0 : 0.0,
                    "judge intent verdict against recorded reference");
        } catch (AssertionError judgeFailure) {
            throw new IllegalStateException("LLM judge failed: " + judgeFailure.getMessage(),
                    judgeFailure);
        }
    }

    private SampledLiveScorer.Verdict quality(String prompt, String response) {
        try {
            var run = judge.judgeQuality(prompt, response);
            var scores = run.quality();
            var mean = (scores.relevance() + scores.coherence() + scores.safety()) / 3.0;
            return new SampledLiveScorer.Verdict(mean >= passThreshold, mean,
                    "relevance=" + scores.relevance() + " coherence=" + scores.coherence()
                            + " safety=" + scores.safety());
        } catch (AssertionError judgeFailure) {
            throw new IllegalStateException("LLM judge failed: " + judgeFailure.getMessage(),
                    judgeFailure);
        }
    }
}
