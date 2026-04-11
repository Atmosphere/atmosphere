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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Concrete TCK test for {@link SpringAiAgentRuntime}.
 */
@SuppressWarnings({"deprecation", "removal"})
class SpringAiRuntimeContractTest extends AbstractAgentRuntimeContractTest {

    @Override
    protected AgentRuntime createRuntime() {
        var chatClient = mockChatClient();
        var runtime = new TestableSpringAiRuntime(chatClient);
        return runtime;
    }

    @Override
    protected AgentExecutionContext createTextContext() {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "gpt-4",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null);
    }

    @Override
    protected AgentExecutionContext createToolCallContext() {
        return null;
    }

    @Override
    protected AgentExecutionContext createErrorContext() {
        return null;
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
    private static ChatClient mockChatClient() {
        var chatClient = mock(ChatClient.class);
        var promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var streamSpec = mock(ChatClient.StreamResponseSpec.class);

        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(any(String.class))).thenReturn(promptSpec);
        when(promptSpec.user(any(String.class))).thenReturn(promptSpec);
        when(promptSpec.options(any())).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);

        var generation = new Generation(new AssistantMessage("Hello world"));
        var chatResponse = new ChatResponse(List.of(generation));
        when(streamSpec.chatResponse()).thenReturn(Flux.just(chatResponse));

        return chatClient;
    }
}
