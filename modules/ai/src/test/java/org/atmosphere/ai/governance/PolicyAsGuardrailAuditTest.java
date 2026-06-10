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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the audit-sink gap: governance policies installed on the
 * {@code @AiEndpoint} streaming path arrive wrapped as {@link PolicyAsGuardrail}
 * and used to evaluate without recording to {@link GovernanceDecisionLog}, so
 * the admin decisions view and the Kafka / Postgres audit sinks missed every
 * decision made on the primary production path. The adapter now records each
 * decision (admit / transform / deny / error) exactly like
 * {@link PolicyAdmissionGate} and {@code AiPipeline}.
 */
class PolicyAsGuardrailAuditTest {

    @BeforeEach
    void installLog() {
        GovernanceDecisionLog.install(50);
    }

    @AfterEach
    void resetLog() {
        GovernanceDecisionLog.reset();
    }

    private static AuditEntry onlyEntry() {
        var recent = GovernanceDecisionLog.installed().recent(10);
        assertEquals(1, recent.size(), "exactly one decision should have been recorded");
        return recent.get(0);
    }

    @Test
    void inspectRequestRecordsDeny() {
        var guardrail = new PolicyAsGuardrail(
                new FixedPolicy("deny-pol", PolicyDecision.deny("blocked")));

        guardrail.inspectRequest(new AiRequest("hello"));

        var entry = onlyEntry();
        assertEquals("deny", entry.decision());
        assertEquals("deny-pol", entry.policyName());
        assertEquals("blocked", entry.reason());
    }

    @Test
    void inspectRequestRecordsAdmit() {
        var guardrail = new PolicyAsGuardrail(
                new FixedPolicy("allow-pol", PolicyDecision.admit()));

        guardrail.inspectRequest(new AiRequest("hello"));

        assertEquals("admit", onlyEntry().decision());
    }

    @Test
    void inspectRequestRecordsTransform() {
        var guardrail = new PolicyAsGuardrail(
                new FixedPolicy("rewrite-pol", PolicyDecision.transform(new AiRequest("[redacted]"))));

        guardrail.inspectRequest(new AiRequest("leak@x.com"));

        assertEquals("transform", onlyEntry().decision());
    }

    @Test
    void inspectResponseRecordsDeny() {
        var guardrail = new PolicyAsGuardrail(
                new FixedPolicy("pii-pol", PolicyDecision.deny("PII in response")));

        guardrail.inspectResponse("leak@x.com");

        var entry = onlyEntry();
        assertEquals("deny", entry.decision());
        assertEquals("PII in response", entry.reason());
    }

    @Test
    void throwingPolicyIsRecordedAsErrorThenRethrown() {
        var boom = new RuntimeException("kaboom");
        var guardrail = new PolicyAsGuardrail(new GovernancePolicy() {
            @Override public String name() {
                return "throwing-pol";
            }

            @Override public String source() {
                return "code:test";
            }

            @Override public String version() {
                return "test";
            }

            @Override public PolicyDecision evaluate(PolicyContext context) {
                throw boom;
            }
        });

        var thrown = assertThrows(RuntimeException.class,
                () -> guardrail.inspectRequest(new AiRequest("hi")));
        assertEquals("kaboom", thrown.getMessage());

        var entry = onlyEntry();
        assertEquals("error", entry.decision());
        assertTrue(entry.reason().contains("kaboom"), entry.reason());
    }

    /** Minimal policy returning a fixed decision, with a controllable name. */
    private record FixedPolicy(String name, PolicyDecision decision) implements GovernancePolicy {
        @Override
        public String source() {
            return "code:test";
        }

        @Override
        public String version() {
            return "test";
        }

        @Override
        public PolicyDecision evaluate(PolicyContext context) {
            return decision;
        }
    }
}
