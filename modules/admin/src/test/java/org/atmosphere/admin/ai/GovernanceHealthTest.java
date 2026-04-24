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
package org.atmosphere.admin.ai;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.governance.DryRunPolicy;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.KillSwitchPolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.governance.PolicyHashDigest;
import org.atmosphere.ai.governance.TimedPolicy;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernanceHealthTest {

    private AtmosphereFramework framework;
    private Map<String, Object> properties;

    @BeforeEach
    void setUp() {
        framework = mock(AtmosphereFramework.class);
        var config = mock(AtmosphereConfig.class);
        properties = new HashMap<>();
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(config.properties()).thenReturn(properties);
    }

    private record FixedPolicy(String name, String source, String version) implements GovernancePolicy {
        @Override public PolicyDecision evaluate(PolicyContext c) { return PolicyDecision.admit(); }
    }

    private static PolicyContext ctx() {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest("x", null, null, null, null, null, null, null, null),
                "");
    }

    @Test
    void healthReportsNoKillSwitchWhenNonePresent() {
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(
                new FixedPolicy("a", "yaml:x", "1")));
        var controller = new GovernanceController(framework);
        var report = controller.health();
        assertFalse(report.killSwitch().armed());
    }

    @Test
    void healthFindsKillSwitchThroughTimedWrapper() {
        var killSwitch = new KillSwitchPolicy();
        killSwitch.arm("maintenance", "admin");
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(
                TimedPolicy.of(killSwitch),
                new FixedPolicy("scope", "yaml:x", "1")));

        var report = new GovernanceController(framework).health();
        assertTrue(report.killSwitch().armed());
        assertEquals("maintenance", report.killSwitch().reason());
        assertEquals("admin", report.killSwitch().operator());
    }

    @Test
    void healthReportsDryRunCountsThroughTimedWrapper() {
        GovernancePolicy denying = new GovernancePolicy() {
            @Override public String name() { return "scope"; }
            @Override public String source() { return "yaml:x"; }
            @Override public String version() { return "1"; }
            @Override public PolicyDecision evaluate(PolicyContext c) {
                return PolicyDecision.deny("off-topic");
            }
        };
        var dryRun = new DryRunPolicy(denying);
        dryRun.evaluate(ctx());
        dryRun.evaluate(ctx());

        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(TimedPolicy.of(dryRun)));

        var report = new GovernanceController(framework).health();
        assertEquals(1, report.dryRuns().size());
        assertEquals("scope", report.dryRuns().get(0).wrappedPolicyName());
        assertEquals(2, report.dryRuns().get(0).denies());
    }

    @Test
    void healthFingerprintsEveryPolicyByUnwrappedIdentity() {
        var plain = new FixedPolicy("plain", "yaml:p.yaml", "1");
        var wrapped = new FixedPolicy("wrapped", "yaml:w.yaml", "2");
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(
                plain,
                TimedPolicy.of(wrapped)));

        var report = new GovernanceController(framework).health();
        assertEquals(2, report.policies().size());

        // Both fingerprints must be computed from the unwrapped identity,
        // otherwise the Timed wrapper would leak into the hash.
        assertEquals(PolicyHashDigest.forIdentity(plain), report.policies().get(0).digest());
        assertEquals(PolicyHashDigest.forIdentity(wrapped), report.policies().get(1).digest());
    }

    @Test
    void healthMapHasStableShape() {
        var killSwitch = new KillSwitchPolicy();
        killSwitch.arm("drill");
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(
                TimedPolicy.of(killSwitch),
                new FixedPolicy("scope", "yaml:x", "1")));

        var map = new GovernanceController(framework).healthMap();
        assertNotNull(map.get("generatedAt"));
        var ks = (Map<?, ?>) map.get("killSwitch");
        assertEquals(true, ks.get("armed"));
        assertEquals("drill", ks.get("reason"));

        var policies = (List<?>) map.get("policies");
        assertEquals(2, policies.size());

        assertTrue(((List<?>) map.get("dryRuns")).isEmpty());
        assertTrue(((List<?>) map.get("slos")).isEmpty());
    }

    @Test
    void healthMapOmitsKillSwitchFieldsWhenDisarmed() {
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(new KillSwitchPolicy()));
        var map = new GovernanceController(framework).healthMap();
        var ks = (Map<?, ?>) map.get("killSwitch");
        assertEquals(false, ks.get("armed"));
        assertFalse(ks.containsKey("reason"),
                "disarmed kill switch must NOT publish a stale reason field");
    }
}
