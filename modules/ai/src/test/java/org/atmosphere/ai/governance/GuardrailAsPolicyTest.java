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

import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GuardrailAsPolicyTest {

    @Test
    void defaultIdentityDerivedFromGuardrailClass() {
        var guardrail = new FixedGuardrail(AiGuardrail.GuardrailResult.pass(),
                AiGuardrail.GuardrailResult.pass());
        var policy = new GuardrailAsPolicy(guardrail);

        assertEquals("FixedGuardrail", policy.name());
        assertEquals("code:" + FixedGuardrail.class.getName(), policy.source());
        assertEquals("embedded", policy.version());
        assertSame(guardrail, policy.guardrail());
    }

    @Test
    void explicitIdentityWins() {
        var guardrail = new FixedGuardrail(AiGuardrail.GuardrailResult.pass(),
                AiGuardrail.GuardrailResult.pass());
        var policy = new GuardrailAsPolicy(guardrail, "my-pii", "yaml:/etc/p.yaml", "1.2.0");

        assertEquals("my-pii", policy.name());
        assertEquals("yaml:/etc/p.yaml", policy.source());
        assertEquals("1.2.0", policy.version());
    }

    @Test
    void preAdmissionPassMapsToAdmit() {
        var policy = new GuardrailAsPolicy(new FixedGuardrail(
                AiGuardrail.GuardrailResult.pass(), AiGuardrail.GuardrailResult.pass()));

        var decision = policy.evaluate(PolicyContext.preAdmission(new AiRequest("hi")));
        assertInstanceOf(PolicyDecision.Admit.class, decision);
    }

    @Test
    void preAdmissionModifyMapsToTransform() {
        var rewritten = new AiRequest("[redacted]");
        var policy = new GuardrailAsPolicy(new FixedGuardrail(
                AiGuardrail.GuardrailResult.modify(rewritten),
                AiGuardrail.GuardrailResult.pass()));

        var decision = policy.evaluate(PolicyContext.preAdmission(new AiRequest("leak@x.com")));
        var transform = assertInstanceOf(PolicyDecision.Transform.class, decision);
        assertSame(rewritten, transform.modifiedRequest());
    }

    @Test
    void preAdmissionBlockMapsToDeny() {
        var policy = new GuardrailAsPolicy(new FixedGuardrail(
                AiGuardrail.GuardrailResult.block("nope"),
                AiGuardrail.GuardrailResult.pass()));

        var decision = policy.evaluate(PolicyContext.preAdmission(new AiRequest("hi")));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, decision);
        assertEquals("nope", deny.reason());
    }

    @Test
    void postResponsePassMapsToAdmit() {
        var policy = new GuardrailAsPolicy(new FixedGuardrail(
                AiGuardrail.GuardrailResult.pass(), AiGuardrail.GuardrailResult.pass()));

        var decision = policy.evaluate(PolicyContext.postResponse(new AiRequest("hi"), "all good"));
        assertInstanceOf(PolicyDecision.Admit.class, decision);
    }

    @Test
    void postResponseBlockMapsToDeny() {
        var policy = new GuardrailAsPolicy(new FixedGuardrail(
                AiGuardrail.GuardrailResult.pass(),
                AiGuardrail.GuardrailResult.block("PII in response")));

        var decision = policy.evaluate(PolicyContext.postResponse(new AiRequest("hi"), "leak@x.com"));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, decision);
        assertEquals("PII in response", deny.reason());
    }

    @Test
    void rejectsNullGuardrail() {
        assertThrows(IllegalArgumentException.class, () -> new GuardrailAsPolicy(null));
    }

    @Test
    void rejectsBlankIdentityFields() {
        var g = new FixedGuardrail(AiGuardrail.GuardrailResult.pass(),
                AiGuardrail.GuardrailResult.pass());
        assertThrows(IllegalArgumentException.class,
                () -> new GuardrailAsPolicy(g, "", "s", "v"));
        assertThrows(IllegalArgumentException.class,
                () -> new GuardrailAsPolicy(g, "n", " ", "v"));
        assertThrows(IllegalArgumentException.class,
                () -> new GuardrailAsPolicy(g, "n", "s", null));
    }

    private record FixedGuardrail(AiGuardrail.GuardrailResult onRequest,
                                  AiGuardrail.GuardrailResult onResponse) implements AiGuardrail {
        @Override
        public GuardrailResult inspectRequest(AiRequest request) {
            return onRequest;
        }

        @Override
        public GuardrailResult inspectResponse(String accumulatedResponse) {
            return onResponse;
        }
    }
}
