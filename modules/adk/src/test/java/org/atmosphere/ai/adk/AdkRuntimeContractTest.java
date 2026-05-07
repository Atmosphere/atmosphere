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

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.test.AbstractAgentRuntimeContractTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Concrete TCK test for {@link AdkAgentRuntime}. Extends the shared contract
 * base class so capability and name tests are enforced. The text-streaming
 * contract is exercised against a mocked {@link Runner} whose
 * {@code runAsync} returns a synthetic {@link Flowable} of one partial-text
 * event followed by a turn-complete event — same pattern that
 * {@link AdkAgentRuntimeCancelTest} uses to drive the cancel path without a
 * live Gemini API key.
 */
class AdkRuntimeContractTest extends AbstractAgentRuntimeContractTest {

    @Override
    protected AgentRuntime createRuntime() {
        return new TestableAdkAgentRuntime(stubRunner());
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
        return new AgentExecutionContext(
                CONTRACT_ERROR_SENTINEL, "You are helpful", "gemini-2.5-flash",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
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
                AiCapability.MULTI_MODAL,
                AiCapability.TOKEN_USAGE,
                AiCapability.PER_REQUEST_RETRY,
                AiCapability.PROMPT_CACHING,
                AiCapability.BUDGET_ENFORCEMENT,
                AiCapability.CONFIDENCE_SCORES,
                AiCapability.PASSIVATION);
    }

    /**
     * Exercise the {@code runtimeWithVisionCapabilityAcceptsImagePart}
     * contract assertion on the ADK runtime. ADK declares
     * {@link AiCapability#VISION} and translates
     * {@link org.atmosphere.ai.Content.Image} parts into
     * {@code Part.fromBytes(byte[], mimeType)} before handing them to the
     * Gemini runner. The assertion fires the runtime.execute path against
     * the stubbed Runner — message-assembly runs and the synthetic
     * Flowable terminates so the assertion completes without hitting the
     * network.
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

    /**
     * Build a {@link Runner} mock whose {@code runAsync} emits one partial
     * text frame and one turnComplete frame. This drives the AdkEventAdapter
     * to forward {@code "Hello world"} through {@code session.send(...)}
     * (via the default {@code emit(TextDelta)} routing) and then terminate
     * the session via {@code complete()} — same end-state the live Gemini
     * stream would produce on a successful single-turn response.
     */
    private static Runner stubRunner() {
        var runner = mock(Runner.class);
        var sessionService = mock(BaseSessionService.class);
        var session = mock(Session.class);
        when(runner.sessionService()).thenReturn(sessionService);
        when(runner.appName()).thenReturn("contract-test-app");
        when(sessionService.getSession(anyString(), anyString(), anyString(), any()))
                .thenReturn(Maybe.just(session));

        var textEvent = Event.builder()
                .id(Event.generateEventId())
                .author("agent")
                .actions(EventActions.builder().build())
                .partial(true)
                .content(Content.fromParts(Part.fromText("Hello world")))
                .build();
        var doneEvent = Event.builder()
                .id(Event.generateEventId())
                .author("agent")
                .actions(EventActions.builder().build())
                .turnComplete(true)
                .build();

        when(runner.runAsync(anyString(), anyString(), any(Content.class)))
                .thenAnswer(inv -> {
                    Content prompt = inv.getArgument(2);
                    if (carriesErrorSentinel(prompt)) {
                        return Flowable.<Event>error(
                                new RuntimeException("forced contract error"));
                    }
                    return Flowable.fromArray(textEvent, doneEvent);
                });
        return runner;
    }

    private static boolean carriesErrorSentinel(Content content) {
        if (content == null) {
            return false;
        }
        return content.parts().map(parts -> {
            for (var p : parts) {
                if (p.text().map(CONTRACT_ERROR_SENTINEL::equals).orElse(false)) {
                    return true;
                }
            }
            return false;
        }).orElse(false);
    }

    static class TestableAdkAgentRuntime extends AdkAgentRuntime {
        TestableAdkAgentRuntime(Runner runner) {
            setNativeClient(runner);
        }
    }
}
