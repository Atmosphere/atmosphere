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
package org.atmosphere.coordinator.fleet;

import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceFleetInterceptorTest {

    private record AdmitPolicy(String n) implements GovernancePolicy {
        @Override public String name() { return n; }
        @Override public String source() { return "test"; }
        @Override public String version() { return "1"; }
        @Override public PolicyDecision evaluate(PolicyContext c) { return PolicyDecision.admit(); }
    }

    private record DenyPolicy(String n, String reason) implements GovernancePolicy {
        @Override public String name() { return n; }
        @Override public String source() { return "test"; }
        @Override public String version() { return "1"; }
        @Override public PolicyDecision evaluate(PolicyContext c) {
            return PolicyDecision.deny(reason);
        }
    }

    private static AgentCall call(String skill, Map<String, Object> args) {
        return new AgentCall("research", skill, args);
    }

    @Test
    void emptyPolicyListAlwaysProceeds() {
        var interceptor = new GovernanceFleetInterceptor(List.of());
        assertInstanceOf(FleetInterceptor.Decision.Proceed.class,
                interceptor.before(call("web_search", Map.of("q", "hi"))));
    }

    @Test
    void allAdmitPoliciesProceed() {
        var interceptor = new GovernanceFleetInterceptor(List.of(
                new AdmitPolicy("p1"), new AdmitPolicy("p2")));
        assertInstanceOf(FleetInterceptor.Decision.Proceed.class,
                interceptor.before(call("web_search", Map.of("q", "hi"))));
    }

    @Test
    void anyDenyShortCircuitsChain() {
        var interceptor = new GovernanceFleetInterceptor(List.of(
                new AdmitPolicy("first"),
                new DenyPolicy("scope", "write_code is off-scope for research agent"),
                new AdmitPolicy("never")));
        var decision = interceptor.before(call("write_code", Map.of("lang", "python")));
        var deny = assertInstanceOf(FleetInterceptor.Decision.Deny.class, decision);
        assertTrue(deny.reason().contains("off-scope"));
    }

    @Test
    void policyExceptionTreatedAsFailClosedDeny() {
        var throwing = new GovernancePolicy() {
            @Override public String name() { return "broken"; }
            @Override public String source() { return "test"; }
            @Override public String version() { return "1"; }
            @Override public PolicyDecision evaluate(PolicyContext c) {
                throw new IllegalStateException("kaboom");
            }
        };
        var interceptor = new GovernanceFleetInterceptor(List.of(throwing));
        var deny = assertInstanceOf(FleetInterceptor.Decision.Deny.class,
                interceptor.before(call("web_search", Map.of())));
        assertTrue(deny.reason().contains("broken"));
    }

    @Test
    void metadataStampedForPolicyVisibility() {
        // A policy that inspects the metadata should see the dispatch tags.
        var capturing = new GovernancePolicy() {
            java.util.Map<String, Object> seen;
            @Override public String name() { return "cap"; }
            @Override public String source() { return "test"; }
            @Override public String version() { return "1"; }
            @Override public PolicyDecision evaluate(PolicyContext c) {
                seen = c.request().metadata();
                return PolicyDecision.admit();
            }
        };
        new GovernanceFleetInterceptor(List.of(capturing))
                .before(call("web_search", Map.of("q", "hi")));
        assertEquals("research", capturing.seen.get("fleet.dispatch.agent"));
        assertEquals("web_search", capturing.seen.get("fleet.dispatch.skill"));
    }

    @Test
    void proceedWhenAllAdmitEvenWithManyPolicies() {
        var policies = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> (GovernancePolicy) new AdmitPolicy("p" + i))
                .toList();
        var interceptor = new GovernanceFleetInterceptor(policies);
        assertInstanceOf(FleetInterceptor.Decision.Proceed.class,
                interceptor.before(call("web_search", Map.of())));
    }
}
