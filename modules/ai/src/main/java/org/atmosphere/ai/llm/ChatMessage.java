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
package org.atmosphere.ai.llm;

import java.util.List;

/**
 * A chat message with a role and content, following the OpenAI chat completions format.
 *
 * @param role       the message role ("system", "user", "assistant", "tool")
 * @param content    the message text
 * @param toolCallId the tool call ID this message responds to (only for role "tool")
 * @param name       the function name this tool result corresponds to (only for role "tool").
 *                   OpenAI treats this field as optional on tool messages, but some
 *                   OpenAI-compatible endpoints — notably Google's Gemini
 *                   {@code v1beta/openai} compatibility layer — require it because they
 *                   map the tool message to their native {@code function_response} which
 *                   has {@code name} as a required field. Populating it broadens
 *                   interop without breaking OpenAI itself.
 */
public record ChatMessage(
        String role,
        String content,
        String toolCallId,
        String name,
        List<ToolCall> toolCalls) {

    /**
     * One function invocation on an assistant message's {@code tool_calls}
     * array. Required for Gemini's OpenAI-compat layer to pair a
     * subsequent function_response with the originating function_call.
     *
     * @param id          the tool-call identifier
     * @param name        the function name being called
     * @param argumentsJson raw JSON-string arguments (already-stringified as
     *                    OpenAI's schema expects, not an Object tree)
     */
    public record ToolCall(String id, String name, String argumentsJson) {
    }

    public ChatMessage {
        toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
    }

    /** Four-arg constructor preserved for callers that carry name but not tool_calls. */
    public ChatMessage(String role, String content, String toolCallId, String name) {
        this(role, content, toolCallId, name, List.of());
    }

    /** Three-arg constructor preserved for callers that do not carry a function name. */
    public ChatMessage(String role, String content, String toolCallId) {
        this(role, content, toolCallId, null, List.of());
    }

    /** Two-arg constructor preserved for role/content pairs (system, user, assistant). */
    public ChatMessage(String role, String content) {
        this(role, content, null, null, List.of());
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null, List.of());
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null, List.of());
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, null, null, List.of());
    }

    /**
     * Assistant message carrying the tool_calls the model emitted. Required
     * for stricter OpenAI-compat endpoints (Gemini) to pair the subsequent
     * tool-role messages with their originating function_call.
     */
    public static ChatMessage assistantToolCalls(List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", null, null, null, toolCalls);
    }

    /**
     * Tool result without a function name. Preserved for callers that were built
     * before the {@code name} field existed. Prefer
     * {@link #tool(String, String, String)} so downstream serialization can
     * populate the optional {@code name} field for interop with stricter
     * OpenAI-compatible endpoints (Gemini compat).
     */
    public static ChatMessage tool(String content, String toolCallId) {
        return new ChatMessage("tool", content, toolCallId, null, List.of());
    }

    /** Tool result carrying the name of the function that produced it. */
    public static ChatMessage tool(String content, String toolCallId, String name) {
        return new ChatMessage("tool", content, toolCallId, name, List.of());
    }
}
