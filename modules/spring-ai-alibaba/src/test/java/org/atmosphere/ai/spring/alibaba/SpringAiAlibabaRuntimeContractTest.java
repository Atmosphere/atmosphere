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
package org.atmosphere.ai.spring.alibaba;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.test.AbstractAgentRuntimeContractTest;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TCK-style contract test for {@link SpringAiAlibabaAgentRuntime}.
 *
 * <p>Mockito 5 mocks the final {@link ReactAgent#call(List)} surface. The
 * stub returns a single {@link AssistantMessage} with the canned reply —
 * {@link SpringAiAlibabaAgentRuntime#doExecute} forwards it as one
 * buffered chunk through the contract test's session, exercising the
 * full sync→buffered translation path.</p>
 */
class SpringAiAlibabaRuntimeContractTest extends AbstractAgentRuntimeContractTest {

    @Override
    protected AgentRuntime createRuntime() {
        var agent = mock(ReactAgent.class);
        try {
            when(agent.call(anyList())).thenAnswer(inv -> {
                List<?> messages = inv.getArgument(0);
                if (carriesErrorSentinel(messages)) {
                    throw new com.alibaba.cloud.ai.graph.exception.GraphRunnerException(
                            "forced contract error");
                }
                return new AssistantMessage("Hello world");
            });
        } catch (com.alibaba.cloud.ai.graph.exception.GraphRunnerException neverThrownByMock) {
            throw new AssertionError(neverThrownByMock);
        }
        return new TestableSpringAiAlibabaRuntime(agent);
    }

    private static boolean carriesErrorSentinel(List<?> messages) {
        if (messages == null) {
            return false;
        }
        for (var msg : messages) {
            if (msg instanceof org.springframework.ai.chat.messages.Message m
                    && CONTRACT_ERROR_SENTINEL.equals(m.getText())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected AgentExecutionContext createTextContext() {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "qwen-plus",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @Override
    protected AgentExecutionContext createToolCallContext() {
        return null; // default test runtime has no ChatModel for per-request tool agent construction
    }

    @Override
    protected AgentExecutionContext createErrorContext() {
        return new AgentExecutionContext(
                CONTRACT_ERROR_SENTINEL, "You are helpful", "qwen-plus",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @Override
    protected Set<AiCapability> expectedCapabilities() {
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.SYSTEM_PROMPT,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.CONVERSATION_MEMORY,
                AiCapability.TOOL_CALLING,
                AiCapability.TOOL_APPROVAL,
                AiCapability.TOKEN_USAGE,
                AiCapability.PER_REQUEST_RETRY,
                AiCapability.BUDGET_ENFORCEMENT,
                AiCapability.CONFIDENCE_SCORES,
                AiCapability.PASSIVATION,
                AiCapability.VISION,
                AiCapability.AUDIO,
                AiCapability.MULTI_MODAL,
                AiCapability.CANCELLATION,
                AiCapability.PLANNING);
    }

    static class TestableSpringAiAlibabaRuntime extends SpringAiAlibabaAgentRuntime {
        TestableSpringAiAlibabaRuntime(ReactAgent agent) {
            setNativeClient(agent);
        }
    }
}
