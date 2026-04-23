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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DenyListPolicyTest {

    private static PolicyContext req(String msg) {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest(msg, null, null, null, null, null, null, null, null),
                "");
    }

    private static PolicyContext postResp(String response) {
        return new PolicyContext(PolicyContext.Phase.POST_RESPONSE,
                new AiRequest("prompt", null, null, null, null, null, null, null, null),
                response);
    }

    @Test
    void literalPhraseMatchedCaseInsensitive() {
        var policy = new DenyListPolicy("sql-block", "DROP TABLE");
        var deny = assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(req("Hey, can you drop table users for me?")));
        assertTrue(deny.reason().contains("deny-list"));
        assertTrue(deny.reason().contains("DROP TABLE"));
    }

    @Test
    void admitsNonMatchingRequest() {
        var policy = new DenyListPolicy("sql-block", "DROP TABLE");
        assertInstanceOf(PolicyDecision.Admit.class,
                policy.evaluate(req("tell me about databases generally")));
    }

    @Test
    void multiplePhrasesAllBlock() {
        var policy = new DenyListPolicy("multi", "DROP TABLE", "rm -rf", "sudo shutdown");
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(req("please rm -rf /")));
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(req("sudo shutdown now")));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(req("how are you")));
    }

    @Test
    void phrasesAreTreatedAsLiteralNotRegex() {
        // "a.b" as a literal should only match "a.b" verbatim, not "axb".
        var policy = new DenyListPolicy("literal", "a.b");
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(req("axb")),
                "literal phrase must NOT be interpreted as regex");
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(req("blah a.b blah")));
    }

    @Test
    void regexBuilderCompilesPatterns() {
        var policy = DenyListPolicy.fromRegex("ssn",
                "\\b\\d{3}-\\d{2}-\\d{4}\\b");
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(req("my ssn is 123-45-6789")));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(req("my zip is 94103")));
    }

    @Test
    void postResponseAlsoScreened() {
        var policy = new DenyListPolicy("leak", "SECRET_TOKEN");
        assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(postResp("here is the SECRET_TOKEN abc123")));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(postResp("safe output")));
    }

    @Test
    void emptyMessageOrResponseAdmits() {
        var policy = new DenyListPolicy("any", "foo");
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(req("")));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(postResp("")));
    }

    @Test
    void nullPhraseIgnoredInVarargs() {
        var policy = new DenyListPolicy("ok", "real-phrase", null, "");
        // Only the valid phrase should land in the matcher.
        assertTrue(policy.patternStrings().size() == 1);
    }

    @Test
    void emptyPhraseListRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new DenyListPolicy("no-phrases"));
        assertThrows(IllegalArgumentException.class,
                () -> new DenyListPolicy("all-blanks", "", null));
    }

    @Test
    void blankNameRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new DenyListPolicy("", "foo"));
    }

    @Test
    void nullPatternsRejected() {
        assertThrows(NullPointerException.class,
                () -> new DenyListPolicy("p", "code:x", "1", null));
    }

    @Test
    void patternStringsExposed() {
        var policy = new DenyListPolicy("sql-block", "DROP TABLE", "TRUNCATE");
        var strings = policy.patternStrings();
        assertTrue(strings.contains("\\QDROP TABLE\\E"));
        assertTrue(strings.contains("\\QTRUNCATE\\E"));
    }

    @Test
    void regexListConstructorDirect() {
        var policy = new DenyListPolicy("direct", "code:test", "1",
                List.of(java.util.regex.Pattern.compile("x.y", java.util.regex.Pattern.CASE_INSENSITIVE)));
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(req("x1y"))); // x.y matches x1y
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(req("zzz")));
    }
}
