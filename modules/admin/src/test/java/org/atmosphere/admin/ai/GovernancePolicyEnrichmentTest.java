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
import org.atmosphere.ai.governance.DenyListPolicy;
import org.atmosphere.ai.governance.DryRunPolicy;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.KillSwitchPolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.SwappablePolicy;
import org.atmosphere.ai.governance.TimedPolicy;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernancePolicyEnrichmentTest {

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

    @Test
    void killSwitchEntryCarriesArmedFlag() {
        var ks = new KillSwitchPolicy();
        ks.arm("incident-7", "oncall");
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(ks));

        var list = new GovernanceController(framework).listPolicies();
        assertEquals(1, list.size());
        var entry = list.get(0);
        assertEquals(true, entry.get("armed"));
        assertEquals("incident-7", entry.get("armedReason"));
    }

    @Test
    void dryRunEntryExposesShadowCounters() {
        var deny = new DenyListPolicy("inner", "x");
        var dr = new DryRunPolicy(deny);
        dr.evaluate(new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest("x", null, null, null, null, null, null, null, null), ""));
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(dr));

        var entry = new GovernanceController(framework).listPolicies().get(0);
        assertEquals(true, entry.get("dryRun"));
        assertEquals("inner", entry.get("wrappedPolicyName"));
        assertEquals(1L, entry.get("shadowDenies"));
    }

    @Test
    void swappableEntryExposesDelegateVersion() {
        GovernancePolicy versioned = new GovernancePolicy() {
            @Override public String name() { return "inner-v1"; }
            @Override public String source() { return "test"; }
            @Override public String version() { return "v1"; }
            @Override public org.atmosphere.ai.governance.PolicyDecision evaluate(
                    PolicyContext c) {
                return org.atmosphere.ai.governance.PolicyDecision.admit();
            }
        };
        var swap = new SwappablePolicy("reloadable", versioned);
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(swap));

        var entry = new GovernanceController(framework).listPolicies().get(0);
        assertEquals(true, entry.get("swappable"));
        assertEquals("v1", entry.get("delegateVersion"));
    }

    @Test
    void timedWrapperFlagsAndUnwrapsForDelegateClassName() {
        var deny = new DenyListPolicy("inner", "x");
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(TimedPolicy.of(deny)));

        var entry = new GovernanceController(framework).listPolicies().get(0);
        assertEquals(true, entry.get("timed"));
        assertEquals(DenyListPolicy.class.getName(), entry.get("delegateClassName"));
    }

    @Test
    void everyEntryCarriesHashDigest() {
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(
                new DenyListPolicy("a", "x"),
                new DenyListPolicy("b", "y")));

        var list = new GovernanceController(framework).listPolicies();
        for (var entry : list) {
            var digest = (String) entry.get("digest");
            assertTrue(digest.startsWith("sha256:"),
                    "every policy entry must include a sha256 digest");
        }
    }
}
