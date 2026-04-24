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
package org.atmosphere.ai.governance;

/**
 * Wrapper that records per-policy {@code evaluate()} latency into
 * {@link GovernanceMetricsHolder#get()}. Preserves the delegate's identity,
 * decision, and exception semantics — the only observable side effect is
 * that {@link GovernanceMetrics#recordEvaluationLatency(String, String, double)}
 * fires on every evaluation.
 *
 * <p>Why a wrapper rather than timing inside {@link org.atmosphere.ai.AiPipeline}:
 * policies land on non-pipeline paths too ({@link PolicyAdmissionGate},
 * direct {@code PolicyRegistry.evaluate} callers, composite structures
 * like {@link PolicyRing}). Wrapping at policy-construction time gets
 * uniform latency metrics regardless of who invokes {@code evaluate}.</p>
 *
 * <p>Error propagation: when the delegate throws, latency is still recorded
 * under {@code decision=error} and the exception re-throws unchanged — the
 * wrapper never swallows. Matches the fail-closed contract of the policy SPI.</p>
 */
public final class TimedPolicy implements GovernancePolicy {

    private final GovernancePolicy delegate;

    public TimedPolicy(GovernancePolicy delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
    }

    /** Convenience — wrap only if not already timed. Idempotent. */
    public static GovernancePolicy of(GovernancePolicy policy) {
        if (policy instanceof TimedPolicy) return policy;
        return new TimedPolicy(policy);
    }

    @Override public String name() { return delegate.name(); }
    @Override public String source() { return delegate.source(); }
    @Override public String version() { return delegate.version(); }

    public GovernancePolicy delegate() { return delegate; }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        var metrics = GovernanceMetricsHolder.get();
        var start = System.nanoTime();
        try {
            var decision = delegate.evaluate(context);
            var elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
            metrics.recordEvaluationLatency(delegate.name(), decisionLabel(decision), elapsedMs);
            return decision;
        } catch (RuntimeException e) {
            var elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
            metrics.recordEvaluationLatency(delegate.name(), "error", elapsedMs);
            throw e;
        }
    }

    private static String decisionLabel(PolicyDecision decision) {
        return switch (decision) {
            case PolicyDecision.Admit ignored -> "admit";
            case PolicyDecision.Deny ignored -> "deny";
            case PolicyDecision.Transform ignored -> "transform";
        };
    }
}
