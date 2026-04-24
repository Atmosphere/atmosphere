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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageLengthPolicyTest {

    private static PolicyContext req(String msg) {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest(msg, null, null, null, null, null, null, null, null),
                "");
    }

    @Test
    void admitsMessagesAtOrUnderCap() {
        var policy = new MessageLengthPolicy("cap-100", 100);
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(req("short")));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(req("a".repeat(100))));
    }

    @Test
    void deniesOverCap() {
        var policy = new MessageLengthPolicy("cap-100", 100);
        var deny = assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(req("a".repeat(101))));
        assertTrue(deny.reason().contains("101"));
        assertTrue(deny.reason().contains("100"));
    }

    @Test
    void emptyMessageAdmits() {
        var policy = new MessageLengthPolicy("cap", 100);
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(req("")));
    }

    @Test
    void postResponseAlwaysAdmits() {
        var policy = new MessageLengthPolicy("cap", 10);
        var post = new PolicyContext(PolicyContext.Phase.POST_RESPONSE,
                new AiRequest("short", null, null, null, null, null, null, null, null),
                "a".repeat(10_000));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(post),
                "response-length caps are the runtime's concern, not a policy concern");
    }

    @Test
    void capAccessorReflectsConfig() {
        assertEquals(4096, new MessageLengthPolicy("cap", 4096).maxChars());
    }

    @Test
    void identityFieldsReflectConstructor() {
        var policy = new MessageLengthPolicy("cap-5k", "yaml:/etc/policies.yaml", "2.1", 5000);
        assertEquals("cap-5k", policy.name());
        assertEquals("yaml:/etc/policies.yaml", policy.source());
        assertEquals("2.1", policy.version());
    }

    @Test
    void rejectsNonPositiveCap() {
        assertThrows(IllegalArgumentException.class, () -> new MessageLengthPolicy("x", 0));
        assertThrows(IllegalArgumentException.class, () -> new MessageLengthPolicy("x", -1));
    }

    @Test
    void rejectsBlankIdentityFields() {
        assertThrows(IllegalArgumentException.class, () -> new MessageLengthPolicy("", 10));
        assertThrows(IllegalArgumentException.class,
                () -> new MessageLengthPolicy("x", "", "1", 10));
        assertThrows(IllegalArgumentException.class,
                () -> new MessageLengthPolicy("x", "src", "", 10));
    }
}
