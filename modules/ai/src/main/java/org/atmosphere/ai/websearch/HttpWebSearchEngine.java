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
package org.atmosphere.ai.websearch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The default {@link WebSearchEngine}: a JSON-over-HTTP search backend that
 * targets an operator-configured endpoint (a self-hosted metasearch instance, or
 * a hosted JSON search API). Registered via {@code META-INF/services/}
 * {@code org.atmosphere.ai.websearch.WebSearchEngine} at priority {@code 0}, so
 * any explicitly added engine can take precedence.
 *
 * <p><strong>Fail closed / offline.</strong> When no endpoint is configured
 * ({@link WebSearchConfig#isConfigured()} is {@code false}) the engine performs
 * <em>no</em> network I/O and returns a clear
 * {@link WebSearchResults#unavailable} notice (Correctness Invariants #5, #6).
 * Only the model-controlled query is dynamic — it is URL-encoded onto an
 * operator-trusted endpoint (Invariant #4) — and the HTTP response body is read
 * under a fixed byte cap so a hostile or runaway endpoint cannot exhaust memory
 * (Invariant #3).</p>
 *
 * <p>The response is parsed defensively: the results array is taken from a
 * top-level {@code results} field or a nested {@code web.results} field, and
 * each entry's title / URL / snippet are read from the common field-name
 * variants, so the same engine handles the JSON shapes of typical metasearch and
 * hosted search services without shape-specific configuration.</p>
 */
public final class HttpWebSearchEngine implements WebSearchEngine {

    private static final Logger logger = LoggerFactory.getLogger(HttpWebSearchEngine.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** Hard cap on the response body we will read into memory (Invariant #3). */
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    private final WebSearchConfig config;
    private volatile HttpClient httpClient;

    /** No-arg constructor for {@link java.util.ServiceLoader}; reads system-property config. */
    public HttpWebSearchEngine() {
        this(WebSearchConfig.fromSystemProperties());
    }

    /**
     * Construct against an explicit configuration. Package-visible so tests can
     * point the engine at a local endpoint without touching system properties.
     *
     * @param config the resolved configuration
     */
    HttpWebSearchEngine(WebSearchConfig config) {
        this.config = config == null ? WebSearchConfig.disabled() : config;
    }

    @Override
    public String name() {
        return "http-json";
    }

    @Override
    public boolean isConfigured() {
        return config.isConfigured();
    }

    @Override
    public WebSearchResults search(WebSearchQuery query) {
        // Fail closed: never touch the network when unconfigured.
        if (!isConfigured()) {
            return WebSearchResults.unavailable(query.query(), notConfiguredNotice());
        }
        if (query.query().isBlank()) {
            return WebSearchResults.unavailable(query.query(), "Error: search query is empty.");
        }

        URI uri;
        try {
            uri = buildUri(query.query());
        } catch (IllegalArgumentException e) {
            logger.warn("web_search endpoint is not a valid URL: {}", e.getMessage());
            return WebSearchResults.unavailable(query.query(),
                    "Web search endpoint is misconfigured: " + e.getMessage());
        }

        var requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(config.timeoutMillis()))
                .header("Accept", "application/json")
                .GET();
        if (!config.apiKey().isBlank()) {
            requestBuilder.header(config.apiKeyHeader(), config.apiKey());
        }

        try {
            var response = client().send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] body = readBounded(response.body());
            if (response.statusCode() / 100 != 2) {
                return WebSearchResults.unavailable(query.query(),
                        "Web search endpoint returned HTTP " + response.statusCode() + ".");
            }
            return parse(query, new String(body, StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warn("web_search request failed: {}", e.toString());
            return WebSearchResults.unavailable(query.query(),
                    "Web search request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return WebSearchResults.unavailable(query.query(), "Web search was interrupted.");
        } catch (RuntimeException e) {
            logger.warn("web_search failed unexpectedly: {}", e.toString());
            return WebSearchResults.unavailable(query.query(),
                    "Web search failed: " + e.getMessage());
        }
    }

    private URI buildUri(String query) {
        var endpoint = config.endpoint();
        var separator = endpoint.indexOf('?') >= 0 ? '&' : '?';
        var url = endpoint + separator + config.queryParam() + '='
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        var uri = URI.create(url);
        var scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("endpoint must be an http(s) URL");
        }
        return uri;
    }

    /** Read at most {@link #MAX_RESPONSE_BYTES} from the response stream. */
    private static byte[] readBounded(InputStream in) throws IOException {
        try (in) {
            var buffer = new ByteArrayOutputStream();
            var chunk = new byte[8_192];
            int read;
            while ((read = in.read(chunk)) != -1
                    && buffer.size() < MAX_RESPONSE_BYTES) {
                buffer.write(chunk, 0, Math.min(read, MAX_RESPONSE_BYTES - buffer.size()));
            }
            return buffer.toByteArray();
        }
    }

    /** Defensively map the endpoint's JSON body onto {@link WebSearchResults}. */
    private WebSearchResults parse(WebSearchQuery query, String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (RuntimeException e) {
            return WebSearchResults.unavailable(query.query(),
                    "Web search endpoint returned a non-JSON response.");
        }
        if (root == null || !root.isObject()) {
            return WebSearchResults.of(query.query(), name(), List.of());
        }
        JsonNode array = arrayField(root, "results");
        if (array == null) {
            var web = root.get("web");
            if (web != null && web.isObject()) {
                array = arrayField(web, "results");
            }
        }
        if (array == null) {
            return WebSearchResults.of(query.query(), name(), List.of());
        }
        var hits = new ArrayList<WebSearchResult>();
        for (int i = 0; i < array.size() && hits.size() < query.maxResults(); i++) {
            var node = array.get(i);
            if (node == null || !node.isObject()) {
                continue;
            }
            var title = firstString(node, "title", "name");
            var url = firstString(node, "url", "href", "link");
            var snippet = firstString(node, "content", "snippet", "description", "body");
            if (!title.isBlank() || !url.isBlank()) {
                hits.add(new WebSearchResult(title, url, snippet));
            }
        }
        return WebSearchResults.of(query.query(), name(), hits);
    }

    private static JsonNode arrayField(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && value.isArray() ? value : null;
    }

    private static String firstString(JsonNode node, String... fields) {
        for (var field : fields) {
            var value = node.get(field);
            if (value != null && !value.isNull()) {
                var text = value.isString() ? value.stringValue() : value.asString();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private String notConfiguredNotice() {
        return "Web search is not configured on this server. Set the '"
                + WebSearchConfig.ENDPOINT + "' property (and '" + WebSearchConfig.API_KEY
                + "' if the endpoint requires a credential) to enable it. "
                + "No web request was made.";
    }

    /**
     * The lazily-built, process-lifetime {@link HttpClient}. Created and owned by
     * this engine (Invariant #1); it is a long-lived shared resource reclaimed
     * with the JVM, so it is not closed per call.
     */
    private HttpClient client() {
        var current = httpClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (httpClient == null) {
                httpClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(config.timeoutMillis()))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
            }
            return httpClient;
        }
    }
}
