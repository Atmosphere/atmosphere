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

import java.util.List;

/**
 * The outcome of one {@link WebSearchEngine#search(WebSearchQuery) search}.
 * Mirrors the error-as-data shape of the sibling {@code eval} tool's
 * {@link org.atmosphere.ai.code.EvalResult}: a search never throws to the model,
 * so {@link #available()} distinguishes a real result set from a fail-closed
 * outcome (not configured, or an engine/network error), and {@link #message()}
 * carries the human-readable reason when {@code available} is {@code false}.
 *
 * <p>{@link #toModelText()} renders either the results or the fail-closed
 * message as the single string the model (or a sample agent) reads.</p>
 *
 * @param query     the query that produced these results
 * @param engine    the name of the engine that ran the search ({@code ""} when unavailable)
 * @param results   the ranked hits (empty when {@code available} is {@code false})
 * @param available whether a real search executed; {@code false} = fail-closed
 * @param message   the reason when {@code available} is {@code false}; {@code null} otherwise
 */
public record WebSearchResults(String query, String engine, List<WebSearchResult> results,
                               boolean available, String message) {

    public WebSearchResults {
        query = query == null ? "" : query;
        engine = engine == null ? "" : engine;
        results = results == null ? List.of() : List.copyOf(results);
    }

    /**
     * A successful search carrying its (possibly empty) ranked hits.
     *
     * @param query   the query that ran
     * @param engine  the engine name
     * @param results the ranked hits
     * @return an available result set
     */
    public static WebSearchResults of(String query, String engine, List<WebSearchResult> results) {
        return new WebSearchResults(query, engine, results, true, null);
    }

    /**
     * A fail-closed outcome — the search did not execute (not configured) or an
     * engine/network error occurred. Carries no hits, only the {@code message}.
     *
     * @param query   the query that was requested
     * @param message the boundary-safe reason
     * @return an unavailable result set
     */
    public static WebSearchResults unavailable(String query, String message) {
        return new WebSearchResults(query, "", List.of(), false, message);
    }

    /**
     * Render this outcome as the single text block a model or agent reads: the
     * fail-closed {@link #message()} when {@link #available()} is {@code false},
     * a "no results" line when the search ran but matched nothing, otherwise the
     * ranked hits as numbered {@code [n] title / URL / snippet} entries.
     *
     * @return the model-facing text, never {@code null} or blank
     */
    public String toModelText() {
        if (!available) {
            return message == null || message.isBlank()
                    ? "Web search is unavailable." : message;
        }
        if (results.isEmpty()) {
            return "No web results found for \"" + query + "\".";
        }
        var sb = new StringBuilder();
        sb.append("Web search results for: \"").append(query).append("\"\n\n");
        int i = 1;
        for (var r : results) {
            sb.append('[').append(i++).append("] ").append(r.title()).append('\n');
            if (!r.url().isBlank()) {
                sb.append("    URL: ").append(r.url()).append('\n');
            }
            if (!r.snippet().isBlank()) {
                sb.append("    ").append(r.snippet()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
