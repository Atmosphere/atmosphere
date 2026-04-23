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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DryRunPolicyTest {

    private GovernanceDecisionLog log;

    @BeforeEach
    void installLog() {
        log = GovernanceDecisionLog.install(50);
    }

    @AfterEach
    void resetLog() {
        GovernanceDecisionLog.reset();
    }

    private static PolicyContext ctx() {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest("hello", null, null, null, null, null, null, null, null),
                "");
    }

    /** Minimal policy that returns a pre-configured decision. */
    private record FixedPolicy(String name, PolicyDecision decision) implements GovernancePolicy {
        @Override public String source() { return "test"; }
        @Override public String version() { return "1"; }
        @Override public PolicyDecision evaluate(PolicyContext c) { return decision; }
    }

    @Test
    void shadowAdmitCountsAndReturnsAdmit() {
        var wrapped = new DryRunPolicy(new FixedPolicy("p1", PolicyDecision.admit()));
        assertInstanceOf(PolicyDecision.Admit.class, wrapped.evaluate(ctx()));
        assertEquals(1, wrapped.shadowAdmits());
        assertEquals(0, wrapped.shadowDenies());
    }

    @Test
    void shadowDenyCountsButStillAdmits() {
        var wrapped = new DryRunPolicy(new FixedPolicy("p1", PolicyDecision.deny("would-block")));
        var decision = wrapped.evaluate(ctx());
        assertInstanceOf(PolicyDecision.Admit.class, decision,
                "dry-run must NEVER deny, regardless of the delegate's decision");
        assertEquals(1, wrapped.shadowDenies());
    }

    @Test
    void shadowTransformCountsAndAdmits() {
        var rewritten = new AiRequest("rewritten", null, null, null, null, null, null, null, null);
        var wrapped = new DryRunPolicy(new FixedPolicy("p1", PolicyDecision.transform(rewritten)));
        assertInstanceOf(PolicyDecision.Admit.class, wrapped.evaluate(ctx()));
        assertEquals(1, wrapped.shadowTransforms());
    }

    @Test
    void delegateExceptionIsSwallowedAndCountedAsError() {
        var throwing = new GovernancePolicy() {
            @Override public String name() { return "boom"; }
            @Override public String source() { return "test"; }
            @Override public String version() { return "1"; }
            @Override public PolicyDecision evaluate(PolicyContext c) {
                throw new IllegalStateException("classpath problem");
            }
        };
        var wrapped = new DryRunPolicy(throwing);
        assertInstanceOf(PolicyDecision.Admit.class, wrapped.evaluate(ctx()),
                "dry-run must admit even when the delegate throws");
        assertEquals(1, wrapped.delegateErrors());
    }

    @Test
    void auditLogEntriesArePrefixedWithDryRun() {
        var wrapped = new DryRunPolicy(new FixedPolicy("scope.support", PolicyDecision.deny("off-topic")));
        wrapped.evaluate(ctx());

        var recent = log.recent(10);
        assertEquals(1, recent.size());
        var entry = recent.get(0);
        assertTrue(entry.decision().startsWith(DryRunPolicy.AUDIT_DECISION_PREFIX),
                "expected dry-run: prefix, got: " + entry.decision());
        assertEquals("dry-run:deny", entry.decision());
        assertEquals("off-topic", entry.reason());
        assertEquals("scope.support", entry.policyName(),
                "audit entry preserves delegate's name so operators can compare dry-run vs enforced");
    }

    @Test
    void wrapperNameIsPrefixedButDelegateFieldsAreExposed() {
        var delegate = new FixedPolicy("scope.support", PolicyDecision.admit());
        var wrapped = new DryRunPolicy(delegate);
        assertEquals("dry-run:scope.support", wrapped.name());
        assertEquals(delegate, wrapped.delegate());
        assertEquals("test", wrapped.source());
        assertEquals("1", wrapped.version());
    }

    @Test
    void doubleWrappingIsRejected() {
        var inner = new DryRunPolicy(new FixedPolicy("p1", PolicyDecision.admit()));
        assertThrows(IllegalArgumentException.class, () -> new DryRunPolicy(inner));
    }

    @Test
    void nullDelegateIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DryRunPolicy(null));
    }

    @Test
    void resetCountersRestartsBaseline() {
        var wrapped = new DryRunPolicy(new FixedPolicy("p1", PolicyDecision.deny("x")));
        wrapped.evaluate(ctx());
        wrapped.evaluate(ctx());
        assertEquals(2, wrapped.shadowDenies());

        wrapped.resetCounters();
        assertEquals(0, wrapped.shadowDenies());
        assertEquals(0, wrapped.totalEvaluations());
    }

    @Test
    void totalEvaluationsAggregatesAllOutcomes() {
        var admit = new DryRunPolicy(new FixedPolicy("a", PolicyDecision.admit()));
        admit.evaluate(ctx());

        var deny = new DryRunPolicy(new FixedPolicy("d", PolicyDecision.deny("no")));
        deny.evaluate(ctx());
        deny.evaluate(ctx());

        assertEquals(1, admit.totalEvaluations());
        assertEquals(2, deny.totalEvaluations());
    }
}
