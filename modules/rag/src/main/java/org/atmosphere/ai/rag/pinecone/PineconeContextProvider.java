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
package org.atmosphere.ai.rag.pinecone;

import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.EmbeddingRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Direct REST {@link ContextProvider} for Pinecone, with no Spring AI or
 * LangChain4j dependency. Embeds the user query through an
 * {@link EmbeddingRuntime}, then issues a single HTTP {@code POST} to
 * {@code https://{indexHost}/query} and maps the response into
 * {@link Document} hits.
 *
 * <p>Construction (an index host comes from Pinecone's control plane —
 * either the dashboard or the {@code describe_index} API):</p>
 * <pre>{@code
 * var provider = PineconeContextProvider.builder(
 *         "atm-docs-abc1234.svc.us-east-1.pinecone.io",
 *         System.getenv("PINECONE_API_KEY"),
 *         embeddingRuntime)
 *     .namespace("docs")
 *     .contentField("content")
 *     .sourceField("source")
 *     .build();
 *
 * var hits = provider.retrieve("How do I reconnect?", 4);
 * }</pre>
 *
 * <p>Boundary safety: the host is treated as opaque and joined to a literal
 * {@code "https://"} prefix; no caller-controlled string is concatenated into
 * the URI path beyond what HTTP host validation already enforces. The
 * namespace is JSON-serialized through Jackson, never URL-spliced.</p>
 */
public final class PineconeContextProvider implements ContextProvider {

    private static final Logger logger = LoggerFactory.getLogger(PineconeContextProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String indexHost;
    private final String apiKey;
    private final String apiVersion;
    private final String namespace;
    private final EmbeddingRuntime embeddingRuntime;
    private final String contentField;
    private final String sourceField;
    private final Duration requestTimeout;

    private PineconeContextProvider(Builder b) {
        this.httpClient = b.httpClient != null ? b.httpClient : defaultHttpClient(b.connectTimeout);
        this.indexHost = requireHost(b.indexHost);
        this.apiKey = Objects.requireNonNull(b.apiKey, "apiKey");
        this.apiVersion = Objects.requireNonNull(b.apiVersion, "apiVersion");
        this.namespace = b.namespace;
        this.embeddingRuntime = Objects.requireNonNull(b.embeddingRuntime, "embeddingRuntime");
        this.contentField = Objects.requireNonNull(b.contentField, "contentField");
        this.sourceField = b.sourceField;
        this.requestTimeout = Objects.requireNonNull(b.requestTimeout, "requestTimeout");
    }

    public static Builder builder(String indexHost, String apiKey, EmbeddingRuntime embeddingRuntime) {
        return new Builder(indexHost, apiKey, embeddingRuntime);
    }

    @Override
    public List<Document> retrieve(String query, int maxResults) {
        if (query == null || query.isBlank() || maxResults <= 0) {
            return List.of();
        }
        float[] vector = embeddingRuntime.embed(query);
        if (vector == null || vector.length == 0) {
            return List.of();
        }

        try {
            var body = buildQueryRequest(vector, maxResults);
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://" + indexHost + "/query"))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Api-Key", apiKey)
                    .header("X-Pinecone-API-Version", apiVersion)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() / 100 != 2) {
                logger.warn("Pinecone query failed: status={} body={}",
                        response.statusCode(), abbreviate(response.body()));
                return List.of();
            }
            return parseMatches(response.body());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.debug("Pinecone query interrupted");
            return List.of();
        } catch (Exception e) {
            logger.warn("Pinecone query threw {}: {}", e.getClass().getSimpleName(), e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean isAvailable() {
        return embeddingRuntime.isAvailable();
    }

    String buildQueryRequest(float[] vector, int maxResults) {
        var payload = new LinkedHashMap<String, Object>();
        var vectorList = new ArrayList<Float>(vector.length);
        for (float v : vector) {
            vectorList.add(v);
        }
        payload.put("vector", vectorList);
        payload.put("topK", maxResults);
        payload.put("includeMetadata", true);
        if (namespace != null && !namespace.isBlank()) {
            payload.put("namespace", namespace);
        }
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Pinecone request serialization failed", e);
        }
    }

    List<Document> parseMatches(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode matches = root.get("matches");
            if (matches == null || !matches.isArray()) {
                return List.of();
            }
            var out = new ArrayList<Document>();
            for (JsonNode match : matches) {
                var metadata = match.get("metadata");
                if (metadata == null) {
                    continue;
                }
                JsonNode contentNode = metadata.get(contentField);
                if (contentNode == null) {
                    continue;
                }
                String content = contentNode.asString();
                String source = sourceField != null && metadata.get(sourceField) != null
                        ? metadata.get(sourceField).asString()
                        : null;
                double score = match.has("score") ? match.get("score").asDouble() : 0.0;
                out.add(new Document(content, source, score));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            logger.warn("Pinecone response parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static HttpClient defaultHttpClient(Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    private static String requireHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("indexHost must not be blank");
        }
        // Reject any caller attempt to splice scheme / path / query into the host.
        if (host.contains("/") || host.contains("?") || host.contains(":")
                || host.contains(" ") || host.contains("@")) {
            throw new IllegalArgumentException(
                    "indexHost must be a bare host name (no scheme, no path); was: " + host);
        }
        return host;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }

    /** Builder mirrors the immutability-by-construction pattern. */
    public static final class Builder {
        private final String indexHost;
        private final String apiKey;
        private final EmbeddingRuntime embeddingRuntime;
        private String apiVersion = "2024-10";
        private String namespace;
        private String contentField = "content";
        private String sourceField = "source";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(10);
        private HttpClient httpClient;

        private Builder(String indexHost, String apiKey, EmbeddingRuntime embeddingRuntime) {
            this.indexHost = indexHost;
            this.apiKey = apiKey;
            this.embeddingRuntime = embeddingRuntime;
        }

        /** Override the Pinecone API version pin (default {@code "2024-10"}). */
        public Builder apiVersion(String value) {
            this.apiVersion = value;
            return this;
        }

        /** Pinecone namespace to scope the query; {@code null} = the default namespace. */
        public Builder namespace(String value) {
            this.namespace = value;
            return this;
        }

        public Builder contentField(String value) {
            this.contentField = value;
            return this;
        }

        /** Pass {@code null} to suppress source-field reads. */
        public Builder sourceField(String value) {
            this.sourceField = value;
            return this;
        }

        public Builder connectTimeout(Duration value) {
            this.connectTimeout = value;
            return this;
        }

        public Builder requestTimeout(Duration value) {
            this.requestTimeout = value;
            return this;
        }

        /** Override the {@link HttpClient}; useful for proxies and tests. */
        public Builder httpClient(HttpClient value) {
            this.httpClient = value;
            return this;
        }

        public PineconeContextProvider build() {
            return new PineconeContextProvider(this);
        }
    }
}
