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

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Operator-armed deny-all switch. When {@link #arm(String)} is called, every
 * subsequent {@link #evaluate(PolicyContext)} returns {@link PolicyDecision.Deny}
 * with the operator-supplied reason. {@link #disarm()} restores normal traffic
 * without a redeploy.
 *
 * <p>Distinct from {@link GovernancePolicy} implementations that make
 * <i>per-request</i> decisions — this is the break-glass "stop ALL AI traffic
 * right now" switch expected on any governance plane. Wire it into the
 * {@code PolicyRegistry} at startup, then flip via an authenticated admin
 * endpoint on incident.</p>
 *
 * <p>The armed state is a single volatile reference — readers pay one atomic
 * read per request, writers pay one CAS. No lock contention on the hot path.</p>
 */
public final class KillSwitchPolicy implements GovernancePolicy {

    /** Conventional name for the single installed kill switch — admin surfaces key off this. */
    public static final String DEFAULT_NAME = "kill-switch";

    private final String name;
    private final String source;
    private final String version;
    private final AtomicReference<ArmedState> armed = new AtomicReference<>(null);

    /**
     * Snapshot of the armed state — captured at arming time so the audit trail
     * can report who/when/why the switch was flipped. Exposed via
     * {@link #armedState()} for admin surfaces.
     */
    public record ArmedState(String reason, String operator, Instant armedAt) {
        public ArmedState {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be null or blank");
            }
            if (operator == null || operator.isBlank()) {
                operator = "unknown";
            }
            if (armedAt == null) {
                armedAt = Instant.now();
            }
        }
    }

    public KillSwitchPolicy() {
        this(DEFAULT_NAME, "code:" + KillSwitchPolicy.class.getName(), "1");
    }

    public KillSwitchPolicy(String name, String source, String version) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be null or blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be null or blank");
        }
        this.name = name;
        this.source = source;
        this.version = version;
    }

    @Override public String name() { return name; }
    @Override public String source() { return source; }
    @Override public String version() { return version; }

    /**
     * Arm the switch with an operator-supplied reason. Subsequent evaluations
     * deny with that reason until {@link #disarm()}. Idempotent — re-arming
     * overwrites the reason and resets the armedAt timestamp so the admin
     * surface reflects the latest flip.
     */
    public void arm(String reason) {
        arm(reason, "unknown");
    }

    /** Arm the switch with a reason and an operator identifier (e.g. admin principal name). */
    public void arm(String reason, String operator) {
        armed.set(new ArmedState(reason, operator, Instant.now()));
    }

    /** Disarm the switch. Idempotent — no-op when already disarmed. */
    public void disarm() {
        armed.set(null);
    }

    /** True when the switch is currently denying traffic. */
    public boolean isArmed() {
        return armed.get() != null;
    }

    /** Current armed state, or {@code null} when disarmed. For admin introspection. */
    public ArmedState armedState() {
        return armed.get();
    }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        var state = armed.get();
        if (state == null) {
            return PolicyDecision.admit();
        }
        return PolicyDecision.deny(state.reason());
    }
}
