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
package org.atmosphere.ai.policy.rego;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end parser → policy → evaluator wiring. Uses a stub
 * {@link RegoEvaluator} so the test is network-free and OPA-binary-free
 * (CI runners don't necessarily have {@code opa} installed).
 */
class RegoPolicyParserTest {

    private static final String SUPPORT_REGO = """
            package atmosphere.governance.support
            default allow = false
            allow {
                input.agent_id == "billing-agent"
                input.message != ""
            }
            """;

    @Test
    void parsesPackageIntoPolicyName() throws IOException {
        var stub = new StubEvaluator(RegoEvaluator.Result.allow());
        var parser = new RegoPolicyParser(stub);
        var policies = parser.parse("rego:support.rego", streamOf(SUPPORT_REGO));

        assertEquals(1, policies.size());
        var policy = policies.get(0);
        assertEquals("atmosphere.governance.support", policy.name());
        assertEquals("rego:support.rego", policy.source());
        assertEquals("1.0", policy.version());
    }

    @Test
    void allowResultProducesAdmit() throws IOException {
        var stub = new StubEvaluator(RegoEvaluator.Result.allow());
        var policy = new RegoPolicyParser(stub)
                .parse("rego:test.rego", streamOf(SUPPORT_REGO))
                .get(0);

        var decision = policy.evaluate(PolicyContext.preAdmission(
                new AiRequest("hello", "", null, null, null, "billing-agent", null,
                        Map.of(), java.util.List.of())));
        assertInstanceOf(PolicyDecision.Admit.class, decision);
    }

    @Test
    void denyResultProducesDenyWithReason() throws IOException {
        var stub = new StubEvaluator(
                RegoEvaluator.Result.deny("tool not allowed at this hour", "hour_window"));
        var policy = new RegoPolicyParser(stub)
                .parse("rego:test.rego", streamOf(SUPPORT_REGO))
                .get(0);

        var decision = policy.evaluate(PolicyContext.preAdmission(new AiRequest("hi")));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, decision);
        assertEquals("tool not allowed at this hour", deny.reason());
    }

    @Test
    void inputDocumentCarriesFlattenedRequestFields() throws IOException {
        var capturedInput = new AtomicReference<Map<String, Object>>();
        var capturing = new RegoEvaluator() {
            @Override
            public Result evaluate(String rego, String query, Map<String, Object> input) {
                capturedInput.set(input);
                return Result.allow();
            }
        };
        var policy = new RegoPolicyParser(capturing)
                .parse("rego:test.rego", streamOf(SUPPORT_REGO))
                .get(0);
        policy.evaluate(PolicyContext.preAdmission(new AiRequest(
                "what is my balance", "",
                "gpt-4o", "user-42", "sess-1", "billing-agent", "conv-9",
                Map.of("tool_name", "search"), java.util.List.of())));

        var input = capturedInput.get();
        assertNotNull(input);
        assertEquals("what is my balance", input.get("message"));
        assertEquals("billing-agent", input.get("agent_id"));
        assertEquals("user-42", input.get("user_id"));
        assertEquals("gpt-4o", input.get("model"));
        assertEquals("search", input.get("tool_name"));
        assertEquals("pre_admission", input.get("phase"));
    }

    @Test
    void evaluatorRuntimeErrorFailsClosed() throws IOException {
        var throwing = new RegoEvaluator() {
            @Override
            public Result evaluate(String rego, String query, Map<String, Object> input) {
                throw new RuntimeException("OPA not installed");
            }
        };
        var policy = new RegoPolicyParser(throwing)
                .parse("rego:test.rego", streamOf(SUPPORT_REGO))
                .get(0);
        var decision = policy.evaluate(PolicyContext.preAdmission(new AiRequest("hi")));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, decision);
        assertTrue(deny.reason().contains("OPA not installed"),
                "fail-closed reason must propagate: " + deny.reason());
    }

    @Test
    void parserFormatIsRego() {
        assertEquals("rego", new RegoPolicyParser(new StubEvaluator(
                RegoEvaluator.Result.allow())).format());
    }

    @Test
    void parseResultHandlesBooleanValue() {
        // OPA eval shape: {"result": [{"expressions": [{"value": true, ...}]}]}
        assertEquals(RegoEvaluator.Result.allow(),
                OpaSubprocessEvaluator.parseResult(
                        "{\"result\": [{\"expressions\": [{\"value\": true}]}]}"));
    }

    @Test
    void parseResultHandlesAllowObjectWithReason() {
        var json = "{\"result\": [{\"expressions\": [{\"value\": "
                + "{\"allow\": false, \"reason\": \"off-hours\"}}]}]}";
        var result = OpaSubprocessEvaluator.parseResult(json);
        assertEquals(false, result.allowed());
        assertEquals("off-hours", result.reason());
    }

    @Test
    void parseResultMissingValueFieldDenies() {
        var result = OpaSubprocessEvaluator.parseResult("{\"unrelated\": true}");
        assertEquals(false, result.allowed());
    }

    @Test
    void toJsonHandlesNestedStructures() {
        var json = OpaSubprocessEvaluator.toJson(Map.of(
                "k", "v",
                "n", 42,
                "b", true,
                "nested", Map.of("inner", "x")));
        assertTrue(json.contains("\"k\":\"v\""));
        assertTrue(json.contains("\"n\":42"));
        assertTrue(json.contains("\"b\":true"));
        assertTrue(json.contains("\"inner\":\"x\""));
    }

    private static ByteArrayInputStream streamOf(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private record StubEvaluator(RegoEvaluator.Result fixed) implements RegoEvaluator {
        @Override
        public Result evaluate(String rego, String query, Map<String, Object> input) {
            return fixed;
        }
    }
}
