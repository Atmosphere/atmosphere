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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KillSwitchPolicyTest {

    private static PolicyContext ctx() {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest("hello", null, null, null, null, null, null, null, null),
                "");
    }

    @Test
    void admitsByDefault() {
        var policy = new KillSwitchPolicy();
        assertFalse(policy.isArmed());
        assertNull(policy.armedState());
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(ctx()));
    }

    @Test
    void deniesOnceArmed() {
        var policy = new KillSwitchPolicy();
        policy.arm("incident-42 rollback in progress");
        assertTrue(policy.isArmed());

        var decision = policy.evaluate(ctx());
        var deny = assertInstanceOf(PolicyDecision.Deny.class, decision);
        assertEquals("incident-42 rollback in progress", deny.reason());
    }

    @Test
    void disarmRestoresTraffic() {
        var policy = new KillSwitchPolicy();
        policy.arm("maintenance");
        policy.disarm();
        assertFalse(policy.isArmed());
        assertNull(policy.armedState());
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(ctx()));
    }

    @Test
    void reArmingOverwritesReason() {
        var policy = new KillSwitchPolicy();
        policy.arm("initial reason");
        policy.arm("updated reason");
        var deny = assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(ctx()));
        assertEquals("updated reason", deny.reason());
    }

    @Test
    void armedStateCapturesOperatorAndTimestamp() {
        var policy = new KillSwitchPolicy();
        policy.arm("ops flip", "sre-oncall");
        var state = policy.armedState();
        assertNotNull(state);
        assertEquals("ops flip", state.reason());
        assertEquals("sre-oncall", state.operator());
        assertNotNull(state.armedAt());
    }

    @Test
    void armedStateDefaultsOperatorToUnknownWhenOmitted() {
        var policy = new KillSwitchPolicy();
        policy.arm("ops flip");
        assertEquals("unknown", policy.armedState().operator());
    }

    @Test
    void rejectsBlankReason() {
        var policy = new KillSwitchPolicy();
        assertThrows(IllegalArgumentException.class, () -> policy.arm(""));
        assertThrows(IllegalArgumentException.class, () -> policy.arm(null));
    }

    @Test
    void disarmIsIdempotent() {
        var policy = new KillSwitchPolicy();
        policy.disarm();
        policy.disarm();
        assertFalse(policy.isArmed());
    }

    @Test
    void identityFieldsReflectConstructorArgs() {
        var policy = new KillSwitchPolicy("custom-name", "yaml:/etc/kill.yaml", "2");
        assertEquals("custom-name", policy.name());
        assertEquals("yaml:/etc/kill.yaml", policy.source());
        assertEquals("2", policy.version());
    }

    @Test
    void defaultConstructorUsesConventionalName() {
        var policy = new KillSwitchPolicy();
        assertEquals(KillSwitchPolicy.DEFAULT_NAME, policy.name());
    }

    @Test
    void concurrentFlipsDoNotCorruptState() throws Exception {
        var policy = new KillSwitchPolicy();
        var armers = Executors.newFixedThreadPool(8);
        var latch = new CountDownLatch(1);
        var denies = new AtomicInteger();
        var admits = new AtomicInteger();
        try {
            for (int i = 0; i < 32; i++) {
                armers.submit(() -> {
                    try {
                        latch.await();
                        policy.arm("race");
                        policy.disarm();
                        var decision = policy.evaluate(ctx());
                        if (decision instanceof PolicyDecision.Deny) {
                            denies.incrementAndGet();
                        } else {
                            admits.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            latch.countDown();
            armers.shutdown();
            assertTrue(armers.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            armers.shutdownNow();
        }
        assertEquals(32, denies.get() + admits.get());
    }
}
