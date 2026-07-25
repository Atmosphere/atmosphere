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
package org.atmosphere.ai.openai;

import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.llm.ChatMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wire-format codec for the OpenAI-compatible serving endpoint: parses and
 * validates inbound {@code chat/completions} JSON at the boundary (malformed
 * input raises a 4xx {@link OpenAiError}, never a 500 — Correctness
 * Invariant #4) and builds the outbound {@code chat.completion} /
 * {@code chat.completion.chunk} / error envelopes.
 *
 * <p>Deliberately unsupported: {@code tools}, {@code tool_choice},
 * {@code functions}, {@code function_call}, {@code n > 1}, non-text
 * {@code response_format}, tool-role messages, and non-text content parts —
 * each is rejected with an explicit {@code unsupported_parameter} error
 * rather than half-supported. Sampling parameters ({@code temperature},
 * {@code top_p}, {@code max_tokens}, {@code stop}) are accepted and ignored:
 * generation parameters are controlled server-side via the framework's
 * {@code GenerationParams} configuration, and the serving layer does not
 * override them per request.</p>
 */
final class OpenAiWire {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAiWire() {
    }

    /**
     * A validated inbound chat-completion call.
     *
     * @param model       requested model name, or {@code null} when absent
     * @param priorTurns  every message before the final one, mapped to
     *                    framework {@link ChatMessage} entries (client-sent
     *                    {@code system} / {@code developer} messages ride
     *                    along as system-role history — they never replace
     *                    the agent's own system prompt)
     * @param userMessage the final user message dispatched to the pipeline
     * @param stream      whether SSE streaming was requested
     * @param user        the OpenAI {@code user} field, or {@code null}
     */
    record InboundChatCompletion(
            String model,
            List<ChatMessage> priorTurns,
            String userMessage,
            boolean stream,
            String user) {
    }

    /**
     * Parse and validate a chat-completions request body.
     *
     * @throws OpenAiError 400 for malformed / unsupported input
     */
    static InboundChatCompletion parse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JacksonException e) {
            throw OpenAiError.invalidRequest("Invalid JSON payload.");
        }
        if (root == null || !root.isObject()) {
            throw OpenAiError.invalidRequest("Request body must be a JSON object.");
        }

        rejectUnsupported(root, "tools",
                "Tool calling is not supported on this endpoint; register tools on the agent instead.");
        rejectUnsupported(root, "tool_choice",
                "Tool calling is not supported on this endpoint.");
        rejectUnsupported(root, "functions",
                "Function calling is not supported on this endpoint.");
        rejectUnsupported(root, "function_call",
                "Function calling is not supported on this endpoint.");
        if (present(root, "n") && root.get("n").isNumber() && root.get("n").asInt() != 1) {
            throw OpenAiError.unsupportedParameter("n", "Only n=1 is supported.");
        }
        if (present(root, "response_format")) {
            var format = root.get("response_format");
            var type = format.isObject() && format.has("type") && format.get("type").isString()
                    ? format.get("type").stringValue() : null;
            if (!"text".equals(type)) {
                throw OpenAiError.unsupportedParameter("response_format",
                        "Only text responses are supported on this endpoint.");
            }
        }

        var model = optionalString(root, "model");
        var user = optionalString(root, "user");
        var stream = false;
        if (present(root, "stream")) {
            if (!root.get("stream").isBoolean()) {
                throw OpenAiError.invalidRequest("'stream' must be a boolean.", "stream");
            }
            stream = root.get("stream").asBoolean();
        }

        var messagesNode = root.get("messages");
        if (messagesNode == null || !messagesNode.isArray() || messagesNode.isEmpty()) {
            throw OpenAiError.invalidRequest("'messages' must be a non-empty array.", "messages");
        }
        var mapped = new ArrayList<ChatMessage>(messagesNode.size());
        for (var messageNode : messagesNode) {
            mapped.add(mapMessage(messageNode));
        }
        var last = mapped.get(mapped.size() - 1);
        if (!"user".equals(last.role()) || last.content() == null || last.content().isBlank()) {
            throw OpenAiError.invalidRequest(
                    "The last message must have role 'user' with non-empty content.", "messages");
        }
        return new InboundChatCompletion(model, List.copyOf(mapped.subList(0, mapped.size() - 1)),
                last.content(), stream, user);
    }

    private static ChatMessage mapMessage(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw OpenAiError.invalidRequest("Each message must be a JSON object.", "messages");
        }
        if (present(node, "tool_calls")) {
            throw OpenAiError.unsupportedParameter("messages",
                    "Assistant tool_calls are not supported on this endpoint.");
        }
        var roleNode = node.get("role");
        if (roleNode == null || !roleNode.isString()) {
            throw OpenAiError.invalidRequest("Each message requires a string 'role'.", "messages");
        }
        var role = switch (roleNode.stringValue()) {
            case "system", "developer" -> "system";
            case "user" -> "user";
            case "assistant" -> "assistant";
            case "tool" -> throw OpenAiError.unsupportedParameter("messages",
                    "Tool-role messages are not supported on this endpoint.");
            default -> throw OpenAiError.invalidRequest(
                    "Unsupported message role '" + roleNode.stringValue() + "'.", "messages");
        };
        return new ChatMessage(role, extractContent(node));
    }

    /**
     * Extract text content from a message: a plain string, or the OpenAI
     * content-parts array restricted to {@code {"type":"text"}} parts.
     */
    private static String extractContent(JsonNode message) {
        var content = message.get("content");
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isString()) {
            return content.stringValue();
        }
        if (content.isArray()) {
            var text = new StringBuilder();
            for (var part : content) {
                var type = part.isObject() && part.has("type") && part.get("type").isString()
                        ? part.get("type").stringValue() : null;
                if (!"text".equals(type)) {
                    throw OpenAiError.unsupportedParameter("messages",
                            "Only text content parts are supported on this endpoint.");
                }
                var textNode = part.get("text");
                if (textNode == null || !textNode.isString()) {
                    throw OpenAiError.invalidRequest(
                            "Text content parts require a string 'text' field.", "messages");
                }
                text.append(textNode.stringValue());
            }
            return text.toString();
        }
        throw OpenAiError.invalidRequest(
                "Message 'content' must be a string or an array of content parts.", "messages");
    }

    private static void rejectUnsupported(JsonNode root, String field, String message) {
        if (present(root, field)) {
            var node = root.get(field);
            if (!(node.isArray() && node.isEmpty())) {
                throw OpenAiError.unsupportedParameter(field, message);
            }
        }
    }

    private static boolean present(JsonNode root, String field) {
        return root.has(field) && !root.get(field).isNull();
    }

    private static String optionalString(JsonNode root, String field) {
        if (!present(root, field)) {
            return null;
        }
        if (!root.get(field).isString()) {
            throw OpenAiError.invalidRequest("'" + field + "' must be a string.", field);
        }
        var value = root.get(field).stringValue();
        return value.isBlank() ? null : value;
    }

    /** Build a non-streaming {@code chat.completion} envelope. */
    static String completionJson(String id, long created, String model,
                                 String content, TokenUsage usage) {
        var body = new LinkedHashMap<String, Object>();
        body.put("id", id);
        body.put("object", "chat.completion");
        body.put("created", created);
        body.put("model", model);
        var message = new LinkedHashMap<String, Object>();
        message.put("role", "assistant");
        message.put("content", content);
        var choice = new LinkedHashMap<String, Object>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");
        body.put("choices", List.of(choice));
        if (usage != null && usage.hasCounts()) {
            body.put("usage", usageMap(usage));
        }
        return MAPPER.writeValueAsString(body);
    }

    /** Build a {@code chat.completion.chunk} envelope with the given delta. */
    static String chunkJson(String id, long created, String model,
                            Map<String, Object> delta, String finishReason) {
        var body = new LinkedHashMap<String, Object>();
        body.put("id", id);
        body.put("object", "chat.completion.chunk");
        body.put("created", created);
        body.put("model", model);
        var choice = new LinkedHashMap<String, Object>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        body.put("choices", List.of(choice));
        return MAPPER.writeValueAsString(body);
    }

    /**
     * Build the final usage chunk (empty {@code choices}, populated
     * {@code usage}) emitted just before {@code data: [DONE]}.
     */
    static String usageChunkJson(String id, long created, String model, TokenUsage usage) {
        var body = new LinkedHashMap<String, Object>();
        body.put("id", id);
        body.put("object", "chat.completion.chunk");
        body.put("created", created);
        body.put("model", model);
        body.put("choices", List.of());
        body.put("usage", usageMap(usage));
        return MAPPER.writeValueAsString(body);
    }

    /** Build the OpenAI error envelope for the given boundary error. */
    static String errorJson(OpenAiError error) {
        var inner = new LinkedHashMap<String, Object>();
        inner.put("message", error.getMessage());
        inner.put("type", error.type());
        inner.put("param", error.param());
        inner.put("code", error.code());
        return MAPPER.writeValueAsString(Map.of("error", inner));
    }

    /** Build the {@code GET /v1/models} listing envelope. */
    static String modelsJson(Collection<String> ids, long created) {
        var data = new ArrayList<Map<String, Object>>(ids.size());
        for (var id : ids) {
            var model = new LinkedHashMap<String, Object>();
            model.put("id", id);
            model.put("object", "model");
            model.put("created", created);
            model.put("owned_by", "atmosphere");
            data.add(model);
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("object", "list");
        body.put("data", data);
        return MAPPER.writeValueAsString(body);
    }

    private static Map<String, Object> usageMap(TokenUsage usage) {
        var map = new LinkedHashMap<String, Object>();
        map.put("prompt_tokens", usage.input());
        map.put("completion_tokens", usage.output());
        map.put("total_tokens", usage.total());
        return map;
    }
}
