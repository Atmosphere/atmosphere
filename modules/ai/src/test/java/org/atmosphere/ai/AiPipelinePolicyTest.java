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
package org.atmosphere.ai;

import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.governance.YamlPolicyParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link AiPipeline}'s native {@link GovernancePolicy} wiring:
 * admission denies stop execution, transforms rewrite the request before the
 * runtime sees it, and post-response evaluation still fires through
 * {@code GuardrailCapturingSession} via {@code PolicyAsGuardrail}.
 */
class AiPipelinePolicyTest {

    @Test
    void policyDenialStopsExecution() {
        var executed = new AtomicBoolean(false);
        var runtime = new StubRuntime() {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                executed.set(true);
                session.complete();
            }
        };
        GovernancePolicy denyAll = new FixedPolicy("deny-all", PolicyDecision.deny("policy says no"));

        var pipeline = new AiPipeline(runtime, null, null, null, null,
                List.of(), List.of(denyAll), List.of(), null, null);
        var session = new CollectingSession("policy-deny");
        pipeline.execute("c1", "hello", session);

        assertFalse(executed.get(), "runtime must not execute when policy denies");
    }

    @Test
    void policyTransformRewritesRequestBeforeRuntime() {
        var captured = new AtomicReference<AgentExecutionContext>();
        var runtime = new StubRuntime() {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                captured.set(context);
                session.complete();
            }
        };
        var rewritten = new AiRequest("[REDACTED]", "sys", null, null, "c1", null, "c1",
                java.util.Map.of(), List.of());
        GovernancePolicy redactor = new FixedPolicy("redactor", PolicyDecision.transform(rewritten));

        var pipeline = new AiPipeline(runtime, "sys", null, null, null,
                List.of(), List.of(redactor), List.of(), null, null);
        var session = new CollectingSession("policy-transform");
        pipeline.execute("c1", "leak@x.com", session);

        assertNotNull(captured.get());
        assertEquals("[REDACTED]", captured.get().message());
    }

    @Test
    void policyExceptionIsFailClosed() {
        var executed = new AtomicBoolean(false);
        var runtime = new StubRuntime() {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                executed.set(true);
                session.complete();
            }
        };
        GovernancePolicy boom = new FixedPolicy("boom",
                (PolicyDecision) null) {
            @Override
            public PolicyDecision evaluate(PolicyContext ctx) {
                throw new RuntimeException("oops");
            }
        };

        var pipeline = new AiPipeline(runtime, null, null, null, null,
                List.of(), List.of(boom), List.of(), null, null);
        var session = new CollectingSession("policy-fail-closed");
        pipeline.execute("c1", "hi", session);

        assertFalse(executed.get(), "policy evaluation failure must fail-closed");
    }

    @Test
    void policiesListExposedForAdminConsole() {
        var p1 = new FixedPolicy("policy-a", PolicyDecision.admit());
        var p2 = new FixedPolicy("policy-b", PolicyDecision.admit());
        var pipeline = new AiPipeline(new StubRuntime(), null, null, null, null,
                List.of(), List.of(p1, p2), List.of(), null, null);

        assertEquals(2, pipeline.policies().size());
        assertEquals("policy-a", pipeline.policies().get(0).name());
        assertEquals("policy-b", pipeline.policies().get(1).name());
    }

    @Test
    void yamlLoadedPoliciesApplyAtAdmission() throws Exception {
        var yaml = """
                policies:
                  - name: strict-pii
                    type: pii-redaction
                    config:
                      mode: block
                """;
        var policies = new YamlPolicyParser().parse("yaml:test",
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        var executed = new AtomicBoolean(false);
        var runtime = new StubRuntime() {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                executed.set(true);
                session.complete();
            }
        };
        var pipeline = new AiPipeline(runtime, null, null, null, null,
                List.of(), policies, List.of(), null, null);
        var session = new CollectingSession("yaml-policy");
        pipeline.execute("c1", "call me at 555-867-5309", session);

        assertFalse(executed.get(), "pii-redaction in block mode must deny at admission");
        assertEquals(1, pipeline.policies().size());
        assertEquals("yaml:test", pipeline.policies().get(0).source());
    }

    @Test
    void legacyConstructorDefaultsPoliciesToEmpty() {
        var pipeline = new AiPipeline(new StubRuntime(), null, null, null, null,
                List.of(), List.of(), null);
        assertTrue(pipeline.policies().isEmpty());
    }

    private static class FixedPolicy implements GovernancePolicy {
        private final String name;
        private final PolicyDecision decision;

        FixedPolicy(String name, PolicyDecision decision) {
            this.name = name;
            this.decision = decision;
        }

        @Override public String name() { return name; }
        @Override public String source() { return "code:test"; }
        @Override public String version() { return "test"; }
        @Override public PolicyDecision evaluate(PolicyContext context) { return decision; }
    }

    private static class StubRuntime implements AgentRuntime {
        @Override public String name() { return "stub"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }
        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.complete();
        }
    }
}
