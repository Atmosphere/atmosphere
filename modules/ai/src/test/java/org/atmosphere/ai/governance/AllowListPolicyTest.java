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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllowListPolicyTest {

    private static PolicyContext req(String msg) {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest(msg, null, null, null, null, null, null, null, null),
                "");
    }

    @Test
    void admitsOnPhraseMatch() {
        var policy = new AllowListPolicy("support-topics", "order", "billing", "refund");
        assertInstanceOf(PolicyDecision.Admit.class,
                policy.evaluate(req("I need help with my order status")));
    }

    @Test
    void deniesWhenNoPhraseMatches() {
        var policy = new AllowListPolicy("support-topics", "order", "billing", "refund");
        var deny = assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(req("tell me a joke")));
        assertTrue(deny.reason().contains("allow-list"));
    }

    @Test
    void caseInsensitiveMatch() {
        var policy = new AllowListPolicy("topics", "Support");
        assertInstanceOf(PolicyDecision.Admit.class,
                policy.evaluate(req("I NEED SUPPORT")));
    }

    @Test
    void regexBuilderCompilesPatterns() {
        var policy = AllowListPolicy.fromRegex("numeric-ids",
                "order[- ]?#?\\d{4,}");
        assertInstanceOf(PolicyDecision.Admit.class,
                policy.evaluate(req("status of order #12345 please")));
        assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(req("tell me about anything")));
    }

    @Test
    void postResponseAlwaysAdmits() {
        var policy = new AllowListPolicy("topics", "order");
        var post = new PolicyContext(PolicyContext.Phase.POST_RESPONSE,
                new AiRequest("prompt", null, null, null, null, null, null, null, null),
                "response about completely unrelated thing");
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(post),
                "allow-list only gates admission; response filtering is a DenyListPolicy job");
    }

    @Test
    void emptyMessageDenied() {
        var policy = new AllowListPolicy("topics", "order");
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(req("")),
                "allow-list default-deny: empty message cannot be on-topic");
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(req("   ")));
    }

    @Test
    void phrasesTreatedAsLiteralNotRegex() {
        var policy = new AllowListPolicy("literal", "a.b");
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(req("axb")),
                "literal phrase must NOT interpret '.' as a regex metachar");
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(req("the key is a.b for you")));
    }

    @Test
    void patternStringsExposedForAdminIntrospection() {
        var policy = new AllowListPolicy("topics", "foo", "bar");
        var strings = policy.patternStrings();
        assertTrue(strings.size() == 2);
        assertTrue(strings.contains("\\Qfoo\\E"));
        assertTrue(strings.contains("\\Qbar\\E"));
    }

    @Test
    void emptyPhraseListRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AllowListPolicy("x"));
        assertThrows(IllegalArgumentException.class, () -> new AllowListPolicy("x", "", null));
    }

    @Test
    void blankNameRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AllowListPolicy("", "foo"));
    }
}
