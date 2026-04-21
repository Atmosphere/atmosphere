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
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolicyAdmissionGateTest {

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
    void admitsWhenNoPoliciesInstalled() {
        var result = PolicyAdmissionGate.admit(framework, new AiRequest("hi"));
        var admitted = assertInstanceOf(PolicyAdmissionGate.Result.Admitted.class, result);
        assertEquals("hi", admitted.request().message());
    }

    @Test
    void admitsWhenFrameworkIsNull() {
        var result = PolicyAdmissionGate.admit((AtmosphereFramework) null, new AiRequest("hi"));
        assertInstanceOf(PolicyAdmissionGate.Result.Admitted.class, result);
    }

    @Test
    void transformsThroughPolicyChain() {
        var rewritten = new AiRequest("[redacted]");
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(
                new FixedPolicy("redactor", PolicyDecision.transform(rewritten))));
        var result = PolicyAdmissionGate.admit(framework, new AiRequest("leak@x.com"));
        var admitted = assertInstanceOf(PolicyAdmissionGate.Result.Admitted.class, result);
        assertEquals("[redacted]", admitted.request().message());
    }

    @Test
    void denialHaltsTheChainAndReportsPolicyIdentity() {
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(
                new FixedPolicy("strict-pii", PolicyDecision.deny("PII present"))));
        var result = PolicyAdmissionGate.admit(framework, new AiRequest("leak@x.com"));
        var denied = assertInstanceOf(PolicyAdmissionGate.Result.Denied.class, result);
        assertEquals("strict-pii", denied.policyName());
        assertEquals("PII present", denied.reason());
    }

    @Test
    void exceptionFromPolicyIsFailClosed() {
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(
                new ThrowingPolicy("boom")));
        var result = PolicyAdmissionGate.admit(framework, new AiRequest("hi"));
        var denied = assertInstanceOf(PolicyAdmissionGate.Result.Denied.class, result);
        assertEquals("boom", denied.policyName());
    }

    @Test
    void nonPolicyEntriesInPropertyAreSkipped() {
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of(
                "not a policy",
                new FixedPolicy("real", PolicyDecision.admit())));
        var result = PolicyAdmissionGate.admit(framework, new AiRequest("hi"));
        assertInstanceOf(PolicyAdmissionGate.Result.Admitted.class, result);
    }

    @Test
    void emptyPoliciesListIsAdmit() {
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.of());
        var result = PolicyAdmissionGate.admit(framework, new AiRequest("hi"));
        assertInstanceOf(PolicyAdmissionGate.Result.Admitted.class, result);
    }

    private record FixedPolicy(String name, PolicyDecision decision) implements GovernancePolicy {
        @Override public String source() { return "code:test"; }
        @Override public String version() { return "test"; }
        @Override public PolicyDecision evaluate(PolicyContext ctx) { return decision; }
    }

    private record ThrowingPolicy(String name) implements GovernancePolicy {
        @Override public String source() { return "code:test"; }
        @Override public String version() { return "test"; }
        @Override public PolicyDecision evaluate(PolicyContext ctx) {
            throw new RuntimeException("boom");
        }
    }
}
