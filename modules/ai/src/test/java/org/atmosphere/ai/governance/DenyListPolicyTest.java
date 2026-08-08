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
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // The reason must NOT leak the matched rule back to the requester, and
        // must NOT surface Pattern.quote()'s \Q..\E artifacts.
        assertFalse(deny.reason().contains("DROP TABLE"), "deny reason must not echo the rule");
        assertFalse(deny.reason().contains("\\Q"), "deny reason must not leak \\Q..\\E quoting");
    }

    @Test
    void regexReasonDoesNotLeakPattern() {
        // Regression: the deny reason once echoed the compiled regex source
        // verbatim (e.g. '(?i)\b(password|api[- ]?key)\b'), both ugly and a
        // filter-enumeration leak.
        var policy = DenyListPolicy.fromRegex("secret", "(?i)\\b(password|api[- ]?key)\\b");
        var deny = assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(req("remind me of my password please")));
        assertTrue(deny.reason().contains("deny-list"));
        assertFalse(deny.reason().contains("password"), "deny reason must not echo the regex");
        assertFalse(deny.reason().contains("(?i)"), "deny reason must not leak the regex source");
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
        // Literals render un-quoted for admin surfaces (no \Q..\E envelope).
        assertTrue(strings.contains("DROP TABLE"));
        assertTrue(strings.contains("TRUNCATE"));
        assertFalse(strings.stream().anyMatch(s -> s.contains("\\Q")));
    }

    /**
     * Admit/deny a tool call through the real admission seam. Deliberately NOT
     * a hand-built context: the bug was that the seam's synthetic message is
     * {@code "call_tool:<name>"} with the payload in metadata, so any test that
     * stuffs arguments into the message passes against the broken policy.
     */
    private static PolicyAdmissionGate.Result callTool(DenyListPolicy policy,
                                                       String toolName,
                                                       Map<String, Object> args) {
        return PolicyAdmissionGate.admitToolCall(List.<GovernancePolicy>of(policy), toolName, args);
    }

    @Test
    void toolCallArgumentMatchingIsDenied() {
        // Regression: an argument-oriented deny-list was inert — the policy read
        // only request.message(), which on this seam never contains an argument.
        var policy = DenyListPolicy.fromRegex("mcp-arg-deny-list",
                "(?i)\\bDROP\\s+TABLE\\b", "(?i)\\brm\\s+-rf\\s+/", "\\.\\./\\.\\./");

        assertInstanceOf(PolicyAdmissionGate.Result.Denied.class,
                callTool(policy, "broadcast_message", Map.of("body", "'; DROP TABLE users;'")));
        assertInstanceOf(PolicyAdmissionGate.Result.Denied.class,
                callTool(policy, "broadcast_message", Map.of("body", "please rm -rf / now")));
        assertInstanceOf(PolicyAdmissionGate.Result.Denied.class,
                callTool(policy, "read_file", Map.of("path", "../../etc/passwd")));
        assertInstanceOf(PolicyAdmissionGate.Result.Admitted.class,
                callTool(policy, "broadcast_message", Map.of("body", "maintenance at 10pm")));
    }

    @Test
    void toolCallDenyReasonNamesTheArgumentWithoutEchoingIt() {
        var policy = DenyListPolicy.fromRegex("mcp-arg-deny-list", "(?i)\\bDROP\\s+TABLE\\b");
        var denied = assertInstanceOf(PolicyAdmissionGate.Result.Denied.class,
                callTool(policy, "broadcast_message", Map.of("body", "'; DROP TABLE users;'")));
        assertTrue(denied.reason().contains("tool argument"),
                "operator needs to know it was an argument, not the prompt: " + denied.reason());
        assertFalse(denied.reason().contains("DROP TABLE"), "deny reason must not echo the rule");
    }

    @Test
    void nestedAndListArgumentsAreScreened() {
        var policy = DenyListPolicy.fromRegex("nested", "(?i)\\bDROP\\s+TABLE\\b");
        assertInstanceOf(PolicyAdmissionGate.Result.Denied.class,
                callTool(policy, "run", Map.of("payload",
                        Map.of("statements", List.of("SELECT 1", "DROP TABLE users")))));
    }

    @Test
    void argumentKeysAreScreenedNotJustValues() {
        // On tools/call the client names the JSON fields too, so a key is just
        // as attacker-controlled as a value.
        var policy = DenyListPolicy.fromRegex("keys", "(?i)\\bDROP\\s+TABLE\\b");
        assertInstanceOf(PolicyAdmissionGate.Result.Denied.class,
                callTool(policy, "run", Map.of("DROP TABLE users", "1")));
    }

    @Test
    void argumentsAreScreenedIndividuallyNotAsOneRendering() {
        // Matching the map's toString() would let a rule straddle two unrelated
        // arguments and fire on the rendering's own punctuation.
        var policy = DenyListPolicy.fromRegex("straddle", "(?i)alpha,\\s*beta");
        var args = new java.util.LinkedHashMap<String, Object>();
        args.put("first", "alpha");
        args.put("second", "beta");
        assertInstanceOf(PolicyAdmissionGate.Result.Admitted.class,
                callTool(policy, "run", args),
                "a rule must not match across two separate arguments");
    }

    @Test
    @Timeout(10)
    void cyclicArgumentGraphTerminates() {
        // A programmatically-built cycle must not spin or blow the stack — a
        // StackOverflowError would escape the gate's fail-closed catch(Exception).
        var policy = DenyListPolicy.fromRegex("cycle", "(?i)\\bDROP\\s+TABLE\\b");
        var cyclic = new HashMap<String, Object>();
        cyclic.put("self", cyclic);
        cyclic.put("leaf", "harmless");
        assertInstanceOf(PolicyAdmissionGate.Result.Admitted.class,
                callTool(policy, "run", Map.of("nested", cyclic)));
    }

    @Test
    void oversizedArgumentGraphDeniesRatherThanSkippingTheTail() {
        // Fail closed: stopping the scan silently would let a payload be buried
        // past the budget, which is the same inert-rule bug in a new shape.
        var policy = DenyListPolicy.fromRegex("budget", "(?i)\\bDROP\\s+TABLE\\b");
        var denied = assertInstanceOf(PolicyAdmissionGate.Result.Denied.class,
                callTool(policy, "run", Map.of("padding", "x".repeat((1 << 20) + 1))));
        assertTrue(denied.reason().contains("budget"),
                "denial must name the budget, not a phantom rule match: " + denied.reason());
    }

    @Test
    void ordinaryTurnsWithoutToolArgsAreUnaffected() {
        var policy = new DenyListPolicy("sql-block", "DROP TABLE");
        assertInstanceOf(PolicyDecision.Admit.class,
                policy.evaluate(new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                        new AiRequest("how do databases work", null, null, null, null, null, null,
                                Map.of("temperature", 0.7), null),
                        "")));
    }

    @Test
    void regexListConstructorDirect() {
        var policy = new DenyListPolicy("direct", "code:test", "1",
                List.of(java.util.regex.Pattern.compile("x.y", java.util.regex.Pattern.CASE_INSENSITIVE)));
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(req("x1y"))); // x.y matches x1y
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(req("zzz")));
    }
}
