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
import org.atmosphere.ai.AiRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drift guard: <b>blocks</b> a response whose character length is more than
 * {@code zScoreThreshold} standard deviations away from the rolling mean
 * of recent responses, and logs a {@code possible drift} warning. Catches
 * runaway generations, truncated replies (text-only checks), and
 * model-degradation drift at the wire level. To flag without blocking, install it in dry-run
 * ({@code org.atmosphere.ai.governance.DryRunPolicy}).
 *
 * <h2>Streamed replies count once</h2>
 *
 * A streaming session inspects the text received so far every few hundred
 * characters and once more when the reply completes, always under the
 * request that produced it. The window keeps one sample per reply, keyed by
 * that request (by identity): each check updates the reply's own sample to
 * the length seen so far instead of adding another, and is scored against the
 * other replies in the window only. So the baseline is made of complete reply
 * lengths, the warm-up counts replies, and a reply still streaming blocks as
 * soon as it is already too long. Because a check cannot tell a reply that is
 * still growing from a finished one, a reply checked under its request is
 * judged on the high side only: it is never blocked for being short. The
 * text-only {@link #inspectResponse(String)} entry point has no request to
 * key on: each call is a complete reply of its own, judged on both sides.
 *
 * <h2>Per-tenant baselines</h2>
 *
 * Multi-tenant deployments partition the rolling window by the
 * {@code business.tenant.id} SLF4J MDC key (populated on every turn by
 * {@code AiEndpointHandler.applyBusinessMdc}). A noisy tenant no longer
 * poisons every other tenant's baseline — each gets its own window,
 * mean, and sigma. Turns without a tenant tag fall through to a shared
 * {@code "__default__"} bucket, so single-tenant deployments behave
 * exactly as before.
 *
 * <p>The window is stateful but thread-safe — each bucket guards itself
 * with a dedicated synchronized block and buckets are stored in a
 * {@link ConcurrentHashMap} so inserts don't serialize across
 * tenants.</p>
 *
 * <h2>Tuning</h2>
 *
 * <ul>
 *   <li>{@code windowSize} — number of recent responses per tenant to
 *       average. 50 is a reasonable default.</li>
 *   <li>{@code zScoreThreshold} — 3.0 roughly corresponds to the 99.7th
 *       percentile under a normal distribution.</li>
 *   <li>{@code minSamples} — pass every response until the window holds
 *       this many other responses for the tenant (window hasn't
 *       stabilized). Default 10.</li>
 * </ul>
 */
public final class OutputLengthZScoreGuardrail implements AiGuardrail {

    private static final Logger logger = LoggerFactory.getLogger(OutputLengthZScoreGuardrail.class);

    /**
     * Fallback bucket for turns that do not carry a
     * {@code business.tenant.id} MDC tag. Single-tenant apps keep
     * a single window; multi-tenant apps that forget to tag a specific
     * request get a "visible in the metrics" shared bucket rather than
     * silently cross-contaminated tenant windows.
     */
    static final String DEFAULT_BUCKET = "__default__";

    private static final String TENANT_MDC_KEY = "business.tenant.id";

    private final int windowSize;
    private final double zScoreThreshold;
    private final int minSamples;
    private final ConcurrentHashMap<String, Deque<Sample>> windowsByTenant =
            new ConcurrentHashMap<>();

    /**
     * One reply's length. {@code reply} is the request that produced it, held
     * weakly so the window never keeps a request (and its history) alive;
     * {@code null} for a text-only check, which never matches another.
     */
    private static final class Sample {
        private final WeakReference<AiRequest> reply;
        private int length;

        private Sample(AiRequest reply, int length) {
            this.reply = reply == null ? null : new WeakReference<>(reply);
            this.length = length;
        }

        private boolean isOf(AiRequest request) {
            return request != null && reply != null && reply.get() == request;
        }
    }

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
        return inspect(null, accumulatedResponse);
    }

    @Override
    public GuardrailResult inspectResponse(AiRequest originatingRequest, String accumulatedResponse) {
        return inspect(originatingRequest, accumulatedResponse);
    }

    private GuardrailResult inspect(AiRequest reply, String accumulatedResponse) {
        if (accumulatedResponse == null) {
            return GuardrailResult.pass();
        }
        var length = accumulatedResponse.length();
        var tenant = resolveTenant();
        var window = windowsByTenant.computeIfAbsent(tenant, k -> new ArrayDeque<>());

        synchronized (window) {
            Sample own = null;
            var sum = 0L;
            var sumSq = 0.0;
            var n = 0;
            for (var sample : window) {
                if (own == null && sample.isOf(reply)) {
                    own = sample;
                    continue;
                }
                sum += sample.length;
                sumSq += (double) sample.length * sample.length;
                n++;
            }

            if (own != null) {
                own.length = Math.max(own.length, length);
            } else {
                window.addLast(new Sample(reply, length));
                while (window.size() > windowSize) {
                    window.removeFirst();
                }
            }

            if (n < minSamples) {
                return GuardrailResult.pass();
            }
            var mean = sum / (double) n;
            var variance = (sumSq / n) - (mean * mean);
            var stddev = variance > 0 ? Math.sqrt(variance) : 1.0;
            // A streamed reply is checked before it is complete, so its length
            // is a lower bound: only "already too long" can be judged.
            var z = reply != null ? (length - mean) / stddev : Math.abs((length - mean) / stddev);

            if (z > zScoreThreshold) {
                logger.warn("Output length {} chars is {} sigma from rolling mean {} "
                                + "(tenant={}) — possible drift",
                        length, String.format("%.2f", z), String.format("%.1f", mean), tenant);
                return GuardrailResult.block(
                        "response length z-score " + String.format("%.2f", z)
                                + " exceeds threshold " + zScoreThreshold
                                + " (tenant=" + tenant + ", possible drift)");
            }
            return GuardrailResult.pass();
        }
    }

    /**
     * Resolve the tenant id from SLF4J MDC. {@code AiEndpointHandler}
     * snapshots {@code business.*} request attributes onto the VT's MDC
     * at turn start, so the tag is live by the time the guardrail
     * inspects the response.
     */
    private static String resolveTenant() {
        var raw = org.slf4j.MDC.get(TENANT_MDC_KEY);
        return raw != null && !raw.isBlank() ? raw : DEFAULT_BUCKET;
    }

    /** Package-private — tests read the snapshot for assertions. */
    int currentSamples() {
        return currentSamples(DEFAULT_BUCKET);
    }

    /** Package-private — tenant-scoped snapshot for parity tests. */
    int currentSamples(String tenant) {
        var window = windowsByTenant.get(tenant);
        if (window == null) return 0;
        synchronized (window) {
            return window.size();
        }
    }
}
