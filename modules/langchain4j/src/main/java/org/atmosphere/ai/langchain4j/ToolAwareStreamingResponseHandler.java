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

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Streaming response handler that supports tool calling with LangChain4j.
 *
 * <p>When the model responds with tool execution requests instead of text,
 * this handler executes the tools and re-submits the conversation with the
 * results, allowing the model to continue generating a text response.</p>
 *
 * <p>The tool execution loop continues until the model produces a text
 * response (no more tool calls) or a maximum number of tool rounds is
 * reached to prevent infinite loops.</p>
 */
class ToolAwareStreamingResponseHandler implements StreamingChatResponseHandler {

    private static final Logger logger = LoggerFactory.getLogger(ToolAwareStreamingResponseHandler.class);
    private static final int MAX_TOOL_ROUNDS = 5;

    private final StreamingSession session;
    private final StreamingChatModel model;
    private final List<ChatMessage> conversationHistory;
    private final List<ToolSpecification> toolSpecifications;
    private final Map<String, ToolDefinition> toolMap;
    private int toolRound;

    ToolAwareStreamingResponseHandler(
            StreamingSession session,
            StreamingChatModel model,
            List<ChatMessage> conversationHistory,
            List<ToolSpecification> toolSpecifications,
            Map<String, ToolDefinition> toolMap) {
        this(session, model, conversationHistory, toolSpecifications, toolMap, 0);
    }

    private ToolAwareStreamingResponseHandler(
            StreamingSession session,
            StreamingChatModel model,
            List<ChatMessage> conversationHistory,
            List<ToolSpecification> toolSpecifications,
            Map<String, ToolDefinition> toolMap,
            int toolRound) {
        this.session = session;
        this.model = model;
        this.conversationHistory = new ArrayList<>(conversationHistory);
        this.toolSpecifications = toolSpecifications;
        this.toolMap = toolMap;
        this.toolRound = toolRound;
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        session.send(partialResponse);
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        var aiMessage = completeResponse.aiMessage();
        if (aiMessage == null) {
            session.complete();
            return;
        }

        // Check if the model wants to call tools
        if (aiMessage.hasToolExecutionRequests() && !toolMap.isEmpty()) {
            if (toolRound >= MAX_TOOL_ROUNDS) {
                logger.warn("Max tool rounds ({}) reached, completing response", MAX_TOOL_ROUNDS);
                if (aiMessage.text() != null) {
                    session.complete(aiMessage.text());
                } else {
                    session.complete();
                }
                return;
            }

            logger.debug("Tool round {}: executing {} tool calls",
                    toolRound + 1, aiMessage.toolExecutionRequests().size());

            // Execute the requested tools
            var toolResults = LangChain4jToolBridge.executeToolCalls(aiMessage, toolMap);

            // Build updated conversation with tool results
            var updatedMessages = new ArrayList<>(conversationHistory);
            updatedMessages.add(aiMessage);
            updatedMessages.addAll(toolResults);

            // Re-submit to the model with tool results
            var followUpRequest = ChatRequest.builder()
                    .messages(updatedMessages)
                    .toolSpecifications(toolSpecifications)
                    .build();

            var nextHandler = new ToolAwareStreamingResponseHandler(
                    session, model, updatedMessages, toolSpecifications, toolMap, toolRound + 1);
            model.chat(followUpRequest, nextHandler);
        } else {
            // No tool calls — deliver the final text response
            if (aiMessage.text() != null) {
                session.complete(aiMessage.text());
            } else {
                session.complete();
            }
        }
    }

    @Override
    public void onError(Throwable error) {
        session.error(error);
    }
}
