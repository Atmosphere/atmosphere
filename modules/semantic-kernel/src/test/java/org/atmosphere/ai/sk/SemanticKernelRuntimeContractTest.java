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
package org.atmosphere.ai.sk;

import com.microsoft.semantickernel.services.chatcompletion.ChatCompletionService;
import com.microsoft.semantickernel.services.chatcompletion.ChatHistory;
import com.microsoft.semantickernel.services.chatcompletion.StreamingChatContent;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.test.AbstractAgentRuntimeContractTest;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Concrete TCK subclass for {@link SemanticKernelAgentRuntime}. Previously
 * SK had a freestanding test that did not run the cross-runtime parity
 * assertions ({@code runtimeWithSystemPromptAlsoDeclaresStructuredOutput},
 * {@code hitlPendingApprovalEmitsProtocolEvent}, etc.). This subclass
 * brings SK into the contract test matrix so those assertions actually
 * fire against 6/6 runtimes instead of silently skipping.
 *
 * <p>Execution-level tests override to skip because SK requires a real
 * {@code ChatCompletionService} backed by an Azure {@code OpenAIAsyncClient},
 * which this module's unit dependencies do not carry.</p>
 */
class SemanticKernelRuntimeContractTest extends AbstractAgentRuntimeContractTest {

    @Override
    protected AgentRuntime createRuntime() {
        @SuppressWarnings("unchecked")
        var frame = (StreamingChatContent<Object>) mock(StreamingChatContent.class);
        when(frame.getContent()).thenReturn("Hello world");
        when(frame.getMetadata()).thenReturn(null);

        var service = mock(ChatCompletionService.class);
        when(service.getStreamingChatMessageContentsAsync(
                any(ChatHistory.class), any(), any()))
                .thenAnswer(inv -> {
                    ChatHistory history = inv.getArgument(0);
                    if (carriesErrorSentinel(history)) {
                        return Flux.error(new RuntimeException("forced contract error"));
                    }
                    return Flux.just(frame);
                });

        return new TestableSemanticKernelRuntime(service);
    }

    private static boolean carriesErrorSentinel(ChatHistory history) {
        for (var m : history.getMessages()) {
            var content = m.getContent();
            if (content != null && content.contains(CONTRACT_ERROR_SENTINEL)) {
                return true;
            }
        }
        return false;
    }

    static class TestableSemanticKernelRuntime extends SemanticKernelAgentRuntime {
        TestableSemanticKernelRuntime(ChatCompletionService service) {
            setNativeClient(service);
        }
    }

    @Override
    protected AgentExecutionContext createTextContext() {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "gpt-4o-mini",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @Override
    protected AgentExecutionContext createToolCallContext() {
        // SK tool-calling is now implemented via SemanticKernelToolBridge;
        // still return null because the contract's tool-execution assertion
        // requires a live ChatCompletionService to drive the auto-invoke
        // loop. Unit-test-level verification lives in SemanticKernelToolBridgeTest.
        return null;
    }

    @Override
    protected AgentExecutionContext createErrorContext() {
        return new AgentExecutionContext(
                CONTRACT_ERROR_SENTINEL, "You are helpful", "gpt-4o-mini",
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
                AiCapability.TOKEN_USAGE,
                AiCapability.TOOL_CALLING,
                AiCapability.TOOL_APPROVAL,
                AiCapability.PER_REQUEST_RETRY,
                AiCapability.BUDGET_ENFORCEMENT,
                AiCapability.CONFIDENCE_SCORES,
                AiCapability.PASSIVATION,
                AiCapability.VISION,
                AiCapability.MULTI_MODAL);
    }

}
