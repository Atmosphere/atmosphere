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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwappablePolicyTest {

    private record FixedPolicy(String n, String v, PolicyDecision d) implements GovernancePolicy {
        @Override public String name() { return n; }
        @Override public String source() { return "test"; }
        @Override public String version() { return v; }
        @Override public PolicyDecision evaluate(PolicyContext c) { return d; }
    }

    private static PolicyContext ctx() {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest("x", null, null, null, null, null, null, null, null),
                "");
    }

    @Test
    void delegatesToInitialPolicy() {
        var swap = new SwappablePolicy("reloadable",
                new FixedPolicy("p", "1", PolicyDecision.deny("initial-reason")));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, swap.evaluate(ctx()));
        assertEquals("initial-reason", deny.reason());
    }

    @Test
    void replaceSwapsEnforcementAtomically() {
        var swap = new SwappablePolicy("reloadable",
                new FixedPolicy("p", "1", PolicyDecision.admit()));
        assertInstanceOf(PolicyDecision.Admit.class, swap.evaluate(ctx()));

        var old = swap.replace(new FixedPolicy("p", "2", PolicyDecision.deny("new-rule")));
        assertInstanceOf(PolicyDecision.Deny.class, swap.evaluate(ctx()));
        assertEquals("1", old.version(),
                "replace returns the outgoing delegate so admin can log the version swap");
    }

    @Test
    void identityFieldsStayStableAcrossSwaps() {
        var swap = new SwappablePolicy("stable-name", "yaml:/etc/policies.yaml", "v1",
                new FixedPolicy("p", "1", PolicyDecision.admit()));
        assertEquals("stable-name", swap.name());
        assertEquals("yaml:/etc/policies.yaml", swap.source());
        assertEquals("v1", swap.version());

        swap.replace(new FixedPolicy("p", "99", PolicyDecision.admit()));
        assertEquals("stable-name", swap.name(),
                "wrapper identity must not change on swap — audit labels stay pinned");
        assertEquals("v1", swap.version());
    }

    @Test
    void delegateVersionReportsCurrentDelegate() {
        var swap = new SwappablePolicy("x",
                new FixedPolicy("p", "1", PolicyDecision.admit()));
        assertEquals("1", swap.delegateVersion());
        swap.replace(new FixedPolicy("p", "42", PolicyDecision.admit()));
        assertEquals("42", swap.delegateVersion());
    }

    @Test
    void delegateAccessorReturnsCurrentReference() {
        var first = new FixedPolicy("p", "1", PolicyDecision.admit());
        var swap = new SwappablePolicy("x", first);
        assertSame(first, swap.delegate());

        var next = new FixedPolicy("p", "2", PolicyDecision.admit());
        swap.replace(next);
        assertSame(next, swap.delegate());
    }

    @Test
    void rejectsNullInitialOrReplacement() {
        assertThrows(IllegalArgumentException.class, () -> new SwappablePolicy("x", null));
        var swap = new SwappablePolicy("x",
                new FixedPolicy("p", "1", PolicyDecision.admit()));
        assertThrows(IllegalArgumentException.class, () -> swap.replace(null));
    }

    @Test
    void nestingSwappableInsideSwappableRejected() {
        var inner = new SwappablePolicy("inner",
                new FixedPolicy("p", "1", PolicyDecision.admit()));
        var outer = new SwappablePolicy("outer",
                new FixedPolicy("p", "1", PolicyDecision.admit()));
        assertThrows(IllegalArgumentException.class, () -> outer.replace(inner));
    }

    @Test
    void blankIdentityRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SwappablePolicy("", new FixedPolicy("p", "1", PolicyDecision.admit())));
    }

    @Test
    void concurrentSwapsAndEvaluationsConverge() throws Exception {
        var swap = new SwappablePolicy("concurrent",
                new FixedPolicy("p", "0", PolicyDecision.admit()));

        var executor = Executors.newFixedThreadPool(16);
        var ready = new CountDownLatch(1);
        var hits = new AtomicInteger();

        try {
            // 10 swappers
            for (int i = 1; i <= 10; i++) {
                final int version = i;
                executor.submit(() -> {
                    try {
                        ready.await();
                        swap.replace(new FixedPolicy("p", String.valueOf(version),
                                PolicyDecision.admit()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            // 100 readers
            for (int i = 0; i < 100; i++) {
                executor.submit(() -> {
                    try {
                        ready.await();
                        swap.evaluate(ctx());
                        hits.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            ready.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(100, hits.get(),
                "every reader completed without NPE on the swap reference");
    }
}
