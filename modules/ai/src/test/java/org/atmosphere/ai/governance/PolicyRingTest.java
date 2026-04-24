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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyRingTest {

    private static PolicyContext ctx() {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest("hello", null, null, null, null, null, null, null, null),
                "");
    }

    /** Policy that logs each evaluation into the caller-supplied list. */
    private record TracingPolicy(String name, PolicyDecision decision, List<String> trace)
            implements GovernancePolicy {
        @Override public String source() { return "test"; }
        @Override public String version() { return "1"; }
        @Override public PolicyDecision evaluate(PolicyContext c) {
            trace.add(name);
            return decision;
        }
    }

    @Test
    void outermostRingEvaluatesBeforeInnerRings() {
        var trace = new ArrayList<String>();
        var ring = PolicyRing.builder("composite")
                .ring(3, new TracingPolicy("llm", PolicyDecision.admit(), trace))
                .ring(1, new TracingPolicy("rule", PolicyDecision.admit(), trace))
                .ring(2, new TracingPolicy("embed", PolicyDecision.admit(), trace))
                .build();

        ring.evaluate(ctx());
        assertEquals(List.of("rule", "embed", "llm"), trace,
                "rings evaluate in ascending index order");
    }

    @Test
    void denyInRing1ShortCircuitsLaterRings() {
        var trace = new ArrayList<String>();
        var ring = PolicyRing.builder("composite")
                .ring(1, new TracingPolicy("cheap", PolicyDecision.deny("no"), trace))
                .ring(2, new TracingPolicy("expensive", PolicyDecision.admit(), trace))
                .build();

        var decision = ring.evaluate(ctx());
        var deny = assertInstanceOf(PolicyDecision.Deny.class, decision);
        assertEquals("no", deny.reason());
        assertEquals(List.of("cheap"), trace,
                "expensive ring must NOT be visited after ring 1 denies");
    }

    @Test
    void transformInRing1FeedsRewrittenRequestToRing2() {
        var rewritten = new AiRequest("[redacted]", null, null, null, null, null, null, null, null);
        var seenByRing2 = new AtomicInteger();
        var transformer = new GovernancePolicy() {
            @Override public String name() { return "t"; }
            @Override public String source() { return "test"; }
            @Override public String version() { return "1"; }
            @Override public PolicyDecision evaluate(PolicyContext c) {
                return PolicyDecision.transform(rewritten);
            }
        };
        var ring2 = new GovernancePolicy() {
            @Override public String name() { return "r2"; }
            @Override public String source() { return "test"; }
            @Override public String version() { return "1"; }
            @Override public PolicyDecision evaluate(PolicyContext c) {
                if (c.request() == rewritten) seenByRing2.incrementAndGet();
                return PolicyDecision.admit();
            }
        };
        var ring = PolicyRing.builder("composite")
                .ring(1, transformer)
                .ring(2, ring2)
                .build();

        var decision = ring.evaluate(ctx());
        assertEquals(1, seenByRing2.get(), "ring 2 must see the rewritten request");
        var transform = assertInstanceOf(PolicyDecision.Transform.class, decision);
        assertSame(rewritten, transform.modifiedRequest(),
                "composite surfaces the rewrite when earlier rings transformed but no ring denied");
    }

    @Test
    void multiplePoliciesInSameRingAllEvaluateUnlessOneDenies() {
        var trace = new ArrayList<String>();
        var ring = PolicyRing.builder("composite")
                .ring(1,
                        new TracingPolicy("a", PolicyDecision.admit(), trace),
                        new TracingPolicy("b", PolicyDecision.admit(), trace),
                        new TracingPolicy("c", PolicyDecision.admit(), trace))
                .build();

        ring.evaluate(ctx());
        assertEquals(List.of("a", "b", "c"), trace);
    }

    @Test
    void insertionOrderPreservedWithinRing() {
        var trace = new ArrayList<String>();
        var ring = PolicyRing.builder("composite")
                .ring(1, new TracingPolicy("first", PolicyDecision.admit(), trace))
                .ring(1, new TracingPolicy("second", PolicyDecision.deny("stop"), trace))
                .ring(1, new TracingPolicy("never", PolicyDecision.admit(), trace))
                .build();

        var decision = ring.evaluate(ctx());
        assertInstanceOf(PolicyDecision.Deny.class, decision);
        assertEquals(List.of("first", "second"), trace,
                "third policy in the ring never evaluates after 'second' denies");
    }

    @Test
    void plainAdmitWhenNoRingTransformedOrDenied() {
        var ring = PolicyRing.builder("composite")
                .ring(1, new TracingPolicy("a", PolicyDecision.admit(), new ArrayList<>()))
                .build();
        assertInstanceOf(PolicyDecision.Admit.class, ring.evaluate(ctx()));
    }

    @Test
    void ringsViewIsSortedByIndex() {
        var ring = PolicyRing.builder("composite")
                .ring(10, new TracingPolicy("x", PolicyDecision.admit(), new ArrayList<>()))
                .ring(1, new TracingPolicy("y", PolicyDecision.admit(), new ArrayList<>()))
                .ring(5, new TracingPolicy("z", PolicyDecision.admit(), new ArrayList<>()))
                .build();

        var indices = ring.rings().stream().map(PolicyRing.RingEntry::index).toList();
        assertEquals(List.of(1, 5, 10), indices);
    }

    @Test
    void emptyBuilderFails() {
        assertThrows(IllegalStateException.class, () -> PolicyRing.builder("x").build());
    }

    @Test
    void emptyRingPoliciesFail() {
        var b = PolicyRing.builder("x");
        assertThrows(IllegalArgumentException.class, () -> b.ring(1, List.of()));
    }

    @Test
    void nullPolicyInRingRejected() {
        var b = PolicyRing.builder("x");
        var listWithNull = new ArrayList<GovernancePolicy>();
        listWithNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> b.ring(1, listWithNull));
    }

    @Test
    void identityFieldsReflectBuilder() {
        var ring = PolicyRing.builder("composite")
                .source("yaml:/etc/policies.yaml")
                .version("2026-04-23")
                .ring(1, new TracingPolicy("p", PolicyDecision.admit(), new ArrayList<>()))
                .build();

        assertEquals("composite", ring.name());
        assertEquals("yaml:/etc/policies.yaml", ring.source());
        assertEquals("2026-04-23", ring.version());
    }

    @Test
    void denyReasonPropagatesUnchanged() {
        var ring = PolicyRing.builder("composite")
                .ring(1, new TracingPolicy("p", PolicyDecision.deny("because"), new ArrayList<>()))
                .build();

        var deny = assertInstanceOf(PolicyDecision.Deny.class, ring.evaluate(ctx()));
        assertTrue(deny.reason().contains("because"));
    }
}
