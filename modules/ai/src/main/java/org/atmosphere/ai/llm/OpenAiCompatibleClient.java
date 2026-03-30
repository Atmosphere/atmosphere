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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.RetryPolicy;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolBridgeUtils;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM client that speaks the OpenAI-compatible chat completions API.
 * Works with OpenAI, Google Gemini, Ollama, Azure OpenAI, Groq, Together AI,
 * and any endpoint that implements the OpenAI streaming format.
 *
 * <p>Uses JDK 21's {@link HttpClient} with no external HTTP dependencies.</p>
 *
 * <p>Supported endpoints:</p>
 * <ul>
 *   <li>OpenAI: {@code https://api.openai.com/v1}</li>
 *   <li>Gemini: {@code https://generativelanguage.googleapis.com/v1beta/openai}</li>
 *   <li>Ollama: {@code http://localhost:11434/v1}</li>
 *   <li>Azure: {@code https://{resource}.openai.azure.com/openai/deployments/{model}}</li>
 * </ul>
 */
public class OpenAiCompatibleClient implements LlmClient {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiCompatibleClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DATA_PREFIX = "data: ";
    private static final String DONE_MARKER = "[DONE]";
    private static final int MAX_TOOL_ROUNDS = 5;

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final RetryPolicy retryPolicy;

    private OpenAiCompatibleClient(String baseUrl, String apiKey, HttpClient httpClient,
                                   Duration timeout, RetryPolicy retryPolicy) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.timeout = timeout;
        this.retryPolicy = retryPolicy;
    }

    /**
     * Returns the API key configured for this client (may be null).
     */
    public String apiKey() {
        return apiKey;
    }

    /**
     * Create a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Quick factory for common configurations.
     */
    public static OpenAiCompatibleClient gemini(String apiKey) {
        return builder()
                .baseUrl("https://generativelanguage.googleapis.com/v1beta/openai")
                .apiKey(apiKey)
                .build();
    }

    /**
     * Quick factory for local Ollama.
     */
    public static OpenAiCompatibleClient ollama() {
        return builder()
                .baseUrl("http://localhost:11434/v1")
                .build();
    }

    /**
     * Quick factory for OpenAI.
     */
    public static OpenAiCompatibleClient openai(String apiKey) {
        return builder()
                .baseUrl("https://api.openai.com/v1")
                .apiKey(apiKey)
                .build();
    }

    @Override
    public void streamChatCompletion(ChatCompletionRequest request, StreamingSession session) {
        try {
            doStreamWithToolLoop(request, session, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error(e);
        } catch (Exception e) {
            logger.error("Error during chat completion streaming", e);
            if (!session.isClosed()) {
                session.error(e);
            }
        }
    }

    private void doStreamWithToolLoop(ChatCompletionRequest request,
                                      StreamingSession session, int toolRound)
            throws InterruptedException {

        var requestBody = buildRequestBody(request);
        logger.debug("Streaming chat completion: model={}, messages={}, tools={}, round={}",
                request.model(), request.messages().size(), request.tools().size(), toolRound);

        var response = sendWithRetry(requestBody, session);
        if (response == null) {
            return;
        }

        var accumulators = new HashMap<Integer, ToolCallAccumulator>();
        var toolCallsRequested = new boolean[]{false};

        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (session.isClosed()) {
                    break;
                }
                processSSELine(line, session, accumulators, toolCallsRequested);
            }
        } catch (java.io.IOException e) {
            logger.error("Error reading SSE stream", e);
            if (!session.isClosed()) {
                session.error(e);
            }
            return;
        }

        // If the model requested tool calls, execute them and re-submit
        if (toolCallsRequested[0] && !accumulators.isEmpty() && !request.tools().isEmpty()) {
            if (toolRound >= MAX_TOOL_ROUNDS) {
                logger.warn("Max tool rounds ({}) reached, completing response", MAX_TOOL_ROUNDS);
                if (!session.isClosed()) {
                    session.complete();
                }
                return;
            }

            var toolMap = ToolExecutionHelper.toToolMap(request.tools());
            var updatedMessages = new ArrayList<>(request.messages());

            // Build assistant message with tool_calls reference (no text content)
            var toolCallIds = new ArrayList<String>();
            for (var entry : accumulators.entrySet()) {
                var acc = entry.getValue();
                toolCallIds.add(acc.id());
            }
            // Add an assistant message placeholder for the tool call turn
            updatedMessages.add(new ChatMessage("assistant", null));

            // Execute each tool call and add result messages
            for (var entry : accumulators.entrySet()) {
                var acc = entry.getValue();
                var toolName = acc.functionName();
                var args = ToolBridgeUtils.parseJsonArgs(acc.arguments());

                session.emit(new AiEvent.ToolStart(toolName, args));

                var tool = toolMap.get(toolName);
                if (tool == null) {
                    logger.warn("Tool not found: {}", toolName);
                    var errorResult = "{\"error\":\"Tool not found: " + toolName + "\"}";
                    session.emit(new AiEvent.ToolError(toolName, "Tool not found"));
                    updatedMessages.add(ChatMessage.tool(errorResult, acc.id()));
                    continue;
                }

                var resultStr = ToolExecutionHelper.executeAndFormat(toolName, tool.executor(), args);
                session.emit(new AiEvent.ToolResult(toolName, resultStr));
                updatedMessages.add(ChatMessage.tool(resultStr, acc.id()));
            }

            logger.debug("Tool round {}: executed {} tool calls, re-submitting",
                    toolRound + 1, accumulators.size());

            var followUp = new ChatCompletionRequest(
                    request.model(), List.copyOf(updatedMessages),
                    request.temperature(), request.maxStreamingTexts(),
                    request.jsonMode(), request.tools());
            doStreamWithToolLoop(followUp, session, toolRound + 1);
        } else if (!session.isClosed()) {
            session.complete();
        }
    }

    private HttpResponse<java.io.InputStream> sendWithRetry(String requestBody,
                                                             StreamingSession session)
            throws InterruptedException {
        HttpResponse<java.io.InputStream> response = null;
        Exception lastException = null;

        var maxRetries = retryPolicy.maxRetries();
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                var httpRequest = buildHttpRequest(requestBody);
                response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() == 200) {
                    return response;
                }

                String errorBody;
                try (var errorStream = response.body()) {
                    errorBody = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
                }

                if (!isRetryable(response.statusCode()) || attempt == maxRetries) {
                    logger.error("LLM API error ({}): {}", response.statusCode(), errorBody);
                    session.error(new LlmException("API returned " + response.statusCode()
                            + ": " + extractErrorMessage(errorBody)));
                    return null;
                }

                var delay = computeRetryDelay(attempt, response);
                logger.warn("LLM API error ({}), retrying in {}ms (attempt {}/{})",
                        response.statusCode(), delay.toMillis(), attempt + 1, maxRetries);
                Thread.sleep(delay.toMillis());
                response = null;
            } catch (java.net.http.HttpTimeoutException e) {
                lastException = e;
                if (attempt == maxRetries) {
                    break;
                }
                var delay = computeRetryDelay(attempt, null);
                logger.warn("LLM request timeout, retrying in {}ms (attempt {}/{}): {}",
                        delay.toMillis(), attempt + 1, maxRetries, e.getMessage());
                Thread.sleep(delay.toMillis());
            } catch (java.io.IOException e) {
                lastException = e;
                if (attempt == maxRetries) {
                    break;
                }
                var delay = computeRetryDelay(attempt, null);
                logger.warn("LLM connection error, retrying in {}ms (attempt {}/{}): {}",
                        delay.toMillis(), attempt + 1, maxRetries, e.getMessage());
                Thread.sleep(delay.toMillis());
            }
        }

        var cause = lastException != null ? lastException
                : new LlmException("Failed after " + (maxRetries + 1) + " attempts");
        logger.error("LLM API failed after {} retries", maxRetries, cause);
        if (!session.isClosed()) {
            session.error(cause);
        }
        return null;
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502 || statusCode == 503;
    }

    private Duration computeRetryDelay(int attempt, HttpResponse<?> response) {
        // Respect Retry-After header on 429 responses
        if (response != null && response.statusCode() == 429) {
            var retryAfter = response.headers().firstValue("Retry-After");
            if (retryAfter.isPresent()) {
                try {
                    var seconds = Integer.parseInt(retryAfter.get());
                    return Duration.ofSeconds(Math.min(seconds, 60));
                } catch (NumberFormatException ex) {
                    logger.trace("Failed to parse Retry-After header", ex);
                }
            }
        }
        return retryPolicy.delayForAttempt(attempt);
    }

    private void processSSELine(String line, StreamingSession session,
                                Map<Integer, ToolCallAccumulator> accumulators,
                                boolean[] toolCallsRequested) {
        if (line.isBlank() || !line.startsWith(DATA_PREFIX)) {
            return;
        }

        var data = line.substring(DATA_PREFIX.length()).trim();
        if (DONE_MARKER.equals(data)) {
            return;
        }

        try {
            var node = MAPPER.readTree(data);
            var choices = node.get("choices");
            if (choices == null || choices.isEmpty()) {
                return;
            }

            var firstChoice = choices.get(0);
            var delta = firstChoice.get("delta");
            if (delta == null) {
                return;
            }

            // Extract content text
            var contentNode = delta.get("content");
            if (contentNode != null && !contentNode.isNull() && contentNode.isString()) {
                var text = contentNode.stringValue();
                if (!text.isEmpty()) {
                    session.send(text);
                }
            }

            // Accumulate tool call fragments
            var toolCallsNode = delta.get("tool_calls");
            if (toolCallsNode != null && toolCallsNode.isArray()) {
                for (var tcNode : toolCallsNode) {
                    var index = tcNode.has("index") ? tcNode.get("index").asInt() : 0;
                    var acc = accumulators.computeIfAbsent(index, k -> new ToolCallAccumulator());

                    if (tcNode.has("id")) {
                        acc.setId(tcNode.get("id").stringValue());
                    }
                    var fnNode = tcNode.get("function");
                    if (fnNode != null) {
                        if (fnNode.has("name") && !fnNode.get("name").isNull()) {
                            acc.setFunctionName(fnNode.get("name").stringValue());
                        }
                        if (fnNode.has("arguments") && !fnNode.get("arguments").isNull()) {
                            acc.appendArguments(fnNode.get("arguments").stringValue());
                        }
                    }
                }
            }

            // Check for finish reason
            var finishNode = firstChoice.get("finish_reason");
            if (finishNode != null && !finishNode.isNull()) {
                var reason = finishNode.stringValue();
                if ("tool_calls".equals(reason)) {
                    toolCallsRequested[0] = true;
                }
                if (!"null".equals(reason)) {
                    logger.debug("Stream finished: reason={}", reason);
                }
            }

            // Forward usage metadata if present
            var usageNode = node.get("usage");
            if (usageNode != null && !usageNode.isNull()) {
                forwardUsageMetadata(usageNode, session);
            }

            // Forward model metadata
            var modelNode = node.get("model");
            if (modelNode != null && !modelNode.isNull()) {
                session.sendMetadata("ai.model", modelNode.stringValue());
            }
        } catch (JacksonException e) {
            logger.warn("Failed to parse SSE data: {}", data, e);
        }
    }

    private static void forwardUsageMetadata(tools.jackson.databind.JsonNode usageNode,
                                             StreamingSession session) {
        if (usageNode.has("prompt_tokens")) {
            session.sendMetadata("ai.tokens.input", usageNode.get("prompt_tokens").asInt());
        }
        if (usageNode.has("completion_tokens")) {
            session.sendMetadata("ai.tokens.output", usageNode.get("completion_tokens").asInt());
        }
        if (usageNode.has("total_tokens")) {
            session.sendMetadata("ai.tokens.total", usageNode.get("total_tokens").asInt());
        }
    }

    private String buildRequestBody(ChatCompletionRequest request) throws JacksonException {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", request.model());
        body.put("messages", request.messages().stream()
                .map(OpenAiCompatibleClient::serializeMessage)
                .toList());
        body.put("stream", true);
        body.put("temperature", request.temperature());
        if (request.maxStreamingTexts() > 0) {
            body.put("max_tokens", request.maxStreamingTexts());
        }
        if (request.jsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        if (!request.tools().isEmpty()) {
            body.put("tools", serializeTools(request.tools()));
        }
        return MAPPER.writeValueAsString(body);
    }

    private static Map<String, Object> serializeMessage(ChatMessage m) {
        var msg = new LinkedHashMap<String, Object>();
        msg.put("role", m.role());
        if (m.content() != null) {
            msg.put("content", m.content());
        }
        if (m.toolCallId() != null) {
            msg.put("tool_call_id", m.toolCallId());
        }
        return msg;
    }

    private static List<Map<String, Object>> serializeTools(List<ToolDefinition> tools) {
        return tools.stream().map(tool -> {
            var fn = new LinkedHashMap<String, Object>();
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.put("parameters", buildParametersObject(tool));
            var wrapper = new LinkedHashMap<String, Object>();
            wrapper.put("type", "function");
            wrapper.put("function", fn);
            return (Map<String, Object>) wrapper;
        }).toList();
    }

    private static Map<String, Object> buildParametersObject(ToolDefinition tool) {
        var params = new LinkedHashMap<String, Object>();
        params.put("type", "object");
        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        for (var p : tool.parameters()) {
            var prop = new LinkedHashMap<String, String>();
            prop.put("type", p.type());
            prop.put("description", p.description());
            properties.put(p.name(), prop);
            if (p.required()) {
                required.add(p.name());
            }
        }
        params.put("properties", properties);
        params.put("required", required);
        return params;
    }

    private HttpRequest buildHttpRequest(String requestBody) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(timeout);

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        return builder.build();
    }

    private String extractErrorMessage(String errorBody) {
        try {
            var node = MAPPER.readTree(errorBody);
            // Handle array response (e.g. Gemini: [{"error":{...}}])
            if (node.isArray() && !node.isEmpty()) {
                node = node.get(0);
            }
            var errorNode = node.get("error");
            if (errorNode != null && errorNode.has("message")) {
                return errorNode.get("message").stringValue();
            }
        } catch (Exception ex) {
            logger.trace("Failed to parse error response JSON", ex);
        }
        return errorBody.length() > 200 ? errorBody.substring(0, 200) + "..." : errorBody;
    }

    /**
     * Exception for LLM API errors.
     */
    public static class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }
    }

    public static final class Builder {
        private String baseUrl = "http://localhost:11434/v1";
        private String apiKey;
        private HttpClient httpClient;
        private Duration timeout = Duration.ofSeconds(120);
        private RetryPolicy retryPolicy;
        private int maxRetries = 3;
        private Duration retryBaseDelay = Duration.ofMillis(500);

        private Builder() {
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Set a custom retry policy. Overrides {@link #maxRetries} and {@link #retryBaseDelay}.
         */
        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        /**
         * Maximum number of retries on transient errors (429, 500, 502, 503)
         * and connection/timeout failures. Default is 3.
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Base delay for exponential backoff between retries.
         * Actual delay is {@code base * 2^attempt} with 20% jitter, capped at 30s.
         * Default is 500ms.
         */
        public Builder retryBaseDelay(Duration retryBaseDelay) {
            this.retryBaseDelay = retryBaseDelay;
            return this;
        }

        public OpenAiCompatibleClient build() {
            var client = this.httpClient != null ? this.httpClient : HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            var policy = this.retryPolicy != null ? this.retryPolicy
                    : new RetryPolicy(maxRetries, retryBaseDelay, Duration.ofSeconds(30),
                            2.0, RetryPolicy.DEFAULT.retryableErrors());
            return new OpenAiCompatibleClient(baseUrl, apiKey, client, timeout, policy);
        }
    }
}
