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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Single-entry TTL cache for live provider model enumeration
 * ({@link org.atmosphere.ai.AgentRuntime#models()} backed by a provider
 * {@code GET .../models} call). Shared by the runtimes that declare
 * {@link org.atmosphere.ai.AiCapability#MODEL_ENUMERATION} over a hand-rolled
 * HTTP client so the enumeration seam behaves identically across them
 * (Correctness Invariant #7 — Mode Parity).
 *
 * <p>Semantics:</p>
 * <ul>
 *   <li><b>Best-effort.</b> Any fetcher exception (or an empty fetch result)
 *       falls back to the caller-supplied fallback — the configured model —
 *       so enumeration failure can never break discovery, let alone dispatch.
 *       Failures are logged at DEBUG, never swallowed silently.</li>
 *   <li><b>Short TTL.</b> A successful fetch is served from memory for
 *       {@code ttl} before the provider is asked again. Failed fetches are
 *       not negatively cached — the next call retries, and the fallback
 *       answers in the meantime.</li>
 *   <li><b>Bounded.</b> Exactly one immutable list is retained (the fetcher
 *       output is itself bounded by {@link ModelListJson#MAX_MODELS}).</li>
 *   <li><b>Racy by design.</b> Concurrent expiry may trigger a duplicate
 *       fetch; both writers store equivalent fresh data. No lock is held
 *       around the network call.</li>
 * </ul>
 */
public final class CachedModelList {

    private static final Logger logger = LoggerFactory.getLogger(CachedModelList.class);

    /** Default TTL for a successful enumeration — short enough to track provider churn. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final long ttlNanos;
    private volatile List<String> cached;
    private volatile long cachedAtNanos;

    public CachedModelList() {
        this(DEFAULT_TTL);
    }

    public CachedModelList(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        this.ttlNanos = ttl.toNanos();
    }

    /**
     * Return the cached list when fresh; otherwise run {@code fetcher} and
     * cache a non-empty result. On any fetcher failure or an empty result,
     * return {@code fallback.get()} (never cached — it reflects configuration,
     * not discovery).
     *
     * @param provider display name for the DEBUG failure log
     * @param fetcher  live enumeration call; may throw or return empty
     * @param fallback configured-model fallback; never {@code null} result
     * @return immutable model list; never {@code null}
     */
    public List<String> get(String provider, Supplier<List<String>> fetcher,
                            Supplier<List<String>> fallback) {
        var snapshot = cached;
        if (snapshot != null && System.nanoTime() - cachedAtNanos < ttlNanos) {
            return snapshot;
        }
        try {
            var live = fetcher.get();
            if (live != null && !live.isEmpty()) {
                var fresh = List.copyOf(live);
                // Publish the list BEFORE the timestamp: a reader that sees the
                // new list with the old timestamp merely re-fetches (harmless),
                // whereas the reverse order could serve a stale list as fresh.
                cached = fresh;
                cachedAtNanos = System.nanoTime();
                // Return the local value, not the volatile — a concurrent
                // writer may already have replaced the field.
                return fresh;
            }
        } catch (RuntimeException e) {
            logger.debug("{} live model enumeration failed — using configured fallback",
                    provider, e);
        }
        return fallback.get();
    }
}
