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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.ai.StreamingSession;
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
import java.util.LinkedHashMap;

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

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final Duration timeout;

    private OpenAiCompatibleClient(String baseUrl, String apiKey, HttpClient httpClient, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.timeout = timeout;
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
            session.progress("Connecting to AI model...");

            var requestBody = buildRequestBody(request);
            var httpRequest = buildHttpRequest(requestBody);

            logger.debug("Streaming chat completion: model={}, messages={}", request.model(), request.messages().size());

            var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                var errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                logger.error("LLM API error ({}): {}", response.statusCode(), errorBody);
                session.error(new LlmException("API returned " + response.statusCode() + ": " + extractErrorMessage(errorBody)));
                return;
            }

            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (session.isClosed()) {
                        break;
                    }
                    processSSELine(line, session);
                }
            }

            if (!session.isClosed()) {
                session.complete();
            }
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

    private void processSSELine(String line, StreamingSession session) {
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

            // Extract content token
            var contentNode = delta.get("content");
            if (contentNode != null && !contentNode.isNull()) {
                var token = contentNode.asText();
                if (!token.isEmpty()) {
                    session.send(token);
                }
            }

            // Check for finish reason
            var finishNode = firstChoice.get("finish_reason");
            if (finishNode != null && !finishNode.isNull()) {
                var reason = finishNode.asText();
                if (!"null".equals(reason)) {
                    logger.debug("Stream finished: reason={}", reason);
                }
            }

            // Forward usage metadata if present
            var usageNode = node.get("usage");
            if (usageNode != null && !usageNode.isNull()) {
                if (usageNode.has("total_tokens")) {
                    session.sendMetadata("usage.totalTokens", usageNode.get("total_tokens").asInt());
                }
                if (usageNode.has("prompt_tokens")) {
                    session.sendMetadata("usage.promptTokens", usageNode.get("prompt_tokens").asInt());
                }
                if (usageNode.has("completion_tokens")) {
                    session.sendMetadata("usage.completionTokens", usageNode.get("completion_tokens").asInt());
                }
            }

            // Forward model metadata
            var modelNode = node.get("model");
            if (modelNode != null && !modelNode.isNull()) {
                session.sendMetadata("model", modelNode.asText());
            }
        } catch (JsonProcessingException e) {
            logger.warn("Failed to parse SSE data: {}", data, e);
        }
    }

    private String buildRequestBody(ChatCompletionRequest request) throws JsonProcessingException {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", request.model());
        body.put("messages", request.messages().stream()
                .map(m -> {
                    var msg = new LinkedHashMap<String, String>();
                    msg.put("role", m.role());
                    msg.put("content", m.content());
                    return msg;
                })
                .toList());
        body.put("stream", true);
        body.put("temperature", request.temperature());
        if (request.maxTokens() > 0) {
            body.put("max_tokens", request.maxTokens());
        }
        return MAPPER.writeValueAsString(body);
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
                return errorNode.get("message").asText();
            }
        } catch (Exception ignored) {
            // Fall through to return raw body
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

        public OpenAiCompatibleClient build() {
            var client = this.httpClient != null ? this.httpClient : HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            return new OpenAiCompatibleClient(baseUrl, apiKey, client, timeout);
        }
    }
}
