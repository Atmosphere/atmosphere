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
package org.atmosphere.ai.guardrails;

import org.atmosphere.ai.AiGuardrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Drift detector: flags responses whose character length is more than
 * {@code zScoreThreshold} standard deviations away from the rolling mean
 * of recent responses. Catches truncated replies, runaway generations,
 * and model-degradation drift at the wire level — the v0.8 "drift /
 * anomaly guardrail" gap the 2026 Dynatrace agentic-AI report flagged.
 *
 * <p>The guardrail is stateful (keeps a rolling window of response
 * lengths per process) but thread-safe — the window guards itself with a
 * dedicated synchronized block. A single global detector is the sweet
 * spot for most deployments; partition by agent-id via the optional
 * {@code bucketKey} if you want independent baselines per endpoint.</p>
 *
 * <h2>Tuning</h2>
 *
 * <ul>
 *   <li>{@code windowSize} — number of recent responses to average. 50 is
 *       a reasonable default: large enough to damp per-response noise,
 *       small enough that a bad model version starts triggering within
 *       a few dozen calls.</li>
 *   <li>{@code zScoreThreshold} — 3.0 roughly corresponds to the 99.7th
 *       percentile under a normal distribution. Conservative so only
 *       genuine outliers fire.</li>
 *   <li>{@code minSamples} — ignore the first N responses (the window
 *       hasn't stabilized). Default 10.</li>
 * </ul>
 */
public final class OutputLengthZScoreGuardrail implements AiGuardrail {

    private static final Logger logger = LoggerFactory.getLogger(OutputLengthZScoreGuardrail.class);

    private final int windowSize;
    private final double zScoreThreshold;
    private final int minSamples;
    private final Deque<Integer> window = new ArrayDeque<>();

    public OutputLengthZScoreGuardrail() {
        this(50, 3.0, 10);
    }

    public OutputLengthZScoreGuardrail(int windowSize, double zScoreThreshold, int minSamples) {
        if (windowSize < 2) {
            throw new IllegalArgumentException("windowSize must be >= 2");
        }
        if (zScoreThreshold <= 0) {
            throw new IllegalArgumentException("zScoreThreshold must be > 0");
        }
        if (minSamples < 2) {
            throw new IllegalArgumentException("minSamples must be >= 2");
        }
        this.windowSize = windowSize;
        this.zScoreThreshold = zScoreThreshold;
        this.minSamples = minSamples;
    }

    @Override
    public GuardrailResult inspectResponse(String accumulatedResponse) {
        if (accumulatedResponse == null) {
            return GuardrailResult.pass();
        }
        var length = accumulatedResponse.length();
        synchronized (window) {
            if (window.size() < minSamples) {
                window.addLast(length);
                return GuardrailResult.pass();
            }

            var sum = 0L;
            var sumSq = 0.0;
            for (var v : window) {
                sum += v;
                sumSq += (double) v * v;
            }
            var n = window.size();
            var mean = sum / (double) n;
            var variance = (sumSq / n) - (mean * mean);
            var stddev = variance > 0 ? Math.sqrt(variance) : 1.0;
            var z = Math.abs((length - mean) / stddev);

            // Record this sample regardless of outcome so the baseline keeps
            // tracking the live distribution (evicting once we hit capacity).
            window.addLast(length);
            while (window.size() > windowSize) {
                window.removeFirst();
            }

            if (z > zScoreThreshold) {
                logger.warn("Output length {} chars is {} sigma from rolling mean {} — possible drift",
                        length, String.format("%.2f", z), String.format("%.1f", mean));
                return GuardrailResult.block(
                        "response length z-score " + String.format("%.2f", z)
                                + " exceeds threshold " + zScoreThreshold
                                + " (possible drift)");
            }
            return GuardrailResult.pass();
        }
    }

    /** Package-private — tests read the snapshot for assertions. */
    int currentSamples() {
        synchronized (window) {
            return window.size();
        }
    }
}
