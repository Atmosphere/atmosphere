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
import java.util.Optional;

/**
 * {@link EmbeddingRuntime} that posts to an OpenAI-compatible {@code /v1/embeddings}
 * endpoint. Works with OpenAI, Azure OpenAI, Gemini's OpenAI gateway, Ollama,
 * and any compatible proxy — same configuration surface as
 * {@link OpenAiCompatibleClient} for the chat path.
 *
 * <p>Configuration comes from {@link AiConfig}: base URL, API key, and the
 * embedding-model name. Against a remote provider the model defaults to
 * {@code text-embedding-3-small}. In {@code llm.mode=local} there is no
 * default: an OpenAI model name is not something Ollama, vLLM or LM Studio
 * serves, so posting it only produced an HTTP 404 on every call while the
 * runtime advertised itself as available. A local backend is therefore used
 * only when the configured {@code LLM_MODEL} is itself an embedding model
 * ({@code text-embedding*}) or the caller set one via
 * {@link #setEmbeddingModel(String)}; otherwise {@link #isAvailable()} reports
 * {@code false} and consumers take their documented no-runtime path.</p>
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
        return isAvailable(AiConfig.get());
    }

    /**
     * Availability against explicit settings: a reachable backend (a
     * credentialed remote, or a local server) plus an embedding model this
     * runtime can actually name to it. Package-private so tests can pin the
     * answer without touching the {@link AiConfig} singleton.
     */
    boolean isAvailable(AiConfig.LlmSettings settings) {
        if (settings == null || settings.baseUrl() == null || settings.baseUrl().isBlank()
                || !settings.hasReachableModel()) {
            return false;
        }
        return resolveModel(settings).isPresent();
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
        // A local embedding server needs no credential, so requiring a key here
        // made every keyless-local deployment throw before it ever issued a
        // request — the RAG path in particular, where the ingest is the first
        // thing that runs. Ask whether a model is reachable, then supply a
        // placeholder for the Authorization header a local server ignores.
        if (settings == null || settings.baseUrl() == null || !settings.hasReachableModel()) {
            throw new IllegalStateException(
                    "Built-in embedding runtime requires AiConfig.baseUrl plus either an "
                            + "apiKey or llm.mode=local");
        }
        var configured = settings.apiKey();
        var apiKey = configured == null || configured.isBlank()
                ? "not-needed-for-local"
                : configured;
        var baseUrl = settings.baseUrl();
        var model = resolveModel(settings).orElseThrow(() -> new IllegalStateException(
                "Built-in embedding runtime has no embedding model for llm.mode=local: "
                        + "LLM_MODEL '" + settings.model() + "' is not an embedding model "
                        + "and no model was set via setEmbeddingModel(String)"));
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

    private Optional<String> resolveModel(AiConfig.LlmSettings settings) {
        var override = modelOverride;
        if (override != null && !override.isBlank()) {
            return Optional.of(override);
        }
        // Re-use the chat model surface only when it is recognizably an
        // embedding model; otherwise fall back to the safe default. Mixing a
        // chat model name into /v1/embeddings would 400 at the provider.
        var chatModel = settings.model();
        if (chatModel != null && chatModel.startsWith("text-embedding")) {
            return Optional.of(chatModel);
        }
        // The default is an OpenAI model name; a local server does not serve
        // it, so a local backend has no embedding model unless one was named.
        return settings.isLocal() ? Optional.empty() : Optional.of(DEFAULT_MODEL);
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
