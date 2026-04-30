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
package org.atmosphere.ai.tool;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the governance-deny payload produced by
 * {@link ToolExecutionHelper}. The previous implementation built the JSON
 * via raw string concatenation and only escaped double quotes in
 * {@code reason}, so backslashes, newlines, tabs, and other control
 * characters in tool / policy names or reasons could break downstream
 * parsing. The fix routes every interpolated field through
 * {@link ToolBridgeUtils#escapeJson}; this test pins that contract so the
 * lossy escape can't reappear.
 */
class ToolExecutionHelperGovernanceJsonTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void simpleDenyProducesParseableJson() {
        String payload = ToolExecutionHelper.buildGovernanceDenyJson(
                "list_rows", "deny-pii", "tool not allowed in scope");
        JsonNode node = MAPPER.readTree(payload);
        assertEquals("cancelled", node.get("status").stringValue());
        String message = node.get("message").stringValue();
        assertTrue(message.contains("list_rows"));
        assertTrue(message.contains("deny-pii"));
        assertTrue(message.contains("tool not allowed in scope"));
    }

    @Test
    void doubleQuoteInReasonStaysParseable() {
        String payload = ToolExecutionHelper.buildGovernanceDenyJson(
                "send_email", "no-pii",
                "Reason has a \"quoted\" phrase");
        JsonNode node = MAPPER.readTree(payload);
        // If the escape was missing, readTree would throw before reaching here.
        assertNotNull(node);
        assertTrue(node.get("message").stringValue().contains("\"quoted\""));
    }

    @Test
    void backslashInPolicyNameStaysParseable() {
        // The classic break case for the pre-fix replace("\"", "\\\"")
        // approach: a literal backslash next to a quote produces an
        // unbalanced escape sequence and yields invalid JSON.
        String payload = ToolExecutionHelper.buildGovernanceDenyJson(
                "exec", "policy\\name", "reason");
        JsonNode node = MAPPER.readTree(payload);
        assertNotNull(node);
        assertTrue(node.get("message").stringValue().contains("policy\\name"));
    }

    @Test
    void newlineInReasonStaysParseable() {
        // Raw newlines in JSON string values are illegal — Jackson rejects
        // them outright. Pre-fix concatenation would have produced a
        // malformed payload here.
        String payload = ToolExecutionHelper.buildGovernanceDenyJson(
                "exec", "no-rce",
                "line one\nline two\rwith carriage return");
        JsonNode node = MAPPER.readTree(payload);
        assertNotNull(node);
        String message = node.get("message").stringValue();
        assertTrue(message.contains("\n"));
        assertTrue(message.contains("\r"));
    }

    @Test
    void tabInReasonStaysParseable() {
        String payload = ToolExecutionHelper.buildGovernanceDenyJson(
                "exec", "no-rce", "field\tvalue");
        JsonNode node = MAPPER.readTree(payload);
        assertEquals("cancelled", node.get("status").stringValue());
        assertTrue(node.get("message").stringValue().contains("\t"));
    }

    @Test
    void nullInputsProduceParseableJsonWithEmptyFields() {
        // Defensive — the production path passes denied.policyName() and
        // .reason() which are non-null in practice, but the helper must
        // not NPE on edge cases (e.g., evolving Denied record shape).
        String payload = ToolExecutionHelper.buildGovernanceDenyJson(null, null, null);
        JsonNode node = MAPPER.readTree(payload);
        assertNotNull(node.get("status"));
        assertNotNull(node.get("message"));
    }
}
