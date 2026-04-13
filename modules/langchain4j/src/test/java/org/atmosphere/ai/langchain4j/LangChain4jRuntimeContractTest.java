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
package org.atmosphere.ai.langchain4j;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.data.message.AiMessage;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Concrete TCK test for {@link LangChain4jAgentRuntime}.
 */
class LangChain4jRuntimeContractTest extends AbstractAgentRuntimeContractTest {

    @Override
    protected AgentRuntime createRuntime() {
        var model = mock(StreamingChatModel.class);
        doAnswer(inv -> {
            ChatRequest req = inv.getArgument(0);
            StreamingChatResponseHandler handler = inv.getArgument(1);
            var hasTools = req.toolSpecifications() != null && !req.toolSpecifications().isEmpty();
            var hasToolResults = req.messages().stream()
                    .anyMatch(m -> m instanceof dev.langchain4j.data.message.ToolExecutionResultMessage);
            if (hasTools && !hasToolResults) {
                var toolCall = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id("call-contract-1")
                        .name("contract_delete")
                        .arguments("{\"id\":\"r-1\"}")
                        .build();
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(toolCall)))
                        .build());
            } else {
                handler.onPartialResponse("Hello world");
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from("Hello world"))
                        .build());
            }
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        var runtime = new TestableLangChain4jRuntime(model);
        return runtime;
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
    protected Set<AiCapability> expectedCapabilities() {
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.TOOL_CALLING,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.SYSTEM_PROMPT,
                AiCapability.TOOL_APPROVAL,
                AiCapability.VISION,
                AiCapability.AUDIO,
                AiCapability.MULTI_MODAL,
                AiCapability.PROMPT_CACHING);
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
     * on LangChain4j. The context carries a {@link org.atmosphere.ai.llm.CacheHint#conservative()}
     * under the canonical metadata key; {@code doExecute} reads it and
     * threads the key through an {@code OpenAiChatRequestParameters}
     * builder. Dispatch continues until the unconfigured
     * {@code StreamingChatModel} throws, which the base assertion catches as
     * skip-not-fail.
     */
    @Override
    protected AgentExecutionContext createCacheContext() {
        var metadata = Map.<String, Object>of(
                org.atmosphere.ai.llm.CacheHint.METADATA_KEY,
                org.atmosphere.ai.llm.CacheHint.conservative("lc4j-cache-test"));
        return new AgentExecutionContext(
                "Hello, cached.", "You are helpful", "gpt-4",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), metadata,
                List.of(), null, null, List.of(), List.of(),
                org.atmosphere.ai.approval.ToolApprovalPolicy.annotated());
    }

    @Override
    protected AgentExecutionContext createApprovalTriggerContext() {
        var sensitiveTool = org.atmosphere.ai.tool.ToolDefinition
                .builder("contract_delete", "test-only deletion")
                .parameter("id", "row id", "string")
                .executor(args -> "deleted:" + args.get("id"))
                .requiresApproval("Approve contract deletion?", 60)
                .build();
        return new AgentExecutionContext(
                "Delete row r-1", "You are helpful", "gpt-4",
                null, "session-1", "user-1", "conv-1",
                List.of(sensitiveTool), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(), List.of(),
                org.atmosphere.ai.approval.ToolApprovalPolicy.annotated());
    }

    @Test
    void langChain4jDeclaresToolCalling() {
        assertTrue(createRuntime().capabilities().contains(AiCapability.TOOL_CALLING));
    }

    @Test
    void langChain4jDeclaresStructuredOutput() {
        assertTrue(createRuntime().capabilities().contains(AiCapability.STRUCTURED_OUTPUT));
    }

    static class TestableLangChain4jRuntime extends LangChain4jAgentRuntime {
        TestableLangChain4jRuntime(StreamingChatModel model) {
            setNativeClient(model);
        }
    }
}
