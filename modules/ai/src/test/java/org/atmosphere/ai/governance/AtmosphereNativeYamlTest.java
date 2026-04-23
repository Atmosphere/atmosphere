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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end YAML conformance for the new Atmosphere-native policy types —
 * parses a single document containing metadata-presence, deny-list,
 * allow-list, message-length, rate-limit, concurrency-limit, time-window,
 * and pii-redaction entries, asserting each produces the expected
 * {@link GovernancePolicy} subclass.
 */
class AtmosphereNativeYamlTest {

    private static final String YAML = """
            version: "1.0"
            policies:
              - name: attribution-required
                type: metadata-presence
                version: "1"
                config:
                  required-keys: [tenant-id, trace-id]
              - name: sql-deny-list
                type: deny-list
                version: "1"
                config:
                  phrases: [DROP TABLE, TRUNCATE]
              - name: support-topics
                type: allow-list
                version: "1"
                config:
                  phrases: [order, billing, refund]
              - name: msg-cap
                type: message-length
                version: "1"
                config:
                  max-chars: 4096
              - name: per-user-rate
                type: rate-limit
                version: "1"
                config:
                  limit: 30
                  window-seconds: 60
              - name: per-user-concurrency
                type: concurrency-limit
                version: "1"
                config:
                  max-concurrent: 3
              - name: business-hours
                type: time-window
                version: "1"
                config:
                  start: "09:00"
                  end: "17:00"
                  zone: UTC
                  days: [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY]
              - name: pii
                type: pii-redaction
                version: "1"
                config:
                  mode: redact
            """;

    @Test
    void parsesEntireChainFromAtmosphereNativeYaml() throws Exception {
        var parser = new YamlPolicyParser();
        List<GovernancePolicy> policies;
        try (var in = new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8))) {
            policies = parser.parse("classpath:atmosphere-native.yaml", in);
        }
        assertEquals(8, policies.size(), "all eight type entries must parse");

        // The types should resolve to the expected policy classes.
        assertInstanceOf(MetadataPresencePolicy.class, findByName(policies, "attribution-required"));
        assertInstanceOf(DenyListPolicy.class, findByName(policies, "sql-deny-list"));
        assertInstanceOf(AllowListPolicy.class, findByName(policies, "support-topics"));
        assertInstanceOf(MessageLengthPolicy.class, findByName(policies, "msg-cap"));
        assertInstanceOf(RateLimitPolicy.class, findByName(policies, "per-user-rate"));
        assertInstanceOf(ConcurrencyLimitPolicy.class, findByName(policies, "per-user-concurrency"));
        assertInstanceOf(TimeWindowPolicy.class, findByName(policies, "business-hours"));
    }

    @Test
    void configValuesPropagateIntoBuiltPolicy() throws Exception {
        var parser = new YamlPolicyParser();
        List<GovernancePolicy> policies;
        try (var in = new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8))) {
            policies = parser.parse("classpath:atmosphere-native.yaml", in);
        }
        var cap = (MessageLengthPolicy) findByName(policies, "msg-cap");
        assertEquals(4096, cap.maxChars());

        var rate = (RateLimitPolicy) findByName(policies, "per-user-rate");
        assertEquals(30, rate.limit());
        assertEquals(60, rate.window().toSeconds());

        var concurrency = (ConcurrencyLimitPolicy) findByName(policies, "per-user-concurrency");
        assertEquals(3, concurrency.maxConcurrent());
    }

    @Test
    void allPoliciesCarryDocumentIdentity() throws Exception {
        var parser = new YamlPolicyParser();
        List<GovernancePolicy> policies;
        try (var in = new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8))) {
            policies = parser.parse("yaml:/etc/policies.yaml", in);
        }
        var sources = policies.stream().map(GovernancePolicy::source).collect(Collectors.toSet());
        assertEquals(1, sources.size(), "every policy carries the same source URI");
        assertTrue(sources.contains("yaml:/etc/policies.yaml"));
    }

    private static GovernancePolicy findByName(List<GovernancePolicy> policies, String name) {
        return policies.stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("policy not found: " + name));
    }
}
