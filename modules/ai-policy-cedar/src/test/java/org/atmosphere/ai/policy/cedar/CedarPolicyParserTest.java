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
package org.atmosphere.ai.policy.cedar;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end Cedar parser/policy wiring against a stub authorizer.
 * Network-free and cedar-CLI-free so CI runs without external tooling.
 */
class CedarPolicyParserTest {

    private static final String CEDAR_ALLOW = """
            @id("support-billing-agent")
            permit(
                principal == User::"42",
                action == Action::"invoke",
                resource == Agent::"billing-agent"
            );
            """;

    @Test
    void parsesIdAnnotationAsPolicyName() throws IOException {
        var stub = new StubAuthorizer(CedarAuthorizer.Result.allow(List.of("support-billing-agent")));
        var parser = new CedarPolicyParser(stub);
        var policies = parser.parse("cedar:support.cedar", streamOf(CEDAR_ALLOW));
        assertEquals(1, policies.size());
        assertEquals("support-billing-agent", policies.get(0).name());
    }

    @Test
    void parserFallsBackToSourceNameWhenNoIdAnnotation() throws IOException {
        var stub = new StubAuthorizer(CedarAuthorizer.Result.allow(List.of()));
        var noId = "permit(principal, action, resource);";
        var policies = new CedarPolicyParser(stub)
                .parse("cedar:/etc/atmosphere/default.cedar", streamOf(noId));
        assertEquals("cedar:default", policies.get(0).name());
    }

    @Test
    void allowResultProducesAdmit() throws IOException {
        var stub = new StubAuthorizer(CedarAuthorizer.Result.allow(List.of("support-billing-agent")));
        var policy = new CedarPolicyParser(stub)
                .parse("cedar:test.cedar", streamOf(CEDAR_ALLOW))
                .get(0);
        var decision = policy.evaluate(PolicyContext.preAdmission(new AiRequest(
                "hi", "", null, "42", null, "billing-agent", null,
                Map.of(), List.of())));
        assertInstanceOf(PolicyDecision.Admit.class, decision);
    }

    @Test
    void denyResultProducesDenyWithReason() throws IOException {
        var stub = new StubAuthorizer(CedarAuthorizer.Result.deny(
                "no matching permit",
                List.of()));
        var policy = new CedarPolicyParser(stub)
                .parse("cedar:test.cedar", streamOf(CEDAR_ALLOW))
                .get(0);
        var decision = policy.evaluate(PolicyContext.preAdmission(new AiRequest("hi")));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, decision);
        assertEquals("no matching permit", deny.reason());
    }

    @Test
    void requestMappingProducesCedarEntityRefs() throws IOException {
        var capturedPrincipal = new AtomicReference<String>();
        var capturedResource = new AtomicReference<String>();
        var capturedContext = new AtomicReference<Map<String, Object>>();
        var capturing = new CedarAuthorizer() {
            @Override
            public Result authorize(String cedarSource, String principal,
                                     String action, String resource,
                                     Map<String, Object> context) {
                capturedPrincipal.set(principal);
                capturedResource.set(resource);
                capturedContext.set(context);
                return Result.allow(List.of());
            }
        };
        var policy = new CedarPolicyParser(capturing)
                .parse("cedar:test.cedar", streamOf(CEDAR_ALLOW))
                .get(0);
        policy.evaluate(PolicyContext.preAdmission(new AiRequest(
                "what is my balance", "",
                "gpt-4o", "user-42", "sess-1", "billing-agent", "conv-9",
                Map.of("tool_name", "search"), List.of())));

        assertEquals("User::\"user-42\"", capturedPrincipal.get());
        assertEquals("Agent::\"billing-agent\"", capturedResource.get());
        var ctx = capturedContext.get();
        assertNotNull(ctx);
        assertEquals("what is my balance", ctx.get("message"));
        assertEquals("search", ctx.get("tool_name"));
        assertEquals("pre_admission", ctx.get("phase"));
    }

    @Test
    void authorizerRuntimeErrorFailsClosed() throws IOException {
        var throwing = new CedarAuthorizer() {
            @Override
            public Result authorize(String s, String p, String a, String r,
                                     Map<String, Object> c) {
                throw new RuntimeException("cedar not installed");
            }
        };
        var policy = new CedarPolicyParser(throwing)
                .parse("cedar:test.cedar", streamOf(CEDAR_ALLOW))
                .get(0);
        var decision = policy.evaluate(PolicyContext.preAdmission(new AiRequest("hi")));
        assertInstanceOf(PolicyDecision.Deny.class, decision);
    }

    @Test
    void parserFormatIsCedar() {
        assertEquals("cedar", new CedarPolicyParser(
                new StubAuthorizer(CedarAuthorizer.Result.allow(List.of()))).format());
    }

    @Test
    void parseResultAllowDecodes() {
        var json = "{\"decision\":\"Allow\",\"diagnostics\":{\"reason\":[\"p1\"],\"errors\":[]}}";
        var result = CedarCliAuthorizer.parseResult(json);
        assertTrue(result.allowed());
        assertEquals(List.of("p1"), result.matchedPolicies());
    }

    @Test
    void parseResultDenyDecodes() {
        var json = "{\"decision\":\"Deny\",\"diagnostics\":{\"reason\":[]}}";
        var result = CedarCliAuthorizer.parseResult(json);
        assertEquals(false, result.allowed());
    }

    @Test
    void parseResultMissingDecisionDenies() {
        var result = CedarCliAuthorizer.parseResult("{\"unrelated\":true}");
        assertEquals(false, result.allowed());
    }

    @Test
    void buildRequestJsonShapeMatchesCedarCli() {
        var json = CedarCliAuthorizer.buildRequestJson(
                "User::\"42\"", "Action::\"invoke\"", "Agent::\"billing\"",
                Map.of("scope", "read"));
        assertTrue(json.contains("\"principal\":\"User::\\\"42\\\"\""),
                "principal must be JSON-escaped: " + json);
        assertTrue(json.contains("\"action\":\"Action::\\\"invoke\\\"\""));
        assertTrue(json.contains("\"resource\":\"Agent::\\\"billing\\\"\""));
        assertTrue(json.contains("\"context\":"));
        assertTrue(json.contains("\"scope\":\"read\""));
    }

    private static ByteArrayInputStream streamOf(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private record StubAuthorizer(CedarAuthorizer.Result fixed) implements CedarAuthorizer {
        @Override
        public Result authorize(String source, String principal, String action,
                                 String resource, Map<String, Object> context) {
            return fixed;
        }
    }
}
