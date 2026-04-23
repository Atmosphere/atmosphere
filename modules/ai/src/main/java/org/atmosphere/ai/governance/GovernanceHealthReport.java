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

import org.atmosphere.ai.governance.sre.SloTracker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Operator-facing health aggregation. Rolls up the state of the
 * governance plane into a single immutable snapshot suitable for an
 * admin dashboard, a health-check endpoint, or a chat-ops report.
 *
 * <p>Pulls from primitives that already exist in this package:</p>
 * <ul>
 *   <li>{@link KillSwitchPolicy} armed state + reason</li>
 *   <li>{@link DryRunPolicy} shadow counters</li>
 *   <li>{@link SloTracker} burn rate + error budget</li>
 *   <li>{@link PolicyHashDigest} over each configured policy</li>
 * </ul>
 *
 * <p>Not a policy itself. Not a metrics emitter either — this is an
 * <i>aggregator</i> that returns a static snapshot at call time. Admin
 * endpoints marshal the record straight to JSON; the record's fields use
 * plain types (String, double, long) so no serialization dance is needed.</p>
 */
public record GovernanceHealthReport(
        Instant generatedAt,
        KillSwitchStatus killSwitch,
        List<PolicyFingerprint> policies,
        List<DryRunStatus> dryRuns,
        List<SloStatus> slos) {

    public GovernanceHealthReport {
        if (generatedAt == null) {
            generatedAt = Instant.now();
        }
        policies = policies == null ? List.of() : List.copyOf(policies);
        dryRuns = dryRuns == null ? List.of() : List.copyOf(dryRuns);
        slos = slos == null ? List.of() : List.copyOf(slos);
    }

    /** {@code null} killSwitch means "no kill switch installed in this plane". */
    public record KillSwitchStatus(boolean armed, String reason, String operator, Instant armedAt) {

        public static KillSwitchStatus from(KillSwitchPolicy policy) {
            if (policy == null) {
                return new KillSwitchStatus(false, null, null, null);
            }
            var state = policy.armedState();
            if (state == null) {
                return new KillSwitchStatus(false, null, null, null);
            }
            return new KillSwitchStatus(true, state.reason(), state.operator(), state.armedAt());
        }
    }

    public record PolicyFingerprint(String name, String source, String version, String digest) { }

    public record DryRunStatus(String wrappedPolicyName,
                               long admits, long denies, long transforms, long errors) {

        public static DryRunStatus from(DryRunPolicy wrapper) {
            return new DryRunStatus(
                    wrapper.delegate().name(),
                    wrapper.shadowAdmits(),
                    wrapper.shadowDenies(),
                    wrapper.shadowTransforms(),
                    wrapper.delegateErrors());
        }
    }

    public record SloStatus(String name,
                            double target,
                            double successRatio,
                            double errorBudgetRemaining,
                            double burnRate,
                            boolean breached) {

        public static SloStatus from(SloTracker tracker) {
            var slo = tracker.slo();
            return new SloStatus(
                    slo.name(),
                    slo.target(),
                    tracker.successRatio(),
                    tracker.errorBudgetRemaining(),
                    tracker.burnRate(),
                    tracker.isBreached());
        }
    }

    /**
     * Build a report from the running set of governance primitives. Only
     * non-null inputs contribute; any input may be {@code null} when the
     * deployment doesn't use that primitive.
     *
     * <p>{@code policies} maps each registered {@link GovernancePolicy} to
     * its boot-time YAML bytes (or {@code null} for code-defined policies).
     * The boot bytes are what gets hashed — pairing the hash with the
     * policy's runtime identity answers the drift question cleanly.</p>
     */
    public static GovernanceHealthReport build(
            KillSwitchPolicy killSwitch,
            Map<GovernancePolicy, byte[]> policies,
            Collection<DryRunPolicy> dryRuns,
            Collection<SloTracker> slos) {

        var fingerprints = new ArrayList<PolicyFingerprint>();
        if (policies != null) {
            policies.forEach((policy, artifact) -> {
                var digest = artifact == null
                        ? PolicyHashDigest.forIdentity(policy)
                        : PolicyHashDigest.forPolicy(policy, artifact);
                fingerprints.add(new PolicyFingerprint(
                        policy.name(), policy.source(), policy.version(), digest));
            });
        }

        var dryRunStatuses = new ArrayList<DryRunStatus>();
        if (dryRuns != null) {
            for (var wrapper : dryRuns) {
                if (wrapper != null) {
                    dryRunStatuses.add(DryRunStatus.from(wrapper));
                }
            }
        }

        var sloStatuses = new ArrayList<SloStatus>();
        if (slos != null) {
            for (var tracker : slos) {
                if (tracker != null) {
                    sloStatuses.add(SloStatus.from(tracker));
                }
            }
        }

        return new GovernanceHealthReport(
                Instant.now(),
                KillSwitchStatus.from(killSwitch),
                fingerprints,
                dryRunStatuses,
                sloStatuses);
    }
}
