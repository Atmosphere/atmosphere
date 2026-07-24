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

import java.util.Locale;

/**
 * Configuration for the built-in {@link HttpWebSearchEngine}. Resolved from
 * {@code org.atmosphere.ai.websearch.*} system properties (each overridable by
 * the equivalent {@code ORG_ATMOSPHERE_AI_WEBSEARCH_*} environment variable) —
 * the same naming scheme the {@code code_exec} and {@code eval} tools use.
 *
 * <p><strong>Fail closed.</strong> {@link #endpoint()} is empty by default, so
 * {@link #isConfigured()} is {@code false} and no search is ever attempted until
 * an operator supplies a JSON search endpoint (Correctness Invariants #5, #6).
 * The endpoint targets a JSON-over-HTTP search service — a self-hosted
 * metasearch instance or a hosted JSON search API — whose response is a results
 * array of objects with title / URL / snippet fields.</p>
 *
 * @param endpoint      the JSON search endpoint base URL; blank = disabled (fail closed)
 * @param apiKey        optional credential sent in {@link #apiKeyHeader()}; blank = none
 * @param apiKeyHeader  the request header the credential is sent in
 * @param queryParam    the query-string parameter the search text is passed as
 * @param maxResults    default ceiling on the number of results returned
 * @param timeoutMillis per-request wall-clock timeout, in milliseconds
 */
public record WebSearchConfig(
        String endpoint,
        String apiKey,
        String apiKeyHeader,
        String queryParam,
        int maxResults,
        long timeoutMillis) {

    /** System property (env {@code ORG_ATMOSPHERE_AI_WEBSEARCH_ENDPOINT}) — JSON search endpoint. */
    public static final String ENDPOINT = "org.atmosphere.ai.websearch.endpoint";
    /** System property — optional API credential sent in {@link #API_KEY_HEADER}. */
    public static final String API_KEY = "org.atmosphere.ai.websearch.apiKey";
    /** System property — request header the credential is sent in. */
    public static final String API_KEY_HEADER = "org.atmosphere.ai.websearch.apiKeyHeader";
    /** System property — query-string parameter the search text is passed as. */
    public static final String QUERY_PARAM = "org.atmosphere.ai.websearch.queryParam";
    /** System property — default ceiling on the number of results returned. */
    public static final String MAX_RESULTS = "org.atmosphere.ai.websearch.maxResults";
    /** System property — per-request timeout in milliseconds. */
    public static final String TIMEOUT_MILLIS = "org.atmosphere.ai.websearch.timeoutMillis";

    /** Default credential header — the subscription-token convention hosted JSON search APIs use. */
    public static final String DEFAULT_API_KEY_HEADER = "X-Subscription-Token";
    /** Default query-string parameter name. */
    public static final String DEFAULT_QUERY_PARAM = "q";
    /** Default result ceiling. */
    public static final int DEFAULT_MAX_RESULTS = 5;
    /** Default per-request timeout. */
    public static final long DEFAULT_TIMEOUT_MILLIS = 8_000L;

    /** Canonicalize: normalize blanks to defaults and floor the bounds. */
    public WebSearchConfig {
        endpoint = endpoint == null ? "" : endpoint.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        apiKeyHeader = blankToDefault(apiKeyHeader, DEFAULT_API_KEY_HEADER);
        queryParam = blankToDefault(queryParam, DEFAULT_QUERY_PARAM);
        if (maxResults <= 0) {
            maxResults = DEFAULT_MAX_RESULTS;
        }
        if (maxResults > WebSearchQuery.HARD_CAP) {
            maxResults = WebSearchQuery.HARD_CAP;
        }
        if (timeoutMillis < 500L) {
            timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
        }
    }

    /** Whether a search endpoint is configured; {@code false} = fail closed (default). */
    public boolean isConfigured() {
        return !endpoint.isBlank();
    }

    /** A disabled configuration — the fail-closed baseline used when nothing is configured. */
    public static WebSearchConfig disabled() {
        return new WebSearchConfig("", "", DEFAULT_API_KEY_HEADER, DEFAULT_QUERY_PARAM,
                DEFAULT_MAX_RESULTS, DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * Resolve from {@code org.atmosphere.ai.websearch.*} configuration. Missing
     * keys fall back to the hardened defaults; a blank {@link #endpoint()} leaves
     * the engine fail-closed.
     *
     * @return the resolved configuration
     */
    public static WebSearchConfig fromSystemProperties() {
        return new WebSearchConfig(
                resolve(ENDPOINT, ""),
                resolve(API_KEY, ""),
                resolve(API_KEY_HEADER, DEFAULT_API_KEY_HEADER),
                resolve(QUERY_PARAM, DEFAULT_QUERY_PARAM),
                parseInt(MAX_RESULTS, DEFAULT_MAX_RESULTS),
                parseLong(TIMEOUT_MILLIS, DEFAULT_TIMEOUT_MILLIS));
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String resolve(String key, String fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key.replace('.', '_').toUpperCase(Locale.ROOT));
        }
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int parseInt(String key, int fallback) {
        String value = resolve(key, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String key, long fallback) {
        String value = resolve(key, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
