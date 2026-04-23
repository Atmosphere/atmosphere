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

import java.util.concurrent.atomic.AtomicReference;

/**
 * Hot-reload seam: wraps a {@link GovernancePolicy} behind a volatile
 * reference that operators can atomically replace without pausing traffic.
 * The reference is swapped with {@link #replace(GovernancePolicy)}; every
 * subsequent evaluation uses the new delegate while any in-flight
 * evaluation against the old one finishes cleanly.
 *
 * <h2>Identity binding</h2>
 * Identity fields ({@link #name()}, {@link #source()}, {@link #version()})
 * come from the SwappablePolicy itself at construction time — they do NOT
 * track the delegate's identity. This is intentional: the audit trail and
 * metrics keep a stable label across reloads, while the delegate's own
 * {@code version} reflects the content that's actually enforcing.
 * {@link #delegateVersion()} exposes the current delegate's version when
 * operators need that directly.
 *
 * <h2>Thread safety</h2>
 * {@link AtomicReference} ensures publication visibility. No lock on the
 * evaluate path — one volatile read per request.
 *
 * <h2>Relationship with {@link DryRunPolicy}</h2>
 * Swappable is about "change the enforcement"; dry-run is about "preview
 * enforcement without applying". Operators typically combine them:
 * {@code DryRunPolicy wraps SwappablePolicy} for a safe rollout where
 * the new policy can be reloaded and observed without blocking traffic.
 */
public final class SwappablePolicy implements GovernancePolicy {

    private final String name;
    private final String source;
    private final String version;
    private final AtomicReference<GovernancePolicy> delegate;

    public SwappablePolicy(String name, GovernancePolicy initial) {
        this(name, "code:" + SwappablePolicy.class.getName(), "1", initial);
    }

    public SwappablePolicy(String name, String source, String version, GovernancePolicy initial) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (initial == null) {
            throw new IllegalArgumentException("initial delegate must not be null");
        }
        this.name = name;
        this.source = source;
        this.version = version;
        this.delegate = new AtomicReference<>(initial);
    }

    @Override public String name() { return name; }
    @Override public String source() { return source; }
    @Override public String version() { return version; }

    /**
     * Atomically replace the delegate. Returns the previous delegate so
     * audit / metrics code can capture the identity of the outgoing
     * policy (useful for the "policy swapped at T=X from version A to B"
     * admin log entry).
     */
    public GovernancePolicy replace(GovernancePolicy next) {
        if (next == null) {
            throw new IllegalArgumentException("replacement delegate must not be null");
        }
        if (next instanceof SwappablePolicy) {
            throw new IllegalArgumentException(
                    "nesting SwappablePolicy inside SwappablePolicy is almost always a bug");
        }
        return delegate.getAndSet(next);
    }

    /** Current delegate — exposed so admin surfaces can introspect its identity. */
    public GovernancePolicy delegate() {
        return delegate.get();
    }

    /** Convenience — delegate's {@link #version()} ({@code null} when disarmed). */
    public String delegateVersion() {
        var d = delegate.get();
        return d == null ? null : d.version();
    }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        return delegate.get().evaluate(context);
    }
}
