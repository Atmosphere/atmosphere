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
package org.atmosphere.ai.adk;

import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.test.AbstractAgentRuntimeContractTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concrete TCK test for {@link AdkAgentRuntime}. Extends the shared contract
 * base class so capability and name tests are enforced. Execution tests are
 * skipped because ADK requires a live Gemini API key and configured Runner.
 */
class AdkRuntimeContractTest extends AbstractAgentRuntimeContractTest {

    @Override
    protected AgentRuntime createRuntime() {
        return new AdkAgentRuntime();
    }

    @Override
    protected AgentExecutionContext createTextContext() {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "gemini-2.5-flash",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @Override
    protected AgentExecutionContext createToolCallContext() {
        return null;
    }

    @Override
    protected AgentExecutionContext createErrorContext() {
        return null;
    }

    @Override
    protected Set<AiCapability> expectedCapabilities() {
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.TOOL_CALLING,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.AGENT_ORCHESTRATION,
                AiCapability.CONVERSATION_MEMORY,
                AiCapability.SYSTEM_PROMPT,
                AiCapability.TOOL_APPROVAL,
                AiCapability.VISION,
                AiCapability.AUDIO,
                AiCapability.MULTI_MODAL);
    }

    /**
     * Exercise the {@code runtimeWithVisionCapabilityAcceptsImagePart}
     * contract assertion on the ADK runtime. ADK declares
     * {@link AiCapability#VISION} and translates
     * {@link org.atmosphere.ai.Content.Image} parts into
     * {@code Part.fromBytes(byte[], mimeType)} before handing them to the
     * Gemini runner. The assertion fires the runtime.execute path against
     * an unconfigured Runner — the message-assembly layer runs before
     * {@code resolveClient()} throws {@code IllegalStateException}, which
     * the base assertion catches via {@code Assumptions.assumeTrue(false)}
     * and marks the test as skipped-with-reason rather than failed.
     * {@code UnsupportedOperationException} from the part translation
     * would still surface as a hard failure, which is the contract the
     * assertion is here to enforce.
     */
    @Override
    protected AgentExecutionContext createImageContext() {
        var parts = List.<org.atmosphere.ai.Content>of(
                new org.atmosphere.ai.Content.Image(TINY_PNG, "image/png"));
        return new AgentExecutionContext(
                "Describe this image.", "You are helpful", "gemini-2.5-flash",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(), parts,
                org.atmosphere.ai.approval.ToolApprovalPolicy.annotated());
    }

    // ADK execution tests require a configured Runner with API key.
    // The inherited textStreamingCompletesSession() will fail because
    // no Runner is configured. Override to skip.
    @Override
    protected void textStreamingCompletesSession() throws Exception {
        // Skip: requires configured Runner with API key
    }

    @Test
    void adkDeclaresToolApprovalCapability() {
        assertTrue(createRuntime().capabilities().contains(AiCapability.TOOL_APPROVAL));
    }

    @Test
    void adkDeclaresConversationMemory() {
        assertTrue(createRuntime().capabilities().contains(AiCapability.CONVERSATION_MEMORY));
    }

    @Test
    void adkDeclaresAgentOrchestration() {
        assertTrue(createRuntime().capabilities().contains(AiCapability.AGENT_ORCHESTRATION));
    }
}
