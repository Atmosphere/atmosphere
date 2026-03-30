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

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.atmosphere.ai.AbstractAgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@link org.atmosphere.ai.AiSupport} implementation backed by LangChain4j's
 * {@link StreamingChatModel}.
 *
 * <p>Auto-detected when {@code langchain4j-core} is on the classpath.
 * The model must be configured via {@link #setModel} — typically done
 * by application configuration or Spring auto-configuration.</p>
 */
public class LangChain4jAgentRuntime extends AbstractAgentRuntime<StreamingChatModel> {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jAgentRuntime.class);

    @Override
    public String name() {
        return "langchain4j";
    }

    @Override
    protected String nativeClientClassName() {
        return "dev.langchain4j.model.chat.StreamingChatModel";
    }

    @Override
    protected String clientDescription() {
        return "StreamingChatModel";
    }

    @Override
    protected String configurationHint() {
        return "Call LangChain4jAgentRuntime.setModel() or use Spring auto-configuration.";
    }

    @Override
    protected StreamingChatModel createNativeClient(AiConfig.LlmSettings settings) {
        var apiKey = settings.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        try {
            Class.forName("dev.langchain4j.model.openai.OpenAiStreamingChatModel");
        } catch (ClassNotFoundException e) {
            logger.info("langchain4j-open-ai not on classpath; add it or call setModel() manually");
            return null;
        }

        var model = dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
                .baseUrl(settings.baseUrl())
                .apiKey(apiKey)
                .modelName(settings.model())
                .build();
        logger.info("LangChain4j auto-configured: model={}, endpoint={}", settings.model(), settings.baseUrl());
        return model;
    }

    /**
     * Set the {@link StreamingChatModel} to use for streaming.
     */
    public static void setModel(StreamingChatModel streamingModel) {
        staticModel = streamingModel;
    }

    // Held for static setter compatibility with Spring auto-configuration
    private static volatile StreamingChatModel staticModel;

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        // If a static model was set via Spring auto-configuration, use it
        if (getNativeClient() == null && staticModel != null) {
            setNativeClient(staticModel);
        }
        super.configure(settings);
    }

    @Override
    protected void doExecute(StreamingChatModel streamingModel,
                            AgentExecutionContext context, StreamingSession session) {
        var messages = assembleMessages(context).stream()
                .map(LangChain4jAgentRuntime::toLangChainMessage)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        // Add tool specifications if tools are present
        var tools = context.tools();
        var toolSpecs = tools.isEmpty()
                ? List.<dev.langchain4j.agent.tool.ToolSpecification>of()
                : LangChain4jToolBridge.toToolSpecifications(tools);

        var chatRequestBuilder = ChatRequest.builder().messages(messages);
        if (context.model() != null && !context.model().isBlank()) {
            chatRequestBuilder.modelName(context.model());
            logger.debug("Using per-request model override: {}", context.model());
        }
        if (!toolSpecs.isEmpty()) {
            chatRequestBuilder.toolSpecifications(toolSpecs);
            logger.debug("Registered {} tool specifications with LangChain4j", toolSpecs.size());
        }

        var toolMap = tools.isEmpty()
                ? java.util.Map.<String, org.atmosphere.ai.tool.ToolDefinition>of()
                : ToolExecutionHelper.toToolMap(tools);

        var handler = new ToolAwareStreamingResponseHandler(
                session, streamingModel, messages, toolSpecs, toolMap);
        streamingModel.chat(chatRequestBuilder.build(), handler);
    }

    @Override
    public Set<AiCapability> capabilities() {
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.TOOL_CALLING,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.SYSTEM_PROMPT
        );
    }

    private static dev.langchain4j.data.message.ChatMessage toLangChainMessage(
            org.atmosphere.ai.llm.ChatMessage msg) {
        return switch (msg.role()) {
            case "assistant" -> AiMessage.from(msg.content());
            case "system" -> SystemMessage.from(msg.content());
            default -> UserMessage.from(msg.content());
        };
    }
}
