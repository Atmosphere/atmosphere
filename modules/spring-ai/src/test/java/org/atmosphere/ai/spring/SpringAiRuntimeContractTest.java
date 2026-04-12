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

import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.test.AbstractAgentRuntimeContractTest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Concrete TCK test for {@link SpringAiAgentRuntime}.
 */
class SpringAiRuntimeContractTest extends AbstractAgentRuntimeContractTest {

    @Override
    protected AgentRuntime createRuntime() {
        return new TestableSpringAiRuntime(mockToolAwareChatClient());
    }

    @Override
    protected AgentExecutionContext createTextContext() {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "gpt-4",
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
    protected AgentExecutionContext createImageContext() {
        var parts = List.<org.atmosphere.ai.Content>of(
                new org.atmosphere.ai.Content.Image(TINY_PNG, "image/png"));
        return new AgentExecutionContext(
                "Describe this image.", "You are helpful", "gpt-4",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(), parts,
                org.atmosphere.ai.approval.ToolApprovalPolicy.annotated());
    }

    /**
     * Exercise the {@code runtimeWithPromptCachingAcceptsCacheHint} assertion
     * on Spring AI. The context carries a {@link org.atmosphere.ai.llm.CacheHint#conservative()}
     * under the canonical metadata key; {@code doExecuteWithHandle} reads it
     * via {@code CacheHint.from(context)} and attaches an
     * {@code OpenAiChatOptions.promptCacheKey} to the prompt spec. Dispatch
     * continues through Spring AI's reactor pipeline; downstream network
     * failures are caught and treated as a skip per the base contract.
     */
    @Override
    protected AgentExecutionContext createCacheContext() {
        var metadata = Map.<String, Object>of(
                org.atmosphere.ai.llm.CacheHint.METADATA_KEY,
                org.atmosphere.ai.llm.CacheHint.conservative("spring-ai-cache-test"));
        return new AgentExecutionContext(
                "Hello, cached.", "You are helpful", "gpt-4",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), metadata,
                List.of(), null, null, List.of(), List.of(),
                org.atmosphere.ai.approval.ToolApprovalPolicy.annotated());
    }

    @Test
    void springAiDeclaresToolCalling() {
        assertTrue(createRuntime().capabilities().contains(AiCapability.TOOL_CALLING));
    }

    @Test
    void springAiDeclaresStructuredOutput() {
        assertTrue(createRuntime().capabilities().contains(AiCapability.STRUCTURED_OUTPUT));
    }

    static class TestableSpringAiRuntime extends SpringAiAgentRuntime {
        TestableSpringAiRuntime(ChatClient client) {
            setNativeClient(client);
        }
    }

    @SuppressWarnings("unchecked")
    private static ChatClient mockToolAwareChatClient() {
        var chatClient = mock(ChatClient.class);
        // RETURNS_SELF handles all fluent chain methods — unstubbed calls
        // return the mock itself instead of null, avoiding NPEs on
        // Spring AI's deeply chained promptSpec.system().user().options()...
        var promptSpec = mock(ChatClient.ChatClientRequestSpec.class,
                org.mockito.Answers.RETURNS_SELF);
        var streamSpec = mock(ChatClient.StreamResponseSpec.class);

        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);

        var generation = new Generation(new AssistantMessage("Hello world"));
        var chatResponse = new ChatResponse(List.of(generation));
        when(streamSpec.chatResponse()).thenReturn(Flux.just(chatResponse));

        return chatClient;
    }

}
