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
package org.atmosphere.ai.cache;

import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.EmbeddingRuntime;
import org.atmosphere.ai.EmbeddingRuntimeResolver;
import org.atmosphere.ai.llm.CacheHint;
import org.atmosphere.cpr.AtmosphereConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * Config seam that installs a {@link ResponseCache} on an {@link AiPipeline},
 * mirroring {@link org.atmosphere.ai.RagRetrieval}'s resolve-from-init-params
 * shape:
 *
 * <pre>
 * org.atmosphere.ai.cache.semantic             = false (default) | true
 * org.atmosphere.ai.cache.semantic.threshold   = &lt;double&gt;  # cosine bar, default 0.92
 * org.atmosphere.ai.cache.semantic.max-entries = &lt;int&gt;     # bounded store, default 1000
 * org.atmosphere.ai.cache.ttl-minutes          = &lt;int&gt;     # cached-entry lifetime, default 5
 * </pre>
 *
 * <h2>Which dispatch paths this reaches</h2>
 *
 * <p>The response-cache gate lives in {@link AiPipeline#execute}, so this seam
 * reaches exactly the pipeline dispatch paths: the {@code @Coordinator} A2A /
 * AG-UI / channel-bridge surface, and any application that builds its own
 * {@code AiPipeline}. It does <strong>not</strong> reach the
 * {@code @AiEndpoint} websocket path — {@code AiStreamingSession} has no
 * response-cache gate, and the Tier-1 dispatch unification deliberately left
 * the gate in {@code AiPipeline} rather than moving it into the shared
 * {@code DispatchDecorators} composer. An {@code @AiEndpoint} that wants
 * response caching routes its {@code @Prompt} method through an
 * {@code AiPipeline} (as {@code PromptCacheDemoChat} does). Advertising
 * anything wider would be a runtime-truth violation (Correctness Invariant #5).</p>
 *
 * <h2>Why enabling also seeds a cache policy</h2>
 *
 * <p>The gate only opens when the request carries a {@link CacheHint}. On the
 * coordinator/channel paths nothing sets one, so installing a cache alone would
 * be inert. Turning the key on therefore also seeds
 * {@link CacheHint.CachePolicy#CONSERVATIVE} as the pipeline default — an
 * explicit opt-in producing an actually-live cache, not a dormant SPI.</p>
 *
 * <p>Fail-closed on runtime truth: when the semantic cache is requested but no
 * {@link EmbeddingRuntime} is available, nothing is installed and a WARN fires.
 * A cache that can never hit is worse than no cache — it hides the
 * misconfiguration behind a "caching is on" claim.</p>
 */
public final class ResponseCacheConfig {

    private static final Logger logger = LoggerFactory.getLogger(ResponseCacheConfig.class);

    /** Init-param: enable the embedding-similarity response cache. Default {@code false}. */
    public static final String SEMANTIC_KEY = "org.atmosphere.ai.cache.semantic";

    /** Init-param: cosine-similarity bar above which a stored response is served. */
    public static final String SEMANTIC_THRESHOLD_KEY = "org.atmosphere.ai.cache.semantic.threshold";

    /** Init-param: bounded entry count for the semantic cache (Invariant #3). */
    public static final String SEMANTIC_MAX_ENTRIES_KEY =
            "org.atmosphere.ai.cache.semantic.max-entries";

    /** Init-param: cached-entry lifetime in minutes. */
    public static final String TTL_MINUTES_KEY = "org.atmosphere.ai.cache.ttl-minutes";

    /** Default cached-entry lifetime, matching {@code AiPipeline}'s own default. */
    public static final int DEFAULT_TTL_MINUTES = 5;

    private ResponseCacheConfig() {
    }

    /**
     * A resolved cache plus the TTL to install it with.
     *
     * @param cache the cache to install
     * @param ttl   how long a cached response stays servable
     */
    public record Resolved(ResponseCache cache, Duration ttl) { }

    /**
     * Resolve the configured cache, using the classpath-resolved embedding
     * runtime.
     *
     * @param cfg the framework config (may be {@code null} in tests)
     * @return the cache to install, or empty when disabled or unusable
     */
    public static Optional<Resolved> resolve(AtmosphereConfig cfg) {
        return resolve(cfg, EmbeddingRuntimeResolver.resolve().orElse(null));
    }

    /**
     * Resolve the configured cache against an explicit embedding runtime.
     *
     * @param cfg        the framework config (may be {@code null} in tests)
     * @param embeddings the embedding runtime to score similarity with;
     *                   {@code null} means none is available
     * @return the cache to install, or empty when disabled or unusable
     */
    public static Optional<Resolved> resolve(AtmosphereConfig cfg, EmbeddingRuntime embeddings) {
        if (cfg == null || !cfg.getInitParameter(SEMANTIC_KEY, false)) {
            return Optional.empty();
        }
        if (embeddings == null) {
            logger.warn("{}=true but no EmbeddingRuntime is available — the semantic response "
                    + "cache is NOT installed (it could never hit). Add an embedding backend "
                    + "or use an exact ResponseCache.", SEMANTIC_KEY);
            return Optional.empty();
        }
        var threshold = threshold(cfg);
        var maxEntries = cfg.getInitParameter(SEMANTIC_MAX_ENTRIES_KEY, 1_000);
        var ttlMinutes = cfg.getInitParameter(TTL_MINUTES_KEY, DEFAULT_TTL_MINUTES);
        if (ttlMinutes <= 0) {
            logger.warn("{}={} is not positive — using {} minutes",
                    TTL_MINUTES_KEY, ttlMinutes, DEFAULT_TTL_MINUTES);
            ttlMinutes = DEFAULT_TTL_MINUTES;
        }
        logger.info("Semantic response cache enabled (threshold={}, maxEntries={}, ttl={}m, "
                + "embeddings={})", threshold, maxEntries, ttlMinutes, embeddings.name());
        return Optional.of(new Resolved(
                new SemanticResponseCache(embeddings, threshold, maxEntries),
                Duration.ofMinutes(ttlMinutes)));
    }

    /**
     * Install the configured cache on {@code pipeline}, seeding the default
     * cache policy so the gate actually opens. A no-op when disabled or when
     * no embedding runtime backs the request.
     *
     * @param pipeline the pipeline to install onto
     * @param cfg      the framework config (may be {@code null})
     * @return {@code true} when a cache was installed
     */
    public static boolean install(AiPipeline pipeline, AtmosphereConfig cfg) {
        return install(pipeline, resolve(cfg));
    }

    /**
     * Install an already-resolved cache. Split out so callers that resolve once
     * (per-application) can install onto many pipelines, and so tests can drive
     * the install with an injected cache.
     *
     * @param pipeline the pipeline to install onto
     * @param resolved the resolution result
     * @return {@code true} when a cache was installed
     */
    public static boolean install(AiPipeline pipeline, Optional<Resolved> resolved) {
        if (pipeline == null || resolved.isEmpty()) {
            return false;
        }
        var installed = resolved.get();
        pipeline.setResponseCache(installed.cache(), installed.ttl());
        // Without a CacheHint the gate in AiPipeline.execute never consults the
        // cache. Nothing on the coordinator / channel paths sets one, so the
        // explicit opt-in seeds the policy too — otherwise "semantic cache
        // enabled" would be a claim with no runtime effect.
        pipeline.setDefaultCachePolicy(CacheHint.CachePolicy.CONSERVATIVE);
        return true;
    }

    private static double threshold(AtmosphereConfig cfg) {
        var raw = cfg.getInitParameter(SEMANTIC_THRESHOLD_KEY);
        if (raw == null || raw.isBlank()) {
            return SemanticResponseCache.DEFAULT_THRESHOLD;
        }
        try {
            var parsed = Double.parseDouble(raw.trim());
            if (parsed <= 0.0 || parsed > 1.0) {
                logger.warn("{}={} is outside (0,1] — using the default {}",
                        SEMANTIC_THRESHOLD_KEY, raw, SemanticResponseCache.DEFAULT_THRESHOLD);
                return SemanticResponseCache.DEFAULT_THRESHOLD;
            }
            return parsed;
        } catch (NumberFormatException e) {
            logger.warn("{}={} is not a number — using the default {}",
                    SEMANTIC_THRESHOLD_KEY, raw, SemanticResponseCache.DEFAULT_THRESHOLD);
            return SemanticResponseCache.DEFAULT_THRESHOLD;
        }
    }
}
