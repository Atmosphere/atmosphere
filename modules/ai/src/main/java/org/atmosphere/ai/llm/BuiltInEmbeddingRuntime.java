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
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.EmbeddingRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * {@link EmbeddingRuntime} that posts to an OpenAI-compatible {@code /v1/embeddings}
 * endpoint. Works with OpenAI, Azure OpenAI, Gemini's OpenAI gateway, Ollama,
 * and any compatible proxy — same configuration surface as
 * {@link OpenAiCompatibleClient} for the chat path.
 *
 * <p>Configuration comes from {@link AiConfig}: base URL, API key, and the
 * embedding-model name (defaults to {@code text-embedding-3-small}). The
 * caller can override the per-instance model via
 * {@link #setEmbeddingModel(String)}.</p>
 *
 * <p>Discovered through the standard {@code ServiceLoader} path so
 * programmatic callers can use {@code EmbeddingRuntime} without wiring a
 * framework-specific client. Priority sits below Spring AI / LC4j so that
 * when a user configures a native {@code EmbeddingModel} the adapter
 * wrapper wins — the Built-in runtime is the zero-dep fallback.</p>
 */
public class BuiltInEmbeddingRuntime implements EmbeddingRuntime {

    private static final Logger logger = LoggerFactory.getLogger(BuiltInEmbeddingRuntime.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_MODEL = "text-embedding-3-small";

    private volatile String modelOverride;
    private final HttpClient httpClient;

    public BuiltInEmbeddingRuntime() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** Test / programmatic hook: override the embedding model name. */
    public void setEmbeddingModel(String model) {
        this.modelOverride = model;
    }

    @Override
    public String name() {
        return "built-in";
    }

    @Override
    public boolean isAvailable() {
        var settings = AiConfig.get();
        if (settings == null) {
            return false;
        }
        var apiKey = settings.apiKey();
        var baseUrl = settings.baseUrl();
        return baseUrl != null && !baseUrl.isBlank()
                && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public float[] embed(String text) {
        Objects.requireNonNull(text, "text must not be null");
        var vectors = embedAll(List.of(text));
        return vectors.get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        Objects.requireNonNull(texts, "texts must not be null");
        if (texts.isEmpty()) {
            return List.of();
        }
        var settings = AiConfig.get();
        if (settings == null || settings.baseUrl() == null || settings.apiKey() == null) {
            throw new IllegalStateException("Built-in embedding runtime requires AiConfig.baseUrl + apiKey");
        }
        var apiKey = settings.apiKey();
        var baseUrl = settings.baseUrl();
        var model = resolveModel(settings);
        var endpoint = normalizedEndpoint(baseUrl) + "/embeddings";
        try {
            var body = buildRequestBody(model, texts);
            var request = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Embedding API returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            return parseResponse(response.body(), texts.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Embedding request interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("Embedding request failed: " + e.getMessage(), e);
        } catch (JacksonException e) {
            throw new RuntimeException("Embedding response parse failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int priority() {
        // Below Spring AI (200) and LC4j (190) so the adapter wrappers win
        // when a framework-native EmbeddingModel is wired. Remains the
        // zero-dep fallback for programmatic callers.
        return 50;
    }

    private String resolveModel(AiConfig.LlmSettings settings) {
        var override = modelOverride;
        if (override != null && !override.isBlank()) {
            return override;
        }
        // Re-use the chat model surface only when it is recognizably an
        // embedding model; otherwise fall back to the safe default. Mixing a
        // chat model name into /v1/embeddings would 400 at the provider.
        var chatModel = settings.model();
        if (chatModel != null && chatModel.startsWith("text-embedding")) {
            return chatModel;
        }
        return DEFAULT_MODEL;
    }

    private static String normalizedEndpoint(String endpoint) {
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    private static String buildRequestBody(String model, List<String> texts) throws JacksonException {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("input", texts);
        return MAPPER.writeValueAsString(body);
    }

    private static List<float[]> parseResponse(String body, int expectedCount) throws JacksonException {
        var root = MAPPER.readTree(body);
        var data = root.get("data");
        if (data == null || !data.isArray()) {
            throw new IllegalStateException("Embedding response missing 'data' array: " + body);
        }
        var results = new ArrayList<float[]>(expectedCount);
        for (var entry : data) {
            var embeddingNode = entry.get("embedding");
            if (embeddingNode == null || !embeddingNode.isArray()) {
                throw new IllegalStateException("Embedding response missing 'embedding' vector: " + entry);
            }
            var vector = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vector[i] = (float) embeddingNode.get(i).doubleValue();
            }
            results.add(vector);
        }
        if (results.size() != expectedCount) {
            logger.warn("Embedding response returned {} vectors, expected {}",
                    results.size(), expectedCount);
        }
        return List.copyOf(results);
    }
}
