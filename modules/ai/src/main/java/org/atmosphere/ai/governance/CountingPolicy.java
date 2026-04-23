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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorator that tallies the delegate's decisions into in-memory counters.
 * Complementary to {@link TimedPolicy} — that one emits to
 * {@link GovernanceMetrics}; this one keeps counters readable without a
 * metrics backend.
 *
 * <p>Use cases where the metrics facade isn't wired:</p>
 * <ul>
 *   <li>Integration tests that assert "policy denied N times"</li>
 *   <li>Smoke-environment deployments without a Prometheus scrape target</li>
 *   <li>Scripts that introspect policy counters over JMX / JSON admin</li>
 * </ul>
 *
 * <p>Counters are {@link AtomicLong} — contention-free on the hot path for
 * up to a handful of concurrent evaluators; higher contention is fine too
 * (each decision type hits a different counter).</p>
 *
 * <p>Errors in the delegate are counted under {@link #errors()} and
 * re-thrown unchanged — the wrapper never swallows, matching {@code
 * TimedPolicy}'s contract.</p>
 */
public final class CountingPolicy implements GovernancePolicy {

    private final GovernancePolicy delegate;
    private final AtomicLong admits = new AtomicLong();
    private final AtomicLong denies = new AtomicLong();
    private final AtomicLong transforms = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    public CountingPolicy(GovernancePolicy delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
    }

    public static CountingPolicy of(GovernancePolicy policy) {
        if (policy instanceof CountingPolicy c) return c;
        return new CountingPolicy(policy);
    }

    @Override public String name() { return delegate.name(); }
    @Override public String source() { return delegate.source(); }
    @Override public String version() { return delegate.version(); }

    public GovernancePolicy delegate() { return delegate; }

    public long admits() { return admits.get(); }
    public long denies() { return denies.get(); }
    public long transforms() { return transforms.get(); }
    public long errors() { return errors.get(); }

    /** Total evaluations — admits + denies + transforms + errors. */
    public long total() {
        return admits.get() + denies.get() + transforms.get() + errors.get();
    }

    public void reset() {
        admits.set(0);
        denies.set(0);
        transforms.set(0);
        errors.set(0);
    }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        try {
            var decision = delegate.evaluate(context);
            switch (decision) {
                case PolicyDecision.Admit ignored -> admits.incrementAndGet();
                case PolicyDecision.Deny ignored -> denies.incrementAndGet();
                case PolicyDecision.Transform ignored -> transforms.incrementAndGet();
            }
            return decision;
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            throw e;
        }
    }
}
