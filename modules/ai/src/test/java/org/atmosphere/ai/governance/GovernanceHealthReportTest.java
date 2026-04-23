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

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.governance.sre.ServiceLevelObjective;
import org.atmosphere.ai.governance.sre.SloTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceHealthReportTest {

    private record FixedPolicy(String n, String s, String v) implements GovernancePolicy {
        @Override public String name() { return n; }
        @Override public String source() { return s; }
        @Override public String version() { return v; }
        @Override public PolicyDecision evaluate(PolicyContext c) { return PolicyDecision.admit(); }
    }

    private static PolicyContext ctx() {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest("x", null, null, null, null, null, null, null, null),
                "");
    }

    @BeforeEach
    void installLog() {
        GovernanceDecisionLog.install(25);
    }

    @AfterEach
    void resetLog() {
        GovernanceDecisionLog.reset();
    }

    @Test
    void emptyReportHasNoKillSwitchArmedAndEmptyLists() {
        var report = GovernanceHealthReport.build(null, Map.of(), List.of(), List.of());
        assertNotNull(report.generatedAt());
        assertFalse(report.killSwitch().armed());
        assertNull(report.killSwitch().reason());
        assertTrue(report.policies().isEmpty());
        assertTrue(report.dryRuns().isEmpty());
        assertTrue(report.slos().isEmpty());
    }

    @Test
    void killSwitchArmedStateIsCaptured() {
        var sw = new KillSwitchPolicy();
        sw.arm("incident-42", "sre-oncall");
        var report = GovernanceHealthReport.build(sw, Map.of(), List.of(), List.of());
        assertTrue(report.killSwitch().armed());
        assertEquals("incident-42", report.killSwitch().reason());
        assertEquals("sre-oncall", report.killSwitch().operator());
        assertNotNull(report.killSwitch().armedAt());
    }

    @Test
    void killSwitchDisarmedStateShowsUnarmed() {
        var sw = new KillSwitchPolicy();
        var report = GovernanceHealthReport.build(sw, Map.of(), List.of(), List.of());
        assertFalse(report.killSwitch().armed());
    }

    @Test
    void policyFingerprintsCarryHashDigest() {
        var a = new FixedPolicy("a", "yaml:/a.yaml", "1");
        var b = new FixedPolicy("b", "code:test", "2");
        // LinkedHashMap preserves iteration order for deterministic assertions.
        var policies = new LinkedHashMap<GovernancePolicy, byte[]>();
        policies.put(a, "artifact-a".getBytes());
        policies.put(b, null);

        var report = GovernanceHealthReport.build(null, policies, List.of(), List.of());
        assertEquals(2, report.policies().size());

        var fpA = report.policies().get(0);
        assertEquals("a", fpA.name());
        assertTrue(fpA.digest().startsWith(PolicyHashDigest.ALGO_PREFIX));
        assertEquals(PolicyHashDigest.forPolicy(a, "artifact-a"), fpA.digest());

        var fpB = report.policies().get(1);
        assertEquals(PolicyHashDigest.forIdentity(b), fpB.digest(),
                "null artifact → identity-only digest");
    }

    @Test
    void dryRunCountersRollUp() {
        var deny = new FixedPolicy("scope.support", "test", "1");
        GovernancePolicy denyingDelegate = new GovernancePolicy() {
            @Override public String name() { return "p"; }
            @Override public String source() { return "test"; }
            @Override public String version() { return "1"; }
            @Override public PolicyDecision evaluate(PolicyContext c) {
                return PolicyDecision.deny("off-topic");
            }
        };
        var wrapped = new DryRunPolicy(denyingDelegate);
        wrapped.evaluate(ctx());
        wrapped.evaluate(ctx());
        wrapped.evaluate(ctx());

        var policies = new LinkedHashMap<GovernancePolicy, byte[]>();
        policies.put(deny, null);
        var report = GovernanceHealthReport.build(null, policies,
                List.of(wrapped), List.of());
        assertEquals(1, report.dryRuns().size());
        var dr = report.dryRuns().get(0);
        assertEquals("p", dr.wrappedPolicyName());
        assertEquals(3, dr.denies());
        assertEquals(0, dr.admits());
    }

    @Test
    void sloStatusesRollUp() {
        var slo1 = ServiceLevelObjective.threeNines("admission-admit");
        var tracker = new SloTracker(slo1);
        tracker.recordSuccess();
        tracker.recordSuccess();
        tracker.recordFailure();

        var report = GovernanceHealthReport.build(null, Map.of(), List.of(), List.of(tracker));
        assertEquals(1, report.slos().size());
        var s = report.slos().get(0);
        assertEquals("admission-admit", s.name());
        assertEquals(slo1.target(), s.target(), 1e-9);
        assertTrue(s.successRatio() > 0.6 && s.successRatio() < 0.8);
    }

    @Test
    void reportWithoutGeneratedAtDefaultsToNow() {
        var before = java.time.Instant.now();
        var report = new GovernanceHealthReport(null, null, null, null, null);
        var after = java.time.Instant.now();
        assertTrue(!report.generatedAt().isBefore(before));
        assertTrue(!report.generatedAt().isAfter(after));
    }

    @Test
    void nullCollectionsCoerceToEmpty() {
        var report = new GovernanceHealthReport(null, null, null, null, null);
        assertTrue(report.policies().isEmpty());
        assertTrue(report.dryRuns().isEmpty());
        assertTrue(report.slos().isEmpty());
    }
}
