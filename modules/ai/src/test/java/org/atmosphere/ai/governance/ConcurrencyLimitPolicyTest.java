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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyLimitPolicyTest {

    private static AiRequest forUser(String u) {
        return new AiRequest("msg", null, null, u, null, null, null, null, null);
    }

    private static PolicyContext preAdm(AiRequest r) {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION, r, "");
    }

    private static PolicyContext postResp(AiRequest r) {
        return new PolicyContext(PolicyContext.Phase.POST_RESPONSE, r, "response");
    }

    @Test
    void admitsUntilMaxConcurrent() {
        var policy = new ConcurrencyLimitPolicy("cc", 2);
        var ctx = preAdm(forUser("alice"));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(ctx));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(ctx));
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(ctx),
                "third concurrent request exceeds cap of 2");
        assertEquals(2, policy.inFlightFor("user:alice"));
    }

    @Test
    void postResponseReleasesSlot() {
        var policy = new ConcurrencyLimitPolicy("cc", 1);
        var pre = preAdm(forUser("alice"));
        policy.evaluate(pre);
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(pre));

        policy.evaluate(postResp(forUser("alice")));
        assertEquals(0, policy.inFlightFor("user:alice"));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(pre),
                "next request admits after the first completed");
    }

    @Test
    void explicitReleaseMatchesPostResponseBehavior() {
        var policy = new ConcurrencyLimitPolicy("cc", 1);
        policy.evaluate(preAdm(forUser("alice")));
        assertEquals(1, policy.inFlightFor("user:alice"));
        policy.release("user:alice");
        assertEquals(0, policy.inFlightFor("user:alice"));
    }

    @Test
    void releaseIsIdempotentAtZero() {
        var policy = new ConcurrencyLimitPolicy("cc", 2);
        policy.release("user:never-existed");
        policy.release("user:never-existed");
        assertEquals(0, policy.inFlightFor("user:never-existed"));
    }

    @Test
    void separateSubjectsCountedIndependently() {
        var policy = new ConcurrencyLimitPolicy("cc", 1);
        policy.evaluate(preAdm(forUser("alice")));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(preAdm(forUser("bob"))),
                "bob's slot is separate from alice's");
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(preAdm(forUser("alice"))));
    }

    @Test
    void deniedRequestDoesNotConsumeSlot() {
        var policy = new ConcurrencyLimitPolicy("cc", 1);
        policy.evaluate(preAdm(forUser("alice")));
        policy.evaluate(preAdm(forUser("alice"))); // denied
        assertEquals(1, policy.inFlightFor("user:alice"),
                "denied request must NOT increment the counter past the cap");
    }

    @Test
    void resetClearsCounters() {
        var policy = new ConcurrencyLimitPolicy("cc", 1);
        policy.evaluate(preAdm(forUser("alice")));
        policy.reset();
        assertEquals(0, policy.inFlightFor("user:alice"));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(preAdm(forUser("alice"))));
    }

    @Test
    void anonymousFallbackForUnkownIdentity() {
        var policy = new ConcurrencyLimitPolicy("cc", 1);
        var anon = preAdm(new AiRequest("m", null, null, null, null, null, null, null, null));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(anon));
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(anon),
                "anonymous also gets a shared slot");
        assertEquals(1, policy.inFlightFor("anonymous"));
    }

    @Test
    void rejectsInvalidConfig() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrencyLimitPolicy("cc", 0));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrencyLimitPolicy("cc", -1));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrencyLimitPolicy("", 1));
    }

    @Test
    void concurrentEvaluateDoesNotExceedCap() throws Exception {
        var policy = new ConcurrencyLimitPolicy("cc", 5);
        var admits = new AtomicInteger();
        var denies = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(16);
        var ready = new CountDownLatch(1);

        try {
            for (int i = 0; i < 50; i++) {
                pool.submit(() -> {
                    try {
                        ready.await();
                        var decision = policy.evaluate(preAdm(forUser("shared")));
                        if (decision instanceof PolicyDecision.Admit) {
                            admits.incrementAndGet();
                        } else {
                            denies.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            ready.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

            // Exactly 5 admits regardless of thread scheduling — the CAS loop
            // is the invariant being tested here.
            assertEquals(5, admits.get(),
                    "concurrent evaluate calls must never exceed the cap");
            assertEquals(45, denies.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
