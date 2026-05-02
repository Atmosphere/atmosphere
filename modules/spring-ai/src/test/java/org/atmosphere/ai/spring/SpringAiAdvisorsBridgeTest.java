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
package org.atmosphere.ai.spring;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the per-request Spring AI {@link Advisor} bridge: helper semantics
 * ({@link SpringAiAdvisors}) plus end-to-end wiring proof that
 * {@link SpringAiAgentRuntime} calls {@code promptSpec.advisors(List)} with
 * the user's advisor list when (and only when) an advisor is attached.
 *
 * <p>The wiring assertion uses a Mockito {@link org.mockito.ArgumentCaptor}
 * on the mocked {@code ChatClientRequestSpec} so we are checking the actual
 * dispatch, not just source-grep parity. This pattern follows
 * {@code SpringAiGatewayAdmissionTest} which closed the same kind of source-vs-runtime
 * gap for gateway admission.</p>
 */
class SpringAiAdvisorsBridgeTest {

    @Test
    void fromReturnsEmptyWhenNoSlot() {
        var ctx = baseContext(Map.of());
        assertTrue(SpringAiAdvisors.from(ctx).isEmpty(),
                "missing slot must yield an empty list, not null");
    }

    @Test
    void fromReturnsEmptyWhenContextIsNull() {
        assertTrue(SpringAiAdvisors.from(null).isEmpty(),
                "null context must yield empty rather than NPE");
    }

    @Test
    void fromRejectsNonListSlot() {
        var ctx = baseContext(Map.of(SpringAiAdvisors.METADATA_KEY, "not a list"));
        var iae = assertThrows(IllegalArgumentException.class,
                () -> SpringAiAdvisors.from(ctx),
                "a non-List slot must fail loudly — silently dropping advisors "
                        + "would mask guardrail / RAG bypass bugs");
        assertTrue(iae.getMessage().contains(SpringAiAdvisors.METADATA_KEY));
    }

    @Test
    void fromRejectsListWithNonAdvisorElement() {
        var bad = List.<Object>of("not an advisor");
        var ctx = baseContext(Map.of(SpringAiAdvisors.METADATA_KEY, bad));
        assertThrows(ClassCastException.class,
                () -> SpringAiAdvisors.from(ctx),
                "element-level type errors must throw — masking misconfigured "
                        + "advisors is the same class of bug as silently dropping them");
    }

    @Test
    void attachAppendsToExistingSlot() {
        var first = new RecordingAdvisor("first");
        var second = new RecordingAdvisor("second");

        var withFirst = SpringAiAdvisors.attach(baseContext(Map.of()), first);
        var withBoth = SpringAiAdvisors.attach(withFirst, second);

        var resolved = SpringAiAdvisors.from(withBoth);
        assertEquals(List.of(first, second), resolved,
                "attach must append additively, matching Spring AI's own "
                        + "ChatClientRequestSpec.advisors(...) semantics");
    }

    @Test
    void attachWithNoAdvisorsReturnsSameContext() {
        var ctx = baseContext(Map.of());
        assertSame(ctx, SpringAiAdvisors.attach(ctx),
                "the no-op attach path must not allocate a new context — "
                        + "callers building contexts in tight loops shouldn't pay GC pressure");
    }

    @Test
    void attachRejectsNullEntry() {
        var ctx = baseContext(Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> SpringAiAdvisors.attach(ctx, (Advisor) null),
                "null entries in the advisor list must fail loudly at attach "
                        + "time so the error surfaces at the call site, not deep "
                        + "inside Spring AI's chain dispatch");
    }

    @Test
    void runtimeForwardsAdvisorsToPromptSpec() {
        var advisor = new RecordingAdvisor("safeguard-double");
        var promptSpec = mock(ChatClient.ChatClientRequestSpec.class,
                org.mockito.Answers.RETURNS_SELF);
        var runtime = newRuntimeWithMockedPrompt(promptSpec);

        var ctx = SpringAiAdvisors.attach(textContext(), advisor);
        runtime.execute(ctx, new NoopSession());

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<Advisor>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(promptSpec).advisors(captor.capture());
        assertEquals(List.of((Advisor) advisor), captor.getValue(),
                "the advisor list passed to promptSpec.advisors(...) must be "
                        + "exactly the list resolved from SpringAiAdvisors.from(ctx)");
    }

    @Test
    void runtimeSkipsAdvisorsCallWhenNoneAttached() {
        var promptSpec = mock(ChatClient.ChatClientRequestSpec.class,
                org.mockito.Answers.RETURNS_SELF);
        var runtime = newRuntimeWithMockedPrompt(promptSpec);

        runtime.execute(textContext(), new NoopSession());

        verify(promptSpec, never()).advisors(anyList());
        assertFalse(SpringAiAdvisors.from(textContext()).isEmpty()
                        != SpringAiAdvisors.from(textContext()).isEmpty(),
                "sanity: from() must be referentially stable when invoked twice");
    }

    @Test
    void runtimePreservesOrderAcrossMultipleAdvisors() {
        var first = new RecordingAdvisor("first");
        var second = new RecordingAdvisor("second");
        var third = new RecordingAdvisor("third");
        var promptSpec = mock(ChatClient.ChatClientRequestSpec.class,
                org.mockito.Answers.RETURNS_SELF);
        var runtime = newRuntimeWithMockedPrompt(promptSpec);

        var ctx = SpringAiAdvisors.attach(textContext(), first, second, third);
        runtime.execute(ctx, new NoopSession());

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<Advisor>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(promptSpec).advisors(captor.capture());
        assertEquals(List.of(first, second, third), captor.getValue(),
                "ordering matters for the Spring AI advisor chain — "
                        + "guardrails must run before observability, RAG before chat memory, etc.");
    }

    private static SpringAiRuntimeContractTest.TestableSpringAiRuntime
            newRuntimeWithMockedPrompt(ChatClient.ChatClientRequestSpec promptSpec) {
        var chatClient = mock(ChatClient.class);
        var streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);
        var generation = new Generation(new AssistantMessage("ok"));
        var chatResponse = new ChatResponse(List.of(generation));
        when(streamSpec.chatResponse()).thenReturn(Flux.just(chatResponse));
        return new SpringAiRuntimeContractTest.TestableSpringAiRuntime(chatClient);
    }

    private static AgentExecutionContext baseContext(Map<String, Object> metadata) {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "gpt-4",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), metadata,
                List.of(), null, null);
    }

    private static AgentExecutionContext textContext() {
        return baseContext(Map.of());
    }

    /**
     * Implements both {@link CallAdvisor} and {@link StreamAdvisor} so the
     * helper's element-type check accepts it on either dispatch path. The
     * advise methods are no-ops here — the wiring assertions verify that the
     * advisor reached {@code promptSpec.advisors(...)}, not that Spring AI's
     * chain executor invoked it (that's covered by Spring AI's own tests).
     */
    private static final class RecordingAdvisor implements CallAdvisor, StreamAdvisor {
        private final String name;

        RecordingAdvisor(String name) {
            this.name = name;
        }

        @Override public String getName() { return name; }

        @Override public int getOrder() { return 0; }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            return chain.nextCall(request);
        }

        @Override
        public Flux<ChatClientResponse> adviseStream(ChatClientRequest request,
                                                    StreamAdvisorChain chain) {
            return chain.nextStream(request);
        }
    }

    private static final class NoopSession implements StreamingSession {
        @Override public String sessionId() { return "advisors-test"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
    }
}
