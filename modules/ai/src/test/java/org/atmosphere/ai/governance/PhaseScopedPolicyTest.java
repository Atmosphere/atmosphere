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

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhaseScopedPolicyTest {

    private static AiRequest req() {
        return new AiRequest("x", null, null, null, null, null, null, null, null);
    }

    private static PolicyContext pre() {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION, req(), "");
    }

    private static PolicyContext post() {
        return new PolicyContext(PolicyContext.Phase.POST_RESPONSE, req(), "response text");
    }

    private static GovernancePolicy counting(AtmosphereInvokeCounter counter,
                                              PolicyDecision decision) {
        return new GovernancePolicy() {
            @Override public String name() { return "counted"; }
            @Override public String source() { return "test"; }
            @Override public String version() { return "1"; }
            @Override public PolicyDecision evaluate(PolicyContext c) {
                counter.incrementAndGet();
                return decision;
            }
        };
    }

    private static class AtmosphereInvokeCounter extends AtomicInteger { }

    @Test
    void preAdmissionOnlySkipsDelegateDuringPostResponse() {
        var counter = new AtmosphereInvokeCounter();
        var gated = PhaseScopedPolicy.preAdmissionOnly(counting(counter, PolicyDecision.deny("x")));

        assertInstanceOf(PolicyDecision.Admit.class, gated.evaluate(post()));
        assertEquals(0, counter.get(), "delegate must not run during POST_RESPONSE");

        assertInstanceOf(PolicyDecision.Deny.class, gated.evaluate(pre()));
        assertEquals(1, counter.get());
    }

    @Test
    void postResponseOnlySkipsDelegateDuringPreAdmission() {
        var counter = new AtmosphereInvokeCounter();
        var gated = PhaseScopedPolicy.postResponseOnly(counting(counter, PolicyDecision.deny("x")));

        assertInstanceOf(PolicyDecision.Admit.class, gated.evaluate(pre()));
        assertEquals(0, counter.get(), "delegate must not run during PRE_ADMISSION");

        assertInstanceOf(PolicyDecision.Deny.class, gated.evaluate(post()));
        assertEquals(1, counter.get());
    }

    @Test
    void identityFieldsPassthrough() {
        var inner = new GovernancePolicy() {
            @Override public String name() { return "inner-name"; }
            @Override public String source() { return "yaml:inner.yaml"; }
            @Override public String version() { return "7"; }
            @Override public PolicyDecision evaluate(PolicyContext c) { return PolicyDecision.admit(); }
        };
        var gated = PhaseScopedPolicy.preAdmissionOnly(inner);
        assertEquals("inner-name", gated.name());
        assertEquals("yaml:inner.yaml", gated.source());
        assertEquals("7", gated.version());
    }

    @Test
    void delegateAccessorReturnsInner() {
        var inner = new GovernancePolicy() {
            @Override public String name() { return "i"; }
            @Override public String source() { return "t"; }
            @Override public String version() { return "1"; }
            @Override public PolicyDecision evaluate(PolicyContext c) { return PolicyDecision.admit(); }
        };
        var gated = PhaseScopedPolicy.preAdmissionOnly(inner);
        assertEquals(inner, gated.delegate());
    }

    @Test
    void activePhasesAccessorIsDefensiveCopy() {
        var inner = new GovernancePolicy() {
            @Override public String name() { return "i"; }
            @Override public String source() { return "t"; }
            @Override public String version() { return "1"; }
            @Override public PolicyDecision evaluate(PolicyContext c) { return PolicyDecision.admit(); }
        };
        var gated = new PhaseScopedPolicy(inner,
                EnumSet.of(PolicyContext.Phase.PRE_ADMISSION));
        var phases = gated.activePhases();
        phases.add(PolicyContext.Phase.POST_RESPONSE);
        assertEquals(1, gated.activePhases().size(),
                "activePhases() must return a defensive copy");
    }

    @Test
    void bothPhasesEvaluateTheDelegateAlways() {
        var counter = new AtmosphereInvokeCounter();
        var gated = new PhaseScopedPolicy(counting(counter, PolicyDecision.admit()),
                EnumSet.allOf(PolicyContext.Phase.class));
        gated.evaluate(pre());
        gated.evaluate(post());
        assertEquals(2, counter.get());
    }

    @Test
    void rejectsNullDelegate() {
        assertThrows(IllegalArgumentException.class,
                () -> new PhaseScopedPolicy(null, EnumSet.of(PolicyContext.Phase.PRE_ADMISSION)));
    }

    @Test
    void rejectsEmptyPhaseSet() {
        var inner = new GovernancePolicy() {
            @Override public String name() { return "i"; }
            @Override public String source() { return "t"; }
            @Override public String version() { return "1"; }
            @Override public PolicyDecision evaluate(PolicyContext c) { return PolicyDecision.admit(); }
        };
        assertThrows(IllegalArgumentException.class,
                () -> new PhaseScopedPolicy(inner, EnumSet.noneOf(PolicyContext.Phase.class)));
    }
}
