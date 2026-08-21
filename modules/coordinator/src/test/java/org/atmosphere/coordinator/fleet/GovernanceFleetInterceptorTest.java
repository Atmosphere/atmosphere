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

    /**
     * Regression: the Transform arm used to rebuild an identical
     * AgentCall without ever reading {@code modifiedRequest()}, then
     * report {@code Decision.rewrite} — a PII-redaction policy showed
     * success in the audit trail while the original arguments passed
     * through untouched. The rewrite must land on the actual dispatched
     * arg values.
     */
    @Test
    void transformAppliesRedactionToTheActualDispatchedArgs() {
        var redacting = new GovernancePolicy() {
            @Override public String name() { return "pii"; }
            @Override public String source() { return "test"; }
            @Override public String version() { return "1"; }
            @Override public PolicyDecision evaluate(PolicyContext c) {
                var msg = c.request().message();
                if (msg != null && msg.contains("alice@example.com")) {
                    return PolicyDecision.transform(c.request()
                            .withMessage(msg.replace("alice@example.com", "[redacted]")));
                }
                return PolicyDecision.admit();
            }
        };
        var interceptor = new GovernanceFleetInterceptor(List.of(redacting));
        var decision = interceptor.before(call("web_search",
                Map.of("q", "contact alice@example.com about the outage", "limit", 3)));

        var rewrite = assertInstanceOf(FleetInterceptor.Decision.Rewrite.class, decision);
        assertEquals("contact [redacted] about the outage",
                rewrite.modifiedCall().args().get("q"),
                "the redaction must reach the arg value the agent receives — "
                + "reporting a rewrite while dispatching the original is worse "
                + "than failing outright");
        assertEquals(3, rewrite.modifiedCall().args().get("limit"),
                "non-String args pass through untouched");
    }

    /**
     * A transform that fires on the summarized call but matches no
     * String arg cannot be applied — the dispatch must proceed as an
     * honest {@code Proceed}, not report a rewrite that never happened.
     */
    @Test
    void transformThatCannotLandOnArgsProceedsUnchanged() {
        var summaryOnly = new GovernancePolicy() {
            @Override public String name() { return "summary-only"; }
            @Override public String source() { return "test"; }
            @Override public String version() { return "1"; }
            @Override public PolicyDecision evaluate(PolicyContext c) {
                var msg = c.request().message();
                // Matches the "skill args" summary, never a bare arg value.
                if (msg != null && msg.startsWith("web_search {")) {
                    return PolicyDecision.transform(c.request().withMessage("rewritten"));
                }
                return PolicyDecision.admit();
            }
        };
        var interceptor = new GovernanceFleetInterceptor(List.of(summaryOnly));
        assertInstanceOf(FleetInterceptor.Decision.Proceed.class,
                interceptor.before(call("web_search", Map.of("q", "hi"))),
                "an unapplied transform must not be reported as a rewrite");
    }

    /** A per-arg Deny during the transform pass fails the dispatch closed. */
    @Test
    void perArgDenyDuringTransformFailsClosed() {
        var strictPerArg = new GovernancePolicy() {
            @Override public String name() { return "strict"; }
            @Override public String source() { return "test"; }
            @Override public String version() { return "1"; }
            @Override public PolicyDecision evaluate(PolicyContext c) {
                var msg = c.request().message();
                if (msg != null && msg.startsWith("web_search {")) {
                    return PolicyDecision.transform(c.request().withMessage("rewritten"));
                }
                return PolicyDecision.deny("bare arg refused");
            }
        };
        var interceptor = new GovernanceFleetInterceptor(List.of(strictPerArg));
        var deny = assertInstanceOf(FleetInterceptor.Decision.Deny.class,
                interceptor.before(call("web_search", Map.of("q", "hi"))));
        assertTrue(deny.reason().contains("bare arg refused"));
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
