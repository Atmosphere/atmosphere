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
import org.atmosphere.ai.annotation.AgentScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimedPolicyTest {

    private record Recording(String policyName, String decision, double evaluationMs) { }

    private final List<Recording> recordings = new ArrayList<>();

    @BeforeEach
    void installCapturingMetrics() {
        GovernanceMetricsHolder.install(new GovernanceMetrics() {
            @Override public void recordSimilarity(String policyName, AgentScope.Tier tier,
                                                    String decision, double similarity) { }
            @Override public void recordEvaluationLatency(String policyName, String decision,
                                                           double evaluationMs) {
                recordings.add(new Recording(policyName, decision, evaluationMs));
            }
        });
    }

    @AfterEach
    void resetMetrics() {
        GovernanceMetricsHolder.reset();
    }

    private record FixedPolicy(String n, PolicyDecision decision) implements GovernancePolicy {
        @Override public String name() { return n; }
        @Override public String source() { return "test"; }
        @Override public String version() { return "1"; }
        @Override public PolicyDecision evaluate(PolicyContext c) { return decision; }
    }

    private static PolicyContext ctx() {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest("x", null, null, null, null, null, null, null, null),
                "");
    }

    @Test
    void admitDecisionRecordsAdmitLabel() {
        new TimedPolicy(new FixedPolicy("p", PolicyDecision.admit())).evaluate(ctx());
        assertEquals(1, recordings.size());
        assertEquals("p", recordings.get(0).policyName());
        assertEquals("admit", recordings.get(0).decision());
        assertTrue(recordings.get(0).evaluationMs() >= 0);
    }

    @Test
    void denyDecisionRecordsDenyLabel() {
        new TimedPolicy(new FixedPolicy("p", PolicyDecision.deny("no"))).evaluate(ctx());
        assertEquals("deny", recordings.get(0).decision());
    }

    @Test
    void transformDecisionRecordsTransformLabel() {
        var rewritten = new AiRequest("new", null, null, null, null, null, null, null, null);
        new TimedPolicy(new FixedPolicy("p", PolicyDecision.transform(rewritten))).evaluate(ctx());
        assertEquals("transform", recordings.get(0).decision());
    }

    @Test
    void delegateExceptionRecordsErrorLabelAndReThrows() {
        var throwing = new GovernancePolicy() {
            @Override public String name() { return "boom"; }
            @Override public String source() { return "test"; }
            @Override public String version() { return "1"; }
            @Override public PolicyDecision evaluate(PolicyContext c) {
                throw new IllegalStateException("kaboom");
            }
        };
        var wrapped = new TimedPolicy(throwing);
        assertThrows(IllegalStateException.class, () -> wrapped.evaluate(ctx()));
        assertEquals(1, recordings.size());
        assertEquals("error", recordings.get(0).decision());
    }

    @Test
    void identityFieldsPassedThrough() {
        var delegate = new FixedPolicy("scope.support", PolicyDecision.admit());
        var wrapped = new TimedPolicy(delegate);
        assertEquals("scope.support", wrapped.name());
        assertEquals("test", wrapped.source());
        assertEquals("1", wrapped.version());
        assertSame(delegate, wrapped.delegate());
    }

    @Test
    void decisionPropagatedUnchanged() {
        var delegate = new FixedPolicy("p", PolicyDecision.deny("reason"));
        var wrapped = new TimedPolicy(delegate);
        var deny = assertInstanceOf(PolicyDecision.Deny.class, wrapped.evaluate(ctx()));
        assertEquals("reason", deny.reason());
    }

    @Test
    void ofIsIdempotent() {
        var base = new FixedPolicy("p", PolicyDecision.admit());
        var once = TimedPolicy.of(base);
        var twice = TimedPolicy.of(once);
        assertSame(once, twice, "TimedPolicy.of must not double-wrap");
    }

    @Test
    void nullDelegateRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TimedPolicy(null));
    }

    @Test
    void latencyIsNonNegative() {
        new TimedPolicy(new FixedPolicy("p", PolicyDecision.admit())).evaluate(ctx());
        assertTrue(recordings.get(0).evaluationMs() >= 0,
                "System.nanoTime measurement should never produce negative elapsed");
    }
}
