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
 * ignored on ADK. Koog 0.7.3 only ships Bedrock cache-control variants, so
 * Koog also logs and ignores until an OpenAI/Anthropic variant ships upstream.
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
     * True when the configured LLM endpoint is known to tolerate the OpenAI
     * extension field {@code prompt_cache_key}. OpenAI proper accepts it;
     * most OpenAI-compat proxies (Groq, Together, vLLM, Ollama) silently
     * ignore unknown fields. Gemini's OpenAI-compat surface enforces strict
     * JSON schema validation and rejects unknown fields with HTTP 400, so
     * adapters MUST suppress {@code prompt_cache_key} when targeting Gemini.
     *
     * <p>Returns {@code true} for {@code null}/blank URLs (assumed OpenAI by
     * default) and any URL not in the known-incompatible list, preserving
     * the cache-hint behavior for all OpenAI-compatible providers except the
     * ones empirically observed to reject unknown fields.</p>
     */
    public static boolean endpointAcceptsPromptCacheKey(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return true;
        }
        String url = endpointUrl.toLowerCase(java.util.Locale.ROOT);
        // Gemini OpenAI-compat: strict schema, rejects unknown fields.
        if (url.contains("generativelanguage.googleapis.com")) {
            return false;
        }
        return true;
    }
}
