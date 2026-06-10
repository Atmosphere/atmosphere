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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

/**
 * Scores a sampled fraction of live production turns and records each verdict as
 * an {@link EvalRun} — the "online scoring" half of the eval flywheel. Apps feed
 * completed {@code (prompt, response)} pairs to {@link #observe}; a configurable
 * fraction are graded by the supplied {@link LiveScorer} (an LLM-as-judge, a
 * rubric, a heuristic) and the result lands in the same {@link EvalRunStore} the
 * admin dashboard already reads, under a dedicated baseline (default
 * {@code "live"}).
 *
 * <p>Sampling keeps the cost bounded on high-traffic deployments. The sampling
 * source is injectable so the gate is deterministic under test.</p>
 *
 * <p>Thread-safe and side-effect-isolated: a scorer or sink failure is logged
 * and swallowed so live scoring never breaks the request path it observes.</p>
 */
public final class SampledLiveScorer {

    private static final Logger logger = LoggerFactory.getLogger(SampledLiveScorer.class);

    /** Grades a single live turn. */
    @FunctionalInterface
    public interface LiveScorer {
        Verdict score(String prompt, String response);
    }

    /**
     * A scoring verdict.
     *
     * @param passed whether the turn met the bar
     * @param score  a normalized quality score (typically {@code [0.0, 1.0]})
     * @param notes  human-readable rationale
     */
    public record Verdict(boolean passed, double score, String notes) { }

    private final double sampleRate;
    private final DoubleSupplier sampler;
    private final LiveScorer scorer;
    private final EvalRunStore sink;
    private final String baseline;
    private final String judgeLabel;
    private final AtomicLong sequence = new AtomicLong();

    /** Build with the default random sampler and {@code "live"} baseline. */
    public SampledLiveScorer(double sampleRate, LiveScorer scorer, EvalRunStore sink) {
        this(sampleRate, scorer, sink, "live", "live-scorer",
                () -> ThreadLocalRandom.current().nextDouble());
    }

    public SampledLiveScorer(double sampleRate, LiveScorer scorer, EvalRunStore sink,
                             String baseline, String judgeLabel, DoubleSupplier sampler) {
        if (scorer == null || sink == null) {
            throw new IllegalArgumentException("scorer and sink must not be null");
        }
        this.sampleRate = Math.max(0.0, Math.min(1.0, sampleRate));
        this.scorer = scorer;
        this.sink = sink;
        this.baseline = baseline != null && !baseline.isBlank() ? baseline : "live";
        this.judgeLabel = judgeLabel != null ? judgeLabel : "live-scorer";
        this.sampler = sampler != null ? sampler : () -> ThreadLocalRandom.current().nextDouble();
    }

    /** The configured sampling rate, clamped to {@code [0.0, 1.0]}. */
    public double sampleRate() {
        return sampleRate;
    }

    /**
     * Observe a completed live turn. With probability {@link #sampleRate} the
     * turn is graded and recorded; otherwise it is skipped.
     *
     * @return the recorded {@link EvalRun}, or empty when the turn was not
     *         sampled (or scoring/recording failed)
     */
    public Optional<EvalRun> observe(String prompt, String response) {
        if (sampleRate <= 0.0 || sampler.getAsDouble() >= sampleRate) {
            return Optional.empty();
        }
        Verdict verdict;
        try {
            verdict = scorer.score(prompt == null ? "" : prompt, response == null ? "" : response);
        } catch (RuntimeException e) {
            logger.warn("Live scorer threw — skipping this sample: {}", e.toString());
            return Optional.empty();
        }
        if (verdict == null) {
            return Optional.empty();
        }
        var run = new EvalRun(
                nextId(), baseline, Instant.now(), "",
                prompt != null ? prompt : "",
                response != null ? response : "",
                verdict.passed(), Map.of("score", verdict.score()),
                judgeLabel, verdict.passed(), verdict.notes());
        try {
            return Optional.of(sink.save(run));
        } catch (RuntimeException e) {
            logger.warn("Failed to record live eval run {}: {}", run.id(), e.toString());
            return Optional.empty();
        }
    }

    private String nextId() {
        return baseline + "-" + Instant.now().toEpochMilli() + "-" + sequence.incrementAndGet();
    }
}
