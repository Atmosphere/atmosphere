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
 * SPI for the engine behind the {@code web_search} tool — a pluggable search
 * backend. Discovered via {@link java.util.ServiceLoader}; the
 * highest-{@link #priority()} engine whose {@link #isConfigured()} returns
 * {@code true} wins, exactly like {@code AgentRuntime} and {@link
 * org.atmosphere.ai.code.EvalEngine} resolution.
 *
 * <p>Atmosphere ships {@link HttpWebSearchEngine} — a JSON-over-HTTP engine that
 * targets an operator-configured search endpoint (a self-hosted metasearch
 * instance, or a hosted JSON search API) — as the default. Alternatives plug in
 * by adding a jar with a {@code META-INF/services/}
 * {@code org.atmosphere.ai.websearch.WebSearchEngine} entry; no Atmosphere
 * change is needed.</p>
 *
 * <p><strong>Contract (Correctness Invariants #5, #6).</strong> An engine MUST
 * report its <em>confirmed</em> configuration through {@link #isConfigured()}
 * (never mere classpath presence), MUST NOT perform any network I/O when it is
 * not configured (fail closed / offline), and MUST return operational failures
 * as {@link WebSearchResults#unavailable} data rather than by throwing, so the
 * caller reads the failure as a tool result and can correct course.</p>
 */
public interface WebSearchEngine {

    /**
     * A short, stable engine name surfaced in the tool registration log and in
     * {@link WebSearchResults#engine()} (e.g. {@code "http-json"}).
     *
     * @return the engine name, never {@code null} or blank
     */
    String name();

    /**
     * Whether this engine is configured and can actually issue searches — its
     * endpoint (and any required credential) is present. Reports <em>confirmed
     * runtime state</em> (Correctness Invariant #5), never mere configuration
     * intent or classpath presence. Called during {@link java.util.ServiceLoader}
     * resolution; must not throw and must not touch the network.
     *
     * @return {@code true} when a search would actually be attempted
     */
    boolean isConfigured();

    /**
     * Selection weight when several engines are configured. Higher wins; the
     * built-in {@link HttpWebSearchEngine} uses {@code 0}, so any explicitly
     * added engine can take precedence with a positive value.
     *
     * @return the priority (default {@code 0})
     */
    default int priority() {
        return 0;
    }

    /**
     * Run one search and return its ranked hits — or a
     * {@link WebSearchResults#unavailable} outcome when the engine is not
     * configured or a network/parse failure occurs. Must not throw for
     * operational failures, and must not touch the network when
     * {@link #isConfigured()} is {@code false}.
     *
     * @param query the normalized, bounded query
     * @return the outcome, never {@code null}
     */
    WebSearchResults search(WebSearchQuery query);
}
