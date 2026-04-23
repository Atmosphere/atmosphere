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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CountingPolicyTest {

    private record FixedPolicy(String n, PolicyDecision d) implements GovernancePolicy {
        @Override public String name() { return n; }
        @Override public String source() { return "test"; }
        @Override public String version() { return "1"; }
        @Override public PolicyDecision evaluate(PolicyContext c) { return d; }
    }

    private static PolicyContext ctx() {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest("x", null, null, null, null, null, null, null, null),
                "");
    }

    @Test
    void admitsCountedIntoAdmitsBucket() {
        var policy = new CountingPolicy(new FixedPolicy("p", PolicyDecision.admit()));
        policy.evaluate(ctx());
        policy.evaluate(ctx());
        assertEquals(2, policy.admits());
        assertEquals(0, policy.denies());
        assertEquals(2, policy.total());
    }

    @Test
    void deniesCountedIntoDeniesBucket() {
        var policy = new CountingPolicy(new FixedPolicy("p", PolicyDecision.deny("x")));
        policy.evaluate(ctx());
        assertEquals(1, policy.denies());
    }

    @Test
    void transformsCountedSeparately() {
        var rewritten = new AiRequest("new", null, null, null, null, null, null, null, null);
        var policy = new CountingPolicy(new FixedPolicy("p", PolicyDecision.transform(rewritten)));
        policy.evaluate(ctx());
        assertEquals(1, policy.transforms());
    }

    @Test
    void errorsCountedAndReThrown() {
        var throwing = new GovernancePolicy() {
            @Override public String name() { return "boom"; }
            @Override public String source() { return "test"; }
            @Override public String version() { return "1"; }
            @Override public PolicyDecision evaluate(PolicyContext c) {
                throw new IllegalStateException("kaboom");
            }
        };
        var policy = new CountingPolicy(throwing);
        assertThrows(IllegalStateException.class, () -> policy.evaluate(ctx()));
        assertEquals(1, policy.errors());
    }

    @Test
    void identityFieldsPassthrough() {
        var inner = new FixedPolicy("inner", PolicyDecision.admit());
        var counting = new CountingPolicy(inner);
        assertEquals("inner", counting.name());
        assertEquals("test", counting.source());
        assertEquals("1", counting.version());
        assertSame(inner, counting.delegate());
    }

    @Test
    void ofIsIdempotent() {
        var base = new FixedPolicy("p", PolicyDecision.admit());
        var once = CountingPolicy.of(base);
        var twice = CountingPolicy.of(once);
        assertSame(once, twice, "CountingPolicy.of must not double-wrap");
    }

    @Test
    void resetClearsCounters() {
        var policy = new CountingPolicy(new FixedPolicy("p", PolicyDecision.admit()));
        policy.evaluate(ctx());
        policy.evaluate(ctx());
        policy.reset();
        assertEquals(0, policy.admits());
        assertEquals(0, policy.total());
    }

    @Test
    void nullDelegateRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CountingPolicy(null));
    }

    @Test
    void totalAggregatesAllBuckets() {
        var admits = new CountingPolicy(new FixedPolicy("p", PolicyDecision.admit()));
        admits.evaluate(ctx());

        var denies = new CountingPolicy(new FixedPolicy("p", PolicyDecision.deny("x")));
        denies.evaluate(ctx());
        denies.evaluate(ctx());

        assertEquals(1, admits.total());
        assertEquals(2, denies.total());
    }
}
