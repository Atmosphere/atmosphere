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
package org.atmosphere.ai.memory;

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongTermMemoryInterceptorTest {

    private final LongTermMemory memory = mock(LongTermMemory.class);
    private final MemoryExtractionStrategy strategy = mock(MemoryExtractionStrategy.class);
    private final AgentRuntime runtime = mock(AgentRuntime.class);
    private final AtmosphereResource resource = mock(AtmosphereResource.class);

    private LongTermMemoryInterceptor createInterceptor() {
        return new LongTermMemoryInterceptor(memory, strategy, runtime, 10);
    }

    private AiRequest requestWithUser(String userId) {
        return new AiRequest("hello", "system prompt")
                .withUserId(userId)
                .withConversationId("conv-1");
    }

    @Test
    void preProcessReturnsUnchangedWhenUserIdNull() {
        var interceptor = createInterceptor();
        var request = new AiRequest("hello", "prompt");

        var result = interceptor.preProcess(request, resource);

        assertEquals(request, result);
        verify(memory, never()).getFacts(anyString(), anyInt());
    }

    @Test
    void preProcessReturnsUnchangedWhenUserIdBlank() {
        var interceptor = createInterceptor();
        var request = requestWithUser("  ");

        var result = interceptor.preProcess(request, resource);

        assertEquals(request, result);
        verify(memory, never()).getFacts(anyString(), anyInt());
    }

    @Test
    void preProcessReturnsUnchangedWhenNoFacts() {
        var interceptor = createInterceptor();
        var request = requestWithUser("user-1");
        when(memory.getFacts("user-1", 10)).thenReturn(List.of());

        var result = interceptor.preProcess(request, resource);

        assertEquals(request, result);
    }

    @Test
    void preProcessAugmentsSystemPromptWithFacts() {
        var interceptor = createInterceptor();
        var request = requestWithUser("user-1");
        when(memory.getFacts("user-1", 10)).thenReturn(List.of("Likes Java", "Uses IntelliJ"));

        var result = interceptor.preProcess(request, resource);

        assertNotNull(result.systemPrompt());
        assertEquals("system prompt\n\nKnown facts about this user:\n- Likes Java\n- Uses IntelliJ",
                result.systemPrompt());
        assertEquals("hello", result.message());
    }

    @Test
    void preProcessHandlesNullSystemPrompt() {
        var interceptor = createInterceptor();
        var request = new AiRequest("hello", null, null, "user-1",
                null, null, "conv-1", java.util.Map.of(), List.of());
        when(memory.getFacts("user-1", 10)).thenReturn(List.of("Fact one"));

        var result = interceptor.preProcess(request, resource);

        assertEquals("Known facts about this user:\n- Fact one", result.systemPrompt());
    }

    @Test
    void postProcessNoExtractionWhenShouldExtractFalse() {
        var interceptor = createInterceptor();
        var request = requestWithUser("user-1");
        when(strategy.shouldExtract(eq("conv-1"), eq("hello"), anyInt())).thenReturn(false);

        interceptor.postProcess(request, resource);

        verify(strategy, never()).extractFacts(anyString(), any(AgentRuntime.class));
    }

    @Test
    void postProcessExtractsAndSavesWhenShouldExtractTrue() {
        var interceptor = createInterceptor();
        var request = requestWithUser("user-1")
                .withHistory(List.of(ChatMessage.user("previous msg")));
        when(strategy.shouldExtract(eq("conv-1"), eq("hello"), anyInt())).thenReturn(true);
        when(strategy.extractFacts(anyString(), eq(runtime))).thenReturn(List.of("new fact"));

        interceptor.postProcess(request, resource);

        verify(strategy).extractFacts(anyString(), eq(runtime));
        verify(memory).saveFacts("user-1", List.of("new fact"));
    }

    @Test
    void postProcessSkipsWhenUserIdNull() {
        var interceptor = createInterceptor();
        var request = new AiRequest("hello");

        interceptor.postProcess(request, resource);

        verify(strategy, never()).shouldExtract(anyString(), anyString(), anyInt());
    }

    @Test
    void postProcessDoesNotSaveEmptyFacts() {
        var interceptor = createInterceptor();
        var request = requestWithUser("user-1");
        when(strategy.shouldExtract(eq("conv-1"), eq("hello"), anyInt())).thenReturn(true);
        when(strategy.extractFacts(anyString(), eq(runtime))).thenReturn(List.of());

        interceptor.postProcess(request, resource);

        verify(memory, never()).saveFacts(anyString(), any());
    }

    @Test
    void onDisconnectExtractsFactsFromHistory() {
        var interceptor = createInterceptor();
        var history = List.of(
                ChatMessage.user("Hi"),
                ChatMessage.assistant("Hello!")
        );
        when(strategy.extractFacts(anyString(), eq(runtime))).thenReturn(List.of("greeted user"));

        interceptor.onDisconnect("user-1", "conv-1", history);

        verify(strategy).extractFacts(anyString(), eq(runtime));
        verify(memory).saveFacts("user-1", List.of("greeted user"));
    }

    @Test
    void onDisconnectSkipsWhenHistoryEmpty() {
        var interceptor = createInterceptor();

        interceptor.onDisconnect("user-1", "conv-1", List.of());

        verify(strategy, never()).extractFacts(anyString(), any(AgentRuntime.class));
    }

    @Test
    void onDisconnectSkipsWhenUserIdNull() {
        var interceptor = createInterceptor();
        var history = List.of(ChatMessage.user("msg"));

        interceptor.onDisconnect(null, "conv-1", history);

        verify(strategy, never()).extractFacts(anyString(), any(AgentRuntime.class));
    }

    @Test
    void onDisconnectSkipsWhenUserIdBlank() {
        var interceptor = createInterceptor();
        var history = List.of(ChatMessage.user("msg"));

        interceptor.onDisconnect("  ", "conv-1", history);

        verify(strategy, never()).extractFacts(anyString(), any(AgentRuntime.class));
    }

    @Test
    void threeArgConstructorDefaultsMaxFacts() {
        var interceptor = new LongTermMemoryInterceptor(memory, strategy, runtime);
        var request = requestWithUser("user-1");
        when(memory.getFacts("user-1", 20)).thenReturn(List.of("fact"));

        interceptor.preProcess(request, resource);

        verify(memory).getFacts("user-1", 20);
    }
}
