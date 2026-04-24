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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyRegistryBuiltinsTest {

    private final PolicyRegistry registry = new PolicyRegistry();

    private static PolicyContext req(String msg) {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest(msg, null, null, null, null, null, null, null, null),
                "");
    }

    @Test
    void denyListFactoryAcceptsPhrases() {
        var policy = registry.build(new PolicyRegistry.PolicyDescriptor(
                "sql-block", "deny-list", "1", "yaml:test",
                Map.of("phrases", List.of("DROP TABLE", "TRUNCATE"))));
        assertInstanceOf(DenyListPolicy.class, policy);
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(req("drop table x")));
    }

    @Test
    void denyListFactoryAcceptsRegex() {
        var policy = registry.build(new PolicyRegistry.PolicyDescriptor(
                "ssn", "deny-list", "1", "yaml:test",
                Map.of("regex", List.of("\\b\\d{3}-\\d{2}-\\d{4}\\b"))));
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(req("ssn 123-45-6789")));
    }

    @Test
    void denyListFactoryRequiresEitherField() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.build(new PolicyRegistry.PolicyDescriptor(
                        "x", "deny-list", "1", "yaml:test", Map.of())));
    }

    @Test
    void allowListFactoryAcceptsPhrases() {
        var policy = registry.build(new PolicyRegistry.PolicyDescriptor(
                "topics", "allow-list", "1", "yaml:test",
                Map.of("phrases", List.of("order", "refund"))));
        assertInstanceOf(AllowListPolicy.class, policy);
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(req("my order status")));
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(req("random small talk")));
    }

    @Test
    void messageLengthFactoryCapsAtThreshold() {
        var policy = registry.build(new PolicyRegistry.PolicyDescriptor(
                "cap-10", "message-length", "1", "yaml:test",
                Map.of("max-chars", 10)));
        assertInstanceOf(MessageLengthPolicy.class, policy);
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(req("short")));
        var deny = assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(req("way too long message text")));
        assertTrue(deny.reason().contains("exceeds maximum"));
    }

    @Test
    void messageLengthFactoryRequiresPositiveMaxChars() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.build(new PolicyRegistry.PolicyDescriptor(
                        "cap", "message-length", "1", "yaml:test", Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> registry.build(new PolicyRegistry.PolicyDescriptor(
                        "cap", "message-length", "1", "yaml:test",
                        Map.of("max-chars", 0))));
    }

    @Test
    void registryKnowsAllNewTypes() {
        assertTrue(registry.has("deny-list"));
        assertTrue(registry.has("allow-list"));
        assertTrue(registry.has("message-length"));
        assertTrue(registry.has("rate-limit"));
        assertTrue(registry.has("concurrency-limit"));
        assertTrue(registry.has("time-window"));
        assertTrue(registry.has("metadata-presence"));
    }

    @Test
    void rateLimitFactoryBuildsWorkingPolicy() {
        var policy = registry.build(new PolicyRegistry.PolicyDescriptor(
                "rl", "rate-limit", "1", "yaml:test",
                Map.of("limit", 2, "window-seconds", 60)));
        assertInstanceOf(RateLimitPolicy.class, policy);

        var aliceCtx = new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest("msg", null, null, "alice", null, null, null, null, null), "");
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(aliceCtx));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(aliceCtx));
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(aliceCtx));
    }

    @Test
    void rateLimitFactoryRequiresBothFields() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.build(new PolicyRegistry.PolicyDescriptor(
                        "x", "rate-limit", "1", "yaml:test", Map.of("limit", 5))));
        assertThrows(IllegalArgumentException.class,
                () -> registry.build(new PolicyRegistry.PolicyDescriptor(
                        "x", "rate-limit", "1", "yaml:test", Map.of("window-seconds", 60))));
    }

    @Test
    void concurrencyLimitFactoryBuildsWorkingPolicy() {
        var policy = registry.build(new PolicyRegistry.PolicyDescriptor(
                "cc", "concurrency-limit", "1", "yaml:test",
                Map.of("max-concurrent", 1)));
        assertInstanceOf(ConcurrencyLimitPolicy.class, policy);
    }

    @Test
    void timeWindowFactoryAcceptsIso8601TimesAndDayList() {
        var policy = registry.build(new PolicyRegistry.PolicyDescriptor(
                "bh", "time-window", "1", "yaml:test",
                Map.of("start", "09:00", "end", "17:00",
                        "days", List.of("MONDAY", "Tuesday", "friday"),
                        "zone", "UTC")));
        assertInstanceOf(TimeWindowPolicy.class, policy);
        var tw = (TimeWindowPolicy) policy;
        assertTrue(tw.days().contains(java.time.DayOfWeek.MONDAY));
        assertTrue(tw.days().contains(java.time.DayOfWeek.TUESDAY));
        assertTrue(tw.days().contains(java.time.DayOfWeek.FRIDAY));
    }

    @Test
    void timeWindowFactoryDefaultsToMondayFridayBusinessDays() {
        var policy = registry.build(new PolicyRegistry.PolicyDescriptor(
                "bh", "time-window", "1", "yaml:test",
                Map.of("start", "09:00", "end", "17:00")));
        var tw = (TimeWindowPolicy) policy;
        assertEquals(5, tw.days().size());
    }

    @Test
    void authorizationFactoryBuildsWorkingPolicy() {
        var policy = registry.build(new PolicyRegistry.PolicyDescriptor(
                "admin-only", "authorization", "1", "yaml:test",
                Map.of("required-roles", List.of("admin"))));
        assertInstanceOf(AuthorizationPolicy.class, policy);

        var ctxWithAdmin = new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest("m", null, null, null, null, null, null,
                        Map.of("roles", "admin,user"), null), "");
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(ctxWithAdmin));

        var ctxWithoutAdmin = new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest("m", null, null, null, null, null, null,
                        Map.of("roles", "user"), null), "");
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(ctxWithoutAdmin));
    }

    @Test
    void authorizationFactoryRequiresRoles() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.build(new PolicyRegistry.PolicyDescriptor(
                        "x", "authorization", "1", "yaml:test", Map.of())));
    }

    @Test
    void metadataPresenceFactoryRequiresKeys() {
        var policy = registry.build(new PolicyRegistry.PolicyDescriptor(
                "attribution", "metadata-presence", "1", "yaml:test",
                Map.of("required-keys", List.of("tenant-id", "trace-id"))));
        assertInstanceOf(MetadataPresencePolicy.class, policy);

        assertThrows(IllegalArgumentException.class,
                () -> registry.build(new PolicyRegistry.PolicyDescriptor(
                        "bad", "metadata-presence", "1", "yaml:test", Map.of())));
    }
}
