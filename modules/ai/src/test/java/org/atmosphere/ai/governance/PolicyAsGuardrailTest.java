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

class PolicyAsGuardrailTest {

    @Test
    void admitMapsToPass() {
        var guardrail = new PolicyAsGuardrail(new FixedPolicy(PolicyDecision.admit()));

        var result = guardrail.inspectRequest(new AiRequest("hi"));
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class, result);
    }

    @Test
    void transformMapsToModify() {
        var rewritten = new AiRequest("[redacted]");
        var guardrail = new PolicyAsGuardrail(new FixedPolicy(PolicyDecision.transform(rewritten)));

        var result = guardrail.inspectRequest(new AiRequest("leak@x.com"));
        var modify = assertInstanceOf(AiGuardrail.GuardrailResult.Modify.class, result);
        assertSame(rewritten, modify.modifiedRequest());
    }

    @Test
    void denyMapsToBlock() {
        var guardrail = new PolicyAsGuardrail(new FixedPolicy(PolicyDecision.deny("nope")));

        var result = guardrail.inspectRequest(new AiRequest("hi"));
        var block = assertInstanceOf(AiGuardrail.GuardrailResult.Block.class, result);
        assertEquals("nope", block.reason());
    }

    @Test
    void postResponseDenyMapsToBlock() {
        var guardrail = new PolicyAsGuardrail(new FixedPolicy(PolicyDecision.deny("PII in response")));

        var result = guardrail.inspectResponse("leak@x.com");
        var block = assertInstanceOf(AiGuardrail.GuardrailResult.Block.class, result);
        assertEquals("PII in response", block.reason());
    }

    @Test
    void postResponseTransformIsIgnoredAndDowngradedToPass() {
        // Transform on the response path is non-operational — streamed text is
        // unretractable. Adapter downgrades to Pass so the rest of the
        // pipeline keeps running; audit signal is on the log, not the stream.
        var guardrail = new PolicyAsGuardrail(new FixedPolicy(
                PolicyDecision.transform(new AiRequest("ignored")), "rewriter"));

        var result = guardrail.inspectResponse("hello");
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class, result);
    }

    @Test
    void rejectsNullPolicy() {
        assertThrows(IllegalArgumentException.class, () -> new PolicyAsGuardrail(null));
    }

    @Test
    void exposesWrappedPolicy() {
        var inner = new FixedPolicy(PolicyDecision.admit());
        var guardrail = new PolicyAsGuardrail(inner);
        assertSame(inner, guardrail.policy());
    }

    private record FixedPolicy(PolicyDecision decision, String name) implements GovernancePolicy {
        FixedPolicy(PolicyDecision decision) {
            this(decision, "fixed-test-policy");
        }

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
