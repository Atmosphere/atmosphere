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

import org.atmosphere.ai.AgentExecutionContext;

import java.time.Duration;
import java.util.Optional;

/**
 * Portable hint that asks a runtime bridge to enable prompt caching for the
 * current request. Travels on {@link AgentExecutionContext#metadata()} under
 * the canonical key {@link #METADATA_KEY} so the canonical context record
 * does not need to grow a dedicated caching field.
 *
 * <p>The hint is advisory: each adapter translates it to whatever cache
 * mechanism the underlying framework exposes. Spring AI (OpenAI path),
 * LangChain4j (OpenAI path), and the Built-in runtime all emit
 * {@code prompt_cache_key} on the OpenAI chat-completions wire — the one
 * prompt-cache primitive that is actually portable across providers today.
 * ADK's {@code ContextCacheConfig} is app-scoped and therefore configured at
 * runtime-bootstrap time, not per-request; the per-request hint is logged and
 * ignored on ADK. Koog maps the hint to Koog 1.0's Bedrock cache-control
 * buckets when the selected Koog provider honors them; other Koog providers
 * ignore the provider-specific marker upstream.
 *
 * @param policy    caller's intent: opt-in or not
 * @param cacheKey  provider-specific cache key (e.g. OpenAI
 *                  {@code prompt_cache_key}); pass a stable value per session
 *                  or per system-prompt to maximize reuse. When empty, the
 *                  adapter falls back to the context's {@code sessionId}
 * @param ttl       optional TTL request — honored by providers with explicit
 *                  TTL control (Anthropic ephemeral, Gemini {@code CachedContent}),
 *                  ignored elsewhere
 */
public record CacheHint(CachePolicy policy, Optional<String> cacheKey, Optional<Duration> ttl) {

    /** Canonical metadata key. Adapters read the hint from this slot only. */
    public static final String METADATA_KEY = "ai.cache.hint";

    /** Caller intent. {@link #NONE} suppresses wiring even when a key is present. */
    public enum CachePolicy {
        /** Explicit opt-out. Runtimes skip cache wiring. */
        NONE,
        /** Opt-in with provider defaults. Spring AI / LC4j / Built-in set {@code prompt_cache_key}. */
        CONSERVATIVE,
        /** Opt-in with maximum-reuse intent. Same wire as CONSERVATIVE today; reserved for future
         *  ephemeral / long-TTL markers on Anthropic / Gemini. */
        AGGRESSIVE
    }

    public CacheHint {
        if (policy == null) {
            policy = CachePolicy.NONE;
        }
        cacheKey = cacheKey == null ? Optional.empty() : cacheKey;
        ttl = ttl == null ? Optional.empty() : ttl;
    }

    public static CacheHint none() {
        return new CacheHint(CachePolicy.NONE, Optional.empty(), Optional.empty());
    }

    public static CacheHint conservative() {
        return new CacheHint(CachePolicy.CONSERVATIVE, Optional.empty(), Optional.empty());
    }

    public static CacheHint conservative(String cacheKey) {
        return new CacheHint(CachePolicy.CONSERVATIVE, Optional.ofNullable(cacheKey), Optional.empty());
    }

    public static CacheHint aggressive(String cacheKey) {
        return new CacheHint(CachePolicy.AGGRESSIVE, Optional.ofNullable(cacheKey), Optional.empty());
    }

    /** True when the caller has opted in — i.e. policy is not {@link CachePolicy#NONE}. */
    public boolean enabled() {
        return policy != CachePolicy.NONE;
    }

    /**
     * Read the hint from an {@link AgentExecutionContext}. Returns
     * {@link #none()} when the metadata slot is unset or carries a non-hint
     * value — the adapter path is allocation-free for the common no-cache
     * case.
     */
    public static CacheHint from(AgentExecutionContext context) {
        if (context == null || context.metadata() == null) {
            return none();
        }
        var raw = context.metadata().get(METADATA_KEY);
        return raw instanceof CacheHint hint ? hint : none();
    }

    /**
     * Resolve a stable cache key for this hint, falling back to the context's
     * {@code sessionId} when the caller did not supply one. Returns an empty
     * Optional when no key can be derived (no hint, no session).
     */
    public Optional<String> resolvedKey(AgentExecutionContext context) {
        if (!enabled()) {
            return Optional.empty();
        }
        if (cacheKey.isPresent() && !cacheKey.get().isBlank()) {
            return cacheKey;
        }
        if (context != null && context.sessionId() != null && !context.sessionId().isBlank()) {
            return Optional.of(context.sessionId());
        }
        return Optional.empty();
    }

    /**
     * The single source of truth for whether the configured LLM endpoint is
     * known to tolerate the OpenAI extension field {@code prompt_cache_key}
     * under {@link PromptCacheKeyMode#AUTO}. This allow-list is shared by both
     * realization sites — the Built-in {@link OpenAiCompatibleClient} (which
     * delegates here from {@code autoSupportsPromptCacheKey()}) and the
     * LangChain4j / Spring AI adapters — so AUTO behavior is identical across
     * the Built-in and framework runtimes (Mode Parity, Correctness Invariant
     * #7).
     *
     * <p><strong>Allow-list, default-DENY.</strong> Returns {@code true} only
     * for endpoints empirically known to honor or gracefully ignore the field:
     * {@code api.openai.com}, Azure OpenAI ({@code .openai.azure.com}), and
     * loopback ({@code localhost} / {@code 127.0.0.1}). Every other host —
     * including {@code null}/blank URLs — returns {@code false}.</p>
     *
     * <p>Default-deny is the safe policy across arbitrary OpenAI-compatible
     * endpoints. A strict OpenAI-compat proxy that rejects unknown fields
     * (Gemini's {@code generativelanguage.googleapis.com} surface returns
     * HTTP 400 {@code "Unknown name 'prompt_cache_key'"}; other gateways may
     * do the same) would break the whole request if we emitted the field
     * speculatively. Suppressing on unknown hosts costs only the prompt-cache
     * optimization — {@link org.atmosphere.ai.cache.ResponseCache} still
     * short-circuits identical requests at the pipeline level — while emitting
     * speculatively risks a hard request failure. Callers who run against an
     * OpenAI-compatible endpoint NOT on this list and know it tolerates the
     * field can force emission anywhere via
     * {@link PromptCacheKeyMode#ENABLED}.</p>
     *
     * @param endpointUrl the configured base URL (may be {@code null}/blank)
     * @return {@code true} iff the host is a known-supported endpoint
     */
    public static boolean endpointAcceptsPromptCacheKey(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return false;
        }
        String url = endpointUrl.toLowerCase(java.util.Locale.ROOT);
        return url.contains("api.openai.com")
                || url.contains(".openai.azure.com")
                || url.contains("localhost")
                || url.contains("127.0.0.1");
    }
}
