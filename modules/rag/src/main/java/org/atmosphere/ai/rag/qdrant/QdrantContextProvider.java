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
package org.atmosphere.ai.rag.qdrant;

import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.EmbeddingRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Direct REST {@link ContextProvider} for the Qdrant vector store, with no
 * Spring AI or LangChain4j dependency. Embeds the user query through an
 * {@link EmbeddingRuntime}, then issues a single HTTP {@code POST} to
 * {@code /collections/{collection}/points/search} and maps the response into
 * {@link Document} hits.
 *
 * <p>Construction:</p>
 * <pre>{@code
 * var provider = QdrantContextProvider.builder("https://qdrant.example.com:6333",
 *                                              "atmosphere-docs",
 *                                              embeddingRuntime)
 *     .apiKey(System.getenv("QDRANT_API_KEY"))
 *     .contentField("content")
 *     .sourceField("source")
 *     .build();
 *
 * var hits = provider.retrieve("How do I reconnect?", 4);
 * }</pre>
 *
 * <p>Boundary safety: the Qdrant collection name is the only caller-controlled
 * value that appears in the request path. It is validated against
 * {@code [A-Za-z0-9_-]+} and URL-encoded before splicing into the URI to
 * eliminate path-injection / smuggling risk (Correctness Invariant #4).</p>
 */
public final class QdrantContextProvider implements ContextProvider {

    private static final Logger logger = LoggerFactory.getLogger(QdrantContextProvider.class);
    private static final Pattern COLLECTION_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String endpoint;
    private final String collection;
    private final EmbeddingRuntime embeddingRuntime;
    private final String apiKey;
    private final String contentField;
    private final String sourceField;
    private final Duration requestTimeout;

    private QdrantContextProvider(Builder b) {
        this.httpClient = b.httpClient != null ? b.httpClient : defaultHttpClient(b.connectTimeout);
        this.endpoint = stripTrailingSlash(Objects.requireNonNull(b.endpoint, "endpoint"));
        this.collection = requireCollection(b.collection);
        this.embeddingRuntime = Objects.requireNonNull(b.embeddingRuntime, "embeddingRuntime");
        this.apiKey = b.apiKey;
        this.contentField = Objects.requireNonNull(b.contentField, "contentField");
        this.sourceField = b.sourceField;
        this.requestTimeout = Objects.requireNonNull(b.requestTimeout, "requestTimeout");
    }

    public static Builder builder(String endpoint, String collection, EmbeddingRuntime embeddingRuntime) {
        return new Builder(endpoint, collection, embeddingRuntime);
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
            var body = buildSearchRequest(vector, maxResults);
            var safeCollection = URLEncoder.encode(collection, StandardCharsets.UTF_8);
            var requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/collections/" + safeCollection + "/points/search"))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (apiKey != null && !apiKey.isBlank()) {
                requestBuilder.header("api-key", apiKey);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() / 100 != 2) {
                logger.warn("Qdrant search failed: status={} body={}",
                        response.statusCode(), abbreviate(response.body()));
                return List.of();
            }
            return parseHits(response.body());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.debug("Qdrant search interrupted");
            return List.of();
        } catch (Exception e) {
            logger.warn("Qdrant search threw {}: {}", e.getClass().getSimpleName(), e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean isAvailable() {
        if (!embeddingRuntime.isAvailable()) {
            return false;
        }
        try {
            var req = HttpRequest.newBuilder(URI.create(endpoint + "/readyz"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            var response = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() / 100 == 2;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    String buildSearchRequest(float[] vector, int maxResults) {
        var payload = new LinkedHashMap<String, Object>();
        var vectorList = new ArrayList<Float>(vector.length);
        for (float v : vector) {
            vectorList.add(v);
        }
        payload.put("vector", vectorList);
        payload.put("limit", maxResults);
        payload.put("with_payload", true);
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant request serialization failed", e);
        }
    }

    List<Document> parseHits(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode result = root.get("result");
            if (result == null || !result.isArray()) {
                return List.of();
            }
            var out = new ArrayList<Document>();
            for (JsonNode hit : result) {
                var payload = hit.get("payload");
                if (payload == null) {
                    continue;
                }
                JsonNode contentNode = payload.get(contentField);
                if (contentNode == null) {
                    continue;
                }
                String content = contentNode.asString();
                String source = sourceField != null && payload.get(sourceField) != null
                        ? payload.get(sourceField).asString()
                        : null;
                double score = hit.has("score") ? hit.get("score").asDouble() : 0.0;
                out.add(new Document(content, source, score));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            logger.warn("Qdrant response parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static HttpClient defaultHttpClient(Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    private static String stripTrailingSlash(String endpoint) {
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    private static String requireCollection(String value) {
        if (value == null || !COLLECTION_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "collection must match [A-Za-z0-9._-]+ (was: " + value + ")");
        }
        return value;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }

    /** Builder mirrors the immutability-by-construction pattern. */
    public static final class Builder {
        private final String endpoint;
        private final String collection;
        private final EmbeddingRuntime embeddingRuntime;
        private String apiKey;
        private String contentField = "content";
        private String sourceField = "source";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(10);
        private HttpClient httpClient;

        private Builder(String endpoint, String collection, EmbeddingRuntime embeddingRuntime) {
            this.endpoint = endpoint;
            this.collection = collection;
            this.embeddingRuntime = embeddingRuntime;
        }

        public Builder apiKey(String value) {
            this.apiKey = value;
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

        public QdrantContextProvider build() {
            return new QdrantContextProvider(this);
        }
    }
}
