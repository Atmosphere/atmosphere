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

/**
 * A normalized web-search request handed to a {@link WebSearchEngine}: the free
 * text {@link #query()} and a bounded {@link #maxResults()} ceiling.
 *
 * <p>The canonical constructor trims the query and clamps {@code maxResults}
 * into {@code [1, }{@link #HARD_CAP}{@code ]} so a caller (or an LLM tool
 * argument) can never ask an engine for an unbounded result set — the ceiling
 * bounds both the outbound request and the memory a result list can occupy
 * (Correctness Invariant #3).</p>
 *
 * @param query      the search text (trimmed; may be empty, which callers reject)
 * @param maxResults the maximum number of results to return, clamped to a sane range
 */
public record WebSearchQuery(String query, int maxResults) {

    /** Default result ceiling when a caller does not specify one. */
    public static final int DEFAULT_MAX_RESULTS = 5;

    /** Absolute upper bound on results, independent of caller input. */
    public static final int HARD_CAP = 10;

    public WebSearchQuery {
        query = query == null ? "" : query.trim();
        if (maxResults <= 0) {
            maxResults = DEFAULT_MAX_RESULTS;
        }
        if (maxResults > HARD_CAP) {
            maxResults = HARD_CAP;
        }
    }

    /**
     * Build a query with an explicit result ceiling (clamped to the valid range).
     *
     * @param query      the search text
     * @param maxResults the requested ceiling; clamped to {@code [1, HARD_CAP]}
     * @return the normalized query
     */
    public static WebSearchQuery of(String query, int maxResults) {
        return new WebSearchQuery(query, maxResults);
    }

    /**
     * Build a query using the {@link #DEFAULT_MAX_RESULTS default} result ceiling.
     *
     * @param query the search text
     * @return the normalized query
     */
    public static WebSearchQuery of(String query) {
        return new WebSearchQuery(query, DEFAULT_MAX_RESULTS);
    }
}
