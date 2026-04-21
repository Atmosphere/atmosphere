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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link YamlPolicyParser} accepts Microsoft Agent Governance
 * Toolkit YAML artifacts byte-for-byte. The fixtures under
 * {@code modules/ai/src/test/resources/ms-agent-os/} are copied verbatim from
 * the April 2026 MS source
 * ({@code docs/tutorials/policy-as-code/examples/*.yaml}) — updating the MS
 * side upstream and not updating our copy will cause a test drift, which is
 * the point: prove interop is real, not aspirational.
 */
class MsAgentOsYamlConformanceTest {

    @Test
    void parsesFirstPolicyExampleVerbatim() throws IOException {
        var policies = parse("ms-agent-os/01_first_policy.yaml");
        assertEquals(1, policies.size(), "one synthetic policy per MS document");
        var policy = assertInstanceOf(MsAgentOsPolicy.class, policies.get(0));
        assertEquals("my-first-policy", policy.name());
        assertEquals("1.0", policy.version());
        assertEquals(2, policy.rules().size());
        // Priority-sorted descending per MS semantic — highest first.
        assertEquals(100, policy.rules().get(0).priority());
        assertEquals("block-delete-database", policy.rules().get(0).name());
        assertEquals(MsAgentOsPolicy.Action.ALLOW, policy.defaultAction());
    }

    @Test
    void firstPolicyDeniesDeleteDatabaseToolCall() throws IOException {
        var policy = parseSingle("ms-agent-os/01_first_policy.yaml");
        // MS rules reference `tool_name`; metadata carries it through.
        var request = requestWithMetadata("query", Map.of("tool_name", "delete_database"));

        var decision = policy.evaluate(PolicyContext.preAdmission(request));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, decision);
        assertTrue(deny.reason().contains("Deleting databases"),
                "MS message copy flows through: " + deny.reason());
    }

    @Test
    void firstPolicyAdmitsUnmatchedToolCalls() throws IOException {
        var policy = parseSingle("ms-agent-os/01_first_policy.yaml");
        var request = requestWithMetadata("query", Map.of("tool_name", "search_documents"));

        var decision = policy.evaluate(PolicyContext.preAdmission(request));
        assertInstanceOf(PolicyDecision.Admit.class, decision,
                "defaults.action: allow — unmatched context allows");
    }

    @Test
    void rateLimitPolicyParsesAndRetainsDefaults() throws IOException {
        var policy = parseSingle("ms-agent-os/03_rate_limit_policy.yaml");
        assertEquals("rate-limit-policy", policy.name());
        assertEquals(MsAgentOsPolicy.Action.ALLOW, policy.defaultAction(),
                "defaults.action: allow (max_tool_calls is advisory, not executable here)");
    }

    @Test
    void productionPolicyEnforcesTieredActions() throws IOException {
        var policy = parseSingle("ms-agent-os/07_policy_v1.yaml");
        assertEquals(5, policy.rules().size());

        // Tier 1 (destructive) denied
        var deny = (PolicyDecision.Deny) policy.evaluate(PolicyContext.preAdmission(
                requestWithMetadata("drop stuff", Map.of("tool_name", "delete_database"))));
        assertTrue(deny.reason().contains("deleting databases"),
                "exact MS message surfaced: " + deny.reason());

        // Tier 2 (transfer_funds) also denied
        var deny2 = (PolicyDecision.Deny) policy.evaluate(PolicyContext.preAdmission(
                requestWithMetadata("x", Map.of("tool_name", "transfer_funds"))));
        assertTrue(deny2.reason().toLowerCase().contains("transfer"),
                "transfer_funds denied: " + deny2.reason());

        // Tier 3 (search_documents) explicitly allowed — highest priority ALLOW rule wins
        var admit = policy.evaluate(PolicyContext.preAdmission(
                requestWithMetadata("look", Map.of("tool_name", "search_documents"))));
        assertInstanceOf(PolicyDecision.Admit.class, admit);
    }

    @Test
    void operatorsMatchMicrosoftSemantics() throws IOException {
        // Hand-authored YAML exercising every operator we ported.
        var yaml = """
                version: "1.0"
                name: operator-coverage
                rules:
                  - name: eq-rule
                    condition: { field: model, operator: eq, value: gpt-4o }
                    action: deny
                    priority: 100
                    message: "eq"
                  - name: ne-rule
                    condition: { field: agent_id, operator: ne, value: trusted }
                    action: deny
                    priority: 90
                    message: "ne"
                  - name: gt-rule
                    condition: { field: token_count, operator: gt, value: 1000 }
                    action: deny
                    priority: 80
                    message: "gt"
                  - name: in-rule
                    condition: { field: tool_name, operator: in, value: [delete, drop, truncate] }
                    action: deny
                    priority: 70
                    message: "in"
                  - name: contains-rule
                    condition: { field: message, operator: contains, value: 'DROP TABLE' }
                    action: deny
                    priority: 60
                    message: "contains"
                  - name: matches-rule
                    condition: { field: message, operator: matches, value: '(?i)select\\s+\\*' }
                    action: deny
                    priority: 50
                    message: "matches"
                defaults: { action: allow }
                """;
        var policy = (MsAgentOsPolicy) new YamlPolicyParser()
                .parse("yaml:operator-test", asStream(yaml)).get(0);

        // eq: model == gpt-4o → deny
        assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(PolicyContext.preAdmission(requestWithModelAndAgent("x", "gpt-4o", null))));

        // ne: agent_id != trusted → deny (note: null agent_id → rule does not match)
        assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(PolicyContext.preAdmission(requestWithModelAndAgent("x", "other-model", "attacker"))));

        // gt: token_count > 1000 → deny (via metadata)
        assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(PolicyContext.preAdmission(
                        requestWithMetadata("x", Map.of("token_count", 1500)))));

        // in: tool_name in [delete, drop, truncate] → deny
        assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(PolicyContext.preAdmission(
                        requestWithMetadata("x", Map.of("tool_name", "drop")))));

        // contains: message contains 'DROP TABLE' → deny
        assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(PolicyContext.preAdmission(
                        new AiRequest("please DROP TABLE users"))));

        // matches: regex on message → deny
        assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(PolicyContext.preAdmission(new AiRequest("SELECT * FROM logs"))));
    }

    @Test
    void defaultsActionDenyAppliesWhenNoRuleMatches() throws IOException {
        var yaml = """
                version: "1.0"
                name: deny-by-default
                rules:
                  - name: allow-reader
                    condition: { field: user_id, operator: eq, value: reader }
                    action: allow
                    priority: 10
                defaults: { action: deny }
                """;
        var policy = (MsAgentOsPolicy) new YamlPolicyParser()
                .parse("yaml:default-deny", asStream(yaml)).get(0);

        var denied = policy.evaluate(PolicyContext.preAdmission(
                requestWithUser("hi", "stranger")));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, denied);
        assertTrue(deny.reason().contains("default action"),
                "default-deny surfaces in reason: " + deny.reason());

        var admitted = policy.evaluate(PolicyContext.preAdmission(
                requestWithUser("hi", "reader")));
        assertInstanceOf(PolicyDecision.Admit.class, admitted);
    }

    @Test
    void mixedSchemaDocumentRaises() {
        var yaml = """
                rules: []
                policies: []
                """;
        var parser = new YamlPolicyParser();
        var error = assertThrows(IOException.class,
                () -> parser.parse("yaml:mixed", asStream(yaml)));
        assertTrue(error.getMessage().contains("both 'rules' and 'policies'"));
    }

    @Test
    void invalidRegexInMatchesRaises() {
        var yaml = """
                name: bad-regex
                rules:
                  - name: broken
                    condition: { field: message, operator: matches, value: '[unterminated' }
                    action: deny
                """;
        var parser = new YamlPolicyParser();
        assertThrows(IOException.class, () -> parser.parse("yaml:bad-regex", asStream(yaml)));
    }

    @Test
    void unknownOperatorRaises() {
        var yaml = """
                name: unknown-op
                rules:
                  - name: x
                    condition: { field: message, operator: lolwut, value: 1 }
                    action: deny
                """;
        var parser = new YamlPolicyParser();
        var error = assertThrows(IOException.class,
                () -> parser.parse("yaml:bad-op", asStream(yaml)));
        assertTrue(error.getMessage().contains("lolwut"));
    }

    // --- helpers ---------------------------------------------------------

    private static List<GovernancePolicy> parse(String resource) throws IOException {
        try (InputStream in = resourceStream(resource)) {
            return new YamlPolicyParser().parse("classpath:" + resource, in);
        }
    }

    private static MsAgentOsPolicy parseSingle(String resource) throws IOException {
        var policies = parse(resource);
        assertEquals(1, policies.size(), "MS documents produce exactly one policy");
        return assertInstanceOf(MsAgentOsPolicy.class, policies.get(0));
    }

    private static InputStream resourceStream(String resource) {
        var in = MsAgentOsYamlConformanceTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, "missing test resource: " + resource);
        return in;
    }

    private static AiRequest requestWithMetadata(String message, Map<String, Object> metadata) {
        return new AiRequest(message, "", null, null, null, null, null, metadata, List.of());
    }

    private static AiRequest requestWithModelAndAgent(String message, String model, String agentId) {
        return new AiRequest(message, "", model, null, null, agentId, null, Map.of(), List.of());
    }

    private static AiRequest requestWithUser(String message, String userId) {
        return new AiRequest(message, "", null, userId, null, null, null, Map.of(), List.of());
    }

    private static ByteArrayInputStream asStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
