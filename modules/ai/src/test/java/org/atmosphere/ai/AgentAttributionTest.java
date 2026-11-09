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

import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.identity.AgentFingerprint;
import org.atmosphere.ai.lineage.InMemoryLineageRecorder;
import org.atmosphere.ai.lineage.LineageRecorderHolder;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A turn's audit records name the agent configuration that served it: the
 * lineage entry (what the agent did, its tool calls) and the governance
 * decisions on its turn carry the runtime, model and a version of the system
 * prompt, so behaviour is attributable to the configuration that produced it,
 * not only to the user it acted for.
 */
class AgentAttributionTest {

    private static final String SYSTEM_PROMPT = "You are the billing agent. Never issue refunds.";

    @AfterEach
    void resetHolders() {
        LineageRecorderHolder.reset();
        GovernanceDecisionLog.reset();
    }

    @Test
    void pipelineTurnStampsTheAgentFingerprintOnItsLineageEntry() {
        var recorder = new InMemoryLineageRecorder();
        LineageRecorderHolder.install(recorder);
        var runtime = runtime(session -> {
            session.emit(new AiEvent.ToolStart("lookup_invoice", Map.of("id", "42")));
            session.emit(new AiEvent.ToolResult("lookup_invoice", "paid"));
            // A runtime cannot rewrite the attribution through its metadata.
            session.sendMetadata(AgentFingerprint.MODEL_ATTRIBUTE, "spoofed-model");
            session.sendMetadata(AgentFingerprint.TOOL_SET_ATTRIBUTE, "spoofed");
            session.complete();
        });
        var pipeline = new AiPipeline(runtime, SYSTEM_PROMPT, "billing-model",
                null, null, List.of(), List.of(), AiMetrics.NOOP);

        pipeline.execute("c1", "Was invoice 42 paid?", new CollectingSession("attr-1"));

        var entry = recorder.recent(1).get(0);
        assertEquals("lookup_invoice", entry.toolCalls().get(0).name());
        var attributes = entry.attributes();
        assertEquals("attribution-stub", attributes.get(AgentFingerprint.RUNTIME_ATTRIBUTE));
        assertEquals("billing-model", attributes.get(AgentFingerprint.MODEL_ATTRIBUTE));
        assertEquals(AgentFingerprint.systemPromptSha256(SYSTEM_PROMPT),
                attributes.get(AgentFingerprint.SYSTEM_PROMPT_ATTRIBUTE));
        assertFalse(attributes.containsKey(AgentFingerprint.TOOL_SET_ATTRIBUTE),
                "no tools were offered, and the runtime's claim of a tool set is not recorded");
        assertFalse(attributes.containsValue(SYSTEM_PROMPT), "the prompt itself is never recorded");
    }

    @Test
    void governanceDecisionOnAPipelineTurnCarriesThePromptVersionNotThePrompt() {
        var log = GovernanceDecisionLog.install(10);
        GovernancePolicy admit = new GovernancePolicy() {
            @Override public String name() { return "admit-all"; }
            @Override public String source() { return "code:test"; }
            @Override public String version() { return "1"; }
            @Override public PolicyDecision evaluate(PolicyContext context) {
                return PolicyDecision.admit();
            }
        };
        var pipeline = new AiPipeline(runtime(StreamingSession::complete), SYSTEM_PROMPT,
                "billing-model", null, null, List.of(), List.of(admit), List.of(), null, null);

        pipeline.execute("c1", "hello", new CollectingSession("attr-2"));

        var snapshot = log.recent(1).get(0).contextSnapshot();
        assertEquals(AgentFingerprint.systemPromptSha256(SYSTEM_PROMPT),
                snapshot.get(GovernanceDecisionLog.SYSTEM_PROMPT_SHA256_KEY));
        assertEquals("billing-model", snapshot.get("model"));
        assertFalse(snapshot.containsValue(SYSTEM_PROMPT), "the prompt itself is never recorded");
    }

    @Test
    void governanceSnapshotHashCannotBeOverriddenByRequestMetadata() {
        var request = new AiRequest("hi", SYSTEM_PROMPT, "m", "u", "s", "a", "c",
                Map.of(GovernanceDecisionLog.SYSTEM_PROMPT_SHA256_KEY, "forged"), List.of());

        var snapshot = GovernanceDecisionLog.snapshotContext(PolicyContext.preAdmission(request));

        assertEquals(AgentFingerprint.systemPromptSha256(SYSTEM_PROMPT),
                snapshot.get(GovernanceDecisionLog.SYSTEM_PROMPT_SHA256_KEY));
    }

    @Test
    void composerFingerprintsTheOfferedToolsAndTheRequestedModel() {
        var tool = ToolDefinition.builder("lookup_invoice", "Find an invoice")
                .executor(args -> "ok").build();
        var request = new AiRequest("hi", SYSTEM_PROMPT, "request-model", null, null, null, null,
                Map.of(), List.of()).withTools(List.of(tool));
        var spec = new DispatchDecorators.Spec(
                null, "client-1", "hi", "user-1", "agent-1", "conv-1",
                AiMetrics.NOOP, "endpoint-model", "test-runtime", "request-model",
                null, List.of(), null, null, request, null);

        var fingerprint = DispatchDecorators.fingerprint(spec);

        assertEquals("test-runtime", fingerprint.runtime());
        assertEquals("request-model", fingerprint.model());
        assertEquals(AgentFingerprint.toolSetSha256(List.of("lookup_invoice")),
                fingerprint.toolSetSha256());
        assertNotNull(fingerprint.systemPromptSha256());
    }

    private static AgentRuntime runtime(java.util.function.Consumer<StreamingSession> driver) {
        return new AgentRuntime() {
            @Override public String name() { return "attribution-stub"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }
            @Override public Set<AiCapability> capabilities() {
                return Set.of(AiCapability.TEXT_STREAMING);
            }
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                driver.accept(session);
            }
        };
    }
}
