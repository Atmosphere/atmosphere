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
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreferencePolicyTest {

    private static PolicyContext pre(String msg) {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest(msg, null, null, null, null, null, null, null, null), "");
    }

    private static PolicyContext post(String msg) {
        return new PolicyContext(PolicyContext.Phase.POST_RESPONSE,
                new AiRequest(msg, null, null, null, null, null, null, null, null), "response");
    }

    private static PreferencePolicy leastPrivilege() {
        return new PreferencePolicy("least-privilege", "code:test", "1",
                List.of(Pattern.compile(Pattern.quote("standing admin"), Pattern.CASE_INSENSITIVE)),
                "request a scoped, time-boxed credential for the single function",
                "standing admin grants violate least-privilege for this ticket type");
    }

    @Test
    void matchYieldsPreferWithAlternativeAndReason() {
        var prefer = assertInstanceOf(PolicyDecision.Prefer.class,
                leastPrivilege().evaluate(pre("grant STANDING ADMIN on the billing app")));
        assertEquals("request a scoped, time-boxed credential for the single function",
                prefer.preferred());
        assertEquals("standing admin grants violate least-privilege for this ticket type",
                prefer.reason());
    }

    @Test
    void nonMatchAdmits() {
        assertInstanceOf(PolicyDecision.Admit.class,
                leastPrivilege().evaluate(pre("grant a read-only scoped token")));
    }

    @Test
    void postResponseAlwaysAdmitsEvenWhenItWouldMatch() {
        // A preference steers the next action; after the response has streamed there is
        // nothing to advise, so the post-response phase must admit.
        assertInstanceOf(PolicyDecision.Admit.class,
                leastPrivilege().evaluate(post("granting standing admin now")));
    }

    @Test
    void blankMessageAdmits() {
        assertInstanceOf(PolicyDecision.Admit.class, leastPrivilege().evaluate(pre("   ")));
    }

    @Test
    void requiresAtLeastOnePattern() {
        assertThrows(IllegalArgumentException.class, () -> new PreferencePolicy(
                "p", "code:test", "1", List.of(), "alt", "why"));
    }

    @Test
    void requiresNonBlankPreferredAndReason() {
        var patterns = List.of(Pattern.compile("x"));
        assertThrows(IllegalArgumentException.class, () -> new PreferencePolicy(
                "p", "code:test", "1", patterns, "  ", "why"));
        assertThrows(IllegalArgumentException.class, () -> new PreferencePolicy(
                "p", "code:test", "1", patterns, "alt", "  "));
    }

    @Test
    void registryBuildsPreferenceType() {
        var registry = new PolicyRegistry();
        var descriptor = new PolicyRegistry.PolicyDescriptor(
                "least-privilege", "preference", "1", "yaml:test",
                Map.of("phrases", List.of("standing admin"),
                        "prefer", "request a scoped credential",
                        "reason", "least-privilege"));
        var policy = registry.build(descriptor);
        var prefer = assertInstanceOf(PolicyDecision.Prefer.class,
                policy.evaluate(pre("please grant standing admin")));
        assertEquals("request a scoped credential", prefer.preferred());
    }

    @Test
    void registryRejectsPreferenceWithoutPreferOrReason() {
        var registry = new PolicyRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.build(
                new PolicyRegistry.PolicyDescriptor("p", "preference", "1", "yaml:test",
                        Map.of("phrases", List.of("standing admin")))));
        assertThrows(IllegalArgumentException.class, () -> registry.build(
                new PolicyRegistry.PolicyDescriptor("p", "preference", "1", "yaml:test",
                        Map.of("prefer", "x", "reason", "y"))));
    }
}
